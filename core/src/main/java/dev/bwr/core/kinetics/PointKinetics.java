package dev.bwr.core.kinetics;

import dev.bwr.core.CoreConfig;

/**
 * Six-group point kinetics with a startup neutron source, integrated by an
 * implicit (backward Euler) scheme specialised to the arrowhead structure of
 * the Jacobian.
 *
 * <h2>The equations</h2>
 * <pre>
 *   dn/dt   = ((rho - beta)/Lambda) * n + sum_i(lambda_i * C_i) + S
 *   dC_i/dt = (beta_i/Lambda) * n - lambda_i * C_i
 * </pre>
 * where {@code n} is fission power as a fraction of rated (never absolute
 * neutron density — that overflows), {@code C_i} are the six precursor
 * concentrations in the same fractional units, {@code rho} is net reactivity in
 * dk/k, {@code Lambda} is the prompt neutron lifetime in seconds, and {@code S}
 * is the startup neutron source in fraction-of-rated per second.
 *
 * <h2>Why the source term is not optional</h2>
 * Drop {@code S} and {@code n = 0} becomes an exact equilibrium: a shutdown
 * core decays to literally zero power, source range monitors read nothing, and
 * a startup is unobservable until the core is already critical. With the source
 * present the subcritical equilibrium is
 * <pre>   n = Lambda * S / |rho|</pre>
 * which supplies both the count-rate floor an operator sees on a shut-down
 * plant and the hyperbolic climb toward criticality as {@code |rho|} shrinks.
 * That hyperbola is what makes an inverse-count-rate (1/M) plot a straight line
 * extrapolating to the critical rod position — the standard startup technique,
 * available here only because the source term exists.
 *
 * <h2>Why the integrator is implicit</h2>
 * {@code Lambda} is about 4e-5 s against a 0.05 s game tick. The system is
 * stiff by three orders of magnitude and forward Euler at tick rate diverges
 * on the first step. The Jacobian is an <i>arrowhead</i> matrix — {@code n}
 * couples to every {@code C_i}, but each {@code C_i} couples only back to
 * {@code n} — so backward Euler collapses to a single scalar equation by
 * substitution, with no linear algebra library:
 * <pre>
 *   C_i' = (C_i + dt*(beta_i/Lambda)*n') / (1 + dt*lambda_i)
 *
 *   n'   = [ n + dt*S + dt*sum_i( lambda_i*C_i / (1 + dt*lambda_i) ) ]
 *          / [ 1 - dt*(rho-beta)/Lambda - (dt*dt/Lambda)*sum_i( lambda_i*beta_i / (1 + dt*lambda_i) ) ]
 * </pre>
 * Both numerator and denominator are positive in normal operation, so
 * {@code n'} and every {@code C_i'} stay non-negative by construction. No
 * clamping is needed to keep power physical.
 *
 * <h2>The denominator guard, and why it exists</h2>
 * The denominator above is approximately {@code 1 + dt*(beta-rho)/Lambda}. For
 * {@code rho < beta} — every subcritical, critical and delayed-supercritical
 * state, which is all of normal operation — it exceeds one and the scheme is
 * unconditionally stable. But when {@code rho > beta} the core is
 * <i>super-prompt-critical</i>, that term changes sign, and the denominator
 * falls toward zero as {@code dt} approaches {@code Lambda/(rho-beta)} — the
 * prompt e-folding time. At the crossing the solve divides by zero; past it the
 * denominator is negative and {@code n'} flips sign, producing negative power
 * and a silently poisoned state.
 *
 * <p>So before each solve the denominator is evaluated and, if it has fallen
 * below {@link #ACCURACY_DENOMINATOR_FLOOR}, the sub-step is halved and
 * retried, up to {@link #MAX_SUB_STEP_HALVINGS} times. Because the denominator
 * depends only on {@code rho}, {@code dt}, {@code Lambda} and the delayed data
 * — never on {@code n} or {@code C_i} — the required sub-step is constant
 * across a call with fixed {@code rho}, so the retry loop is resolved once up
 * front rather than per sub-step. The trigger and the resulting step are
 * identical either way; this is just the cheap way to spell it.
 *
 * <h2>Two floors, because they answer two different questions</h2>
 * <b>{@link #DENOMINATOR_FLOOR} = 0.5 is the survival floor.</b> It is the last
 * clamp applied after the halving budget is spent, and it exists solely so the
 * divisor can never reach zero or go negative and invert the sign of power. It
 * must not be relaxed: past it the state is silently poisoned rather than
 * merely inaccurate.
 *
 * <p><b>{@link #ACCURACY_DENOMINATOR_FLOOR} = 0.995 is the accuracy floor, and
 * it is the one that drives the halving loop.</b> Writing
 * {@code u = dt*(rho-beta)/Lambda}, backward Euler grows by {@code 1/(1-u)} per
 * sub-step where the true answer grows by {@code exp(u)}. Over {@code N}
 * sub-steps the logarithms differ by about {@code N*u*u/2}, which is
 * {@code u_tick * u / 2} with {@code u_tick = dt_tick*(rho-beta)/Lambda} the
 * prompt growth exponent of the whole tick — so the error in the answer is
 * {@code exp(u_tick*u/2)} and shrinking {@code u} is the only lever on it.
 *
 * <p>Using the survival floor as the accuracy criterion left the entire 1.5 to
 * 4 dollar band — precisely the rod-drop and rod-ejection range — integrated
 * with no subdivision at all. Measured against a 100000-sub-step reference over
 * one 50 ms tick from {@code n = 1} on U-235 data with the source off:
 *
 * <pre>
 *   rho       $      floor 0.5   floor 0.95   floor 0.995
 *   0.0100  1.54$      1.23x        1.11x        1.006x   (1600 sub-steps)
 *   0.0132  2.03$      2.21x        1.20x        1.011x   (3200)
 *   0.0160  2.46$      5.39x        1.20x        1.022x   (3200)
 *   0.0200  3.08$     41.0x         1.44x        1.021x   (6400)
 * </pre>
 *
 * At 0.5 every one of those reported the nominal 50 sub-steps and no clamp, so
 * nothing flagged the step as under-resolved. 0.95 fixes the order of magnitude
 * but not the answer, because a denominator criterion alone does not see
 * {@code u_tick}; 0.995 lands the whole band inside a couple of percent for
 * under a tenth of a millisecond of arithmetic. The cost is bounded anyway: the
 * sub-step needed scales as {@code 1/(rho-beta)} and the total is capped by
 * {@link #MAX_TOTAL_SUB_STEPS}.
 *
 * <p>Sub-stepping is how the prompt excursion gets resolved sharply enough for
 * Doppler feedback to terminate it at the right power, which is the whole
 * reason a Pu core behaves differently from an LEU one. At 41x the terminating
 * power is not right, which is why the accuracy floor is not a tuning knob.
 *
 * <h2>What this class does not do</h2>
 * It has no notion of a setpoint, a trip, or an acceptable power level. It
 * integrates the reactivity it is handed and reports the state that results.
 * Protection logic is the player's, written in CC:Tweaked.
 *
 * <p>Not thread safe. One instance per reactor, stepped from the server thread.
 */
