package dev.bwr.core.instrument;

import dev.bwr.core.ReactorCore;
import dev.bwr.core.harness.TransientHarness;

/**
 * Player APRM calibration: the heat balance, the per-channel error, and the bounded manual gain.
 *
 * <p>The procedure under test is the real one. Read the heat-balance power, read an APRM, set that APRM's
 * gain to {@code gain * heatBalance / indicated}, and the channel agrees with the heat balance. Nothing here
 * calibrates by itself, and one test pins that down.
 */
public final class AprmCalibrationTest {
    private static void require(boolean good, String message) {
        if (!good) throw new AssertionError(message);
    }

    private static void steps(ReactorCore core, int n) {
        for (int i = 0; i < n; i++) core.step();
    }

    /** At a steady rated plant the flow-based heat balance closes on the simulation's own thermal power. */
    public static void testHeatBalanceMatchesThermalPowerWhenSteady() {
        ReactorCore core = TransientHarness.ratedCore();
        steps(core, 40);
        double heatBalance = core.getHeatBalanceThermalPowerMW();
        double model = core.getThermalPowerMW();
        System.out.printf(java.util.Locale.ROOT, "HEAT BALANCE %.2f MW vs model thermal %.2f MW (%.3f%%)%n",
                heatBalance, model, 100.0 * (heatBalance / model - 1.0));
        require(Math.abs(heatBalance / model - 1.0) < 0.01, "steady heat balance " + heatBalance + " MW vs " + model + " MW");
        require(Math.abs(core.getHeatBalancePercentOfRated() - 100.0 * heatBalance / core.getConfig().ratedThermalMW) < 1e-9,
                "percent of rated is not the MW over rated");
    }

    /** With no steam leaving, the heat balance reads zero whatever the core does: it is not the model's power. */
    public static void testHeatBalanceIsAMeasurementNotTheModelPower() {
        ReactorCore core = TransientHarness.ratedCore();
        core.setTurbineSteamFlowKgPerS(0.0);
        steps(core, 5);
        require(core.getThermalPowerMW() > 0.9 * core.getConfig().ratedThermalMW, "the core is no longer at power");
        require(core.getHeatBalanceThermalPowerMW() < 1.0e-3,
                "heat balance with no steam flow must read zero, got " + core.getHeatBalanceThermalPowerMW());
        require(core.getAveragePowerRangeHeatBalanceErrorPercent(0) > 90.0,
                "the error against a zero heat balance is the whole indication");
    }

    /** Miscalibrate one channel, recalibrate it by hand from the readings, and it agrees with the heat balance. */
    public static void testManualRecalibrationBringsAChannelToHeatBalance() {
        ReactorCore core = TransientHarness.ratedCore();
        steps(core, 40);
        int channel = 2;
        double[] others = new double[6];
        for (int c = 0; c < 6; c++) others[c] = core.getAveragePowerRangePercent(c);
        double originalGain = core.getAveragePowerRangeGain(channel);

        require(core.setAveragePowerRangeGain(channel, originalGain * 0.80), "in-range gain refused");
        steps(core, 40);
        double wrong = core.getAveragePowerRangeHeatBalanceErrorPercent(channel);
        System.out.printf(java.util.Locale.ROOT, "APRM %d miscalibrated: %.2f%% vs heat balance %.2f%% (error %.2f)%n",
                channel + 1, core.getAveragePowerRangePercent(channel), core.getHeatBalancePercentOfRated(), wrong);
        require(wrong < -10.0, "an 80% gain should read at least 10 points low, error " + wrong);

        double gain = core.getAveragePowerRangeGain(channel);
        double fixed = gain * core.getHeatBalancePercentOfRated() / core.getAveragePowerRangePercent(channel);
        require(core.setAveragePowerRangeGain(channel, fixed), "computed calibration refused: " + fixed);
        steps(core, 40);
        double right = core.getAveragePowerRangeHeatBalanceErrorPercent(channel);
        System.out.printf(java.util.Locale.ROOT, "APRM %d recalibrated gain %.4f: %.2f%% vs heat balance %.2f%% (error %.3f)%n",
                channel + 1, fixed, core.getAveragePowerRangePercent(channel), core.getHeatBalancePercentOfRated(), right);
        require(Math.abs(right) < 0.5, "recalibrated channel still " + right + " points from the heat balance");

        for (int c = 0; c < 6; c++) {
            if (c == channel) continue;
            require(Math.abs(core.getAveragePowerRangePercent(c) - others[c]) < 0.05,
                    "calibrating APRM " + (channel + 1) + " moved APRM " + (c + 1));
        }
    }

