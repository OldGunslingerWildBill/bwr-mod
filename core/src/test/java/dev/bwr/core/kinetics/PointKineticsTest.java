package dev.bwr.core.kinetics;

import dev.bwr.core.Check;
import dev.bwr.core.CoreConfig;

/**
 * Acceptance tests 1 to 4: the integrator itself, against closed-form answers.
 *
 * <p>These run {@link PointKinetics} bare, with no thermal feedback and (except
 * where the source is the thing under test) no neutron source, because every
 * case here has an exact analytic result and adding feedback would replace that
 * result with an opinion.
 */
public final class PointKineticsTest {

    private PointKineticsTest() {
    }

    /** A fresh integrator on U-235 data with the source switched off. */
    private static PointKinetics sourceFreeU235() {
        CoreConfig config = new CoreConfig();
        PointKinetics kinetics = new PointKinetics(config, DelayedNeutronData.U235);
        kinetics.setSourceStrengthPerSecond(0.0);
        return kinetics;
    }

    // ===============================================================
    // 1 — zero reactivity holds power flat
    // ===============================================================

    /**
     * <b>Acceptance 1.</b> Reactivity exactly zero, precursors at equilibrium:
     * power must not move.
     *
     * <p>This is the cheapest possible catch for a sign error in the prompt
     * term, a factor of {@code Lambda} in the wrong place, or precursor banks
     * initialised empty. Any of those produce a large excursion on the first
     * step instead of a flat line. The equilibrium is exact in real arithmetic —
     * substituting {@code C_i = beta_i*n/(lambda_i*Lambda)} into the implicit
     * step reproduces {@code n} identically — so the only permitted drift over
     * twenty thousand steps is floating point rounding.
     */
    public static void test01_zeroReactivityHoldsPowerFlat() {
        PointKinetics kinetics = sourceFreeU235();
        kinetics.initialiseToEquilibrium(1.0);

        double[] initialPrecursors = kinetics.getPrecursorConcentrations();
        double worstDrift = 0.0;
        for (int tick = 0; tick < 20_000; tick++) { // 1000 simulated seconds
            kinetics.step(0.0, 0.05);
            worstDrift = Math.max(worstDrift, Math.abs(kinetics.getNeutronPowerFraction() - 1.0));
        }

        Check.note("1000 s at rho = 0: n = %.17g, worst drift %.3g", kinetics.getNeutronPowerFraction(),
                worstDrift);
        Check.relative(1.0, kinetics.getNeutronPowerFraction(), 1.0e-9, "n after 1000 s at rho = 0");
        Check.lessThan(1.0e-9, worstDrift, "worst excursion from flat over 1000 s");

        double[] finalPrecursors = kinetics.getPrecursorConcentrations();
        for (int group = 0; group < DelayedNeutronData.GROUP_COUNT; group++) {
            Check.relative(initialPrecursors[group], finalPrecursors[group], 1.0e-9,
                    "precursor group " + group + " after 1000 s at rho = 0");
        }
        Check.absolute(0.0, kinetics.getPowerRateOfChangePerSecond(), 1.0e-9,
                "dn/dt at rho = 0 with equilibrium precursors");
    }

    /**
     * The same case with the source restored: power must creep <i>up</i>, not
     * stay put. {@code n = 0} being an equilibrium is exactly the failure the
     * source term exists to prevent, so a model where this stays flat has lost
     * its source.
     */
    public static void test02_zeroReactivityWithSourceCreepsUpward() {
        CoreConfig config = new CoreConfig();
        PointKinetics kinetics = new PointKinetics(config, DelayedNeutronData.U235);
        kinetics.setSourceStrengthPerSecond(1.0e-9);
        kinetics.initialiseToEquilibrium(1.0);

        for (int tick = 0; tick < 20_000; tick++) {
            kinetics.step(0.0, 0.05);
        }
        double rise = kinetics.getNeutronPowerFraction() - 1.0;
        Check.note("1000 s at rho = 0 with S = 1e-9/s: n rose by %.4g (source alone would give %.4g)",
                rise, 1.0e-9 * 1000.0);
        Check.greaterThan(0.0, rise, "power rise from the source term alone");
        Check.lessThan(1.0e-5, rise, "power rise from the source term alone");
    }

    // ===============================================================
    // 2 — prompt jump and the inhour equation
    // ===============================================================

