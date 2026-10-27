package dev.bwr.core.thermal;

import dev.bwr.core.PhysicalConstants;
import dev.bwr.core.fuel.FuelAssembly;

/**
 * Decay heat of one discharged fuel assembly, from its burnup and the time since it left the core.
 *
 * <h2>The curve is the reactor's own</h2>
 * This is {@link DecayHeat}'s eighteen-group fit (amplitudes and decay constants, tabulated there against the
 * standard ANS-5.1 infinite-operation curve for U-235 to 1.3 %), evaluated in closed form instead of integrated:
 * a group that was filled by operating for {@code T} seconds and then left to decay for {@code t} seconds holds
 *
 * <pre>
 *   H_i(T, t) = a_i * (1 - exp(-lambda_i * T)) * exp(-lambda_i * t)
 * </pre>
 *
 * of its saturated amount, and the assembly's decay power is {@code P_op * sum_i H_i}. It is the same
 * solution {@link DecayHeat#setToOperatingHistory} builds for a core and {@link DecayHeat#projectedFractionOfRated}
 * runs forward, so a pool and a scrammed reactor agree about the same fuel at the same age (a unit test checks
 * that to rounding).
 *
 * <h2>What the assembly is assumed to have done</h2>
 * Nothing on the item records how long or how hard the assembly ran, only its burnup. The operating time that
 * produced a burnup {@code B} (MWd/t) at the core-average specific power {@code p} is {@code T = B / p}, and the
 * assembly is taken to have run at that power throughout: {@code P_op = p * m}. {@code p} is the mod's own
 * core average, rated thermal power over the heavy metal in its full core (about 26.6 MW per tonne).
 * An assembly that spent its life in a hotter position left with more decay heat and one that sat on the periphery
 * with less; the average is the honest single number. A short run fills only the fast groups, so a bundle with a
 * burnup of a few MWd/t has very little long-lived heat, and the infinite-burnup limit is the bounding case.
 *
 * <p>For scale, the way-wigner approximation {@code P/P0 = 0.0622 (t^-0.2 - (t+T)^-0.2)} (t and T in seconds) is
 * within about 15 % of the fit between a minute and a month and is used as a cross-check in the unit test, not as the
 * model. Pure Java, no Minecraft.
 */
public final class AssemblyDecayHeat {

    private AssemblyDecayHeat() {
    }

    private static final double SECONDS_PER_DAY = 86_400.0;

    /**
     * Core-average specific power, MW of thermal power per tonne of heavy metal: the rated power over the heavy
     * metal in {@link PhysicalConstants#FUEL_ASSEMBLIES} reference assemblies. About 26.6.
     */
    public static final double SPECIFIC_POWER_MW_PER_TONNE = PhysicalConstants.RATED_THERMAL_MW
            / (PhysicalConstants.FUEL_ASSEMBLIES * FuelAssembly.REFERENCE_HEAVY_METAL_MASS_KG / 1000.0);

    /** Thermal power of a reference assembly while it was in the core, W. About 4.8 MW. */
    public static final double REFERENCE_OPERATING_POWER_W =
            SPECIFIC_POWER_MW_PER_TONNE * 1.0e6 * FuelAssembly.REFERENCE_HEAVY_METAL_MASS_KG / 1000.0;

    /** Five years, the cooling time given to an assembly whose discharge time is not known ("long-cooled"). */
    public static final double LONG_COOLED_SECONDS = 5.0 * 365.25 * SECONDS_PER_DAY;

    /** Longest cooling time searched by {@link #secondsToCoolBelow}; beyond it the answer is "never" (infinity). */
    private static final double SEARCH_LIMIT_SECONDS = 1.0e10;

    /** Seconds of full-power operation that produce this burnup at the core-average specific power. */
    public static double operatingSeconds(double burnupMwdPerTonne) {
        if (!(burnupMwdPerTonne > 0.0) || !Double.isFinite(burnupMwdPerTonne)) {
            return 0.0;
        }
        return burnupMwdPerTonne / SPECIFIC_POWER_MW_PER_TONNE * SECONDS_PER_DAY;
    }

    /**
     * Decay heat as a fraction of the operating power: the sum over the groups of
     * {@code a_i (1 - exp(-lambda_i T)) exp(-lambda_i t)}.
     *
     * @param operatingSeconds how long the assembly ran at power, {@code T}
     * @param coolingSeconds   time since it stopped, {@code t}; negative is taken as zero
     */
    public static double fractionOfOperatingPower(double operatingSeconds, double coolingSeconds) {
        if (!(operatingSeconds > 0.0) || !Double.isFinite(operatingSeconds)) {
            return 0.0;
        }
        double t = (coolingSeconds > 0.0 && Double.isFinite(coolingSeconds)) ? coolingSeconds : 0.0;
        double sum = 0.0;
        for (int i = 0; i < DecayHeat.GROUP_COUNT; i++) {
            double lambda = DecayHeat.DECAY_CONSTANT_PER_SECOND[i];
            sum += DecayHeat.AMPLITUDE_FRACTION_OF_RATED[i]
                    * (1.0 - Math.exp(-lambda * operatingSeconds)) * Math.exp(-lambda * t);
        }
        return sum;
    }

