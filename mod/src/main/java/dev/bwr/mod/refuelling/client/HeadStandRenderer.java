package dev.bwr.mod.refuelling.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.bwr.mod.BwrMod;
import dev.bwr.mod.client.MachineMeshCache;
import dev.bwr.mod.reactor.client.ReactorVesselRenderer;
import dev.bwr.mod.refuelling.HeadStandBlockEntity;
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
import org.joml.Matrix3f;

/**
 * Draws the vessel head a head stand holds: the cradle ring and legs under its flange, sized to the head, and the head itself lying
 * on it, as it was on its vessel (the vessel's own cached head mesh, without the nuts). The stand block is the middle of the head.
 */
@EventBusSubscriber(modid = BwrMod.MOD_ID, value = Dist.CLIENT)
public final class HeadStandRenderer implements BlockEntityRenderer<HeadStandBlockEntity> {

    private record Cradle(String name) {
    }

    public HeadStandRenderer(BlockEntityRendererProvider.Context context) {
    }

    @SubscribeEvent
    public static void renderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(BwrBlockEntities.HEAD_STAND.get(), HeadStandRenderer::new);
    }

    @Override
    public void render(HeadStandBlockEntity stand, float partial, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        var vessel = stand.profile();
        if (vessel == null) {
            return;
        }
        // the cradle: a unit ring and four legs scaled to the flange's radius and the cradle's height (the normals stay unscaled)
        pose.pushPose();
        pose.translate(.5, HeadStandBlockEntity.TOP, .5);
        Matrix3f normal = new Matrix3f(pose.last().normal());
        pose.scale((float) (.47 * vessel.width), (float) HeadStandBlockEntity.CRADLE_HEIGHT, (float) (.47 * vessel.depth));
        pose.last().normal().set(normal);
        MachineMeshCache.draw(new Cradle("stand_cradle"), b -> b.part(BuildingCraneRenderer.model("stand_cradle"), 0, 0, 0, 1, 1, 1), pose, buffers, light, overlay);
        pose.popPose();
        // the head on it
        pose.pushPose();
        pose.translate(.5, HeadStandBlockEntity.REST, .5);
        ReactorVesselRenderer.drawHead(vessel, pose, buffers, light, overlay);
        pose.popPose();
    }

    @Override
    public AABB getRenderBoundingBox(HeadStandBlockEntity stand) {
        var vessel = stand.profile();
        if (vessel == null) {
            return new AABB(stand.getBlockPos());
        }
        double rx = vessel.width / 2.0 + 1, rz = vessel.depth / 2.0 + 1;
        var p = stand.getBlockPos();
        return new AABB(p.getX() + .5 - rx, p.getY(), p.getZ() + .5 - rz, p.getX() + .5 + rx, p.getY() + 2 + vessel.headRise, p.getZ() + .5 + rz);
    }

    @Override
    public boolean shouldRenderOffScreen(HeadStandBlockEntity stand) {
        return true;
    }

    @Override
    public int getViewDistance() {
        return 128;
    }

    @Override
    public boolean shouldRender(HeadStandBlockEntity stand, Vec3 eye) {
        return stand.holdsHead() && getRenderBoundingBox(stand).distanceToSqr(eye) < 128.0 * 128.0;
    }
}
