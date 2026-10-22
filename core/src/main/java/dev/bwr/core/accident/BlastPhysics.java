package dev.bwr.core.accident;

/**
 * Energy, pressure and reach of accident blasts. Pure arithmetic: the world adapter
 * ({@code dev.bwr.mod.accident.AccidentBlast}) turns these numbers into broken blocks.
 *
 * <h2>The chain</h2>
 * <ol>
 *   <li><b>Energy.</b> A hydrogen burn releases {@value #HYDROGEN_LHV_MJ_PER_KG} MJ per kg (lower heating
 *       value). Only part of it leaves as a pressure wave: {@link #DEFLAGRATION_BLAST_YIELD} for a deflagration
 *       (slow flame, most heat goes into the gas), {@link #DETONATION_BLAST_YIELD} for a detonation. A
 *       bursting pressure vessel delivers its stored gas energy (isentropic expansion to atmosphere) with
 *       {@link #STEAM_BURST_BLAST_YIELD}; a bursting containment, {@link #GAS_BURST_BLAST_YIELD}.</li>
 *   <li><b>TNT equivalent.</b> Blast energy divided by {@value #TNT_MJ_PER_KG} MJ/kg. Hopkinson-Cranz scaling then
 *       says the pressure at range R from a charge of W kg is a function of {@code Z = R / W^(1/3)} only.</li>
 *   <li><b>Free-air overpressure.</b> The Kinney-Graham fit to the Hopkinson-Cranz curve
 *       ({@link #overpressurePsi}). A game-scale fit, good to a factor of about two against the standard
 *       charts, which is more accuracy than the rest of the chain has.</li>
 *   <li><b>Cap.</b> A deflagration in a closed room cannot exceed its adiabatic isochoric complete combustion
 *       pressure, which the containment model already computes ({@code lastBurnPeakPsig}), so the blast
 *       curve is clipped to it. A detonation's wall load is {@value #DETONATION_PRESSURE_FACTOR} times the
 *       absolute AICC pressure (Chapman-Jouguet over AICC is 1.9 to 2 for hydrogen-air).</li>
 *   <li><b>Block rating.</b> A block breaks where the local pressure exceeds its rating,
 *       {@link ContainmentWallStrength#psi}: {@code K * sqrt(blast resistance)}, the same number the containment
 *       survey uses to rate walls, so one block is as strong in a blast as in a wall.</li>
 * </ol>
 *
 * <p>These are game calibrations built on real scaling laws. They are not a structural response analysis;
 * there is no shock reflection, venting or shielding by intervening walls.
 */
public final class BlastPhysics {
    private BlastPhysics() {
    }

    // ---------------------------------------------------------------
    // Constants
    // ---------------------------------------------------------------

    /** Lower heating value of hydrogen, MJ/kg. */
    public static final double HYDROGEN_LHV_MJ_PER_KG = 120.0;

    /** Energy of one kg of TNT, MJ. */
    public static final double TNT_MJ_PER_KG = 4.184;

    /** Fraction of a deflagration's combustion energy delivered as blast. */
    public static final double DEFLAGRATION_BLAST_YIELD = 0.10;

    /** Fraction of a detonation's combustion energy delivered as blast (confined, so high). */
    public static final double DETONATION_BLAST_YIELD = 0.50;

    /** Chapman-Jouguet pressure over AICC pressure, absolute, for hydrogen-air. */
    public static final double DETONATION_PRESSURE_FACTOR = 2.0;

    /** Fraction of a pressure vessel's stored energy delivered as blast when it bursts. */
    public static final double STEAM_BURST_BLAST_YIELD = 0.40;

    /** Fraction of a containment's stored gas energy delivered as blast when it bursts. */
    public static final double GAS_BURST_BLAST_YIELD = 0.05;

    /** A wall that lets go suddenly sees its stored load with this dynamic overshoot. */
    public static final double SUDDEN_FAILURE_LOAD_FACTOR = 1.25;

    /** Atmospheric pressure, psi. */
    public static final double ATMOSPHERE_PSI = 14.696;

    private static final double PA_PER_PSI = 6894.757;
    private static final double ATMOSPHERE_KPA = 101.325;