    /**
     * <b>Acceptance 2.</b> A small step insertion gives the prompt jump
     * {@code beta/(beta-rho)}, then settles onto the asymptotic period predicted
     * by the dominant root of the inhour equation.
     *
     * <p>The prompt jump is the fixed point of the implicit step with the
     * precursors held: {@code n* = Lambda*sum(lambda_i*C_i)/(beta-rho)}, which
     * for equilibrium precursors is {@code n0*beta/(beta-rho)} exactly. The
     * asymptotic period is not a fit — it is the smallest positive root of
     * <pre>   rho = Lambda*omega + sum_i( beta_i*omega/(omega+lambda_i) )</pre>
     * solved here by bisection and compared against the measured e-folding.
     */
    public static void test03_promptJumpAndAsymptoticPeriod() {
        PointKinetics kinetics = sourceFreeU235();
        double beta = kinetics.getBetaTotalFraction();
        double rho = 0.10 * beta; // ten cents
        kinetics.initialiseToEquilibrium(1.0);

        // One tick of prompt jump. The delayed source has barely moved in 50 ms,
        // so the expected value is the jump times a whisker of asymptotic growth.
        kinetics.step(rho, 0.05);
        double measuredJump = kinetics.getNeutronPowerFraction();
        double expectedJump = beta / (beta - rho);

        double omega = dominantInhourRoot(kinetics.getDelayedNeutronData(),
                kinetics.getPromptLifetimeSeconds(), rho);
        double expectedPeriod = 1.0 / omega;
        double expectedJumpWithGrowth = expectedJump * Math.exp(0.05 * omega);

        Check.note("prompt jump after 50 ms: %.5f, beta/(beta-rho) = %.5f (with 50 ms of growth %.5f)",
                measuredJump, expectedJump, expectedJumpWithGrowth);
        Check.relative(expectedJumpWithGrowth, measuredJump, 0.02, "prompt jump ratio at rho = 0.10$");

        // Let the faster precursor modes die, then measure the e-folding over a
        // long window: T = (t2 - t1) / ln(n2/n1).
        advance(kinetics, rho, 300.0);
        double powerAtStart = kinetics.getNeutronPowerFraction();
        advance(kinetics, rho, 600.0);
        double powerAtEnd = kinetics.getNeutronPowerFraction();
        double measuredPeriod = 600.0 / Math.log(powerAtEnd / powerAtStart);

        Check.note("asymptotic period: measured %.3f s, inhour dominant root %.3f s (omega = %.6g /s)",
                measuredPeriod, expectedPeriod, omega);
        Check.relative(expectedPeriod, measuredPeriod, 0.02, "asymptotic period at rho = 0.10$");

        // The true period reported by the model must agree with the same root.
        Check.relative(expectedPeriod, kinetics.getPeriodSeconds(), 0.02,
                "PointKinetics.getPeriodSeconds() at rho = 0.10$");
    }

    /**
     * The same check one decade smaller, at one cent, where the asymptotic
     * period is dominated by the longest-lived precursor group and the prompt
     * jump is almost invisible. A model that has the group spectrum wrong passes
     * the ten-cent case and fails this one.
     */
    public static void test04_inhourAtOneCent() {
        PointKinetics kinetics = sourceFreeU235();
        double beta = kinetics.getBetaTotalFraction();
        double rho = 0.01 * beta;
        kinetics.initialiseToEquilibrium(1.0);

        double omega = dominantInhourRoot(kinetics.getDelayedNeutronData(),
                kinetics.getPromptLifetimeSeconds(), rho);
        advance(kinetics, rho, 1500.0);
        double powerAtStart = kinetics.getNeutronPowerFraction();
        advance(kinetics, rho, 1500.0);
        double measuredPeriod = 1500.0 / Math.log(kinetics.getNeutronPowerFraction() / powerAtStart);

        Check.note("at 0.01$: measured period %.2f s, inhour root %.2f s", measuredPeriod, 1.0 / omega);
        Check.relative(1.0 / omega, measuredPeriod, 0.02, "asymptotic period at rho = 0.01$");
    }

    /**
     * A negative step must produce the mirror image: a prompt drop of
     * {@code beta/(beta-rho)} below one, and a negative period. This is the same
     * arithmetic as a scram, isolated from the rod drives.
     */
    public static void test05_negativeStepGivesPromptDrop() {
        PointKinetics kinetics = sourceFreeU235();
        double beta = kinetics.getBetaTotalFraction();
        double rho = -1.0 * beta; // one dollar in
        kinetics.initialiseToEquilibrium(1.0);

        kinetics.step(rho, 0.05);
        double expectedDrop = beta / (beta - rho); // 0.5
        Check.note("prompt drop at -1.00$: %.5f, beta/(beta-rho) = %.5f",
                kinetics.getNeutronPowerFraction(), expectedDrop);
        Check.relative(expectedDrop, kinetics.getNeutronPowerFraction(), 0.02, "prompt drop at -1.00$");
        Check.lessThan(0.0, kinetics.getPeriodSeconds(), "period while power falls");
        Check.finiteAndPositive(kinetics.getNeutronPowerFraction(), "power after a negative step");
    }

