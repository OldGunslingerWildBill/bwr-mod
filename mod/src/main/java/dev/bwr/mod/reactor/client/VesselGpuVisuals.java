package dev.bwr.mod.reactor.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.bwr.mod.BwrMod;
import dev.bwr.mod.client.GpuVisualShaders;
import dev.bwr.mod.client.VisualEffectsMode;
import dev.bwr.mod.reactor.CherenkovAppearance;
import dev.bwr.mod.reactor.ReactorControllerBlockEntity;
import dev.bwr.mod.reactor.VesselAppearance;
import dev.bwr.mod.reactor.VesselCoreAppearance;
import dev.bwr.mod.reactor.VesselInternalsGeometry;
import dev.bwr.mod.reactor.VesselWaterGeometry;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.GameShuttingDownEvent;
import net.neoforged.neoforge.event.level.LevelEvent;

/** Immediate GPU geometry for the two RPV effects. The classic classes remain untouched. */
@EventBusSubscriber(modid = BwrMod.MOD_ID, value = Dist.CLIENT)
final class VesselGpuVisuals {
    private static final ResourceLocation WATER = ResourceLocation.withDefaultNamespace("block/water_still");
    private static final Map<ReactorControllerBlockEntity, Sample> LEVELS = new WeakHashMap<>();
    private record Sample(long tick, double level, double falling) {}

    @SubscribeEvent public static void unload(LevelEvent.Unload event) { if (event.getLevel().isClientSide()) LEVELS.clear(); }
    @SubscribeEvent public static void shutdown(GameShuttingDownEvent event) { LEVELS.clear(); }

    static boolean water(ReactorControllerBlockEntity be, VesselAppearance.Envelope e, float partial,
                         PoseStack pose, MultiBufferSource buffers, int light) {
        if (!available(VisualEffectsMode.Effect.WATER, buffers)) return false;
        double level = be.clientWaterLevelIn(partial);
        double y = VesselWaterGeometry.height(e, level);
        double radius = VesselWaterGeometry.radiusFraction(e, y);
        long tick = be.getLevel() == null ? 0 : be.getLevel().getGameTime();
        Sample old = LEVELS.get(be);
        double falling = old == null ? 0 : old.tick == tick ? old.falling
                : Math.clamp((old.level - level) / Math.max(1, tick - old.tick) / 3.0, 0, 1);
        LEVELS.put(be, new Sample(tick, level, falling));
        float power = (float)Math.clamp(be.clientPowerFractionOfRated(), 0, 2);
        float gameTime = tick + partial;
        float worldLight = Math.clamp(Math.max(light & 65535, (light >>> 16) & 65535) / 240f, .12f, 1f);
        var sprite = Minecraft.getInstance().getTextureAtlas(TextureAtlas.LOCATION_BLOCKS).apply(WATER);
        flush(buffers);
        RenderSystem.setShaderTexture(0, TextureAtlas.LOCATION_BLOCKS);
        return GpuVisualShaders.draw(VisualEffectsMode.Effect.WATER, shader -> {
            shader.safeGetUniform("GameTime").set(gameTime);
            shader.safeGetUniform("Power").set(power);
            shader.safeGetUniform("Falling").set((float)falling);
            shader.safeGetUniform("WorldLight").set(worldLight);
        }, () -> waterDisk(pose, e, y, radius, sprite), false);
    }

    static boolean cherenkov(ReactorControllerBlockEntity be, VesselAppearance.Envelope e,
                             VesselCoreAppearance core, float partial, PoseStack pose, MultiBufferSource buffers) {
        if (!available(VisualEffectsMode.Effect.CHERENKOV, buffers)) return false;
        double surface = Math.max(VesselWaterGeometry.height(e, be.clientWaterLevelIn(partial)), be.clientWellSurface());
        float strength = (float)CherenkovAppearance.strength(be.clientPowerFractionOfRated(), be.clientHasFuel(),
                be.clientWaterPresent(), surface);
        if (strength < .002f) return true; // zero means no draw on both paths
        float lit = strength * (float)CherenkovAppearance.wellBoost(surface, VesselWaterGeometry.rim(e));
        double top = Math.min(surface - .035, VesselInternalsGeometry.coreTop(e) + .27);
        if (top <= 2.02) return true;
        double radius = ReactorCoreGeometry.fuelEnvelopeRadius(core) * 1.01;
        float time = be.getLevel() == null ? partial : be.getLevel().getGameTime() + partial;
        flush(buffers);
        return GpuVisualShaders.draw(VisualEffectsMode.Effect.CHERENKOV, shader -> {
            shader.safeGetUniform("Strength").set(lit);
            shader.safeGetUniform("CoreTop").set((float)top);
            shader.safeGetUniform("CoreBottom").set(2.02f);
            shader.safeGetUniform("Surface").set((float)surface);
            shader.safeGetUniform("Radius").set((float)radius);
            shader.safeGetUniform("GameTime").set(time);
        }, () -> glowVolume(pose, e, radius, top, surface), true);
    }

