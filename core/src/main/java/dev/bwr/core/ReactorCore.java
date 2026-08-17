package dev.bwr.core;

import dev.bwr.core.boundary.BoundaryStress;
import dev.bwr.core.fuel.CoreLoading;
import dev.bwr.core.fuel.FuelAssembly;
import dev.bwr.core.fuel.FuelType;
import dev.bwr.core.instrument.AveragePowerRangeMonitor;
import dev.bwr.core.instrument.IntermediateRangeMonitor;
import dev.bwr.core.instrument.NeutronDetector;
import dev.bwr.core.instrument.PeriodMeter;
import dev.bwr.core.instrument.SourceRangeMonitor;
import dev.bwr.core.kinetics.DelayedNeutronData;
import dev.bwr.core.kinetics.PointKinetics;
import dev.bwr.core.kinetics.ReactivityBalance;
import dev.bwr.core.kinetics.RodWorth;
import dev.bwr.core.nodal.FluxSolution;
import dev.bwr.core.nodal.NodalFluxSolver;
import dev.bwr.core.poison.Xenon;
import dev.bwr.core.thermal.DecayHeat;
import dev.bwr.core.thermal.FuelThermal;
import dev.bwr.core.thermal.PressureVessel;
import dev.bwr.core.thermal.Saturation;
import dev.bwr.core.thermal.VoidModel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The whole reactor: neutronics, thermal hydraulics, fuel, poisons, control rod
 * drives and nuclear instrumentation, assembled into one object that advances by
 * one game tick per {@link #step()}.
 *
 * <p>Pure Java, doubles only, zero Minecraft imports — {@code SPEC.md} section 0.
 * The block entity owns NBT, structure validation and client sync; it calls
 * {@link #step()} once per tick and moves state across the boundary through
 * {@link #toState()} and {@link #fromState(ReactorState)}.
 *
 * <h2>What this class exposes, and what it refuses to</h2>
 * Measurements and actuators. Nothing else.
 *
 * <p>There is no scram logic here. {@link #scram()} is a bare actuator: it fires
 * the accumulators and drives every rod it can move to the bottom, and it does
 * not look at power, period, pressure, level or anything else first. There are no
 * trip setpoints, no protection system, no automatic ECCS actuation and no
 * interlocks anywhere in this codebase. The player writes all of that in
 * CC:Tweaked, against the getters below. That is the entire design premise
 * (README principle 1, {@code SPEC.md} section 9): the mod provides hardware and
 * physics, the player provides control logic.
 *
 * <p>Consequently no method here decides whether a number is acceptable. The
 * relief valves do not open by themselves; {@link #setReliefSteamFlowKgPerS} is
 * an actuator the player drives. Feedwater does not chase level. Rods do not
 * insert on a period. If a reader ever wants a method called
 * {@code shouldScram()} or {@code checkTrips()}, the answer is that it belongs in
 * Lua, in the player's control room, where they can get it wrong.
 *
 * <h2>Order of operations inside a tick, and why it is what it is</h2>
 * <ol>
 *   <li>Control rod drives step — mechanical motion and accumulator bookkeeping.</li>
 *   <li>Rod worth, then void fraction, at <b>last tick's pressure</b>.</li>
 *   <li>The reactivity balance is closed once, producing a single scalar rho.</li>
 *   <li>Point kinetics is sub-stepped across the tick <b>at that frozen rho</b>.</li>
 *   <li>Decay heat, then fuel and clad temperature.</li>
 *   <li>The pressure vessel steps <b>once</b>, producing next tick's pressure.</li>
 *   <li>Xenon, burnup, instruments.</li>
 *   <li>Overpressure wear on the pressure boundary, at the pressure step 6
 *       just produced — {@link BoundaryStress}.</li>
 * </ol>
 *
 * <p>That last step is the <b>only</b> thing in this model that happens to a
 * player without a player asking. It is damage, not protection: it accumulates
 * wear and eventually breaks a pipe. It does not warn, trip, relieve or
 * prevent, and it is the one place in the physics that draws a random number.
 *
 * <p><b>Pressure is deliberately held constant across the sub-stepped kinetics
 * and updated exactly once per tick. Do not "fix" this by moving the pressure
 * solve inside the sub-step loop.</b> Pressure raises saturation temperature,
 * which collapses voids, which adds positive reactivity, which raises power,
 * which makes more steam, which raises pressure. That loop is fast and
 * positive-feedback-capable ({@code SPEC.md} section 6.3), and the void and
 * pressure models carry tick-resolution transport lags — a recirculation transit
 * and a channel transit — that are physically meaningful at tick scale and
 * meaningless at 20-microsecond sub-step scale. Integrating the loop at sub-step
 * resolution against models that only have tick resolution manufactures an
 * oscillation that is a numerical artefact and not reactor behaviour. The
 * timescale separation is load bearing.
 *
 * <p>The nodal-shape refresh ({@code SPEC.md} section 1.3) is slower still: fuel
 * aggregates, effective beta, prompt lifetime and burnup are updated every
 * {@link CoreConfig#nodalSolveIntervalTicks} ticks, because flux <i>shape</i>
 * changes slowly while flux <i>level</i> changes fast.
 *
 * <h2>Fission power versus total power</h2>
 * {@code n} is fission power as a fraction of rated. Decay heat is a separate
 * additive fraction. Total thermal power is their sum, and it is total power that
 * boils water, heats fuel and makes void — a scrammed core is still a steam
 * source. Rated thermal power is a <i>total</i>, so a core in equilibrium at
 * rated sits at about 93.8% fission and 6.6% decay heat, not 100% and 6.6%. See
 * {@link #initialiseAtTotalPowerFraction}.
 *
 * <p>Not thread safe. One instance per reactor, stepped from the server thread.
 */
public final class ReactorCore {

    // ---------------------------------------------------------------
    // Control rod drive hardware [TTC 2.3, SPEC 3.3]
    // ---------------------------------------------------------------

    /** Notch spans between the 25 discrete positions. */
    public static final int NOTCH_SPANS = PhysicalConstants.ROD_NOTCH_POSITIONS - 1; // 24

    /** Travel of one notch, inches — active fuel height divided by the notch spans. */
    public static final double INCHES_PER_NOTCH = PressureVessel.ACTIVE_FUEL_HEIGHT_IN / NOTCH_SPANS;

    /** Seconds a powered drive takes to index one notch at the normal drive speed. */
    public static final double NORMAL_SECONDS_PER_NOTCH =
            INCHES_PER_NOTCH / PhysicalConstants.ROD_DRIVE_SPEED_IN_PER_SEC; // 2.083 s

    /** Full-stroke scram insertion time from a charged accumulator, seconds. */
    public static final double SCRAM_FULL_STROKE_SECONDS = 3.0;

    /**
     * Dome pressure above which reactor pressure alone can complete a scram
     * stroke after the accumulator is spent, psig.
     *
     * <p>Hardware capability, not a setpoint: the scram piston is driven by the
     * difference between the accumulator or reactor pressure below it and the
     * scram discharge volume above it. With the vessel at power there is plenty
     * of head to insert a rod without any stored charge at all, just more slowly.
     * A cold depressurised vessel has none, which is precisely why an uncharged
     * accumulator on a shut-down plant is a stuck rod.
     */
    public static final double SCRAM_PRESSURE_ASSIST_PSIG = 600.0;

    /** Fraction of scram speed available on reactor pressure alone. */
    public static final double PRESSURE_ASSIST_SPEED_FRACTION = 0.5;

    /**
     * Accumulator charge consumed per notch of scram travel. One full stroke
     * spends one full charge, so a charged accumulator buys exactly one scram —
     * {@code SPEC.md} section 3.3.
     */
    public static final double ACCUMULATOR_CHARGE_PER_NOTCH = 1.0 / NOTCH_SPANS;

    /**
     * Default accumulator recharge rate, fraction per second.
     *
     * <p>Derived from plant data rather than chosen: one CRD hydraulic pump of
     * {@link PhysicalConstants#CRD_PUMP_CAPACITY_GPM} gallons per minute refilling
     * {@link CoreConfig#controlRodCount} accumulators of
     * {@link PhysicalConstants#ACCUMULATOR_VOLUME_GAL} gallons each. That is the
     * pessimistic bound — every accumulator empty and charging at once — and it
     * works out at about 39 minutes to recover full scram capability after a full
     * scram. {@code SPEC.md} section 3.3 names this the main balance knob, so it
     * is settable per core; nothing in normal operation gates on it, and that is
     * the property to preserve if it is retuned.
     */
    public static final double DEFAULT_ACCUMULATOR_RECHARGE_PER_SECOND =
            PhysicalConstants.CRD_PUMP_CAPACITY_GPM
                    / (PhysicalConstants.CONTROL_RODS * PhysicalConstants.ACCUMULATOR_VOLUME_GAL)
                    / 60.0;

    // ---------------------------------------------------------------
    // Recirculation [SPEC 4.2]
    // ---------------------------------------------------------------

    /** Recirculation flow lag on acceleration, seconds. */
    public static final double FLOW_ACCELERATION_TIME_CONSTANT_S = 5.0;

    /**
     * Recirculation flow lag on deceleration, seconds. Longer than the
     * acceleration constant because the pump has a flywheel: coastdown is the
     * most recognisable behaviour in a loss-of-power event and it is what turns
     * an instant flow loss into a survivable flow decay.
     */
    public static final double FLOW_COASTDOWN_TIME_CONSTANT_S = 15.0;

    // ---------------------------------------------------------------
    // Instrument complement [TTC 3.2]
    // ---------------------------------------------------------------

    /** Source range monitor channels. */
    public static final int SOURCE_RANGE_CHANNELS = 4;

    /** Intermediate range monitor channels. */
    public static final int INTERMEDIATE_RANGE_CHANNELS = 8;

    /** Average power range monitor channels. */
    public static final int AVERAGE_POWER_RANGE_CHANNELS = 6;

    /** Lattice width used by {@link #defaultCoreLoading}, giving room for 748 assemblies. */
    public static final int DEFAULT_LATTICE_WIDTH = 31;

    // ---------------------------------------------------------------
    // Components
    // ---------------------------------------------------------------

    private final CoreConfig config;
    private final CoreLoading loading;
    private final NodalFluxSolver nodalFlux;

    private final PointKinetics kinetics;
    private final ReactivityBalance reactivity;
    private final RodWorth rodWorth;
    private final VoidModel voidModel;
    private final PressureVessel vessel;
    private final FuelThermal fuelThermal;
    private final DecayHeat decayHeat;
    private final Xenon xenon;
    private final BoundaryStress boundary;

    private final SourceRangeMonitor[] sourceRangeMonitors;
    private final IntermediateRangeMonitor[] intermediateRangeMonitors;
    private final AveragePowerRangeMonitor[] averagePowerRangeMonitors;
    private final PeriodMeter[] sourceRangePeriodMeters;
    private final PeriodMeter[] intermediateRangePeriodMeters;

    // ---------------------------------------------------------------
    // Control rod drive state
    // ---------------------------------------------------------------

    private final int controlRodCount;
    private final int[] rodNotchIndex;
    private final int[] rodNotchDemand;
    private final double[] rodDriveTimerSeconds;
    private final double[] accumulatorCharge;

    private boolean crdPowered = true;
    private boolean crdWaterSupplied = true;
    private boolean scramActive = false;
    private double accumulatorRechargePerSecond = DEFAULT_ACCUMULATOR_RECHARGE_PER_SECOND;

    // ---------------------------------------------------------------
    // Commanded actuator state
    // ---------------------------------------------------------------

    private double recirculationFlowFractionDemand = 1.0;
    private double coreFlowFraction = 1.0;

    private double turbineSteamFlowKgPerS;
    private double bypassSteamFlowKgPerS;
    private double reliefSteamFlowKgPerS;

    private double feedwaterFlowKgPerS;
    private double feedwaterTemperatureC = 215.6;
    private double injectionFlowKgPerS;
    private double coreSprayFlowKgPerS;

    /**
     * Leakage the caller has staged by hand, kept separate from the leakage the
     * overpressure damage model has caused so that the two add rather than
     * clobber one another.
     */
    private double manualLiquidLeakKgPerS;
    private double manualSteamLeakKgPerS;

    /** What the boundary breaks actually discharged on the last tick, kg/s. */
    private double lastBreakLiquidFlowKgPerS;
    private double lastBreakSteamFlowKgPerS;

    private double boronInjectionPpmPerMinute;
    private double boronPpm;

    // ---------------------------------------------------------------
    // Bookkeeping
    // ---------------------------------------------------------------

    private double elapsedSeconds;
    private long tickCount;
    private double secondsSinceAggregateRefresh;
    private boolean burnupEnabled = true;
    private double cachedAggregateBeta = Double.NaN;

    // ---------------------------------------------------------------
    // Construction
    // ---------------------------------------------------------------

    /** A core with the default 748-assembly LEU loading. */
    public ReactorCore(CoreConfig config) {
        this(config, defaultCoreLoading(config, FuelType.LEU));
    }

    /**
     * <p>A newly constructed core is left at <b>hot shutdown</b>, and that is a
     * default rather than a claim about where a reactor starts. The constructor
     * has to leave every component in some self-consistent state, and every real
     * caller immediately says which one it wants: the harness and the acceptance
     * suite call {@link #initialiseHotShutdown()} or
     * {@link #initialiseAtTotalPowerFraction} because they are staging a plant
     * that is already running, and the mod calls {@link #initialiseCold()} on
     * formation and {@link #fromState} on a chunk load, either of which overwrites
     * all of this. Nothing is meant to read a core between construction and one of
     * those calls; if anything ever does, it is looking at a placeholder.
     *
     * @param config  tunables and core sizing; the reference is retained, so
     *                retuning it takes effect on the next tick
     * @param loading the fuel actually in the core. Supplies the aggregate
     *                k-infinity, effective beta, prompt lifetime and Doppler
     *                coefficient the kinetics runs on. {@code ReactorCore} never
     *                sees a fuel name.
     */
    public ReactorCore(CoreConfig config, CoreLoading loading) {
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        if (loading == null) {
            throw new IllegalArgumentException("loading must not be null");
        }
        this.config = config;
        this.loading = loading;
        this.controlRodCount = Math.max(1, config.controlRodCount);

        this.kinetics = new PointKinetics(config, delayedDataFor(loading.effectiveBeta()));
        this.reactivity = new ReactivityBalance(config);
        this.rodWorth = new RodWorth(config);
        this.voidModel = new VoidModel(config);
        this.vessel = new PressureVessel(config);
        this.fuelThermal = new FuelThermal(config);
        this.decayHeat = new DecayHeat();
        this.xenon = new Xenon();
        this.boundary = new BoundaryStress();

        this.rodNotchIndex = new int[controlRodCount];
        this.rodNotchDemand = new int[controlRodCount];
        this.rodDriveTimerSeconds = new double[controlRodCount];
        this.accumulatorCharge = new double[controlRodCount];
        Arrays.fill(accumulatorCharge, 1.0);

        this.sourceRangeMonitors = new SourceRangeMonitor[SOURCE_RANGE_CHANNELS];
        this.sourceRangePeriodMeters = new PeriodMeter[SOURCE_RANGE_CHANNELS];
        for (int i = 0; i < SOURCE_RANGE_CHANNELS; i++) {
            sourceRangeMonitors[i] = new SourceRangeMonitor("SRM " + (char) ('A' + i), config);
            sourceRangePeriodMeters[i] =
                    new PeriodMeter("PERIOD SRM " + (char) ('A' + i), sourceRangeMonitors[i]);
        }
        this.intermediateRangeMonitors = new IntermediateRangeMonitor[INTERMEDIATE_RANGE_CHANNELS];
        this.intermediateRangePeriodMeters = new PeriodMeter[INTERMEDIATE_RANGE_CHANNELS];
        for (int i = 0; i < INTERMEDIATE_RANGE_CHANNELS; i++) {
            intermediateRangeMonitors[i] = new IntermediateRangeMonitor("IRM " + (char) ('A' + i));
            intermediateRangePeriodMeters[i] =
                    new PeriodMeter("PERIOD IRM " + (char) ('A' + i), intermediateRangeMonitors[i]);
        }
        this.averagePowerRangeMonitors = new AveragePowerRangeMonitor[AVERAGE_POWER_RANGE_CHANNELS];
        for (int i = 0; i < AVERAGE_POWER_RANGE_CHANNELS; i++) {
            averagePowerRangeMonitors[i] = new AveragePowerRangeMonitor("APRM " + (i + 1));
        }

        // The spatial half of the neutronics ({@code SPEC.md} section 1.3).
        // Installed before the first aggregate refresh, because every aggregate
        // below is a fission-rate-weighted average and these are the weights.
        this.nodalFlux = new NodalFluxSolver(config, loading);
        loading.setPowerWeightSource(nodalFlux);

        refreshFuelAggregates();
        this.feedwaterTemperatureC = 215.6;
        applyFeedwaterTemperature();
        initialiseHotShutdown();
    }

    /**
     * A full core of fresh assemblies of one fuel type, loaded centre-outward
     * into a square lattice — a real core is a circle inscribed in that square
     * and the corners simply hold nothing ({@code CoreLoading} allows empty
     * positions for exactly this reason).
     */
    public static CoreLoading defaultCoreLoading(CoreConfig config, FuelType fuelType) {
        int width = DEFAULT_LATTICE_WIDTH;
        CoreLoading loading = new CoreLoading(width);
        int wanted = Math.min(config.assemblyCount, width * width);

        Integer[] order = new Integer[width * width];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        double centre = (width - 1) / 2.0;
        Arrays.sort(order, (a, b) -> Double.compare(
                radiusSquared(a, width, centre), radiusSquared(b, width, centre)));
        for (int i = 0; i < wanted; i++) {
            loading.load(order[i], new FuelAssembly(fuelType));
        }
        return loading;
    }

    private static double radiusSquared(int index, int width, double centre) {
        double x = (index % width) - centre;
        double y = (index / width) - centre;
        return x * x + y * y;
    }

    // ---------------------------------------------------------------
    // Initialisation
    // ---------------------------------------------------------------

    /**
     * Hot shutdown: rated dome pressure, every rod fully inserted, accumulators
     * charged, no irradiation history, and the neutron flux at the source-driven
     * subcritical equilibrium {@code n = Lambda*S/|rho|}.
     *
     * <p>That last part is what puts a finite count rate on the source range
     * monitors before the first rod moves, and it is the starting condition an
     * approach to critical is run from.
     *
     * <p><b>This is a state an operator achieves, not a state a plant is in.</b>
     * A vessel at 1025 psig is holding 190 GJ that something had to put there, so
     * this is the right starting point for a scenario that begins with the plant
     * already hot — the acceptance suite and {@code TransientHarness} both want
     * exactly that, and get it — and the wrong one for a reactor that has just
     * been built. Use {@link #initialiseCold()} for that.
     */
    public void initialiseHotShutdown() {
        vessel.initialiseToNormalLevel();
        initialiseShutdownAtVesselConditions();
    }

    /**
     * Cold shutdown: atmospheric dome pressure, water and fuel at the boiling
     * point for it, every rod fully inserted, accumulators charged, no irradiation
     * history — a reactor as it comes out of the shipyard, before anybody has
     * heated it.
     *
     * <p>Identical to {@link #initialiseHotShutdown()} in every respect except
     * where the vessel is put, which is the only difference there should be
     * between the two: both are shut-down plants with a full complement of charged
     * accumulators, an empty fission product inventory and the flux sitting at the
     * source-driven subcritical equilibrium. See
     * {@link PressureVessel#initialiseCold()} for what "cold" can and cannot mean
     * in a vessel model whose temperature <i>is</i> its pressure, and for why the
     * water stands 127 in below where it will sit once the plant is hot.
     *
     * <p><b>Nothing here heats the plant, and nothing may be added that does.</b>
     * The heat-up is the player's to run, with the actuators that already exist:
     * withdraw rods until the core is critical, hold the turbine, bypass and
     * relief valves shut, and the pressure capacity term in
     * {@link PressureVessel#step} turns core power into dome pressure at exactly
     * the rate the inventory's sensible heat allows. An automatic warm-up would be
     * the model deciding on the player's behalf what condition the plant ought to
     * be in, which is the one thing this codebase does not do.
     *
     * <h2>What a freshly built plant therefore reads</h2>
     * 0 psig, 99 degC, no power, no decay heat, no pressure rise, and — on a
     * vessel with no fuel in it — a reactivity of many dollars negative, because
     * an empty lattice has no k-infinity to be reactive with. Those are all
     * measurements of a plant in which nothing has happened yet, which is the
     * point: the first playtest formed an unfuelled core and found it at 1025 psig
     * and 287 degC, hot standby with no candidate heat source anywhere in the
     * model, because formation ran the hot initialiser.
     *
     * <p>The recirculation pumps are left on their low-speed detent exactly as
     * hot shutdown leaves them, and that is a deliberate choice rather than a
     * copied line. Real BWR practice is to run recirculation in slow speed
     * <i>through</i> the startup, and this model has a stronger reason:
     * {@code SPEC.md} section 4.5 defers natural circulation, so
     * {@link VoidModel#MINIMUM_FLOW_FRACTION} — one per cent of rated — is all a
     * core with its pumps stopped gets, and one per cent of flow against any real
     * power drives quality to one and void to its ceiling. Handing a player a
     * plant parked in the least physical corner of the flow model would be a trap
     * with no exit; the low-speed detent is both the real lineup and out of it.
     */
    public void initialiseCold() {
        vessel.initialiseCold();
        initialiseShutdownAtVesselConditions();
    }

    /**
     * Everything a shut-down plant is, given a vessel that has already been put
     * where it belongs. Shared by {@link #initialiseHotShutdown()} and
     * {@link #initialiseCold()} so the two cannot drift apart: the difference
     * between a hot plant and a cold one is the dome pressure and the water
     * standing in the vessel, and nothing else about a shut-down reactor has any
     * business differing between them.
     */
    private void initialiseShutdownAtVesselConditions() {
        Arrays.fill(rodNotchIndex, RodWorth.NOTCH_INDEX_FULLY_INSERTED);
        Arrays.fill(rodNotchDemand, RodWorth.NOTCH_INDEX_FULLY_INSERTED);
        Arrays.fill(rodDriveTimerSeconds, 0.0);
        Arrays.fill(accumulatorCharge, 1.0);
        scramActive = false;

        double pressurePsig = vessel.getPressurePsig();
        double saturationC = Saturation.temperatureCelsiusFromPsig(pressurePsig);

        decayHeat.clearInventory();
        xenon.clear();
        boronPpm = 0.0;
        fuelThermal.initialiseToSteadyState(0.0, pressurePsig);
        fuelThermal.restoreTemperatures(saturationC, saturationC);
        // The fuel damage record is history too, and this method is describing a
        // plant that has none — the decay heat inventory, the xenon and the
        // pressure boundary's accumulated wear are all cleared within a few lines
        // of here for exactly the same reason.
        //
        // It has to be cleared explicitly because peak clad and peak fuel
        // temperature are monotonic and initialiseToSteadyState above can only
        // raise them. So a core initialised twice kept the hotter of the two
        // conditions: constructed at hot shutdown and then initialised cold, it
        // sat at 99 degC reporting a peak clad temperature of 287.44 — the rated
        // saturation temperature, on a vessel that had never been within 900 psi
        // of rated pressure. That is the same 287 degC the first in-world playtest
        // found on the panel of an unfuelled core, arriving by a second route, and
        // fixing only the vessel would have left it there.
        //
        // Bit-identical for every existing caller. All three of them — this
        // class's own constructor, TransientHarness.shutdownCore() and the mod's
        // formation path — call an initialiser on a core built moments earlier,
        // whose record is pristine anyway, and at zero power the two temperatures
        // written here are the same doubles initialiseToSteadyState just derived
        // from the same pressure.
        fuelThermal.restoreDamageState(saturationC, saturationC, 0.0, 0.0);

        recirculationFlowFractionDemand = PhysicalConstants.RECIRC_LOW_SPEED_FRACTION;
        coreFlowFraction = PhysicalConstants.RECIRC_LOW_SPEED_FRACTION;
        voidModel.initialiseToSteadyState(0.0, coreFlowFraction, pressurePsig);

        turbineSteamFlowKgPerS = 0.0;
        bypassSteamFlowKgPerS = 0.0;
        reliefSteamFlowKgPerS = 0.0;
        feedwaterFlowKgPerS = 0.0;
        injectionFlowKgPerS = 0.0;
        coreSprayFlowKgPerS = 0.0;
        boronInjectionPpmPerMinute = 0.0;
        manualLiquidLeakKgPerS = 0.0;
        manualSteamLeakKgPerS = 0.0;
        lastBreakLiquidFlowKgPerS = 0.0;
        lastBreakSteamFlowKgPerS = 0.0;
        vessel.setLiquidLeakKgPerS(0.0);
        vessel.setSteamLeakKgPerS(0.0);
        boundary.reset();

        refreshNodalShape();
        double rho = closeReactivityBalance();
        kinetics.initialiseSubcritical(Math.min(rho, -1.0e-9));

        elapsedSeconds = 0.0;
        tickCount = 0L;
        secondsSinceAggregateRefresh = 0.0;
        settleInstruments();
    }

    /**
     * Steady state at a given <b>total</b> thermal power, fission plus decay heat.
     *
     * <p>Rated thermal power is a total, so this splits the requested figure
     * between fission and a saturated decay heat inventory rather than stacking
     * 6.6% of decay heat on top of 100% of fission. Void, fuel temperature,
     * xenon and vessel inventory are all put at their equilibria for that point,
     * turbine steam flow and feedwater are set to the flow the core actually
     * boils, and a rod pattern is searched for that brings net reactivity to
     * approximately zero.
     *
     * <p>Approximately, not exactly: rods are notched, so the achievable
     * reactivities are quantised at roughly a cent apiece and the core is left
     * within one rod-notch of critical. A real plant has the same problem and
     * answers it the same way, by trimming.
     *
     * @param totalPowerFractionOfRated total core thermal power, fraction of rated
     */
    public void initialiseAtTotalPowerFraction(double totalPowerFractionOfRated) {
        double total = Math.max(0.0, totalPowerFractionOfRated);
        double fission = total / (1.0 + DecayHeat.SATURATED_FRACTION_OF_RATED);

        scramActive = false;
        Arrays.fill(accumulatorCharge, 1.0);
        Arrays.fill(rodDriveTimerSeconds, 0.0);

        vessel.initialiseToNormalLevel();
        double pressurePsig = vessel.getPressurePsig();

        decayHeat.setToSaturatedInventory(fission);
        xenon.setToEquilibrium(fission);
        boronPpm = 0.0;

        recirculationFlowFractionDemand = 1.0;
        coreFlowFraction = 1.0;
        voidModel.initialiseToSteadyState(total, coreFlowFraction, pressurePsig);
        fuelThermal.initialiseToSteadyState(total, pressurePsig);

        // Steady state boils exactly what feedwater puts in, so the two flows are
        // equal and the balance closes on the vapour enthalpy rise from feedwater.
        double psia = Saturation.psiaFromPsig(pressurePsig);
        double enthalpyRise = Saturation.vapourEnthalpyKJPerKg(psia)
                - Saturation.subcooledLiquidEnthalpyKJPerKg(feedwaterTemperatureC);
        double steamFlow = (enthalpyRise > 0.0)
                ? total * config.ratedThermalMW * 1000.0 / enthalpyRise : 0.0;
        turbineSteamFlowKgPerS = steamFlow;
        bypassSteamFlowKgPerS = 0.0;
        reliefSteamFlowKgPerS = 0.0;
        feedwaterFlowKgPerS = steamFlow;
        injectionFlowKgPerS = 0.0;
        coreSprayFlowKgPerS = 0.0;
        boronInjectionPpmPerMinute = 0.0;
        manualLiquidLeakKgPerS = 0.0;
        manualSteamLeakKgPerS = 0.0;
        lastBreakLiquidFlowKgPerS = 0.0;
        lastBreakSteamFlowKgPerS = 0.0;
        vessel.setLiquidLeakKgPerS(0.0);
        vessel.setSteamLeakKgPerS(0.0);
        boundary.reset();
        applyFeedwaterTemperature();

        // Two passes over the shape and the rod pattern. Solve the flux for the
        // rods as they stand, trim the rods against the aggregates that fall out
        // of it, then re-solve because the rods just moved. The second correction
        // is small — a rod pattern change of a notch or two barely shifts a
        // fission-rate-weighted average — so two passes settles it and iterating
        // to a fixed point would buy nothing.
        refreshNodalShape();
        positionRodsForReactivity(0.0);
        refreshNodalShape();
        positionRodsForReactivity(0.0);
        // A third solve, with no rod movement after it, so that the shape the
        // core is left holding belongs to the rod pattern the core is left in.
        // Every pass above ends by moving rods, which leaves the per-rod flux
        // weights one pass stale — and per-rod weights are not a detail that
        // averages out, because the pattern is uniform-plus-a-few-withdrawn and
        // the worth of that pattern is the worth of exactly those few rods. A
        // core restored from a snapshot re-solves at the final pattern, so
        // without this an initialised core and a reloaded one holding the same
        // rods disagree about reactivity by about 1e-5 dk/k, and drift apart
        // from there.
        //
        // It costs the last fraction of a notch of trim: the balance is closed
        // below against weights the rod search did not see, so the core comes up
        // a fifth of a notch off critical instead of a hundredth. The method
        // contract already promises only "within one rod-notch of critical", and
        // a self-consistent shape is worth more than that fifth of a notch.
        refreshNodalShape();
        Arrays.fill(rodNotchDemand, 0);
        System.arraycopy(rodNotchIndex, 0, rodNotchDemand, 0, controlRodCount);

        closeReactivityBalance();
        kinetics.initialiseToEquilibrium(fission);

        elapsedSeconds = 0.0;
        tickCount = 0L;
        secondsSinceAggregateRefresh = 0.0;
        settleInstruments();
    }

    /**
     * Search for the rod pattern whose worth brings net reactivity closest to a
     * target, and apply it.
     *
     * <p>Every other term in the balance is taken as it currently stands, so this
     * must be called after the void, fuel temperature and xenon states are set.
     * The pattern is uniform at some notch with a number of rods one notch
     * further out — which is a rod pattern, and is how notched drives produce a
     * fractional-notch answer.
     *
     * @param targetDkOverK net reactivity wanted, dk/k. Zero for critical.
     * @return the net reactivity actually achieved, dk/k
     */
    public double positionRodsForReactivity(double targetDkOverK) {
        double withoutRods = reactivityWithRodWorth(0.0);
        double neededRodWorth = targetDkOverK - withoutRods;

        int[] pattern = new int[controlRodCount];
        int uniform = RodWorth.NOTCH_INDEX_FULLY_INSERTED;
        for (int k = RodWorth.NOTCH_INDEX_FULLY_INSERTED; k <= RodWorth.NOTCH_INDEX_FULLY_WITHDRAWN; k++) {
            Arrays.fill(pattern, k);
            if (rodWorth.totalInsertedWorthDkOverK(pattern) <= neededRodWorth) {
                uniform = k;
            } else {
                break;
            }
        }

        Arrays.fill(pattern, uniform);
        double bestError = Math.abs(rodWorth.totalInsertedWorthDkOverK(pattern) - neededRodWorth);
        int bestWithdrawn = 0;
        if (uniform < RodWorth.NOTCH_INDEX_FULLY_WITHDRAWN) {
            for (int r = 0; r < controlRodCount; r++) {
                pattern[r] = uniform + 1;
                double error = Math.abs(rodWorth.totalInsertedWorthDkOverK(pattern) - neededRodWorth);
                if (error < bestError) {
                    bestError = error;
                    bestWithdrawn = r + 1;
                } else {
                    break;
                }
            }
        }

        Arrays.fill(pattern, uniform);
        for (int r = 0; r < bestWithdrawn; r++) {
            pattern[r] = uniform + 1;
        }
        System.arraycopy(pattern, 0, rodNotchIndex, 0, controlRodCount);
        System.arraycopy(pattern, 0, rodNotchDemand, 0, controlRodCount);
        Arrays.fill(rodDriveTimerSeconds, 0.0);
        return closeReactivityBalance();
    }

    /** Net reactivity the balance would report with a given total rod worth, dk/k. */
    private double reactivityWithRodWorth(double rodWorthDkOverK) {
        reactivity.setRodWorthDkOverK(rodWorthDkOverK);
        reactivity.setVoidFraction(voidModel.getCoreAverageVoidFraction());
        reactivity.setFuelTemperatureC(fuelThermal.getFuelTemperatureC());
        reactivity.setXenonConcentration(xenon.fractionOfRatedEquilibrium());
        reactivity.setPressurePsig(vessel.getPressurePsig());
        reactivity.setBoronPpm(boronPpm);
        return reactivity.update();
    }

    private double closeReactivityBalance() {
        return reactivityWithRodWorth(rodWorth.totalInsertedWorthDkOverK(rodNotchIndex));
    }

    // ---------------------------------------------------------------
    // The tick
    // ---------------------------------------------------------------

    /** Advance the whole model one game tick, {@link CoreConfig#tickSeconds}. */
    public void step() {
        step(config.tickSeconds);
    }

    /**
     * Advance the whole model by an arbitrary interval. Callers in the mod should
     * use {@link #step()}; this exists for the standalone harness and for tests
     * that want a coarser or finer clock.
     *
     * @param dtSeconds interval, seconds; non-positive is a no-op
     */
    public void step(double dtSeconds) {
        if (!(dtSeconds > 0.0) || !Double.isFinite(dtSeconds)) {
            return;
        }

        // --- 1. Hardware. Rod drives move, accumulators drain or recharge, the
        //        recirculation loop answers its demand through the pump lag.
        stepControlRodDrives(dtSeconds);
        stepRecirculationFlow(dtSeconds);
        stepBoronInjection(dtSeconds);

        // --- 2. Void fraction, computed at LAST tick's pressure and last tick's
        //        power. Together with step 6 this is the timescale separation the
        //        class comment insists on.
        double previousTotalPower = getTotalPowerFractionOfRated();
        voidModel.update(previousTotalPower, coreFlowFraction, vessel.getPressurePsig(), dtSeconds);

        // --- 3. Close the reactivity balance once. One scalar rho for this tick.
        double rho = closeReactivityBalance();

        // --- 4. Point kinetics, sub-stepped internally at that frozen rho.
        //        PointKinetics sub-divides further on its own if the implicit
        //        denominator says the step is under-resolved, which only happens
        //        above prompt critical.
        double fissionPower = kinetics.step(rho, dtSeconds);

        // --- 5. Decay heat from fission power alone (feeding total power back
        //        into it would make the inventory chase itself), then the fuel
        //        and clad nodes, which see both.
        double decayFraction = decayHeat.step(fissionPower, dtSeconds);
        fuelThermal.setCoveredFuelFraction(vessel.getCoveredFuelFraction());
        fuelThermal.setSteamCoolingFlowKgPerS(vessel.getSteamGenerationKgPerS());
        fuelThermal.setSteamSupplyKgPerS(Math.max(0.0, vessel.getSteamGenerationKgPerS())
                + fuelThermal.getSprayEvaporationKgPerS());
        fuelThermal.setCoreSprayFlowKgPerS(coreSprayFlowKgPerS);
        fuelThermal.step(fissionPower, decayFraction, vessel.getPressurePsig(), dtSeconds);

        // --- 6. Pressure and inventory, ONCE per tick. This is what produces the
        //        pressure the next tick's void solve will see.
        //
        //        Breaks in the pressure boundary are applied here, at the start
        //        of the vessel solve, using the damage state as it stood at the
        //        end of the last tick. A component that fails in step 8 below
        //        therefore starts discharging on the following tick, which is
        //        50 ms of latency and keeps the vessel solve consistent with the
        //        boundary state it was handed.
        double totalPower = fissionPower + decayFraction;
        double breakPressurePsig = vessel.getPressurePsig();
        vessel.setFeedwaterFlowKgPerS(feedwaterFlowKgPerS * boundary.feedwaterDeliveredFraction());

        // Core spray is liquid makeup only for the part of it that survives the
        // trip past the fuel. FuelThermal has just told us how much of it boiled
        // off cooling the clad, and that steam has already been handed back as
        // reactant for the zirconium reaction — crediting the same kilograms to
        // the vessel as liquid would count them twice. Above a few hundred
        // degrees of clad superheat the evaporated fraction is essentially 1.0,
        // so without the deduction a 200 kg/s spray onto a dry core refloods the
        // vessel at 12 tonnes a minute of water that has already been spent, and
        // the core re-covers on inventory that does not exist.
        double sprayEvaporationKgPerS = fuelThermal.getSprayEvaporationKgPerS();
        vessel.setInjectionFlowKgPerS(Math.max(0.0,
                injectionFlowKgPerS + coreSprayFlowKgPerS - sprayEvaporationKgPerS));
        // A hole below the water line can only pass water while there is water
        // above it. Once the vessel is dry the break is uncovered and discharges
        // steam, which the steam-side term already accounts for; capping here
        // keeps the reported discharge honest instead of leaving it quoting a
        // flow out of an empty vessel.
        lastBreakLiquidFlowKgPerS = Math.min(
                boundary.liquidBreakFlowKgPerS(breakPressurePsig),
                vessel.getLiquidMassKg() / dtSeconds);
        lastBreakSteamFlowKgPerS = boundary.steamBreakFlowKgPerS(breakPressurePsig);
        vessel.setLiquidLeakKgPerS(manualLiquidLeakKgPerS + lastBreakLiquidFlowKgPerS);
        // Steam the zirconium-water reaction eats never reaches a valve, but it
        // does leave the steam space, so it belongs with the other sinks. The
        // reaction is limited by the steam available to it (FuelThermal caps its
        // rate on setSteamSupplyKgPerS), and a limit that consumes nothing is not
        // a limit: without this the model lets a runaway oxidation burn steam it
        // never removes from the vessel.
        vessel.setSteamLeakKgPerS(manualSteamLeakKgPerS + lastBreakSteamFlowKgPerS
                + fuelThermal.getSteamConsumptionKgPerS());
        // The vessel is fed the heat SOURCES, not the clad-to-coolant conduction
        // path — that decoupling is deliberate. The zirconium-water reaction is a
        // third source alongside fission and decay heat, and at 1800 degC it is
        // the largest of the three (roughly 400 MW against 50 MW of decay heat).
        // FuelThermal deposits it in the clad node and nowhere else, so until it
        // is added here the biggest heat source in a severe accident boils no
        // water, raises no pressure and drains no inventory.
        vessel.step(totalPower * config.ratedThermalMW + fuelThermal.getZirconiumReactionPowerMW(),
                voidModel.getCoreAverageVoidFraction(),
                turbineSteamFlowKgPerS,
                bypassSteamFlowKgPerS,
                reliefSteamFlowKgPerS,
                dtSeconds);

        // --- 7. Slow states and instruments.
        xenon.tick(fissionPower, dtSeconds);
        stepAggregateRefresh(totalPower, dtSeconds);
        stepInstruments(fissionPower, dtSeconds);

        // --- 8. Overpressure wear, against the pressure this tick just produced.
        //        The only automatic consequence in the plant, and the only place
        //        anything is drawn at random. It accumulates damage; it does not
        //        warn, trip or relieve, and it never touches a valve.
        boundary.setIrradiatedFuelPresent(
                decayHeat.getFractionOfRated() > 0.0 || loading.averageBurnupMwdPerTonne() > 0.0);
        boundary.step(vessel.getPressurePsig(), dtSeconds);

        elapsedSeconds += dtSeconds;
        tickCount++;
    }

    /**
     * Control rod drive motion. Rods index one discrete notch at a time — there
     * is no continuous position and no percentage, because a BWR drive is a
     * notched hydraulic collet and its position is one of twenty-five things.
     */
    private void stepControlRodDrives(double dtSeconds) {
        double domePressurePsig = vessel.getPressurePsig();
        boolean pressureAssist = domePressurePsig >= SCRAM_PRESSURE_ASSIST_PSIG;
        boolean normalMotionAvailable = crdPowered && crdWaterSupplied;

        for (int r = 0; r < controlRodCount; r++) {
            int target;
            double secondsPerNotch;

            if (scramActive) {
                target = RodWorth.NOTCH_INDEX_FULLY_INSERTED;
                if (accumulatorCharge[r] > 0.0) {
                    secondsPerNotch = SCRAM_FULL_STROKE_SECONDS / NOTCH_SPANS;
                } else if (pressureAssist) {
                    secondsPerNotch =
                            SCRAM_FULL_STROKE_SECONDS / NOTCH_SPANS / PRESSURE_ASSIST_SPEED_FRACTION;
                } else {
                    // No stored charge and no reactor pressure behind the piston.
                    // This rod is stuck where it is, and it is stuck because its
                    // accumulator was allowed to drain — a consequence of neglect,
                    // not a dice roll.
                    rodDriveTimerSeconds[r] = 0.0;
                    continue;
                }
            } else {
                if (!normalMotionAvailable) {
                    rodDriveTimerSeconds[r] = 0.0;
                    continue;
                }
                target = rodNotchDemand[r];
                secondsPerNotch = NORMAL_SECONDS_PER_NOTCH;
            }

            if (rodNotchIndex[r] == target) {
                rodDriveTimerSeconds[r] = 0.0;
                continue;
            }

            rodDriveTimerSeconds[r] += dtSeconds;
            while (rodDriveTimerSeconds[r] >= secondsPerNotch && rodNotchIndex[r] != target) {
                rodDriveTimerSeconds[r] -= secondsPerNotch;
                rodNotchIndex[r] += (target > rodNotchIndex[r]) ? 1 : -1;
                if (scramActive) {
                    // A charge too small to index one more notch is not a charge,
                    // so it is spent rather than left as a residue. Twenty-four
                    // subtractions of 1/24 from 1.0 do not land on zero in binary
                    // floating point, and without this snap a completed full-stroke
                    // scram would leave every accumulator holding about 1e-16 of a
                    // charge — which getChargedAccumulatorCount() would then report
                    // as a full complement of armed drives. That readout is "how
                    // many rods will actually insert if I scram right now" and it
                    // is the one number on the panel that must never lie.
                    double remaining = accumulatorCharge[r] - ACCUMULATOR_CHARGE_PER_NOTCH;
                    accumulatorCharge[r] = (remaining >= ACCUMULATOR_CHARGE_PER_NOTCH) ? remaining : 0.0;
                }
            }
        }

        // Recharging needs both electrical power and a water supply. Losing the
        // water means scram capability decays even with the bus energised, which
        // is the non-obvious failure mode SPEC 3.3 asks to be surfaced.
        if (!scramActive && crdPowered && crdWaterSupplied) {
            double gain = accumulatorRechargePerSecond * dtSeconds;
            for (int r = 0; r < controlRodCount; r++) {
                if (accumulatorCharge[r] < 1.0) {
                    accumulatorCharge[r] = Math.min(1.0, accumulatorCharge[r] + gain);
                }
            }
        }
    }

    private void stepRecirculationFlow(double dtSeconds) {
        // A broken recirculation line delivers nothing however hard the pumps
        // are driven, but the coastdown lag still applies: the flywheels are
        // still turning, they are just pushing water onto the drywell floor.
        double target = recirculationFlowFractionDemand * boundary.coreFlowDeliveredFraction();
        double tau = (target >= coreFlowFraction)
                ? FLOW_ACCELERATION_TIME_CONSTANT_S : FLOW_COASTDOWN_TIME_CONSTANT_S;
        double alpha = (tau > 0.0) ? 1.0 - Math.exp(-dtSeconds / tau) : 1.0;
        coreFlowFraction += (target - coreFlowFraction) * alpha;
    }

    private void stepBoronInjection(double dtSeconds) {
        if (boronInjectionPpmPerMinute > 0.0) {
            boronPpm = Math.min(PhysicalConstants.SLC_DESIGN_BORON_PPM,
                    boronPpm + boronInjectionPpmPerMinute * dtSeconds / 60.0);
        }
    }

    /**
     * The slow loop: fuel aggregates and burnup, refreshed on the nodal-solve
     * interval rather than every tick. Flux shape changes slowly, flux level
     * changes fast — {@code SPEC.md} section 1.3.
     */
    private void stepAggregateRefresh(double totalPowerFraction, double dtSeconds) {
        secondsSinceAggregateRefresh += dtSeconds;
        double interval = Math.max(1, config.nodalSolveIntervalTicks) * config.tickSeconds;
        if (secondsSinceAggregateRefresh < interval) {
            return;
        }
        if (burnupEnabled) {
            loading.advanceBurnup(totalPowerFraction * config.ratedThermalMW,
                    secondsSinceAggregateRefresh);
        }
        secondsSinceAggregateRefresh = 0.0;
        refreshNodalShape();
    }

    /**
     * Re-solve the spatial flux shape and push what falls out of it into the
     * scalar model: per-assembly power weights, and through them the
     * fission-rate-weighted effective beta, prompt lifetime, Doppler coefficient
     * and excess reactivity.
     *
     * <p><b>This is the only place the shape changes, and that is the invariant
     * the whole neutronics rests on.</b> Between calls — every
     * {@link CoreConfig#nodalSolveIntervalTicks} ticks, 1 Hz by default — the
     * point kinetics treats {@code beta_eff} and the power weights as frozen
     * constants. Flux <i>shape</i> is driven by rod motion, void, burnup and
     * xenon, which move over seconds to hours; flux <i>level</i> is driven by
     * prompt neutrons on a 4e-5 s lifetime. Re-solving the shape inside the
     * kinetics sub-step would couple those two timescales at 20-microsecond
     * resolution and hand the integrator back exactly the stiffness it exists to
     * avoid — against inputs that only have tick resolution to begin with. The
     * separation is load bearing. Solve more often if you must; do not solve
     * deeper.
     */
    private void refreshNodalShape() {
        nodalFlux.setRodNotchIndices(rodNotchIndex);
        nodalFlux.setVoidProfile(voidModel.getBoilingBoundaryFraction(),
                voidModel.getExitVoidFraction());
        loading.invalidateWeights();

        // Force the one solve this interval is allowed, here, before anything
        // reads a quantity derived from it.
        //
        // This is not belt and braces. CoreLoading.weightedAverage short-circuits
        // a property that has the same value in every assembly — deliberately, to
        // keep a single-fuel core's aggregates independent of the solver's
        // convergence path — so on a fresh uniform core refreshFuelAggregates()
        // below asks the solver for nothing at all, and the shape would never be
        // re-solved. It works out on a burning core only because advanceBurnup()
        // happens to call powerWeights() first. Asking for the weights here makes
        // the solve happen exactly once per refresh in every case, at the rod
        // positions and void profile just pushed in, and it costs nothing extra:
        // CoreLoading caches what comes back, so the aggregate reads below and
        // next second's burnup step both hit that cache instead of re-solving.
        loading.powerWeights();

        // The radial half of rod worth. The solve knows which rods sit in peak
        // flux and which sit in the reflector; without this the reactivity
        // balance does not, and every rod ends up worth totalRodWorth/count.
        installRodFluxWeights();

        refreshFuelAggregates();
    }

    /**
     * Push the per-rod relative flux weights from the last spatial solve into
     * {@link RodWorth}, so that a central rod is worth several times a peripheral
     * one ({@code SPEC.md} section 1.3, {@code REFERENCE-DATA.md} section 10).
     *
     * <p>This is the consumer {@code FluxSolution.rodFluxWeights()} exists for.
     * Without it the nodal solve computes the number and the reactivity balance
     * throws it away: rod worth is uniform, a stuck central rod costs the same
     * shutdown margin as a stuck peripheral one, and
     * {@link #positionRodsForReactivity} cannot tell the difference between
     * withdrawing one and withdrawing the other. <b>Do not remove this call
     * because rod worth "looks like it already works" — it works numerically and
     * is wrong spatially.</b>
     *
     * <p>Called from {@link #refreshNodalShape()}, which is the only place the
     * shape is established, so rod worth changes on the same 1 Hz cadence as
     * beta_eff and the power weights and stays a frozen constant inside the
     * kinetics sub-step like everything else the solve produces.
     *
     * <p>{@link #fromState} calls it only as a <b>fallback</b>, for a record that
     * carries no weights of its own. A record that does carry them installs those
     * instead, verbatim, because a restored core has to resume on the weighting it
     * was suspended on and this method by definition produces the one solved a
     * moment ago — see {@code restorePersistedRodFluxWeights}.
     *
     * <p>Every degenerate case is skipped rather than forced, because
     * {@code setRodFluxWeights} throws on a bad array and a solve that produced
     * one is not a reason to fail a tick: an unsolvable core keeps whatever
     * weighting it last had.
     */
    private void installRodFluxWeights() {
        FluxSolution solution = nodalFlux.lastSolution();
        if (solution == null) {
            return;
        }
        double[] weights = solution.rodFluxWeights();
        if (weights == null || weights.length != controlRodCount) {
            return;
        }
        double sum = 0.0;
        for (double weight : weights) {
            if (!(weight >= 0.0) || !Double.isFinite(weight)) {
                return;
            }
            sum += weight;
        }
        if (!(sum > 0.0)) {
            return;
        }
        rodWorth.setRodFluxWeights(weights);
    }

    /**
     * Put persisted per-rod flux weights back exactly as they were recorded, for
     * {@link #fromState}.
     *
     * <p>Not a solve and not a normalisation — {@code RodWorth.restoreRodFluxWeights}
     * takes the array as given, which is the whole point: the reactivity balance
     * recorded in the snapshot was closed on these numbers and closing it again on
     * anything else, including a rescale of these numbers, does not reproduce it.
     *
     * <p>Every degenerate case is <em>reported</em> rather than thrown, mirroring
     * {@link #installRodFluxWeights()}: a record with no weights in it (one that
     * predates the component) or with the wrong number of them (one whose
     * multiblock has since changed size) is not a reason to fail a chunk load. The
     * caller falls back to the restore-time solve, which is what such a save
     * already got.
     *
     * @return true when the persisted weights were installed
     */
    private boolean restorePersistedRodFluxWeights(double[] weights) {
        if (weights == null || weights.length != controlRodCount) {
            return false;
        }
        double sum = 0.0;
        for (double weight : weights) {
            if (!(weight >= 0.0) || !Double.isFinite(weight)) {
                return false;
            }
            sum += weight;
        }
        if (!(sum > 0.0)) {
            return false;
        }
        rodWorth.restoreRodFluxWeights(weights);
        return true;
    }

    /**
     * Push the loaded fuel's aggregate constants into the kinetics and the
     * reactivity balance. Precursor inventories are deliberately carried across a
     * beta change rather than reset — they are physical inventories that exist
     * regardless of what the fission-rate weighting says this second.
     */
    private void refreshFuelAggregates() {
        double beta = loading.effectiveBeta();
        if (!(Math.abs(beta - cachedAggregateBeta) < 1.0e-9)) {
            kinetics.setDelayedNeutronData(delayedDataFor(beta));
            cachedAggregateBeta = beta;
        }
        kinetics.setPromptLifetimeSeconds(loading.effectivePromptLifetimeSeconds());
        reactivity.setFuelExcessReactivityDkOverK(loading.excessReactivityAllRodsOutDkK());
        reactivity.setDopplerCoefficientPerCAtAnchor(loading.effectiveDopplerCoeffPerC());
    }

    /**
     * Delayed neutron data for a core whose flux-weighted total beta is the given
     * value. Where that total is achievable as a uranium/plutonium fission blend
     * — which covers every fuel in the registry and every mixture of them — the
     * group spectrum is the real blend of the two nuclides rather than uranium's
     * shape wearing someone else's magnitude.
     */
    private static DelayedNeutronData delayedDataFor(double betaTotalFraction) {
        double lower = DelayedNeutronData.PU239.betaTotalFraction();
        double upper = DelayedNeutronData.U235.betaTotalFraction();
        if (betaTotalFraction >= lower && betaTotalFraction <= upper) {
            return DelayedNeutronData.uraniumPlutoniumBlendWithTotalBeta(
                    "core", betaTotalFraction);
        }
        return DelayedNeutronData.U235.withTotalBetaFraction("core", Math.max(0.0, betaTotalFraction));
    }

    private void stepInstruments(double fissionPowerFraction, double dtSeconds) {
        for (int i = 0; i < sourceRangeMonitors.length; i++) {
            sourceRangeMonitors[i].update(fissionPowerFraction, dtSeconds);
            sourceRangePeriodMeters[i].update(dtSeconds);
        }
        for (int i = 0; i < intermediateRangeMonitors.length; i++) {
            intermediateRangeMonitors[i].update(fissionPowerFraction, dtSeconds);
            intermediateRangePeriodMeters[i].update(dtSeconds);
        }
        for (AveragePowerRangeMonitor aprm : averagePowerRangeMonitors) {
            aprm.update(fissionPowerFraction, dtSeconds);
        }
    }

    /**
     * Drive every detector's conditioning lag to its settled value for the
     * current flux, and park the period meters at centre scale.
     *
     * <p>Used after an initialisation or a state restore. Detector signals are
     * not in {@link ReactorState} — a signal-conditioning lag is not plant state,
     * it is a filter — so on a chunk reload the channels re-settle rather than
     * resume, which takes well under a second of indication and is exactly what a
     * real instrument rack does when its power comes back.
     *
     * <p>Range switch positions <i>are</i> plant state and <i>are</i> in the
     * record, and {@link #fromState} puts them back before calling this. Order
     * matters: an IRM's indication is the flux divided by the full scale of the
     * selected detent, so a channel settled first and ranged afterwards is
     * showing the swing of a range change nobody made.
     */
    public void settleInstruments() {
        double n = kinetics.getNeutronPowerFraction();
        double settle = 60.0;
        for (int i = 0; i < sourceRangeMonitors.length; i++) {
            sourceRangeMonitors[i].update(n, settle);
            sourceRangePeriodMeters[i].reset();
        }
        for (int i = 0; i < intermediateRangeMonitors.length; i++) {
            intermediateRangeMonitors[i].update(n, settle);
            intermediateRangePeriodMeters[i].reset();
        }
        for (AveragePowerRangeMonitor aprm : averagePowerRangeMonitors) {
            aprm.update(n, settle);
        }
    }

    // ---------------------------------------------------------------
    // Actuators — control rods
    // ---------------------------------------------------------------

    /**
     * <b>SCRAM.</b> Fire the accumulators and drive every rod that can move to
     * the bottom.
     *
     * <p>This checks nothing. It does not look at power, period, pressure, level,
     * flow or rod position first, and it will happily scram a core that is
     * already shut down or refuse nothing at all. Deciding <i>when</i> to call it
     * is the player's control logic, written in Lua, and the deliberate absence
     * of any condition here is the point of the whole project.
     *
     * <p>What it cannot do is move a rod whose accumulator is flat on a
     * depressurised vessel. That rod stays where it is.
     */
    public void scram() {
        scramActive = true;
        for (int r = 0; r < controlRodCount; r++) {
            rodNotchDemand[r] = RodWorth.NOTCH_INDEX_FULLY_INSERTED;
            rodDriveTimerSeconds[r] = 0.0;
        }
    }

    /**
     * Clear the scram signal so the drives will answer withdrawal demands again.
     * An actuator, like {@link #scram()}: it makes no judgement about whether
     * clearing the signal is a sensible thing to do right now.
     */
    public void resetScram() {
        scramActive = false;
    }

    /** True while the scram signal is latched in. */
    public boolean isScramActive() {
        return scramActive;
    }

    /**
     * Command one rod to a notch position. 0 is fully inserted, 24 fully
     * withdrawn; the Full Core Display label is twice the index.
     *
     * <p>This sets a demand. The drive indexes toward it one notch at a time at
     * {@link #NORMAL_SECONDS_PER_NOTCH} per notch, and only while it has both
     * power and a water supply.
     */
    public void setRodNotchDemand(int rodIndex, int notchIndex) {
        checkRodIndex(rodIndex);
        if (notchIndex < RodWorth.NOTCH_INDEX_FULLY_INSERTED
                || notchIndex > RodWorth.NOTCH_INDEX_FULLY_WITHDRAWN) {
            throw new IllegalArgumentException("notch index out of range 0.."
                    + RodWorth.NOTCH_INDEX_FULLY_WITHDRAWN + ": " + notchIndex);
        }
        rodNotchDemand[rodIndex] = notchIndex;
    }

    /** Command every rod to the same notch. Convenience; the drives are still individual. */
    public void setAllRodNotchDemand(int notchIndex) {
        for (int r = 0; r < controlRodCount; r++) {
            setRodNotchDemand(r, notchIndex);
        }
    }

    /** Command one rod by its Full Core Display label, 00 to 48 in steps of two. */
    public void setRodNotchLabelDemand(int rodIndex, int notchLabel) {
        setRodNotchDemand(rodIndex, RodWorth.notchIndexFromLabel(notchLabel));
    }

    /** Where a rod's drive is being asked to go, notch index. */
    public int getRodNotchDemand(int rodIndex) {
        checkRodIndex(rodIndex);
        return rodNotchDemand[rodIndex];
    }

    /** Where a rod actually is, notch index 0..24. */
    public int getRodNotchIndex(int rodIndex) {
        checkRodIndex(rodIndex);
        return rodNotchIndex[rodIndex];
    }

    /** Where a rod actually is, as the 00..48 Full Core Display label. */
    public int getRodNotchLabel(int rodIndex) {
        return RodWorth.notchLabel(getRodNotchIndex(rodIndex));
    }

    /** Copy of every rod's actual notch index. */
    public int[] getRodNotchIndices() {
        return rodNotchIndex.clone();
    }

    /** True when every drive has reached its demanded notch. */
    public boolean isRodMotionComplete() {
        for (int r = 0; r < controlRodCount; r++) {
            if (rodNotchIndex[r] != rodNotchDemand[r]) {
                return false;
            }
        }
        return true;
    }

    /** Number of control rods, and therefore of control rod drives. */
    public int getControlRodCount() {
        return controlRodCount;
    }

    /** One CRD's accumulator charge, 0 to 1. */
    public double getAccumulatorCharge(int rodIndex) {
        checkRodIndex(rodIndex);
        return accumulatorCharge[rodIndex];
    }

    /** Copy of every CRD's accumulator charge. */
    public double[] getAccumulatorCharges() {
        return accumulatorCharge.clone();
    }

    /**
     * How many rods will actually insert if a scram is fired right now — the
     * panel readout {@code SPEC.md} section 3.3 requires. A count of hardware
     * capability, not a judgement about whether the count is adequate.
     */
    public int getChargedAccumulatorCount() {
        int count = 0;
        for (double charge : accumulatorCharge) {
            if (charge > 0.0) {
                count++;
            }
        }
        return count;
    }

    /** Whether the control rod drive bus is energised. */
    public boolean isCrdPowered() {
        return crdPowered;
    }

    /** @see #isCrdPowered() */
    public void setCrdPowered(boolean crdPowered) {
        this.crdPowered = crdPowered;
    }

    /**
     * Whether the CRD hydraulic system has its demineralised water supply.
     * Without it accumulators cannot recharge, so scram capability decays even
     * with the bus energised.
     */
    public boolean isCrdWaterSupplied() {
        return crdWaterSupplied;
    }

    /** @see #isCrdWaterSupplied() */
    public void setCrdWaterSupplied(boolean crdWaterSupplied) {
        this.crdWaterSupplied = crdWaterSupplied;
    }

    /** Accumulator recharge rate, fraction of full charge per second. */
    public double getAccumulatorRechargePerSecond() {
        return accumulatorRechargePerSecond;
    }

    /** @see #getAccumulatorRechargePerSecond() */
    public void setAccumulatorRechargePerSecond(double rate) {
        if (!(rate >= 0.0) || !Double.isFinite(rate)) {
            throw new IllegalArgumentException("recharge rate must be finite and non-negative, got " + rate);
        }
        this.accumulatorRechargePerSecond = rate;
    }

    // ---------------------------------------------------------------
    // Actuators — flow, steam, water, boron
    // ---------------------------------------------------------------

    /**
     * Commanded recirculation flow, fraction of rated core flow. The actual flow
     * follows through the pump lag, slower on the way down than on the way up
     * because of the flywheel.
     *
     * <p>Along with rod position this is one of only two handles on power, and
     * the only one that moves power without moving a rod: less flow means more
     * void means less reactivity.
     */
    public void setRecirculationFlowFraction(double fraction) {
        if (!Double.isFinite(fraction)) {
            return;
        }
        this.recirculationFlowFractionDemand = Math.max(0.0, fraction);
    }

    /** Commanded recirculation flow, fraction of rated. */
    public double getRecirculationFlowFractionDemand() {
        return recirculationFlowFractionDemand;
    }

    /** Actual core flow, fraction of rated, after the pump lag. */
    public double getCoreFlowFraction() {
        return coreFlowFraction;
    }

    /** Actual core flow, kg/s. */
    public double getCoreFlowKgPerS() {
        return coreFlowFraction * voidModel.getRatedCoreFlowKgPerS();
    }

    /** Steam to the turbine control valves, kg/s. */
    public void setTurbineSteamFlowKgPerS(double flow) {
        this.turbineSteamFlowKgPerS = nonNegative(flow);
    }

    public double getTurbineSteamFlowKgPerS() {
        return turbineSteamFlowKgPerS;
    }

    /** Steam to the turbine bypass valves, kg/s. */
    public void setBypassSteamFlowKgPerS(double flow) {
        this.bypassSteamFlowKgPerS = nonNegative(flow);
    }

    public double getBypassSteamFlowKgPerS() {
        return bypassSteamFlowKgPerS;
    }

    /**
     * Steam through safety and relief valves, kg/s.
     *
     * <p><b>Nothing in this model opens an SRV.</b> They are player-actuated
     * through CC:Tweaked or redstone. {@link PhysicalConstants#SRV_SPRING_SETTINGS_PSIG}
     * records what the real valves are rated at, and deciding when to lift them
     * is Lua's job.
     */
    public void setReliefSteamFlowKgPerS(double flow) {
        this.reliefSteamFlowKgPerS = nonNegative(flow);
    }

    public double getReliefSteamFlowKgPerS() {
        return reliefSteamFlowKgPerS;
    }

    /** Total steam leaving the vessel, kg/s: turbine plus bypass plus relief. */
    public double getCommandedSteamFlowKgPerS() {
        return turbineSteamFlowKgPerS + bypassSteamFlowKgPerS + reliefSteamFlowKgPerS;
    }

    /** Feedwater flow into the vessel, kg/s. The normal level control path. */
    public void setFeedwaterFlowKgPerS(double flow) {
        this.feedwaterFlowKgPerS = nonNegative(flow);
    }

    public double getFeedwaterFlowKgPerS() {
        return feedwaterFlowKgPerS;
    }

    /**
     * Final feedwater temperature, degrees C. Colder feedwater means more core
     * inlet subcooling, a higher boiling boundary and less void, so this is a
     * genuine reactivity handle and not just a heat balance detail. Applied to
     * both the vessel energy balance and the void model, which each need it.
     */
    public void setFeedwaterTemperatureC(double temperatureC) {
        if (!Double.isFinite(temperatureC)) {
            return;
        }
        this.feedwaterTemperatureC = temperatureC;
        applyFeedwaterTemperature();
    }

    public double getFeedwaterTemperatureC() {
        return feedwaterTemperatureC;
    }

    private void applyFeedwaterTemperature() {
        vessel.setFeedwaterTemperatureC(feedwaterTemperatureC);
        voidModel.setFeedwaterTemperatureC(feedwaterTemperatureC);
    }

    /**
     * Emergency injection flow into the vessel, kg/s — RCIC, HPCI, HPCS, LPCI,
     * CRD purge, whatever the player has running, summed. This model does not
     * know which system it came from and has no opinion about whether it should
     * be running.
     */
    public void setInjectionFlowKgPerS(double flow) {
        this.injectionFlowKgPerS = nonNegative(flow);
    }

    public double getInjectionFlowKgPerS() {
        return injectionFlowKgPerS;
    }

    /** Injection water temperature, degrees C. */
    public void setInjectionTemperatureC(double temperatureC) {
        vessel.setInjectionTemperatureC(temperatureC);
    }

    /**
     * Core spray flow, kg/s. Spray cools uncovered fuel directly; injection has
     * to refill the vessel first. They behave differently on purpose.
     */
    public void setCoreSprayFlowKgPerS(double flow) {
        this.coreSprayFlowKgPerS = nonNegative(flow);
    }

    public double getCoreSprayFlowKgPerS() {
        return coreSprayFlowKgPerS;
    }

    /** Core spray water temperature, degrees C. */
    public void setCoreSprayTemperatureC(double temperatureC) {
        fuelThermal.setSprayTemperatureC(temperatureC);
    }

    /**
     * Standby liquid control injection rate, ppm of boron per minute. The design
     * range is {@link PhysicalConstants#SLC_INJECTION_PPM_PER_MIN_MIN} to
     * {@link PhysicalConstants#SLC_INJECTION_PPM_PER_MIN_MAX}; nothing here
     * enforces it, and nothing here decides that boron is called for.
     */
    public void setBoronInjectionPpmPerMinute(double ppmPerMinute) {
        this.boronInjectionPpmPerMinute = nonNegative(ppmPerMinute);
    }

    public double getBoronInjectionPpmPerMinute() {
        return boronInjectionPpmPerMinute;
    }

    /** Dissolved boron in the coolant, ppm. */
    public double getBoronPpm() {
        return boronPpm;
    }

    /** Set the dissolved boron inventory directly, ppm. For staging a scenario. */
    public void setBoronPpm(double ppm) {
        this.boronPpm = nonNegative(ppm);
    }

    /** Drywell temperature, degrees C — it distorts the vessel level indication. */
    public void setDrywellTemperatureC(double temperatureC) {
        vessel.setDrywellTemperatureC(temperatureC);
    }

    /**
     * Liquid leaving through a break below the water line, kg/s, staged by
     * hand. Added to whatever the overpressure damage model is discharging
     * through its own breaks, so setting this to zero does not plug a hole the
     * plant tore in itself.
     */
    public void setLiquidLeakKgPerS(double flow) {
        this.manualLiquidLeakKgPerS = nonNegative(flow);
    }

    /** @see #setLiquidLeakKgPerS(double) */
    public double getLiquidLeakKgPerS() {
        return manualLiquidLeakKgPerS;
    }

    /** Steam leaving through a break in the steam space, kg/s, staged by hand. */
    public void setSteamLeakKgPerS(double flow) {
        this.manualSteamLeakKgPerS = nonNegative(flow);
    }

    /** @see #setSteamLeakKgPerS(double) */
    public double getSteamLeakKgPerS() {
        return manualSteamLeakKgPerS;
    }

    /**
     * Startup neutron source strength, fraction-of-rated per second.
     *
     * <p>A real installed source decays over the cycle and an unfuelled core has
     * only spontaneous fission, so this is hardware state a source or fuel model
     * owns. It is also what sizes the count rate a shutdown core reads: the
     * subcritical equilibrium is {@code n = Lambda*S/|rho|}, so the source
     * strength and the shutdown margin between them decide the number on the SRM.
     */
    public void setNeutronSourceStrengthPerSecond(double sourceStrengthPerSecond) {
        kinetics.setSourceStrengthPerSecond(sourceStrengthPerSecond);
    }

    /** @see #setNeutronSourceStrengthPerSecond(double) */
    public double getNeutronSourceStrengthPerSecond() {
        return kinetics.getSourceStrengthPerSecond();
    }

    /** Whether fuel exposure accumulates. Off is for tests and for a frozen core. */
    public void setBurnupEnabled(boolean burnupEnabled) {
        this.burnupEnabled = burnupEnabled;
    }

    public boolean isBurnupEnabled() {
        return burnupEnabled;
    }

    // ---------------------------------------------------------------
    // Measurements — power and neutronics
    // ---------------------------------------------------------------

    /** Fission power, fraction of rated. Excludes decay heat. */
    public double getNeutronPowerFraction() {
        return kinetics.getNeutronPowerFraction();
    }

    /** Decay heat, fraction of rated. */
    public double getDecayHeatFraction() {
        return decayHeat.getFractionOfRated();
    }

    /** Total thermal power, fraction of rated: fission plus decay heat. */
    public double getTotalPowerFractionOfRated() {
        return kinetics.getNeutronPowerFraction() + decayHeat.getFractionOfRated();
    }

    /** Total thermal power, MW. */
    public double getThermalPowerMW() {
        return getTotalPowerFractionOfRated() * config.ratedThermalMW;
    }

    /** Decay heat, MW. */
    public double getDecayHeatMW() {
        return decayHeat.getThermalMW(config.ratedThermalMW);
    }

    /** Net reactivity this tick, dk/k. */
    public double getReactivityDkOverK() {
        return reactivity.getTotalDkOverK();
    }

    /** Net reactivity in dollars: rho over beta. A unit conversion, not a judgement. */
    public double getReactivityDollars() {
        return kinetics.reactivityInDollars(reactivity.getTotalDkOverK());
    }

    /** Every reactivity component separately, dk/k. */
    public ReactivityBalance.Breakdown getReactivityBreakdown() {
        return reactivity.breakdown();
    }

    /** Flux-weighted effective delayed neutron fraction currently in use. */
    public double getBetaEffective() {
        return kinetics.getBetaTotalFraction();
    }

    /** Effective prompt neutron lifetime, seconds. */
    public double getPromptLifetimeSeconds() {
        return kinetics.getPromptLifetimeSeconds();
    }

    /** Delayed neutron source, fraction-of-rated per second. */
    public double getDelayedSourcePerSecond() {
        return kinetics.getDelayedSourcePerSecond();
    }

    /** Copy of the six precursor concentrations. */
    public double[] getPrecursorConcentrations() {
        return kinetics.getPrecursorConcentrations();
    }

    /**
     * The <b>true</b> reactor period of the physics, seconds.
     *
     * <p>This is not what a control room sees and must not be published to the
     * peripheral. Indicated period comes from a {@link PeriodMeter} watching a
     * detector's own signal, so a saturated or mis-ranged channel produces a
     * garbage period — which is a real startup hazard and is preserved on
     * purpose. For the model's own diagnostics and for tests.
     */
    public double getTruePeriodSeconds() {
        return kinetics.getPeriodSeconds();
    }

    /** Implicit solves the last kinetics step performed. Numerical diagnostic. */
    public long getLastKineticsSubStepCount() {
        return kinetics.getLastStepSubStepCount();
    }

    /** Implicit denominator the last kinetics step used. Numerical diagnostic. */
    public double getLastKineticsDenominator() {
        return kinetics.getLastStepDenominator();
    }

    // ---------------------------------------------------------------
    // Measurements — thermal hydraulics
    // ---------------------------------------------------------------

    /** Reactor steam dome pressure, psig. */
    public double getPressurePsig() {
        return vessel.getPressurePsig();
    }

    /** Reactor steam dome pressure, psia. */
    public double getPressurePsia() {
        return vessel.getPressurePsia();
    }

    /** Rate of change of dome pressure, psi per second. */
    public double getPressureRateOfChangePsiPerSecond() {
        return vessel.getPressureRateOfChangePsiPerSecond();
    }

    /** Bulk coolant temperature, degrees C — saturation at dome pressure. */
    public double getCoolantTemperatureC() {
        return vessel.getSaturationTemperatureC();
    }

    /** Volume-average fuel temperature, degrees C. Drives Doppler. */
    public double getFuelTemperatureC() {
        return fuelThermal.getFuelTemperatureC();
    }

    /** Average cladding temperature, degrees C. */
    public double getCladTemperatureC() {
        return fuelThermal.getCladTemperatureC();
    }

    /** Highest cladding temperature ever reached, degrees C. Monotonic. */
    public double getPeakCladTemperatureC() {
        return fuelThermal.getPeakCladTemperatureC();
    }

    /**
     * Highest fuel temperature ever reached, degrees C. Monotonic, and the only
     * input to {@code FuelThermal.getPeakFuelTemperatureFractionOfMelt()} — it is
     * the model's record of how close the fuel came to melting, so it is carried
     * in {@link ReactorState} rather than reconstructed.
     */
    public double getPeakFuelTemperatureC() {
        return fuelThermal.getPeakFuelTemperatureC();
    }

    /** Cumulative zirconium oxidation, 0 to 1 of the core inventory. Monotonic. */
    public double getOxidationFraction() {
        return fuelThermal.getOxidationFraction();
    }

    /** Hydrogen generated by zirconium oxidation, kg. Monotonic. */
    public double getHydrogenGeneratedKg() {
        return fuelThermal.getHydrogenGeneratedKg();
    }

    /** Heat the zirconium-water reaction is adding, MW. */
    public double getZirconiumReactionPowerMW() {
        return fuelThermal.getZirconiumReactionPowerMW();
    }

    /** Core-average void fraction, 0 to 1. */
    public double getVoidFraction() {
        return voidModel.getCoreAverageVoidFraction();
    }

    /** Void fraction at the core exit, 0 to 1. */
    public double getExitVoidFraction() {
        return voidModel.getExitVoidFraction();
    }

    /** Flow quality at the core exit, kg steam per kg of core flow. */
    public double getExitQuality() {
        return voidModel.getExitQuality();
    }

    /** Boiling boundary height, fraction of active fuel height. */
    public double getBoilingBoundaryFraction() {
        return voidModel.getBoilingBoundaryFraction();
    }

    /** Core inlet subcooling, kJ/kg. */
    public double getCoreInletSubcoolingKJPerKg() {
        return voidModel.getCoreInletSubcoolingKJPerKg();
    }

    /** Net steam generation, kg/s. Negative when cold injection is condensing steam. */
    public double getSteamGenerationKgPerS() {
        return vessel.getSteamGenerationKgPerS();
    }

    /** Water inventory in the vessel, kg. */
    public double getLiquidMassKg() {
        return vessel.getLiquidMassKg();
    }

    /**
     * What the vessel level instrument reads, inches on the instrument-zero
     * scale. This is the only level a control program should be given, and it is
     * wrong during pressure and drywell temperature transients in exactly the way
     * a real differential-pressure cell is wrong.
     */
    public double getIndicatedLevelIn() {
        return vessel.getIndicatedLevelIn();
    }

    /** The actual two-phase free surface, inches on the instrument-zero scale. */
    public double getTwoPhaseLevelIn() {
        return vessel.getTwoPhaseLevelIn();
    }

    /**
     * Collapsed water level, inches on the instrument-zero scale — every void
     * squeezed out. The honest number, and the one the fuel feels.
     */
    public double getCollapsedLevelIn() {
        return vessel.getCollapsedLevelIn();
    }

    /** Fraction of the active fuel height above the collapsed level, 0 to 1. */
    public double getUncoveredFuelFraction() {
        return vessel.getUncoveredFuelFraction();
    }

    // ---------------------------------------------------------------
    // Measurements — pressure boundary condition [SPEC 7]
    // ---------------------------------------------------------------

    /**
     * The overpressure damage model: accumulated stress per pressure boundary
     * component, what has broken, and the current failure hazard.
     *
     * <p>Published in full because it is the one thing in this plant that
     * happens without the player asking. Every number on it is a measurement —
     * how much life a pipe has used, how long the vessel has been over design
     * pressure, what the per-second failure rate currently is. None of them is
     * an opinion about whether that is alright, and nothing in the model reacts
     * to any of them. Writing the alarm is the player's job, in Lua, against
     * exactly these getters.
     */
    public BoundaryStress getBoundaryStress() {
        return boundary;
    }

    /** Steam that actually left through breaks in the pressure boundary last tick, kg/s. */
    public double getBreakSteamFlowKgPerS() {
        return lastBreakSteamFlowKgPerS;
    }

    /**
     * Water that actually left through breaks in the pressure boundary last
     * tick, kg/s. Falls to zero once the vessel is dry — the hole is still
     * there, there is simply nothing left to push through it.
     */
    public double getBreakLiquidFlowKgPerS() {
        return lastBreakLiquidFlowKgPerS;
    }

    /**
     * Feedwater actually reaching the vessel, kg/s. Differs from
     * {@link #getFeedwaterFlowKgPerS()} — which is what was commanded — once
     * the feedwater line has broken.
     */
    public double getDeliveredFeedwaterFlowKgPerS() {
        return feedwaterFlowKgPerS * boundary.feedwaterDeliveredFraction();
    }

    // ---------------------------------------------------------------
    // Measurements — poisons and fuel
    // ---------------------------------------------------------------

    /** Xe-135 inventory, atoms per cm3. */
    public double getXenonAtomsPerCm3() {
        return xenon.xenonAtomsPerCm3();
    }

    /** I-135 inventory, atoms per cm3. */
    public double getIodineAtomsPerCm3() {
        return xenon.iodineAtomsPerCm3();
    }

    /** Xenon inventory as a multiple of rated-power equilibrium. */
    public double getXenonFractionOfRatedEquilibrium() {
        return xenon.fractionOfRatedEquilibrium();
    }

    /** Core-average burnup, MWd per tonne of heavy metal. */
    public double getAverageBurnupMwdPerTonne() {
        return loading.averageBurnupMwdPerTonne();
    }

    /** Flux-weighted core k-infinity. */
    public double getAggregateKInfinity() {
        return loading.aggregateKInf();
    }

    /**
     * Multiplication factor of the loaded fuel with every rod withdrawn, cold
     * and clean: aggregate k-infinity times non-leakage.
     *
     * <p>Excludes rods, voids, Doppler, xenon and boron by construction — those
     * are reactivity terms the running plant adds on top. This is the narrower
     * question "is there anything left in the fuel", and it is the quantity
     * {@link #isEndOfCycle()} is defined against.
     */
    public double getKEffectiveAllRodsOut() {
        return loading.kEffAllRodsOut();
    }

    /**
     * End of cycle: the loaded fuel can no longer reach criticality with every
     * rod withdrawn (SPEC 2.3).
     *
     * <p>A measurement, not a judgement and not an instruction. It does not say
     * the plant should shut down, and it is not a trip: an operating core runs
     * out of usable margin well before this, because xenon, voids and fuel
     * temperature each subtract reactivity this figure ignores. Watching
     * {@link #getKEffectiveAllRodsOut()} approach 1 and planning an outage is the
     * player's job, in their own Lua.
     */
    public boolean isEndOfCycle() {
        return loading.isEndOfCycle();
    }

    /** The fuel actually loaded. The refuelling GUI and the burnup map read this. */
    public CoreLoading getCoreLoading() {
        return loading;
    }

    /** The tunables this core was built with. */
    public CoreConfig getConfig() {
        return config;
    }

    /** Simulated seconds since this core was created or last initialised. */
    public double getElapsedSeconds() {
        return elapsedSeconds;
    }

    /** Ticks stepped since this core was created or last initialised. */
    public long getTickCount() {
        return tickCount;
    }

    // ---------------------------------------------------------------
    // Measurements — nuclear instrumentation
    // ---------------------------------------------------------------

    /** Source range monitor channels, in panel order. */
    public List<SourceRangeMonitor> getSourceRangeMonitors() {
        return Collections.unmodifiableList(new ArrayList<>(Arrays.asList(sourceRangeMonitors)));
    }

    /** One source range monitor channel. */
    public SourceRangeMonitor getSourceRangeMonitor(int channel) {
        return sourceRangeMonitors[channel];
    }

    /** Indicated count rate on one source range monitor, counts per second. */
    public double getSourceRangeCountsPerSecond(int channel) {
        return sourceRangeMonitors[channel].getCountsPerSecond();
    }

    /** Intermediate range monitor channels, in panel order. */
    public List<IntermediateRangeMonitor> getIntermediateRangeMonitors() {
        return Collections.unmodifiableList(new ArrayList<>(Arrays.asList(intermediateRangeMonitors)));
    }

    /** One intermediate range monitor channel. */
    public IntermediateRangeMonitor getIntermediateRangeMonitor(int channel) {
        return intermediateRangeMonitors[channel];
    }

    /**
     * Move one IRM's range switch, 1 to 10.
     *
     * <p>Manual, always. Leaving an IRM on a low range while withdrawing rods is
     * a classic startup error and auto-ranging would delete the skill of avoiding
     * it along with the hazard. A control program is of course free to drive this
     * itself — that is the player writing their own auto-ranger, which is exactly
     * the intended division of labour.
     */
    public void setIntermediateRangeMonitorRange(int channel, int range) {
        intermediateRangeMonitors[channel].setRange(range);
    }

    /** Move every IRM's range switch at once. Still a manual actuator. */
    public void setAllIntermediateRangeMonitorRanges(int range) {
        for (IntermediateRangeMonitor irm : intermediateRangeMonitors) {
            irm.setRange(range);
        }
    }

    /**
     * Where every IRM's range switch is sitting, one detent number 1..10 per
     * channel in panel order.
     *
     * <p>Hardware state rather than a reading, which is why it is in
     * {@link ReactorState}: nothing in the plant puts a switch back where the
     * operator left it, so a reload that rebuilt the channels on their most
     * sensitive detent would be silently auto-ranging the whole rack.
     */
    public int[] getIntermediateRangeMonitorRanges() {
        int[] ranges = new int[intermediateRangeMonitors.length];
        for (int i = 0; i < ranges.length; i++) {
            ranges[i] = intermediateRangeMonitors[i].getRange();
        }
        return ranges;
    }

    /** Meter indication on one IRM, 0 to 125 divisions within its selected range. */
    public double getIntermediateRangeDivisions(int channel) {
        return intermediateRangeMonitors[channel].getScaleDivisions();
    }

    /** Average power range monitor channels, in panel order. */
    public List<AveragePowerRangeMonitor> getAveragePowerRangeMonitors() {
        return Collections.unmodifiableList(new ArrayList<>(Arrays.asList(averagePowerRangeMonitors)));
    }

    /** One average power range monitor channel. */
    public AveragePowerRangeMonitor getAveragePowerRangeMonitor(int channel) {
        return averagePowerRangeMonitors[channel];
    }

    /** Indicated power on one APRM, percent of rated. */
    public double getAveragePowerRangePercent(int channel) {
        return averagePowerRangeMonitors[channel].getPercentOfRated();
    }

    /** Period meter watching one source range monitor. */
    public PeriodMeter getSourceRangePeriodMeter(int channel) {
        return sourceRangePeriodMeters[channel];
    }

    /** Period meter watching one intermediate range monitor. */
    public PeriodMeter getIntermediateRangePeriodMeter(int channel) {
        return intermediateRangePeriodMeters[channel];
    }

    /** Indicated period from one source range channel, seconds. */
    public double getSourceRangePeriodSeconds(int channel) {
        return sourceRangePeriodMeters[channel].getPeriodSeconds();
    }

    /** Indicated period from one intermediate range channel, seconds. */
    public double getIntermediateRangePeriodSeconds(int channel) {
        return intermediateRangePeriodMeters[channel].getPeriodSeconds();
    }

    /** Indicated startup rate from one source range channel, decades per minute. */
    public double getSourceRangeStartupRateDecadesPerMinute(int channel) {
        return sourceRangePeriodMeters[channel].getStartupRateDecadesPerMinute();
    }

    // ---------------------------------------------------------------
    // Component access, for the mod layer and for tests
    // ---------------------------------------------------------------

    public PointKinetics getKinetics() {
        return kinetics;
    }

    public ReactivityBalance getReactivityBalance() {
        return reactivity;
    }

    public RodWorth getRodWorth() {
        return rodWorth;
    }

    public VoidModel getVoidModel() {
        return voidModel;
    }

    public PressureVessel getPressureVessel() {
        return vessel;
    }

    public FuelThermal getFuelThermal() {
        return fuelThermal;
    }

    public DecayHeat getDecayHeat() {
        return decayHeat;
    }

    public Xenon getXenon() {
        return xenon;
    }

    /**
     * The nodal diffusion solve. Exposed so the multiblock can hand it the real
     * rod-to-lattice mapping from structure validation, and so a tritium rod can
     * declare its absorption.
     */
    public NodalFluxSolver getNodalFluxSolver() {
        return nodalFlux;
    }

    /**
     * The flux map as of the last spatial solve — per-assembly flux and fission
     * share, the axial shape, the eigenvalue and the peaking factors.
     *
     * <p>Deliberately <i>not</i> re-solved on read. This is the frozen shape the
     * kinetics has been running on since the last refresh, and reporting anything
     * else would be reporting a number the model is not using.
     *
     * <p>The <i>map</i> is not in {@link ReactorState} — it is a large derived
     * object over fuel, rods and void, all of which are persisted, so a reload
     * re-solves it rather than storing it. The handful of <i>scalars</i> the
     * kinetics actually runs on between refreshes are a different matter and are
     * all components of the record: effective beta, prompt lifetime, the per-rod
     * flux weights, the fuel excess reactivity and the Doppler coefficient. Those
     * are frozen values a restore must resume on rather than recompute, and this
     * accessor is the reason the distinction is easy to miss — a re-solved map is
     * fine, a re-solved constant is a snapshot that cannot reproduce itself.
     */
    public FluxSolution getFluxSolution() {
        return nodalFlux.lastSolution();
    }

    // ---------------------------------------------------------------
    // Persistence, client sync and the peripheral snapshot
    // ---------------------------------------------------------------

    /**
     * Immutable snapshot of everything the model holds. One record serves NBT
     * persistence, client sync and the CC:Tweaked readout — {@code SPEC.md}
     * section 0. The core never touches NBT.
     *
     * <p>Two things are brought up to date before the snapshot is taken, so that
     * the record is <b>self-consistent</b> rather than a mixture of quantities
     * from different points in the tick. The xenon integrator is flushed, since
     * it runs on a coarse interval of its own. And the reactivity balance is
     * re-closed, because the rho left over from {@link #step} was deliberately
     * evaluated at the <i>start</i> of the tick — before the pressure and fuel
     * temperature updates that this snapshot reports — and a rho that cannot be
     * reproduced from the state alongside it is a rho that changes when the world
     * is saved and loaded. Neither call alters the trajectory: the next
     * {@link #step} re-closes the balance from scratch regardless.
     */
    public ReactorState toState() {
        xenon.flush();
        closeReactivityBalance();
        return new ReactorState(
                kinetics.getNeutronPowerFraction(),
                kinetics.getPrecursorConcentrations(),
                kinetics.getSourceStrengthPerSecond(),
                decayHeat.getFractionOfRated(),
                decayHeat.getGroupInventories(),
                fuelThermal.getFuelTemperatureC(),
                fuelThermal.getCladTemperatureC(),
                fuelThermal.getPeakCladTemperatureC(),
                fuelThermal.getPeakFuelTemperatureC(),
                fuelThermal.getOxidationFraction(),
                fuelThermal.getHydrogenGeneratedKg(),
                fuelThermal.getProtectiveArealMassKgPerM2(),
                vessel.getSaturationTemperatureC(),
                vessel.getPressurePsig(),
                voidModel.getCoreAverageVoidFraction(),
                // The void model's second lagged state, beside the void fraction
                // above. Recomputing it on restore could only put it at the
                // equilibrium for the restored operating point, which is exactly
                // where a core mid-transient is not.
                voidModel.getCoreInletEnthalpyKJPerKg(),
                coreFlowFraction,
                vessel.getCollapsedLevelIn(),
                xenon.xenonAtomsPerCm3(),
                xenon.iodineAtomsPerCm3(),
                boronPpm,
                loading.averageBurnupMwdPerTonne(),
                rodNotchIndex,
                accumulatorCharge,
                reactivity.getTotalDkOverK(),
                kinetics.getBetaTotalFraction(),
                kinetics.getPromptLifetimeSeconds(),
                // The third frozen output of the nodal solve, beside the effective
                // beta and prompt lifetime above. The rho recorded on the line
                // above was closed on exactly these weights, up to a second old;
                // re-solving for them on restore closes it on this second's
                // instead, and the record stops reproducing itself.
                rodWorth.getRodFluxWeights(),
                // The other two aggregates refreshFuelAggregates() freezes, and the
                // other two terms rho was closed on. Both are fission-rate-weighted
                // averages over the assemblies, so both are outputs of the same
                // solve as the three above and both go stale with it.
                reactivity.getFuelExcessReactivityDkOverK(),
                reactivity.getDopplerCoefficientPerCAtAnchor(),
                elapsedSeconds,
                scramActive,
                // Range switch positions, not readings. The detector signals are
                // deliberately absent from the record — a conditioning lag is a
                // filter, not plant state — but where the operator left the
                // switches is hardware, and nothing will put it back.
                getIntermediateRangeMonitorRanges());
    }

    /**
     * Restore a snapshot verbatim. A reactor mid-transient resumes mid-transient
     * — walking away suspends an accident, it does not cancel it
     * ({@code SPEC.md} section 11).
     *
     * <p>Two things are deliberately not restored from here.
     * <b>Fuel exposure</b> is item-side state: burnup and remaining gadolinia
     * live on the assembly items as data components ({@code SPEC.md} section 2.3)
     * so that shuffling works, and the block entity reloads them into the
     * {@link CoreLoading} before calling this. {@code burnupMwdPerTonne} in the
     * record is a readout of that, not the authority for it.
     * <b>Detector signals</b> are not restored because a signal-conditioning lag
     * is a filter, not plant state; the channels re-settle instantly here through
     * {@link #settleInstruments()}. Their <b>range switches</b> are a different
     * thing entirely and <i>are</i> restored — see below.
     *
     * <p>The <b>scram latch</b> is restored, and that is not a detail. It is what
     * selects the scram insertion path — accumulator charge or reactor-pressure
     * assist, ungated by CRD power and water — and it latches until
     * {@link #resetScram()}. Clearing it here used to make a save cancel a scram
     * in progress; a reactor mid-transient has to resume mid-transient.
     *
     * <p>The <b>peak fuel temperature</b> is restored from the record rather than
     * reconstructed. It cannot be reconstructed: heat flows fuel to clad, so peak
     * fuel is always above peak clad, and flooring it at the peak clad
     * temperature always understates it. A core that touched 2400 degC and cooled
     * came back reporting a peak near the saturation temperature, so every save
     * quietly un-melted the fuel and
     * {@code FuelThermal.getPeakFuelTemperatureFractionOfMelt()} — which is
     * computed from this and nothing else — forgot the accident had happened.
     *
     * <p>The <b>core inlet enthalpy</b> is restored rather than reconstructed,
     * and it is the second of the void model's two lagged states — the void
     * fraction is the other, and both now come back verbatim. A reconstruction
     * can only put a lag at its <em>equilibrium</em> for the restored operating
     * point, which is by definition where a core caught mid-transient is not:
     * the recirculation transit has not finished. Doing that gave a restored
     * core a boiling boundary of 0.66 where the original was running on 0.19,
     * and the axial void profile is what drives the nodal flux solve, so the
     * per-rod flux weights and with them {@code reactivityTotal} came back
     * differing from the record they were restored from — a snapshot that could
     * not reproduce itself. A record carrying zero predates this component and
     * falls back to the old equilibrium reconstruction, which is the behaviour
     * that save already had.
     *
     * <p>The <b>per-rod flux weights</b> are restored rather than re-solved, and
     * they are the third frozen output of the nodal solve to be put back verbatim
     * — {@code betaEff} and {@code promptLifetime} are the other two, and the
     * argument is identical for all three. That solve runs once every
     * {@link CoreConfig#nodalSolveIntervalTicks} ticks, 1 Hz by default, so
     * {@link #toState()} closes the reactivity balance it records on weights up to
     * a second old, while the re-solve below produces this second's. Those are not
     * the same numbers: on a core drifting after a scram all 177 of 177 rods
     * differed, by up to a part in 250, and
     * {@code RodWorth.totalInsertedWorthDkOverK} sums that difference over every
     * rod into a {@code reactivityTotal} an ulp or two away from the one recorded
     * beside it. A re-solve cannot fix that by being more accurate, because it is
     * not an approximation of the frozen weighting — it is a different, later one,
     * and a suspended core has to resume on the one it was suspended on. A record
     * that carries no weights predates the component and falls back to installing
     * the restore-time solve's own, which is the behaviour that save already had.
     *
     * <p>The <b>fuel excess reactivity</b> and the <b>Doppler coefficient</b> are
     * restored for the same reason, which completes the set: those two and the
     * three above are exactly the five things {@link #refreshNodalShape()} freezes,
     * and persisting three of five was the inconsistency. Both are
     * fission-rate-weighted averages over the assemblies, so both move with the
     * weighting, and {@code CoreLoading.weightedAverage} short-circuits a property
     * that is identical in every assembly — which hides the whole problem on a
     * fresh single-fuel core and only there. A core with a burnup gradient does not
     * qualify: at 1039 MWd/t the recomputed excess reactivity came back 5.6e-09
     * dk/k from the recorded one and carried all of it into
     * {@code reactivityTotal}; a mixed-fuel core was out by 5.0e-06 dk/k. The
     * Doppler coefficient is strictly negative for any fuel that exists, so a zero
     * is a record that predates the pair and both are recomputed from the loaded
     * fuel instead — the behaviour that save already had. The fuel itself stays
     * item-side authority throughout: these are readouts of it, restored so the
     * balance closes where it closed, not a second opinion about what is loaded.
     *
     * <p>The <b>IRM range switches</b> are restored for the same reason: ranging
     * is manual by design ({@code SPEC.md} section 9), so nothing in the plant
     * will put a switch back where the operator left it. The block entity builds
     * a fresh core on every chunk load, and a fresh core's channels sit on their
     * most sensitive detent — so not restoring them was <em>silent
     * auto-ranging</em>, and a mid-startup core came back with eight pegged
     * channels reading an infinite period. A record that carries no ranges at all
     * — a save that predates the component — leaves the switches wherever they
     * are rather than moving them.
     *
     * <p>One piece of hardware state the record still does not carry, and its
     * consequence, so nobody rediscovers it: <b>rod notch demand</b>. See the
     * comment at the restore itself — under a live scram it is reconstructed
     * exactly, and without one a withdrawal in flight collapses onto the notch
     * the rods had reached. The mod layer persists the operator's standing demand
     * separately, in {@code ReactorControllerBlockEntity}'s {@code RodDemand} tag,
     * and reinstalls it through {@code ControlRodDriveNetwork} after this returns;
     * it is deliberately not a component here, because demand is the control
     * room's state and not the physics'.
     *
     * @throws IllegalArgumentException if the rod or accumulator arrays do not
     *                                  match this core's rod count
     */
    public void fromState(ReactorState state) {
        if (state == null) {
            throw new IllegalArgumentException("state must not be null");
        }
        int[] notches = state.rodNotches();
        double[] charges = state.accumulatorCharge();
        if (notches.length != controlRodCount || charges.length != controlRodCount) {
            throw new IllegalArgumentException("state has " + notches.length + " rods and "
                    + charges.length + " accumulators, this core has " + controlRodCount
                    + "; reconcile the multiblock size before restoring");
        }

        kinetics.setSourceStrengthPerSecond(state.sourceStrength());
        kinetics.setPromptLifetimeSeconds(state.promptLifetime());
        if (!(Math.abs(state.betaEff() - kinetics.getBetaTotalFraction()) < 1.0e-15)) {
            kinetics.setDelayedNeutronData(delayedDataFor(state.betaEff()));
        }
        cachedAggregateBeta = kinetics.getBetaTotalFraction();
        kinetics.restoreState(state.neutronPower(), state.precursors());

        decayHeat.restoreGroupInventories(state.decayGroups());

        vessel.restorePressurePsig(state.pressurePsig());
        restoreCollapsedLevelExactly(state.waterLevelIn());

        // Core inlet enthalpy is the void model's other lagged state and it comes
        // back verbatim, exactly like the void fraction below. It used to be
        // reconstructed as the equilibrium for the restored operating point,
        // which is the one place a core mid-transient is guaranteed not to be:
        // the recirculation transit had not finished, so the restored core got a
        // different subcooling, a different boiling boundary and exit void, a
        // different nodal flux shape and therefore per-rod flux weights and a
        // reactivityTotal that no longer matched the record it came from.
        //
        // Zero means a record written before this component existed. There is
        // nothing in such a save to restore, so it falls back to the old
        // equilibrium reconstruction — which is not an invented value, it is
        // precisely the behaviour that save already had. Zero is also not a
        // reachable enthalpy for water in a hot vessel, so it cannot collide
        // with a genuine persisted value.
        double persistedInletEnthalpyKJPerKg = state.coreInletEnthalpyKJPerKg();
        voidModel.restoreCoreInletEnthalpyKJPerKg(
                persistedInletEnthalpyKJPerKg > 0.0
                        ? persistedInletEnthalpyKJPerKg
                        : Saturation.liquidEnthalpyKJPerKg(
                                Saturation.psiaFromPsig(state.pressurePsig()))
                                - voidModel.equilibriumSubcooling(
                                        state.neutronPower() + state.decayHeatFraction(),
                                        state.coreFlowFraction(), state.pressurePsig()));
        // The two restore calls set the two LAGGED states and nothing else. The
        // axial geometry that hangs off them — boiling boundary, exit quality,
        // exit void, enthalpy rise — is algebraic and derived, and until it is
        // recomputed it is still whatever the constructor's hot-shutdown
        // initialisation left: boiling boundary 1.0 and exit void 0.0, which is
        // "no boiling anywhere". A zero-length update recomputes exactly that
        // geometry without advancing either lag, so the restore-time nodal solve
        // a few lines down sees the axial profile the core was actually running
        // on rather than a flooded one, and a Lua program polling immediately
        // after a reload does not read exit quality 0.0 on a core making
        // 1941 kg/s of steam.
        //
        // update() with dt = 0 snaps the core-average void onto the quasi-static
        // answer, so the exact persisted value has to be put back afterwards —
        // it is a lagged state and must survive the trip bit for bit.
        voidModel.update(state.neutronPower() + state.decayHeatFraction(),
                state.coreFlowFraction(), state.pressurePsig(), 0.0);
        voidModel.restoreCoreAverageVoidFraction(state.voidFraction());

        fuelThermal.restoreTemperatures(state.fuelTempC(), state.cladTempC());
        restoreOxidationExactly(state.peakCladTempC(), state.oxidationFraction(),
                state.hydrogenKg());
        // Must follow restoreOxidationExactly: restoreDamageState recomputes the
        // protective film from the reacted mass, which is right for a core that
        // has only ever grown oxide and wrong for one caught mid quench spike.
        fuelThermal.restoreProtectiveArealMassKgPerM2(state.protectiveOxideArealKgPerM2());
        // Also after restoreOxidationExactly, and for a related reason: every
        // restoreDamageState call in that search floors peak fuel at peak clad,
        // so a peak fuel temperature installed first would be raised and left
        // there. Installed afterwards it is an assignment, floored only at the
        // peak clad temperature that is now final — which is a genuine physical
        // floor, heat flowing fuel to clad. A record from before this component
        // existed carries 0.0 and therefore restores to exactly the old
        // reconstruction, no worse than it was.
        fuelThermal.restorePeakFuelTemperatureC(state.peakFuelTempC());

        xenon.setInventoriesAtomsPerCm3(state.iodine(), state.xenon());
        boronPpm = state.boronPpm();

        coreFlowFraction = state.coreFlowFraction();
        recirculationFlowFractionDemand = state.coreFlowFraction();

        System.arraycopy(notches, 0, rodNotchIndex, 0, controlRodCount);
        System.arraycopy(charges, 0, accumulatorCharge, 0, controlRodCount);
        Arrays.fill(rodDriveTimerSeconds, 0.0);

        // The scram latch is plant state and comes back latched. It used to be
        // cleared unconditionally here, which meant saving cancelled a scram: the
        // drives resumed unlatched at whatever notch they had reached, and with
        // the CRD bus de-energised — the ATWS case, where the scram path is the
        // only thing that could still move a rod — they stopped there for good
        // while the half-spent accumulators recharged over the evidence.
        //
        // Rod DEMAND has no component of its own in the record, so it is
        // reconstructed. Under a live scram that reconstruction is exact rather
        // than approximate: scram() drives every demand to fully inserted, so
        // that is where a scrammed core's demands were. Without a scram there is
        // nothing here to reconstruct it from and the achieved position is the
        // only honest answer — a withdrawal in flight when the chunk unloaded
        // would collapse onto the notch the rods had reached. It does not, but
        // that is not this method's doing: demand is the control room's state,
        // not the physics', so the mod layer persists it in its own RodDemand tag
        // and ControlRodDriveNetwork reinstalls it after this returns. Do not add
        // a component here for it — there would then be two authorities for the
        // same pattern, and the stale one would drive the rods.
        scramActive = state.scramActive();
        if (scramActive) {
            Arrays.fill(rodNotchDemand, RodWorth.NOTCH_INDEX_FULLY_INSERTED);
        } else {
            System.arraycopy(notches, 0, rodNotchDemand, 0, controlRodCount);
        }

        elapsedSeconds = state.elapsedSeconds();
        tickCount = 0L;
        secondsSinceAggregateRefresh = 0.0;

        // The spatial flux map is derived, not persisted, so it is re-solved here
        // from the rods and void that were. Only the map: the scalars the solve
        // produced come back from the record verbatim above, because a restored
        // core must resume on the beta it was suspended on rather than on one
        // recomputed from a slightly different weighting.
        nodalFlux.setRodNotchIndices(rodNotchIndex);
        nodalFlux.setVoidProfile(voidModel.getBoilingBoundaryFraction(),
                voidModel.getExitVoidFraction());
        loading.invalidateWeights();
        // Same reasoning as refreshNodalShape(): force the solve here rather than
        // leaving it to whichever aggregate read happens to want weights. Without
        // it a restored core has no flux map at all until the first aggregate
        // refresh a second later.
        loading.powerWeights();
        // The per-rod flux weights are the third frozen output of that solve, and
        // they come back from the record for the same reason the beta does. The
        // solve runs at 1 Hz, so toState() closed the recorded reactivity balance
        // on weights up to a second old while the re-solve just above has this
        // second's: on a core drifting after a scram all 177 of 177 differed, by
        // up to a part in 250, and totalInsertedWorthDkOverK sums that over every
        // rod into a reactivityTotal an ulp or two off the recorded one. Verbatim,
        // through restoreRodFluxWeights rather than setRodFluxWeights, because the
        // latter renormalises and that rescale is not the identity.
        //
        // A record carrying no weights predates the component. There is nothing in
        // it to restore, so the re-solve's own answer is installed instead —
        // precisely the behaviour that save already had, never an invented one.
        if (!restorePersistedRodFluxWeights(state.rodFluxWeights())) {
            installRodFluxWeights();
        }

        // The last two frozen aggregates, restored verbatim for the same reason as
        // the three above rather than recomputed from the fuel. Both are
        // fission-rate-weighted averages, so both depend on the flux weighting the
        // solve produced, and the restore-time solve produces a later one.
        //
        // CoreLoading.weightedAverage short-circuits a property with the same value
        // in every assembly, which hides this completely on a fresh single-fuel
        // core — and only there. A core that has run long enough to grow a burnup
        // gradient no longer qualifies: at 1039 MWd/t the recomputed excess
        // reactivity landed 5.6e-09 dk/k from the recorded one and put all of it
        // into reactivityTotal; a mixed-fuel core was out by 5.0e-06 dk/k.
        //
        // The Doppler coefficient is strictly negative — FuelType refuses a fuel
        // whose coefficient is not — so zero is unambiguously "no such key", and it
        // is the discriminator for the pair, which is written and read together.
        // Such a record predates the components and both are recomputed from the
        // loaded fuel, which is exactly what that save already got. NaN takes the
        // same branch, since it is not less than zero.
        //
        // The record stays a READOUT of the fuel and never the authority for it.
        // The assemblies are item-side state and the block entity reloads them
        // before this runs, so if the fuel in the core is somehow not the fuel the
        // record was written against, these two values are wrong for at most one
        // nodal interval: stepAggregateRefresh re-derives both from the fuel
        // actually loaded a second later, exactly as it does for the beta.
        if (state.dopplerCoefficientPerCAtAnchor() < 0.0) {
            reactivity.setFuelExcessReactivityDkOverK(state.fuelExcessReactivityDkOverK());
            reactivity.setDopplerCoefficientPerCAtAnchor(state.dopplerCoefficientPerCAtAnchor());
        } else {
            reactivity.setFuelExcessReactivityDkOverK(loading.excessReactivityAllRodsOutDkK());
            reactivity.setDopplerCoefficientPerCAtAnchor(loading.effectiveDopplerCoeffPerC());
        }
        closeReactivityBalance();

        // Before settleInstruments(), because the range switch decides what the
        // channel is settling onto: an IRM's indication is the flux divided by
        // the full scale of the selected detent, so settling first and ranging
        // afterwards would leave every channel showing the swing of a range
        // change nobody made.
        restoreIntermediateRangeMonitorRanges(state.intermediateRangeMonitorRanges());
        settleInstruments();
    }

    /**
     * Put the IRM range switches back where the operator left them.
     *
     * <p>Not an auto-ranger and not a decision: it copies persisted switch
     * positions back onto the switches, which is the opposite of ranging on the
     * plant's behalf. Ranging is manual by design and the absence of this call is
     * what made a reload move every switch to detent 1.
     *
     * <p>A shorter array than there are channels — a save from a smaller
     * instrument complement, or one that predates the component and carries none
     * — leaves the remaining switches wherever they are, which is the only
     * honest answer for a channel the record says nothing about. Detents outside
     * the switch's travel clamp, in {@link IntermediateRangeMonitor#setRange},
     * because a physical switch has nowhere else to go.
     */
    private void restoreIntermediateRangeMonitorRanges(int[] ranges) {
        int channels = Math.min(ranges.length, intermediateRangeMonitors.length);
        for (int i = 0; i < channels; i++) {
            intermediateRangeMonitors[i].setRange(ranges[i]);
        }
    }

    /**
     * Set the water inventory so the collapsed level reads exactly the persisted
     * figure.
     *
     * <p>Level and inventory are related by three multiplications, so a plain
     * round trip through them can land an ulp away from where it started. That is
     * invisible once and a slow drift after a hundred chunk reloads, so the
     * inversion is closed by iteration instead of trusted. Same reasoning as
     * {@link #restoreOxidationExactly}.
     */
    private void restoreCollapsedLevelExactly(double targetLevelIn) {
        vessel.setCollapsedLevelIn(targetLevelIn);
        double massPerInch = PressureVessel.METRES_PER_INCH * vessel.getLevelAreaM2()
                * Saturation.liquidDensityKgPerM3(vessel.getPressurePsia());
        if (!(massPerInch > 0.0)) {
            return;
        }
        double bestMass = vessel.getLiquidMassKg();
        double bestError = Math.abs(targetLevelIn - vessel.getCollapsedLevelIn());
        for (int i = 0; i < 8 && bestError != 0.0; i++) {
            double corrected =
                    vessel.getLiquidMassKg() + (targetLevelIn - vessel.getCollapsedLevelIn()) * massPerInch;
            if (!(corrected >= 0.0) || corrected == vessel.getLiquidMassKg()) {
                break;
            }
            vessel.restoreLiquidMassKg(corrected);
            double error = Math.abs(targetLevelIn - vessel.getCollapsedLevelIn());
            if (error < bestError) {
                bestError = error;
                bestMass = vessel.getLiquidMassKg();
            }
        }

        // The correction above is a Newton step against an approximate
        // derivative, and it lands within a few representable masses of the
        // answer rather than on it. Close the last few by walking the inventory
        // one double at a time: the persisted level came from some exact mass, so
        // that mass exists and reproduces the level bit for bit, and level is
        // monotonic in inventory so the walk knows which way to go and stops the
        // moment it stops improving. Without this the restored level lands a
        // handful of ulps out for some targets and exactly right for others,
        // which is the worst kind of nearly-correct: it survives most tests and
        // drifts across a hundred chunk reloads.
        vessel.restoreLiquidMassKg(bestMass);
        for (int i = 0; i < 64 && bestError != 0.0; i++) {
            double towards = (targetLevelIn > vessel.getCollapsedLevelIn()) ? Double.MAX_VALUE : 0.0;
            double stepped = Math.nextAfter(vessel.getLiquidMassKg(), towards);
            if (stepped == vessel.getLiquidMassKg() || !(stepped >= 0.0)) {
                break;
            }
            vessel.restoreLiquidMassKg(stepped);
            double error = Math.abs(targetLevelIn - vessel.getCollapsedLevelIn());
            if (!(error < bestError)) {
                break;
            }
            bestError = error;
            bestMass = stepped;
        }

        // The inversion is ambiguous by an inventory ulp or two, and it has to be
        // resolved deterministically or it is not an inversion.
        //
        // The level scale reads from an instrument zero 352 inches below the
        // water, so the reported figure is a ten-inch number computed by
        // subtracting a 352-inch offset from a 362-inch height. The bottom of the
        // mantissa goes with it: several consecutive representable inventories
        // produce the identical level, and the search above stops at whichever
        // one it happened to reach first. Walking down to the smallest inventory
        // that still reproduces the level exactly makes the choice a property of
        // the recorded number rather than of the search path — and in practice it
        // lands on the inventory the level came from, so a saved and reloaded
        // plant carries on with the same water in it rather than a few
        // femtogrammes more.
        for (int i = 0; i < 64 && bestError == 0.0; i++) {
            double lower = Math.nextAfter(bestMass, 0.0);
            if (!(lower > 0.0)) {
                break;
            }
            vessel.restoreLiquidMassKg(lower);
            if (targetLevelIn != vessel.getCollapsedLevelIn()) {
                break;
            }
            bestMass = lower;
        }
        vessel.restoreLiquidMassKg(bestMass);
    }

    /**
     * Restore the oxidation state so the reported fraction reads exactly the
     * persisted figure. {@code FuelThermal} stores reacted zirconium per unit
     * area and reports a fraction of the inventory, and that multiply-then-divide
     * is not exactly the identity in binary floating point. Damage state must
     * survive a reload unchanged, so the inversion is iterated.
     */
    private void restoreOxidationExactly(double peakCladTemperatureC,
                                         double targetFraction,
                                         double hydrogenKg) {
        double request = Math.max(0.0, Math.min(1.0, targetFraction));
        fuelThermal.restoreDamageState(peakCladTemperatureC, request, hydrogenKg);
        double bestRequest = request;
        double bestError = Math.abs(targetFraction - fuelThermal.getOxidationFraction());
        for (int i = 0; i < 8 && bestError != 0.0; i++) {
            double next = request + (targetFraction - fuelThermal.getOxidationFraction());
            if (next == request || !(next >= 0.0) || next > 1.0) {
                break;
            }
            request = next;
            fuelThermal.restoreDamageState(peakCladTemperatureC, request, hydrogenKg);
            double error = Math.abs(targetFraction - fuelThermal.getOxidationFraction());
            if (error < bestError) {
                bestError = error;
                bestRequest = request;
            }
        }
        fuelThermal.restoreDamageState(peakCladTemperatureC, bestRequest, hydrogenKg);
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    private void checkRodIndex(int rodIndex) {
        if (rodIndex < 0 || rodIndex >= controlRodCount) {
            throw new IllegalArgumentException(
                    "rod index out of range 0.." + (controlRodCount - 1) + ": " + rodIndex);
        }
    }

    private static double nonNegative(double value) {
        return (Double.isFinite(value) && value > 0.0) ? value : 0.0;
    }

    @Override
    public String toString() {
        return String.format(
                "ReactorCore[t=%.1fs n=%.4e (%.2f%% rated total) rho=%+.5f dk/k (%.2f$) "
                        + "P=%.1f psig / %.1f degC void=%.3f flow=%.2f level=%.1f in rods=%d]",
                elapsedSeconds, getNeutronPowerFraction(), 100.0 * getTotalPowerFractionOfRated(),
                getReactivityDkOverK(), getReactivityDollars(), getPressurePsig(),
                getCoolantTemperatureC(), getVoidFraction(), getCoreFlowFraction(),
                getIndicatedLevelIn(), rodNotchIndex[0]);
    }
}
