package dev.bwr.mod.condenser.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.bwr.mod.BwrMod;
import dev.bwr.mod.client.MachineMeshCache;
import dev.bwr.mod.condenser.*;
import dev.bwr.mod.registry.BwrBlockEntities;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.blockentity.*;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.*;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.*;

/** The complete exterior is rendered once per machine, not once for each occupied cell. */
@EventBusSubscriber(modid=BwrMod.MOD_ID,value=Dist.CLIENT)
public class CondenserRenderer implements BlockEntityRenderer<CondenserBlockEntity> {
    public static final ModelResourceLocation MODEL=ModelResourceLocation.standalone(ResourceLocation.fromNamespaceAndPath("bwr","block/condenser/body"));
    public static final ModelResourceLocation LEGACY_MODEL=ModelResourceLocation.standalone(ResourceLocation.fromNamespaceAndPath("bwr","block/condenser/legacy/body"));
    public static final ModelResourceLocation COMPACT_V2_MODEL=ModelResourceLocation.standalone(ResourceLocation.fromNamespaceAndPath("bwr","block/condenser/compact_v2/body"));
    public static final ModelResourceLocation WIDE_V3_MODEL=ModelResourceLocation.standalone(ResourceLocation.fromNamespaceAndPath("bwr","block/condenser/wide_v3/body"));
    public CondenserRenderer(BlockEntityRendererProvider.Context context){}
    @SubscribeEvent public static void models(ModelEvent.RegisterAdditional event){event.register(MODEL);event.register(LEGACY_MODEL);event.register(COMPACT_V2_MODEL);event.register(WIDE_V3_MODEL);}
    @SubscribeEvent public static void renderers(EntityRenderersEvent.RegisterRenderers event){event.registerBlockEntityRenderer(BwrBlockEntities.CONDENSER.get(),CondenserRenderer::new);}
    @Override public void render(CondenserBlockEntity be,float partial,PoseStack pose,MultiBufferSource buffers,int light,int overlay){
        if(!be.getBlockState().getValue(CondenserBlock.CONTROLLER))return;
        var layout=be.layout();
        var model=layout==CondenserLayout.LEGACY?LEGACY_MODEL:layout==CondenserLayout.COMPACT_V2?COMPACT_V2_MODEL:layout==CondenserLayout.WIDE_V3?WIDE_V3_MODEL:MODEL;var c=layout.controller;
        float angle=switch(be.getBlockState().getValue(CondenserBlock.FACING)){case EAST->90;case SOUTH->180;case WEST->270;default->0;};
        pose.pushPose();pose.translate(.5,0,.5);pose.mulPose(Axis.YP.rotationDegrees(-angle));pose.translate(-.5-c.getX(),-c.getY(),-.5-c.getZ());
        MachineMeshCache.model(model,pose,buffers,light,overlay);
        pose.popPose();
    }
    @Override public AABB getRenderBoundingBox(CondenserBlockEntity be){return be.layout().bounds(be.getBlockPos(),be.getBlockState().getValue(CondenserBlock.FACING));}
    @Override public int getViewDistance(){return 128;}
    @Override public boolean shouldRenderOffScreen(CondenserBlockEntity be){return be.getBlockState().getValue(CondenserBlock.CONTROLLER);}
    @Override public boolean shouldRender(CondenserBlockEntity be,Vec3 camera){
        if(!be.getBlockState().getValue(CondenserBlock.CONTROLLER))return false;
        return MachineMeshCache.withinDistance(getRenderBoundingBox(be),camera,getViewDistance());
    }
}
