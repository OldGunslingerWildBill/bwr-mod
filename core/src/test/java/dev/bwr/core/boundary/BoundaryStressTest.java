package dev.bwr.core.boundary;

import dev.bwr.core.Check;
import dev.bwr.core.PhysicalConstants;
import dev.bwr.core.ReactorCore;
import dev.bwr.core.harness.TransientHarness;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The overpressure damage model — {@code SPEC.md} section 7.
 *
 * <p>These tests exist because this is the only automatic consequence in the
 * entire plant. Safety and relief valves are player-actuated, so nothing
 * relieves pressure on its own and nothing scrams on anything; if this model is
 * silently wrong, an isolated reactor sits at three times design pressure
 * forever and the project's claim that failures are traceable to neglect is
 * false.
 *
 * <p>Four properties are load bearing and each has a test here: nothing at all
 * happens below the vessel design pressure, the accumulation rate really does
 * climb with overpressure rather than being a disguised threshold, the
 * component that broke is the component that was abused, and a plant built with
 * reactor internal pumps genuinely outlives one built with external loops.
 */
public final class BoundaryStressTest {

    private BoundaryStressTest() {
    }

    private static final double TICK = 0.05;

    /** Hold a boundary at a fixed pressure and return how many seconds it took to break. */
    private static double runToFailure(BoundaryStress boundary, double pressurePsig,
                                       double maxSeconds) {
        double t = 0.0;
        while (t < maxSeconds) {
            if (boundary.step(pressurePsig, TICK) != null) {
                return t;
            }
            t += TICK;
        }
        return Double.NaN;
    }

    private static BoundaryStress boundaryWithSeed(PlantConfiguration configuration, long seed) {
        BoundaryStress boundary = new BoundaryStress(configuration);
        boundary.setRandomSeed(seed);
        return boundary;
    }

    // =================================================================

    /**
     * <b>Nothing below 1250 psig.</b> Vessel design pressure is the envelope the
     * plant was built to sit inside, so an hour at 1249 psig — and a normal
     * operating day at 1025 — must leave every component untouched, bit for bit.
     * Any accumulation here would mean a plant wears out simply by running.
     */
    public static void test01_noDamageBelowDesignPressure() {
        BoundaryStress boundary = new BoundaryStress();

        for (double psig : new double[]{0.0, 1025.0, 1103.0, 1249.0, 1250.0}) {
            BoundaryStress b = new BoundaryStress();
            for (int i = 0; i < 20 * 3600; i++) { // one hour
                b.step(psig, TICK);
            }
            for (BoundaryComponent component : BoundaryComponent.values()) {
                Check.exactly(0.0, b.getStress(component),
                        String.format(Locale.ROOT, "stress on %s after an hour at %.0f psig",
                                component.displayName(), psig));
            }
            Check.exactly(0.0, b.getSecondsAboveDesignPressure(),
                    "seconds above design pressure at " + psig + " psig");
            Check.isFalse(b.hasFailed(), "nothing may break at %.0f psig", psig);
        }
        Check.note("one hour each at 0 / 1025 / 1103 / 1249 / 1250 psig: stress exactly 0.0 on all "
                + BoundaryComponent.values().length + " components");

        // And the rate function itself is exactly zero across the whole band.
        for (double psig = 0.0; psig <= 1250.0; psig += 25.0) {
            Check.exactly(0.0, BoundaryStress.stressRatePerUnitWeight(psig),
                    "stress rate at " + psig + " psig");
        }
        Check.greaterThan(0.0, BoundaryStress.stressRatePerUnitWeight(1250.001),
                "stress rate just above design pressure");
        Check.note("rate is exactly zero from 0 to 1250 psig and positive immediately above it");

        // One tick over design pressure and back does leave a permanent mark,
        // which is the point of the model: damage is remembered.
        boundary.step(1300.0, TICK);
        double after = boundary.getStress(BoundaryComponent.MAIN_STEAM_LINE);
        Check.greaterThan(0.0, after, "one tick at 1300 psig must leave a mark");
        for (int i = 0; i < 20 * 600; i++) {
            boundary.step(1025.0, TICK);
        }
        Check.exactly(after, boundary.getStress(BoundaryComponent.MAIN_STEAM_LINE),
                "stress must not heal over ten minutes back at rated pressure");
        Check.note("one 0.05 s excursion to 1300 psig leaves %.3e of stress, unchanged after "
                + "ten minutes back at 1025 psig — metal does not un-fatigue", after);
    }

