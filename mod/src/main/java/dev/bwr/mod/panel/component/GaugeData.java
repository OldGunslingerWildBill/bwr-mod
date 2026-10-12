package dev.bwr.mod.panel.component;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.List;

/**
 * Scale configuration shared by the round dial and the two edgewise meters:
 * range, units, up to {@link #BANDS} coloured bands and the needle damping.
 * No latched state. Input 0 is the measured value; the edgewise meters have an
 * optional input 1 for a demand marker.
 *
 * <p>A band is drawn when its high end is above its low end, so the default
 * "unused" band (0..0) costs nothing.
 */
public final class GaugeData extends ComponentData {
    public static final int BANDS = 3;
    public static final double LIMIT = 1.0e9;
    public static final int MAX_UNITS = 8;
    public static final double MAX_DAMPING = 5.0;

    public double min = 0.0;
    public double max = 100.0;
    public String units = "";
    /** Needle time constant in seconds on the client (0 = the needle follows instantly). */
    public double damping = 0.4;
    public final double[] bandLow = {80.0, 90.0, 0.0};
    public final double[] bandHigh = {90.0, 100.0, 0.0};
    public final int[] bandColor = {0xFFB000, 0xE02020, 0x30C030};

    /** Config fields for a gauge: scale, units, damping, bands. */
    public static List<ConfigField> schema() {
        List<ConfigField> fields = new ArrayList<>();
        fields.add(ConfigField.decimal("Min", "component.bwr.field.min", -LIMIT, LIMIT));
        fields.add(ConfigField.decimal("Max", "component.bwr.field.max", -LIMIT, LIMIT));
        fields.add(ConfigField.text("Units", "component.bwr.field.units", MAX_UNITS));
        fields.add(ConfigField.decimal("Damping", "component.bwr.field.damping", 0.0, MAX_DAMPING));
        for (int i = 1; i <= BANDS; i++) {
            fields.add(ConfigField.decimal("Band" + i + "Low", "component.bwr.field.band" + i + "_low", -LIMIT, LIMIT));
            fields.add(ConfigField.decimal("Band" + i + "High", "component.bwr.field.band" + i + "_high", -LIMIT, LIMIT));
            fields.add(ConfigField.color("Band" + i + "Color", "component.bwr.field.band" + i + "_color"));
        }
        return List.copyOf(fields);
    }

    /** The scale length; never zero, so a mis-set range cannot divide by zero. */
    public double span() {
        return max > min ? max - min : 1.0;
    }

    /** Position of {@code value} along the scale, 0..1. No signal pegs low (0). */
    public double fraction(double value) {
        if (Double.isNaN(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, (value - min) / span()));
    }

    /** No signal: the meter pegs low and shows the off-scale flag. */
    public static boolean offScale(double value) {
        return Double.isNaN(value);
    }

    @Override
    public void save(CompoundTag tag) {
        tag.putDouble("Min", min);
        tag.putDouble("Max", max);
        tag.putString("Units", units);
        tag.putDouble("Damping", damping);
        for (int i = 0; i < BANDS; i++) {
            tag.putDouble("Band" + (i + 1) + "Low", bandLow[i]);
            tag.putDouble("Band" + (i + 1) + "High", bandHigh[i]);
            tag.putInt("Band" + (i + 1) + "Color", bandColor[i]);
        }
    }

    @Override
    public void load(CompoundTag tag) {
        min = number(tag, "Min", 0.0, -LIMIT, LIMIT);
        max = number(tag, "Max", 100.0, -LIMIT, LIMIT);
        units = ConfigSchema.cleanText(tag.getString("Units"), MAX_UNITS);
        damping = number(tag, "Damping", 0.4, 0.0, MAX_DAMPING);
        double[] defaultLow = {80.0, 90.0, 0.0};
        double[] defaultHigh = {90.0, 100.0, 0.0};
        int[] defaultColor = {0xFFB000, 0xE02020, 0x30C030};
        for (int i = 0; i < BANDS; i++) {
            String key = "Band" + (i + 1);
            bandLow[i] = number(tag, key + "Low", defaultLow[i], -LIMIT, LIMIT);
            bandHigh[i] = number(tag, key + "High", defaultHigh[i], -LIMIT, LIMIT);
            bandColor[i] = tag.contains(key + "Color", Tag.TAG_ANY_NUMERIC) ? tag.getInt(key + "Color") & 0xFFFFFF
                    : defaultColor[i];
        }
    }

    static double number(CompoundTag tag, String key, double fallback, double low, double high) {
        if (!tag.contains(key, Tag.TAG_ANY_NUMERIC)) {
            return fallback;
        }
        double value = tag.getDouble(key);
        return Double.isFinite(value) ? Math.max(low, Math.min(high, value)) : fallback;
    }
}