    /** Ratio of specific heats used for expanding steam. */
    public static final double STEAM_GAMMA = 1.3;

    /** Ratio of specific heats used for the containment gas (nitrogen-like). */
    public static final double AIR_GAMMA = 1.4;

    /** Specific flash energy of saturated water at 1,400 psig released to atmosphere, MJ/kg. */
    public static final double FLASH_ENERGY_MJ_PER_KG_AT_1400 = 0.3;

    /**
     * Pressure below which nothing in the world is damaged, psi. Flimsy fixtures (light fittings, signs,
     * carpets) go at about this; it also floors the rating of zero-resistance blocks.
     */
    public static final double MIN_DAMAGE_PSI = 1.0;

    /** Blast length scale of a breach, as a fraction of W^(1/3), blocks per kg^(1/3). */
    public static final double BREACH_SCALE_PER_CBRT_TNT = 0.5;

    /** The widest hole a breach will ever open, blocks of radius. */
    public static final double MAX_BREACH_RADIUS_BLOCKS = 8.0;

    /** Never sweep further than this from the source, blocks. */
    public static final double MAX_REACH_BLOCKS = 40.0;

    /** Pipe whip and jet impingement radius range, blocks. */
    public static final double PIPE_WHIP_MIN_BLOCKS = 1.0;
    public static final double PIPE_WHIP_MAX_BLOCKS = 2.0;

    /** Pipe pressure below which the whip radius is at its minimum, psig. */
    public static final double PIPE_WHIP_FULL_RADIUS_PSIG = 1300.0;
    public static final double PIPE_WHIP_MIN_RADIUS_PSIG = 300.0;

    /** Jet core length: the distance at which the jet's stagnation pressure is half its source pressure, blocks. */
    public static final double JET_HALF_PRESSURE_BLOCKS = 0.7;

    /** Reference core fuel mass at rated size, kg. A BWR/4-class core holds about 140 t of fuel and cladding. */
    public static final double REFERENCE_CORE_MASS_KG = 140_000.0;

    /** Fuel specific energy above water temperature when it meets water, MJ/kg (about 2,500 K of sensible heat). */
    public static final double DEBRIS_THERMAL_MJ_PER_KG = 1.2;

    /** Fraction of that heat converted to blast in a molten fuel-coolant interaction. */
    public static final double FCI_BLAST_YIELD = 0.001;

    // ---------------------------------------------------------------
    // Energy
    // ---------------------------------------------------------------

    /** TNT equivalent of an energy, kg. */
    public static double tntKg(double energyJ) {
        return Math.max(0.0, finite(energyJ)) / (TNT_MJ_PER_KG * 1.0e6);
    }

    /** Combustion energy of burned hydrogen, J. */
    public static double combustionEnergyJ(double hydrogenKg) {
        return Math.max(0.0, finite(hydrogenKg)) * HYDROGEN_LHV_MJ_PER_KG * 1.0e6;
    }

    /** Blast TNT equivalent of a hydrogen deflagration, kg. */
    public static double deflagrationTntKg(double hydrogenKg) {
        return tntKg(combustionEnergyJ(hydrogenKg) * DEFLAGRATION_BLAST_YIELD);
    }

    /** Blast TNT equivalent of a hydrogen detonation, kg. */
    public static double detonationTntKg(double hydrogenKg) {
        return tntKg(combustionEnergyJ(hydrogenKg) * DETONATION_BLAST_YIELD);
    }

    /**
     * Energy of gas at {@code pressurePsig} in {@code volumeM3} expanding isentropically to atmosphere, J:
     * {@code P V / (g - 1) * (1 - (P0/P)^((g-1)/g))}.
     */
    public static double expansionEnergyJ(double pressurePsig, double volumeM3, double gamma) {
        double psia = Math.max(0.0, finite(pressurePsig)) + ATMOSPHERE_PSI;
        double v = Math.max(0.0, finite(volumeM3));
        double pa = psia * PA_PER_PSI;
        double work = pa * v / (gamma - 1.0) * (1.0 - Math.pow(ATMOSPHERE_PSI / psia, (gamma - 1.0) / gamma));
        return Math.max(0.0, work);
    }

