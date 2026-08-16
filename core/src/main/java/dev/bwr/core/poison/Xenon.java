package dev.bwr.core.poison;

/**
 * Iodine-135 and xenon-135 inventory, and the reactivity they cost. SPEC 1.5.
 *
 * <p>Two coupled ODEs. Xe-135 has the largest thermal absorption cross-section
 * of any nuclide anyone has measured — about 2.65 million barns — so a few
 * hundred parts per billion of it is worth several percent in reactivity:
 *
 * <pre>
 *   dI /dt = gamma_I  * F - lambda_I * I
 *   dXe/dt = gamma_Xe * F + lambda_I * I - lambda_Xe * Xe - sigma_a * phi * Xe
 * </pre>
 *
 * where {@code F} is the fission rate density and {@code phi} the thermal flux,
 * both proportional to reactor power.
 *
 * <p><b>Why the post-shutdown peak happens.</b> At power, xenon is destroyed
 * mostly by neutron capture rather than by its own decay — the
 * {@code sigma_a * phi * Xe} term dominates. Shut down and that term vanishes
 * instantly, but the iodine already in the core keeps decaying into xenon for
 * hours. Production briefly outruns removal and the xenon inventory climbs to
 * roughly 2.3 times its full-power equilibrium, peaking around nine hours after
 * the trip, before the 9.14 hour half-life finally wins. With the constants
 * below that peak is worth about -0.064 dk/k, which is more excess reactivity
 * than a fresh core has: restart is precluded until the xenon burns off. The
 * mod does not enforce that. The player pulls rods, discovers the core will not
 * go critical, and either waits it out or does not.
 *
 * <p>Time constants are hours, so this integrates on a coarse step. The scheme
 * is backward Euler, which is unconditionally stable, keeps both inventories
 * positive, and reproduces the steady state exactly at any step size.
 *
 * <p>Pure Java, doubles only, no Minecraft.
 */
public final class Xenon {

    // ---------------------------------------------------------------
    // Nuclear data
    // ---------------------------------------------------------------

    /** I-135 decay constant, per second. Half-life 6.57 hours. */
    public static final double IODINE_DECAY_PER_SECOND = Math.log(2.0) / (6.57 * 3600.0);

    /** Xe-135 decay constant, per second. Half-life 9.14 hours. */
    public static final double XENON_DECAY_PER_SECOND = Math.log(2.0) / (9.14 * 3600.0);

    /** I-135 fission yield, atoms per fission. */
    public static final double IODINE_YIELD_PER_FISSION = 0.0639;

    /** Direct Xe-135 fission yield, atoms per fission. Most xenon arrives via iodine. */
    public static final double XENON_DIRECT_YIELD_PER_FISSION = 0.00237;

    /** Xe-135 thermal absorption cross-section, cm^2. 2.65e6 barns at 0.0253 eV. */
    public static final double XENON_ABSORPTION_CROSS_SECTION_CM2 = 2.65e-18;

    // ---------------------------------------------------------------
    // Core-average scaling
    // ---------------------------------------------------------------

    /** Core-average thermal neutron flux at rated power, neutrons per cm^2 per second. */
    public static final double RATED_THERMAL_FLUX_N_PER_CM2_S = 4.0e13;

    /**
     * Core-average fission rate density at rated power, fissions per cm^3 per
     * second. Derived from a BWR power density near 51 W/cm^3 divided by the
     * ~200 MeV recoverable per fission.
     */
    public static final double RATED_FISSION_RATE_PER_CM3_S = 1.6e12;

    /**
     * Equilibrium xenon worth at rated power, dk/k. The literature value for a
     * light water reactor is -2.6% to -3.0%; -2.8% is used to calibrate
     * {@link #WORTH_PER_ATOM_PER_CM3}, and every other worth in the model then
     * follows from the ODEs rather than from another tuned number.
     */
    public static final double EQUILIBRIUM_WORTH_AT_RATED_DK_K = -0.028;

