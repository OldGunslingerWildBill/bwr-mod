package dev.bwr.core;

import dev.bwr.core.harness.TransientHarness;
import dev.bwr.core.kinetics.RodWorth;
import dev.bwr.core.thermal.DecayHeat;

/**
 * Acceptance test 5: scram from rated power.
 *
 * <p>Three separate things have to be right at once, and each catches a
 * different class of error.
 * <ul>
 *   <li>The <b>prompt drop</b> within the insertion stroke. Too shallow means
 *       rod worth or the prompt term is wrong; instantaneous means the delayed
 *       neutrons are missing.</li>
 *   <li>The <b>delayed tail</b>. Fission power must decay on the longest-lived
 *       precursor group — about 80 s — and must <b>never reach zero</b>. A
 *       scrammed core that goes to exactly zero has the delayed neutrons wrong,
 *       and everything downstream of the kinetics is then built on sand.</li>
 *   <li><b>Decay heat</b>, which the scram does not touch at all. It is the
 *       driver of every severe accident in the spec, and no rod position and no
 *       boron concentration changes it.</li>
 * </ul>
 */
public final class ReactorCoreScramTest {

    private ReactorCoreScramTest() {
    }

    /** Rated core with the harness operator holding pressure and level. */
    private static TransientHarness.PlantOperator operatorFor(ReactorCore core) {
        TransientHarness.PlantOperator operator = new TransientHarness.PlantOperator(core);
        operator.setPressureRegulatorEnabled(true);
        operator.setLevelControlEnabled(true);
        operator.setRodControlEnabled(false);
        return operator;
    }

    private static void run(ReactorCore core, TransientHarness.PlantOperator operator, double seconds) {
        TransientHarness.runSeconds(core, operator, seconds, null);
    }

    /**
     * <b>Acceptance 5.</b> Rated power, scram, and follow it for ten minutes.
     */
    public static void test01_scramFromRatedDropsPromptlyAndNeverReachesZero() {
        ReactorCore core = TransientHarness.ratedCore();
        TransientHarness.PlantOperator operator = operatorFor(core);
        run(core, operator, 10.0);

        double fissionBefore = core.getNeutronPowerFraction();
        double totalBefore = core.getTotalPowerFractionOfRated();
        double voidBefore = core.getVoidFraction();
        Check.note("before scram: fission %.4f, decay %.4f, total %.4f of rated, void %.4f, "
                        + "%.1f degC coolant, %.0f psig",
                fissionBefore, core.getDecayHeatFraction(), totalBefore, voidBefore,
                core.getCoolantTemperatureC(), core.getPressurePsig());
        Check.relative(1.0, totalBefore, 0.02, "total power before the scram");

        core.scram();
        Check.isTrue(core.isScramActive(), "the scram signal must latch");

        run(core, operator, 4.0); // one full insertion stroke plus a second
        double fissionAfterStroke = core.getNeutronPowerFraction();
        Check.note("4 s after scram: fission %.4E (%.2f%% of pre-scram), decay %.4f, total %.4f",
                fissionAfterStroke, 100.0 * fissionAfterStroke / fissionBefore,
                core.getDecayHeatFraction(), core.getTotalPowerFractionOfRated());
        Check.isTrue(core.isRodMotionComplete(), "every rod must be in within the insertion stroke");
        for (int rod = 0; rod < core.getControlRodCount(); rod++) {
            Check.exactly(RodWorth.NOTCH_INDEX_FULLY_INSERTED, core.getRodNotchIndex(rod),
                    "rod " + rod + " notch after the scram stroke");
        }
        Check.lessThan(0.20 * fissionBefore, fissionAfterStroke,
                "fission power four seconds after the scram");
        Check.finiteAndPositive(fissionAfterStroke, "fission power four seconds after the scram");

        run(core, operator, 56.0);
        double fissionAtOneMinute = core.getNeutronPowerFraction();
        double decayAtOneMinute = core.getDecayHeatFraction();
        Check.note("60 s after scram: fission %.4E, decay %.4f, total %.4f of rated, void %.5f",
                fissionAtOneMinute, decayAtOneMinute, core.getTotalPowerFractionOfRated(),
                core.getVoidFraction());
        Check.lessThan(0.05 * fissionBefore, fissionAtOneMinute, "fission power one minute after the scram");
        Check.finiteAndPositive(fissionAtOneMinute, "fission power one minute after the scram");
        Check.inRange(0.03, 0.07, decayAtOneMinute, "decay heat one minute after the scram");

        run(core, operator, 540.0);
        double fissionAtTenMinutes = core.getNeutronPowerFraction();
        Check.note("600 s after scram: fission %.4E, decay %.4f (%.0f MW), total %.4f of rated",
                fissionAtTenMinutes, core.getDecayHeatFraction(), core.getDecayHeatMW(),
                core.getTotalPowerFractionOfRated());

        // The whole point: it does not go to zero, and it never will, because
        // delayed neutrons and a startup source are both still there.
        Check.finiteAndPositive(fissionAtTenMinutes, "fission power ten minutes after the scram");
        Check.greaterThan(0.0, core.getTotalPowerFractionOfRated(),
                "total power ten minutes after the scram");
        // Ten minutes out, essentially all of what is left is decay heat: about
        // 2% of rated, which is 75 MW that has to go somewhere.
        Check.inRange(0.015, 0.030, core.getTotalPowerFractionOfRated(),
                "total power ten minutes after the scram");
        Check.lessThan(0.001 * fissionBefore, fissionAtTenMinutes,
                "fission power ten minutes after the scram");
    }