public final class PointKinetics {

    /**
     * <b>Survival floor.</b> Smallest implicit denominator the solve will ever
     * divide by, applied as a clamp once the halving budget is spent. At 0.5 the
     * sub-step never exceeds half a prompt e-folding time and prompt growth is
     * capped at 2x per sub-step, but the point of this constant is that the
     * divisor stays comfortably positive: below zero, {@code n'} flips sign and
     * the state is poisoned rather than approximate. It is <b>not</b> the
     * criterion that decides when to subdivide — see
     * {@link #ACCURACY_DENOMINATOR_FLOOR}.
     */
    public static final double DENOMINATOR_FLOOR = 0.5;

    /**
     * <b>Accuracy floor.</b> The denominator the halving loop actually targets,
     * so that {@code u = dt*(rho-beta)/Lambda} stays under about 0.005 and one
     * tick's accumulated {@code exp(u_tick*u/2)} error stays inside a couple of
     * percent everywhere a fuelled core can reach.
     *
     * <p>Always at or above {@link #DENOMINATOR_FLOOR}; the two are separate
     * because they answer different questions, and collapsing them back into one
     * constant is what produced the 41x overstatement tabulated in the class
     * comment. Raising this toward 1.0 costs sub-steps in proportion to
     * {@code 1/(1-floor)} and buys error in the same proportion; lowering it
     * re-opens the rod-ejection band, and 0.95 was measured still 44% high
     * there. Bounded either way by {@link #MAX_TOTAL_SUB_STEPS}.
     */
    public static final double ACCURACY_DENOMINATOR_FLOOR = 0.995;