    /**
     * <b>Rate scales with overpressure.</b> The model must be a wear rate and
     * not a threshold in disguise, so the same exposure time at a higher
     * pressure must do strictly more damage, and it must do so as the square of
     * the fractional overpressure rather than linearly.
     */
    public static void test02_stressAccumulatesFasterAtHigherOverpressure() {
        // The rate law itself, across the whole range the vessel can reach.
        double previousRate = -1.0;
        for (double psig : new double[]{1250.001, 1275.0, 1325.0, 1375.0, 1500.0, 2000.0, 3315.0}) {
            double rate = BoundaryStress.stressRatePerUnitWeight(psig);
            double x = (psig - BoundaryStress.DESIGN_PRESSURE_PSIG) / BoundaryStress.CODE_MARGIN_PSI;
            Check.relative(Math.pow(x, BoundaryStress.STRESS_EXPONENT)
                            / BoundaryStress.SECONDS_TO_UNIT_STRESS_AT_CODE_PRESSURE,
                    rate, 1.0e-12, "rate law at " + psig + " psig");
            Check.greaterThan(previousRate, rate, "rate must climb at " + psig + " psig");
            previousRate = rate;
            Check.note("%.3f psig: %.3e stress per second per unit weight "
                            + "(a steam line's whole life in %.1f s)",
                    psig, rate, BoundaryStress.FAILURE_STRESS / rate);
        }

        // And the accumulation actually integrates that law. Kept below the code
        // limit and to short exposures at 1500 psig so that nothing breaks
        // mid-measurement — a broken component stops accumulating, which is
        // correct and would otherwise be mistaken for the law being wrong.
        double[] pressures = {1275.0, 1300.0, 1325.0, 1350.0, 1375.0, 1500.0};
        double exposure = 60.0;
        double previous = -1.0;

        for (double psig : pressures) {
            BoundaryStress boundary = new BoundaryStress();
            for (int i = 0; i < (int) (exposure / TICK); i++) {
                boundary.step(psig, TICK);
            }
            double steam = boundary.getStress(BoundaryComponent.MAIN_STEAM_LINE);
            double x = (psig - BoundaryStress.DESIGN_PRESSURE_PSIG) / BoundaryStress.CODE_MARGIN_PSI;
            double expected = Math.pow(x, BoundaryStress.STRESS_EXPONENT) * exposure
                    / BoundaryStress.SECONDS_TO_UNIT_STRESS_AT_CODE_PRESSURE;

            Check.note("%.0f psig for %.0f s: steam line stress %.5f (x=%.2f, x^2 law predicts %.5f)",
                    psig, exposure, steam, x, expected);
            Check.relative(expected, steam, 1.0e-9, "square-law stress at " + psig + " psig");
            Check.greaterThan(previous, steam, "stress at " + psig + " must exceed the pressure below it");
            previous = steam;
        }

        // A doubling of the fractional overpressure must quadruple the rate.
        double atHalf = BoundaryStress.stressRatePerUnitWeight(1250.0 + 0.5 * 125.0);
        double atFull = BoundaryStress.stressRatePerUnitWeight(1250.0 + 125.0);
        Check.relative(4.0, atFull / atHalf, 1.0e-12,
                "quadrupling of the rate for a doubling of overpressure");
        Check.note("rate at the code limit is %.1fx the rate halfway to it", atFull / atHalf);

        // And the exposure weights order the components correctly at any pressure.
        BoundaryStress boundary = new BoundaryStress();
        for (int i = 0; i < 20 * 300; i++) {
            boundary.step(1350.0, TICK);
        }
        Check.greaterThan(boundary.getStress(BoundaryComponent.MAIN_STEAM_LINE),
                boundary.getStress(BoundaryComponent.RECIRCULATION_LINE),
                "the recirculation line is the most exposed penetration");
        Check.lessThan(boundary.getStress(BoundaryComponent.MAIN_STEAM_LINE),
                boundary.getStress(BoundaryComponent.FEEDWATER_LINE),
                "feedwater lines are less exposed than steam lines");
        Check.lessThan(boundary.getStress(BoundaryComponent.FEEDWATER_LINE),
                boundary.getStress(BoundaryComponent.REACTOR_VESSEL_HEAD),
                "the head is the thickest section and wears slowest");
        Check.exactly(BoundaryComponent.RECIRCULATION_LINE.ordinal(),
                boundary.getMostStressedComponent().ordinal(),
                "the most stressed component on a jet pump plant");
    }

    /**
     * <b>The 1250 to 1375 band accumulates but cannot break.</b> That band is
     * the whole warning the player gets. If something could let go inside it
     * there would be no readable interval between "you have a problem" and "you
     * have a hole", and the stress readout would be pointless.
     */
    public static void test03_nothingBreaksBelowTheCodeLimit() {
        BoundaryStress boundary = new BoundaryStress();
        for (int i = 0; i < 20 * 4 * 3600; i++) { // four hours at 1374 psig
            Check.isTrue(boundary.step(1374.0, TICK) == null,
                    "nothing may break below the code limit");
        }
        Check.note("four hours at 1374 psig: steam line stress %.2f, recirculation %.2f, "
                        + "hazard %.3e per second, failures %d",
                boundary.getStress(BoundaryComponent.MAIN_STEAM_LINE),
                boundary.getStress(BoundaryComponent.RECIRCULATION_LINE),
                boundary.getFailureRatePerSecond(), boundary.getFailures().size());

        Check.greaterThan(10.0, boundary.getStress(BoundaryComponent.MAIN_STEAM_LINE),
                "four hours one psi under the code limit must do serious damage");
        Check.exactly(0.0, boundary.getFailureRatePerSecond(),
                "failure hazard below the code limit");
        Check.isFalse(boundary.hasFailed(), "nothing may break one psi below the code limit");
        Check.exactly(0.0, boundary.getSecondsAboveCodePressure(), "seconds above the code limit");

        // The damage is real, though: one second past the limit and it is armed.
        //
        // This line used to read `step(...) == null || true`, which is a constant
        // true expression: it performed the step but asserted nothing, while
        // reading in the transcript as a check. What the test actually wants of
        // that single tick is that nothing lets go on it — the whole "gradual
        // enough to be noticed" claim above depends on there being a readable
        // interval between crossing the limit and losing a pipe, and a model that
        // broke a component on the first tick across would have satisfied
        // `null || true` just as happily.
        BoundaryFailure firstTickAcross = boundary.step(1376.0, TICK);
        Check.isTrue(firstTickAcross == null,
                "nothing may break on the first tick across the code limit; got %s",
                firstTickAcross);
        Check.exactly(TICK, boundary.getSecondsAboveCodePressure(),
                "the step above the limit must be counted as time above the limit");
        Check.greaterThan(0.0, boundary.getFailureRatePerSecond(),
                "hazard becomes non-zero the moment the code limit is crossed with used-up metal");
        // And the hazard it arms has to be a hazard, not a certainty: a tick is
        // 50 ms and the whole design intent is minutes of warning, so the chance
        // of losing something on any one tick must stay far below one.
        Check.lessThan(0.01, boundary.getFailureProbabilityOver(TICK),
                "single-tick failure probability just across the code limit");
        Check.note("crossing to 1376 psig with that history arms a hazard of %.4f per second, "
                        + "which is a %.3g chance over one %.2f s tick",
                boundary.getFailureRatePerSecond(), boundary.getFailureProbabilityOver(TICK), TICK);
    }