    /** Xe-135 inventory at rated-power equilibrium, atoms per cm^3. */
    public static final double EQUILIBRIUM_XENON_AT_RATED_ATOMS_PER_CM3 = equilibriumXenon(1.0);

    /** Reactivity per unit xenon inventory, dk/k per atom per cm^3. Negative. */
    public static final double WORTH_PER_ATOM_PER_CM3 =
            EQUILIBRIUM_WORTH_AT_RATED_DK_K / EQUILIBRIUM_XENON_AT_RATED_ATOMS_PER_CM3;

    /**
     * Default integration interval, seconds. The fastest time constant in the
     * system is the ~2.6 hour effective xenon removal at full power, so a
     * minute is generous accuracy and costs two divisions a minute.
     */
    public static final double DEFAULT_INTEGRATION_INTERVAL_SECONDS = 60.0;

    // ---------------------------------------------------------------
    // State
    // ---------------------------------------------------------------

    private double iodineAtomsPerCm3;
    private double xenonAtomsPerCm3;

    /** Buffered time and power-time between coarse integration steps. */
    private double pendingSeconds;
    private double pendingPowerSeconds;

    private double integrationIntervalSeconds = DEFAULT_INTEGRATION_INTERVAL_SECONDS;

    /** A clean core: no iodine, no xenon. */
    public Xenon() {
    }

    /** A core that has been running long enough to reach equilibrium at this power. */
    public static Xenon atEquilibrium(double powerFraction) {
        Xenon xenon = new Xenon();
        xenon.setToEquilibrium(powerFraction);
        return xenon;
    }

    // ---------------------------------------------------------------
    // Integration
    // ---------------------------------------------------------------

    /**
     * Feeds a game tick in. Buffers power and time and integrates once the
     * buffer reaches {@link #integrationIntervalSeconds}, since resolving an
     * hours-long transient at 20 Hz is wasted arithmetic.
     *
     * @param powerFraction fission power, fraction of rated
     * @param dtSeconds     elapsed time, seconds
     */
    public void tick(double powerFraction, double dtSeconds) {
        if (!(dtSeconds > 0.0)) {
            return;
        }
        pendingSeconds += dtSeconds;
        pendingPowerSeconds += Math.max(0.0, powerFraction) * dtSeconds;
        if (pendingSeconds >= integrationIntervalSeconds) {
            flush();
        }
    }

    /**
     * Integrates whatever is currently buffered. Call before saving or before
     * reading the inventory for a report, so no fraction of an interval is
     * silently dropped.
     */
    public void flush() {
        if (!(pendingSeconds > 0.0)) {
            return;
        }
        double averagePower = pendingPowerSeconds / pendingSeconds;
        step(averagePower, pendingSeconds);
        pendingSeconds = 0.0;
        pendingPowerSeconds = 0.0;
    }

    /**
     * One backward Euler step at the given power. Stable at any step size and
     * exact at steady state; iodine is advanced first and the new value used
     * for xenon, which is what keeps the post-shutdown ingrowth right at coarse
     * steps.
     *
     * @param powerFraction fission power, fraction of rated
     * @param dtSeconds     step length, seconds
     */
    public void step(double powerFraction, double dtSeconds) {
        if (!(dtSeconds > 0.0)) {
            return;
        }
        double power = Math.max(0.0, powerFraction);
        double fissionRate = RATED_FISSION_RATE_PER_CM3_S * power;
        double flux = RATED_THERMAL_FLUX_N_PER_CM2_S * power;

        double iodineNext = (iodineAtomsPerCm3 + dtSeconds * IODINE_YIELD_PER_FISSION * fissionRate)
                / (1.0 + dtSeconds * IODINE_DECAY_PER_SECOND);

        double xenonRemoval = XENON_DECAY_PER_SECOND + XENON_ABSORPTION_CROSS_SECTION_CM2 * flux;
        double xenonSource = XENON_DIRECT_YIELD_PER_FISSION * fissionRate
                + IODINE_DECAY_PER_SECOND * iodineNext;
        double xenonNext = (xenonAtomsPerCm3 + dtSeconds * xenonSource)
                / (1.0 + dtSeconds * xenonRemoval);

        iodineAtomsPerCm3 = Math.max(0.0, iodineNext);
        xenonAtomsPerCm3 = Math.max(0.0, xenonNext);
    }

