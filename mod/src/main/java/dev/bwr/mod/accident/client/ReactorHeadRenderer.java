package dev.bwr.mod.accident.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.bwr.mod.BwrMod;
import dev.bwr.mod.accident.ReactorHeadEntity;
import dev.bwr.mod.client.MachineMeshCache;
import dev.bwr.mod.registry.BwrEntities;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/**
 * Draws the thrown vessel head with the vessel's own head ({@code VesselModel.buildHead}: flange forging, dome, crown
 * nozzles, lifting lugs, nuts and washers), at the vessel's width and depth and the saved dome height. In the air it wobbles a few degrees; at rest it sits tilted by the saved angles.
 */
@EventBusSubscriber(modid = BwrMod.MOD_ID, value = Dist.CLIENT)
public final class ReactorHeadRenderer extends EntityRenderer<ReactorHeadEntity> {
    /** One cached mesh per head size. */
    private record HeadShape(float width, float depth, float rise) {
    }
    private static final ResourceLocation NO_TEXTURE = ResourceLocation.withDefaultNamespace("textures/block/iron_block.png");

    public ReactorHeadRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @SubscribeEvent
    public static void renderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(BwrEntities.REACTOR_HEAD.get(), ReactorHeadRenderer::new);
    }

    @Override
    public void render(ReactorHeadEntity head, float yaw, float partial, PoseStack pose, MultiBufferSource buffers, int light) {
        pose.pushPose();
        pose.mulPose(Axis.YP.rotationDegrees(-yaw));
        float tiltX, tiltZ;
        if (head.isResting()) {
            tiltX = head.tiltXDegrees();
            tiltZ = head.tiltZDegrees();
        } else {
            double t = head.level().getGameTime() - head.launchedAt() + partial;
            tiltX = (float) (ReactorHeadEntity.TUMBLE_DEGREES * Math.sin(0.11 * t));
            tiltZ = (float) (ReactorHeadEntity.TUMBLE_DEGREES * Math.sin(0.083 * t + 1.0));
        }
        pose.mulPose(Axis.XP.rotationDegrees(tiltX));
        pose.mulPose(Axis.ZP.rotationDegrees(tiltZ));
        float w = head.width(), d = head.depth(), rise = head.height();
        MachineMeshCache.draw(new HeadShape(w, d, rise), b -> dev.bwr.mod.reactor.client.VesselModel.buildHead(b, w, d, rise), pose, buffers, light,
                OverlayTexture.NO_OVERLAY);
        pose.popPose();
    }

    @Override
    public ResourceLocation getTextureLocation(ReactorHeadEntity head) {
        return NO_TEXTURE;
    }
}
