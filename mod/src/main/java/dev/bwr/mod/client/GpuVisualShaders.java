package dev.bwr.mod.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.bwr.mod.BwrMod;
import java.io.IOException;
import java.util.EnumMap;
import java.util.function.Consumer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import org.lwjgl.opengl.GL11;

/** Shader resources and guarded immediate draws. All calls occur on the render thread. */
@EventBusSubscriber(modid = BwrMod.MOD_ID, value = Dist.CLIENT)
public final class GpuVisualShaders {
    private static final EnumMap<VisualEffectsMode.Effect, ShaderInstance> SHADERS =
            new EnumMap<>(VisualEffectsMode.Effect.class);
    private static final EnumMap<VisualEffectsMode.Effect, Long> DRAWS =
            new EnumMap<>(VisualEffectsMode.Effect.class);

    private GpuVisualShaders() {}

    @SubscribeEvent public static void shaders(RegisterShadersEvent event) {
        SHADERS.clear();
        VisualEffectsMode.reload();
        register(event, VisualEffectsMode.Effect.CHERENKOV, "cherenkov");
        register(event, VisualEffectsMode.Effect.WATER, "vessel_water");
        try {
            ShaderInstance instance = new ShaderInstance(event.getResourceProvider(), BwrMod.id("vapor_instanced"),
                    DefaultVertexFormat.POSITION);
            event.registerShader(instance, loaded -> {
                SHADERS.put(VisualEffectsMode.Effect.VAPOR, loaded);
                VisualEffectsMode.loaded(VisualEffectsMode.Effect.VAPOR);
            });
        } catch (IOException | RuntimeException error) {
            VisualEffectsMode.failed(VisualEffectsMode.Effect.VAPOR, error);
        }
        dev.bwr.mod.cooling.client.VaporPlumeRenderer.clearBuffers();
    }

    private static void register(RegisterShadersEvent event, VisualEffectsMode.Effect effect, String name) {
        try {
            ShaderInstance instance = new ShaderInstance(event.getResourceProvider(), BwrMod.id(name),
                    DefaultVertexFormat.POSITION_TEX_COLOR);
            event.registerShader(instance, loaded -> {
                SHADERS.put(effect, loaded);
                VisualEffectsMode.loaded(effect);
            });
        } catch (IOException | RuntimeException error) {
            VisualEffectsMode.failed(effect, error);
        }
    }

    public static ShaderInstance shader(VisualEffectsMode.Effect effect) { return SHADERS.get(effect); }
    public static long drawCount(VisualEffectsMode.Effect effect) { return DRAWS.getOrDefault(effect, 0L); }

    /** A failing effect is disabled through the next resource reload; other effects keep working. */
    public static boolean draw(VisualEffectsMode.Effect effect, Consumer<ShaderInstance> setup, Runnable geometry,
                               boolean additive) {
        ShaderInstance shader = SHADERS.get(effect);
        if (shader == null || !VisualEffectsMode.gpu(effect, true)) return false;
        RenderSystem.assertOnRenderThread();
        ShaderInstance previous = RenderSystem.getShader();
        try {
            RenderSystem.enableBlend();
            if (additive) RenderSystem.blendFunc(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE);
            else RenderSystem.defaultBlendFunc();
            RenderSystem.enableDepthTest();
            RenderSystem.depthMask(false);
            RenderSystem.disableCull();
            RenderSystem.setShader(() -> shader);
            shader.setDefaultUniforms(VertexFormat.Mode.TRIANGLES, RenderSystem.getModelViewMatrix(),
                    RenderSystem.getProjectionMatrix(), Minecraft.getInstance().getWindow());
            setup.accept(shader);
            shader.apply();
            geometry.run();
            int glError = GL11.glGetError();
            if (glError != GL11.GL_NO_ERROR) throw new IllegalStateException("GL error 0x" + Integer.toHexString(glError));
            DRAWS.merge(effect, 1L, Long::sum);
            return true;
        } catch (RuntimeException | Error error) {
            VisualEffectsMode.failed(effect, error);
            return false;
        } finally {
            shader.clear();
            RenderSystem.setShader(() -> previous);
            RenderSystem.depthMask(true);
            RenderSystem.enableCull();
            RenderSystem.disableBlend();
            RenderSystem.defaultBlendFunc();
        }
    }
}
