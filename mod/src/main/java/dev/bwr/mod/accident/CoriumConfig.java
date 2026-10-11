package dev.bwr.mod.accident;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Server switches for severe-accident world alteration. Per world, in serverconfig/. */
public final class CoriumConfig {
    private CoriumConfig() {}

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();
    public static final ModConfigSpec.BooleanValue ALTER_WORLD = BUILDER
            .comment("Let ex-vessel corium melt the floor under a failed reactor vessel into corium blocks.",
                    "Off: the accident is still simulated and reported, but no blocks are changed.")
            .define("coriumAltersWorld", true);
    public static final ModConfigSpec.IntValue BLOCKS_PER_TICK = BUILDER
            .comment("Maximum block positions one reactor's corium pool examines in one tick")
            .defineInRange("coriumBlocksPerTick", 48, 1, 4_096);
    public static final ModConfigSpec SPEC = BUILDER.build();

    /** Before the server config loads (or on a client) the defaults apply. */
    public static boolean alterWorld() {
        return !SPEC.isLoaded() || ALTER_WORLD.get();
    }

    public static int blocksPerTick() {
        return SPEC.isLoaded() ? BLOCKS_PER_TICK.get() : BLOCKS_PER_TICK.getDefault();
    }
}