    /**
     * <b>The abused component is the one that goes.</b> Failures are drawn at
     * random, but the draw is weighted by accumulated stress, and accumulated
     * stress is proportional to exposure. So across many independent plants the
     * recirculation line — the most exposed penetration on a jet pump plant —
     * must dominate, and the vessel head must be rare, because it wears at a
     * quarter of the rate and takes four times as long to become eligible at all.
     */
    public static void test04_failureIsWeightedTowardTheMostStressedComponent() {
        int trials = 400;
        Map<BoundaryComponent, Integer> tally = new EnumMap<>(BoundaryComponent.class);
        for (BoundaryComponent component : BoundaryComponent.values()) {
            tally.put(component, 0);
        }

        double total = 0.0;
        for (int trial = 0; trial < trials; trial++) {
            BoundaryStress boundary =
                    boundaryWithSeed(PlantConfiguration.EXTERNAL_RECIRCULATION_LOOPS, trial * 7919L + 1L);
            double t = runToFailure(boundary, 1500.0, 3600.0);
            Check.finiteAndPositive(t, "time to failure at a sustained 1500 psig, trial " + trial);
            total += t;
            BoundaryFailure failure = boundary.getLastFailure();
            tally.put(failure.component(), tally.get(failure.component()) + 1);
        }

        for (BoundaryComponent component : BoundaryComponent.values()) {
            Check.note("%-20s exposure %.2f -> %d of %d failures (%.1f%%)",
                    component.displayName(), component.exposureWeight(),
                    tally.get(component), trials, 100.0 * tally.get(component) / trials);
        }
        Check.note("mean time to first failure at a sustained 1500 psig: %.1f s", total / trials);

        int recirc = tally.get(BoundaryComponent.RECIRCULATION_LINE);
        int steam = tally.get(BoundaryComponent.MAIN_STEAM_LINE);
        int feed = tally.get(BoundaryComponent.FEEDWATER_LINE);
        int head = tally.get(BoundaryComponent.REACTOR_VESSEL_HEAD);

        Check.greaterThan(steam, recirc, "the recirculation line must fail more often than a steam line");
        Check.greaterThan(feed, steam, "a steam line must fail more often than a feedwater line");
        Check.greaterThan(head, feed, "a feedwater line must fail more often than the head");
        Check.greaterThan(0.4 * trials, recirc,
                "the most exposed component must take the clear majority share");
        Check.lessThan(0.05 * trials, head,
                "the head must almost never be the first thing to go");

        // Nothing is predetermined: the same plant driven with different seeds
        // does not always lose the same pipe.
        Check.greaterThan(0, steam, "the draw must not be deterministic — steam lines must sometimes go");
        Check.greaterThan(0, feed, "the draw must not be deterministic — feedwater lines must sometimes go");
    }

    /**
     * <b>Reactor internal pumps are a real mechanical advantage.</b> A RIP plant
     * has no external recirculation piping, so it carries one fewer — and the
     * most exposed — pressure boundary component. Two consequences must both
     * hold: it takes measurably longer to break, and it is structurally
     * incapable of the design basis large-break LOCA.
     */
    public static void test05_reactorInternalPumpsSurviveLongerThanExternalLoops() {
        int trials = 300;
        double pressure = 1500.0;

        double externalTotal = 0.0;
        double ripTotal = 0.0;
        int ripRecircFailures = 0;
        List<String> ripFailureKinds = new ArrayList<>();

        for (int trial = 0; trial < trials; trial++) {
            long seed = trial * 104_729L + 17L;

            BoundaryStress external =
                    boundaryWithSeed(PlantConfiguration.EXTERNAL_RECIRCULATION_LOOPS, seed);
            double te = runToFailure(external, pressure, 7200.0);
            Check.finiteAndPositive(te, "external-loop time to failure, trial " + trial);
            externalTotal += te;

            BoundaryStress rip = boundaryWithSeed(PlantConfiguration.REACTOR_INTERNAL_PUMPS, seed);
            double tr = runToFailure(rip, pressure, 7200.0);
            Check.finiteAndPositive(tr, "RIP time to failure, trial " + trial);
            ripTotal += tr;

            BoundaryComponent broke = rip.getLastFailure().component();
            if (broke == BoundaryComponent.RECIRCULATION_LINE) {
                ripRecircFailures++;
            }
            if (!ripFailureKinds.contains(broke.name())) {
                ripFailureKinds.add(broke.name());
            }
        }

        double externalMean = externalTotal / trials;
        double ripMean = ripTotal / trials;
        Check.note("sustained %.0f psig, %d trials each: external loops fail at %.1f s mean, "
                        + "RIP at %.1f s mean — RIP lasts %.1f%% longer",
                pressure, trials, externalMean, ripMean,
                100.0 * (ripMean / externalMean - 1.0));
        Check.note("RIP plants lost: %s", ripFailureKinds);
        Check.note("total exposure weight %.2f external vs %.2f RIP",
                PlantConfiguration.EXTERNAL_RECIRCULATION_LOOPS.totalExposureWeight(),
                PlantConfiguration.REACTOR_INTERNAL_PUMPS.totalExposureWeight());

        Check.greaterThan(externalMean * 1.10, ripMean,
                "a RIP plant must outlive an external-loop plant by a clear margin");
        Check.exactly(0, ripRecircFailures,
                "a RIP plant has no recirculation line and cannot lose one");
        Check.isFalse(PlantConfiguration.REACTOR_INTERNAL_PUMPS
                        .exposes(BoundaryComponent.RECIRCULATION_LINE),
                "the recirculation line must not be part of a RIP plant's boundary");

        // The advantage is not merely statistical: the pipe genuinely does not
        // exist, so it accumulates nothing however hard the plant is abused.
        BoundaryStress rip = new BoundaryStress(PlantConfiguration.REACTOR_INTERNAL_PUMPS);
        for (int i = 0; i < 20 * 600; i++) {
            rip.step(2500.0, TICK);
        }
        Check.exactly(0.0, rip.getStress(BoundaryComponent.RECIRCULATION_LINE),
                "recirculation stress on a RIP plant after ten minutes at 2500 psig");
        Check.greaterThan(0.0, rip.getStress(BoundaryComponent.MAIN_STEAM_LINE),
                "its steam lines are still exposed");
        Check.exactly(1.0, rip.coreFlowDeliveredFraction(),
                "a RIP plant can never lose core flow to a recirculation break");
    }