    // ===============================================================
    // 3 — exactly prompt critical
    // ===============================================================

    /**
     * <b>Acceptance 3.</b> {@code rho = beta} exactly. The prompt term vanishes,
     * the delayed source alone drives the ramp, and the growth runs on the
     * {@code Lambda} timescale. The requirement is that it ramps and that
     * nothing becomes NaN, infinite or negative.
     */
    public static void test06_promptCriticalRampsAndStaysFinite() {
        PointKinetics kinetics = sourceFreeU235();
        double beta = kinetics.getBetaTotalFraction();
        kinetics.initialiseToEquilibrium(1.0);

        double previous = kinetics.getNeutronPowerFraction();
        for (int tick = 0; tick < 10; tick++) { // 0.5 s
            double now = kinetics.step(beta, 0.05);
            Check.finiteAndPositive(now, "power at exactly prompt critical, tick " + tick);
            Check.greaterThan(previous, now, "power must climb at prompt critical, tick " + tick);
            Check.allFiniteAndNonNegative(kinetics.getPrecursorConcentrations(),
                    "precursors at prompt critical");
            previous = now;
        }
        Check.note("0.5 s at exactly prompt critical (rho = beta = %.6f): n = %.6g, "
                        + "denominator %.6f, %d sub-steps",
                beta, kinetics.getNeutronPowerFraction(), kinetics.getLastStepDenominator(),
                kinetics.getLastStepSubStepCount());

        // At rho = beta the prompt term is exactly cancelled, so the implicit
        // denominator sits just under one and needs no subdivision. That is the
        // boundary the guard is measured against.
        Check.inRange(0.5, 1.0, kinetics.getLastStepDenominator(),
                "implicit denominator at exactly prompt critical");
        Check.isFalse(kinetics.wasLastStepDenominatorClamped(),
                "denominator must not need clamping at exactly prompt critical");
    }

    // ===============================================================
    // 4 — super prompt critical and the denominator guard
    // ===============================================================

    /**
     * <b>Acceptance 4.</b> {@code rho > beta} far enough that the nominal
     * sub-step would drive the implicit denominator through zero. The guard must
     * engage, the sub-step must subdivide, and power must stay finite and
     * positive.
     *
     * <p>The trigger point is arithmetic, not a guess: the denominator is
     * approximately {@code 1 - dt*(rho-beta)/Lambda}, so with the default
     * 1 ms nominal sub-step it reaches the 0.5 floor at
     * {@code rho - beta = 0.5*Lambda/dt = 0.02}. Below that no subdivision
     * happens and none is needed; above it, each halving buys a factor of two.
     */
    public static void test07_superPromptCriticalEngagesDenominatorGuard() {
        PointKinetics kinetics = sourceFreeU235();
        CoreConfig config = new CoreConfig();
        double nominalSubStep = config.tickSeconds / config.kineticsSubSteps;
        double beta = kinetics.getBetaTotalFraction();
        kinetics.initialiseToEquilibrium(1.0);

        double rho = 0.03; // about 4.6 dollars, a rod ejection
        double unguardedDenominator = kinetics.implicitDenominator(rho, nominalSubStep);
        Check.lessThan(PointKinetics.DENOMINATOR_FLOOR, unguardedDenominator,
                "the unguarded nominal sub-step must be the one under test");

        double power = kinetics.step(rho, config.tickSeconds);

        Check.note("rho = %.4f (%.2f$): unguarded denominator %.4f -> guarded %.4f, "
                        + "%d sub-steps (nominal %d), n = %.6g",
                rho, rho / beta, unguardedDenominator, kinetics.getLastStepDenominator(),
                kinetics.getLastStepSubStepCount(), config.kineticsSubSteps, power);

        Check.greaterThan(config.kineticsSubSteps, kinetics.getLastStepSubStepCount(),
                "the guard must subdivide beyond the nominal sub-step count");
        Check.greaterThan(PointKinetics.DENOMINATOR_FLOOR - 1.0e-12, kinetics.getLastStepDenominator(),
                "guarded implicit denominator");
        Check.finiteAndPositive(power, "power after a super-prompt-critical step");
        Check.greaterThan(1.0, power, "power must have grown at 4.6 dollars");
        Check.allFiniteAndNonNegative(kinetics.getPrecursorConcentrations(),
                "precursors after a super-prompt-critical step");
    }

