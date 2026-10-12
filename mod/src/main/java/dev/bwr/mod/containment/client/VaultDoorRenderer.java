package dev.bwr.mod.containment.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.bwr.mod.BwrMod;
import dev.bwr.mod.client.MachineMeshCache;
import dev.bwr.mod.containment.ContainmentVaultDoorBlock;
import dev.bwr.mod.containment.ContainmentVaultDoorBlockEntity;
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

/** Root-only GPU geometry; only a pose matrix changes as the leaf swings. */
@EventBusSubscriber(modid=BwrMod.MOD_ID,value=Dist.CLIENT)
public final class VaultDoorRenderer implements BlockEntityRenderer<ContainmentVaultDoorBlockEntity> {
    public static final ModelResourceLocation FRAME3=model("vault_3_frame"), LEAF3=model("vault_3_leaf");
    public static final ModelResourceLocation FRAME4=model("vault_4_frame"), LEAF4=model("vault_4_leaf");
    private static ModelResourceLocation model(String name){return ModelResourceLocation.standalone(
            ResourceLocation.fromNamespaceAndPath("bwr","block/containment/"+name));}
    public VaultDoorRenderer(BlockEntityRendererProvider.Context ignored){}
    @SubscribeEvent public static void models(ModelEvent.RegisterAdditional event){
        event.register(FRAME3);event.register(LEAF3);event.register(FRAME4);event.register(LEAF4);
    }
    @SubscribeEvent public static void renderers(EntityRenderersEvent.RegisterRenderers event){
        event.registerBlockEntityRenderer(BwrBlockEntities.CONTAINMENT_VAULT_DOOR.get(),VaultDoorRenderer::new);
    }
    @Override public void render(ContainmentVaultDoorBlockEntity be,float partial,PoseStack pose,MultiBufferSource buffers,int light,int overlay){
        int size=((ContainmentVaultDoorBlock)be.getBlockState().getBlock()).size();
        int angle=switch(be.getBlockState().getValue(ContainmentVaultDoorBlock.FACING)){
            case EAST->90;case SOUTH->180;case WEST->270;default->0;};
        pose.pushPose();pose.translate(.5,0,.5);pose.mulPose(Axis.YP.rotationDegrees(-angle));pose.translate(-.5,0,-.5);
        MachineMeshCache.model(size==3?FRAME3:FRAME4,pose,buffers,light,overlay);
        pose.pushPose();pose.translate(.17,0,.40);
        pose.mulPose(Axis.YP.rotationDegrees((float)(-82*be.swing(partial))));
        pose.translate(-.17,0,-.40);
        MachineMeshCache.model(size==3?LEAF3:LEAF4,pose,buffers,light,overlay);
        pose.popPose();pose.popPose();
    }
    @Override public AABB getRenderBoundingBox(ContainmentVaultDoorBlockEntity be){
        var pos=be.getBlockPos();int size=((ContainmentVaultDoorBlock)be.getBlockState().getBlock()).size();
        return new AABB(pos).inflate(size+1,size+1,size+1);
    }
    @Override public int getViewDistance(){return 96;}
    @Override public boolean shouldRenderOffScreen(ContainmentVaultDoorBlockEntity be){return true;}
    @Override public boolean shouldRender(ContainmentVaultDoorBlockEntity be,Vec3 camera){
        return MachineMeshCache.withinDistance(getRenderBoundingBox(be),camera,getViewDistance());
    }
}
