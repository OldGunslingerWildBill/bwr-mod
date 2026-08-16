package dev.bwr.core;

import dev.bwr.core.harness.TransientHarness;

import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;

/**
 * Acceptance test 9: {@code toState()} / {@code fromState()} must preserve every
 * field exactly.
 *
 * <p>One record serves three consumers — NBT persistence, client sync and the
 * CC:Tweaked peripheral — so a field that survives the trip approximately is a
 * field that drifts a little every chunk reload. A reactor mid-transient has to
 * resume mid-transient: walking away suspends an accident, it does not cancel
 * it. The criterion here is therefore bit-for-bit equality, not agreement to
 * some tolerance.
 */
public final class ReactorStateRoundTripTest {

    private ReactorStateRoundTripTest() {
    }

    /**
     * Drive a core into a thoroughly awkward state — mid-scram, borated, xenon
     * transient in progress, level off normal, pressure off rated — snapshot it,
     * restore it into a different instance, and snapshot again.
     */
    public static void test01_roundTripPreservesEveryFieldExactly() {
        ReactorCore original = interestingCore();
        ReactorState snapshot = original.toState();

        ReactorCore restored = new ReactorCore(new CoreConfig());
        restored.setBurnupEnabled(false);
        restored.fromState(snapshot);
        ReactorState afterRestore = restored.toState();

        Check.note("snapshot at t = %.2f s: n = %.6E, decay %.6f, %.2f psig, void %.5f, "
                        + "level %.3f in, %.1f ppm boron",
                snapshot.elapsedSeconds(), snapshot.neutronPower(), snapshot.decayHeatFraction(),
                snapshot.pressurePsig(), snapshot.voidFraction(), snapshot.waterLevelIn(),
                snapshot.boronPpm());
        noteFluxShapeReconstruction(original, restored);
        assertStatesIdentical(snapshot, afterRestore, "round trip through fromState");
    }

