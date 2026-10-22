package dev.bwr.core.radiation;

import dev.bwr.core.PhysicalConstants;
import dev.bwr.core.accident.ContainmentAtmosphere;
import dev.bwr.core.thermal.DecayHeat;

import java.util.Locale;

/**
 * Turns the plant's hazard numbers into a dose rate a person could measure. Pure arithmetic, no Minecraft.
 *
 * <p><b>The one unit is the millisievert per hour (mSv/h).</b> Everything that reports a dose rate in this mod (the
 * survey meter, the area monitor, the CC peripheral, the signal layer) is in mSv/h, and an accumulated dose is in mSv.
 * Gamma only, so 1 Gy = 1 Sv.
 *
 * <h2>This is not a second radiation model</h2>
 * The hazard model already exists: {@code ContainmentAtmosphere} tracks the fraction of the core's fission product
 * inventory that is airborne in containment, deposited on plant surfaces (decaying with the contamination half-life) or
 * released to the site, and the world adapter turns those fractions into a hazard that falls off with distance. This
 * class only supplies the missing physical scale for that hazard, and one direct term the contamination model does not
 * have, the gamma from the core itself.
 *
 * <h2>Contamination: hazard to mSv/h</h2>
 * A hazard of 1.0 means the whole fission product inventory of the core. Its gamma output is not invented here, it is
 * the model's own source term:
 * <pre>
 *   W_inv  = GAMMA_FRACTION_OF_DECAY_HEAT x decay heat one day after shutdown x rated thermal power
 *          = 0.5 x DecayHeat.projectedFractionOfRated(86400 s) x 3579 MW      (about 9.4 MW of gamma)
 * </pre>
 * One day because the contamination half-life is one day: that is the age the model gives the inventory. That
 * inventory spread through the containment's free volume V ({@code FREE_VOLUME_AT_RATED_M3}, 8000 m3, an equivalent
 * sphere of radius R = 12.4 m, which is also the model's in-plant half distance of 12 blocks) gives a person at the
 * centre the dose rate of a uniform volume source,
 * <pre>
 *   D = (W_inv / V) x (mu_en/rho)_tissue x R x (1 - e^(-mu_air R)) / (mu_air R)       ~ 1.5e5 Sv/h
 * </pre>
 * with (mu_en/rho) of soft tissue at 1 MeV (0.0031 m2/kg) and mu_air = 0.0076 per m. That is the bare, unshielded
 * figure. The plant is not bare: pedestal, piping, pool, steel and concrete stand between a person and most of the
 * activity. {@link #STRUCTURE_SHIELDING_FACTOR} (1/100) is the one calibrated number of the conversion, checked against
 * published accident dose rates (Fukushima Daiichi 2 pedestal, about 530 Sv/h, is a few tenths of an inventory here).
 * The result is {@link #MSV_PER_H_PER_INVENTORY}, about 1.5e6 mSv/h per unit hazard, so the existing effect thresholds
 * land where radiology puts them: hunger at hazard 1e-4 is about 150 mSv/h, weakness and poison at 1e-3 about 1.5 Sv/h,
 * wither at 1e-2 about 15 Sv/h. The effects are deliberately mild next to the real doses; the meter is honest.
 * The site hazard uses the same conversion, because the model already weights it ({@code SITE_HAZARD_WEIGHT}).
 *
 * <h2>The core itself: gamma from fission and decay heat</h2>
 * Gamma power of the running or shut-down core is {@code rated x (0.07 x fission fraction + 0.5 x decay heat fraction)}:
 * about 7 % of fission energy leaves as prompt and capture gamma, about half of decay heat is gamma. As a point source at
 * the core centre the dose rate at distance r is {@code W x (mu_en/rho) x e^(-mu_air r) / (4 pi r^2)}, multiplied by the
 * transmission of what is in the way:
 * <ul>
 *   <li>a steel vessel wall, 15 cm, mu 47 per m: {@link #VESSEL_WALL_TRANSMISSION} = 8.5e-4;</li>
 *   <li>the water around the core, 1 m of downcomer at mu 7.07 per m, scaled by how much of the fuel is covered:
 *       8.5e-4 covered, 1 uncovered;</li>
 *   <li>the biological shield and the building, {@link #BIOLOGICAL_SHIELD_TRANSMISSION} = 1e-4 (concrete with build-up,
 *       chosen so that rated power reads a couple of tenths of a mSv/h at 10 m, the order of a real controlled area;
 *       a day after shutdown it reads a hundredth of that).</li>
 * </ul>
 * Looking down an open head, the vessel wall and the biological shield are not in the way, only the water above the fuel:
 * {@code e^(-7.07 x depth)}. With the fuel uncovered that is nothing at all. After the lower head fails the vessel wall
 * and its water are gone too.
 */
public final class DoseModel {
    private DoseModel() {
    }

