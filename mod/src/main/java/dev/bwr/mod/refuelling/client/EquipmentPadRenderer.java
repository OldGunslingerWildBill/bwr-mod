package dev.bwr.mod.refuelling.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.bwr.mod.BwrMod;
import dev.bwr.mod.reactor.VesselInternal;
import dev.bwr.mod.reactor.client.ReactorVesselRenderer;
import dev.bwr.mod.refuelling.EquipmentPadBlockEntity;
import dev.bwr.mod.registry.BwrBlockEntities;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/** Draws the assembly an equipment pad holds, standing on the pad as it stood in its vessel (the vessel's own cached meshes). */
@EventBusSubscriber(modid = BwrMod.MOD_ID, value = Dist.CLIENT)
public final class EquipmentPadRenderer implements BlockEntityRenderer<EquipmentPadBlockEntity> {
    public EquipmentPadRenderer(BlockEntityRendererProvider.Context context) {
    }

    @SubscribeEvent
    public static void renderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(BwrBlockEntities.EQUIPMENT_PAD.get(), EquipmentPadRenderer::new);
    }

    @Override
    public void render(EquipmentPadBlockEntity pad, float partial, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        var which = pad.stored();
        var vessel = pad.profile();
        if (which == null || vessel == null) return;
        double bottom = which == VesselInternal.DRYER ? vessel.dryerBottom() : vessel.separatorBottom();
        pose.pushPose();
        pose.translate(.5, EquipmentPadBlockEntity.TOP - bottom, .5);
        if (which == VesselInternal.DRYER) ReactorVesselRenderer.drawDryer(vessel, pose, buffers, light, overlay);
        else ReactorVesselRenderer.drawSeparators(vessel, pose, buffers, light, overlay);
        pose.popPose();
    }

    @Override
    public AABB getRenderBoundingBox(EquipmentPadBlockEntity pad) {
        var vessel = pad.profile();
        if (vessel == null) return new AABB(pad.getBlockPos());
        double r = Math.max(vessel.width, vessel.depth) / 2.0 + 1;
        var p = pad.getBlockPos();
        return new AABB(p.getX() - r, p.getY(), p.getZ() - r, p.getX() + 1 + r, p.getY() + 1 + pad.assemblyHeight() + 1, p.getZ() + 1 + r);
    }

    @Override
    public boolean shouldRenderOffScreen(EquipmentPadBlockEntity pad) {
        return true;
    }

    @Override
    public int getViewDistance() {
        return 128;
    }

    @Override
    public boolean shouldRender(EquipmentPadBlockEntity pad, Vec3 eye) {
        return pad.stored() != null && getRenderBoundingBox(pad).distanceToSqr(eye) < 128.0 * 128.0;
    }
}
