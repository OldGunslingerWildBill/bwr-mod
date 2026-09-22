package dev.bwr.mod.reactor.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.bwr.mod.BwrMod;
import dev.bwr.mod.reactor.*;
import dev.bwr.mod.registry.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.blockentity.*;
import net.minecraft.client.resources.model.*;
import net.minecraft.core.*;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.*;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.*;
import net.neoforged.neoforge.client.model.BakedModelWrapper;
import net.neoforged.neoforge.client.model.data.*;
import java.util.*;

/** One renderer per reactor. Blender pieces scale to the existing construction envelope. */
@EventBusSubscriber(modid=BwrMod.MOD_ID,value=Dist.CLIENT)
public final class ReactorVesselRenderer implements BlockEntityRenderer<ReactorControllerBlockEntity> {
    public static final String[] PARTS={"barrel","bottom","flange","head","weld","stud","spool","collar"};
    private static final ModelProperty<Boolean> HIDDEN=new ModelProperty<>();
    private static final Map<String,List<BakedQuad>> QUADS=new HashMap<>();
    public static ModelResourceLocation model(String part) {
        return ModelResourceLocation.standalone(BwrMod.id("block/reactor_vessel/"+part+"/body"));
    }
    public ReactorVesselRenderer(BlockEntityRendererProvider.Context context) {}
    @SubscribeEvent public static void models(ModelEvent.RegisterAdditional e) {for(var p:PARTS)e.register(model(p));}
    @SubscribeEvent public static void renderers(EntityRenderersEvent.RegisterRenderers e) {
        e.registerBlockEntityRenderer(BwrBlockEntities.REACTOR_CONTROLLER.get(),ReactorVesselRenderer::new);
    }
    @SubscribeEvent public static void baked(ModelEvent.ModifyBakingResult e) {
        QUADS.clear();
        for(var state:BwrBlocks.REACTOR_VESSEL.get().getStateDefinition().getPossibleStates()) {
            var key=BlockModelShaper.stateToModelLocation(state);var original=e.getModels().get(key);
            if(original!=null)e.getModels().put(key,new ShellModel(original));
        }
    }
    private static final class ShellModel extends BakedModelWrapper<BakedModel> {
        ShellModel(BakedModel model) {super(model);}
        @Override public ModelData getModelData(BlockAndTintGetter world,BlockPos pos,BlockState state,ModelData data) {
            return data.derive().with(HIDDEN,VesselAppearance.hides(Minecraft.getInstance().level,pos)).build();
        }
        @Override public List<BakedQuad> getQuads(BlockState state,Direction side,RandomSource random,ModelData data,RenderType type) {
            return Boolean.TRUE.equals(data.get(HIDDEN))?List.of():super.getQuads(state,side,random,data,type);
        }
    }
    @Override public void render(ReactorControllerBlockEntity be,float partial,PoseStack pose,MultiBufferSource buffers,int light,int overlay) {
        var e=be.clientVesselEnvelope();if(e==null || !be.clientFormed())return;
        float w=e.width(),d=e.depth(),h=e.height();
        float headHeight=Math.min(h*.22f,Math.min(w,d)*.18f);
        float flangeY=h-headHeight-.40f;
        double cx=e.min().getX()+w/2,cz=e.min().getZ()+d/2;
        if(be.getLevel()!=null)light=LevelRenderer.getLightColor(be.getLevel(),BlockPos.containing(cx,e.max().getY()+1,cz));
        pose.pushPose();pose.translate(cx-be.getBlockPos().getX(),e.min().getY()-be.getBlockPos().getY(),cz-be.getBlockPos().getZ());
        part("bottom",pose,buffers,light,overlay,0,0,0,w,1,d);
        part("barrel",pose,buffers,light,overlay,0,1.2,0,w,flangeY-1.15f,d);
        part("flange",pose,buffers,light,overlay,0,flangeY,0,w,1,d);
        if(be.vesselState().canHoldPressure()) {
            part("head",pose,buffers,light,overlay,0,h-headHeight,0,w,headHeight/1.65f,d);
            int studs=Math.min(80,Math.max(24,Math.round((w+d)*2)));
            for(int i=0;i<studs;i++) {double a=i*Math.PI*2/studs;
                part("stud",pose,buffers,light,overlay,.472*w*Math.cos(a),flangeY,.472*d*Math.sin(a),1,1,1);
            }
        }
        for(double y=4;y<flangeY-.5;y+=4)part("weld",pose,buffers,light,overlay,0,y,0,w,1,d);
        // Adapt the curved barrel to actual player-selected wall blocks. Interfaces stay put.
        for(var port:e.ports()) {
            double x=port.getX()+.5-cx,z=port.getZ()+.5-cz,y=port.getY()+.5-e.min().getY();
            Direction face=port.getX()==e.min().getX()?Direction.WEST:port.getX()==e.max().getX()?Direction.EAST
                    :port.getZ()==e.min().getZ()?Direction.NORTH:port.getZ()==e.max().getZ()?Direction.SOUTH
                    :port.getY()==e.min().getY()?Direction.DOWN:Direction.UP;
            double radiusX=.46*w,radiusZ=.46*d;
            double startX=x,startY=y,startZ=z;
            double r=Math.sqrt(x*x/(radiusX*radiusX)+z*z/(radiusZ*radiusZ));
            if(face.getAxis().isHorizontal()) {
                // Include the dished heads and off-centre/corner ports. Project
                // radially onto the ellipse, then angle the Blender spool to the interface.
                double section=y<1.2?Math.sqrt(Math.max(.04,1-Math.pow((1.2-y)/1.02,2)))
                        :y>h-headHeight?Math.sqrt(Math.max(.04,1-Math.pow((y-(h-headHeight))/headHeight,2))):1;
                startX=x*section*.98/Math.max(.01,r);startZ=z*section*.98/Math.max(.01,r);
            } else {
                double scale=Math.min(1,.94/Math.max(.01,r));startX*=scale;startZ*=scale;r*=scale;
                startY=face==Direction.UP?h-headHeight+headHeight*Math.sqrt(1-r*r)-.08
                        :1.2-1.02*Math.sqrt(1-r*r)+.08;
            }
            var vector=new org.joml.Vector3f((float)(x-face.getStepX()*.45-startX),
                    (float)(y-face.getStepY()*.45-startY),(float)(z-face.getStepZ()*.45-startZ));
            float length=vector.length();if(length<.02)continue;
            pose.pushPose();pose.translate(startX,startY,startZ);
            pose.mulPose(new org.joml.Quaternionf().rotationTo(new org.joml.Vector3f(0,0,1),vector.normalize()));
            part("spool",pose,buffers,light,overlay,0,0,0,1,1,(float)length);
            part("collar",pose,buffers,light,overlay,0,0,.08,1,1,1);
            pose.popPose();
        }
        pose.popPose();
        var player=Minecraft.getInstance().player;
        if(player!=null && (player.getMainHandItem().is(BwrBlocks.REACTOR_VESSEL.get().asItem())
                || player.getOffhandItem().is(BwrBlocks.REACTOR_VESSEL.get().asItem()))) {
            var box=getRenderBoundingBox(be).move(-be.getBlockPos().getX(),-be.getBlockPos().getY(),-be.getBlockPos().getZ());
            LevelRenderer.renderLineBox(pose,buffers.getBuffer(RenderType.lines()),box,.3f,.75f,1f,.65f);
        }
    }
    private static List<BakedQuad> quads(String name) {
        return QUADS.computeIfAbsent(name,key->{
            var m=Minecraft.getInstance().getModelManager().getModel(model(key));var result=new ArrayList<BakedQuad>();
            var random=RandomSource.create(42);
            for(int i=0;i<7;i++){random.setSeed(42);result.addAll(m.getQuads(null,i<6?Direction.values()[i]:null,random,ModelData.EMPTY,RenderType.solid()));}
            return List.copyOf(result);
        });
    }
    private static void part(String name,PoseStack pose,MultiBufferSource buffers,int light,int overlay,double x,double y,double z,float sx,float sy,float sz) {
        pose.pushPose();pose.translate(x,y,z);pose.scale(sx,sy,sz);
        var out=buffers.getBuffer(Sheets.cutoutBlockSheet());
        for(var q:quads(name))out.putBulkData(pose.last(),q,1,1,1,1,light,overlay,true);
        pose.popPose();
    }
    @Override public AABB getRenderBoundingBox(ReactorControllerBlockEntity be) {
        var e=be.clientVesselEnvelope();return e==null?new AABB(be.getBlockPos()):new AABB(Vec3.atLowerCornerOf(e.min()),Vec3.atLowerCornerOf(e.max().offset(1,1,1)));
    }
    @Override public int getViewDistance(){return 256;}
    @Override public boolean shouldRenderOffScreen(ReactorControllerBlockEntity be){return true;}
    @Override public boolean shouldRender(ReactorControllerBlockEntity be,Vec3 camera) {
        return be.clientFormed() && be.clientVesselEnvelope()!=null && camera.distanceToSqr(getRenderBoundingBox(be).getCenter())<256*256;
    }
}
