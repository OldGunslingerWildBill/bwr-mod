package dev.bwr.core.display;

/**
 * Display-only units for the on-screen readouts. The physics, the saved data, the network and the computer APIs keep
 * the values they always had; a screen converts at the last moment with one of these functions, chosen by the
 * player's {@link Mode}.
 *
 * <p>Each {@link Quantity} has a <b>base</b> unit: the unit the mod stores and displays today. The base is SI for
 * temperature (C), flow (kg/s), length (m), volume (m3), mass (kg) and power (MW), and the mod's own units for the two
 * that are not SI in the mod: pressure (psi, gauge or absolute) and water level (inches). Keeping those as bases is what
 * makes {@link Mode#METRIC} (the default, today's look) an exact identity: no value is converted, so no digit moves.
 *
 * <p>Pressure and power are never converted in any mode; they only change their label where the mode asks for one
 * (psig, psia, psi, MWth, MWt, MWe). Panel gauges are not converted at all (see the class comment of the mod's
 * {@code DisplayUnitReadouts}).
 */
public final class DisplayUnits {
    private DisplayUnits() {
    }

    /** The player's choice. METRIC is the default and is exactly what the screens have always shown. */
    public enum Mode { METRIC, IMPERIAL, PLANT }

    /** A physical quantity on a readout. The base unit of each is named on its constant. */
    public enum Quantity {
        /** Degrees Celsius in the mod. */
        TEMPERATURE,
        /** Gauge pressure, psi in the mod (the label is psig where the screen shows gauge pressure). */
        PRESSURE_GAUGE,
        /** Absolute pressure, psia. */
        PRESSURE_ABS,
        /** A pressure difference or rise, psi. */
        PRESSURE_DIFF,
        /** Mass flow, kg/s in the mod. */
        MASS_FLOW,
        /** Water flow, kg/s in the mod; shown as a volume flow (gpm) in the imperial modes, see {@link #waterFlow}. */
        WATER_FLOW,
        /** A water level, inches in the mod (the reactor's level indicator). */
        LEVEL,
        /** A length or height, m in the mod. */
        LENGTH,
        /** Volume, m3 in the mod. */
        VOLUME,
        /** Mass, kg in the mod. */
        MASS,
        /** Thermal power, MW in the mod (the label is MWth, or MWt in the plant mode). */
        POWER_THERMAL,
        /** Electrical power, MW in the mod (the label is MW, or MWe in the plant mode). */
        POWER_ELECTRIC
    }

    /** Exact US customary factors (NIST): 1 lb = 0.45359237 kg, 1 ft = 0.3048 m, 1 US gal = 3.785411784 L. */
    public static final double KG_PER_LB = 0.45359237;
    public static final double M_PER_FT = 0.3048;
    public static final double M3_PER_GAL = 0.003785411784;
    /** Seconds per hour and the million that the plant's Mlb/hr uses. */
    public static final double SECONDS_PER_HOUR = 3600.0;
    /** Inches per foot and metres per inch. */
    public static final double INCHES_PER_FOOT = 12.0;
    public static final double M_PER_INCH = 0.0254;
    /** Seconds per minute, for gallons per minute. */
    public static final double SECONDS_PER_MINUTE = 60.0;

    /** The unit label for a quantity in a mode. Pressure and power labels are the mod's own in every mode but power's plant form. */
    public static String unit(Mode mode, Quantity quantity) {
        switch (quantity) {
            case TEMPERATURE: return mode == Mode.METRIC ? "C" : "F";
            case PRESSURE_GAUGE: return "psig";
            case PRESSURE_ABS: return "psia";
            case PRESSURE_DIFF: return "psi";
            case MASS_FLOW:
                switch (mode) {
                    case METRIC: return "kg/s";
                    case IMPERIAL: return "lb/s";
                    default: return "Mlb/hr";
                }
            case WATER_FLOW: return mode == Mode.METRIC ? "kg/s" : "gpm";
            case LEVEL:
                switch (mode) {
                    case METRIC: return "in";
                    case IMPERIAL: return "ft";
                    default: return "in";
                }
            case LENGTH:
                switch (mode) {
                    case METRIC: return "m";
                    case IMPERIAL: return "ft";
                    default: return "in";
                }
            case VOLUME: return mode == Mode.METRIC ? "m3" : "gal";
            case MASS: return mode == Mode.METRIC ? "kg" : "lb";
            case POWER_THERMAL: return mode == Mode.PLANT ? "MWt" : "MWth";
            case POWER_ELECTRIC: return mode == Mode.PLANT ? "MWe" : "MW";
            default: throw new IllegalArgumentException("unknown quantity " + quantity);
        }
    }

    /**
     * {@code base} (in the quantity's base unit) shown in {@code mode}. Identity for {@link Mode#METRIC} and for the
     * quantities that are never converted. {@link Quantity#WATER_FLOW} must use {@link #waterFlow} instead.
     */
    public static double convert(Mode mode, Quantity quantity, double base) {
        if (mode == Mode.METRIC) {
            return base;
        }
        switch (quantity) {
            case TEMPERATURE: return base * 9.0 / 5.0 + 32.0;
            case MASS_FLOW:
                return mode == Mode.IMPERIAL
                        ? base / KG_PER_LB
                        : base * SECONDS_PER_HOUR / KG_PER_LB / 1.0e6;
            case LEVEL:
                // Inches in the base; feet for the imperial mode, inches for the plant.
                return mode == Mode.IMPERIAL ? base / INCHES_PER_FOOT : base;
            case LENGTH:
                // Metres in the base; feet for the imperial mode, inches for the plant.
                return mode == Mode.IMPERIAL ? base / M_PER_FT : base / M_PER_INCH;
            case VOLUME: return base / M3_PER_GAL;
            case MASS: return base / KG_PER_LB;
            case WATER_FLOW:
                throw new IllegalArgumentException("water flow needs a density: use waterFlow()");
            default:
                // Pressures and powers: the label changes, the number does not.
                return base;
        }
    }

    /**
     * Water flow of {@code kgPerS} at {@code densityKgPerM3}: kg/s in METRIC, US gallons per minute otherwise (the
     * volume flow the pump actually moves: mass flow over density).
     */
    public static double waterFlow(Mode mode, double kgPerS, double densityKgPerM3) {
        if (mode == Mode.METRIC) {
            return kgPerS;
        }
        double cubicMetresPerSecond = kgPerS / densityKgPerM3;
        return cubicMetresPerSecond * SECONDS_PER_MINUTE / M3_PER_GAL;
    }

    /** "value unit" with the given decimals, for a tooltip or a sentence. */
    public static String format(Mode mode, Quantity quantity, double base, int decimals) {
        return String.format(java.util.Locale.ROOT, "%." + Math.max(0, Math.min(4, decimals)) + "f %s",
                convert(mode, quantity, base), unit(mode, quantity));
    }
}