    /**
     * Bound on retries of the halving loop. Fourteen halvings shrink a 1 ms
     * nominal sub-step to about 60 ns, which satisfies the accuracy floor past
     * +1 dk/k — over 150 dollars, two orders of magnitude beyond anything a
     * fuelled core can physically hold — and satisfies the survival floor far
     * beyond that. It was ten while the loop targeted the survival floor; the
     * tighter accuracy floor wants a smaller sub-step for the same reactivity,
     * so the budget grew with it. The bound exists so a pathological caller
     * cannot make the integrator run forever, not because the physics needs it,
     * and in practice {@link #MAX_TOTAL_SUB_STEPS} binds first.
     */
    public static final int MAX_SUB_STEP_HALVINGS = 14;

    /** Hard ceiling on implicit solves per {@link #step} call, bounding worst-case tick cost. */
    public static final long MAX_TOTAL_SUB_STEPS = 200_000L;

    /** Sanity bound on the configured nominal sub-step count. */
    private static final int MAX_NOMINAL_SUB_STEPS = 100_000;

    /**
     * Overflow guard on fractional power, 1e12 times rated.
     *
     * <p>A sustained super-prompt excursion with feedback disabled grows without
     * bound and would reach {@code Infinity} in a fraction of a second, after
     * which every downstream double is NaN and the reactor state is
     * unrecoverable garbage. Capping keeps the state finite so the thermal,
     * damage and containment models can run the consequences of the excursion
     * instead of propagating NaN. The cap is 1e12 x 3579 MW; nothing physical
     * approaches it, so it never alters a meaningful trajectory.
     */
    private static final double MAX_NEUTRON_POWER_FRACTION = 1.0e12;

    private final CoreConfig config;

    private DelayedNeutronData delayedData;
    private double[] lambdaPerSecond;
    private double[] betaFraction;
    private double betaTotalFraction;

    private double promptLifetimeSeconds;
    private double sourceStrengthPerSecond;
    // Derived from installed cassettes; the separately persisted base source stays unchanged.
    private double installedSourcePerSecond;
    public void setInstalledSourcePerSecond(double value) {
        if (!Double.isFinite(value) || value < 0) throw new IllegalArgumentException("Invalid installed source");
        installedSourcePerSecond = value;
    }

    private double neutronPowerFraction;
    private final double[] precursorConcentrations = new double[DelayedNeutronData.GROUP_COUNT];

    private double lastReactivityDkOverK;

    // Diagnostics for the last step() call.
    private long lastStepSubStepCount;
    private double lastStepSubStepSeconds;
    private double lastStepDenominator;
    private boolean lastStepDenominatorWasClamped;
    private boolean lastStepWasUnderResolved;

    /**
     * @param config      supplies the nominal sub-step count, the prompt neutron
     *                    lifetime and the startup source strength. The reference
     *                    is retained, so retuning {@code kineticsSubSteps} at
     *                    runtime takes effect on the next step.
     * @param delayedData delayed neutron data for the loaded fuel
     */
    public PointKinetics(CoreConfig config, DelayedNeutronData delayedData) {
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        this.config = config;
        this.promptLifetimeSeconds = config.promptLifetime;
        this.sourceStrengthPerSecond = config.neutronSource;
        adoptDelayedData(delayedData);
        this.neutronPowerFraction = 0.0;
        this.lastReactivityDkOverK = 0.0;
        if (!(this.promptLifetimeSeconds > 0.0)) {
            throw new IllegalArgumentException(
                    "promptLifetime must be positive, got " + this.promptLifetimeSeconds);
        }
    }

    private void adoptDelayedData(DelayedNeutronData data) {
        if (data == null) {
            throw new IllegalArgumentException("delayedData must not be null");
        }
        this.delayedData = data;
        this.lambdaPerSecond = data.lambdaPerSecond();
        this.betaFraction = data.betaFraction();
        this.betaTotalFraction = data.betaTotalFraction();
    }