    private static boolean available(VisualEffectsMode.Effect effect, MultiBufferSource buffers) {
        return buffers instanceof MultiBufferSource.BufferSource && VisualEffectsMode.gpu(effect, true)
                && GpuVisualShaders.shader(effect) != null;
    }

    private static void flush(MultiBufferSource buffers) { ((MultiBufferSource.BufferSource)buffers).endBatch(); }

    private static BufferBuilder triangles() {
        return Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_TEX_COLOR);
    }

    private static void waterDisk(PoseStack pose, VesselAppearance.Envelope e, double y, double radius, TextureAtlasSprite sprite) {
        var out = triangles();
        var matrix = pose.last().pose();
        int n = 112;
        for (int i = 0; i < n; i++) {
            double a = -i * Math.PI * 2 / n, b = -(i + 1) * Math.PI * 2 / n;
            waterVertex(out, matrix, 0, y, 0, sprite, .5, .5);
            waterVertex(out, matrix, radius * e.width() * Math.cos(a), y, radius * e.depth() * Math.sin(a), sprite,
                    .5 + .5 * Math.cos(a), .5 + .5 * Math.sin(a));
            waterVertex(out, matrix, radius * e.width() * Math.cos(b), y, radius * e.depth() * Math.sin(b), sprite,
                    .5 + .5 * Math.cos(b), .5 + .5 * Math.sin(b));
        }
        BufferUploader.draw(out.buildOrThrow());
    }

    private static void waterVertex(BufferBuilder out, org.joml.Matrix4f matrix, double x, double y, double z,
                                    TextureAtlasSprite sprite, double u, double v) {
        out.addVertex(matrix, (float)x, (float)y, (float)z).setColor(70, 139, 197, 160)
                .setUv(sprite.getU((float)u), sprite.getV((float)v));
    }

    private static void glowVolume(PoseStack pose, VesselAppearance.Envelope e, double radius, double top, double surface) {
        var out = triangles();
        var matrix = pose.last().pose();
        int n = 48;
        double[] radii = {0, .55, .82, 1};
        float[] alpha = {.72f, .65f, .26f, 0};
        for (double y : new double[]{top, surface - .001}) {
            for (int ring = 0; ring < radii.length - 1; ring++) for (int i = 0; i < n; i++) {
                double a = -i * Math.PI * 2 / n, b = -(i + 1) * Math.PI * 2 / n;
                glowTriangle(out, matrix, e, radius, y, radii[ring], a, alpha[ring], radii[ring+1], a, alpha[ring+1], radii[ring+1], b, alpha[ring+1]);
                glowTriangle(out, matrix, e, radius, y, radii[ring], a, alpha[ring], radii[ring+1], b, alpha[ring+1], radii[ring], b, alpha[ring]);
            }
        }
        for (int i = 0; i < n; i++) {
            double a = -i * Math.PI * 2 / n, b = -(i + 1) * Math.PI * 2 / n;
            glowVertex(out, matrix, e, radius, 1, a, 2.02, .10f);
            glowVertex(out, matrix, e, radius, 1, b, 2.02, .10f);
            glowVertex(out, matrix, e, radius, 1, b, top, .035f);
            glowVertex(out, matrix, e, radius, 1, a, 2.02, .10f);
            glowVertex(out, matrix, e, radius, 1, b, top, .035f);
            glowVertex(out, matrix, e, radius, 1, a, top, .035f);
        }
        BufferUploader.draw(out.buildOrThrow());
    }

    private static void glowTriangle(BufferBuilder out, org.joml.Matrix4f matrix, VesselAppearance.Envelope e,
                                     double radius, double y, double r1, double a1, float alpha1,
                                     double r2, double a2, float alpha2, double r3, double a3, float alpha3) {
        glowVertex(out, matrix, e, radius, r1, a1, y, alpha1);
        glowVertex(out, matrix, e, radius, r2, a2, y, alpha2);
        glowVertex(out, matrix, e, radius, r3, a3, y, alpha3);
    }

    private static void glowVertex(BufferBuilder out, org.joml.Matrix4f matrix, VesselAppearance.Envelope e,
                                   double radius, double ring, double angle, double y, float alpha) {
        out.addVertex(matrix, (float)(e.width() * radius * ring * Math.cos(angle)), (float)y,
                (float)(e.depth() * radius * ring * Math.sin(angle))).setColor(1f, 1f, 1f, alpha)
                .setUv((float)(radius * ring), (float)y);
    }
}
