package dev.bwr.mod.devtest;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import dev.bwr.mod.BwrMod;
import dev.bwr.mod.client.GpuVisualShaders;
import dev.bwr.mod.client.VisualEffectsConfig;
import dev.bwr.mod.client.VisualEffectsMode;
import java.io.IOException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.ParticleStatus;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.core.BlockPos;
import dev.bwr.mod.cooling.client.VaporPlumeRenderer;

/** Assertions run on the render thread in the disposable RVU and cooling client worlds. */
public final class GpuVisualsClientCheck {
    private GpuVisualsClientCheck() {}

    public static void policyAndBrokenShader(Minecraft mc) {
        var vapor = VisualEffectsMode.Effect.VAPOR;
        var glow = VisualEffectsMode.Effect.CHERENKOV;
        var water = VisualEffectsMode.Effect.WATER;
        VisualEffectsMode.testOverride(VisualEffectsConfig.Mode.SHADER, false);
        for (var effect : VisualEffectsMode.Effect.values()) {
            if (GpuVisualShaders.shader(effect) == null || !VisualEffectsMode.gpu(effect, true))
                throw new AssertionError("GPU shader did not load: " + effect);
        }
        VisualEffectsMode.testOverride(VisualEffectsConfig.Mode.CLASSIC, false);
        for (var effect : VisualEffectsMode.Effect.values())
            if (VisualEffectsMode.gpu(effect, true)) throw new AssertionError("CLASSIC selected GPU " + effect);
        VisualEffectsMode.testOverride(VisualEffectsConfig.Mode.SHADER, true);
        for (var effect : VisualEffectsMode.Effect.values())
            if (VisualEffectsMode.gpu(effect, true)) throw new AssertionError("shader pack did not override " + effect);
        VisualEffectsMode.testOverride(VisualEffectsConfig.Mode.SHADER, false);
        Throwable compileFailure = null;
        try (var broken = new ShaderInstance(mc.getResourceManager(), BwrMod.id("test_broken_gpu"),
                DefaultVertexFormat.POSITION_TEX_COLOR)) {
            throw new AssertionError("Deliberately broken fragment shader unexpectedly compiled");
        } catch (IOException | RuntimeException expected) {
            compileFailure = expected;
        }
        int before = VisualEffectsMode.failureLogCount();
        VisualEffectsMode.failed(glow, compileFailure);
        VisualEffectsMode.failed(glow, compileFailure);
        if (VisualEffectsMode.failureLogCount() != before + 1 || VisualEffectsMode.gpu(glow, true)
                || !VisualEffectsMode.gpu(vapor, true) || !VisualEffectsMode.gpu(water, true))
            throw new AssertionError("Per-effect shader failure or log-once behavior wrong");
        VisualEffectsMode.loaded(glow);
        VisualEffectsMode.testOverride(null, null);
    }

    public static void vaporSettingsAndCap(Minecraft mc, BlockPos tower) {
        if (mc.level == null || !VisualEffectsMode.gpu(VisualEffectsMode.Effect.VAPOR, true))
            throw new AssertionError("Vapor GPU path unavailable for setting check");
        ParticleStatus previous = mc.options.particles().get();
        try {
            int start = VaporPlumeRenderer.count();
            mc.options.particles().set(ParticleStatus.MINIMAL);
            for (int i = 0; i < 20; i++) puff(mc, tower);
            if (VaporPlumeRenderer.count() != start) throw new AssertionError("Minimal particles spawned GPU vapor");
            mc.options.particles().set(ParticleStatus.DECREASED);
            for (int i = 0; i < 200; i++) puff(mc, tower);
            int half = VaporPlumeRenderer.count() - start;
            if (half < 70 || half > 130) throw new AssertionError("Decreased GPU vapor is not approximately half: " + half);
            mc.options.particles().set(ParticleStatus.ALL);
            for (int i = 0; i < VaporPlumeRenderer.CAP; i++) puff(mc, tower);
            if (VaporPlumeRenderer.count() != VaporPlumeRenderer.CAP)
                throw new AssertionError("GPU vapor cap failed: " + VaporPlumeRenderer.count());
        } finally {
            mc.options.particles().set(previous);
        }
    }

    private static void puff(Minecraft mc, BlockPos tower) {
        if (!VaporPlumeRenderer.spawn(mc.level, tower, tower.getX() + .5, tower.getY() + 10,
                tower.getZ() + .5, 3.4, .3, .8)) throw new AssertionError("GPU vapor spawn fell back");
    }
}
