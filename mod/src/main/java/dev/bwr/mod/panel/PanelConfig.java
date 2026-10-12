package dev.bwr.mod.panel;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Control panel limits. Registered as {@code bwr-panels.toml} (a second COMMON
 * config beside the containment one, so it needs its own file name).
 */
public final class PanelConfig {
    private PanelConfig() {
    }

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.IntValue MAX_GROUP_COLUMNS = BUILDER
            .comment("Widest a panel group may be, in blocks. A block that would make a group wider is refused")
            .defineInRange("maxGroupColumns", 16, 1, 64);
    public static final ModConfigSpec.IntValue MAX_GROUP_ROWS = BUILDER
            .comment("Tallest a panel group may be, in blocks. A block that would make a group taller is refused")
            .defineInRange("maxGroupRows", 8, 1, 64);
    public static final ModConfigSpec.IntValue MAX_COMPONENTS_PER_BLOCK = BUILDER
            .comment("Most components one panel block may own (anchor in that block)")
            .defineInRange("maxComponentsPerBlock", 64, 1, 256);
    public static final ModConfigSpec.IntValue SAMPLE_TICKS = BUILDER
            .comment("Minimum ticks between samples of a panel group's linked signals (used by the signal layer)")
            .defineInRange("sampleTicks", 2, 1, 200);
    public static final ModConfigSpec.IntValue MAX_LABEL_LENGTH = BUILDER
            .comment("Longest component label")
            .defineInRange("maxLabelLength", 16, 4, 32);
    public static final ModConfigSpec.IntValue LINK_RANGE = BUILDER
            .comment("Farthest a panel may be linked to a plant device, in blocks (same dimension only)")
            .defineInRange("linkRange", 64, 1, 1024);
    public static final ModConfigSpec SPEC = BUILDER.build();

    /** Defensive reads: a GameTest or a very early caller may run before the file is loaded. */
    public static int maxColumns() {
        return SPEC.isLoaded() ? MAX_GROUP_COLUMNS.get() : 16;
    }

    public static int maxRows() {
        return SPEC.isLoaded() ? MAX_GROUP_ROWS.get() : 8;
    }

    public static int maxComponentsPerBlock() {
        return SPEC.isLoaded() ? MAX_COMPONENTS_PER_BLOCK.get() : 64;
    }

    public static int sampleTicks() {
        return SPEC.isLoaded() ? SAMPLE_TICKS.get() : 2;
    }

    public static int linkRange() {
        return SPEC.isLoaded() ? LINK_RANGE.get() : 64;
    }

    public static int maxLabelLength() {
        return SPEC.isLoaded() ? MAX_LABEL_LENGTH.get() : 16;
    }
}
