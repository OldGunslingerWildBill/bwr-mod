package dev.bwr.mod.containment;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Limits for the event-driven containment survey. */
public final class ContainmentConfig {
    private ContainmentConfig() {}

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();
    public static final ModConfigSpec.IntValue MAX_INTERIOR_CELLS = BUILDER
            .comment("Maximum air cells one containment controller can survey")
            .defineInRange("maxInteriorCells", 200_000, 50, 2_000_000);
    public static final ModConfigSpec.IntValue MAX_SPAN = BUILDER
            .comment("Maximum interior span along each axis, in blocks")
            .defineInRange("maxSpan", 128, 8, 512);
    public static final ModConfigSpec.IntValue SCAN_CELLS_PER_TICK = BUILDER
            .comment("Maximum cells one controller examines in one tick")
            .defineInRange("scanCellsPerTick", 20_000, 100, 100_000);
    public static final ModConfigSpec.IntValue REVALIDATE_TICKS = BUILDER
            .comment("Slow backstop survey interval, in ticks")
            .defineInRange("revalidateTicks", 600, 20, 72_000);
    public static final ModConfigSpec SPEC = BUILDER.build();
}
