package dev.bwr.mod.client;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Client-only choice; no server physics or network field depends on it. */
public final class VisualEffectsConfig {
    public enum Mode { AUTO, SHADER, CLASSIC }

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();
    public static final ModConfigSpec.EnumValue<Mode> VISUAL_EFFECTS = BUILDER
            .comment("GPU visual effects: AUTO chooses the measured default, SHADER requests GPU effects, CLASSIC uses the original renderers.")
            .defineEnum("visualEffects", Mode.AUTO);
    public static final ModConfigSpec.BooleanValue PANEL_GPU_CACHE = BUILDER
            .comment("Control panels keep the solid geometry of each panel block in a vertex buffer on the GPU, which is much faster with detailed component models. Turn off if panels draw wrongly with a render mod or shader pack (the buffers are drawn through the game's own entity render type, so a pack's program shades them like any other block entity).")
            .define("panelGpuCache", true);
    public static final ModConfigSpec SPEC = BUILDER.build();

    private VisualEffectsConfig() {}
}
