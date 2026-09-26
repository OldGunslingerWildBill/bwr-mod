package dev.bwr.mod.cooling.client;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.bwr.mod.BwrMod;
import dev.bwr.mod.client.MachineMeshCache;
import dev.bwr.mod.cooling.*;
import dev.bwr.core.turbine.CoolingWaterUnit.Design;
import dev.bwr.mod.registry.BwrBlockEntities;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.blockentity.*;
import net.minecraft.client.resources.model.*;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.*;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.*;

@EventBusSubscriber(modid=BwrMod.MOD_ID,value=Dist.CLIENT)
public class CoolingRenderer implements BlockEntityRenderer<CoolingBlockEntity> {
    public static ModelResourceLocation model(Design d){return ModelResourceLocation.standalone(BwrMod.id("block/cooling/"+d.id+"/body"));}
    public static ModelResourceLocation previous(Design d){return ModelResourceLocation.standalone(BwrMod.id("block/cooling/"+d.id+"_v2/body"));}
    public static final ModelResourceLocation FAN=ModelResourceLocation.standalone(BwrMod.id("block/cooling/fan/body"));
    public CoolingRenderer(BlockEntityRendererProvider.Context c){}
    @SubscribeEvent public static void models(ModelEvent.RegisterAdditional e){for(var d:Design.values()){e.register(model(d));if(CoolingLayout.revisedPump(d))e.register(previous(d));}e.register(FAN);}
    @SubscribeEvent public static void renderers(EntityRenderersEvent.RegisterRenderers e){e.registerBlockEntityRenderer(BwrBlockEntities.COOLING.get(),CoolingRenderer::new);}
    @Override public void render(CoolingBlockEntity be,float partial,PoseStack pose,MultiBufferSource buffers,int light,int overlay){
        if(!be.getBlockState().getValue(CoolingBlock.CONTROLLER))return;var layout=be.layout();var c=layout.controller;
        // The controller is buried in the basin/skid. Its skylight alone makes
        // the entire tall exterior black even outdoors in daylight.
        if(be.getLevel()!=null){var l=be.getLevel();var top=be.getBlockPos().above(layout.size.getY());
            light=LightTexture.pack(Math.max(l.getBrightness(net.minecraft.world.level.LightLayer.BLOCK,be.getBlockPos()),l.getBrightness(net.minecraft.world.level.LightLayer.BLOCK,top)),l.getBrightness(net.minecraft.world.level.LightLayer.SKY,top));}
        float angle=switch(be.getBlockState().getValue(CoolingBlock.FACING)){case EAST->90;case SOUTH->180;case WEST->270;default->0;};
        pose.pushPose();pose.translate(.5,0,.5);pose.mulPose(Axis.YP.rotationDegrees(-angle));pose.translate(-.5-c.getX(),-c.getY(),-.5-c.getZ());
        draw(be.previousPumpModel()?previous(be.design()):model(be.design()),pose,buffers,light,overlay);
        for(var pivot:layout.rotors){
            pose.pushPose();pose.translate(pivot.x,pivot.y,pivot.z);
            pose.mulPose(Axis.YP.rotationDegrees(net.minecraft.util.Mth.lerp(partial,be.previousFanAngle,be.fanAngle)));draw(FAN,pose,buffers,light,overlay);pose.popPose();
        }pose.popPose();
    }
    private static void draw(ModelResourceLocation id,PoseStack pose,MultiBufferSource buffers,int light,int overlay){
        MachineMeshCache.model(id,pose,buffers,light,overlay);
    }
    @Override public AABB getRenderBoundingBox(CoolingBlockEntity be){return be.layout().bounds(be.getBlockPos(),be.getBlockState().getValue(CoolingBlock.FACING));}
    @Override public int getViewDistance(){return 192;}
    // Register the full machine independently of its controller's chunk section.
    // NeoForge still frustum-culls this finite bounding box, including global BERs.
    @Override public boolean shouldRenderOffScreen(CoolingBlockEntity be){return be.getBlockState().getValue(CoolingBlock.CONTROLLER);}
    @Override public boolean shouldRender(CoolingBlockEntity be,Vec3 camera){
        return be.getBlockState().getValue(CoolingBlock.CONTROLLER)&&MachineMeshCache.withinDistance(getRenderBoundingBox(be),camera,getViewDistance());
    }
}