    // ---------------------------------------------------------------
    // Initialisation
    // ---------------------------------------------------------------

    /**
     * Set power and put every precursor group at the steady-state
     * concentration for that power, {@code C_i = beta_i * n / (lambda_i * Lambda)}.
     *
     * <p>Without this a run started at rated power begins with empty precursor
     * banks, so the delayed neutron source is missing and power collapses on
     * the first tick before recovering over the next few minutes — a large
     * spurious transient that has nothing to do with the scenario being
     * simulated. The resulting state is an exact equilibrium at
     * {@code rho = -Lambda*S/n}, which for any realistic source strength is
     * indistinguishable from zero.
     *
     * @param powerFractionOfRated fission power as a fraction of rated; negative values clamp to zero
     */
    public void initialiseToEquilibrium(double powerFractionOfRated) {
        double n = Double.isFinite(powerFractionOfRated) ? Math.max(0.0, powerFractionOfRated) : 0.0;
        this.neutronPowerFraction = n;
        for (int i = 0; i < DelayedNeutronData.GROUP_COUNT; i++) {
            precursorConcentrations[i] = betaFraction[i] * n / (lambdaPerSecond[i] * promptLifetimeSeconds);
        }
        this.lastReactivityDkOverK = 0.0;
        clearStepDiagnostics();
    }

    /**
     * Set the state to the subcritical source-driven equilibrium for a given
     * negative reactivity: {@code n = Lambda * S / |rho|}, with precursors at
     * their steady-state values for that power.
     *
     * <p>This is the correct starting state for a cold shutdown core with a
     * live startup source, and it is what puts a finite, believable count rate
     * on the source range monitors before the first rod moves. The state is an
     * exact equilibrium: substituting it into the kinetics gives
     * {@code dn/dt = rho*n/Lambda + S = 0}.
     *
     * @param reactivityDkOverK net reactivity, must be strictly negative — a core
     *                          at or above critical has no bounded source-driven
     *                          equilibrium to initialise to
     */
    public void initialiseSubcritical(double reactivityDkOverK) {
        if (!(reactivityDkOverK < 0.0) || !Double.isFinite(reactivityDkOverK)) {
            throw new IllegalArgumentException(
                    "initialiseSubcritical needs strictly negative reactivity; a core at or above "
                            + "critical has no bounded source-driven equilibrium. Got " + reactivityDkOverK);
        }
        double n = promptLifetimeSeconds * sourceStrengthPerSecond / Math.abs(reactivityDkOverK);
        this.neutronPowerFraction = n;
        for (int i = 0; i < DelayedNeutronData.GROUP_COUNT; i++) {
            precursorConcentrations[i] = betaFraction[i] * n / (lambdaPerSecond[i] * promptLifetimeSeconds);
        }
        this.lastReactivityDkOverK = reactivityDkOverK;
        clearStepDiagnostics();
    }

    /**
     * Restore a persisted state verbatim, for world load.
     *
     * @param neutronPowerFraction    fission power, fraction of rated
     * @param precursorConcentrations six precursor concentrations, copied in
     */
    public void restoreState(double neutronPowerFraction, double[] precursorConcentrations) {
        if (precursorConcentrations == null
                || precursorConcentrations.length != DelayedNeutronData.GROUP_COUNT) {
            throw new IllegalArgumentException("expected " + DelayedNeutronData.GROUP_COUNT
                    + " precursor concentrations");
        }
        this.neutronPowerFraction = Math.max(0.0, neutronPowerFraction);
        for (int i = 0; i < DelayedNeutronData.GROUP_COUNT; i++) {
            this.precursorConcentrations[i] = Math.max(0.0, precursorConcentrations[i]);
        }
        clearStepDiagnostics();
    }

    private void clearStepDiagnostics() {
        lastStepSubStepCount = 0L;
        lastStepSubStepSeconds = 0.0;
        lastStepDenominator = Double.NaN;
        lastStepDenominatorWasClamped = false;
        lastStepWasUnderResolved = false;
    }

    // ---------------------------------------------------------------
    // Integration
    // ---------------------------------------------------------------