    /**
     * <b>The head only goes on sustained gross overpressure.</b> It is a forging
     * several inches thick, wearing at a quarter of a steam line's rate, so it
     * needs exactly four times as long to become eligible to fail at all — by
     * which time something thinner has almost certainly already let go and
     * depressurised the vessel.
     */
    public static void test06_theVesselHeadIsTheSlowestThingToFail() {
        double pressure = 1500.0;
        Map<BoundaryComponent, Double> eligibleAt = new EnumMap<>(BoundaryComponent.class);

        for (BoundaryComponent component : BoundaryComponent.values()) {
            BoundaryStress boundary = new BoundaryStress();
            double t = 0.0;
            while (boundary.getStress(component) < BoundaryStress.FAILURE_STRESS && t < 36_000.0) {
                // Repair everything else each step so only the timing of this
                // component's own wear is measured.
                boundary.step(pressure, TICK);
                t += TICK;
            }
            eligibleAt.put(component, t);
            Check.note("%-20s reaches the failure gate after %.1f s at %.0f psig",
                    component.displayName(), t, pressure);
        }

        double steam = eligibleAt.get(BoundaryComponent.MAIN_STEAM_LINE);
        double head = eligibleAt.get(BoundaryComponent.REACTOR_VESSEL_HEAD);
        Check.relative(4.0, head / steam, 0.01,
                "the head must take four times as long as a steam line to become eligible");
        Check.isTrue(BoundaryComponent.REACTOR_VESSEL_HEAD.isTerminal(),
                "the head is the terminal failure");
        Check.isFalse(BoundaryComponent.MAIN_STEAM_LINE.isTerminal(),
                "a steam line break is survivable");
        Check.isFalse(BoundaryComponent.RECIRCULATION_LINE.isTerminal(),
                "a recirculation break is survivable, if barely");
        Check.isFalse(BoundaryComponent.FEEDWATER_LINE.isTerminal(),
                "a feedwater break is survivable");
    }

