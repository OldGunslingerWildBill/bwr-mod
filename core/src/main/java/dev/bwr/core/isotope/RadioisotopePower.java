package dev.bwr.core.isotope;

import dev.bwr.core.grid.EnergyUnits;
import dev.bwr.core.radiation.DoseModel;

/**
 * The arithmetic of a radioisotope thermoelectric generator (RTG). Pure functions, no Minecraft, no tables: the
 * isotope numbers (specific power, half-life, capsule mass) arrive as arguments, from the mod's data map.
 *
 * <h2>The chain</h2>
 * <pre>
 *   thermal W  = grams x specific power (W/g) x 2^(-t / half-life)
 *   electric W = thermal W x {@link #THERMOCOUPLE_EFFICIENCY}
 *   FE/t       = electric W / {@link EnergyUnits#WATTS_PER_FE_PER_TICK}  x the server's output multiplier (default 1)
 * </pre>
 * The last step is the mod's one existing conversion, 13.4 W per FE/t (the turbine auxiliaries' 75 kW is 5,598 FE/t);
 * there is no second one. A radioisotope source is small by the plant's standards: 1 W of electricity is 0.0746 FE/t.
 *
 * <h2>Efficiency</h2>
 * Real units sit at 5 to 7 % with Pu-238 and silicon-germanium or lead-telluride couples (MMRTG about 110 W from about
 * 2,000 W thermal, 5.5 %; GPHS-RTG 300 W from 4,400 W, 6.8 %) and at 2.4 to 5.5 % with strontium-90 (Beta-M 10 W from
 * 230 W, 4.3 %), whose heat source runs cooler. One figure for every isotope, 5 %, between them.
 *
 * <h2>Decay</h2>
 * Exponential in world time, {@code 2^(-t / half-life)}. The half-lives are decades to centuries and a game session is
 * hours, so over a play session the factor is within a few thousandths of 1 (Sr-90, 28.9 years: 100 hours of
 * running costs 0.027 %); it is computed because it is true, not because it is felt.
 */
public final class RadioisotopePower {

    private RadioisotopePower() {
    }

    /** Julian year in seconds. */
    public static final double SECONDS_PER_YEAR = 365.25 * 86_400.0;

    /** Thermocouple efficiency, electric over thermal, the same for every isotope. */
    public static final double THERMOCOUPLE_EFFICIENCY = 0.05;

    /**
     * What an RTG's body lets through of the capsules' radiation (a tungsten and steel housing: the Soviet Sr-90 units
     * took the dose at 1 m from 1 to 2 Sv/h of bare core down to a tenth of a mSv/h, four orders of magnitude).
     */
    public static final double BODY_TRANSMISSION = 1.0e-4;

    /** Server ticks per second. */
    public static final double TICKS_PER_SECOND = 20.0;

    /** Fraction of the original activity left after {@code elapsedSeconds}: {@code 2^(-t / half-life)}, 1 for a stable or unset source. */
    public static double decayFactor(double halfLifeYears, double elapsedSeconds) {
        if (!(halfLifeYears > 0.0) || !(elapsedSeconds > 0.0) || !Double.isFinite(halfLifeYears) || !Double.isFinite(elapsedSeconds)) {
            return 1.0;
        }
        return Math.pow(0.5, elapsedSeconds / (halfLifeYears * SECONDS_PER_YEAR));
    }

    /** Thermal power of {@code grams} of an isotope that has been decaying for {@code elapsedSeconds}, W. */
    public static double thermalWatts(double grams, double specificPowerWPerG, double halfLifeYears, double elapsedSeconds) {
        if (!(grams > 0.0) || !(specificPowerWPerG > 0.0)) {
            return 0.0;
        }
        return grams * specificPowerWPerG * decayFactor(halfLifeYears, elapsedSeconds);
    }

    /** Electric power out of a thermal power, W. */
    public static double electricWatts(double thermalWatts, double efficiency) {
        return thermalWatts > 0.0 && efficiency > 0.0 ? thermalWatts * efficiency : 0.0;
    }

    /** Watts as FE per tick on the mod's one conversion, times the server's output multiplier. */
    public static double fePerTick(double watts, double outputMultiplier) {
        return watts > 0.0 && outputMultiplier > 0.0 ? watts / EnergyUnits.WATTS_PER_FE_PER_TICK * outputMultiplier : 0.0;
    }

    /**
     * Gamma power of a source as the plant's dose model counts it: grams times the photon power per gram (the energy
     * the isotope's decays send out as photons, W/g) times what its container lets through.
     */
    public static double gammaWatts(double grams, double gammaWattsPerGram, double transmission) {
        if (!(grams > 0.0) || !(gammaWattsPerGram > 0.0) || !(transmission > 0.0)) {
            return 0.0;
        }
        return grams * gammaWattsPerGram * Math.min(1.0, transmission);
    }

    /** Dose rate in mSv/h at {@code distanceM} from a source of {@code gammaWatts} behind {@code transmission}. */
    public static double doseRateMsvPerH(double gammaWatts, double distanceM, double transmission) {
        return DoseModel.pointSourceMsvPerH(gammaWatts, distanceM, transmission);
    }
}