    /** Millisievert per hour for one gray per second (3600 s, 1000 mSv per Sv, gamma so Sv = Gy). */
    public static final double MSV_PER_H_PER_GY_PER_S = 3.6e6;

    /** Mass energy absorption coefficient of soft tissue for 1 MeV photons, m2/kg (ICRU 0.0309 cm2/g). */
    public static final double TISSUE_ABSORPTION_M2_PER_KG = 0.0031;

    /** Attenuation of 1 MeV photons in air, per metre. */
    public static final double AIR_ATTENUATION_PER_M = 0.0076;

    /** Effective attenuation of gamma in water, per metre. */
    public static final double WATER_ATTENUATION_PER_M = 7.07;

    /** Attenuation in steel, per metre. */
    public static final double STEEL_ATTENUATION_PER_M = 47.1;

    /** Share of decay heat that leaves as gamma rather than staying in the fuel as beta heat. */
    public static final double GAMMA_FRACTION_OF_DECAY_HEAT = 0.5;

    /** Share of fission energy that leaves the core as prompt and capture gamma. */
    public static final double PROMPT_GAMMA_FRACTION_OF_FISSION_POWER = 0.07;

    /** Age of the released inventory, seconds: the contamination half-life of the containment model. */
    public static final double INVENTORY_AGE_S = ContainmentAtmosphere.CONTAMINATION_HALF_LIFE_S;

    /** Steel wall of the reactor pressure vessel, metres. */
    public static final double VESSEL_WALL_M = 0.15;

    /** Water between the core and the vessel wall (downcomer and shroud), metres. */
    public static final double DOWNCOMER_WATER_M = 1.0;

    /** Fraction of the core's gamma that gets through the vessel wall. */
    public static final double VESSEL_WALL_TRANSMISSION = Math.exp(-STEEL_ATTENUATION_PER_M * VESSEL_WALL_M);

    /** Fraction that gets through the biological shield, build-up included. */
    public static final double BIOLOGICAL_SHIELD_TRANSMISSION = 1.0e-4;

    /**
     * The calibrated number: what plant structure takes off the bare volume-source figure. See the class comment for
     * the check against published accident dose rates.
     */
    public static final double STRUCTURE_SHIELDING_FACTOR = 0.01;

    /**
     * Past this distance a source reads as nothing, metres. It is the reach of the existing hazard model
     * ({@code MAX_DOSE_DISTANCE_BLOCKS}), and it keeps a reading of zero meaning zero rather than a vanishing tail.
     */
    public static final double MAX_RANGE_M = 256.0;

    /** Gamma power of one whole core inventory at {@link #INVENTORY_AGE_S}, watts. */
    public static final double INVENTORY_GAMMA_WATTS = GAMMA_FRACTION_OF_DECAY_HEAT
            * DecayHeat.saturatedAt(1.0).projectedFractionOfRated(INVENTORY_AGE_S)
            * PhysicalConstants.RATED_THERMAL_MW * 1.0e6;

    /** Radius of the sphere with the containment's free volume, metres. */
    public static final double CONTAINMENT_RADIUS_M = Math.cbrt(
            3.0 * ContainmentAtmosphere.FREE_VOLUME_AT_RATED_M3 / (4.0 * Math.PI));

    /** Bare dose rate at the centre of a whole inventory spread through containment, mSv/h. */
    public static final double BARE_MSV_PER_H_PER_INVENTORY = INVENTORY_GAMMA_WATTS
            / ContainmentAtmosphere.FREE_VOLUME_AT_RATED_M3 * TISSUE_ABSORPTION_M2_PER_KG * CONTAINMENT_RADIUS_M
            * (1.0 - Math.exp(-AIR_ATTENUATION_PER_M * CONTAINMENT_RADIUS_M))
            / (AIR_ATTENUATION_PER_M * CONTAINMENT_RADIUS_M) * MSV_PER_H_PER_GY_PER_S;

    /** mSv/h for a hazard of 1.0 (the whole core inventory) at the reference point of the hazard model. */
    public static final double MSV_PER_H_PER_INVENTORY = BARE_MSV_PER_H_PER_INVENTORY * STRUCTURE_SHIELDING_FACTOR;

    /** The hazard of the containment model (fraction-of-inventory units, after distance) as a dose rate, mSv/h. */
    public static double doseRateFromHazard(double hazard) {
        return hazard > 0.0 && Double.isFinite(hazard) ? hazard * MSV_PER_H_PER_INVENTORY : 0.0;
    }

    /** A core making less gamma than this (a cold core's numerical residue is about 1e-6 W) emits nothing, watts. */
    public static final double MIN_CORE_GAMMA_WATTS = 1.0;

    /** Gamma power of the core, watts, from its fission power and its decay heat in MW. */
    public static double coreGammaWatts(double fissionPowerMW, double decayHeatMW) {
        double w = (PROMPT_GAMMA_FRACTION_OF_FISSION_POWER * Math.max(0.0, fissionPowerMW)
                + GAMMA_FRACTION_OF_DECAY_HEAT * Math.max(0.0, decayHeatMW)) * 1.0e6;
        return Double.isFinite(w) && w >= MIN_CORE_GAMMA_WATTS ? w : 0.0;
    }

