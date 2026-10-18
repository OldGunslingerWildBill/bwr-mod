package dev.bwr.mod.signal;

import java.util.List;

/**
 * Power and energy units for gauges, converted by the binding's own scale ({@code PlantSource}: displayed =
 * raw x scale + offset), so the conversion costs nothing at run time.
 *
 * <p>One FE is {@code feToJoules} joules (default 2.5, a server setting). A signal in FE per tick is
 * {@code 20 x feToJoules} watts per unit (100 FE/t x 20 x 2.5 = 5000 W = 5 kW); one in FE per second is
 * {@code feToJoules} watts per unit. Then kW is W / 1000 and MW is W / 1,000,000. Stored energy (FE) is not power: it
 * is offered in kJ and MJ (x feToJoules joules per FE) and never in W.
 */
public final class PowerUnits {
    private PowerUnits() {
    }

    public static final String RAW = "Raw";
    public static final String WATT = "W";
    public static final String KILOWATT = "kW";
    public static final String MEGAWATT = "MW";
    public static final String KILOJOULE = "kJ";
    public static final String MEGAJOULE = "MJ";

    /** Every choice of the {@code PowerUnits} gauge setting; {@link #offers} says which apply to a signal. */
    public static final List<String> OPTIONS = List.of(RAW, WATT, KILOWATT, MEGAWATT, KILOJOULE, MEGAJOULE);

    /** How an FE rate signal counts time. */
    public static final String PER_TICK = "tick";
    public static final String PER_SECOND = "second";
    public static final List<String> RATES = List.of(PER_TICK, PER_SECOND);

    /** Ticks in a second. */
    public static final double TICKS_PER_SECOND = 20.0;

    /** The choices that apply to a signal of this quantity: power takes Raw, W, kW, MW; energy Raw, kJ, MJ; others only Raw. */
    public static List<String> offeredFor(Quantity quantity) {
        if (quantity == Quantity.POWER) {
            return List.of(RAW, WATT, KILOWATT, MEGAWATT);
        }
        if (quantity == Quantity.ENERGY) {
            return List.of(RAW, KILOJOULE, MEGAJOULE);
        }
        return List.of(RAW);
    }

    /** Whether {@code choice} may be picked for a signal of this quantity (W never for stored energy). */
    public static boolean offers(Quantity quantity, String choice) {
        return offeredFor(quantity).contains(choice);
    }

    /**
     * Whether an FE rate signal is ambiguous about its time base: a plain FE unit on a key that names a rate. Such a
     * signal gets the Per tick / Per second selector; FE/t and FE/s say it themselves.
     */
    public static boolean ambiguousRate(String unit, String key) {
        return unit.equals("FE") && (key.contains("rate") || key.contains("per_"));
    }

    /**
     * The scale that turns the raw signal into {@code choice}, or 1 for Raw (or when the choice does not apply to the
     * unit). {@code perSecond} only matters for an ambiguous FE rate; FE/t and FE/s decide for themselves.
     */
    public static double scale(String unit, String key, String choice, boolean perSecond, double feToJoules) {
        double joulesPerUnit = joulesPerUnit(unit, key, perSecond, feToJoules);
        if (Double.isNaN(joulesPerUnit)) {
            return 1.0;
        }
        return switch (choice) {
            case WATT -> joulesPerUnit;
            case KILOWATT, KILOJOULE -> joulesPerUnit / 1.0e3;
            case MEGAWATT, MEGAJOULE -> joulesPerUnit / 1.0e6;
            default -> 1.0;
        };
    }

    /** Watts (or joules, for energy) in one unit of the signal; NaN when the unit is not one of power or energy. */
    private static double joulesPerUnit(String unit, String key, boolean perSecond, double fe) {
        return switch (unit) {
            case "W" -> 1.0;
            case "kW" -> 1.0e3;
            case "MW" -> 1.0e6;
            case "FE/t" -> TICKS_PER_SECOND * fe;
            case "FE/s" -> fe;
            case "FE" -> ambiguousRate(unit, key) ? (perSecond ? fe : TICKS_PER_SECOND * fe) : fe;
            case "MJ" -> 1.0e6;
            case "MWh" -> 3.6e9;
            default -> Double.NaN;
        };
    }

    /** The text a gauge shows as its units for {@code choice} (the signal's own unit for Raw). */
    public static String unitsText(String choice, String rawUnit) {
        return RAW.equals(choice) ? rawUnit : choice;
    }
}