    /**
     * Decay power of an assembly, watts.
     *
     * @param burnupMwdPerTonne accumulated exposure, MWd per tonne of heavy metal
     * @param heavyMetalKg      heavy metal in the assembly
     * @param coolingSeconds    time since it left the core (use {@link #LONG_COOLED_SECONDS} when unknown)
     */
    public static double powerWatts(double burnupMwdPerTonne, double heavyMetalKg, double coolingSeconds) {
        if (!(heavyMetalKg > 0.0) || !Double.isFinite(heavyMetalKg)) {
            return 0.0;
        }
        return operatingPowerWatts(heavyMetalKg) * fractionOfOperatingPower(operatingSeconds(burnupMwdPerTonne), coolingSeconds);
    }

    /** The power the assembly is taken to have run at, W: the core-average specific power times its heavy metal. */
    private static double operatingPowerWatts(double heavyMetalKg) {
        return SPECIFIC_POWER_MW_PER_TONNE * 1.0e6 * heavyMetalKg / 1000.0;
    }

    /**
     * One assembly's decay curve with the part that depends only on its history worked out once: the filled fraction of each
     * group, {@code a_i (1 - exp(-lambda_i T))}. {@link #powerWatts(double)} then costs one exponential per group instead of
     * two, and returns exactly (to the last bit) what {@link AssemblyDecayHeat#powerWatts(double, double, double)} returns for
     * the same burnup, heavy metal and age: the products and the sum are done in the same order. A spent fuel pool keeps one
     * per stored assembly so that thousands of them can be re-evaluated every second.
     */
    public static final class Curve {
        private final double[] filled;
        private final double operatingPowerW;

        public Curve(double burnupMwdPerTonne, double heavyMetalKg) {
            if (!(heavyMetalKg > 0.0) || !Double.isFinite(heavyMetalKg)) {
                filled = null;
                operatingPowerW = 0.0;
                return;
            }
            operatingPowerW = operatingPowerWatts(heavyMetalKg);
            double operating = operatingSeconds(burnupMwdPerTonne);
            if (!(operating > 0.0) || !Double.isFinite(operating)) {
                filled = null;
                return;
            }
            filled = new double[DecayHeat.GROUP_COUNT];
            for (int i = 0; i < DecayHeat.GROUP_COUNT; i++) {
                filled[i] = DecayHeat.AMPLITUDE_FRACTION_OF_RATED[i]
                        * (1.0 - Math.exp(-DecayHeat.DECAY_CONSTANT_PER_SECOND[i] * operating));
            }
        }

        /** Decay power after {@code coolingSeconds} of cooling, W; negative is taken as zero. */
        public double powerWatts(double coolingSeconds) {
            if (filled == null) {
                return operatingPowerW * 0.0;
            }
            double t = (coolingSeconds > 0.0 && Double.isFinite(coolingSeconds)) ? coolingSeconds : 0.0;
            double sum = 0.0;
            for (int i = 0; i < DecayHeat.GROUP_COUNT; i++) {
                sum += filled[i] * Math.exp(-DecayHeat.DECAY_CONSTANT_PER_SECOND[i] * t);
            }
            return operatingPowerW * sum;
        }
    }

    /**
     * Seconds of cooling after which the assembly's decay power is at or below {@code limitWatts}: zero if it
     * already is at discharge, positive infinity if it never gets there inside 1e10 s
     * (about 300 years). The decay power falls monotonically, so this bisects.
     */
    public static double secondsToCoolBelow(double burnupMwdPerTonne, double heavyMetalKg, double limitWatts) {
        if (powerWatts(burnupMwdPerTonne, heavyMetalKg, 0.0) <= limitWatts) {
            return 0.0;
        }
        if (powerWatts(burnupMwdPerTonne, heavyMetalKg, SEARCH_LIMIT_SECONDS) > limitWatts) {
            return Double.POSITIVE_INFINITY;
        }
        double low = 0.0;
        double high = SEARCH_LIMIT_SECONDS;
        for (int i = 0; i < 200 && high - low > 1.0e-3 * Math.max(1.0, low); i++) {
            double mid = 0.5 * (low + high);
            if (powerWatts(burnupMwdPerTonne, heavyMetalKg, mid) > limitWatts) {
                low = mid;
            } else {
                high = mid;
            }
        }
        return high;
    }
}
