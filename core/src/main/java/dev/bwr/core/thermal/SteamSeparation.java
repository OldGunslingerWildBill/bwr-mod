package dev.bwr.core.thermal;

import dev.bwr.core.PhysicalConstants;

/**
 * Steam separation, carryover, carryunder and the natural circulation chimney, as pure functions of the drawn vessel's
 * proportions and the water level. See {@code VESSEL-HEIGHT.md} for the derivations and sources.
 *
 * <h2>Two numbers the vessel hands the plant</h2>
 * <ul>
 *   <li><b>Carryover</b>, here called steam moisture: the mass fraction of liquid in the steam that leaves the vessel
 *       for the turbine. Design value 0.1 % [owner brief; the Columbia FSAR quotes 0.25 % at the main steam valves
 *       (s.5.4, PDF p 2172) and 0.30 % at the turbine inlet (s.10.1, PDF p 3529), which include line margin].</li>
 *   <li><b>Carryunder</b>: the mass fraction of steam in the water the separators return to the downcomer. Design value
 *       0.25 % (an engineering estimate: no figure for Columbia was found). It mixes into the water that is cooled by
 *       the feedwater, so it eats the core inlet subcooling and thins the downcomer's density, which is the head that
 *       drives natural circulation.</li>
 * </ul>
 * The Columbia FSAR says what they depend on: "The optimum reactor vessel water level is determined by the requirements
 * of the steam separators. The separators limit water carry-over in the steam going to the turbines and limit steam
 * carry-under in water returning to the core" (s.7.7.1.4, PDF p 2911); the high level trip L8 "is intended to prohibit
 * moisture carryover to the main turbine" (s.15.3.1.2.3.2, PDF p 4286).
 *
 * <h2>What acts relative to what</h2>
 * <ul>
 *   <li>The turbine side (work, enthalpy, blade wear) is calibrated with a design-quality separator, 0.1 % moisture, so
 *       only the <i>excess over design</i> acts on it. A vessel at true scale in its band is the turbine as it has always
 *       behaved, bit for bit.</li>
 *   <li>The reactor side (the rated core inlet subcooling and the natural circulation line) is calibrated with the mod's
 *       reference vessel, 17 x 17 x 28, so only the carryunder <i>difference from the reference build</i> acts on it.
 *       The reference plays exactly as it did before this model existed; a taller vessel gains, a shorter one loses.</li>
 * </ul>
 */
public final class SteamSeparation {

    private SteamSeparation() {
    }

    // ---------------------------------------------------------------
    // Real elevations, inches above vessel zero (Columbia FSAR Fig 5.3-3, PDF p 2157; RPV-REFERENCE.md)
    // ---------------------------------------------------------------

    /** L3 low level scram, 540.5 in: the bottom of the operating band, at the foot of the separator barrels (540 in). */
    public static final double L3_VESSEL_IN = 540.5;
    /** L8 high level trip, 582.0 in: the top of the band. The trip exists to stop carryover reaching the turbine. */
    public static final double L8_VESSEL_IN = 582.0;
    /** Normal water level, 563.55 in: on the separator barrels, 41.1 in of band below L8, 23.1 in above L3. */
    public static final double NORMAL_VESSEL_IN = 563.55;
    /** Top of the separator top section, 632 in; the steam nozzle centreline is at 648 in. */
    public static final double SEPARATOR_TOP_VESSEL_IN = 632.0, STEAM_NOZZLE_VESSEL_IN = 648.0;

    // ---------------------------------------------------------------
    // Separator and dryer
    // ---------------------------------------------------------------

    /** Moisture the steam leaves a design-quality separator and dryer train with: 0.1 %. */
    public static final double DESIGN_MOISTURE = 0.001;

    /**
     * Moisture of the two-phase mixture entering the separators: one minus the core exit quality. Rated exit quality is
     * 0.148 (VoidModel), so 0.852 of the mixture is water. This is the moisture of a vessel with no separation at all.
     */
    public static final double INLET_MOISTURE = 1.0 - 0.148;

    /**
     * Fraction of the separation that does not shorten when the stack is squashed: the swirl vanes, pickoff rings and
     * the dryer's vane plates are the same plate geometry at any height. Only the standpipe and barrel (the swirl run)
     * lose length. A game calibration, chosen so a 0.5 scale vessel is wet but plainly operable.
     */
    public static final double FIXED_SEPARATION_FRACTION = 0.3;