    // ---------------------------------------------------------------
    // Readouts
    // ---------------------------------------------------------------

    /**
     * Xenon reactivity, dk/k. Strongly negative — a few percent at power, more
     * than double that at the post-shutdown peak.
     */
    public double reactivityDkK() {
        return WORTH_PER_ATOM_PER_CM3 * xenonAtomsPerCm3;
    }

    public double xenonAtomsPerCm3() {
        return xenonAtomsPerCm3;
    }

    public double iodineAtomsPerCm3() {
        return iodineAtomsPerCm3;
    }

    /** Xenon inventory as a multiple of rated-power equilibrium. Peaks near 2.3 after a trip. */
    public double fractionOfRatedEquilibrium() {
        return xenonAtomsPerCm3 / EQUILIBRIUM_XENON_AT_RATED_ATOMS_PER_CM3;
    }

    // ---------------------------------------------------------------
    // Equilibrium and state restore
    // ---------------------------------------------------------------

    /** I-135 inventory after running indefinitely at this power, atoms per cm^3. */
    public static double equilibriumIodine(double powerFraction) {
        double power = Math.max(0.0, powerFraction);
        return IODINE_YIELD_PER_FISSION * RATED_FISSION_RATE_PER_CM3_S * power
                / IODINE_DECAY_PER_SECOND;
    }

    /** Xe-135 inventory after running indefinitely at this power, atoms per cm^3. */
    public static double equilibriumXenon(double powerFraction) {
        double power = Math.max(0.0, powerFraction);
        double production = (IODINE_YIELD_PER_FISSION + XENON_DIRECT_YIELD_PER_FISSION)
                * RATED_FISSION_RATE_PER_CM3_S * power;
        double removal = XENON_DECAY_PER_SECOND
                + XENON_ABSORPTION_CROSS_SECTION_CM2 * RATED_THERMAL_FLUX_N_PER_CM2_S * power;
        return production / removal;
    }

    /** Jumps both inventories to equilibrium at this power. For a hot restart, or a test. */
    public void setToEquilibrium(double powerFraction) {
        iodineAtomsPerCm3 = equilibriumIodine(powerFraction);
        xenonAtomsPerCm3 = equilibriumXenon(powerFraction);
        pendingSeconds = 0.0;
        pendingPowerSeconds = 0.0;
    }

    /** Restores saved inventories. */
    public void setInventoriesAtomsPerCm3(double iodine, double xenon) {
        if (Double.isNaN(iodine) || Double.isNaN(xenon)) {
            throw new IllegalArgumentException("iodine and xenon inventories must be numbers");
        }
        this.iodineAtomsPerCm3 = Math.max(0.0, iodine);
        this.xenonAtomsPerCm3 = Math.max(0.0, xenon);
        this.pendingSeconds = 0.0;
        this.pendingPowerSeconds = 0.0;
    }

    /** Empties both inventories — a freshly loaded core that has never run. */
    public void clear() {
        setInventoriesAtomsPerCm3(0.0, 0.0);
    }

    public double integrationIntervalSeconds() {
        return integrationIntervalSeconds;
    }

    public void setIntegrationIntervalSeconds(double seconds) {
        if (!(seconds > 0.0)) {
            throw new IllegalArgumentException(
                    "integrationIntervalSeconds must be positive, was " + seconds);
        }
        this.integrationIntervalSeconds = seconds;
    }

    @Override
    public String toString() {
        return String.format("Xenon[I=%.3e Xe=%.3e atoms/cm3, %.2f x equilibrium, rho=%.5f dk/k]",
                iodineAtomsPerCm3, xenonAtomsPerCm3, fractionOfRatedEquilibrium(), reactivityDkK());
    }
}
