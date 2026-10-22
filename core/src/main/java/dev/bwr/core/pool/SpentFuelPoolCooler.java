package dev.bwr.core.pool;

import dev.bwr.core.thermal.Saturation;
import dev.bwr.core.thermal.WaterInventory;

/**
 * The cooling train of a spent fuel pool: a circulation pump and a plate heat exchanger, as one unit built into
 * the pool's controller. Pool water goes through the primary side at {@value #PRIMARY_FLOW_KG_PER_S} kg/s and
 * comes back, cooler, to the same pool (the water inventory is unchanged, only heat leaves); the player supplies
 * cold water to the secondary side through an inlet port and takes the warmed water away from an outlet port,
 * exactly as with the RHR exchanger ({@link RhrHeatExchanger}), whose secondary buffers, design temperature range
 * and heat-transfer limit this reuses. The unit does not start itself: the owner calls {@link #step} only while
 * the player has the pump running and it has power.
 *
 * <p>Rating {@value #RATING_MW} MW. A real pool cooling train removes the decay heat of a refuelling offload, a
 * few MW (EPRI and plant FSARs quote 5 to 20 MW for the full-core-offload case); this is the low end so one train
 * is enough for a part core and not for a full one. Game-scale, not vendor data.
 */
public final class SpentFuelPoolCooler {

    public static final double PRIMARY_FLOW_KG_PER_S = 150.0;
    public static final double SECONDARY_FLOW_KG_PER_S = 400.0;
    public static final double RATING_MW = 6.0;
    public static final int CAPACITY_KG = RhrHeatExchanger.CAPACITY_KG;
    private static final double CP = SpentFuelPool.CP_KJ_PER_KG_K;
    private static final double COLD_C = RhrHeatExchanger.COLD_C;
    /** Highest rise of the cooling water across the exchanger, C. */
    private static final double DESIGN_RANGE_C = 11.0;

    private final WaterInventory cold = new WaterInventory(CAPACITY_KG, COLD_C);
    private final WaterInventory hot = new WaterInventory(CAPACITY_KG, COLD_C);
    private double primaryFlow;
    private double secondaryFlow;
    private double heatMW;

    public double coldKg() {
        return cold.mass();
    }

    public double hotKg() {
        return hot.mass();
    }

    public double coldEnthalpy() {
        return cold.enthalpy();
    }

    public double hotEnthalpy() {
        return hot.enthalpy();
    }

    public double coldTemperatureC() {
        return cold.temperatureC();
    }

    public double hotTemperatureC() {
        return hot.temperatureC();
    }

    public double primaryFlowKgPerS() {
        return primaryFlow;
    }

    public double secondaryFlowKgPerS() {
        return secondaryFlow;
    }

    public double heatMW() {
        return heatMW;
    }

    public double fillCold(double kg, double enthalpy, boolean simulate) {
        return cold.fill(kg, enthalpy, simulate);
    }

    public double drainHot(double kg, boolean simulate) {
        return hot.drain(kg, simulate);
    }

    public void clearReadouts() {
        primaryFlow = 0.0;
        secondaryFlow = 0.0;
        heatMW = 0.0;
    }

    public void restore(double coldKg, double coldH, double hotKg, double hotH) {
        cold.restore(coldKg, coldH);
        hot.restore(hotKg, hotH);
        clearReadouts();
    }

    /**
     * Run the loop for {@code seconds}: moves heat from the pool water into the secondary water, returning the
     * megajoules removed from the pool. Needs cold water in the inlet buffer, room in the hot buffer and water in
     * the pool above its suction.
     */
    public double step(SpentFuelPool pool, double seconds) {
        clearReadouts();
        if (pool == null || !(seconds > 0.0) || !Double.isFinite(seconds)) {
            return 0.0;
        }
        double primaryKg = Math.min(PRIMARY_FLOW_KG_PER_S * seconds, pool.availableSuctionKg());
        double secondaryKg = Math.min(cold.mass(), Math.min(SECONDARY_FLOW_KG_PER_S * seconds, hot.space()));
        if (primaryKg <= 0.0 || secondaryKg <= 0.0) {
            return 0.0;
        }
        double inlet = pool.temperatureC();
        double coldC = cold.temperatureC();
        double inH = cold.enthalpy();
        double requested = Math.min(RATING_MW * seconds,
                0.8 * Math.min(primaryKg, secondaryKg) * CP * Math.max(0.0, inlet - coldC) / 1000.0);
        double ceiling = Saturation.subcooledLiquidEnthalpyKJPerKg(Math.min(inlet, coldC + DESIGN_RANGE_C));
        requested = Math.min(requested, secondaryKg * Math.max(0.0, ceiling - inH) / 1000.0);
        double removed = pool.removeHeatMJ(requested, coldC);
        double outH = inH + removed * 1000.0 / secondaryKg;
        cold.drain(secondaryKg, false);
        hot.fill(secondaryKg, outH, false);
        primaryFlow = primaryKg / seconds;
        secondaryFlow = secondaryKg / seconds;
        heatMW = removed / seconds;
        return removed;
    }
}