    /**
     * The tail must run on the precursors, not on some arbitrary decay. With the
     * rods in, net reactivity is about -16 dollars, and the dominant root of the
     * inhour equation there sits a hair above {@code -lambda_1}: the fission
     * power decays with a time constant of about 80 seconds, the half-life of
     * the longest-lived precursor group. That number is not tuned anywhere — it
     * is Keepin's {@code lambda_1 = 0.0124 /s} coming back out of the model.
     */
    public static void test02_delayedTailDecaysOnTheLongestPrecursorGroup() {
        ReactorCore core = TransientHarness.ratedCore();
        TransientHarness.PlantOperator operator = operatorFor(core);
        run(core, operator, 10.0);
        core.scram();

        run(core, operator, 150.0);
        double rhoAtStart = core.getReactivityDkOverK();
        double powerAtStart = core.getNeutronPowerFraction();
        run(core, operator, 300.0);
        double powerAtMiddle = core.getNeutronPowerFraction();
        run(core, operator, 300.0);
        double powerAtEnd = core.getNeutronPowerFraction();

        double earlyTimeConstant = -300.0 / Math.log(powerAtMiddle / powerAtStart);
        double lateTimeConstant = -300.0 / Math.log(powerAtEnd / powerAtMiddle);
        double longestPrecursorTimeConstant = 1.0 / 0.0124;

        Check.note("post-scram rho %+.4f dk/k (%.1f$); fission %.4E -> %.4E -> %.4E",
                rhoAtStart, core.getReactivityDollars(), powerAtStart, powerAtMiddle, powerAtEnd);
        Check.note("tail time constant %.1f s over 150-450 s, %.1f s over 450-750 s, "
                        + "longest precursor group 1/lambda_1 = %.1f s",
                earlyTimeConstant, lateTimeConstant, longestPrecursorTimeConstant);
        Check.lessThan(-0.05, rhoAtStart, "the scrammed core must be deeply subcritical");

        // The faster groups are still dying at 150 s, so the early window reads
        // short and the late window converges on lambda_1 from below. Both must
        // be recognisably the longest-lived precursor group and nothing else.
        Check.relative(longestPrecursorTimeConstant, earlyTimeConstant, 0.15,
                "post-scram decay time constant over 150-450 s");
        Check.relative(longestPrecursorTimeConstant, lateTimeConstant, 0.06,
                "post-scram decay time constant over 450-750 s");
        Check.greaterThan(earlyTimeConstant, lateTimeConstant,
                "the tail must lengthen toward lambda_1 as the faster groups die");
    }

