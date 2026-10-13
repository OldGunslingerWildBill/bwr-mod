package dev.bwr.mod.client;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Client-only choice; no server physics or network field depends on it. */
public final class VisualEffectsConfig {
    public enum Mode { AUTO, SHADER, CLASSIC }

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();
    public static final ModConfigSpec.EnumValue<Mode> VISUAL_EFFECTS = BUILDER
            .comment("GPU visual effects: AUTO chooses the measured default, SHADER requests GPU effects, CLASSIC uses the original renderers.")
            .defineEnum("visualEffects", Mode.AUTO);
    public static final ModConfigSpec SPEC = BUILDER.build();

    private VisualEffectsConfig() {}
}