    /**
     * Advance {@code n} and the six {@code C_i} by {@code dtSeconds} at fixed
     * reactivity.
     *
     * <p>Reactivity is held constant across the whole call by design: per
     * {@code SPEC.md} section 6.3 the sub-stepped kinetics see pressure, void
     * and fuel temperature frozen within a tick, and those are updated once per
     * tick around this call. That keeps the fast positive-feedback pressure
     * loop from being integrated at sub-step resolution against a thermal model
     * that only has tick resolution.
     *
     * @param reactivityDkOverK net reactivity for this tick, dk/k
     * @param dtSeconds         interval to advance, seconds; non-positive is a no-op
     * @return the new fission power as a fraction of rated
     */
    public double step(double reactivityDkOverK, double dtSeconds) {
        if (!(dtSeconds > 0.0) || !Double.isFinite(dtSeconds)) {
            return neutronPowerFraction;
        }
        double rho = Double.isFinite(reactivityDkOverK) ? reactivityDkOverK : 0.0;
        this.lastReactivityDkOverK = rho;

        int nominalSubSteps = config.kineticsSubSteps;
        if (nominalSubSteps < 1) {
            nominalSubSteps = 1;
        } else if (nominalSubSteps > MAX_NOMINAL_SUB_STEPS) {
            nominalSubSteps = MAX_NOMINAL_SUB_STEPS;
        }

        // --- Denominator guard: halve the sub-step until the implicit solve is
        // --- well resolved. Only a super-prompt-critical core ever enters this
        // --- loop; below prompt critical the denominator exceeds one.
        //
        // The loop targets the ACCURACY floor, not the survival floor. Those are
        // different numbers on purpose (see the class comment): backward Euler
        // grows by 1/(1-u) against the true exp(u), so a denominator that is
        // merely positive is not a denominator that is right. Subdividing only
        // at 0.5 left the whole 1.5-4 dollar rod-ejection band unsubdivided and
        // overstated the excursion by up to 41x; 0.95 still left it 44% high.
        // Do not fold these back into one constant.
        double dt = dtSeconds / nominalSubSteps;
        int halvings = 0;
        while (implicitDenominator(rho, dt) < ACCURACY_DENOMINATOR_FLOOR
                && halvings < MAX_SUB_STEP_HALVINGS) {
            dt *= 0.5;
            halvings++;
        }

        long totalSubSteps = (long) nominalSubSteps << halvings;
        if (totalSubSteps > MAX_TOTAL_SUB_STEPS) {
            totalSubSteps = MAX_TOTAL_SUB_STEPS;
        }
        dt = dtSeconds / totalSubSteps;

        double denominator = implicitDenominator(rho, dt);
        // Resolution is a spectrum, poisoning is a cliff. Under-resolution is
        // reported and integrated anyway; a denominator at or below zero is
        // clamped, because that is the one that inverts the sign of power.
        boolean underResolved = !(denominator >= ACCURACY_DENOMINATOR_FLOOR);
        boolean clamped = false;
        if (!(denominator >= DENOMINATOR_FLOOR)) {
            // Halving and sub-step budgets both exhausted, i.e. reactivity far
            // beyond anything a real core can hold. Clamp rather than divide by
            // a near-zero or negative number: growth per sub-step is capped
            // instead of the sign of power inverting.
            denominator = DENOMINATOR_FLOOR;
            clamped = true;
        }

        // dt is constant across the loop, so the per-group coefficients are too.
        final int groups = DelayedNeutronData.GROUP_COUNT;
        double[] precursorDecay = new double[groups];   // 1 / (1 + dt*lambda_i)
        double[] precursorSource = new double[groups];  // dt * beta_i / Lambda
        for (int i = 0; i < groups; i++) {
            precursorDecay[i] = 1.0 / (1.0 + dt * lambdaPerSecond[i]);
            precursorSource[i] = dt * betaFraction[i] / promptLifetimeSeconds;
        }
        double sourceTerm = dt * (sourceStrengthPerSecond + installedSourcePerSecond);

        double n = neutronPowerFraction;
        for (long s = 0; s < totalSubSteps; s++) {
            double delayedSum = 0.0;
            for (int i = 0; i < groups; i++) {
                delayedSum += lambdaPerSecond[i] * precursorConcentrations[i] * precursorDecay[i];
            }
            double numerator = n + sourceTerm + dt * delayedSum;
            double nNext = numerator / denominator;
            if (nNext > MAX_NEUTRON_POWER_FRACTION) {
                nNext = MAX_NEUTRON_POWER_FRACTION;
            }
            for (int i = 0; i < groups; i++) {
                precursorConcentrations[i] =
                        (precursorConcentrations[i] + precursorSource[i] * nNext) * precursorDecay[i];
            }
            n = nNext;
        }
        this.neutronPowerFraction = n;

        this.lastStepSubStepCount = totalSubSteps;
        this.lastStepSubStepSeconds = dt;
        this.lastStepDenominator = denominator;
        this.lastStepDenominatorWasClamped = clamped;
        this.lastStepWasUnderResolved = underResolved;
        return n;
    }

