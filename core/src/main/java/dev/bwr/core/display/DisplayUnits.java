package dev.bwr.core.display;

import java.util.Locale;

/**
 * Display-only units for the on-screen readouts. The physics, the saved data, the network and the computer APIs keep
 * the values they always had; a screen converts at the last moment with one of these functions, chosen by the
 * player's {@link Mode}.
 *
 * <p>Each {@link Quantity} has a <b>base</b> unit: the unit the mod stores and displays today. Temperature (C), mass
 * flow (kg/s), length (m), volume (m3), mass (kg) and power (MW) are SI already. Pressure (psi) and water level (inches)
 * are the mod's own units, so they are kept as bases: that is what makes {@link Mode#METRIC} (the default, today's look)
 * an exact identity, with no digit moved.
 *
 * <p>Pressure is converted only in {@link Mode#SI}, and there the unit depends on the value (kPa, switching to MPa
 * above 1,000 kPa), so SI pressure goes through {@link #pressureSi} and {@link #pressureSiUnit}, never through
 * {@link #convert} or {@link #unit}. In the other modes pressure keeps psi and only its label changes.
 *
 * <p>Panel gauges are not converted in any mode (see the class comment of the mod's {@code DisplayUnitReadouts}).
 */
public final class DisplayUnits {
    private DisplayUnits() {
    }

    /**
     * The player's choice. METRIC is the default and is exactly what the screens have always shown. SI is the metric
     * system for the same quantities: kPa and MPa, metres, degrees Celsius, kg/s and m3/h.
     */
    public enum Mode { METRIC, IMPERIAL, PLANT, SI }

    /** A physical quantity on a readout. The base unit of each is named on its constant. */
    public enum Quantity {
        /** Degrees Celsius in the mod. */
        TEMPERATURE,
        /** Gauge pressure, psi in the mod (the label is psig where the screen shows gauge pressure). */
        PRESSURE_GAUGE,
        /** Absolute pressure, psia. */
        PRESSURE_ABS,
        /** A pressure difference or rise across a pump, psi. */
        PRESSURE_DIFF,
        /** The reactor vessel's pressure as a pump reads it: gauge pressure, labelled psi in METRIC as always. */
        PRESSURE_VESSEL,
        /** A rate of pressure change, psi per second in the mod. */
        PRESSURE_RATE,
        /** Mass flow, kg/s in the mod. */
        MASS_FLOW,
        /** Water flow, kg/s in the mod; shown as a volume flow, see {@link #waterFlow}. */
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
    /** Exact: 1 psi = 6.894757 kPa (the figure the request fixes). */
    public static final double KPA_PER_PSI = 6.894757;
    /** Exact: 1 inch = 0.0254 m. */
    public static final double M_PER_INCH = 0.0254;
    /** Kilopascals above which SI pressure is written in MPa instead. */
    public static final double KPA_SWITCH_TO_MPA = 1000.0;
    /** Seconds per hour, for the plant's Mlb/hr and the SI m3/h. */
    public static final double SECONDS_PER_HOUR = 3600.0;
    /** Inches per foot. */
    public static final double INCHES_PER_FOOT = 12.0;
    /** Seconds per minute, for gallons per minute. */
    public static final double SECONDS_PER_MINUTE = 60.0;

    private static final String DEGREE_CELSIUS = "°C";
    private static final String CUBIC_METRE = "m³";

    /** True for the quantities that are pressures (not the rate of change of one). */
    public static boolean isPressure(Quantity quantity) {
        return quantity == Quantity.PRESSURE_GAUGE || quantity == Quantity.PRESSURE_ABS
                || quantity == Quantity.PRESSURE_DIFF || quantity == Quantity.PRESSURE_VESSEL;
    }

    /**
     * The unit label for a quantity in a mode. Pressures have no single label in SI (kPa or MPa depends on the value):
     * use {@link #pressureSiUnit}, which throws here.
     */
    public static String unit(Mode mode, Quantity quantity) {
        if (mode == Mode.SI && isPressure(quantity)) {
            throw new IllegalArgumentException(quantity + " in SI depends on the value: use pressureSiUnit()");
        }
        switch (quantity) {
            case TEMPERATURE:
                if (mode == Mode.SI) return DEGREE_CELSIUS;
                return mode == Mode.METRIC ? "C" : "F";
            case PRESSURE_GAUGE: return "psig";
            case PRESSURE_ABS: return "psia";
            case PRESSURE_DIFF: return "psi";
            case PRESSURE_VESSEL:
                // The vessel row has always said "psi" in METRIC; the other modes say psig, the gauge pressure it is.
                return mode == Mode.METRIC ? "psi" : "psig";
            case PRESSURE_RATE:
                return mode == Mode.SI ? "kPa/s" : "psi/s";
            case MASS_FLOW:
                switch (mode) {
                    case METRIC: return "kg/s";
                    case IMPERIAL: return "lb/s";
                    case SI: return "kg/s";
                    default: return "Mlb/hr";
                }
            case WATER_FLOW:
                if (mode == Mode.METRIC) return "kg/s";
                return mode == Mode.SI ? CUBIC_METRE + "/h" : "gpm";
            case LEVEL:
                switch (mode) {
                    case METRIC: return "in";
                    case IMPERIAL: return "ft";
                    case SI: return "m";
                    default: return "in";
                }
            case LENGTH:
                switch (mode) {
                    case METRIC: return "m";
                    case IMPERIAL: return "ft";
                    case SI: return "m";
                    default: return "in";
                }
            case VOLUME:
                if (mode == Mode.METRIC) return "m3";
                return mode == Mode.SI ? CUBIC_METRE : "gal";
            case MASS: return mode == Mode.METRIC || mode == Mode.SI ? "kg" : "lb";
            case POWER_THERMAL: return mode == Mode.PLANT ? "MWt" : "MWth";
            case POWER_ELECTRIC: return mode == Mode.PLANT ? "MWe" : "MW";
            default: throw new IllegalArgumentException("unknown quantity " + quantity);
        }
    }