    /**
     * Push it far past anything a fuelled core can hold. More halvings, still no
     * sign inversion, still finite. This is the case that decides whether a
     * reactivity excursion leaves a wrecked plant or a state full of NaN.
     */
    public static void test08_extremeReactivityStaysFiniteAndPositive() {
        PointKinetics kinetics = sourceFreeU235();
        CoreConfig config = new CoreConfig();
        kinetics.initialiseToEquilibrium(1.0);

        long previousSubSteps = 0;
        for (double rho : new double[]{0.02, 0.03, 0.06, 0.12, 0.50}) {
            double power = kinetics.step(rho, config.tickSeconds);
            Check.note("rho = %.3f: %d sub-steps, denominator %.4f, clamped %b, n = %.6g",
                    rho, kinetics.getLastStepSubStepCount(), kinetics.getLastStepDenominator(),
                    kinetics.wasLastStepDenominatorClamped(), power);
            Check.finiteAndPositive(power, "power at rho = " + rho);
            Check.allFiniteAndNonNegative(kinetics.getPrecursorConcentrations(),
                    "precursors at rho = " + rho);
            Check.greaterThan(previousSubSteps - 1, kinetics.getLastStepSubStepCount(),
                    "sub-step count must not fall as reactivity rises, at rho = " + rho);
            previousSubSteps = kinetics.getLastStepSubStepCount();
        }
        Check.isFalse(kinetics.wasLastStepDenominatorClamped(),
                "the halving budget must still cover rho = 0.5 dk/k");
    }

    /**
     * Plutonium's hazard has to be emergent. The same reactivity insertion in
     * dk/k is worth three times as many dollars on Pu-239 data, so the same
     * number that is comfortably delayed-critical on uranium is super prompt
     * critical on plutonium — with nothing anywhere penalising plutonium.
     */
    public static void test09_plutoniumGoesPromptOnAnInsertionUraniumRidesOut() {
        CoreConfig config = new CoreConfig();
        double rho = 0.004; // 0.62$ on U-235, 1.91$ on Pu-239

        PointKinetics uranium = new PointKinetics(config, DelayedNeutronData.U235);
        uranium.setSourceStrengthPerSecond(0.0);
        uranium.initialiseToEquilibrium(1.0);
        PointKinetics plutonium = new PointKinetics(config, DelayedNeutronData.PU239);
        plutonium.setSourceStrengthPerSecond(0.0);
        plutonium.initialiseToEquilibrium(1.0);

        advance(uranium, rho, 1.0);
        advance(plutonium, rho, 1.0);

        Check.note("rho = %.4f is %.2f$ on U-235 and %.2f$ on Pu-239", rho,
                uranium.reactivityInDollars(rho), plutonium.reactivityInDollars(rho));
        Check.note("after 1 s: U-235 n = %.4g, Pu-239 n = %.4g",
                uranium.getNeutronPowerFraction(), plutonium.getNeutronPowerFraction());

        Check.lessThan(1.0, uranium.reactivityInDollars(rho), "insertion in dollars on U-235");
        Check.greaterThan(1.0, plutonium.reactivityInDollars(rho), "insertion in dollars on Pu-239");
        Check.greaterThan(100.0 * uranium.getNeutronPowerFraction(),
                plutonium.getNeutronPowerFraction(),
                "plutonium must run away where uranium does not");
        Check.finiteAndPositive(plutonium.getNeutronPowerFraction(), "plutonium power after 1 s");
    }

    // ===============================================================
    // Helpers
    // ===============================================================

    /** Steps at fixed reactivity for a wall of simulated seconds, in 50 ms ticks. */
    private static void advance(PointKinetics kinetics, double rho, double seconds) {
        int ticks = (int) Math.round(seconds / 0.05);
        for (int i = 0; i < ticks; i++) {
            kinetics.step(rho, 0.05);
        }
    }

    /**
     * Smallest positive root of the inhour equation,
     * {@code rho = Lambda*omega + sum_i(beta_i*omega/(omega+lambda_i))}, by
     * bisection. The left side is strictly increasing in {@code omega} over the
     * positive reals, so bisection is exact to machine precision and needs no
     * initial guess.
     */
    public static double dominantInhourRoot(DelayedNeutronData data, double promptLifetimeSeconds,
                                            double reactivityDkOverK) {
        if (!(reactivityDkOverK > 0.0)) {
            throw new IllegalArgumentException("this helper solves the positive-period branch only");
        }
        double low = 1.0e-9;
        double high = 1.0e6;
        for (int i = 0; i < 400; i++) {
            double mid = 0.5 * (low + high);
            if (inhourReactivity(data, promptLifetimeSeconds, mid) < reactivityDkOverK) {
                low = mid;
            } else {
                high = mid;
            }
        }
        return 0.5 * (low + high);
    }

    private static double inhourReactivity(DelayedNeutronData data, double promptLifetimeSeconds,
                                           double omega) {
        double sum = promptLifetimeSeconds * omega;
        for (int i = 0; i < DelayedNeutronData.GROUP_COUNT; i++) {
            sum += data.betaFraction(i) * omega / (omega + data.lambdaPerSecond(i));
        }
        return sum;
    }
}
