package dev.bwr.core;

import dev.bwr.core.fuel.CoreLoading;
import dev.bwr.core.fuel.FuelType;
import dev.bwr.core.harness.TransientHarness;
import dev.bwr.core.nodal.FluxSolution;
import dev.bwr.core.nodal.FluxSolveExecutor;
import dev.bwr.core.nodal.NodalFluxSolver;

/**
 * The nodal refresh run off the stepping thread ({@link ReactorCore.NodalRefresh}): the mod
 * iterates the 1 Hz solve on a worker and applies its answer a fixed number of ticks later.
 *
 * <p>Three things are pinned here. The split solve (begin, run anywhere, adopt) gives the
 * answer {@link NodalFluxSolver#solve()} gives, bit for bit. A core that iterates on the pool
 * and a core that iterates inline, both applying on the same later tick, stay identical,
 * bit for bit, through a minute of burnup and rod motion: the outcome never depends on the
 * worker's timing. And a refresh in flight survives what a block entity does to its core
 * (a save, a discard) without an exception.
 */
public final class DeferredNodalRefreshTest {
    private DeferredNodalRefreshTest() {
    }

    private static void sameSolution(FluxSolution expected, FluxSolution actual, String what) {
        sameArray(expected.assemblyWeights(), actual.assemblyWeights(), what + " assembly weights");
        sameArray(expected.assemblyFlux(), actual.assemblyFlux(), what + " assembly flux");
        sameArray(expected.nodeFlux(), actual.nodeFlux(), what + " node flux");
        sameArray(expected.axialShape(), actual.axialShape(), what + " axial shape");
        sameArray(expected.rodFluxWeights(), actual.rodFluxWeights(), what + " rod flux weights");
        Check.exactly(expected.multiplicationFactor(), actual.multiplicationFactor(), what + " k");
        Check.exactly(expected.nonLeakageProbability(), actual.nonLeakageProbability(), what + " non-leakage");
    }

    private static void sameArray(double[] expected, double[] actual, String what) {
        Check.exactly(expected.length, actual.length, what + " length");
        for (int i = 0; i < expected.length; i++) {
            if (Double.doubleToRawLongBits(expected[i]) != Double.doubleToRawLongBits(actual[i])) {
                Check.fail("%s differ at %d: %s vs %s", what, i, expected[i], actual[i]);
            }
        }
    }

    /** begin + run (on another thread) + adopt is solve(), and a stale begin is refused. */
    public static void test01_splitSolveIsTheSameSolve() throws Exception {
        CoreConfig config = new CoreConfig();
        CoreLoading a = ReactorCore.defaultCoreLoading(config, FuelType.LEU);
        CoreLoading b = ReactorCore.defaultCoreLoading(config, FuelType.LEU);
        NodalFluxSolver plain = new NodalFluxSolver(config, a);
        NodalFluxSolver split = new NodalFluxSolver(config, b);
        double[] rods = new double[config.controlRodCount];
        for (int round = 0; round < 3; round++) {
            for (int r = 0; r < rods.length; r++) {
                rods[r] = (r * 7 + round * 5) % 25;
            }
            for (NodalFluxSolver solver : new NodalFluxSolver[]{plain, split}) {
                solver.setRodPositions(rods);
                solver.setVoidProfile(0.2 + 0.05 * round, 0.6);
            }
            FluxSolution expected = plain.solve();
            FluxSolution before = split.lastSolution();
            NodalFluxSolver.Deferred deferred = split.beginSolve();
            Check.isTrue(split.lastSolution() == before, "a begun solve changed the answer before its adopt");
            Thread worker = new Thread(deferred::run, "test-flux");
            worker.start();
            worker.join();
            Check.isTrue(split.adopt(deferred), "round %d: the deferred solve was refused", round);
            sameSolution(expected, split.lastSolution(), "round " + round);
            // The memo after an adopt is the memo after a solve: the same problem again
            // comes straight back with the same answer and no iterations.
            FluxSolution again = split.solve();
            Check.exactly(0, again.iterations(), "round " + round + " memo iterations after adopt");
            sameSolution(expected, again, "round " + round + " memo after adopt");
        }
        NodalFluxSolver.Deferred stale = split.beginSolve();
        stale.run();
        split.solve();
        Check.isFalse(split.adopt(stale), "a deferred solve was adopted after a newer solve");
    }

    private static ReactorCore movingCore(ReactorCore.NodalRefresh mode) {
        ReactorCore core = TransientHarness.ratedCore();
        core.setNodalRefresh(mode);
        return core;
    }