    /**
     * <b>Each break behaves differently.</b> Run the same rated plant into each
     * failure in turn and measure what it actually does to the reactor. A steam
     * line depressurises and turns the power down; a recirculation line takes
     * flow and inventory together; a feedwater line takes only makeup and is
     * slow enough to be missed.
     */
    public static void test07_eachBreakHasItsOwnConsequence() {
        // --- Main steam line: rapid depressurisation, voids FORM, power falls.
        ReactorCore steamBreak = TransientHarness.ratedCore();
        TransientHarness.PlantOperator quiet = new TransientHarness.PlantOperator(steamBreak);
        quiet.setPressureRegulatorEnabled(true);
        quiet.setLevelControlEnabled(true);
        quiet.setRodControlEnabled(false);
        TransientHarness.runSeconds(steamBreak, quiet, 10.0, null);

        double pressureBefore = steamBreak.getPressurePsig();
        double voidBefore = steamBreak.getVoidFraction();
        double powerBefore = steamBreak.getNeutronPowerFraction();
        double collapsedBefore = steamBreak.getCollapsedLevelIn();
        double swellBefore = steamBreak.getTwoPhaseLevelIn() - collapsedBefore;
        steamBreak.getBoundaryStress()
                .forceFailure(BoundaryComponent.MAIN_STEAM_LINE, pressureBefore);

        double leastIndicated = steamBreak.getIndicatedLevelIn();
        double leastIndicatedAt = 0.0;
        for (int i = 0; i < 20 * 90; i++) {
            quiet.tick();
            steamBreak.step();
            if (steamBreak.getIndicatedLevelIn() < leastIndicated) {
                leastIndicated = steamBreak.getIndicatedLevelIn();
                leastIndicatedAt = (i + 1) * 0.05;
            }
        }
        double swellAfter = steamBreak.getTwoPhaseLevelIn() - steamBreak.getCollapsedLevelIn();
        Check.note("steam line break: %.0f -> %.0f psig, void %.4f -> %.4f, fission %.4f -> %.4f, "
                        + "break flow %.0f kg/s",
                pressureBefore, steamBreak.getPressurePsig(), voidBefore,
                steamBreak.getVoidFraction(), powerBefore, steamBreak.getNeutronPowerFraction(),
                steamBreak.getBreakSteamFlowKgPerS());
        Check.note("level over 90 s: collapsed %.1f -> %.1f in while swell grows %.1f -> %.1f in; "
                        + "the INDICATION bottoms at %.1f in at %.0f s and then reads %.1f in "
                        + "as the real inventory keeps falling",
                collapsedBefore, steamBreak.getCollapsedLevelIn(), swellBefore, swellAfter,
                leastIndicated, leastIndicatedAt, steamBreak.getIndicatedLevelIn());

        Check.lessThan(pressureBefore - 100.0, steamBreak.getPressurePsig(),
                "a steam line break must depressurise the vessel");
        Check.greaterThan(voidBefore, steamBreak.getVoidFraction(),
                "voids must FORM on a depressurisation, not collapse");
        Check.lessThan(powerBefore, steamBreak.getNeutronPowerFraction(),
                "void formation is negative reactivity, so power must fall");
        Check.exactly(0.0, steamBreak.getBreakLiquidFlowKgPerS(),
                "a steam line break is in the steam space, not below the water line");

        // Swell and shrink, as the model expresses them: the two-phase column
        // stands further and further above the collapsed level as void grows,
        // and the differential-pressure instrument — calibrated for a density
        // that no longer holds — turns back upward while the vessel is still
        // emptying. That combination is the classic operator trap, and it is
        // why nothing in this mod hands a control program the collapsed level.
        Check.greaterThan(swellBefore, swellAfter,
                "the two-phase column must swell relative to the collapsed level");
        Check.lessThan(collapsedBefore, steamBreak.getCollapsedLevelIn(),
                "while the real inventory shrinks");
        Check.greaterThan(leastIndicated, steamBreak.getIndicatedLevelIn(),
                "and the indication must recover upward while the inventory does not");
        Check.greaterThan(steamBreak.getCollapsedLevelIn(), steamBreak.getIndicatedLevelIn(),
                "so the instrument reads above the collapsed level it is meant to represent");

        // --- Recirculation line: flow AND inventory, simultaneously. The
        //     control program is left running — pressure regulator and level
        //     control both in service — so this is what happens to a plant
        //     whose operator is still doing everything right.
        ReactorCore lossOfCoolant = TransientHarness.ratedCore();
        TransientHarness.PlantOperator stillTrying =
                new TransientHarness.PlantOperator(lossOfCoolant);
        stillTrying.setPressureRegulatorEnabled(true);
        stillTrying.setLevelControlEnabled(true);
        stillTrying.setRodControlEnabled(false);
        TransientHarness.runSeconds(lossOfCoolant, stillTrying, 10.0, null);

        double flowBefore = lossOfCoolant.getCoreFlowFraction();
        double massBefore = lossOfCoolant.getLiquidMassKg();
        double powerBeforeLoca = lossOfCoolant.getNeutronPowerFraction();
        lossOfCoolant.getBoundaryStress().forceFailure(
                BoundaryComponent.RECIRCULATION_LINE, lossOfCoolant.getPressurePsig());

        double flowAt30 = Double.NaN;
        for (int i = 0; i < 20 * 90; i++) {
            stillTrying.tick();
            lossOfCoolant.step();
            if (i == 20 * 30 - 1) {
                flowAt30 = lossOfCoolant.getCoreFlowFraction();
            }
        }
        Check.note("recirculation break: core flow %.2f -> %.3f at 30 s -> %.3f at 90 s; "
                        + "inventory %.0f -> %.0f kg at %.0f kg/s out with feedwater still "
                        + "delivering %.0f kg/s",
                flowBefore, flowAt30, lossOfCoolant.getCoreFlowFraction(), massBefore,
                lossOfCoolant.getLiquidMassKg(), lossOfCoolant.getBreakLiquidFlowKgPerS(),
                lossOfCoolant.getDeliveredFeedwaterFlowKgPerS());
        Check.note("collapsed level %.0f in, uncovered fuel %.3f, fission %.4f -> %.4f",
                lossOfCoolant.getCollapsedLevelIn(), lossOfCoolant.getUncoveredFuelFraction(),
                powerBeforeLoca, lossOfCoolant.getNeutronPowerFraction());

        Check.lessThan(0.20, flowAt30, "core flow must be gone within the pump coastdown");
        Check.lessThan(0.05, lossOfCoolant.getCoreFlowFraction(),
                "a recirculation break must take the core flow with it");
        Check.lessThan(0.5 * massBefore, lossOfCoolant.getLiquidMassKg(),
                "and the inventory, at the same time — despite feedwater running");
        Check.greaterThan(0.0, lossOfCoolant.getDeliveredFeedwaterFlowKgPerS(),
                "feedwater is intact and cannot keep up");
        Check.greaterThan(0.5, lossOfCoolant.getUncoveredFuelFraction(),
                "the core uncovers inside two minutes with no ECCS — this is the break "
                        + "that sizes emergency injection");
        Check.greaterThan(0.0, lossOfCoolant.getBreakLiquidFlowKgPerS(),
                "the break must still be discharging");

        // --- Feedwater line: slower. Makeup goes away; nothing else changes.
        ReactorCore lossOfFeed = TransientHarness.ratedCore();
        TransientHarness.PlantOperator feeding =
                new TransientHarness.PlantOperator(lossOfFeed);
        feeding.setPressureRegulatorEnabled(true);
        feeding.setLevelControlEnabled(true);
        feeding.setRodControlEnabled(false);
        TransientHarness.runSeconds(lossOfFeed, feeding, 10.0, null);

        double feedLevelBefore = lossOfFeed.getCollapsedLevelIn();
        double feedPressureBefore = lossOfFeed.getPressurePsig();
        double feedFlowBefore = lossOfFeed.getCoreFlowFraction();
        double feedPowerBefore = lossOfFeed.getNeutronPowerFraction();
        lossOfFeed.getBoundaryStress()
                .forceFailure(BoundaryComponent.FEEDWATER_LINE, feedPressureBefore);
        TransientHarness.runSeconds(lossOfFeed, feeding, 20.0, null);

        double levelAt30 = lossOfFeed.getCollapsedLevelIn();
        Check.note("feedwater break, first 20 s: commanded %.0f kg/s, delivered %.0f kg/s; "
                        + "pressure %.0f -> %.0f psig, core flow %.2f -> %.2f, "
                        + "fission %.4f -> %.4f, collapsed level %.1f -> %.1f in",
                lossOfFeed.getFeedwaterFlowKgPerS(), lossOfFeed.getDeliveredFeedwaterFlowKgPerS(),
                feedPressureBefore, lossOfFeed.getPressurePsig(),
                feedFlowBefore, lossOfFeed.getCoreFlowFraction(),
                feedPowerBefore, lossOfFeed.getNeutronPowerFraction(),
                feedLevelBefore, levelAt30);

        Check.greaterThan(0.0, lossOfFeed.getFeedwaterFlowKgPerS(),
                "the control program is still commanding feedwater");
        Check.exactly(0.0, lossOfFeed.getDeliveredFeedwaterFlowKgPerS(),
                "but none of it arrives");
        Check.exactly(0.0, lossOfFeed.getBreakLiquidFlowKgPerS(),
                "a feedwater break spills outside the vessel, not out of it");
        Check.exactly(0.0, lossOfFeed.getBreakSteamFlowKgPerS(),
                "and it is not in the steam space either");
        // This is what "slower" means. Twenty seconds after a steam line break
        // the vessel has lost hundreds of psi; twenty seconds after a
        // recirculation break the core flow is most of the way gone. Twenty
        // seconds after this one the vessel is intact, still at pressure, still
        // circulating, still making power, and nothing is coming out of it. The
        // only thing that has moved is the level, which is exactly the failure
        // an inattentive operator misses.
        Check.absolute(feedPressureBefore, lossOfFeed.getPressurePsig(), 150.0,
                "the vessel must still be holding pressure after 20 s");
        Check.absolute(feedFlowBefore, lossOfFeed.getCoreFlowFraction(), 0.05,
                "core flow is untouched");
        Check.relative(feedPowerBefore, lossOfFeed.getNeutronPowerFraction(), 0.40,
                "and the reactor is still making power as if nothing happened");
        Check.lessThan(feedLevelBefore - 40.0, levelAt30,
                "only the level is going anywhere, and it is going a long way");
        Check.exactly(1, lossOfFeed.getBoundaryStress().getBrokenComponents().size(),
                "nothing else has broken yet");

        // Ignore it, though. At rated power a 220 tonne vessel boils dry in
        // under two minutes, and the shrinking inventory pressurises: less water
        // absorbing the same 3579 MW. Void collapses, power runs up, and the
        // overpressure model — the thing this whole file is about — is what
        // finally ends the transient, by breaking something.
        TransientHarness.runSeconds(lossOfFeed, feeding, 40.0, null);
        Check.note("feedwater break, 60 s in and unattended: pressure %.0f psig, "
                        + "fission %.2f of rated, collapsed level %.0f in, uncovered fuel %.3f, "
                        + "broken: %s",
                lossOfFeed.getPressurePsig(), lossOfFeed.getNeutronPowerFraction(),
                lossOfFeed.getCollapsedLevelIn(), lossOfFeed.getUncoveredFuelFraction(),
                lossOfFeed.getBoundaryStress().getBrokenComponents());
        Check.lessThan(levelAt30, lossOfFeed.getCollapsedLevelIn(),
                "and the level keeps going down");
        Check.greaterThan(0.0, lossOfFeed.getUncoveredFuelFraction(),
                "an unattended loss of feedwater uncovers the core on its own");
    }