    /**
     * Decay heat is untouched by the scram, and that is the point of modelling it
     * separately. It must be at its saturated value the instant the rods hit
     * bottom and must still be several percent of rated an hour later.
     */
    public static void test03_decayHeatIgnoresTheScram() {
        ReactorCore core = TransientHarness.ratedCore();
        TransientHarness.PlantOperator operator = operatorFor(core);
        run(core, operator, 10.0);

        double decayBefore = core.getDecayHeatFraction();
        // What the decay heat would do over the next five seconds if fission
        // stopped dead. The scram cannot beat this curve; the only thing it can
        // do is fail to add to it.
        double projectedFiveSeconds = core.getDecayHeat().projectedFractionOfRated(5.0);
        Check.note("decay heat at rated: %.4f of rated (%.0f MW), saturated value %.4f, "
                        + "projected five seconds ahead with fission stopped %.4f",
                decayBefore, core.getDecayHeatMW(), DecayHeat.SATURATED_FRACTION_OF_RATED,
                projectedFiveSeconds);

        core.scram();
        run(core, operator, 5.0);
        double decayAfterStroke = core.getDecayHeatFraction();
        Check.note("decay heat 5 s after the scram: %.4f of rated (%.0f MW)",
                decayAfterStroke, core.getDecayHeatMW());
        // Above the projection, because the residual delayed-neutron fission is
        // still feeding the short-lived groups, and only just: the scram bought
        // essentially nothing.
        Check.greaterThan(projectedFiveSeconds * (1.0 - 1.0e-9), decayAfterStroke,
                "decay heat five seconds after the scram must be at least the no-fission projection");
        Check.relative(projectedFiveSeconds, decayAfterStroke, 0.05,
                "decay heat five seconds after the scram against the projection");
        Check.greaterThan(0.7 * decayBefore, decayAfterStroke,
                "the scram must not meaningfully reduce decay heat");
        Check.greaterThan(core.getNeutronPowerFraction(), decayAfterStroke,
                "decay heat must dominate fission power once the rods are in");

        run(core, operator, 3595.0);
        Check.note("decay heat one hour after the scram: %.4f of rated (%.0f MW)",
                core.getDecayHeatFraction(), core.getDecayHeatMW());
        Check.inRange(0.005, 0.02, core.getDecayHeatFraction(), "decay heat one hour after the scram");
        Check.greaterThan(0.0, core.getDecayHeatMW(), "decay heat one hour after the scram, MW");
    }

    /**
     * Void collapses as power falls, and the coolant stays pinned to the
     * saturation temperature for the dome pressure throughout — the vessel is a
     * saturated system and nothing about a scram changes that.
     */
    public static void test04_voidCollapsesAndCoolantStaysOnTheSaturationCurve() {
        ReactorCore core = TransientHarness.ratedCore();
        TransientHarness.PlantOperator operator = operatorFor(core);
        run(core, operator, 10.0);

        double voidBefore = core.getVoidFraction();
        Check.inRange(0.2, 0.6, voidBefore, "core average void fraction at rated power");

        core.scram();
        run(core, operator, 60.0);
        double voidAfter = core.getVoidFraction();
        Check.note("void fraction %.4f -> %.4f across the scram", voidBefore, voidAfter);
        Check.lessThan(0.1 * voidBefore, voidAfter, "void fraction one minute after the scram");

        double saturationC = dev.bwr.core.thermal.Saturation.temperatureCelsiusFromPsig(
                core.getPressurePsig());
        Check.note("dome %.1f psig, coolant %.2f degC, saturation at that pressure %.2f degC",
                core.getPressurePsig(), core.getCoolantTemperatureC(), saturationC);
        Check.absolute(saturationC, core.getCoolantTemperatureC(), 1.0e-9,
                "coolant temperature must be saturation temperature");
        Check.absolute(287.4, core.getCoolantTemperatureC(), 2.0,
                "coolant temperature with the regulator holding rated pressure");
    }

    /**
     * {@link ReactorCore#scram()} is a bare actuator. It must check nothing:
     * not power, not period, not pressure, not whether the core is already shut
     * down, and not whether scramming is a sensible thing to do. Deciding that
     * is the player's job in Lua, and the absence of any condition here is the
     * premise of the whole project.
     */
    public static void test05_scramIsAnUnconditionalActuator() {
        // On a core that is already shut down with every rod in.
        ReactorCore shutdown = TransientHarness.shutdownCore();
        shutdown.scram();
        shutdown.step();
        Check.isTrue(shutdown.isScramActive(), "scram latches on an already-shutdown core");
        Check.finiteAndPositive(shutdown.getNeutronPowerFraction(),
                "flux after scramming an already-shutdown core");

        // Twice in a row, from rated.
        ReactorCore rated = TransientHarness.ratedCore();
        rated.scram();
        rated.scram();
        TransientHarness.runSeconds(rated, operatorFor(rated), 5.0, null);
        Check.isTrue(rated.isRodMotionComplete(), "a doubled scram still inserts");

        // And it can be cleared, again with no judgement about whether that is wise.
        rated.resetScram();
        Check.isFalse(rated.isScramActive(), "resetScram clears the latch");
        rated.setAllRodNotchDemand(12);
        TransientHarness.runSeconds(rated, operatorFor(rated), 40.0, null);
        Check.exactly(12, rated.getRodNotchIndex(0), "rods answer demands again after resetScram");
    }