    /**
     * The effective separation length of a vessel drawn at {@code separatorRatio} of true scale, 0..1: the fixed part
     * plus the part that scales.
     */
    public static double effectiveSeparationLength(double separatorRatio) {
        double r = clamp(separatorRatio, 0.0, 1.0);
        return FIXED_SEPARATION_FRACTION + (1.0 - FIXED_SEPARATION_FRACTION) * r;
    }

    /**
     * Steam moisture at a vessel's normal water level, mass fraction. Separation is a removal along the swirl and vane
     * path, so the moisture falls off exponentially with the length of that path:
     * {@code w = w_in * (w_design / w_in) ^ s} where {@code s} is {@link #effectiveSeparationLength}. At true scale this
     * is exactly the design 0.1 %; with no separation it is the inlet moisture.
     */
    public static double bandMoisture(double separatorRatio) {
        double s = effectiveSeparationLength(separatorRatio);
        if (s >= 1.0) {
            return DESIGN_MOISTURE; // exactly, not within rounding: the excess over design must be zero
        }
        return INLET_MOISTURE * Math.pow(DESIGN_MOISTURE / INLET_MOISTURE, s);
    }

    /**
     * The e-fold height of carryover above L8, inches: the moisture multiplies by e for each 6 in the level climbs above
     * the band. The separator top section is 50 in above L8, the steam nozzle 66, so the level that floods the separators
     * (about 25 in over L8) turns a design-quality train into a mostly-water steam line. Game calibration.
     */
    public static final double CARRYOVER_E_FOLD_IN = 6.0;

    /**
     * Total steam moisture at a water level, mass fraction. Flat in the band, then {@code exp((L - L8) / 6 in)} above L8,
     * never more than the inlet moisture.
     *
     * @param levelVesselIn the physical water level, inches above vessel zero (instrument level plus 530 in)
     */
    public static double moisture(double separatorRatio, double levelVesselIn) {
        double w = bandMoisture(separatorRatio);
        if (levelVesselIn > L8_VESSEL_IN) {
            w *= Math.exp(Math.min(60.0, (levelVesselIn - L8_VESSEL_IN) / CARRYOVER_E_FOLD_IN));
        }
        return Math.min(INLET_MOISTURE, w);
    }

    /** Moisture over the design 0.1 %, the only part the plant feels. Zero at or better than design. */
    public static double excessMoisture(double moisture) {
        return Math.max(0.0, moisture - DESIGN_MOISTURE);
    }

    // ---------------------------------------------------------------
    // Carryunder
    // ---------------------------------------------------------------

    /** Design carryunder, mass fraction of the separators' return flow that is steam: 0.25 % (estimate). */
    public static final double DESIGN_CARRYUNDER = 0.0025;

    /** How many times design the carryunder of a vessel with no swirl run at all would be. */
    public static final double SQUASHED_CARRYUNDER_MULTIPLE = 6.0;

    /** The e-fold depth of carryunder below L3, inches: it rises to its ceiling over about 24 in. */
    public static final double CARRYUNDER_E_FOLD_IN = 12.0;

    /** Most steam the return flow can carry: 10 % of its mass (a separator whose return annuli have run dry). */
    public static final double MAX_CARRYUNDER = 0.10;

    /**
     * Carryunder at a vessel's normal level, mass fraction: design times {@link #SQUASHED_CARRYUNDER_MULTIPLE} to the
     * power of the squash, so it is 0.25 % at true scale. A shorter swirl run gives the steam less time to break out
     * before the water leaves the barrel.
     */
    public static double bandCarryunder(double separatorRatio) {
        double s = effectiveSeparationLength(separatorRatio);
        return DESIGN_CARRYUNDER * Math.pow(SQUASHED_CARRYUNDER_MULTIPLE, 1.0 - s);
    }

    /**
     * Total carryunder at a water level, mass fraction: the band value, plus below L3 a rise toward
     * {@link #MAX_CARRYUNDER} as the barrels' water seal is lost.
     */
    public static double carryunder(double separatorRatio, double levelVesselIn) {
        double base = bandCarryunder(separatorRatio);
        if (levelVesselIn < L3_VESSEL_IN) {
            double rise = 1.0 - Math.exp(-(L3_VESSEL_IN - levelVesselIn) / CARRYUNDER_E_FOLD_IN);
            base += (MAX_CARRYUNDER - base) * rise;
        }
        return Math.min(MAX_CARRYUNDER, base);
    }

