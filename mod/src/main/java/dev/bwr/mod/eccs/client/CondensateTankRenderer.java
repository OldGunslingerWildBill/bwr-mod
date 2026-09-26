package dev.bwr.mod.eccs.client;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.bwr.mod.BwrMod;
import dev.bwr.mod.client.MachineMeshCache;
import dev.bwr.mod.eccs.*;
import dev.bwr.mod.registry.BwrBlockEntities;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.blockentity.*;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.*;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.*;

/** Reuses Blender components: shell scales, pipe bores and ladder rungs stay full-size. */
@EventBusSubscriber(modid=BwrMod.MOD_ID,value=Dist.CLIENT)
public final class CondensateTankRenderer implements BlockEntityRenderer<CondensateStorageTankBlockEntity> {
    private record Shape(int diameter,int height){}
    public static final String[] PARTS={"base","wall","roof","port","ladder"};
    public static ModelResourceLocation model(String part){return ModelResourceLocation.standalone(BwrMod.id("block/condensate_tank/"+part+"/body"));}
    public CondensateTankRenderer(BlockEntityRendererProvider.Context c){}
    @SubscribeEvent public static void models(ModelEvent.RegisterAdditional e){for(var p:PARTS)e.register(model(p));}
    @SubscribeEvent public static void renderers(EntityRenderersEvent.RegisterRenderers e){e.registerBlockEntityRenderer(BwrBlockEntities.CONDENSATE_STORAGE_TANK.get(),CondensateTankRenderer::new);}
    @Override public void render(CondensateStorageTankBlockEntity be,float partial,PoseStack pose,MultiBufferSource buffers,int light,int overlay){
        if(!be.assembled()||!be.getBlockState().getValue(CondensateStorageTankBlock.CONTROLLER)||!CondensateTankShape.valid(be.diameter,be.height))return;
        int d=be.diameter,h=be.height;
        if(be.getLevel()!=null)light=LevelRenderer.getLightColor(be.getLevel(),be.getBlockPos().above(h));
        pose.pushPose();pose.translate(.5,0,.5);
        MachineMeshCache.draw(new Shape(d,h),b->build(b,d,h),pose,buffers,light,overlay);
        pose.popPose();
    }
    private static void build(MachineMeshCache.Builder b,int d,int h){
        var pose=b.pose;
        b.part(model("base"),0,0,0,d,1,d);
        b.part(model("wall"),0,.18,0,d,h-1.48f,d);
        b.part(model("roof"),0,h-1.3,0,d,1,d);
        for(int i=0;i<4;i++){pose.pushPose();pose.mulPose(Axis.YP.rotationDegrees(i*90));b.part(model("port"),0,1.5,-d/2.0,1,1,1);pose.popPose();}
        double z=-Math.sqrt(Math.pow(.47*d,2)-.7*.7)-.05;
        for(int y=0;y<h-1;y++)b.part(model("ladder"),.7,y+.2,z,1,1,1);
    }
    @Override public AABB getRenderBoundingBox(CondensateStorageTankBlockEntity be){int half=be.diameter/2;return new AABB(Vec3.atLowerCornerOf(be.getBlockPos().offset(-half,0,-half)),Vec3.atLowerCornerOf(be.getBlockPos().offset(half+1,be.height,half+1))).inflate(.75);}
    @Override public int getViewDistance(){return 192;}
    @Override public boolean shouldRenderOffScreen(CondensateStorageTankBlockEntity be){return be.getBlockState().getValue(CondensateStorageTankBlock.CONTROLLER);}
    @Override public boolean shouldRender(CondensateStorageTankBlockEntity be,Vec3 camera){return be.assembled()&&be.getBlockState().getValue(CondensateStorageTankBlock.CONTROLLER)&&MachineMeshCache.withinDistance(getRenderBoundingBox(be),camera,getViewDistance());}
}