    /**
     * A charged accumulator buys exactly one scram, and recharging needs both
     * electrical power and a water supply. A drive with a flat accumulator on a
     * depressurised vessel is a stuck rod — a consequence of neglect, never a
     * dice roll.
     */
    public static void test06_scramCapabilityIsHardwareNotAGuarantee() {
        // One rod fully withdrawn on an otherwise shut-down core: a full stroke
        // for that drive, and negligible reactivity, so this is a pure hardware
        // test. The rest of the drives are already at the bottom and spend
        // nothing, which is itself the thing to check — charge is spent per notch
        // of travel, not per scram signal.
        ReactorCore core = TransientHarness.shutdownCore();
        core.setRodNotchDemand(0, RodWorth.NOTCH_INDEX_FULLY_WITHDRAWN);
        core.setRodNotchDemand(1, 12);
        TransientHarness.runSeconds(core, null, 60.0, null);
        Check.exactly(RodWorth.NOTCH_INDEX_FULLY_WITHDRAWN, core.getRodNotchIndex(0),
                "rod 0 withdrawn to the top of travel");
        Check.exactly(12, core.getRodNotchIndex(1), "rod 1 withdrawn halfway");
        Check.exactly(core.getControlRodCount(), core.getChargedAccumulatorCount(),
                "every accumulator charged before the scram");

        core.scram();
        TransientHarness.runSeconds(core, null, 5.0, null);
        Check.note("after a scram from notch 24 / notch 12 / notch 0: charges %.4f / %.4f / %.4f",
                core.getAccumulatorCharge(0), core.getAccumulatorCharge(1),
                core.getAccumulatorCharge(2));
        Check.exactly(0.0, core.getAccumulatorCharge(0),
                "a full-stroke insertion spends the whole accumulator charge");
        Check.relative(0.5, core.getAccumulatorCharge(1), 1.0e-12,
                "a half-stroke insertion spends half the charge");
        Check.exactly(1.0, core.getAccumulatorCharge(2),
                "a drive that was already at the bottom spends nothing");
        Check.exactly(core.getControlRodCount() - 1, core.getChargedAccumulatorCount(),
                "the spent drive drops out of the charged count");

        // With power and water the charge comes back, slowly. Nothing in normal
        // operation gates on it; a scram-restart cycle costs real time.
        core.resetScram();
        TransientHarness.runSeconds(core, null, 600.0, null);
        double rechargedTen = core.getAccumulatorCharge(0);
        Check.note("ten minutes of recharging brings the spent accumulator to %.3f "
                        + "(rate %.3g per second)", rechargedTen, core.getAccumulatorRechargePerSecond());
        Check.greaterThan(0.0, rechargedTen, "accumulators recharge with power and water");
        Check.lessThan(1.0, rechargedTen, "a full recovery must take longer than ten minutes");

        // Take the water away and the charge stops coming back, even though the
        // bus is still energised. Scram capability decays on neglect.
        ReactorCore dry = TransientHarness.shutdownCore();
        dry.setRodNotchDemand(0, RodWorth.NOTCH_INDEX_FULLY_WITHDRAWN);
        TransientHarness.runSeconds(dry, null, 60.0, null);
        dry.scram();
        TransientHarness.runSeconds(dry, null, 5.0, null);
        dry.resetScram();
        dry.setCrdWaterSupplied(false);
        TransientHarness.runSeconds(dry, null, 600.0, null);
        Check.note("with the CRD water supply lost, the spent accumulator sits at %.3f "
                        + "after ten minutes", dry.getAccumulatorCharge(0));
        Check.exactly(0.0, dry.getAccumulatorCharge(0),
                "accumulators must not recharge without a water supply");
        Check.exactly(dry.getControlRodCount() - 1, dry.getChargedAccumulatorCount(),
                "charged accumulator count with no water supply");

        // And with no power the drive cannot answer a withdrawal demand at all.
        dry.setCrdPowered(false);
        dry.setAllRodNotchDemand(6);
        TransientHarness.runSeconds(dry, null, 60.0, null);
        Check.exactly(RodWorth.NOTCH_INDEX_FULLY_INSERTED, dry.getRodNotchIndex(0),
                "an unpowered drive cannot answer a withdrawal demand");
    }
}
