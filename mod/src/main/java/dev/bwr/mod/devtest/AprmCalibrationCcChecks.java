package dev.bwr.mod.devtest;

import dan200.computercraft.api.lua.LuaException;
import dev.bwr.mod.peripheral.ReactorPeripheral;
import dev.bwr.mod.reactor.ReactorControllerBlockEntity;
import dev.bwr.mod.signal.SignalProvider;
import net.minecraft.gametest.framework.GameTestHelper;

import java.util.Map;

/**
 * The CC half of {@link AprmCalibrationTests}: the calibration table is the signal layer's readings, a Lua-side
 * recalibration uses the table as a player's program would, and the bounds are Lua errors that change nothing.
 * References CC classes, so callers must be behind {@code BwrMod.isComputerCraftPresent()}.
 */
final class AprmCalibrationCcChecks {
    private AprmCalibrationCcChecks() {}

    @SuppressWarnings("unchecked")
    static void recalibrateFive(GameTestHelper h, ReactorControllerBlockEntity be, SignalProvider p) {
        var cc = new ReactorPeripheral(be);
        try {
            Map<String, Object> table = cc.getAprmCalibration();
            Map<Integer, Object> channels = (Map<Integer, Object>) table.get("channels");
            h.assertTrue(channels.size() == 6, "calibration table has " + channels.size() + " channels");
            h.assertTrue(((Number) table.get("heatBalancePercent")).doubleValue() == p.read("heat_balance_percent").orElse(Double.NaN)
                    && ((Number) table.get("heatBalanceMW")).doubleValue() == p.read("heat_balance_mw").orElse(Double.NaN)
                    && cc.getHeatBalancePower() == p.read("heat_balance_mw").orElse(Double.NaN), "CC heat balance differs from the signal");
            for (int n = 1; n <= 6; n++) {
                Map<String, Object> one = (Map<String, Object>) channels.get(n);
                h.assertTrue(((Number) one.get("percent")).doubleValue() == p.read("aprm_" + n + "_percent").orElse(Double.NaN)
                        && ((Number) one.get("gain")).doubleValue() == p.read("aprm_" + n + "_gain").orElse(Double.NaN)
                        && ((Number) one.get("heatBalanceErrorPercent")).doubleValue() == p.read("aprm_" + n + "_heat_balance_error").orElse(Double.NaN),
                        "CC APRM " + n + " calibration differs from the signals");
            }

            for (double bad : new double[]{0.4, 2.5, Double.NaN, Double.POSITIVE_INFINITY}) {
                refused(h, cc, 5, bad);
            }
            refused(h, cc, 0, 1.0);
            refused(h, cc, 7, 1.0);
            h.assertTrue(p.read("aprm_5_gain").orElse(Double.NaN) == 1.45, "a refused CC gain changed APRM 5");

            Map<String, Object> five = (Map<String, Object>) channels.get(5);
            double gain = ((Number) five.get("gain")).doubleValue() * ((Number) table.get("heatBalancePercent")).doubleValue()
                    / ((Number) five.get("percent")).doubleValue();
            cc.setAprmGain(5, gain);
            h.assertTrue(be.core().getAveragePowerRangeGain(4) == gain, "CC setAprmGain did not reach the core");
        } catch (LuaException e) {
            throw new AssertionError(e);
        }
    }

    private static void refused(GameTestHelper h, ReactorPeripheral cc, int channel, double gain) {
        try {
            cc.setAprmGain(channel, gain);
        } catch (LuaException expected) {
            return;
        }
        h.assertTrue(false, "CC setAprmGain(" + channel + ", " + gain + ") was accepted");
    }
}
