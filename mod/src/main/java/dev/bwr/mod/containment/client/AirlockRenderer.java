package dev.bwr.mod.containment.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.bwr.mod.BwrMod;
import dev.bwr.mod.client.MachineMeshCache;
import dev.bwr.mod.containment.ContainmentAirlockBlock;
import dev.bwr.mod.containment.ContainmentAirlockBlockEntity;
import dev.bwr.mod.registry.BwrBlockEntities;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.ModelEvent;

@EventBusSubscriber(modid=BwrMod.MOD_ID,value=Dist.CLIENT)
public final class AirlockRenderer implements BlockEntityRenderer<ContainmentAirlockBlockEntity> {
    private static ModelResourceLocation model(String name){return ModelResourceLocation.standalone(
            ResourceLocation.fromNamespaceAndPath("bwr","block/containment/"+name));}
    public static final ModelResourceLocation FRAME=model("airlock_frame"),INNER=model("airlock_inner_leaf"),OUTER=model("airlock_outer_leaf");
    public AirlockRenderer(BlockEntityRendererProvider.Context ignored){}
    @SubscribeEvent public static void models(ModelEvent.RegisterAdditional event){event.register(FRAME);event.register(INNER);event.register(OUTER);}
    @SubscribeEvent public static void renderers(EntityRenderersEvent.RegisterRenderers event){
        event.registerBlockEntityRenderer(BwrBlockEntities.CONTAINMENT_AIRLOCK.get(),AirlockRenderer::new);
    }
    @Override public void render(ContainmentAirlockBlockEntity be,float partial,PoseStack pose,MultiBufferSource buffers,int light,int overlay){
        int angle=switch(be.getBlockState().getValue(ContainmentAirlockBlock.FACING)){
            case WEST->90;case NORTH->180;case EAST->270;default->0;};
        pose.pushPose();pose.translate(.5,0,.5);pose.mulPose(Axis.YP.rotationDegrees(-angle));pose.translate(-.5,0,-.5);
        MachineMeshCache.model(FRAME,pose,buffers,light,overlay);
        pose.pushPose();pose.translate(.11,0,0);
        if(be.innerOpen())pose.mulPose(Axis.YP.rotationDegrees(-82));
        pose.translate(-.11,0,0);MachineMeshCache.model(INNER,pose,buffers,light,overlay);pose.popPose();
        pose.pushPose();pose.translate(.11,0,2.5);
        if(be.outerOpen())pose.mulPose(Axis.YP.rotationDegrees(82));
        pose.translate(-.11,0,-2.5);MachineMeshCache.model(OUTER,pose,buffers,light,overlay);pose.popPose();
        pose.popPose();
    }
    @Override public AABB getRenderBoundingBox(ContainmentAirlockBlockEntity be){return new AABB(be.getBlockPos()).inflate(4);}
    @Override public int getViewDistance(){return 96;}
    @Override public boolean shouldRenderOffScreen(ContainmentAirlockBlockEntity be){return true;}
    @Override public boolean shouldRender(ContainmentAirlockBlockEntity be,Vec3 camera){
        return MachineMeshCache.withinDistance(getRenderBoundingBox(be),camera,getViewDistance());
    }
}