    /**
     * Stored energy released by a reactor head failure, J: the steam dome expanding, plus the water that
     * flashes in the first second ({@code flashMassKg}) at about 0.3 MJ/kg at 1,400 psig, scaled with pressure.
     */
    public static double headFailureEnergyJ(double pressurePsig, double domeVolumeM3, double flashMassKg) {
        double dome = expansionEnergyJ(pressurePsig, domeVolumeM3, STEAM_GAMMA);
        double perKg = FLASH_ENERGY_MJ_PER_KG_AT_1400 * 1.0e6 * Math.clamp(finite(pressurePsig) / 1400.0, 0.0, 1.5);
        return dome + Math.max(0.0, finite(flashMassKg)) * perKg;
    }

    /** Blast TNT equivalent of a head failure, kg. */
    public static double headFailureTntKg(double pressurePsig, double domeVolumeM3, double flashMassKg) {
        return tntKg(headFailureEnergyJ(pressurePsig, domeVolumeM3, flashMassKg) * STEAM_BURST_BLAST_YIELD);
    }

    /** Blast TNT equivalent of a containment bursting at {@code pressurePsig} in {@code volumeM3}, kg. */
    public static double containmentBurstTntKg(double pressurePsig, double volumeM3) {
        return tntKg(expansionEnergyJ(pressurePsig, volumeM3, AIR_GAMMA) * GAS_BURST_BLAST_YIELD);
    }

    /**
     * Blast TNT equivalent of molten debris dropping into the cavity under a failed lower head, kg.
     *
     * @param debrisFraction fraction of the core that is debris in the lower plenum or ex-vessel, 0..1
     * @param sizeScale      the reactor's size relative to rated, which scales the core mass
     */
    public static double lowerHeadTntKg(double debrisFraction, double sizeScale) {
        double mass = Math.clamp(finite(debrisFraction), 0.0, 1.0) * REFERENCE_CORE_MASS_KG * Math.max(0.0, finite(sizeScale));
        return tntKg(mass * DEBRIS_THERMAL_MJ_PER_KG * 1.0e6 * FCI_BLAST_YIELD);
    }

    // ---------------------------------------------------------------
    // Pressure
    // ---------------------------------------------------------------

    /**
     * Free-air incident overpressure, psi, at {@code rangeM} metres from a charge of {@code tntKg}
     * (Kinney and Graham, 1985). One block is one metre.
     */
    public static double overpressurePsi(double tntKg, double rangeM) {
        double w = Math.max(0.0, finite(tntKg));
        if (w <= 0.0) {
            return 0.0;
        }
        double r = Math.max(0.1, finite(rangeM));
        double z = r / Math.cbrt(w);
        double ratio = 808.0 * (1.0 + sq(z / 4.5))
                / (Math.sqrt(1.0 + sq(z / 0.048)) * Math.sqrt(1.0 + sq(z / 0.32)) * Math.sqrt(1.0 + sq(z / 1.35)));
        return ratio * ATMOSPHERE_KPA * 1000.0 / PA_PER_PSI;
    }