    /**
     * <b>The balance claim, measured.</b> An unattended plant must be noticed
     * rather than instantly deleted. This runs the real thing — a rated core,
     * MSIVs shut, no relief valve opened, nobody home — and reports how long the
     * pressure boundary lasts and what it loses.
     */
    public static void test08_damageIsGradualEnoughToBeNoticed() {
        ReactorCore core = TransientHarness.ratedCore();
        TransientHarness.PlantOperator operator = new TransientHarness.PlantOperator(core);
        operator.setPressureRegulatorEnabled(true);
        operator.setLevelControlEnabled(true);
        operator.setRodControlEnabled(false);
        TransientHarness.runSeconds(core, operator, 20.0, null);

        // MSIVs shut. Regulator valves are now on the wrong side of them.
        operator.setPressureRegulatorEnabled(false);
        core.setTurbineSteamFlowKgPerS(0.0);
        core.setBypassSteamFlowKgPerS(0.0);
        core.setReliefSteamFlowKgPerS(0.0);

        BoundaryStress boundary = core.getBoundaryStress();
        double crossedDesign = Double.NaN;
        double crossedCode = Double.NaN;
        double failedAt = Double.NaN;
        double peakPressure = 0.0;

        for (int i = 0; i < 20 * 600 && !boundary.hasFailed(); i++) {
            operator.tick();
            core.step();
            double t = core.getElapsedSeconds();
            peakPressure = Math.max(peakPressure, core.getPressurePsig());
            if (Double.isNaN(crossedDesign)
                    && core.getPressurePsig() > BoundaryStress.DESIGN_PRESSURE_PSIG) {
                crossedDesign = t;
            }
            if (Double.isNaN(crossedCode)
                    && core.getPressurePsig() > BoundaryStress.CODE_PRESSURE_PSIG) {
                crossedCode = t;
            }
            if (boundary.hasFailed() && Double.isNaN(failedAt)) {
                failedAt = t;
            }
        }
        if (boundary.hasFailed() && Double.isNaN(failedAt)) {
            failedAt = core.getElapsedSeconds();
        }

        BoundaryFailure failure = boundary.getLastFailure();
        Check.note("isolated at 20.0 s; crossed 1250 psig at %.2f s, 1375 psig at %.2f s, "
                        + "peak %.0f psig", crossedDesign, crossedCode, peakPressure);
        Check.note("first failure: %s", failure);
        Check.note("survived %.2f s above design pressure and %.2f s above the code limit",
                failedAt - crossedDesign, failedAt - crossedCode);
        Check.note("at the moment of the break: %.0f psig, %.1f%% of rated, void %.4f; "
                        + "stress left standing — recirc %.2f steam %.2f feed %.2f head %.2f",
                core.getPressurePsig(), 100.0 * core.getTotalPowerFractionOfRated(),
                core.getVoidFraction(),
                boundary.getStress(BoundaryComponent.RECIRCULATION_LINE),
                boundary.getStress(BoundaryComponent.MAIN_STEAM_LINE),
                boundary.getStress(BoundaryComponent.FEEDWATER_LINE),
                boundary.getStress(BoundaryComponent.REACTOR_VESSEL_HEAD));

        Check.isTrue(boundary.hasFailed(),
                "an isolated reactor with no relief must eventually break something");
        Check.greaterThan(10.0, failedAt - crossedDesign,
                "at least ten seconds of overpressure before anything lets go");
        Check.isFalse(boundary.hasTerminalFailure(),
                "the first thing to go in a fast pressurisation must not be the head");
        Check.exactly(0.0, core.getReliefSteamFlowKgPerS(),
                "no relief valve may open by itself, even now");
        Check.greaterThan(0.0, boundary.getIrradiatedFuelOverpressureSeconds(),
                "the excursion past 1325 psig with irradiated fuel must be on the record");
    }