    /**
     * The implicit denominator for a candidate sub-step.
     * <pre>
     *   1 - dt*(rho-beta)/Lambda - (dt*dt/Lambda) * sum_i( lambda_i*beta_i / (1 + dt*lambda_i) )
     * </pre>
     * Exposed for tests and for a caller that wants to know how close the
     * current reactivity is to needing sub-division.
     */
    public double implicitDenominator(double reactivityDkOverK, double dtSeconds) {
        double groupSum = 0.0;
        for (int i = 0; i < DelayedNeutronData.GROUP_COUNT; i++) {
            groupSum += lambdaPerSecond[i] * betaFraction[i] / (1.0 + dtSeconds * lambdaPerSecond[i]);
        }
        return 1.0
                - dtSeconds * (reactivityDkOverK - betaTotalFraction) / promptLifetimeSeconds
                - (dtSeconds * dtSeconds / promptLifetimeSeconds) * groupSum;
    }

    // ---------------------------------------------------------------
    // State
    // ---------------------------------------------------------------

    /** Fission power as a fraction of rated. Excludes decay heat. */
    public double getNeutronPowerFraction() {
        return neutronPowerFraction;
    }

    /** Copy of the six precursor concentrations. */
    public double[] getPrecursorConcentrations() {
        return precursorConcentrations.clone();
    }

    /** One precursor group concentration. */
    public double getPrecursorConcentration(int group) {
        return precursorConcentrations[group];
    }

    /**
     * The delayed neutron source, fraction-of-rated per second:
     * {@code sum_i(lambda_i * C_i)}. This is the term that keeps a scrammed
     * core producing fission power for minutes after the rods are in.
     */
    public double getDelayedSourcePerSecond() {
        double sum = 0.0;
        for (int i = 0; i < DelayedNeutronData.GROUP_COUNT; i++) {
            sum += lambdaPerSecond[i] * precursorConcentrations[i];
        }
        return sum;
    }

    /**
     * {@code dn/dt} evaluated exactly from the current state and the reactivity
     * of the most recent step, fraction-of-rated per second.
     */
    public double getPowerRateOfChangePerSecond() {
        return ((lastReactivityDkOverK - betaTotalFraction) / promptLifetimeSeconds) * neutronPowerFraction
                + getDelayedSourcePerSecond()
                + sourceStrengthPerSecond + installedSourcePerSecond;
    }

    /**
     * Instantaneous reactor period {@code n / (dn/dt)}, seconds. Positive while
     * power rises, negative while it falls, infinite at steady state.
     *
     * <p>This is the <b>true</b> period of the physics. It is not what an
     * operator sees. The period displayed in a control room is derived from a
     * neutron detector's own signal, so a detector that is saturated, dead-time
     * rolled over, or on the wrong range produces a garbage period — which is a
     * real startup hazard and must be preserved. Instrumentation must derive
     * indicated period from its detector, never from this method.
     */
    public double getPeriodSeconds() {
        double rate = getPowerRateOfChangePerSecond();
        if (rate == 0.0 || !Double.isFinite(rate)) {
            return Double.POSITIVE_INFINITY;
        }
        return neutronPowerFraction / rate;
    }

    /** Reactivity expressed in dollars: {@code rho / beta}. A unit conversion, not a judgement. */
    public double reactivityInDollars(double reactivityDkOverK) {
        return reactivityDkOverK / betaTotalFraction;
    }

