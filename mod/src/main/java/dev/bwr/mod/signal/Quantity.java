package dev.bwr.mod.signal;

import java.util.Locale;

/**
 * What a signal measures, derived from its {@link SignalSpec#unit() unit} and {@link Kind}: one table, here. A gauge
 * that declares what it {@code Measures} only takes signals of that quantity, so a water level gauge can never be
 * wired to a kW signal, however the wiring is made (a line link or a CC port).
 *
 * <table>
 *   <caption>Units by quantity</caption>
 *   <tr><td>LEVEL</td><td>in, m, mB; fraction or % when the key contains "level"</td></tr>
 *   <tr><td>PRESSURE</td><td>psig, psi, psia, inHg</td></tr>
 *   <tr><td>TEMPERATURE</td><td>degC, degF</td></tr>
 *   <tr><td>RATE</td><td>degC/h</td></tr>
 *   <tr><td>FLOW</td><td>kg/s, gpm, Mlb/hr</td></tr>
 *   <tr><td>POWER</td><td>MW, W, kW, FE/t, FE/s</td></tr>
 *   <tr><td>ENERGY</td><td>FE, MJ, MWh</td></tr>
 *   <tr><td>POSITION</td><td>fraction open, notch, rod</td></tr>
 *   <tr><td>PERCENT</td><td>%, fraction, % rated, fraction of rated</td></tr>
 *   <tr><td>SPEED</td><td>rpm</td></tr>
 *   <tr><td>CHEMISTRY</td><td>ppm, ppm/min</td></tr>
 *   <tr><td>COUNT</td><td>everything else</td></tr>
 *   <tr><td>BOOLEAN</td><td>the BOOLEAN kind</td></tr>
 *   <tr><td>TEXT</td><td>the TEXT kind</td></tr>
 *   <tr><td>DOSE_RATE</td><td>mSv/h, uSv/h, Sv/h</td></tr>
 *   <tr><td>VIBRATION</td><td>mm/s</td></tr>
 * </table>
 */
public enum Quantity {
    LEVEL, PRESSURE, TEMPERATURE, RATE, FLOW, POWER, ENERGY, POSITION, PERCENT, SPEED, CHEMISTRY, COUNT, BOOLEAN, TEXT,
    /** Radiation dose rate (mSv/h). Last, so every existing ordinal (the wire form) keeps its meaning. */
    DOSE_RATE,
    /** Shaft vibration velocity (mm/s). Appended after DOSE_RATE, so no earlier ordinal moved. */
    VIBRATION;

    /** The quantity of a signal. */
    public static Quantity of(SignalSpec spec) {
        return of(spec.key(), spec.unit(), spec.kind());
    }

    public static Quantity of(String key, String unit, Kind kind) {
        if (kind == Kind.BOOLEAN) {
            return BOOLEAN;
        }
        if (kind == Kind.TEXT) {
            return TEXT;
        }
        switch (unit) {
            case "in", "m", "mB":
                return LEVEL;
            case "psig", "psi", "psia", "inHg":
                return PRESSURE;
            case "degC", "degF":
                return TEMPERATURE;
            case "degC/h":
                return RATE;
            case "kg/s", "gpm", "Mlb/hr":
                return FLOW;
            case "MW", "W", "kW", "FE/t", "FE/s":
                return POWER;
            case "FE":
                // Stored energy, unless the key says it is a rate (then it is power with an unsaid time base).
                return PowerUnits.ambiguousRate(unit, key) ? POWER : ENERGY;
            case "MJ", "MWh":
                return ENERGY;
            case "fraction open", "notch", "rod":
                return POSITION;
            case "%", "fraction", "% rated", "fraction of rated":
                return key.toLowerCase(Locale.ROOT).contains("level") ? LEVEL : PERCENT;
            case "rpm":
                return SPEED;
            case "ppm", "ppm/min":
                return CHEMISTRY;
            case "mSv/h", "uSv/h", "µSv/h", "Sv/h":
                return DOSE_RATE;
            case "mm/s":
                return VIBRATION;
            default:
                return COUNT;
        }
    }

    /** The quantity with this ordinal, or null (the wire form of "unknown"). */
    public static Quantity byId(int id) {
        Quantity[] all = values();
        return id >= 0 && id < all.length ? all[id] : null;
    }

    /** The quantity named {@code name} (any case), or null. */
    public static Quantity byName(String name) {
        for (Quantity quantity : values()) {
            if (quantity.name().equalsIgnoreCase(name)) {
                return quantity;
            }
        }
        return null;
    }
}