    /**
     * <b>The existing pressurisation trace is unaffected.</b> Twenty seconds of
     * unrelieved isolation is calibrated to sit just inside the failure gate: it
     * must consume most of the recirculation line's life without breaking
     * anything, so the transient the rest of the suite measures still runs to
     * completion and the damage model is visible as a warning rather than as a
     * deletion.
     */
    public static void test09_twentySecondsOfIsolationWoundsButDoesNotBreak() {
        ReactorCore core = TransientHarness.ratedCore();
        TransientHarness.PlantOperator operator = new TransientHarness.PlantOperator(core);
        operator.setPressureRegulatorEnabled(true);
        operator.setLevelControlEnabled(true);
        operator.setRodControlEnabled(false);
        TransientHarness.runSeconds(core, operator, 20.0, null);

        operator.setPressureRegulatorEnabled(false);
        core.setTurbineSteamFlowKgPerS(0.0);
        core.setBypassSteamFlowKgPerS(0.0);
        core.setReliefSteamFlowKgPerS(0.0);
        TransientHarness.runSeconds(core, operator, 20.0, null);

        BoundaryStress boundary = core.getBoundaryStress();
        Check.note("20 s of isolation reaches %.0f psig; stress recirc %.3f, steam %.3f, "
                        + "feed %.3f, head %.3f",
                core.getPressurePsig(),
                boundary.getStress(BoundaryComponent.RECIRCULATION_LINE),
                boundary.getStress(BoundaryComponent.MAIN_STEAM_LINE),
                boundary.getStress(BoundaryComponent.FEEDWATER_LINE),
                boundary.getStress(BoundaryComponent.REACTOR_VESSEL_HEAD));
        Check.note("%.2f s above design pressure, %.2f s above the code limit, "
                        + "%.2f s above 1325 psig with irradiated fuel",
                boundary.getSecondsAboveDesignPressure(), boundary.getSecondsAboveCodePressure(),
                boundary.getIrradiatedFuelOverpressureSeconds());

        Check.isFalse(boundary.hasFailed(),
                "the documented 20 s pressurisation trace must still run to completion");
        Check.greaterThan(0.5, boundary.getStress(BoundaryComponent.RECIRCULATION_LINE),
                "but it must consume most of the recirculation line's life");
        Check.lessThan(BoundaryStress.FAILURE_STRESS,
                boundary.getStress(BoundaryComponent.RECIRCULATION_LINE),
                "and stop just short of the failure gate");
        Check.exactly(0.0, boundary.getFailureRatePerSecond(),
                "so the hazard is still exactly zero when the trace ends");
    }