    /** Net reactivity handed to the most recent {@link #step}, dk/k. */
    public double getLastReactivityDkOverK() {
        return lastReactivityDkOverK;
    }

    // ---------------------------------------------------------------
    // Parameters
    // ---------------------------------------------------------------

    /** Delayed neutron data currently in use. */
    public DelayedNeutronData getDelayedNeutronData() {
        return delayedData;
    }

    /**
     * Swap in new delayed neutron data, keeping {@code n} and the precursor
     * inventories as they are.
     *
     * <p>Called when the nodal solve refreshes {@code beta_eff} at 1 Hz per
     * {@code SPEC.md} section 1.3, and on refuelling. Precursors are physical
     * inventories that exist regardless of what the fission-rate weighting says
     * this second, so they are carried across rather than reset — resetting
     * them would inject a spurious transient every time the shape solve ran.
     */
    public void setDelayedNeutronData(DelayedNeutronData data) {
        adoptDelayedData(data);
    }

    /** Effective total delayed neutron fraction currently in use. */
    public double getBetaTotalFraction() {
        return betaTotalFraction;
    }

    /** Prompt neutron lifetime, seconds. */
    public double getPromptLifetimeSeconds() {
        return promptLifetimeSeconds;
    }

    /** Set the prompt neutron lifetime, seconds. A per-fuel property per {@code SPEC.md} section 2.1. */
    public void setPromptLifetimeSeconds(double promptLifetimeSeconds) {
        if (!(promptLifetimeSeconds > 0.0) || !Double.isFinite(promptLifetimeSeconds)) {
            throw new IllegalArgumentException(
                    "promptLifetime must be finite and positive, got " + promptLifetimeSeconds);
        }
        this.promptLifetimeSeconds = promptLifetimeSeconds;
    }

    /** Startup neutron source strength, fraction-of-rated per second. */
    public double getSourceStrengthPerSecond() {
        return sourceStrengthPerSecond;
    }

    /**
     * Set the startup neutron source strength, fraction-of-rated per second.
     * A real installed source decays over the cycle and an unfuelled core has
     * only spontaneous fission, so this is a state a fuel or source model owns.
     */
    public void setSourceStrengthPerSecond(double sourceStrengthPerSecond) {
        if (!(sourceStrengthPerSecond >= 0.0) || !Double.isFinite(sourceStrengthPerSecond)) {
            throw new IllegalArgumentException(
                    "source strength must be finite and non-negative, got " + sourceStrengthPerSecond);
        }
        this.sourceStrengthPerSecond = sourceStrengthPerSecond;
    }

    // ---------------------------------------------------------------
    // Integrator diagnostics
    // ---------------------------------------------------------------

    /** Implicit solves performed during the most recent {@link #step}. */
    public long getLastStepSubStepCount() {
        return lastStepSubStepCount;
    }

    /** Sub-step length actually used by the most recent {@link #step}, seconds. */
    public double getLastStepSubStepSeconds() {
        return lastStepSubStepSeconds;
    }

    /** Implicit denominator used by the most recent {@link #step}. NaN before the first step. */
    public double getLastStepDenominator() {
        return lastStepDenominator;
    }

    /**
     * True when the most recent step exhausted its halving budget and had to
     * clamp the denominator to {@link #DENOMINATOR_FLOOR} — the trajectory
     * through that step is not merely coarse, it is capped. Purely a numerical
     * diagnostic.
     */
    public boolean wasLastStepDenominatorClamped() {
        return lastStepDenominatorWasClamped;
    }

    /**
     * True when the most recent step could not get its implicit denominator up
     * to {@link #ACCURACY_DENOMINATOR_FLOOR}, because the halving budget or
     * {@link #MAX_TOTAL_SUB_STEPS} ran out first. The step still integrated and
     * the sign of power is still sound — that is what
     * {@link #wasLastStepDenominatorClamped()} reports — but the prompt
     * excursion through it is coarser than the integrator wanted and the growth
     * is overstated.
     *
     * <p>Purely a numerical diagnostic. It says the integrator was short of
     * resolution, not that the reactor is in any particular condition.
     */
    public boolean wasLastStepUnderResolved() {
        return lastStepWasUnderResolved;
    }
}
