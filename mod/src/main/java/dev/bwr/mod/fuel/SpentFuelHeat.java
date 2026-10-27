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

    /**
     * Decay power of the assemblies in this stack at {@code gameTime}, W: the power of one times the count, since
     * assemblies stack as items and every bundle in a stack is a whole bundle. Zero for anything that is not an
     * assembly. The one-assembly positions (a rack cell, the grapple, the elevator, the reprocessing slot) hold stacks
     * of one, so for them this is the power of the assembly.
     */
    public static double decayWatts(ItemStack stack, long gameTime) {
        return decayWattsEach(stack, gameTime) * stack.getCount();
    }

    /** Decay power of one assembly of this stack at {@code gameTime}, W, whatever the count; zero for anything that is not an assembly. */
    public static double decayWattsEach(ItemStack stack, long gameTime) {
        if (stack.isEmpty() || !stack.is(BwrItems.FUEL_ASSEMBLY.get())) {
            return 0.0;
        }
        FuelAssemblyData data = FuelAssemblies.dataOf(stack);
        return AssemblyDecayHeat.powerWatts(data.burnupMwdPerTonne(), data.heavyMetalMassKg(), coolingSeconds(stack, gameTime));
    }

    /**
     * What a store of many assemblies keeps for each one: its decay curve with the history part worked out once
     * ({@link AssemblyDecayHeat.Curve}) and its discharge stamp, read off the stack once. {@link #watts(long)} is then exactly
     * {@link #decayWatts(ItemStack, long)} for the same stack (to the last bit) at half the cost and without reading the
     * item's components again. Valid as long as the stack it was made from is not changed.
     */
    public static final class Curve {
        private final AssemblyDecayHeat.Curve curve;
        private final long stamp;
        private final boolean assembly;
        /** How many assemblies the stack held (a rack cell holds one): {@link #watts} is the power of them all. */
        private final int count;
        /** The assembly's burnup, MWd/t. */
        public final double burnupMwdPerTonne;
        /** Heavy metal, kg. */
        public final double heavyMetalKg;
        private double reprocessLimitW = Double.NaN;
        private double reprocessCoolS;

        public Curve(ItemStack stack) {
            assembly = !stack.isEmpty() && stack.is(BwrItems.FUEL_ASSEMBLY.get());
            count = assembly ? stack.getCount() : 0;
            stamp = dischargeTime(stack);
            FuelAssemblyData data = assembly ? FuelAssemblies.dataOf(stack) : null;
            burnupMwdPerTonne = data == null ? 0.0 : data.burnupMwdPerTonne();
            heavyMetalKg = data == null ? 0.0 : data.heavyMetalMassKg();
            curve = assembly ? new AssemblyDecayHeat.Curve(burnupMwdPerTonne, heavyMetalKg) : null;
        }

        /** Seconds since discharge at {@code gameTime}, as {@link SpentFuelHeat#coolingSeconds}. */
        public double coolingSeconds(long gameTime) {
            if (stamp < 0L) {
                return AssemblyDecayHeat.LONG_COOLED_SECONDS;
            }
            return Math.max(0L, gameTime - stamp) / (double) TICKS_PER_SECOND;
        }

        /** Decay power at {@code gameTime}, W: what {@link SpentFuelHeat#decayWatts} gives for the stack (all its assemblies). */
        public double watts(long gameTime) {
            return assembly ? curve.powerWatts(coolingSeconds(gameTime)) * count : 0.0;
        }

        /** Whether the stack carried a discharge stamp. */
        public boolean stamped() {
            return stamp >= 0L;
        }

        /** As {@link SpentFuelHeat#secondsUntilReprocessable}; the search for the cool-down time is done once per limit. */
        public double secondsUntilReprocessable(long gameTime) {
            double limit = reprocessingLimitWatts();
            if (!(limit > 0.0) || !assembly) {
                return 0.0;
            }
            if (limit != reprocessLimitW) {
                reprocessLimitW = limit;
                reprocessCoolS = AssemblyDecayHeat.secondsToCoolBelow(burnupMwdPerTonne, heavyMetalKg, limit);
            }
            if (Double.isInfinite(reprocessCoolS)) {
                return reprocessCoolS;
            }
            return Math.max(0.0, reprocessCoolS - coolingSeconds(gameTime));
        }
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
