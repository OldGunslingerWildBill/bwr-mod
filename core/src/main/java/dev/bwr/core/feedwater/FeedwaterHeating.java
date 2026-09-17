package dev.bwr.core.feedwater;

import dev.bwr.core.PhysicalConstants;

/**
 * Final feedwater temperature as a function of how hard the plant is running.
 *
 * <h2>Why this is not simply the temperature of the water in the pipe</h2>
 * A real BWR does not put condenser-temperature water into the vessel. Five or
 * six stages of feedwater heaters sit between the condenser and the vessel,
 * every one of them heated by steam extracted from the main turbine, and they
 * lift the feedwater from about 32 °C to
 * {@value dev.bwr.core.PhysicalConstants#RATED_FEEDWATER_TEMPERATURE_C} °C by
 * the time it arrives.
 *
 * <p>That is not a detail. Feedwater subcooling is a reactivity lever: colder
 * feedwater collapses more void in the downcomer, which adds moderator, which
 * adds reactivity. Feeding a plant condenser-cold water at rated flow would
 * cost roughly a quarter of rated thermal power just to heat it to saturation,
 * and rated steam flow would be unreachable. A plant with feed pumps and no
 * heater string genuinely cannot make rated power, which is why every real
 * plant has one.
 *
 * <h2>The model, stated honestly</h2>
 * There is no heater block in this mod, so the heater string is modelled rather
 * than built. Extraction steam is drawn from the main turbine, so the heating
 * duty scales with turbine load, which at steady state scales with steam flow,
 * which is feedwater flow. The temperature is therefore interpolated linearly
 * between the suction temperature at no flow and rated feedwater temperature at
 * rated flow.
 *
 * <p>Linear is an approximation — a real heater string is a series of terminal
 * temperature differences and is not quite straight — but it is exact at both
 * ends, it is monotonic, and it reproduces the behaviour that matters: a plant
 * coming up from cold feeds cold water, and anything that cuts feedwater flow
 * also cuts feedwater temperature, adding positive reactivity at the same time
 * as it takes inventory away. Loss of feedwater heating is a real BWR transient
 * and it is one where power goes <i>up</i>.
 *
 * <p>Nothing here is a control action. It reports a temperature; it does not
 * decide anything, throttle anything or protect anything.
 *
 * <p>Pure Java, no Minecraft imports.
 */
public final class FeedwaterHeating {

    private FeedwaterHeating() {
    }

    /**
     * Temperature feedwater actually arrives at, degrees C.
     *
     * @param suctionTemperatureC temperature of the water the pumps are taking,
     *                            degrees C — condensate, so cold
     * @param flowFractionOfRated total plant feedwater flow as a fraction of
     *                            {@link FeedwaterDesign#RATED_FEEDWATER_FLOW_KG_PER_S},
     *                            clamped into 0..1. Above rated the heaters are
     *                            already saturated, so more flow buys no more
     *                            temperature
     * @return the suction temperature at no flow, rising to rated feedwater
     *         temperature at rated flow. Never below the suction temperature —
     *         a heater string cannot cool anything
     */
    public static double finalTemperatureC(double suctionTemperatureC, double flowFractionOfRated) {
        double suction = Double.isFinite(suctionTemperatureC) ? suctionTemperatureC : 0.0;
        double fraction = Double.isFinite(flowFractionOfRated)
                ? Math.min(1.0, Math.max(0.0, flowFractionOfRated)) : 0.0;
        double rated = PhysicalConstants.RATED_FEEDWATER_TEMPERATURE_C;
        if (suction >= rated) {
            // Water already hotter than the heater string could make it goes
            // through unchanged. Guarding the direction rather than the value
            // keeps this a heater and stops it becoming a cooler.
            return suction;
        }
        return suction + (rated - suction) * fraction;
    }
}