    /** Range at which the overpressure of {@code tntKg} has fallen to {@code psi}, metres; 0 if it never reaches it. */
    public static double rangeForPsi(double tntKg, double psi) {
        double w = Math.max(0.0, finite(tntKg));
        double target = Math.max(1.0e-6, finite(psi));
        if (w <= 0.0 || overpressurePsi(w, 0.1) < target) {
            return 0.0;
        }
        double lo = 0.1, hi = 1.0e4;
        for (int i = 0; i < 60; i++) {
            double mid = 0.5 * (lo + hi);
            if (overpressurePsi(w, mid) > target) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    /** How far to sweep for damage from a source: where the blast has fallen to {@link #MIN_DAMAGE_PSI}, capped. */
    public static double reachBlocks(double tntKg) {
        return Math.min(MAX_REACH_BLOCKS, rangeForPsi(tntKg, MIN_DAMAGE_PSI));
    }

    /**
     * Pressure a hydrogen deflagration puts on something {@code rangeM} from the ignition point: the blast
     * curve, clipped to the room's AICC peak.
     */
    public static double deflagrationPsi(double tntKg, double aiccPeakPsig, double rangeM) {
        return Math.min(Math.max(0.0, finite(aiccPeakPsig)), overpressurePsi(tntKg, rangeM));
    }

    /** Wall load of a detonation, psig: {@value #DETONATION_PRESSURE_FACTOR} times the absolute AICC pressure. */
    public static double detonationPeakPsig(double aiccPeakPsig) {
        double absolute = Math.max(0.0, finite(aiccPeakPsig)) + ATMOSPHERE_PSI;
        return DETONATION_PRESSURE_FACTOR * absolute - ATMOSPHERE_PSI;
    }

    /** Pressure a detonation puts on something {@code rangeM} from the ignition point. */
    public static double detonationPsi(double tntKg, double aiccPeakPsig, double rangeM) {
        return Math.min(detonationPeakPsig(aiccPeakPsig), overpressurePsi(tntKg, rangeM));
    }

    /** What a block resists, psi: its rating, floored at {@link #MIN_DAMAGE_PSI}. */
    public static double effectiveRating(double ratingPsi) {
        return Math.max(MIN_DAMAGE_PSI, finite(ratingPsi));
    }

    // ---------------------------------------------------------------
    // Breach
    // ---------------------------------------------------------------

    /** The wall load that opens a breach, psig: the load, but never less than a sudden failure of the rating. */
    public static double breachLoadPsi(double loadPsig, double weakestRatingPsig) {
        return Math.max(finite(loadPsig), SUDDEN_FAILURE_LOAD_FACTOR * Math.max(0.0, finite(weakestRatingPsig)));
    }

    /** Length scale over which the wall load falls off around the weakest spot, blocks: {@code 0.5 W^(1/3)}. */
    public static double breachScaleBlocks(double tntKg) {
        return BREACH_SCALE_PER_CBRT_TNT * Math.cbrt(Math.max(0.0, finite(tntKg)));
    }

    /** Wall load {@code distance} blocks from the centre of the breach: {@code P / (1 + (d/s)^2)}. */
    public static double breachLoadAt(double centreLoadPsi, double scaleBlocks, double distance) {
        double s = Math.max(1.0e-6, finite(scaleBlocks));
        return Math.max(0.0, finite(centreLoadPsi)) / (1.0 + sq(Math.max(0.0, finite(distance)) / s));
    }

    /**
     * Radius of the hole, blocks: where the wall load has fallen to the rating, {@code s * sqrt(P/R - 1)},
     * zero when the load does not exceed the rating, capped at {@link #MAX_BREACH_RADIUS_BLOCKS}.
     */
    public static double breachRadiusBlocks(double tntKg, double centreLoadPsi, double ratingPsi) {
        double rating = Math.max(1.0e-6, finite(ratingPsi));
        double excess = finite(centreLoadPsi) / rating - 1.0;
        if (excess <= 0.0) {
            return 0.0;
        }
        return Math.min(MAX_BREACH_RADIUS_BLOCKS, breachScaleBlocks(tntKg) * Math.sqrt(excess));
    }

    // ---------------------------------------------------------------
    // Pipe whip
    // ---------------------------------------------------------------

    /** Radius of pipe whip and jet damage around a break at {@code pressurePsig}: 1 block up to 2, bounded. */
    public static double pipeWhipRadiusBlocks(double pressurePsig) {
        double t = (finite(pressurePsig) - PIPE_WHIP_MIN_RADIUS_PSIG)
                / (PIPE_WHIP_FULL_RADIUS_PSIG - PIPE_WHIP_MIN_RADIUS_PSIG);
        return PIPE_WHIP_MIN_BLOCKS + Math.clamp(t, 0.0, 1.0) * (PIPE_WHIP_MAX_BLOCKS - PIPE_WHIP_MIN_BLOCKS);
    }

    /** Jet stagnation pressure {@code distance} blocks from the break: {@code P / (1 + (d/0.7)^2)}. */
    public static double jetPsi(double pressurePsig, double distance) {
        return Math.max(0.0, finite(pressurePsig))
                / (1.0 + sq(Math.max(0.0, finite(distance)) / JET_HALF_PRESSURE_BLOCKS));
    }

    private static double sq(double v) {
        return v * v;
    }

    private static double finite(double v) {
        return Double.isFinite(v) ? v : 0.0;
    }
}
