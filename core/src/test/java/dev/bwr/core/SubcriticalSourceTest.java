package dev.bwr.core;

import dev.bwr.core.harness.TransientHarness;
import dev.bwr.core.kinetics.DelayedNeutronData;
import dev.bwr.core.kinetics.PointKinetics;

/**
 * Acceptance test 6: a subcritical core with a live source settles at
 * {@code n = Lambda*S/|rho|}, and the source range monitors read a finite,
 * non-zero count rate because of it.
 *
 * <p>This is the single property that makes a startup playable. Without the
 * source term {@code n = 0} is an exact equilibrium: a shutdown core decays to
 * literally zero, the count rate is zero, and the first sign of anything
 * happening is the core already being critical. With it, the count rate is a
 * hyperbola in {@code |rho|} — deeper subcritical reads lower, nearer critical
 * reads higher — and its reciprocal is the 1/M plot an operator extrapolates to
 * find the critical rod position before arriving there.
 */
public final class SubcriticalSourceTest {

    private SubcriticalSourceTest() {
    }

    /**
     * <b>Acceptance 6, analytic half.</b> Start from a dead core with empty
     * precursor banks and let the source fill them. The steady state must be
     * {@code Lambda*S/|rho|} exactly, for every depth of subcriticality.
     */
    public static void test01_subcriticalEquilibriumIsLambdaSourceOverRho() {
        double source = TransientHarness.CALIBRATED_SOURCE_PER_SECOND;
        for (double rho : new double[]{-0.20, -0.10, -0.05, -0.02, -0.005}) {
            CoreConfig config = new CoreConfig();
            PointKinetics kinetics = new PointKinetics(config, DelayedNeutronData.U235);
            kinetics.setSourceStrengthPerSecond(source);
            kinetics.restoreState(0.0, new double[DelayedNeutronData.GROUP_COUNT]);

            double expected = kinetics.getPromptLifetimeSeconds() * source / Math.abs(rho);
            // The approach to the subcritical equilibrium runs on the longest
            // precursor group, about 85 seconds whatever the depth, so 20000 s is
            // two hundred time constants. Stepped coarsely on purpose: the fixed
            // point of the implicit scheme is the analytic equilibrium for any
            // step length, so a coarse step reaches the same answer sooner.
            for (int step = 0; step < 4000; step++) {
                kinetics.step(rho, 5.0);
            }
            double settled = kinetics.getNeutronPowerFraction();
            Check.note("rho = %+.3f: settled n = %.6E, Lambda*S/|rho| = %.6E (%.3f%%)",
                    rho, settled, expected, 100.0 * (settled - expected) / expected);
            Check.relative(expected, settled, 1.0e-4, "subcritical equilibrium at rho = " + rho);
        }
    }

    /**
     * {@code initialiseSubcritical} must land on that same equilibrium exactly,
     * so a core loaded at hot shutdown does not drift on its first tick.
     */
    public static void test02_initialiseSubcriticalIsAnExactEquilibrium() {
        CoreConfig config = new CoreConfig();
        PointKinetics kinetics = new PointKinetics(config, DelayedNeutronData.U235);
        kinetics.setSourceStrengthPerSecond(TransientHarness.CALIBRATED_SOURCE_PER_SECOND);
        double rho = -0.1067;
        kinetics.initialiseSubcritical(rho);
        double initial = kinetics.getNeutronPowerFraction();

        for (int tick = 0; tick < 20_000; tick++) { // 1000 s
            kinetics.step(rho, 0.05);
        }
        Check.note("initialised at n = %.6E, 1000 s later n = %.6E",
                initial, kinetics.getNeutronPowerFraction());
        Check.relative(initial, kinetics.getNeutronPowerFraction(), 1.0e-9,
                "n after 1000 s from initialiseSubcritical");
        Check.absolute(0.0, kinetics.getPowerRateOfChangePerSecond(),
                1.0e-9 * initial, "dn/dt at the subcritical equilibrium");
    }

    /**
     * The hyperbola: halving the shutdown margin doubles the count rate.
     * Multiplication {@code M = 1/|rho|} up to the constant, so the inverse
     * count rate is a straight line through the origin in {@code |rho|} — which
     * is what makes a 1/M plot extrapolate.
     */
    public static void test03_countRateIsHyperbolicInShutdownMargin() {
        double source = TransientHarness.CALIBRATED_SOURCE_PER_SECOND;
        CoreConfig config = new CoreConfig();
        double[] depths = {-0.16, -0.08, -0.04, -0.02, -0.01};
        double[] countRates = new double[depths.length];

        for (int i = 0; i < depths.length; i++) {
            PointKinetics kinetics = new PointKinetics(config, DelayedNeutronData.U235);
            kinetics.setSourceStrengthPerSecond(source);
            kinetics.initialiseSubcritical(depths[i]);
            double n = kinetics.getNeutronPowerFraction();
            double trueCps = config.srmCountsPerUnitPower * n;
            countRates[i] = trueCps * Math.exp(-trueCps * config.srmDeadTimeSeconds);
            Check.note("rho = %+.3f: n = %.4E, count rate %.4E cps, 1/M = %.4f",
                    depths[i], n, countRates[i], countRates[0] / countRates[i]);
        }

        for (int i = 1; i < depths.length; i++) {
            Check.greaterThan(countRates[i - 1], countRates[i],
                    "count rate must rise as the core approaches critical, step " + i);
            // Halving |rho| doubles the count rate, so 1/M is linear in |rho|.
            double inverseM = countRates[0] / countRates[i];
            double expected = Math.abs(depths[i]) / Math.abs(depths[0]);
            Check.relative(expected, inverseM, 1.0e-6, "1/M at rho = " + depths[i]);
        }
    }

