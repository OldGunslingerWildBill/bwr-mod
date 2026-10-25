package dev.bwr.core.chemistry;

import dev.bwr.core.thermal.Saturation;
import dev.bwr.core.thermal.WaterInventory;

/**
 * The cooling-water side of the non-regenerative exchanger: a finite inventory of cold water and a finite one of the
 * heated water waiting to be drained, like the RHR exchanger. The cooling water must be piped in; with none, the exchanger
 * removes nothing. Pure Java.
 */
public final class RwcuCooler {
    public static final int CAPACITY_KG = 3_000;
    public static final double RATING_MW = 8.0;
    public static final double SECONDARY_FLOW = 150.0;
    /** The most the cooling water is allowed to warm on one pass, K. */
    public static final double MAX_RISE_K = 20.0;
    public static final double COLD_C = 13.0;

    private final WaterInventory cold = new WaterInventory(CAPACITY_KG, COLD_C);
    private final WaterInventory hot = new WaterInventory(CAPACITY_KG, COLD_C);
    private double dutyMW;
    private double coolingFlow;
    private double outletC;

    public double cold() { return cold.mass(); }
    public double hot() { return hot.mass(); }
    public double coldH() { return cold.enthalpy(); }
    public double hotH() { return hot.enthalpy(); }
    public double coldTemperatureC() { return cold.temperatureC(); }
    public double hotTemperatureC() { return hot.temperatureC(); }
    public double dutyMW() { return dutyMW; }
    public double coolingFlowKgPerS() { return coolingFlow; }
    public double outletC() { return outletC; }
    public double fillCold(double kg, double h, boolean simulate) { return cold.fill(kg, h, simulate); }
    public double drainHot(double kg, boolean simulate) { return hot.drain(kg, simulate); }
    public void clearReadouts() { dutyMW = 0; coolingFlow = 0; outletC = 0; }

    private double riseKJPerKg() {
        double c = cold.temperatureC();
        return Saturation.subcooledLiquidEnthalpyKJPerKg(c + MAX_RISE_K) - Saturation.subcooledLiquidEnthalpyKJPerKg(c);
    }

    /** The most it can remove over {@code dt}, MW: the rating, the cooling water it holds and the room for it to go. */
    public double maxDutyMW(double dt) {
        if (!(dt > 0)) return 0;
        double kg = Math.min(cold.mass(), Math.min(SECONDARY_FLOW * dt, hot.space()));
        return Math.min(RATING_MW, kg * riseKJPerKg() / 1000.0 / dt);
    }

    /** Removes {@code dutyMW} for {@code dt}: cooling water passes and warms. Returns the duty actually taken. */
    public double commit(double dutyMW, double dt) {
        clearReadouts();
        if (!(dutyMW > 0) || !(dt > 0)) return 0;
        double duty = Math.min(dutyMW, maxDutyMW(dt));
        if (!(duty > 0)) return 0;
        double rise = riseKJPerKg();
        double kg = Math.min(cold.mass(), duty * 1000.0 * dt / rise);
        double inH = cold.enthalpy();
        double outH = inH + duty * 1000.0 * dt / kg;
        cold.drain(kg, false);
        hot.fill(kg, outH, false);
        this.dutyMW = duty;
        this.coolingFlow = kg / dt;
        this.outletC = WaterInventory.temperature(outH);
        return duty;
    }

    public void restore(double coldKg, double hotKg, double coldH, double hotH) {
        cold.restore(coldKg, coldH);
        hot.restore(hotKg, hotH);
        clearReadouts();
    }
}
