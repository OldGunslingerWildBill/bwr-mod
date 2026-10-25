package dev.bwr.core.chemistry;

import dev.bwr.core.thermal.Saturation;
import dev.bwr.core.thermal.WaterInventory;

/**
 * The thermodynamics of the reactor water cleanup loop, on one enthalpy balance so that nothing is counted twice.
 * Pure Java.
 *
 * <p>The pump draws {@code F} kg/s of saturated vessel water at {@code h_v}. All of it goes through the hot side of the
 * regenerative exchanger and the non-regenerative exchanger, which cools it to {@code h_x}. A share {@code B} is rejected
 * there (blowdown to the condensate tank or hotwell, at {@code h_x}); the rest, {@code F - B}, passes the demineraliser
 * (at {@code h_x}), the cold side of the regenerative exchanger (which heats it back toward the vessel) and returns to
 * the feedwater line at {@code h_ret}.
 *
 * <pre>
 *   Q_r  = eps (F - B) (h_v - h_x)                 regenerative duty
 *   h_x  = h_v - Q_c / (F (1 - eps (F - B)/F))     the cooler's duty sets the demineraliser temperature
 *   h_ret = h_x + Q_r / (F - B)
 *   vessel heat lost, beyond the blowdown's own enthalpy = (F - B)(h_v - h_ret)
 * </pre>
 *
 * <p>Energy closes: {@code F h_v = (F - B) h_ret + B h_x + Q_c}. The blowdown's mass leaves the vessel as saturated liquid
 * (the vessel model already carries its enthalpy away with the mass), so the vessel is charged only the second line,
 * {@code (F - B)(h_v - h_ret)}, as a heat sink. If cooling water is short the cooler's duty is short, so {@code h_x}
 * rises and the demineraliser sees hot water.
 */
public final class RwcuLoop {
    private RwcuLoop() {}

    /** Regenerative exchanger effectiveness (on the lower heat capacity rate). */
    public static final double REGEN_EFFECTIVENESS = 0.92;
    /** Water temperature the non-regenerative exchanger cools to when it can, 120 F. */
    public static final double TARGET_C = 49.0;

    public record Result(double demineraliserC, double hotSideOutC, double returnC, double regenDutyMW, double coolerDutyMW,
                         double vesselHeatLossMW, double unregeneratedDutyMW, double rejectC) {
        public static final Result NONE = new Result(0, 0, 0, 0, 0, 0, 0, 0);
    }

    public static double enthalpy(double c) {
        return Saturation.subcooledLiquidEnthalpyKJPerKg(c);
    }

    public static double temperature(double h) {
        return WaterInventory.temperature(h);
    }

    /**
     * The duty (MW) the cooler must remove to bring the demineraliser water to {@link #TARGET_C} at this flow.
     */
    public static double coolerDutyNeededMW(double vesselC, double flow, double reject, double effectiveness) {
        if (!(flow > 0)) return 0;
        double r = Math.max(0, flow - reject) / flow;
        return flow * (1.0 - effectiveness * r) * Math.max(0, enthalpy(vesselC) - enthalpy(TARGET_C)) / 1000.0;
    }

    /**
     * @param vesselC        saturated vessel water temperature
     * @param flow           pump flow, kg/s
     * @param reject         blowdown share of it, kg/s (0..flow)
     * @param coolerLimitMW  the most the cooler can remove right now (rating and cooling water)
     * @param effectiveness  the regenerative exchanger's effectiveness (a parameter for tests and degradations)
     */
    public static Result solve(double vesselC, double flow, double reject, double coolerLimitMW, double effectiveness) {
        if (!(flow > 0) || !Double.isFinite(vesselC)) return Result.NONE;
        double cold = Math.max(0, flow - Math.max(0, reject));
        double r = cold / flow;
        double hv = enthalpy(vesselC), ht = enthalpy(TARGET_C);
        double gain = flow * (1.0 - effectiveness * r);
        double needKw = gain * Math.max(0, hv - ht);
        double dutyKw = Math.min(needKw, Math.max(0, coolerLimitMW) * 1000.0);
        double hx = gain > 0 ? hv - dutyKw / gain : hv;
        double qr = effectiveness * cold * (hv - hx);
        double hHotOut = hv - qr / flow;
        double hRet = cold > 0 ? hx + qr / cold : hx;
        double lossKw = cold * (hv - hRet);
        double unregenKw = cold * Math.max(0, hv - ht);
        return new Result(temperature(hx), temperature(hHotOut), temperature(hRet), qr / 1000.0, dutyKw / 1000.0,
                lossKw / 1000.0, unregenKw / 1000.0, temperature(hx));
    }

    public static Result solve(double vesselC, double flow, double reject, double coolerLimitMW) {
        return solve(vesselC, flow, reject, coolerLimitMW, REGEN_EFFECTIVENESS);
    }
}
