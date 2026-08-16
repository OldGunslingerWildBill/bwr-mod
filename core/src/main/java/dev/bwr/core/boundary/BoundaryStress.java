package dev.bwr.core.boundary;

import dev.bwr.core.PhysicalConstants;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Overpressure damage to the reactor pressure boundary — {@code SPEC.md}
 * section 7, and the only thing in this entire plant that happens to a player
 * without a player asking for it.
 *
 * <h2>Why this class exists at all</h2>
 * Every other consequence in this mod is either commanded by the player or
 * mediated by their control program. The safety and relief valves are
 * player-actuated ({@code ReactorCore#setReliefSteamFlowKgPerS}), so nothing
 * relieves pressure on its own; there is no scram on high pressure, because
 * there is no scram on anything. Without this class a plant can be isolated at
 * rated power and left, and it will sit at three times its design pressure
 * indefinitely with no consequence whatsoever. That silently contradicts the
 * project's premise that failures should be traceable to neglect: neglect
 * needs somewhere to land.
 *
 * <p>This is damage, not protection. Nothing here warns, trips, prevents,
 * relieves or actuates. It accumulates wear and eventually breaks something.
 * Accumulated stress per component is published as a measurement precisely so
 * a player can write their own alarms against it in Lua — and equally, so they
 * can choose not to.
 *
 * <h2>Stress accumulation, not a threshold</h2>
 * Metal does not fail the instant it is overloaded and survive indefinitely
 * one psi below. It uses up life at a rate that climbs steeply with load. So:
 *
 * <table border="1">
 *   <caption>Behaviour by dome pressure</caption>
 *   <tr><th>Dome pressure</th><th>Behaviour</th></tr>
 *   <tr><td>below {@value #DESIGN_PRESSURE_PSIG} psig</td>
 *       <td>Nothing. Vessel design pressure [TTC 2.2]; the plant is inside its
 *           envelope and no stress accumulates at all.</td></tr>
 *   <tr><td>{@value #DESIGN_PRESSURE_PSIG} to {@value #CODE_PRESSURE_PSIG}</td>
 *       <td>Stress accumulates, at a rate scaling as the square of how far
 *           over design pressure the plant is. Nothing can break in this band.
 *           This is the warning band, and the damage done in it is
 *           <b>permanent</b>.</td></tr>
 *   <tr><td>above {@value #CODE_PRESSURE_PSIG}</td>
 *       <td>ASME 110% of design, the code transient limit [TTC 2.5]. Stress
 *           keeps accumulating and each component now carries a per-second
 *           failure hazard proportional to the stress it has already
 *           accumulated.</td></tr>
 * </table>
 *
 * <p>Stress never decreases. There is no annealing, no cooling-off, no
 * forgiveness for going back inside the envelope. A plant that spends an hour
 * at 1300 psig cannot break there — but it has spent most of a steam line's
 * life, and the <i>next</i> excursion past the code limit will kill it in
 * seconds. That is what makes a failure traceable to neglect rather than to a
 * dice roll: the dice are only rolled at all once the metal is used up, and
 * how used up it is was entirely the player's doing.
 *
 * <h2>Non-deterministic, but not arbitrary</h2>
 * Above the code limit every exposed component has an independent hazard rate
 *
 * <pre>
 *   h_c = HAZARD_PER_SECOND * y * max(0, stress_c - FAILURE_STRESS)
 *   y   = (pressure - CODE_PRESSURE) / (CODE_PRESSURE - DESIGN_PRESSURE)
 * </pre>
 *
 * and the probability that anything fails in a step is
 * {@code 1 - exp(-sum(h_c) * dt)}. When something does fail, which component
 * it was is drawn in proportion to those same hazards. Since {@code h_c} is
 * linear in accumulated stress, and accumulated stress is proportional to the
 * component's exposure weight, <b>the component that has been abused hardest
 * is the one that goes</b>, without any of it being predetermined.
 *
 * <p>The {@code max(0, stress - FAILURE_STRESS)} gate is what keeps the vessel
 * head honest. The head accumulates stress at a quarter of a steam line's rate
 * because it is a forging several inches thick, so it needs four times as long
 * to reach the gate at all. By then a steam line or a recirculation line has
 * almost certainly already gone and depressurised the vessel. Losing the head
 * therefore requires sustained gross overpressure with everything else somehow
 * intact, which is exactly what {@code SPEC.md} section 7 asks for.
 *
 * <h2>Calibration, and why it is deliberately slow</h2>
 * {@link #SECONDS_TO_UNIT_STRESS_AT_CODE_PRESSURE} is the single knob. At
 * exactly the code limit a main steam line uses up its life in twenty minutes.
 * Everything else follows from the square law:
 *
 * <table border="1">
 *   <caption>Time for a main steam line to reach the failure gate</caption>
 *   <tr><th>Sustained pressure</th><th>Time to the gate</th></tr>
 *   <tr><td>1300 psig</td><td>about 2 hours (and it can never break there)</td></tr>
 *   <tr><td>1325 psig</td><td>about 56 minutes (and it can never break there)</td></tr>
 *   <tr><td>1375 psig</td><td>20 minutes</td></tr>
 *   <tr><td>1500 psig</td><td>5 minutes</td></tr>
 *   <tr><td>2000 psig</td><td>33 seconds</td></tr>
 *   <tr><td>3315 psig</td><td>4.4 seconds</td></tr>
 * </table>
 *
 * <p>The point of those numbers is that a plant drifting a little over design
 * pressure is a problem measured in hours, which is long enough for an absent
 * player to come back and find it, and a plant in a runaway pressurisation
 * transient is a problem measured in seconds, which is correct because it is
 * already at more than twice the pressure the vessel was built for. Nothing is
 * deleted instantly at any pressure: the fastest possible path from crossing
 * design pressure to a break is several seconds, and the break that results is
 * survivable — only the head is terminal, and the head is the slowest thing
 * here by a factor of four.
 *
 * <p>Pure Java, doubles only, zero Minecraft imports.
 */
public final class BoundaryStress {

    // -----------------------------------------------------------------
    // The pressure envelope. Hardware, from REFERENCE-DATA section 1.
    // -----------------------------------------------------------------

    /** Vessel design pressure, psig. Below this nothing at all happens [TTC 2.2]. */
    public static final double DESIGN_PRESSURE_PSIG = PhysicalConstants.VESSEL_DESIGN_PRESSURE_PSIG;

    /** ASME 110% of design, the code transient limit, psig [TTC 2.5]. */
    public static final double CODE_PRESSURE_PSIG = PhysicalConstants.VESSEL_CODE_LIMIT_PSIG;

    /**
     * Pressure the code case forbids exceeding with irradiated fuel in the
     * vessel, psig [TTC 2.2]. Exposure above it is recorded — see
     * {@link #getIrradiatedFuelOverpressureSeconds()} — and published as a
     * measurement. It is deliberately <b>not</b> a behaviour: nothing in this
     * model stops the plant reaching it, because deciding what to do about it
     * is the player's.
     */
    public static final double FUEL_PRESENT_PRESSURE_PSIG =
            PhysicalConstants.VESSEL_FUEL_PRESENT_LIMIT_PSIG;

    /** Span between design pressure and the code limit, psi. The natural scale. */
    public static final double CODE_MARGIN_PSI = CODE_PRESSURE_PSIG - DESIGN_PRESSURE_PSIG;

    // -----------------------------------------------------------------
    // Calibration
    // -----------------------------------------------------------------

    /**
     * How sharply the accumulation rate climbs with overpressure. Two, so the
     * rate scales with the square of the fractional overpressure — a plant a
     * quarter of the way from design pressure to the code limit wears out
     * sixteen times slower than one sitting on the limit.
     */
    public static final double STRESS_EXPONENT = 2.0;

    /**
     * Seconds a main steam line takes to reach {@link #FAILURE_STRESS} while
     * held at exactly the code limit. The one balance knob in this class; every
     * other number in the calibration table follows from it and
     * {@link #STRESS_EXPONENT}.
     */
    public static final double SECONDS_TO_UNIT_STRESS_AT_CODE_PRESSURE = 1200.0;

    /**
     * Accumulated stress at which a component can begin to fail. One unit is
     * one exhausted life, so this is 1.0 by definition and is named rather than
     * inlined only so that the readouts have something to be relative to.
     */
    public static final double FAILURE_STRESS = 1.0;

    /**
     * Failure hazard per second per unit of excess stress per unit of excess
     * pressure. Chosen so that a plant sitting just over the code limit with a
     * fully used-up steam line survives tens of minutes, while one at two and a
     * half times design pressure survives seconds.
     */
    public static final double HAZARD_PER_SECOND = 0.02;

    /**
     * Fixed default seed. The physics is otherwise bit-deterministic and two
     * identically driven cores must stay identical, so the draw sequence is
     * seeded rather than left to the clock. The mod re-seeds per reactor from
     * its block position.
     */
    public static final long DEFAULT_RANDOM_SEED = 0x42_57_52_31L; // 'BWR1'

    // -----------------------------------------------------------------
    // Break flows. What a hole in each component actually passes.
    // -----------------------------------------------------------------

    private static final double KG_PER_LB = 0.45359237;

    /** Rated main steam flow, kg/s, from the plant data. */
    public static final double RATED_STEAM_FLOW_KG_PER_S =
            PhysicalConstants.RATED_STEAM_FLOW_LB_PER_HR * KG_PER_LB / 3600.0;

    /** Rated core flow, kg/s, from the plant data. */
    public static final double RATED_CORE_FLOW_KG_PER_S =
            PhysicalConstants.RATED_CORE_FLOW_LB_PER_HR * KG_PER_LB / 3600.0;

    /** Rated dome pressure in psia, the reference the break flows are quoted at. */
    private static final double RATED_PSIA =
            PhysicalConstants.RATED_DOME_PRESSURE_PSIG + PhysicalConstants.ATMOSPHERIC_PSI;

    /**
     * Steam discharged by a broken main steam line at rated pressure, as a
     * multiple of total rated steam flow. One, and that figure is derived
     * rather than picked: each of the four main steam lines carries a quarter
     * of rated flow and has a venturi flow restrictor at its vessel nozzle
     * sized at about 200% of that line's rating, so the vessel side of a
     * guillotine passes roughly half of rated total flow and the header side
     * blows down the rest.
     *
     * <p>That is deliberately not enormous. It is a little more than the core
     * boils at rated power, so the vessel depressurises over a minute or two
     * rather than in seconds — and if the player never turns the reactor down,
     * the plant will happily keep boiling into the drywell at a few hundred psi
     * instead of dying. Choked flow, so the discharge scales linearly with
     * absolute pressure and tails off as the vessel empties.
     */
    public static final double STEAM_LINE_BREAK_FLOW_MULTIPLE = 1.0;

    /**
     * Water discharged by a broken recirculation line at rated pressure, as a
     * fraction of rated core flow. Subcooled liquid leaving below the water
     * line, so it scales with the square root of absolute pressure. At about
     * 2600 kg/s this empties the vessel in something over a minute if nothing
     * injects, which is what "the design basis LOCA that sizes ECCS" means.
     */
    public static final double RECIRCULATION_BREAK_FLOW_FRACTION = 0.20;

    /** A failed vessel head passes this multiple of rated steam flow at rated pressure. */
    public static final double HEAD_FAILURE_STEAM_MULTIPLE = 4.0;

    /** A failed vessel head also passes this fraction of rated core flow as liquid. */
    public static final double HEAD_FAILURE_LIQUID_FRACTION = 0.30;

    // -----------------------------------------------------------------
    // State
    // -----------------------------------------------------------------

    private static final BoundaryComponent[] COMPONENTS = BoundaryComponent.values();

    private final double[] stress = new double[COMPONENTS.length];
    private final boolean[] broken = new boolean[COMPONENTS.length];
    private final List<BoundaryFailure> failures = new ArrayList<>();

    private PlantConfiguration configuration = PlantConfiguration.EXTERNAL_RECIRCULATION_LOOPS;
    private long randomSeed = DEFAULT_RANDOM_SEED;
    private Random random = new Random(DEFAULT_RANDOM_SEED);

    private double clockSeconds;
    private double secondsAboveDesignPressure;
    private double secondsAboveCodePressure;
    private double irradiatedFuelOverpressureSeconds;
    private double peakPressurePsig;

    private boolean irradiatedFuelPresent;
    private double lastFailureRatePerSecond;

    /** A pristine boundary on a jet pump plant. */
    public BoundaryStress() {
    }

    /** A pristine boundary on a plant of the given configuration. */
    public BoundaryStress(PlantConfiguration configuration) {
        setPlantConfiguration(configuration);
    }

    // -----------------------------------------------------------------
    // The step
    // -----------------------------------------------------------------

    /**
     * Accumulate wear for one timestep at the given dome pressure, and roll for
     * a failure if the plant is over the code limit.
     *
     * <p>At most one component fails per step. That is not a fairness rule: a
     * break relieves the pressure that caused it, so two simultaneous failures
     * would be double-counting a condition that no longer exists by the time
     * the second one is evaluated.
     *
     * @param pressurePsig dome pressure, psig
     * @param dtSeconds    timestep, seconds; non-positive is a no-op
     * @return the failure that occurred this step, or null — which is the
     *         normal answer
     */
    public BoundaryFailure step(double pressurePsig, double dtSeconds) {
        if (!(dtSeconds > 0.0) || !Double.isFinite(dtSeconds) || !Double.isFinite(pressurePsig)) {
            return null;
        }
        clockSeconds += dtSeconds;
        if (pressurePsig > peakPressurePsig) {
            peakPressurePsig = pressurePsig;
        }

        if (irradiatedFuelPresent && pressurePsig > FUEL_PRESENT_PRESSURE_PSIG) {
            irradiatedFuelOverpressureSeconds += dtSeconds;
        }

        if (pressurePsig <= DESIGN_PRESSURE_PSIG) {
            // Inside the envelope. Nothing wears, nothing heals.
            lastFailureRatePerSecond = 0.0;
            return null;
        }
        secondsAboveDesignPressure += dtSeconds;

        double perUnitWeight = stressRatePerUnitWeight(pressurePsig) * dtSeconds;
        for (BoundaryComponent component : COMPONENTS) {
            int i = component.ordinal();
            if (broken[i] || !configuration.exposes(component)) {
                continue;
            }
            stress[i] += component.exposureWeight() * perUnitWeight;
        }

        if (pressurePsig <= CODE_PRESSURE_PSIG) {
            // Damage, but nothing can let go yet.
            lastFailureRatePerSecond = 0.0;
            return null;
        }
        secondsAboveCodePressure += dtSeconds;
        return rollForFailure(pressurePsig, dtSeconds);
    }

    /**
     * Stress accumulated per second by a component of unit exposure weight at
     * the given pressure. Zero at or below design pressure; one over
     * {@link #SECONDS_TO_UNIT_STRESS_AT_CODE_PRESSURE} at the code limit.
     * Published because it is the number that makes the model legible.
     */
    public static double stressRatePerUnitWeight(double pressurePsig) {
        if (!(pressurePsig > DESIGN_PRESSURE_PSIG)) {
            return 0.0;
        }
        double overpressure = (pressurePsig - DESIGN_PRESSURE_PSIG) / CODE_MARGIN_PSI;
        return Math.pow(overpressure, STRESS_EXPONENT) / SECONDS_TO_UNIT_STRESS_AT_CODE_PRESSURE;
    }

    /**
     * How far past the code limit the plant is, in units of the design-to-code
     * span. Zero at the code limit, one at 1500 psig. This is the multiplier on
     * the failure hazard.
     */
    public static double codeExceedance(double pressurePsig) {
        return Math.max(0.0, (pressurePsig - CODE_PRESSURE_PSIG) / CODE_MARGIN_PSI);
    }

    private BoundaryFailure rollForFailure(double pressurePsig, double dtSeconds) {
        double exceedance = codeExceedance(pressurePsig);
        double[] hazard = new double[COMPONENTS.length];
        double total = 0.0;
        for (BoundaryComponent component : COMPONENTS) {
            int i = component.ordinal();
            if (broken[i] || !configuration.exposes(component)) {
                continue;
            }
            double excess = stress[i] - FAILURE_STRESS;
            if (excess <= 0.0) {
                continue;
            }
            hazard[i] = HAZARD_PER_SECOND * exceedance * excess;
            total += hazard[i];
        }
        lastFailureRatePerSecond = total;
        if (!(total > 0.0)) {
            return null;
        }

        double probability = -Math.expm1(-total * dtSeconds);
        if (random.nextDouble() >= probability) {
            return null;
        }

        // Which one. Drawn in proportion to hazard, and hazard is linear in
        // accumulated stress, so the abused component is the one that goes.
        double pick = random.nextDouble() * total;
        double running = 0.0;
        for (BoundaryComponent component : COMPONENTS) {
            int i = component.ordinal();
            running += hazard[i];
            if (hazard[i] > 0.0 && pick < running) {
                return recordFailure(component, pressurePsig);
            }
        }
        return null; // unreachable barring rounding; treat as no failure
    }

    private BoundaryFailure recordFailure(BoundaryComponent component, double pressurePsig) {
        int i = component.ordinal();
        broken[i] = true;
        BoundaryFailure failure =
                new BoundaryFailure(component, clockSeconds, pressurePsig, stress[i]);
        failures.add(failure);
        return failure;
    }

    // -----------------------------------------------------------------
    // Actuators
    // -----------------------------------------------------------------

    /**
     * Break a component outright, regardless of accumulated stress. A bare
     * actuator with no condition attached, for staging a scenario and for
     * whatever in-world sabotage or debug tool wants it. Does nothing if the
     * plant does not have that component or it is already broken.
     */
    public BoundaryFailure forceFailure(BoundaryComponent component, double pressurePsig) {
        if (component == null || broken[component.ordinal()]
                || !configuration.exposes(component)) {
            return null;
        }
        return recordFailure(component, pressurePsig);
    }

    /**
     * Replace a component: the break is closed and its accumulated stress goes
     * back to zero, because it is new pipe. Also a bare actuator — it makes no
     * judgement about whether the plant is in a fit condition to be repaired,
     * and it will happily replace a vessel head on a core that no longer
     * exists.
     */
    public void repair(BoundaryComponent component) {
        if (component == null) {
            return;
        }
        broken[component.ordinal()] = false;
        stress[component.ordinal()] = 0.0;
    }

    /** Replace every component. */
    public void repairAll() {
        for (BoundaryComponent component : COMPONENTS) {
            repair(component);
        }
    }

    /**
     * Back to a pristine plant with no history at all — stress, breaks,
     * exposure counters and the clock. Used when a core is initialised.
     */
    public void reset() {
        repairAll();
        failures.clear();
        clockSeconds = 0.0;
        secondsAboveDesignPressure = 0.0;
        secondsAboveCodePressure = 0.0;
        irradiatedFuelOverpressureSeconds = 0.0;
        peakPressurePsig = 0.0;
        lastFailureRatePerSecond = 0.0;
        random = new Random(randomSeed);
    }

    // -----------------------------------------------------------------
    // Configuration
    // -----------------------------------------------------------------

    /** Which recirculation system this plant is built with. */
    public PlantConfiguration getPlantConfiguration() {
        return configuration;
    }

    /**
     * Set the recirculation configuration. Changing to
     * {@link PlantConfiguration#REACTOR_INTERNAL_PUMPS} clears any state on the
     * recirculation line, because on a RIP plant that pipe does not exist and a
     * pipe that does not exist cannot be stressed or broken.
     */
    public void setPlantConfiguration(PlantConfiguration configuration) {
        if (configuration == null) {
            return;
        }
        this.configuration = configuration;
        for (BoundaryComponent component : COMPONENTS) {
            if (!configuration.exposes(component)) {
                stress[component.ordinal()] = 0.0;
                broken[component.ordinal()] = false;
            }
        }
    }

    /**
     * Whether the core contains fuel that has been irradiated. Only used to
     * decide whether exposure above {@link #FUEL_PRESENT_PRESSURE_PSIG} counts
     * toward {@link #getIrradiatedFuelOverpressureSeconds()}.
     */
    public void setIrradiatedFuelPresent(boolean irradiatedFuelPresent) {
        this.irradiatedFuelPresent = irradiatedFuelPresent;
    }

    /** @see #setIrradiatedFuelPresent(boolean) */
    public boolean isIrradiatedFuelPresent() {
        return irradiatedFuelPresent;
    }

    /**
     * Re-seed the failure draw. One reactor per seed, so two plants in the same
     * world do not break in lockstep. Retained across {@link #reset()}.
     */
    public void setRandomSeed(long seed) {
        this.randomSeed = seed;
        this.random = new Random(seed);
    }

    /** The seed the failure draw was last set to. */
    public long getRandomSeed() {
        return randomSeed;
    }

    // -----------------------------------------------------------------
    // Measurements — no judgements
    // -----------------------------------------------------------------

    /**
     * Accumulated stress on one component, where {@link #FAILURE_STRESS} is one
     * exhausted life. Monotonic except across a {@link #repair}. This is the
     * measurement {@code SPEC.md} section 7 requires be published so a player
     * can write their own alarms in Lua; the model itself has no opinion about
     * what value is worth reacting to.
     */
    public double getStress(BoundaryComponent component) {
        return component == null ? 0.0 : stress[component.ordinal()];
    }

    /** Accumulated stress on every component this plant has. */
    public Map<BoundaryComponent, Double> getStressByComponent() {
        Map<BoundaryComponent, Double> out = new EnumMap<>(BoundaryComponent.class);
        for (BoundaryComponent component : configuration.exposedComponents()) {
            out.put(component, stress[component.ordinal()]);
        }
        return out;
    }

    /** Stress as a fraction of one exhausted life. Same number, friendlier scale. */
    public double getStressFraction(BoundaryComponent component) {
        return getStress(component) / FAILURE_STRESS;
    }

    /**
     * The unbroken component carrying the most accumulated stress, or null if
     * nothing has any. The component most likely to be the next to go, though
     * this model states that as a fact about stress and not as a prediction.
     */
    public BoundaryComponent getMostStressedComponent() {
        BoundaryComponent worst = null;
        double best = 0.0;
        for (BoundaryComponent component : COMPONENTS) {
            int i = component.ordinal();
            if (broken[i] || !configuration.exposes(component)) {
                continue;
            }
            if (stress[i] > best) {
                best = stress[i];
                worst = component;
            }
        }
        return worst;
    }

    /** Greatest accumulated stress on any unbroken component. */
    public double getPeakStress() {
        BoundaryComponent worst = getMostStressedComponent();
        return worst == null ? 0.0 : stress[worst.ordinal()];
    }

    /** Whether a component has broken. */
    public boolean isBroken(BoundaryComponent component) {
        return component != null && broken[component.ordinal()];
    }

    /** Every component currently broken. */
    public Set<BoundaryComponent> getBrokenComponents() {
        Set<BoundaryComponent> out = EnumSet.noneOf(BoundaryComponent.class);
        for (BoundaryComponent component : COMPONENTS) {
            if (broken[component.ordinal()]) {
                out.add(component);
            }
        }
        return out;
    }

    /** Whether anything at all has broken. */
    public boolean hasFailed() {
        for (boolean b : broken) {
            if (b) {
                return true;
            }
        }
        return false;
    }

    /** Whether a failure the plant does not come back from has occurred. */
    public boolean hasTerminalFailure() {
        for (BoundaryComponent component : COMPONENTS) {
            if (broken[component.ordinal()] && component.isTerminal()) {
                return true;
            }
        }
        return false;
    }

    /** Every failure so far, in the order they happened. Immutable view. */
    public List<BoundaryFailure> getFailures() {
        return Collections.unmodifiableList(new ArrayList<>(failures));
    }

    /** The most recent failure, or null. */
    public BoundaryFailure getLastFailure() {
        return failures.isEmpty() ? null : failures.get(failures.size() - 1);
    }

    /**
     * Total failure hazard as of the last step, per second. Zero below the code
     * limit and zero while no component has reached {@link #FAILURE_STRESS}.
     * A rate, not a warning.
     */
    public double getFailureRatePerSecond() {
        return lastFailureRatePerSecond;
    }

    /** The same hazard expressed as a probability over one interval, 0 to 1. */
    public double getFailureProbabilityOver(double dtSeconds) {
        if (!(dtSeconds > 0.0) || !(lastFailureRatePerSecond > 0.0)) {
            return 0.0;
        }
        return -Math.expm1(-lastFailureRatePerSecond * dtSeconds);
    }

    /** Cumulative seconds spent above vessel design pressure. */
    public double getSecondsAboveDesignPressure() {
        return secondsAboveDesignPressure;
    }

    /** Cumulative seconds spent above the ASME code limit. */
    public double getSecondsAboveCodePressure() {
        return secondsAboveCodePressure;
    }

    /**
     * Cumulative seconds spent above {@value #FUEL_PRESENT_PRESSURE_PSIG} psig
     * with irradiated fuel in the vessel — the condition [TTC 2.2] says must
     * not occur. The model records it and does nothing else with it: preventing
     * it is operating discipline, which belongs to the player.
     */
    public double getIrradiatedFuelOverpressureSeconds() {
        return irradiatedFuelOverpressureSeconds;
    }

    /** Greatest dome pressure this boundary has ever seen, psig. Monotonic. */
    public double getPeakPressurePsig() {
        return peakPressurePsig;
    }

    /** Seconds this boundary model has been stepped since its last reset. */
    public double getClockSeconds() {
        return clockSeconds;
    }

    // -----------------------------------------------------------------
    // What a break actually does
    // -----------------------------------------------------------------

    /**
     * Steam leaving through breaks in the steam space, kg/s. Choked flow, so
     * linear in absolute pressure: violent at first and self-limiting as the
     * vessel depressurises.
     *
     * <p>A main steam line break is the one that <i>reduces</i> power. Pressure
     * falls, voids form rather than collapse, the void coefficient is negative,
     * and the reactor turns itself down — while the level swells hard on the
     * flashing and then shrinks as the inventory actually leaves.
     */
    public double steamBreakFlowKgPerS(double pressurePsig) {
        double psia = pressurePsig + PhysicalConstants.ATMOSPHERIC_PSI;
        if (!(psia > 0.0)) {
            return 0.0;
        }
        double scale = psia / RATED_PSIA;
        double multiple = 0.0;
        if (broken[BoundaryComponent.MAIN_STEAM_LINE.ordinal()]) {
            multiple += STEAM_LINE_BREAK_FLOW_MULTIPLE;
        }
        if (broken[BoundaryComponent.REACTOR_VESSEL_HEAD.ordinal()]) {
            multiple += HEAD_FAILURE_STEAM_MULTIPLE;
        }
        return multiple * RATED_STEAM_FLOW_KG_PER_S * scale;
    }

    /**
     * Water leaving through breaks below the water line, kg/s. Subcooled
     * discharge, so it goes as the square root of absolute pressure.
     *
     * <p>The recirculation line is the nasty one: this drain runs at the same
     * time as {@link #coreFlowDeliveredFraction()} goes to zero, so the plant
     * loses inventory and forced circulation together. That combination is the
     * design basis LOCA on a jet pump plant, and it is the failure a reactor
     * internal pump plant is structurally incapable of suffering.
     */
    public double liquidBreakFlowKgPerS(double pressurePsig) {
        double psia = pressurePsig + PhysicalConstants.ATMOSPHERIC_PSI;
        if (!(psia > 0.0)) {
            return 0.0;
        }
        double scale = Math.sqrt(psia / RATED_PSIA);
        double fraction = 0.0;
        if (broken[BoundaryComponent.RECIRCULATION_LINE.ordinal()]) {
            fraction += RECIRCULATION_BREAK_FLOW_FRACTION;
        }
        if (broken[BoundaryComponent.REACTOR_VESSEL_HEAD.ordinal()]) {
            fraction += HEAD_FAILURE_LIQUID_FRACTION;
        }
        return fraction * RATED_CORE_FLOW_KG_PER_S * scale;
    }

    /**
     * Fraction of commanded feedwater that actually reaches the vessel. Zero
     * once the feedwater line is broken.
     *
     * <p>This is the slow failure. Nothing dramatic happens: the pumps run, the
     * control program thinks it is feeding, and the level walks down at the
     * boiloff rate while decay heat keeps making steam. It is the failure most
     * likely to be survivable and most likely to be missed.
     */
    public double feedwaterDeliveredFraction() {
        return broken[BoundaryComponent.FEEDWATER_LINE.ordinal()] ? 0.0 : 1.0;
    }

    /**
     * Fraction of commanded recirculation flow the loops can still deliver.
     * Zero once the recirculation line is broken — the driving head is now
     * pushing water onto the drywell floor.
     */
    public double coreFlowDeliveredFraction() {
        return broken[BoundaryComponent.RECIRCULATION_LINE.ordinal()] ? 0.0 : 1.0;
    }

    // -----------------------------------------------------------------
    // Persistence
    // -----------------------------------------------------------------

    /** Number of doubles {@link #toArray()} produces. */
    public static final int SNAPSHOT_LENGTH = 6 + 2 * BoundaryComponent.values().length;

    /**
     * Snapshot for NBT persistence and client sync. Damage must survive a chunk
     * reload — a plant that heals when nobody is looking is a plant with no
     * consequences ({@code SPEC.md} section 11).
     *
     * <p>The random generator's state is deliberately not included. Accumulated
     * stress is the state that matters and it round-trips exactly; the draw
     * sequence restarting is indistinguishable from any other draw.
     */
    public double[] toArray() {
        double[] a = new double[SNAPSHOT_LENGTH];
        a[0] = clockSeconds;
        a[1] = secondsAboveDesignPressure;
        a[2] = secondsAboveCodePressure;
        a[3] = irradiatedFuelOverpressureSeconds;
        a[4] = peakPressurePsig;
        a[5] = configuration.ordinal();
        for (int i = 0; i < COMPONENTS.length; i++) {
            a[6 + i] = stress[i];
            a[6 + COMPONENTS.length + i] = broken[i] ? 1.0 : 0.0;
        }
        return a;
    }

    /**
     * Restore from {@link #toArray()}. The failure list is not carried across —
     * it is a narrative log, not plant state — but the breaks themselves are,
     * so a vessel that came back with a hole in it still has the hole.
     */
    public void fromArray(double[] a) {
        if (a == null || a.length < SNAPSHOT_LENGTH) {
            return;
        }
        clockSeconds = a[0];
        secondsAboveDesignPressure = a[1];
        secondsAboveCodePressure = a[2];
        irradiatedFuelOverpressureSeconds = a[3];
        peakPressurePsig = a[4];
        int ordinal = (int) Math.round(a[5]);
        PlantConfiguration[] configurations = PlantConfiguration.values();
        configuration = (ordinal >= 0 && ordinal < configurations.length)
                ? configurations[ordinal] : PlantConfiguration.EXTERNAL_RECIRCULATION_LOOPS;
        for (int i = 0; i < COMPONENTS.length; i++) {
            stress[i] = a[6 + i];
            broken[i] = a[6 + COMPONENTS.length + i] != 0.0;
        }
        failures.clear();
        lastFailureRatePerSecond = 0.0;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("BoundaryStress[").append(configuration.displayName());
        for (BoundaryComponent component : configuration.exposedComponents()) {
            sb.append(String.format(Locale.ROOT, " %s=%.3f%s",
                    component.name(), stress[component.ordinal()],
                    broken[component.ordinal()] ? "/BROKEN" : ""));
        }
        return sb.append(String.format(Locale.ROOT, " peak=%.0f psig]", peakPressurePsig)).toString();
    }
}