    /** Carryunder over design, the part the plant feels. */
    public static double excessCarryunder(double carryunder) {
        return Math.max(0.0, carryunder - DESIGN_CARRYUNDER);
    }

    /**
     * Where the level stands in the separators' operating band: 0 at L3, 1 at L8, 0.54 at the normal level; negative below
     * the band (carryunder), above 1 over it (carryover).
     */
    public static double bandPosition(double levelVesselIn) {
        return (levelVesselIn - L3_VESSEL_IN) / (L8_VESSEL_IN - L3_VESSEL_IN);
    }

    /** Vessel inches for a level on the instrument scale. */
    public static double vesselInchesFromInstrument(double instrumentLevelIn) {
        return instrumentLevelIn + PhysicalConstants.INSTRUMENT_ZERO_ABOVE_VESSEL_ZERO_IN;
    }

    // ---------------------------------------------------------------
    // Chimney and the downcomer
    // ---------------------------------------------------------------

    /**
     * Share of the natural circulation loop's resistance that does not change with the chimney's length: the core, the
     * inlet orifices and the downcomer. The rest is friction and form loss along the riser and standpipes and grows with
     * length. Game calibration: it sets the cap, {@code 1 / sqrt(1 - fixed)} = 1.41.
     */
    public static final double FIXED_LOOP_RESISTANCE = 0.5;

    /**
     * Natural circulation flow over its calibrated value for a chimney {@code x} times the reference build's.
     * The driving head is the density difference over the column, {@code H}; the resistance is a fixed part plus a part
     * proportional to {@code H}, and flow goes as the square root of their ratio:
     * <pre>  f(x) = sqrt( x / (a + (1 - a) x) ),   a = 0.5   =>  f(1) = 1, f(infinity) = 1.41 </pre>
     * A short chimney is square-root weak; a tall one is stronger, but its own friction caps the gain.
     */
    public static double chimneyFactor(double chimneyRatio) {
        double x = Math.max(0.0, chimneyRatio);
        if (x == 1.0) {
            return 1.0;
        }
        double a = FIXED_LOOP_RESISTANCE;
        return Math.sqrt(x / (a + (1.0 - a) * x));
    }

    /** Void fraction the chimney's average mixture is taken to have when carryunder thins the downcomer (ratio of the head it must beat). */
    public static final double CHIMNEY_MEAN_VOID = 0.5;

    /**
     * Natural circulation head left after carryunder, as a flow factor. Steam in the downcomer water of mass fraction
     * {@code x} is a homogeneous void {@code alpha = x / (x + (1 - x) rho_g / rho_f)}, which lowers the downcomer
     * density by {@code alpha} and so the driving head by {@code alpha / (1 - mean chimney void)}; flow goes as the
     * square root of the head.
     *
     * @param carryunderChange carryunder over the build the loop is calibrated to, mass fraction, signed (negative is less steam and more head)
     * @param densityRatio     steam over water density at the dome pressure
     */
    public static double carryunderDriveFactor(double carryunderChange, double densityRatio) {
        double x = carryunderChange;
        if (x == 0.0 || !Double.isFinite(x)) {
            return 1.0;
        }
        double alpha = x / (x + (1.0 - x) * densityRatio);
        return Math.sqrt(Math.max(0.0, 1.0 - alpha / (1.0 - CHIMNEY_MEAN_VOID)));
    }

    /**
     * Pressure added at the foot of the core by the water standing above it, psi: {@code rho_f g H}. The saturation
     * temperature there is higher than in the dome by this pressure's worth. Read-out only: the rated core inlet
     * subcooling the model reproduces already includes the real column.
     *
     * @param depthIn equivalent inches of water above the core's bottom
     */
    public static double hydrostaticRisePsi(double depthIn, double liquidDensityKgPerM3) {
        double pascals = liquidDensityKgPerM3 * 9.80665 * depthIn * PressureVessel.METRES_PER_INCH;
        return pascals / 6894.757;
    }

    private static double clamp(double v, double lo, double hi) {
        return Double.isFinite(v) ? Math.max(lo, Math.min(hi, v)) : lo;
    }
}
