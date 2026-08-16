package dev.bwr.core;

/**
 * Tunables for one reactor instance. Separated from {@link PhysicalConstants}
 * because these are model-calibration knobs and per-core sizing, whereas
 * PhysicalConstants holds real plant data.
 *
 * <p>Mutable by design: a datapack or multiblock size can adjust these at
 * construction time. {@code ReactorCore} reads them and does not write them.
 */
public final class CoreConfig {

    // --- Integration -------------------------------------------------

    /**
     * Sub-steps per game tick for the point-kinetics solve. The integrator is
     * implicit and therefore stable at any step, so this is an accuracy knob
     * rather than a survival one. See {@code PointKinetics}.
     */
    public int kineticsSubSteps = 50;

    /** Game tick length, seconds. 20 ticks per second. */
    public double tickSeconds = 0.05;

    /** Nodal flux shape re-solve interval, ticks. 20 ticks = 1 Hz per SPEC 1.3. */
    public int nodalSolveIntervalTicks = 20;

    // --- Core sizing -------------------------------------------------

    /** Fuel assemblies in this core. */
    public int assemblyCount = PhysicalConstants.FUEL_ASSEMBLIES;

    /** Control rods, and therefore control rod drives, in this core. */
    public int controlRodCount = PhysicalConstants.CONTROL_RODS;

    /** Rated thermal power for this core, MW. */
    public double ratedThermalMW = PhysicalConstants.RATED_THERMAL_MW;

    // --- Neutronics --------------------------------------------------

    /**
     * Startup neutron source strength, fraction-of-rated per second.
     *
     * <p>This term is what makes a shutdown core readable. Without it, n = 0
     * is an equilibrium, a subcritical core decays to exactly zero, and source
     * range monitors read nothing. With it the subcritical equilibrium is
     * n = lambda * S / |rho|, giving both a finite floor and the hyperbolic
     * climb toward criticality that makes 1/M plots work.
     */
    public double neutronSource = 1.0e-12;

    /**
     * Prompt neutron lifetime a bare {@code PointKinetics} starts on, seconds.
     *
     * <p><b>This is not a live knob on a fuelled reactor, and retuning it on one
     * has no effect whatsoever.</b> It seeds the {@code PointKinetics}
     * constructor, and a {@code ReactorCore} then overwrites it — inside its own
     * constructor, before it returns — with
     * {@code CoreLoading.effectivePromptLifetimeSeconds()}, and again on every
     * nodal refresh thereafter. Prompt lifetime is a property of the fuel: it is
     * declared per {@code FuelType} and averaged over the loading by fission
     * rate, so the way to change it is to load different fuel, not to set this.
     *
     * <p>Kept because {@code PointKinetics} is usable on its own — the kinetics
     * tests drive one directly — and it has to start somewhere. Treat it as that
     * starting value and nothing more.
     */
    public double promptLifetime = 4.0e-5;

    /**
     * Total rod worth at full insertion, dk/k. Negative.
     *
     * <p>Genuinely live, unlike the two lifetimes and excess reactivities around
     * it: {@code RodWorth} keeps it, and {@code NodalFluxSolver} calibrates its
     * per-rod absorption cross-section against it.
     */
    public double totalRodWorth = -0.18;

    /**
     * Excess reactivity a bare {@code ReactivityBalance} starts on, dk/k.
     *
     * <p><b>Not a live knob on a fuelled reactor</b>, for the same reason as
     * {@link #promptLifetime}: it seeds the {@code ReactivityBalance}
     * constructor and a {@code ReactorCore} overwrites it before returning with
     * {@code CoreLoading.excessReactivityAllRodsOutDkK()} — 0.0795 dk/k for a
     * fresh LEU core, falling with burnup — and again on every nodal refresh.
     * How much reactivity is left in the fuel is a question about the fuel, and
     * {@code CoreLoading} owns the answer.
     */
    public double excessReactivity = 0.06;

    // --- Thermal hydraulics ------------------------------------------

    /** Steam dome volume, cubic metres. Sets pressure response sharpness. */
    public double steamDomeVolumeM3 = 180.0;

    /** Coolant mass in the vessel at normal level, kg. */
    public double coolantMassKg = 2.2e5;

    /** Fuel thermal time constant, seconds. */
    public double fuelTimeConstantS = 6.0;

    /** Fuel heat capacity, MJ per degree C for the whole core. */
    public double fuelHeatCapacityMJperC = 120.0;

    // --- Instrumentation ---------------------------------------------

    /**
     * Source range monitor dead time, seconds. A pulse-counting fission
     * chamber is paralyzable: indicated = true * exp(-true * deadTime).
     * That peaks at 1/(e*deadTime) and then rolls to zero, which is why a
     * saturated SRM reads 00.00 rather than pegging high.
     */
    public double srmDeadTimeSeconds = 3.68e-12;

    /** Counts per second produced per unit of fractional neutron power. */
    public double srmCountsPerUnitPower = 4.1e13;

    public CoreConfig() {
    }

    /** Sub-step length in seconds, derived from tick length and sub-step count. */
    public double subStepSeconds() {
        return tickSeconds / kineticsSubSteps;
    }
}
