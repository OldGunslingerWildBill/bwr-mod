package dev.bwr.mod.refuelling.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.bwr.core.refuelling.BridgeMotion.Point;
import dev.bwr.mod.BwrMod;
import dev.bwr.mod.refuelling.BuildingCraneBlockEntity;
import dev.bwr.mod.refuelling.PlugSectionEntity;
import dev.bwr.mod.registry.BwrEntities;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/**
 * Draws a shield plug section hanging from the reactor building crane's hook: every block of the entity with its own model (tier, seam grooves,
 * anchors), at its place in the section, the pick point under the hook. While the crane that carries it is loaded the section is drawn from the
 * crane's own hook position (the same pose the hook block and slings are drawn from), so it never trails the hook; otherwise from the
 * entity's own, interpolated, position.
 */
@EventBusSubscriber(modid = BwrMod.MOD_ID, value = Dist.CLIENT)
public final class PlugSectionRenderer extends EntityRenderer<PlugSectionEntity> {
    private static final ResourceLocation NO_TEXTURE = ResourceLocation.withDefaultNamespace("textures/block/iron_block.png");

    public PlugSectionRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @SubscribeEvent
    public static void renderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(BwrEntities.PLUG_SECTION.get(), PlugSectionRenderer::new);
    }

    @Override
    public boolean shouldRender(PlugSectionEntity e, net.minecraft.client.renderer.culling.Frustum frustum, double x, double y, double z) {
        return frustum.isVisible(e.getBoundingBoxForCulling());
    }

    @Override
    public void render(PlugSectionEntity e, float yaw, float partial, PoseStack pose, MultiBufferSource buffers, int light) {
        pose.pushPose();
        Vec3 here = e.getPosition(partial);
        var level = e.level();
        if (level.getBlockEntity(e.cranePos()) instanceof BuildingCraneBlockEntity crane && crane.ownsPlugEntity(e.getUUID())
                && (crane.plugPlan() != null || crane.gateLift().plan() != null)) {
            Point tip = crane.tip(partial);
            if (tip != null) {
                // a shield plug section, or a canal gate (CanalGateLift) on the same entity
                double hang = crane.plugPlan() != null ? crane.plugPlan().hookAboveBase() : crane.gateLift().plan().hookAboveBase();
                pose.translate(tip.x() - here.x, tip.y() - hang - here.y, tip.z() - here.z);
            }
        }
        // the crane carries its own work lights: never darker than a lit room
        int lit = LightTexture.pack(Math.max(LightTexture.block(light), 11), Math.max(LightTexture.sky(light), 6));
        var dispatcher = Minecraft.getInstance().getBlockRenderer();
        for (PlugSectionEntity.Cell c : e.cells()) {
            pose.pushPose();
            pose.translate(c.x() - e.pickX(), c.y(), c.z() - e.pickZ());
            dispatcher.renderSingleBlock(c.state(), pose, buffers, lit, OverlayTexture.NO_OVERLAY);
            pose.popPose();
        }
        pose.popPose();
    }

    @Override
    public ResourceLocation getTextureLocation(PlugSectionEntity e) {
        return NO_TEXTURE;
    }
}
