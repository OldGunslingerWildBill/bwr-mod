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
    private static final long MAX_BYTES=96L*1024*1024;
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
        // Destruction/outline consumers must retain Minecraft's special vertex path.
        if(shader==null||!(buffers instanceof MultiBufferSource.BufferSource)){
            geometry.accept(new Builder(pose,buffers.getBuffer(Sheets.cutoutBlockSheet()),light,overlay));return;
        }
        var mesh=MESHES.get(key);
        if(mesh==null){
            mesh=compile(geometry);MESHES.put(key,mesh);bytes+=mesh.bytes();
            var it=MESHES.entrySet().iterator();
            while(MESHES.size()>1&&(bytes>MAX_BYTES||MESHES.size()>MAX_ENTRIES)){
                var old=it.next().getValue();bytes-=old.bytes();old.close();it.remove();
            }
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
    private static Mesh compile(Consumer<Builder> geometry){
        try(var storage=new ByteBufferBuilder(256*1024)){
            var vertices=new BufferBuilder(storage,VertexFormat.Mode.QUADS,DefaultVertexFormat.NEW_ENTITY);
            geometry.accept(new Builder(new PoseStack(),vertices,0,0));
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
        public void part(ModelResourceLocation id,double x,double y,double z,float sx,float sy,float sz){
            pose.pushPose();pose.translate(x,y,z);pose.scale(sx,sy,sz);model(id);pose.popPose();
        }
    }
}
