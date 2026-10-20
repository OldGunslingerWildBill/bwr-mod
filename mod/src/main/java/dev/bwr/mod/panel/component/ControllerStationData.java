package dev.bwr.mod.panel.component;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/**
 * Manual/auto controller station: its scale (the span the process variable and the setpoint are shown over), the units
 * text, how far one press of RAISE or LOWER moves things, and the latched state: the mode, the manual demand and the
 * local setpoint.
 *
 * <p>The local setpoint is kept as a fraction of the span ({@link #spFraction}), so changing the span does not move the
 * pointer; the output reports it in the scale's own units ({@link #setpointValue}). Everything is saved only when it is not
 * its default, and a missing key loads as the default, so the data of a station placed before a field existed (and the
 * wrench's merge of the whole saved tag) loads back identically.
 */
public final class ControllerStationData extends ComponentData {
    public static final String TYPE = "controller_station";
    public static final double LIMIT = GaugeData.LIMIT;
    public static final int MAX_UNITS = 8;
    public static final double DEFAULT_MIN = 0.0;
    public static final double DEFAULT_MAX = 100.0;
    /** Percent of the demand per press of RAISE / LOWER (and per scroll notch) in MAN. */
    public static final double DEFAULT_STEP = 1.0;
    public static final double MIN_STEP = 0.1;
    public static final double MAX_STEP = 25.0;
    /** Percent of the span per press (and per scroll notch) in AUTO with a local setpoint. */
    public static final double DEFAULT_SP_STEP = 1.0;
    public static final double DEFAULT_SP_FRACTION = 0.5;

    public double min = DEFAULT_MIN;
    public double max = DEFAULT_MAX;
    public String units = "";
    public double step = DEFAULT_STEP;
    public double spStep = DEFAULT_SP_STEP;

    /** Latched: true in AUTO, false in MAN (the default). */
    public boolean auto;
    /** Latched: the station's own demand, 0..100 percent (what RAISE and LOWER move in MAN). */
    public double demand;
    /** Latched: the local setpoint as a fraction of the span, 0..1. */
    public double spFraction = DEFAULT_SP_FRACTION;

    public double span() {
        return max > min ? max - min : 1.0;
    }

    /** Position of {@code value} on the scale, 0..1 (NaN: 0). */
    public double fraction(double value) {
        return Double.isNaN(value) ? 0.0 : Math.max(0.0, Math.min(1.0, (value - min) / span()));
    }

    /** The local setpoint in the scale's units. */
    public double setpointValue() {
        return min + Math.max(0.0, Math.min(1.0, spFraction)) * span();
    }

    @Override
    public void save(CompoundTag tag) {
        tag.putDouble("Min", min);
        tag.putDouble("Max", max);
        if (!units.isEmpty()) {
            tag.putString("Units", units);
        }
        tag.putDouble("Step", step);
        tag.putDouble("SpStep", spStep);
        if (auto) {
            tag.putBoolean("Auto", true);
        }
        if (demand != 0.0) {
            tag.putDouble("Demand", demand);
        }
        if (spFraction != DEFAULT_SP_FRACTION) {
            tag.putDouble("SpFraction", spFraction);
        }
    }

    @Override
    public void load(CompoundTag tag) {
        min = GaugeData.number(tag, "Min", DEFAULT_MIN, -LIMIT, LIMIT);
        max = GaugeData.number(tag, "Max", DEFAULT_MAX, -LIMIT, LIMIT);
        units = ConfigSchema.cleanText(tag.getString("Units"), MAX_UNITS);
        step = GaugeData.number(tag, "Step", DEFAULT_STEP, MIN_STEP, MAX_STEP);
        spStep = GaugeData.number(tag, "SpStep", DEFAULT_SP_STEP, MIN_STEP, MAX_STEP);
        auto = tag.getBoolean("Auto");
        demand = GaugeData.number(tag, "Demand", 0.0, 0.0, 100.0);
        spFraction = GaugeData.number(tag, "SpFraction", DEFAULT_SP_FRACTION, 0.0, 1.0);
    }
}
