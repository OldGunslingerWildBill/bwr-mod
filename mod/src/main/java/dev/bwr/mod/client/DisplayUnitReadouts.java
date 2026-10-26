package dev.bwr.mod.client;

import dev.bwr.core.display.DisplayUnits;
import dev.bwr.core.display.DisplayUnits.Quantity;
import dev.bwr.core.thermal.Saturation;

/**
 * The screens' one door to the display units: the player's choice ({@code displayUnits} in bwr-visuals.toml) applied to
 * a value the mod holds in its base unit. Client only, and display only: nothing here is read by the physics, saved,
 * sent over the network or returned to a computer.
 *
 * <p>Panel gauges are deliberately not converted. Their scales are numbers the player set and that are saved in the
 * component's own units, so relabelling them would make the printed scale disagree with the setpoints wired to it.
 * The control panel stays in the units it has always used in every mode.
 */
public final class DisplayUnitReadouts {
    private DisplayUnitReadouts() {
    }

    /** Water at 20 C, for a pump whose temperature is not reported yet. */
    private static final double FALLBACK_WATER_DENSITY_KG_PER_M3 = 998.2;

    /** The mode the screens use right now. */
    public static DisplayUnits.Mode mode() {
        return VisualEffectsConfig.displayUnits();
    }

    /** {@code base} in the base unit of {@code quantity} shown in the current mode. */
    public static double value(Quantity quantity, double base) {
        return DisplayUnits.convert(mode(), quantity, base);
    }

    /** The unit label of {@code quantity} in the current mode. */
    public static String unit(Quantity quantity) {
        return DisplayUnits.unit(mode(), quantity);
    }

    /**
     * A steam or feed flow as a whole-number readout, grouped, from kg/s. Plant mode prints Mlb/hr with two decimals:
     * a whole number of Mlb/hr would hide the difference between 12.8 and 13 (which is most of a plant's steam).
     */
    public static String massFlow(double kgPerS) {
        return String.format(java.util.Locale.ROOT, mode() == DisplayUnits.Mode.PLANT ? "%,.2f" : "%,.0f",
                value(Quantity.MASS_FLOW, kgPerS));
    }

    /** A whole number with thousands separators and its unit, for a tooltip: "1,000 C" in METRIC. */
    public static String withUnit(Quantity quantity, double base) {
        return String.format(java.util.Locale.ROOT, "%,.0f %s", value(quantity, base), unit(quantity));
    }

    /**
     * A pressure of {@code psi} as the number on a row. Outside SI the number is {@code psi} itself, written with
     * {@code decimals} places (grouped when asked), exactly as the row always wrote it. SI converts to kPa, or MPa above
     * 1,000 kPa (two places), and writes kPa with one place.
     */
    public static String pressure(Quantity quantity, double psi, int decimals, boolean grouped) {
        DisplayUnits.Mode mode = mode();
        if (mode != DisplayUnits.Mode.SI) {
            return String.format(java.util.Locale.ROOT, (grouped ? "%,." : "%.") + decimals + "f",
                    DisplayUnits.convert(mode, quantity, psi));
        }
        return String.format(java.util.Locale.ROOT, DisplayUnits.pressureUsesMpa(psi) ? "%.2f" : "%.1f",
                DisplayUnits.pressureSi(quantity, psi));
    }

    /** The unit label beside {@link #pressure}: the mode's own, or kPa(g) / MPa(g) and the like in SI. */
    public static String pressureUnit(Quantity quantity, double psi) {
        DisplayUnits.Mode mode = mode();
        return mode == DisplayUnits.Mode.SI ? DisplayUnits.pressureSiUnit(quantity, psi) : DisplayUnits.unit(mode, quantity);
    }

    /** A pressure with thousands separators and its unit, for a tooltip: "1,250 psig" outside SI, "8.62 MPa(g)" in SI. */
    public static String withPressure(Quantity quantity, double psi) {
        return pressure(quantity, psi, 0, true) + " " + pressureUnit(quantity, psi);
    }

    /** A rate of pressure change (psi per second in the mod) as a number in the current mode, with {@code decimals} places. */
    public static String pressureRate(double psiPerS, int decimals) {
        return String.format(java.util.Locale.ROOT, "%." + decimals + "f",
                value(Quantity.PRESSURE_RATE, psiPerS));
    }

    /**
     * Water flow of {@code kgPerS} in the current mode. The volume flow needs the water's density at its temperature
     * ({@code waterTemperatureC}; NaN falls back to water at 20 C).
     */
    public static double waterFlow(double kgPerS, double waterTemperatureC) {
        return DisplayUnits.waterFlow(mode(), kgPerS, waterDensityKgPerM3(waterTemperatureC));
    }

    /**
     * Saturated liquid density at {@code temperatureC}, from the same correlations the reactor uses. The temperature is
     * kept inside the range the correlation is written for, and a missing temperature reads as water at 20 C.
     */
    public static double waterDensityKgPerM3(double temperatureC) {
        if (Double.isNaN(temperatureC) || Double.isInfinite(temperatureC)) {
            return FALLBACK_WATER_DENSITY_KG_PER_M3;
        }
        double clamped = Math.max(20.0, Math.min(370.0, temperatureC));
        return Saturation.liquidDensityKgPerM3(Saturation.pressurePsiaFromTemperatureCelsius(clamped));
    }
}
