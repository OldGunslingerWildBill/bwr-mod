package dev.bwr.core;

/**
 * Physical constants for a BWR/6-class plant.
 *
 * <p>Values are grounded in the NRC Technical Training Center's
 * <i>Boiling Water Reactor Systems Manual, BWR/6 Design</i> (ADAMS accession
 * ML20090J537), cited below as [TTC section]. See {@code REFERENCE-DATA.md}.
 *
 * <p><b>Everything here is hardware or physics.</b> No protection-system
 * setpoints live in this class. Scram thresholds, ECCS initiation levels and
 * trip logic are the player's to write in CC:Tweaked; the mod exposes
 * measurements and actuators, never judgements.
 */
public final class PhysicalConstants {
    private PhysicalConstants() {
    }

    // ---------------------------------------------------------------
    // Pressure — hardware envelope [TTC 2.2, 2.5, 3.1.3]
    // ---------------------------------------------------------------

    /** Rated reactor steam dome pressure, psig [TTC 2.5.2.2]. */
    public static final double RATED_DOME_PRESSURE_PSIG = 1025.0;

    /** Reactor vessel design pressure, psig [TTC 2.2]. */
    public static final double VESSEL_DESIGN_PRESSURE_PSIG = 1250.0;

    /** Must not be exceeded with irradiated fuel present, psig [TTC 2.2]. */
    public static final double VESSEL_FUEL_PRESENT_LIMIT_PSIG = 1325.0;

    /** ASME code transient limit: 110% of design, psig [TTC 2.5]. */
    public static final double VESSEL_CODE_LIMIT_PSIG = 1375.0;

    /**
     * Safety/relief valve spring settings, psig [TTC 3.1.3].
     * Nineteen valves in three banks: 1 at the first setting, 9 at the second,
     * 9 at the third. These are mechanical spring settings, not chosen
     * setpoints — but note that in this mod SRVs are player-actuated via
     * CC:Tweaked or redstone, so these describe valve hardware ratings only.
     */
    public static final double[] SRV_SPRING_SETTINGS_PSIG = {1103.0, 1113.0, 1123.0};

    /** Number of SRVs at each spring setting, parallel to the array above. */
    public static final int[] SRV_COUNT_PER_BANK = {1, 9, 9};

    /** Standard atmospheric pressure, psi — for psig/psia conversion. */
    public static final double ATMOSPHERIC_PSI = 14.696;

    // ---------------------------------------------------------------
    // Core geometry and rating [TTC 1.8 Table 1.8-1, 2.1, 2.3]
    // ---------------------------------------------------------------

    /** Rated thermal power, MW. */
    public static final double RATED_THERMAL_MW = 3579.0;

    /** Number of fuel assemblies in a full BWR/6 core. */
    public static final int FUEL_ASSEMBLIES = 748;

    /** Number of control rods, and therefore control rod drives. */
    public static final int CONTROL_RODS = 177;

    /** Jet pumps, arranged in pairs sharing a common inlet riser [TTC 2.1.2.2.6]. */
    public static final int JET_PUMPS = 20;

    /** Rated core flow, lb/hr. */
    public static final double RATED_CORE_FLOW_LB_PER_HR = 104.0e6;

    /** Rated steam flow, lb/hr. */
    public static final double RATED_STEAM_FLOW_LB_PER_HR = 15.4e6;

    // ---------------------------------------------------------------
    // Control rod drives [TTC 2.3]
    // ---------------------------------------------------------------

    /**
     * Number of discrete notch positions per rod. Real BWR rods index in 25
     * steps read out as 00..48 in increments of two on the Full Core Display
     * [TTC 2.3.2.12]. Position 00 is fully inserted, 48 fully withdrawn.
     */
    public static final int ROD_NOTCH_POSITIONS = 25;

    /** Fully-withdrawn notch label as shown on the FCD. */
    public static final int ROD_NOTCH_MAX_LABEL = 48;

    /** Notch label increment between adjacent positions. */
    public static final int ROD_NOTCH_STEP = 2;

    /** Normal control rod drive speed, inches per second [TTC 2.3.2]. */
    public static final double ROD_DRIVE_SPEED_IN_PER_SEC = 3.0;

    /** Scram accumulator volume, US gallons [TTC 2.3.2]. */
    public static final double ACCUMULATOR_VOLUME_GAL = 48.0;

    /** CRD hydraulic pump discharge / accumulator charging pressure, psig [TTC 2.3.2]. */
    public static final double CRD_CHARGING_PRESSURE_PSIG = 1750.0;

