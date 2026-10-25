package dev.bwr.mod.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.bwr.mod.BwrMod;
import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.ArrayList;

/**
 * Water surfaces and Cherenkov-style glow that a block entity asks for, drawn once per frame after the translucent terrain.
 *
 * <p>A shader pack (Iris) chooses the program for a draw from where it happens. Inside the block entity pass a translucent
 * draw goes to the block entity program, and the water program (where a pack does its water reflection and refraction) only
 * sees the translucent terrain pass. So a block entity records its pose and the draw here, and the draw is replayed after
 * {@code AFTER_TRANSLUCENT_BLOCKS} with {@link #WATER} for the water and {@link RenderType#eyes} for the glow.
 * Vanilla draws the same quads with the same blending, in the same order (water, then glow); the copy of the pose keeps the
 * block entity's camera-relative transform. The queue is emptied at {@code AFTER_SKY} of every level render, so a draw queued
 * by a pass that never reaches the translucent stage (a shadow pass) cannot survive into the next frame.
 */
@EventBusSubscriber(modid = BwrMod.MOD_ID, value = Dist.CLIENT)
public final class DeferredLiquids {

    /** One draw: fill the buffers for the given pose; the caller picks the render types through {@code buffers}. */
    @FunctionalInterface
    public interface Draw {
        void draw(PoseStack pose, MultiBufferSource buffers);
    }

    /**
     * Water: the vanilla translucent shader, blend, texture and cull, but colour-only writes. Vanilla translucent writes depth, which
     * would hide the glow under the surface (the pool's glow and the well's sit below it). Iris still routes this to the water program,
     * because it asks for the same shader as {@link RenderType#translucent()}.
     */
    public static final RenderType WATER = RenderType.create("bwr_water", DefaultVertexFormat.BLOCK, VertexFormat.Mode.QUADS, 786432, true, true,
            RenderType.CompositeState.builder()
                    .setShaderState(RenderStateShard.RENDERTYPE_TRANSLUCENT_SHADER)
                    .setTextureState(RenderStateShard.BLOCK_SHEET_MIPPED)
                    .setLightmapState(RenderStateShard.LIGHTMAP)
                    .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                    .setCullState(RenderStateShard.CULL)
                    .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                    .createCompositeState(true));

    private record Pending(PoseStack pose, Draw draw) {
    }

    private static final ArrayList<Pending> PENDING = new ArrayList<>();
    private static int replayed;
    private static boolean straightGlow;

    /**
     * Whether the glow is written straight rather than premultiplied. Vanilla's additive eyes take the premultiplied colour; a shader
     * pack's glow program blends with the alpha, which squares a premultiplied colour and left the glow almost invisible under
     * Complementary. Read once per replay, so the colour of each vertex costs no call.
     */
    public static boolean glowStraight() {
        return straightGlow;
    }

    private DeferredLiquids() {
    }

    /** How many draws the last translucent pass replayed (a check reads it: zero means no water or glow went through here). */
    public static int replayedLastFrame() {
        return replayed;
    }

    /** Records {@code draw} with a copy of the current transform. Call from a block entity renderer on the render thread. */
    public static void add(PoseStack pose, Draw draw) {
        PoseStack copy = new PoseStack();
        copy.last().pose().set(pose.last().pose());
        copy.last().normal().set(pose.last().normal());
        PENDING.add(new Pending(copy, draw));
    }

    @SubscribeEvent
    public static void onStage(RenderLevelStageEvent event) {
        var stage = event.getStage();
        if (stage == RenderLevelStageEvent.Stage.AFTER_SKY) {
            PENDING.clear();
            return;
        }
        if (stage != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        replayed = PENDING.size();
        straightGlow = replayed > 0 && VisualEffectsMode.shaderPackInUse();
        if (replayed == 0) {
            return;
        }
        var buffers = Minecraft.getInstance().renderBuffers().bufferSource();
        for (Pending p : PENDING) {
            p.draw().draw(p.pose(), buffers);
        }
        PENDING.clear();
        // Water first, then the additive glow, as the block entity pass drew them.
        buffers.endBatch(WATER);
        buffers.endBatch(RenderType.eyes(TextureAtlas.LOCATION_BLOCKS));
    }
}
