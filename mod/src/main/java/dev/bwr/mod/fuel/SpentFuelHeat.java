package dev.bwr.mod.fuel;

import dev.bwr.core.thermal.AssemblyDecayHeat;
import dev.bwr.mod.config.GameplayConfig;
import dev.bwr.mod.registry.BwrItems;
import net.minecraft.world.item.ItemStack;

/**
 * The decay heat of a discharged fuel assembly as an item: its burnup (on the {@link FuelAssemblyData}) and the game
 * tick it left a core (the {@code bwr:discharge_time} component). The clock is the level's game time, so it keeps
 * running while the assembly is in a chest, in a pool or in the player's pocket, and in a chunk nobody has loaded.
 * A bundle without a stamp (from before this existed, or from a path that never recorded one) is treated as
 * <b>long-cooled</b>: {@link AssemblyDecayHeat#LONG_COOLED_SECONDS} of cooling, a few kilowatts at most.
 *
 * <p>The curve is {@link AssemblyDecayHeat} in the core module; nothing here is physics, only the item bookkeeping.
 */
public final class SpentFuelHeat {

    private SpentFuelHeat() {
    }

    public static final int TICKS_PER_SECOND = 20;

    /** The stamp on a stack, or -1 if it has none. */
    public static long dischargeTime(ItemStack stack) {
        Long time = stack.get(BwrDataComponents.DISCHARGE_TIME.get());
        return time == null ? -1L : time;
    }

    /** Records that this assembly was taken out of a core at {@code gameTime}. */
    public static void stamp(ItemStack stack, long gameTime) {
        if (!stack.isEmpty() && gameTime >= 0L) {
            stack.set(BwrDataComponents.DISCHARGE_TIME.get(), gameTime);
        }
    }

    /** Seconds since discharge at {@code gameTime}; long-cooled when unstamped. Never negative. */
    public static double coolingSeconds(ItemStack stack, long gameTime) {
        long stamp = dischargeTime(stack);
        if (stamp < 0L) {
            return AssemblyDecayHeat.LONG_COOLED_SECONDS;
        }
        return Math.max(0L, gameTime - stamp) / (double) TICKS_PER_SECOND;
    }

    /** Decay power of the assembly in this stack at {@code gameTime}, W; zero for anything that is not an assembly. */
    public static double decayWatts(ItemStack stack, long gameTime) {
        if (stack.isEmpty() || !stack.is(BwrItems.FUEL_ASSEMBLY.get())) {
            return 0.0;
        }
        FuelAssemblyData data = FuelAssemblies.dataOf(stack);
        return AssemblyDecayHeat.powerWatts(data.burnupMwdPerTonne(), data.heavyMetalMassKg(), coolingSeconds(stack, gameTime));
    }

    /** The reprocessing plant's limit, W per assembly; zero or less means the plant does not check. */
    public static double reprocessingLimitWatts() {
        return GameplayConfig.reprocessingMaxDecayHeatKw() * 1000.0;
    }

    /** True if the plant must refuse this assembly now: a limit is set and its decay heat is above it. */
    public static boolean tooHotToReprocess(ItemStack stack, long gameTime) {
        double limit = reprocessingLimitWatts();
        return limit > 0.0 && decayWatts(stack, gameTime) > limit;
    }

    /** Seconds until the assembly is at or below the plant's limit (zero if it already is, infinity if never). */
    public static double secondsUntilReprocessable(ItemStack stack, long gameTime) {
        double limit = reprocessingLimitWatts();
        if (!(limit > 0.0) || stack.isEmpty() || !stack.is(BwrItems.FUEL_ASSEMBLY.get())) {
            return 0.0;
        }
        FuelAssemblyData data = FuelAssemblies.dataOf(stack);
        double needed = AssemblyDecayHeat.secondsToCoolBelow(data.burnupMwdPerTonne(), data.heavyMetalMassKg(), limit);
        if (Double.isInfinite(needed)) {
            return needed;
        }
        return Math.max(0.0, needed - coolingSeconds(stack, gameTime));
    }
}
