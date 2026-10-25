package dev.bwr.mod.spentfuel.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.bwr.mod.BwrMod;
import dev.bwr.mod.client.DeferredLiquids;
import dev.bwr.mod.registry.BwrBlockEntities;
import dev.bwr.mod.spentfuel.SpentFuelPoolBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/**
 * The water of a spent fuel pool and the Cherenkov glow of fresh fuel in it, drawn from the picture the controller sends
 * (rim to rim bounds, the level in eighths of a metre, one glow strength per cell). One translucent surface, and for each
 * cell whose strength is above zero a blue emissive cap on the rack with a short fading column. The strength is the
 * reactor's own {@code CherenkovAppearance} fed with the assembly's decay power as a fraction of its operating power,
 * so a freshly discharged assembly glows strongly and an old one barely. No particles, no light blocks, no mesh
 * rebuilds; a pool that is not formed draws nothing.
 */
@EventBusSubscriber(modid = BwrMod.MOD_ID, value = Dist.CLIENT)
public final class SpentFuelPoolRenderer implements BlockEntityRenderer<SpentFuelPoolBlockEntity> {

    private static final ResourceLocation WATER = ResourceLocation.withDefaultNamespace("block/water_still");

    public SpentFuelPoolRenderer(BlockEntityRendererProvider.Context context) {
    }