    /**
     * {@code base} (in the quantity's base unit) shown in {@code mode}. Identity for {@link Mode#METRIC}, and for the
     * quantities that are never converted in the other modes. {@link Quantity#WATER_FLOW} must use {@link #waterFlow}
     * instead, and SI pressure must use {@link #pressureSi}.
     */
    public static double convert(Mode mode, Quantity quantity, double base) {
        if (mode == Mode.METRIC) {
            return base;
        }
        if (quantity == Quantity.WATER_FLOW) {
            throw new IllegalArgumentException("water flow needs a density: use waterFlow()");
        }
        if (mode == Mode.SI && isPressure(quantity)) {
            throw new IllegalArgumentException(quantity + " in SI depends on the value: use pressureSi()");
        }
        switch (quantity) {
            case TEMPERATURE: return mode == Mode.SI ? base : base * 9.0 / 5.0 + 32.0;
            case MASS_FLOW:
                if (mode == Mode.SI) return base;
                return mode == Mode.IMPERIAL
                        ? base / KG_PER_LB
                        : base * SECONDS_PER_HOUR / KG_PER_LB / 1.0e6;
            case LEVEL:
                // Inches in the base; metres for SI, feet for the imperial mode, inches for the plant.
                if (mode == Mode.SI) return base * M_PER_INCH;
                return mode == Mode.IMPERIAL ? base / INCHES_PER_FOOT : base;
            case LENGTH:
                // Metres in the base; metres for SI, feet for the imperial mode, inches for the plant.
                if (mode == Mode.SI) return base;
                return mode == Mode.IMPERIAL ? base / M_PER_FT : base / M_PER_INCH;
            case VOLUME:
                if (mode == Mode.SI) return base;
                return base / M3_PER_GAL;
            case MASS:
                if (mode == Mode.SI) return base;
                return base / KG_PER_LB;
            case PRESSURE_RATE:
                // psi per second becomes kPa per second in SI; no MPa switch for a rate.
                return mode == Mode.SI ? base * KPA_PER_PSI : base;
            default:
                // Non-SI pressures and powers: the label changes, the number does not.
                return base;
        }
    }

    /** Pressure in kPa from psi, by the exact factor. */
    public static double pressureKpa(double psi) {
        return psi * KPA_PER_PSI;
    }

    /** True when SI pressure of {@code psi} is written in MPa (above 1,000 kPa). A NaN stays in kPa. */
    public static boolean pressureUsesMpa(double psi) {
        return pressureKpa(psi) > KPA_SWITCH_TO_MPA;
    }

    /**
     * SI pressure for {@code quantity} at {@code psi}: kPa, or MPa above 1,000 kPa. Gauge and vessel pressures are
     * gauge, absolute is absolute, and a difference is just a difference (no suffix).
     */
    public static double pressureSi(Quantity quantity, double psi) {
        requirePressure(quantity);
        double kpa = pressureKpa(psi);
        return pressureUsesMpa(psi) ? kpa / 1000.0 : kpa;
    }

    /** The SI label that goes with {@link #pressureSi}: kPa(g), kPa(a), kPa, or the MPa form above 1,000 kPa. */
    public static String pressureSiUnit(Quantity quantity, double psi) {
        requirePressure(quantity);
        String prefix = pressureUsesMpa(psi) ? "MPa" : "kPa";
        switch (quantity) {
            case PRESSURE_GAUGE:
            case PRESSURE_VESSEL:
                return prefix + "(g)";
            case PRESSURE_ABS:
                return prefix + "(a)";
            default:
                return prefix;
        }
    }

    private static void requirePressure(Quantity quantity) {
        if (!isPressure(quantity)) {
            throw new IllegalArgumentException(quantity + " is not a pressure");
        }
    }

    /**
     * Water flow of {@code kgPerS} at {@code densityKgPerM3}: kg/s in METRIC, m3/h in SI, and US gallons per minute in
     * the imperial and plant modes. The volume flow the pump moves is mass flow over density.
     */
    public static double waterFlow(Mode mode, double kgPerS, double densityKgPerM3) {
        if (mode == Mode.METRIC) {
            return kgPerS;
        }
        double cubicMetresPerSecond = kgPerS / densityKgPerM3;
        if (mode == Mode.SI) {
            return cubicMetresPerSecond * SECONDS_PER_HOUR;
        }
        return cubicMetresPerSecond * SECONDS_PER_MINUTE / M3_PER_GAL;
    }

    /** "value unit" with the given decimals, for a tooltip or a sentence. Not for SI pressure (see pressureSiUnit). */
    public static String format(Mode mode, Quantity quantity, double base, int decimals) {
        return String.format(Locale.ROOT, "%." + Math.max(0, Math.min(4, decimals)) + "f %s",
                convert(mode, quantity, base), unit(mode, quantity));
    }
}