    /** Transmission through a water layer, 0..1. */
    public static double waterTransmission(double depthM) {
        return Math.exp(-WATER_ATTENUATION_PER_M * Math.max(0.0, depthM));
    }

    /**
     * Transmission from the core to a point beside the vessel (the vessel wall, the downcomer water and the biological
     * shield), 0..1.
     *
     * @param fuelCoveredFraction the share of the active fuel under water, 0..1
     * @param lowerHeadFailed     the vessel wall (and its water) no longer surrounds the debris
     */
    public static double lateralTransmission(double fuelCoveredFraction, boolean lowerHeadFailed) {
        double covered = Math.min(1.0, Math.max(0.0, fuelCoveredFraction));
        double vessel = lowerHeadFailed ? 1.0
                : VESSEL_WALL_TRANSMISSION * waterTransmission(DOWNCOMER_WATER_M * covered);
        return vessel * BIOLOGICAL_SHIELD_TRANSMISSION;
    }

    /** Transmission from the core to a point looking down an open vessel: only the water above the fuel. */
    public static double openHeadTransmission(double waterDepthAboveFuelM) {
        return waterTransmission(waterDepthAboveFuelM);
    }

    /**
     * Dose rate of a point source of gamma, mSv/h.
     *
     * @param gammaWatts   gamma power of the source
     * @param distanceM    distance to the source; nearer than half a metre counts as half a metre, beyond {@link #MAX_RANGE_M} it reads zero
     * @param transmission what gets through whatever is in the way, 0..1
     */
    public static double pointSourceMsvPerH(double gammaWatts, double distanceM, double transmission) {
        if (!(gammaWatts > 0.0) || !(transmission > 0.0) || !Double.isFinite(gammaWatts)) {
            return 0.0;
        }
        if (distanceM > MAX_RANGE_M) {
            return 0.0;
        }
        double r = Math.max(0.5, Double.isFinite(distanceM) ? distanceM : 0.5);
        double gyPerS = gammaWatts * TISSUE_ABSORPTION_M2_PER_KG * Math.exp(-AIR_ATTENUATION_PER_M * r)
                / (4.0 * Math.PI * r * r);
        return gyPerS * Math.min(1.0, transmission) * MSV_PER_H_PER_GY_PER_S;
    }

    // ---------------------------------------------------------------
    // The instruments
    // ---------------------------------------------------------------

    /** Clicks per second the survey meter makes with no source at all (audio only, never part of a reading). */
    public static final double GEIGER_IDLE_CPS = 0.4;

    /** The fastest the meter clicks, per second. */
    public static final double GEIGER_MAX_CPS = 40.0;

    /**
     * Geiger click rate for a dose rate: {@code 0.4 + 2.5 x log2(1 + uSv/h)}, capped at 40 per second. A real counter's
     * rate is proportional to dose rate; a game speaker cannot play eight decades, so the rate is compressed with a
     * logarithm: 1 uSv/h clicks 3 times a second, 1 mSv/h 25 times, and 65 mSv/h and above is a continuous rattle.
     */
    public static double geigerClicksPerSecond(double msvPerH) {
        double microsievert = Math.max(0.0, Double.isFinite(msvPerH) ? msvPerH : 0.0) * 1000.0;
        double rate = GEIGER_IDLE_CPS + 2.5 * Math.log1p(microsievert) / Math.log(2.0);
        return Math.min(GEIGER_MAX_CPS, rate);
    }

    /** A dose rate with a sensible unit: uSv/h below 1 mSv/h, mSv/h below 1 Sv/h, Sv/h above (three significant digits). */
    public static String formatRate(double msvPerH) {
        double v = Double.isFinite(msvPerH) ? Math.max(0.0, msvPerH) : 0.0;
        if (v < 1.0) {
            return digits(v * 1000.0) + " µSv/h";
        }
        if (v < 1000.0) {
            return digits(v) + " mSv/h";
        }
        return digits(v / 1000.0) + " Sv/h";
    }

    /** An accumulated dose with a sensible unit: uSv, mSv or Sv. */
    public static String formatDose(double msv) {
        double v = Double.isFinite(msv) ? Math.max(0.0, msv) : 0.0;
        if (v < 1.0) {
            return digits(v * 1000.0) + " µSv";
        }
        if (v < 1000.0) {
            return digits(v) + " mSv";
        }
        return digits(v / 1000.0) + " Sv";
    }

    private static String digits(double v) {
        if (v >= 100.0) {
            return String.format(Locale.ROOT, "%.0f", v);
        }
        if (v >= 10.0) {
            return String.format(Locale.ROOT, "%.1f", v);
        }
        return String.format(Locale.ROOT, "%.2f", v);
    }
}