    /**
     * <b>Acceptance 6, plant half.</b> A hot shutdown core with every rod in and
     * the installed source must put a real number on the source range monitors —
     * about 41 cps, an order of magnitude above the bottom of the scale.
     */
    public static void test04_hotShutdownCoreReadsAboutFortyOneCountsPerSecond() {
        ReactorCore core = TransientHarness.shutdownCore();
        double rho = core.getReactivityDkOverK();
        double n = core.getNeutronPowerFraction();
        double cps = TransientHarness.averageCountRate(core);

        double expectedN = core.getPromptLifetimeSeconds()
                * core.getNeutronSourceStrengthPerSecond() / Math.abs(rho);
        Check.note("hot shutdown: rho = %+.5f dk/k (%.2f$), n = %.5E (Lambda*S/|rho| = %.5E)",
                rho, core.getReactivityDollars(), n, expectedN);
        Check.note("source range monitors read %.4E cps, status %s",
                cps, core.getSourceRangeMonitor(0).getStatus());

        Check.lessThan(0.0, rho, "a hot shutdown core must be subcritical");
        Check.relative(expectedN, n, 1.0e-9, "hot shutdown flux against Lambda*S/|rho|");
        Check.finiteAndPositive(cps, "hot shutdown count rate");
        Check.relative(41.0, cps, 0.10, "hot shutdown count rate");
        Check.greaterThan(3.0, cps, "the shutdown count rate must be on scale");

        // Every channel agrees, since they see the same core.
        for (int channel = 0; channel < ReactorCore.SOURCE_RANGE_CHANNELS; channel++) {
            Check.relative(cps, core.getSourceRangeCountsPerSecond(channel), 1.0e-12,
                    "source range channel " + channel);
        }
    }

    /**
     * Deeper subcritical reads lower, nearer critical reads higher — on the
     * assembled core, through the rod worth curve and the real instrument chain,
     * not just in the integrator.
     */
    public static void test05_rodWithdrawalRaisesTheCountRate() {
        // All rods in is about -0.107 dk/k on a fresh core, so these targets are
        // inside the reachable band; asking for more than the rods are worth
        // would simply saturate at the bottom of travel.
        double[] targets = {-0.10, -0.08, -0.05, -0.03, -0.015};
        double previousCps = -1.0;
        double previousNotch = -1.0;

        for (double target : targets) {
            ReactorCore core = TransientHarness.shutdownCore();
            double achieved = core.positionRodsForReactivity(target);
            core.getKinetics().initialiseSubcritical(achieved);
            core.settleInstruments();

            double cps = TransientHarness.averageCountRate(core);
            double notch = TransientHarness.averageNotch(core);
            Check.note("target rho %+.3f, achieved %+.5f at average notch %.2f: %.4E cps",
                    target, achieved, notch, cps);

            Check.greaterThan(previousCps, cps, "count rate at rho = " + target);
            Check.greaterThan(previousNotch - 1.0e-9, notch,
                    "rods must be further out at rho = " + target);
            previousCps = cps;
            previousNotch = notch;
        }
    }

    /**
     * Boron is the other direction. Injecting it drives the core deeper
     * subcritical and the count rate down — the standby liquid control answer to
     * rods that will not insert, visible on the instruments.
     */
    public static void test06_boronInjectionDrivesTheCountRateDown() {
        ReactorCore clean = TransientHarness.shutdownCore();
        double cleanRho = clean.getReactivityDkOverK();
        double cleanCps = TransientHarness.averageCountRate(clean);

        ReactorCore borated = TransientHarness.shutdownCore();
        borated.setBoronPpm(PhysicalConstants.SLC_SHUTDOWN_BORON_PPM);
        borated.step(); // one tick to close the balance with boron in the coolant
        double boratedRho = borated.getReactivityDkOverK();
        borated.getKinetics().initialiseSubcritical(boratedRho);
        borated.settleInstruments();
        double boratedCps = TransientHarness.averageCountRate(borated);

        Check.note("clean: rho %+.5f, %.4E cps; %.0f ppm boron: rho %+.5f, %.4E cps",
                cleanRho, cleanCps, PhysicalConstants.SLC_SHUTDOWN_BORON_PPM,
                boratedRho, boratedCps);
        Check.lessThan(cleanRho, boratedRho, "boron must make reactivity more negative");
        Check.lessThan(cleanCps, boratedCps, "boron must lower the count rate");
        Check.finiteAndPositive(boratedCps, "borated count rate");
    }
}