    /**
     * Record what the restore rebuilt against what it read back, so that a
     * failure in the reactivity component carries its cause in the transcript
     * instead of just an ulp.
     *
     * <p>{@code reactivityTotal} is a derived readout: {@code toState()} closes
     * the reactivity balance so that the rho in the record can be reproduced from
     * the state beside it. Closing it needs the per-rod flux weights, and those
     * are an output of the nodal solve, which runs at 1 Hz — so the weights a
     * snapshot is closed on are up to a second old while a restore-time re-solve
     * has this second's. Both of the inputs that used to make those two disagree
     * are now components of the record: the void model's lagged core inlet
     * enthalpy, which drives the axial profile the solve runs on, and the frozen
     * weights themselves. The first line below is therefore the axial profile
     * agreeing, and the second is the tripwire that matters — <b>the weights must
     * come back identical, and "N of 177 differ" for any non-zero N means
     * something has gone back to re-solving for them.</b>
     */
    private static void noteFluxShapeReconstruction(ReactorCore original, ReactorCore restored) {
        double[] a = original.getRodWorth().getRodFluxWeights();
        double[] b = restored.getRodWorth().getRodFluxWeights();
        int differing = 0;
        double worstRelative = 0.0;
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            if (Double.compare(a[i], b[i]) != 0) {
                differing++;
                worstRelative = Math.max(worstRelative,
                        Math.abs(a[i] - b[i]) / Math.max(1.0e-300, Math.abs(a[i])));
            }
        }
        Check.note("void axial profile the nodal solve ran on: boiling boundary %.6g vs %.6g, "
                        + "exit void %.6g vs %.6g (inlet subcooling %.4g vs %.4g kJ/kg — "
                        + "lagged, and restored from the record rather than reconstructed)",
                original.getVoidModel().getBoilingBoundaryFraction(),
                restored.getVoidModel().getBoilingBoundaryFraction(),
                original.getVoidModel().getExitVoidFraction(),
                restored.getVoidModel().getExitVoidFraction(),
                original.getCoreInletSubcoolingKJPerKg(),
                restored.getCoreInletSubcoolingKJPerKg());
        Check.note("per-rod flux weights the balance was closed on: %d of %d differ, "
                        + "worst %.3g relative; k_eff %.12g vs %.12g",
                differing, a.length, worstRelative,
                original.getFluxSolution() == null
                        ? Double.NaN : original.getFluxSolution().multiplicationFactor(),
                restored.getFluxSolution() == null
                        ? Double.NaN : restored.getFluxSolution().multiplicationFactor());
    }

    /**
     * The restored core must carry on identically, not merely look the same for
     * one frame. A quantity that is reported but not actually restored shows up
     * as the two cores diverging as soon as they are stepped, which a
     * single-frame comparison would never catch.
     *
     * <p>Actuator commands are copied across by hand here, exactly as the block
     * entity does. Valve positions, pump demands and injection rates are the
     * satellite machines' own persisted state ({@code SPEC.md} section 11), not
     * the core's — {@link ReactorState} is the physics snapshot and deliberately
     * has no field for where the turbine control valves are sitting.
     */
    public static void test02_restoredCoreContinuesIdentically() {
        // Snapshot a core sitting at its equilibrium. The ordinary case: a plant
        // is saved far more often at a steady operating point than mid-excursion,
        // and at equilibrium the lagged internals are where a restore would put
        // them anyway — so this passed even while the void model's core inlet
        // enthalpy was still being reconstructed rather than read back. It is
        // test06 that exercises the case where that distinction bites.
        ReactorCore original = TransientHarness.ratedCore();
        original.setBurnupEnabled(false);

        ReactorCore restored = new ReactorCore(new CoreConfig());
        restored.setBurnupEnabled(false);
        restored.fromState(original.toState());
        copyActuatorCommands(original, restored);

        for (int i = 0; i < 1200; i++) { // 60 s
            original.step();
            restored.step();
        }

        ReactorState a = original.toState();
        ReactorState b = restored.toState();
        Check.note("after 60 s of identical stepping: n = %.9E vs %.9E, %.4f vs %.4f psig",
                a.neutronPower(), b.neutronPower(), a.pressurePsig(), b.pressurePsig());
        assertStatesIdentical(a, b, "60 s of stepping after a restore");
    }

    /**
     * Restoring in the middle of a transient is the harder case, because it is
     * where every quantity that is <i>lagged</i> rather than instantaneous is
     * somewhere a reconstruction cannot guess.
     *
     * <p>This test used to document a known loss: the void model's core inlet
     * enthalpy was a lagged internal with no field in the record, so it came back
     * at the equilibrium for the restored operating point rather than wherever the
     * recirculation transit had left it, and the flux shape and rho that fell out
     * of it came back differing. <b>That is fixed</b> — it is a component now, as
     * are the frozen per-rod flux weights that used to be re-solved from it. A
     * mid-transient restore is bit-identical to the core it came from at the
     * instant it happens.
     *
     * <p>What is left over is not a physics field. The record is the core's
     * snapshot and deliberately does not own everything a running core holds:
     * valve positions and injection rates belong to the satellite machines
     * (copied by hand below, exactly as the block entity does), the pressure
     * boundary's accumulated damage and the operator's standing rod demand have
     * tags of their own, and the detectors' signal-conditioning lags are filters
     * that re-settle rather than resume. The two cores therefore step from an
     * identical record but not from an identical machine, and on this scenario
     * they separate in the last bit eleven ticks later. What this test pins down
     * is that the separation then <em>shrinks</em>. A restore that diverged
     * instead would fail here even with every component round-tripping perfectly.
     */
    public static void test06_midTransientRestoreConvergesRatherThanDiverging() {
        ReactorCore original = interestingCore();
        ReactorState snapshot = original.toState();

        ReactorCore restored = new ReactorCore(new CoreConfig());
        restored.setBurnupEnabled(false);
        restored.fromState(snapshot);
        copyActuatorCommands(original, restored);
        // The scram latch is plant state and fromState now restores it, so the
        // two cores already agree about it. Clearing it on the original here —
        // which this test used to do, with a comment saying fromState cleared
        // the latch — would now be the thing that makes them differ.
        Check.exactly(original.isScramActive(), restored.isScramActive(),
                "the restored core must come back with the same scram latch");

        // Sample the divergence on a fixed schedule so that "converging" is a
        // statement about the shape of the curve rather than about its maximum.
        //
        // Comparing the last sample against the running maximum — which this
        // test used to do — cannot fail: the last sample is one of the samples
        // the maximum was taken over, so it is <= the maximum by construction,
        // and a restore that separated monotonically all the way to the 120 s
        // mark would have passed while reporting that it converged.
        final int ticks = 2400;             // 120 s
        final int earlySampleTick = 200;    // 10 s, after the restore transient starts
        double worstRelativeDifference = 0.0;
        double earlyDifference = Double.NaN;
        double lateWindowSum = 0.0;
        double earlyWindowSum = 0.0;
        int lateWindowSamples = 0;
        int earlyWindowSamples = 0;
        for (int i = 1; i <= ticks; i++) {
            original.step();
            restored.step();
            double a = original.getTotalPowerFractionOfRated();
            double b = restored.getTotalPowerFractionOfRated();
            double difference = Math.abs(a - b) / Math.max(1.0e-12, a);
            worstRelativeDifference = Math.max(worstRelativeDifference, difference);
            if (i == earlySampleTick) {
                earlyDifference = difference;
            }
            // Two 10 s windows, one straddling the early sample and one at the
            // very end, so a single lucky tick cannot decide the verdict.
            if (i > earlySampleTick - 200 && i <= earlySampleTick) {
                earlyWindowSum += difference;
                earlyWindowSamples++;
            }
            if (i > ticks - 200) {
                lateWindowSum += difference;
                lateWindowSamples++;
            }
        }
        double finalDifference = Math.abs(original.getTotalPowerFractionOfRated()
                - restored.getTotalPowerFractionOfRated())
                / original.getTotalPowerFractionOfRated();
        double earlyMean = earlyWindowSum / earlyWindowSamples;
        double lateMean = lateWindowSum / lateWindowSamples;

        Check.note("mid-transient restore: worst power difference %.4f%%, at 10 s %.4f%%, "
                        + "after 120 s %.4f%%",
                100.0 * worstRelativeDifference, 100.0 * earlyDifference, 100.0 * finalDifference);
        Check.note("mean difference over the first 10 s %.4f%% against the last 10 s %.4f%% "
                        + "— a factor of %.1f",
                100.0 * earlyMean, 100.0 * lateMean, earlyMean / Math.max(1.0e-30, lateMean));
        Check.note("inlet subcooling %.2f vs %.2f kJ/kg at the end",
                original.getCoreInletSubcoolingKJPerKg(), restored.getCoreInletSubcoolingKJPerKg());
        Check.lessThan(0.05, worstRelativeDifference,
                "a mid-transient restore must not change the trajectory materially");
        Check.finite(earlyDifference, "difference at the 10 s sample");
        // The real content: the curve must be coming down. Both the single
        // fixed sample and the ten-second means have to shrink, so a restore
        // that separated monotonically fails here instead of being reported as
        // convergent.
        Check.lessThan(earlyDifference, finalDifference,
                "the difference 120 s after a mid-transient restore must be below the "
                        + "difference 10 s after it — it must converge, not grow");
        Check.lessThan(earlyMean, lateMean,
                "the mean difference over the last 10 s must be below the mean over the first 10 s");
    }

    /**
     * Valve positions, pump demands and injection rates belong to the satellite
     * machines, so a restore has to put them back from their own persistence.
     * This is the block entity's job, done here by hand.
     */
    private static void copyActuatorCommands(ReactorCore from, ReactorCore to) {
        to.setTurbineSteamFlowKgPerS(from.getTurbineSteamFlowKgPerS());
        to.setBypassSteamFlowKgPerS(from.getBypassSteamFlowKgPerS());
        to.setReliefSteamFlowKgPerS(from.getReliefSteamFlowKgPerS());
        to.setFeedwaterFlowKgPerS(from.getFeedwaterFlowKgPerS());
        to.setFeedwaterTemperatureC(from.getFeedwaterTemperatureC());
        to.setInjectionFlowKgPerS(from.getInjectionFlowKgPerS());
        to.setCoreSprayFlowKgPerS(from.getCoreSprayFlowKgPerS());
        to.setBoronInjectionPpmPerMinute(from.getBoronInjectionPpmPerMinute());
        to.setRecirculationFlowFraction(from.getRecirculationFlowFractionDemand());
        to.setNeutronSourceStrengthPerSecond(from.getNeutronSourceStrengthPerSecond());
        to.setCrdPowered(from.isCrdPowered());
        to.setCrdWaterSupplied(from.isCrdWaterSupplied());
    }

    /** A snapshot of a shutdown core and of a rated core must both round trip. */
    public static void test03_roundTripAtBothEndsOfTheRange() {
        for (String label : new String[]{"hot shutdown", "rated"}) {
            ReactorCore core = label.equals("rated")
                    ? TransientHarness.ratedCore() : TransientHarness.shutdownCore();
            core.setBurnupEnabled(false);
            ReactorState snapshot = core.toState();

            ReactorCore restored = new ReactorCore(new CoreConfig());
            restored.setBurnupEnabled(false);
            restored.setNeutronSourceStrengthPerSecond(snapshot.sourceStrength());
            restored.fromState(snapshot);
            Check.note("--- %s", label);
            noteFluxShapeReconstruction(core, restored);
            assertStatesIdentical(snapshot, restored.toState(), "round trip at " + label);
            Check.note("%s round trip clean: n = %.6E, %.2f psig, void %.5f, level %.3f in",
                    label, snapshot.neutronPower(), snapshot.pressurePsig(),
                    snapshot.voidFraction(), snapshot.waterLevelIn());
        }
    }

    /**
     * A snapshot whose rod count does not match the core must be refused rather
     * than silently truncated. The multiblock can legitimately change size
     * between saves, and quietly dropping rods would be a stuck-rod bug that
     * only shows up during a scram.
     */
    public static void test04_mismatchedRodCountIsRefused() {
        ReactorCore core = TransientHarness.shutdownCore();
        ReactorState snapshot = core.toState();
        // The canonical constructor and nothing else. There is deliberately no
        // shorter overload, so this call site breaks when a component is added —
        // which is the point: see the class comment on ReactorState.
        ReactorState wrongSize = new ReactorState(
                snapshot.neutronPower(), snapshot.precursors(), snapshot.sourceStrength(),
                snapshot.decayHeatFraction(), snapshot.decayGroups(), snapshot.fuelTempC(),
                snapshot.cladTempC(), snapshot.peakCladTempC(), snapshot.peakFuelTempC(),
                snapshot.oxidationFraction(),
                snapshot.hydrogenKg(), snapshot.protectiveOxideArealKgPerM2(),
                snapshot.coolantTempC(), snapshot.pressurePsig(), snapshot.voidFraction(),
                snapshot.coreInletEnthalpyKJPerKg(),
                snapshot.coreFlowFraction(), snapshot.waterLevelIn(), snapshot.xenon(),
                snapshot.iodine(), snapshot.boronPpm(), snapshot.burnupMwdPerTonne(),
                new int[3], new double[3], snapshot.reactivityTotal(), snapshot.betaEff(),
                snapshot.promptLifetime(), new double[3],
                snapshot.fuelExcessReactivityDkOverK(),
                snapshot.dopplerCoefficientPerCAtAnchor(),
                snapshot.elapsedSeconds(), snapshot.scramActive(),
                snapshot.intermediateRangeMonitorRanges());

        boolean refused = false;
        try {
            core.fromState(wrongSize);
        } catch (IllegalArgumentException expected) {
            refused = true;
            Check.note("refused as it should: %s", expected.getMessage());
        }
        Check.isTrue(refused, "a state with the wrong rod count must be refused");
    }

    /** The record must defensively copy, or a peripheral could mutate live core state. */
    public static void test05_stateArraysAreDefensivelyCopied() {
        ReactorCore core = TransientHarness.ratedCore();
        ReactorState snapshot = core.toState();

        double[] precursors = snapshot.precursors();
        int[] notches = snapshot.rodNotches();
        double[] charges = snapshot.accumulatorCharge();
        precursors[0] = -12345.0;
        notches[0] = 99;
        charges[0] = -1.0;

        Check.isFalse(snapshot.precursors()[0] == -12345.0, "precursors must be copied out");
        Check.isFalse(snapshot.rodNotches()[0] == 99, "rod notches must be copied out");
        Check.isFalse(snapshot.accumulatorCharge()[0] == -1.0, "accumulator charges must be copied out");
        Check.exactly(core.getRodNotchIndex(0), snapshot.rodNotches()[0], "live core untouched");
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    /**
     * A core somewhere genuinely awkward: partway through a scram, with boron in
     * the coolant, xenon out of equilibrium, level off setpoint and pressure off
     * rated. Every state variable is somewhere other than its default.
     */
    private static ReactorCore interestingCore() {
        ReactorCore core = TransientHarness.ratedCore();
        core.setBurnupEnabled(false);
        TransientHarness.PlantOperator operator = new TransientHarness.PlantOperator(core);
        operator.setPressureRegulatorEnabled(true);
        operator.setLevelControlEnabled(true);
        operator.setRodControlEnabled(false);

        TransientHarness.runSeconds(core, operator, 30.0, null);
        core.setRecirculationFlowFraction(0.65);
        TransientHarness.runSeconds(core, operator, 40.0, null);
        core.setBoronInjectionPpmPerMinute(PhysicalConstants.SLC_INJECTION_PPM_PER_MIN_MAX);
        core.scram();
        // Stop mid-stroke: rods partly in, accumulators partly spent.
        TransientHarness.runSeconds(core, operator, 1.5, null);
        // Then let the plant drift with no operator at all, so pressure, level and
        // every lagged internal state end up somewhere arbitrary.
        TransientHarness.runSeconds(core, null, 12.0, null);
        return core;
    }

    /**
     * Bit-for-bit comparison of every component of the record, <b>found by
     * reflection rather than named by hand</b>.
     *
     * <p>This used to be twenty-seven hand-written lines, and a hand-maintained
     * list is how {@code hydrogenKg} and the protective oxide film went missing:
     * they were added to {@code FuelThermal}, never added to the record, and no
     * persistence test noticed. A list that has to be remembered will eventually
     * not be. Walking {@code getRecordComponents()} cannot fall behind the record
     * — a component added tomorrow is compared today.
     *
     * <p>The type dispatch below is deliberately <b>exhaustive rather than
     * lenient</b>: an unknown component type throws rather than being skipped,
     * because a comparator that silently ignores what it does not understand is
     * exactly the hand-maintained list wearing a reflective disguise.
     * {@link #test07_theComparatorCoversEveryRecordComponent} proves the dispatch
     * really does reach every component by perturbing each one in turn.
     */
    public static void assertStatesIdentical(ReactorState expected, ReactorState actual, String what) {
        RecordComponent[] components = ReactorState.class.getRecordComponents();
        for (RecordComponent component : components) {
            compareComponent(component, read(component, expected), read(component, actual),
                    what + ": " + component.getName());
        }
    }

    private static Object read(RecordComponent component, ReactorState state) {
        try {
            return component.getAccessor().invoke(state);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "cannot read ReactorState." + component.getName(), e);
        }
    }

    /**
     * Compare one component by its declared type. Every branch is bit-for-bit;
     * a persistence round trip has no tolerance to spend.
     */
    private static void compareComponent(RecordComponent component,
                                         Object expected, Object actual, String label) {
        Class<?> type = component.getType();
        if (type == double.class) {
            Check.exactly((double) expected, (double) actual, label);
        } else if (type == int.class) {
            Check.exactly((int) expected, (int) actual, label);
        } else if (type == long.class) {
            Check.exactly((long) expected, (long) actual, label);
        } else if (type == boolean.class) {
            Check.exactly((boolean) expected, (boolean) actual, label);
        } else if (type == double[].class) {
            Check.arraysExactly((double[]) expected, (double[]) actual, label);
        } else if (type == int[].class) {
            Check.arraysExactly((int[]) expected, (int[]) actual, label);
        } else {
            throw new IllegalStateException(
                    "this comparator does not know how to compare " + type
                            + " for ReactorState." + component.getName()
                            + " — teach it, do not skip it; a component nothing compares is a "
                            + "component nothing notices has stopped round-tripping");
        }
    }

    /**
     * {@link #assertStatesIdentical} is reflective, but its <i>type dispatch</i>
     * is not: a component of a type the dispatch does not handle would be a
     * component nothing compares. This test proves the dispatch actually reaches
     * every component, by building two records that differ in exactly one
     * component and requiring the comparator to say so — for each component in
     * turn.
     *
     * <p>Only a {@link Check.Failure} counts as noticing. Anything else escaping
     * the comparator — the {@code IllegalStateException} it throws for a type it
     * has not been taught, above all — propagates and fails this test loudly
     * rather than being miscounted as a catch.
     *
     * <p>It also guards the rule {@link ReactorState}'s own class comment states:
     * there is exactly one constructor and there must never be a second. A
     * shorter compatibility overload is what let the NBT reader bind to a
     * defaulted {@code scramActive} and un-scram a saved reactor.
     */
    public static void test07_theComparatorCoversEveryRecordComponent() {
        RecordComponent[] components = ReactorState.class.getRecordComponents();
        Constructor<?> canonical = canonicalConstructor(components);

        Check.exactly(1, ReactorState.class.getDeclaredConstructors().length,
                "ReactorState must declare exactly one constructor, the canonical one — a "
                        + "shorter overload silently defaults the newest components");

        List<String> uncompared = new ArrayList<>();
        for (int i = 0; i < components.length; i++) {
            Object[] baseline = defaultArguments(components);
            Object[] perturbed = defaultArguments(components);
            perturbed[i] = perturb(components[i].getType(), components[i].getName());

            ReactorState a = instantiate(canonical, baseline);
            ReactorState b = instantiate(canonical, perturbed);

            boolean noticed = false;
            try {
                assertStatesIdentical(a, b, "comparator coverage probe");
            } catch (Check.Failure expected) {
                noticed = true;
            }
            if (!noticed) {
                uncompared.add(components[i].getName());
            }
        }

        Check.note("perturbed each of the %d ReactorState components in turn; the reflective "
                + "comparator caught every one", components.length);
        Check.isTrue(uncompared.isEmpty(),
                "assertStatesIdentical does not compare these ReactorState components, so "
                        + "nothing would notice if they stopped round-tripping: %s", uncompared);
    }

    private static Constructor<?> canonicalConstructor(RecordComponent[] components) {
        Class<?>[] types = new Class<?>[components.length];
        for (int i = 0; i < components.length; i++) {
            types[i] = components[i].getType();
        }
        try {
            return ReactorState.class.getDeclaredConstructor(types);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("ReactorState has no canonical constructor", e);
        }
    }

    private static ReactorState instantiate(Constructor<?> canonical, Object[] arguments) {
        try {
            return (ReactorState) canonical.newInstance(arguments);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot construct a probe ReactorState", e);
        }
    }

    /** A baseline value per component type. Physically meaningless on purpose. */
    private static Object[] defaultArguments(RecordComponent[] components) {
        Object[] arguments = new Object[components.length];
        for (int i = 0; i < components.length; i++) {
            Class<?> type = components[i].getType();
            if (type == double.class) {
                arguments[i] = 1.0;
            } else if (type == int.class) {
                arguments[i] = 1;
            } else if (type == long.class) {
                arguments[i] = 1L;
            } else if (type == boolean.class) {
                // false, so that perturb() can hand back the only other value.
                arguments[i] = Boolean.FALSE;
            } else if (type == double[].class) {
                arguments[i] = new double[]{1.0, 2.0};
            } else if (type == int[].class) {
                arguments[i] = new int[]{1, 2};
            } else {
                throw new IllegalStateException(
                        "this probe does not know how to build a " + type
                                + " for ReactorState." + components[i].getName()
                                + " — teach it, do not delete the test");
            }
        }
        return arguments;
    }

    /**
     * A value guaranteed to differ from the baseline for the same type.
     *
     * <p>A {@code boolean} has exactly two values, so the baseline must be
     * {@code false} for this to be a perturbation at all — which is why
     * {@link #defaultArguments} pins it there rather than picking something
     * "interesting". {@code scramActive} is the component this exists for.
     */
    private static Object perturb(Class<?> type, String componentName) {
        if (type == double.class) {
            return 99.0;
        }
        if (type == int.class) {
            return 99;
        }
        if (type == long.class) {
            return 99L;
        }
        if (type == boolean.class) {
            return Boolean.TRUE;
        }
        if (type == double[].class) {
            return new double[]{7.0, 8.0};
        }
        if (type == int[].class) {
            return new int[]{7, 8};
        }
        throw new IllegalStateException("no perturbation known for " + type
                + " (ReactorState." + componentName + ") — teach it, do not delete the test");
    }
}
