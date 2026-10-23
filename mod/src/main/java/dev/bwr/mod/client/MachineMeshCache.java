package dev.bwr.mod.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import dev.bwr.mod.BwrMod;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.*;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.event.GameShuttingDownEvent;
import net.neoforged.neoforge.event.level.LevelEvent;

/** Immutable local-space meshes. Placement, normal transforms and light stay GPU uniforms.
 * Cache keys describe geometry, never a world or block entity. All GL ownership is on the render thread. */
@EventBusSubscriber(modid=BwrMod.MOD_ID,value=Dist.CLIENT)
public final class MachineMeshCache {
    // One reactor with the largest (21 x 21) core is about 90 MB of meshes in the four entries it draws every frame. With a smaller
    // budget trim() evicts one of them each frame and the core is uploaded again every frame; the budget must hold the largest reactor.
    private static final long MAX_BYTES=256L*1024*1024;
    private static final int MAX_ENTRIES=128;
    private static final LinkedHashMap<Object,Mesh> MESHES=new LinkedHashMap<>(16,.75f,true);
    private static ShaderInstance shader;
    private static long bytes,uploads,draws;
    private record Mesh(VertexBuffer buffer,long bytes) implements AutoCloseable {
        @Override public void close(){if(buffer!=null)buffer.close();}
    }
    private MachineMeshCache(){}

    @SubscribeEvent public static void shaders(RegisterShadersEvent event) throws IOException {
        event.registerShader(new ShaderInstance(event.getResourceProvider(),BwrMod.id("cached_machine"),DefaultVertexFormat.NEW_ENTITY),s->shader=s);
    }
    @SubscribeEvent public static void baked(ModelEvent.BakingCompleted event){clear();}
    @SubscribeEvent public static void unload(LevelEvent.Unload event){if(event.getLevel().isClientSide())clear();}
    @SubscribeEvent public static void shutdown(GameShuttingDownEvent event){clear();}
    public static void clear(){
        if(!RenderSystem.isOnRenderThread()){RenderSystem.recordRenderCall(MachineMeshCache::clear);return;}
        MESHES.values().forEach(Mesh::close);MESHES.clear();bytes=0;
    }
    /** Monotonic counters for the disposable client regression harness. */
    public static long uploadCount(){return uploads;}
    public static long drawCount(){return draws;}
    public static long cachedBytes(){return bytes;}
    public static int cachedMeshes(){return MESHES.size();}