    private static TransientHarness.PlantOperator operatorFor(ReactorCore core) {
        TransientHarness.PlantOperator operator = new TransientHarness.PlantOperator(core);
        operator.setPressureRegulatorEnabled(true);
        operator.setLevelControlEnabled(true);
        operator.setRodControlEnabled(true);
        return operator;
    }

    /** Pool and inline, same apply tick: identical through a minute of burnup, flow and rod changes. */
    public static void test02_poolAndInlineStayIdentical() {
        ReactorCore inline = movingCore(ReactorCore.NodalRefresh.DEFERRED_INLINE);
        ReactorCore pooled = movingCore(ReactorCore.NodalRefresh.DEFERRED_ASYNC);
        var inlineOperator = operatorFor(inline);
        var pooledOperator = operatorFor(pooled);
        long tasksBefore = FluxSolveExecutor.tasksRun();
        int pendingTicks = 0;
        double dt = inline.getConfig().tickSeconds;
        for (int tick = 0; tick < 1200; tick++) {
            if (tick == 200) {
                inline.setRecirculationFlowFraction(0.8);
                pooled.setRecirculationFlowFraction(0.8);
            }
            if (tick == 700) {
                inline.setRecirculationFlowFraction(0.95);
                pooled.setRecirculationFlowFraction(0.95);
            }
            inlineOperator.tick();
            pooledOperator.tick();
            inline.step(dt);
            pooled.step(dt);
            if (pooled.hasPendingNodalRefresh()) {
                pendingTicks++;
            }
            Check.exactly(inline.hasPendingNodalRefresh(), pooled.hasPendingNodalRefresh(),
                    "refresh in flight at tick " + tick);
        }
        long tasks = FluxSolveExecutor.tasksRun() - tasksBefore;
        Check.note("60 s: %d solves iterated on the pool (%d threads), %d ticks with a refresh in flight; "
                        + "power %.9f vs %.9f, %.6f vs %.6f psig",
                tasks, FluxSolveExecutor.THREADS, pendingTicks, inline.getThermalPowerMW(), pooled.getThermalPowerMW(),
                inline.getPressurePsig(), pooled.getPressurePsig());
        Check.isTrue(tasks >= 30, "only %d solves ran on the pool", tasks);
        Check.isTrue(pendingTicks > 0, "no refresh was ever in flight");
        ReactorStateRoundTripTest.assertStatesIdentical(inline.toState(), pooled.toState(),
                "60 s deferred inline against pooled");
        sameSolution(inline.getFluxSolution(), pooled.getFluxSolution(), "final flux");
        sameArray(inline.getCoreLoading().powerWeights(), pooled.getCoreLoading().powerWeights(), "final power weights");
    }

    /** A refresh in flight: save it, drop it, keep stepping; no exception, and IMMEDIATE is the default. */
    public static void test03_saveAndDiscardMidFlight() {
        Check.isTrue(new ReactorCore(new CoreConfig()).getNodalRefresh() == ReactorCore.NodalRefresh.IMMEDIATE,
                "a core does not refresh immediately by default");
        ReactorCore core = movingCore(ReactorCore.NodalRefresh.DEFERRED_ASYNC);
        int guard = 0;
        while (!core.hasPendingNodalRefresh() && guard++ < 100) {
            core.step();
        }
        Check.isTrue(core.hasPendingNodalRefresh(), "no refresh started in 100 ticks");
        ReactorState saved = core.toState();
        ReactorCore restored = new ReactorCore(new CoreConfig());
        restored.setNodalRefresh(ReactorCore.NodalRefresh.DEFERRED_ASYNC);
        restored.fromState(saved);
        Check.isFalse(restored.hasPendingNodalRefresh(), "a restored core has a refresh in flight");
        core.discardPendingNodalRefresh();
        Check.isFalse(core.hasPendingNodalRefresh(), "discard left a refresh in flight");
        for (int i = 0; i < 100; i++) {
            core.step();
            restored.step();
        }
        core.setNodalRefresh(ReactorCore.NodalRefresh.IMMEDIATE);
        Check.isFalse(core.hasPendingNodalRefresh(), "IMMEDIATE left a refresh in flight");
        core.step();
        Check.isTrue(Double.isFinite(core.getThermalPowerMW()) && Double.isFinite(restored.getThermalPowerMW()),
                "power is not finite after a discarded refresh");
    }
}