    /** All six channels can be brought to the heat balance independently, and the gain survives a save. */
    public static void testAllSixChannelsCalibrateAndTheGainsPersist() {
        ReactorCore core = TransientHarness.ratedCore();
        steps(core, 40);
        double[] before = new double[6];
        for (int c = 0; c < 6; c++) before[c] = core.getAveragePowerRangePercent(c);
        for (int c = 0; c < 6; c++) {
            require(core.setAveragePowerRangeGain(c, 1.0 + 0.05 * (c - 2)), "miscalibration refused");
        }
        steps(core, 40);
        for (int c = 0; c < 6; c++) {
            double g = core.getAveragePowerRangeGain(c);
            require(core.setAveragePowerRangeGain(c, g * core.getHeatBalancePercentOfRated() / core.getAveragePowerRangePercent(c)),
                    "calibration refused");
        }
        steps(core, 40);
        for (int c = 0; c < 6; c++) {
            double error = core.getAveragePowerRangeHeatBalanceErrorPercent(c);
            System.out.printf(java.util.Locale.ROOT, "APRM %d: gain %.4f, error %.3f (was %.2f%% before)%n",
                    c + 1, core.getAveragePowerRangeGain(c), error, before[c]);
            require(Math.abs(error) < 0.5, "APRM " + (c + 1) + " not on heat balance: " + error);
        }

        var state = core.toState();
        ReactorCore copy = TransientHarness.ratedCore();
        copy.fromState(state);
        steps(copy, 40);
        for (int c = 0; c < 6; c++) {
            require(copy.getAveragePowerRangeGain(c) == core.getAveragePowerRangeGain(c), "gain " + c + " lost on reload");
            require(Math.abs(copy.getAveragePowerRangePercent(c) - copy.getHeatBalancePercentOfRated()) < 0.7,
                    "reloaded APRM " + (c + 1) + " no longer on heat balance");
        }
    }

    /** The gain is bounded; a refused command changes nothing. */
    public static void testGainIsBoundedAndRefusalsChangeNothing() {
        ReactorCore core = TransientHarness.ratedCore();
        double[] bad = {AveragePowerRangeMonitor.MIN_OPERATOR_GAIN - 0.001, AveragePowerRangeMonitor.MAX_OPERATOR_GAIN + 0.001,
                0.0, -1.0, 100.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY};
        for (double value : bad) {
            require(!core.setAveragePowerRangeGain(1, value), "accepted gain " + value);
            require(core.getAveragePowerRangeGain(1) == 1.0, "refused gain " + value + " changed the channel");
        }
        require(!core.setAveragePowerRangeGain(-1, 1.0) && !core.setAveragePowerRangeGain(6, 1.0), "accepted a missing channel");
        require(core.setAveragePowerRangeGain(1, AveragePowerRangeMonitor.MIN_OPERATOR_GAIN)
                && core.getAveragePowerRangeGain(1) == AveragePowerRangeMonitor.MIN_OPERATOR_GAIN, "lower bound refused");
        require(core.setAveragePowerRangeGain(1, AveragePowerRangeMonitor.MAX_OPERATOR_GAIN)
                && core.getAveragePowerRangeGain(1) == AveragePowerRangeMonitor.MAX_OPERATOR_GAIN, "upper bound refused");
    }

    /** No automatic calibration: a miscalibrated channel stays exactly where the player left it. */
    public static void testNothingRecalibratesAChannelByItself() {
        ReactorCore core = TransientHarness.ratedCore();
        require(core.setAveragePowerRangeGain(4, 1.17), "gain refused");
        steps(core, 1200);
        core.setAllRodNotchDemand(0);
        steps(core, 400);
        for (int c = 0; c < 6; c++) {
            double expected = c == 4 ? 1.17 : 1.0;
            require(core.getAveragePowerRangeGain(c) == expected,
                    "APRM " + (c + 1) + " gain became " + core.getAveragePowerRangeGain(c) + " without the player");
        }
    }
}