    public static boolean withinDistance(AABB bounds,Vec3 camera,int distance){
        return camera.distanceToSqr(Math.clamp(camera.x,bounds.minX,bounds.maxX),
                Math.clamp(camera.y,bounds.minY,bounds.maxY),Math.clamp(camera.z,bounds.minZ,bounds.maxZ))<(double)distance*distance;
    }
    public static void model(ModelResourceLocation id,PoseStack pose,MultiBufferSource buffers,int light,int overlay){
        draw(id,b->b.model(id),pose,buffers,light,overlay);
    }
    public static void draw(Object key,Consumer<Builder> geometry,PoseStack pose,MultiBufferSource buffers,int light,int overlay){
        RenderSystem.assertOnRenderThread();
        // A shader pack replaces the programs of the render types, and Iris hands block entities its own wrapper instead of
        // a BufferSource. The wrapper used to fail the check below, so with a pack on every machine was rebuilt quad by
        // quad through Iris's extended vertex path each frame (about 120 ms for a reactor and its drives). It is drawn from
        // a buffer through the render type instead, so the pack's own program is the one that shades it.
        if(shader!=null&&isPackSource(buffers)){drawThroughRenderType(key,geometry,pose,light,overlay);return;}
        // Destruction/outline consumers must retain Minecraft's special vertex path.
        if(shader==null||!(buffers instanceof MultiBufferSource.BufferSource)){
            geometry.accept(new Builder(pose,buffers.getBuffer(Sheets.cutoutBlockSheet()),light,overlay));return;
        }
        var mesh=MESHES.get(key);
        if(mesh==null){
            mesh=compile(geometry,0,0);MESHES.put(key,mesh);bytes+=mesh.bytes();trim();
        }
        if(mesh.buffer()==null)return;
        var type=Sheets.cutoutBlockSheet();var previousShader=RenderSystem.getShader();
        type.setupRenderState();
        try{
            shader.safeGetUniform("ModelMat").set(pose.last().pose());
            shader.safeGetUniform("NormalMat").set(pose.last().normal());
            shader.safeGetUniform("LightUV").set(light&65535,(light>>>16)&65535);
            shader.safeGetUniform("OverlayUV").set(overlay&65535,(overlay>>>16)&65535);
            mesh.buffer().bind();
            mesh.buffer().drawWithShader(RenderSystem.getModelViewMatrix(),RenderSystem.getProjectionMatrix(),shader);
            draws++;
        }finally{
            VertexBuffer.unbind();type.clearRenderState();RenderSystem.setShader(()->previousShader);
        }
    }
    /** What a shader pack's block entity pass is given: Iris's wrapper around the real buffer source (named, never linked). */
    static boolean isPackSource(MultiBufferSource buffers){
        return !(buffers instanceof MultiBufferSource.BufferSource)&&buffers.getClass().getName().startsWith("net.irisshaders.");
    }
    /** The part of a variant key that is not the geometry: the light and overlay the vertices carry. */
    private record Lit(Object key,int light,int overlay){}
    /**
     * The same mesh, with the light of this block and the overlay baked into its vertices (one buffer per geometry, light and
     * overlay), drawn through the render type's own program with the pose as the model-view matrix. The program is the
     * shader pack's when one is in use, so the mod's cached_machine program is never involved.
     */
    private static void drawThroughRenderType(Object key,Consumer<Builder> geometry,PoseStack pose,int light,int overlay){
        var lit=new Lit(key,light,overlay);
        var mesh=MESHES.get(lit);
        if(mesh==null){
            mesh=compile(geometry,light,overlay);MESHES.put(lit,mesh);bytes+=mesh.bytes();trim();
        }
        if(mesh.buffer()==null)return;
        var type=Sheets.cutoutBlockSheet();
        type.setupRenderState();
        try{
            var modelView=new org.joml.Matrix4f(RenderSystem.getModelViewMatrix()).mul(pose.last().pose());
            mesh.buffer().bind();
            mesh.buffer().drawWithShader(modelView,RenderSystem.getProjectionMatrix(),RenderSystem.getShader());
            draws++;
        }finally{
            VertexBuffer.unbind();type.clearRenderState();
        }
    }
    private static void trim(){
        var it=MESHES.entrySet().iterator();
        while(MESHES.size()>1&&(bytes>MAX_BYTES||MESHES.size()>MAX_ENTRIES)){
            var old=it.next().getValue();bytes-=old.bytes();old.close();it.remove();
        }
    }
    private static Mesh compile(Consumer<Builder> geometry,int light,int overlay){
        try(var storage=new ByteBufferBuilder(256*1024)){
            var vertices=new BufferBuilder(storage,VertexFormat.Mode.QUADS,DefaultVertexFormat.NEW_ENTITY);
            geometry.accept(new Builder(new PoseStack(),vertices,light,overlay));
            var data=vertices.build();
            if(data==null)return new Mesh(null,0);
            long size=(long)data.drawState().vertexCount()*DefaultVertexFormat.NEW_ENTITY.getVertexSize();
            var buffer=new VertexBuffer(VertexBuffer.Usage.STATIC);
            try{buffer.bind();buffer.upload(data);uploads++;return new Mesh(buffer,size);}
            catch(RuntimeException|Error ex){buffer.close();throw ex;}
            finally{VertexBuffer.unbind();}
        }
    }
    /** Builds a complete stationary assembly once, or the exceptional damage-overlay pass. */
    public static final class Builder {
        public final PoseStack pose;
        private final VertexConsumer out;
        private final int overlay;
        private final RandomSource random=RandomSource.create(42);
        private final float[] brightness={1,1,1,1};
        private final int[] lights;
        private Builder(PoseStack pose,VertexConsumer out,int light,int overlay){
            this.pose=pose;this.out=out;this.overlay=overlay;this.lights=new int[]{light,light,light,light};
        }
        public void model(ModelResourceLocation id){
            var model=Minecraft.getInstance().getModelManager().getModel(id);
            for(int side=0;side<7;side++){
                random.setSeed(42);
                for(var quad:model.getQuads(null,side<6?Direction.values()[side]:null,random,ModelData.EMPTY,RenderType.solid()))
                    out.putBulkData(pose.last(),quad,brightness,1,1,1,1,lights,overlay,true);
            }
        }
        /** One vertex of procedural geometry, in the current pose, lit as the rest of the assembly and tinted by r, g, b. */
        public void vertex(double x,double y,double z,float u,float v,float nx,float ny,float nz,float r,float g,float b){
            var last=pose.last();
            out.addVertex(last,(float)x,(float)y,(float)z).setColor(r,g,b,1f).setUv(u,v).setOverlay(overlay).setLight(lights[0]).setNormal(last,nx,ny,nz);
        }
        public void part(ModelResourceLocation id,double x,double y,double z,float sx,float sy,float sz){
            pose.pushPose();pose.translate(x,y,z);pose.scale(sx,sy,sz);model(id);pose.popPose();
        }
    }
}