    /** CRD hydraulic pump capacity, gpm [TTC 2.3.2]. */
    public static final double CRD_PUMP_CAPACITY_GPM = 220.0;

    // ---------------------------------------------------------------
    // Vessel level geometry [TTC 3.1.2.1]
    // ---------------------------------------------------------------

    /** Instrument zero, inches above vessel zero. */
    public static final double INSTRUMENT_ZERO_ABOVE_VESSEL_ZERO_IN = 530.0;

    /** Top of active fuel, inches above vessel zero. */
    public static final double TAF_ABOVE_VESSEL_ZERO_IN = 363.0;

    /** Top of active fuel expressed on the instrument-zero scale, inches. */
    public static final double TAF_ON_INSTRUMENT_SCALE_IN =
            TAF_ABOVE_VESSEL_ZERO_IN - INSTRUMENT_ZERO_ABOVE_VESSEL_ZERO_IN; // -167.0

    // ---------------------------------------------------------------
    // Flow equipment [TTC 2.4, 2.7]
    // ---------------------------------------------------------------

    /** Recirculation pump rated flow, gpm each [TTC 2.4]. */
    public static final double RECIRC_PUMP_RATED_GPM = 35_400.0;

    /** Recirculation pump rated discharge head, feet [TTC 2.4]. */
    public static final double RECIRC_PUMP_HEAD_FT = 865.0;

    /**
     * Low-speed recirculation operation as a fraction of rated, driven by a
     * 15 Hz motor-generator set to avoid cavitation during startup [TTC 2.4].
     */
    public static final double RECIRC_LOW_SPEED_FRACTION = 0.25;

    // ---------------------------------------------------------------
    // Standby liquid control [TTC 7.4]
    // ---------------------------------------------------------------

    /** Boron concentration giving the design shutdown margin, ppm [TTC 7.4]. */
    public static final double SLC_SHUTDOWN_BORON_PPM = 600.0;

    /** Shutdown margin delivered at {@link #SLC_SHUTDOWN_BORON_PPM}, dk/k [TTC 7.4]. */
    public static final double SLC_SHUTDOWN_MARGIN_DK_K = 0.05;

    /** Derived boron reactivity worth, dk/k per ppm. */
    public static final double BORON_WORTH_PER_PPM =
            SLC_SHUTDOWN_MARGIN_DK_K / SLC_SHUTDOWN_BORON_PPM; // 8.333e-5

    /** Design tank concentration, ppm — 600 plus 25% mixing margin [TTC 7.4]. */
    public static final double SLC_DESIGN_BORON_PPM = 750.0;

    /** Injection rate bounds, ppm per minute [TTC 7.4]. */
    public static final double SLC_INJECTION_PPM_PER_MIN_MIN = 8.0;
    public static final double SLC_INJECTION_PPM_PER_MIN_MAX = 20.0;

    // ---------------------------------------------------------------
    // Reactivity coefficients
    // ---------------------------------------------------------------

    /**
     * Moderator void coefficient, dk/k per unit void fraction, at low void.
     * The manual gives shape rather than magnitude [TTC 1.7.2.1.2]; magnitude
     * is the operating-BWR literature value of -7.0e-4 per %void, converted
     * here to per-unit-void-fraction.
     *
     * <p>This is the <i>low-void</i> value. Real void worth steepens as void
     * rises because a 1% void step removes a larger share of the remaining
     * water: roughly 1.1% of the water at 10% void, but 3.45% at 70% void
     * [TTC 1.7.2.1.3]. Void models must scale this, not treat it as constant.
     */
    public static final double VOID_COEFF_PER_VOID_FRACTION = -7.0e-2;

    /** Doppler (fuel temperature) coefficient, dk/k per degree C. Negative and prompt. */
    public static final double DOPPLER_COEFF_PER_C = -1.6e-5;

    // ---------------------------------------------------------------
    // Severe accident thresholds
    // ---------------------------------------------------------------

    /** Onset of significant zirconium-water reaction, degrees C. */
    public static final double ZR_REACTION_ONSET_C = 1200.0;

    /** Above this peak clad temperature a reflood risks a quench spike, degrees C. */
    public static final double QUENCH_SPIKE_THRESHOLD_C = 1527.0; // ~1800 K

    /** UO2 melting point, degrees C. */
    public static final double FUEL_MELT_C = 2800.0;
}