    /**
     * Damage must survive a chunk reload. A plant that heals while nobody is
     * looking is a plant with no consequences ({@code SPEC.md} section 11), so
     * the snapshot round trip is checked bit for bit, breaks included.
     */
    public static void test10_damageSurvivesASnapshotRoundTrip() {
        BoundaryStress original = new BoundaryStress(PlantConfiguration.REACTOR_INTERNAL_PUMPS);
        original.setIrradiatedFuelPresent(true);
        for (int i = 0; i < 20 * 400; i++) {
            original.step(1450.0, TICK);
        }
        original.forceFailure(BoundaryComponent.MAIN_STEAM_LINE, 1450.0);

        double[] snapshot = original.toArray();
        BoundaryStress restored = new BoundaryStress();
        restored.fromArray(snapshot);

        Check.exactly(BoundaryStress.SNAPSHOT_LENGTH, snapshot.length, "snapshot length");
        Check.exactly(original.getPlantConfiguration().ordinal(),
                restored.getPlantConfiguration().ordinal(), "plant configuration");
        for (BoundaryComponent component : BoundaryComponent.values()) {
            Check.exactly(original.getStress(component), restored.getStress(component),
                    "stress on " + component.displayName());
            Check.isTrue(original.isBroken(component) == restored.isBroken(component),
                    "break state of %s", component.displayName());
        }
        Check.exactly(original.getSecondsAboveDesignPressure(),
                restored.getSecondsAboveDesignPressure(), "seconds above design pressure");
        Check.exactly(original.getSecondsAboveCodePressure(),
                restored.getSecondsAboveCodePressure(), "seconds above the code limit");
        Check.exactly(original.getIrradiatedFuelOverpressureSeconds(),
                restored.getIrradiatedFuelOverpressureSeconds(),
                "irradiated-fuel overpressure exposure");
        Check.exactly(original.getPeakPressurePsig(), restored.getPeakPressurePsig(),
                "peak pressure");
        Check.arraysExactly(snapshot, restored.toArray(), "second round trip");

        Check.note("round trip exact across %d doubles; steam line still broken after restore: %s",
                snapshot.length, restored.isBroken(BoundaryComponent.MAIN_STEAM_LINE));

        // A repair is the only thing that clears it, and it clears it completely.
        restored.repair(BoundaryComponent.MAIN_STEAM_LINE);
        Check.isFalse(restored.isBroken(BoundaryComponent.MAIN_STEAM_LINE), "after a repair");
        Check.exactly(0.0, restored.getStress(BoundaryComponent.MAIN_STEAM_LINE),
                "new pipe has no history");
        Check.greaterThan(0.0, restored.getStress(BoundaryComponent.FEEDWATER_LINE),
                "repairing one component must not repair the others");
    }

    /**
     * <b>The design rule, in this package too.</b> The mod provides hardware and
     * physics; the player provides control logic. A damage model is the most
     * tempting possible place to slip in a convenience {@code isOverPressure()}
     * or an automatic relief, so the same reflection scan
     * {@code ReactorCoreTickTest.test07} runs over the physics is run here over
     * everything in {@code dev.bwr.core.boundary}.
     */
    public static void test11_noProtectionLogicInTheDamageModel() {
        Class<?>[] types = {
                BoundaryStress.class, BoundaryComponent.class,
                PlantConfiguration.class, BoundaryFailure.class,
        };
        List<String> bannedWords = List.of(
                "high", "low", "trip", "trips", "tripped", "permissive", "permissives",
                "setpoint", "setpoints", "interlock", "interlocks", "alarm", "alarms",
                "unsafe", "acceptable", "violation");
        List<String> bannedNames = List.of(
                "shouldscram", "checktrips", "autoscram", "needsscram", "issafe", "isunsafe",
                "ishighpressure", "islowlevel", "checklimits", "enforcelimits",
                "protectionsystem", "safetysystem", "autostart", "scramifneeded");

        List<String> offences = new ArrayList<>();
        int scanned = 0;
        for (Class<?> type : types) {
            for (Method method : type.getDeclaredMethods()) {
                if (method.isSynthetic()) {
                    continue;
                }
                scanned++;
                String name = method.getName();
                String flat = name.toLowerCase(Locale.ROOT);
                for (String banned : bannedNames) {
                    if (flat.contains(banned)) {
                        offences.add(type.getSimpleName() + "." + name + " (name '" + banned + "')");
                    }
                }
                for (String word : splitCamelCase(name)) {
                    if (bannedWords.contains(word)) {
                        offences.add(type.getSimpleName() + "." + name + " (word '" + word + "')");
                    }
                }
            }
        }
        Check.note("scanned %d methods across %d boundary classes", scanned, types.length);
        for (String offence : offences) {
            Check.note("OFFENCE %s", offence);
        }
        Check.isTrue(offences.isEmpty(),
                "the damage model must expose measurements and actuators, never judgements: %s",
                offences);

        // And it must not have grown an opinion about relief either: stepping a
        // core straight through the code limit must never open a valve.
        ReactorCore core = TransientHarness.ratedCore();
        core.setTurbineSteamFlowKgPerS(0.0);
        core.setBypassSteamFlowKgPerS(0.0);
        for (int i = 0; i < 20 * 30; i++) {
            core.step();
            Check.exactly(0.0, core.getReliefSteamFlowKgPerS(), "relief flow during an excursion");
        }
        Check.note("30 s of unrelieved isolation reached %.0f psig and opened nothing",
                core.getPressurePsig());
        Check.greaterThan(PhysicalConstants.VESSEL_CODE_LIMIT_PSIG, core.getPressurePsig(),
                "the excursion must genuinely have gone past the code limit");
    }

    private static List<String> splitCamelCase(String name) {
        List<String> words = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isUpperCase(c) && current.length() > 0) {
                words.add(current.toString().toLowerCase(Locale.ROOT));
                current.setLength(0);
            }
            if (Character.isLetterOrDigit(c)) {
                current.append(c);
            }
        }
        if (current.length() > 0) {
            words.add(current.toString().toLowerCase(Locale.ROOT));
        }
        return words;
    }
}
