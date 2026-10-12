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
    public static final ModConfigSpec.IntValue STRENGTH_SCAN_DEPTH = BUILDER
            .comment("Maximum continuous wall layers counted at each boundary face")
            .defineInRange("strengthScanDepth", 16, 1, 64);
    public static final ModConfigSpec.DoubleValue PENETRATION_CONTAINMENT_PSIG = BUILDER
            .comment("Pressure difference rating of a sound containment penetration sleeve, psig")
            .defineInRange("penetrationContainmentPsig", 150.0, 0.0, 10_000.0);
    public static final ModConfigSpec.DoubleValue VAULT_DOOR_CONTAINMENT_PSIG = BUILDER
            .comment("Closed containment vault door pressure rating, psig")
            .defineInRange("vaultDoorContainmentPsig", 200.0, 0.0, 10_000.0);
    public static final ModConfigSpec.DoubleValue AIRLOCK_CONTAINMENT_PSIG = BUILDER
            .comment("Closed personnel airlock leaf pressure rating, psig")
            .defineInRange("airlockContainmentPsig", 120.0, 0.0, 10_000.0);
    public static final ModConfigSpec.DoubleValue STEAM_DESIGN_PSIG = BUILDER
            .comment("Steam penetration process nameplate at 575 F, psig")
            .defineInRange("steamPenetrationDesignPsig", 1250.0, 0.0, 20_000.0);
    public static final ModConfigSpec.DoubleValue STEAM_CODE_LIMIT_PSIG = BUILDER
            .comment("Steam penetration process code limit, psig")
            .defineInRange("steamPenetrationCodeLimitPsig", 1375.0, 0.0, 20_000.0);
    public static final ModConfigSpec.DoubleValue WATER_DESIGN_PSI = BUILDER
            .comment("Water penetration process nameplate, psi")
            .defineInRange("waterPenetrationDesignPsi", 2500.0, 0.0, 20_000.0);
    public static final ModConfigSpec.DoubleValue PASS_THROUGH_DESIGN_PSI = BUILDER
            .comment("General pass-through process nameplate, psi")
            .defineInRange("passThroughDesignPsi", 1480.0, 0.0, 20_000.0);
    public static final ModConfigSpec.IntValue POWER_PENETRATION_FE_PER_TICK = BUILDER
            .comment("Electrical penetration FE throughput cap per tick")
            .defineInRange("powerPenetrationFePerTick", 100_000, 1, 10_000_000);
    public static final ModConfigSpec SPEC = BUILDER.build();
}