    @SubscribeEvent
    public static void renderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(BwrBlockEntities.SPENT_FUEL_POOL.get(), SpentFuelPoolRenderer::new);
    }

    @Override
    public void render(SpentFuelPoolBlockEntity be, float partial, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        if (!be.visFormed || be.visMin == null || be.visMax == null || be.getLevel() == null) {
            return;
        }
        double depth = be.visLevelQ / 8.0;
        if (depth <= 0.0) {
            return;
        }
        BlockPos root = be.getBlockPos();
        double surface = be.visMin.getY() + 1 + depth;
        int surfaceLight = LevelRenderer.getLightColor(be.getLevel(), new BlockPos(root.getX(), be.visMax.getY() + 1, root.getZ()));
        // Replayed after the translucent terrain, where a shader pack draws water with its water program (see DeferredLiquids).
        DeferredLiquids.add(pose, (p, src) -> draw(be, p, src, surface, surfaceLight));
    }

    private static void draw(SpentFuelPoolBlockEntity be, PoseStack pose, MultiBufferSource buffers, double surface, int surfaceLight) {
        BlockPos min = be.visMin;
        BlockPos max = be.visMax;
        BlockPos root = be.getBlockPos();
        TextureAtlasSprite sprite = Minecraft.getInstance().getTextureAtlas(TextureAtlas.LOCATION_BLOCKS).apply(WATER);

        pose.pushPose();
        pose.translate(-root.getX(), -root.getY(), -root.getZ());
        PoseStack.Pose p = pose.last();

        VertexConsumer water = buffers.getBuffer(DeferredLiquids.WATER);
        double x0 = min.getX() + 1;
        double x1 = max.getX();
        double z0 = min.getZ() + 1;
        double z1 = max.getZ();
        float s = (float) (surface - 0.06);   // the surface sits a little under the rim, as water does
        quad(water, p, sprite, x0, s, z0, x1, s, z1, 0.18f, 0.42f, 0.95f, 0.62f, surfaceLight, 1f);
        quad(water, p, sprite, x0, s, z1, x1, s, z0, 0.18f, 0.42f, 0.95f, 0.62f, surfaceLight, -1f);

        VertexConsumer glow = buffers.getBuffer(RenderType.eyes(TextureAtlas.LOCATION_BLOCKS));
        long[] racks = be.visRacks;
        byte[] strengths = be.visGlow;
        for (int r = 0; r < racks.length; r++) {
            BlockPos rack = BlockPos.of(racks[r]);
            double top = rack.getY() + dev.bwr.mod.spentfuel.SpentFuelPoolLayout.RACK_HEIGHT;
            if (top + 0.05 >= s) {
                continue;
            }
            for (int k = 0; k < 4; k++) {
                int index = r * 4 + k;
                if (index >= strengths.length || strengths[index] <= 0) {
                    continue;
                }
                float strength = strengths[index] / 15f;
                double cx = rack.getX() + 0.25 + 0.5 * (k & 1);
                double cz = rack.getZ() + 0.25 + 0.5 * (k >> 1);
                cell(glow, p, sprite, cx, top, cz, Math.min(s - 0.03, top + 0.25 + 0.9 * strength), strength);
            }
        }
        pose.popPose();
    }

    /** The glow of one cell: a cap on the head of the assembly and four faces that fade as they rise. */
    private static void cell(VertexConsumer out, PoseStack.Pose p, TextureAtlasSprite sprite, double cx, double base, double cz,
                             double top, float strength) {
        double h = 0.2;
        double y = base + 0.02;
        emit(out, p, sprite, cx - h, y, cz - h, strength);
        emit(out, p, sprite, cx - h, y, cz + h, strength);
        emit(out, p, sprite, cx + h, y, cz + h, strength);
        emit(out, p, sprite, cx + h, y, cz - h, strength);
        double[][] corners = {{-h, -h}, {-h, h}, {h, h}, {h, -h}};
        for (int i = 0; i < 4; i++) {
            double[] a = corners[i];
            double[] b = corners[(i + 1) % 4];
            emit(out, p, sprite, cx + a[0], y, cz + a[1], 0.45f * strength);
            emit(out, p, sprite, cx + b[0], y, cz + b[1], 0.45f * strength);
            emit(out, p, sprite, cx + b[0], top, cz + b[1], 0f);
            emit(out, p, sprite, cx + a[0], top, cz + a[1], 0f);
        }
    }

    private static void quad(VertexConsumer out, PoseStack.Pose p, TextureAtlasSprite sprite, double x0, double y, double z0,
                             double x1, double y1, double z1, float r, float g, float b, float a, int light, float normalY) {
        float u0 = sprite.getU0();
        float u1 = sprite.getU1();
        float v0 = sprite.getV0();
        float v1 = sprite.getV1();
        // a handful of tiles' worth of the animated water texture across the surface
        out.addVertex(p, (float) x0, (float) y, (float) z0).setColor(r, g, b, a).setUv(u0, v0).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(p, 0, normalY, 0);
        out.addVertex(p, (float) x0, (float) y, (float) z1).setColor(r, g, b, a).setUv(u0, v1).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(p, 0, normalY, 0);
        out.addVertex(p, (float) x1, (float) y1, (float) z1).setColor(r, g, b, a).setUv(u1, v1).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(p, 0, normalY, 0);
        out.addVertex(p, (float) x1, (float) y1, (float) z0).setColor(r, g, b, a).setUv(u1, v0).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(p, 0, normalY, 0);
    }

    private static void emit(VertexConsumer out, PoseStack.Pose p, TextureAtlasSprite sprite, double x, double y, double z, float alpha) {
        // The additive shader blends ONE/ONE: premultiply so the fade and the strength really extinguish the light.
        float k = DeferredLiquids.glowStraight() ? 1f : alpha;
        out.addVertex(p, (float) x, (float) y, (float) z).setColor(0.04f * k, 0.6f * k, k, alpha)
                .setUv(sprite.getU(0.5f), sprite.getV(0.5f)).setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(LightTexture.FULL_BRIGHT).setNormal(p, 0, 1, 0);
    }

    @Override
    public AABB getRenderBoundingBox(SpentFuelPoolBlockEntity be) {
        if (be.visMin == null || be.visMax == null) {
            return new AABB(be.getBlockPos());
        }
        return new AABB(Vec3.atLowerCornerOf(be.visMin), Vec3.atLowerCornerOf(be.visMax.offset(1, 2, 1)));
    }

    @Override
    public int getViewDistance() {
        return 128;
    }

    @Override
    public boolean shouldRenderOffScreen(SpentFuelPoolBlockEntity be) {
        return true;
    }

    @Override
    public boolean shouldRender(SpentFuelPoolBlockEntity be, Vec3 eye) {
        return be.visFormed && getRenderBoundingBox(be).distanceToSqr(eye) < (double) getViewDistance() * getViewDistance();
    }
}
