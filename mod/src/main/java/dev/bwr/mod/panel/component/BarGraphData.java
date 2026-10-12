package dev.bwr.mod.panel.component;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/** Bar graph configuration: the scale, the warn and alarm levels, and the normal segment colour. No latched state. */
public final class BarGraphData extends ComponentData {
    public static final double LIMIT = 1.0e9;
    public static final int DEFAULT_COLOR = 0x30D060;

    public double min;
    public double max = 100.0;
    public double warn = 75.0;
    public double alarm = 90.0;
    public int color = DEFAULT_COLOR;

    private static double read(CompoundTag tag, String key, double fallback) {
        double value = tag.getDouble(key);
        return tag.contains(key, Tag.TAG_ANY_NUMERIC) && Double.isFinite(value) ? Math.max(-LIMIT, Math.min(LIMIT, value)) : fallback;
    }

    @Override
    public void save(CompoundTag tag) {
        tag.putDouble("Min", min);
        tag.putDouble("Max", max);
        tag.putDouble("Warn", warn);
        tag.putDouble("Alarm", alarm);
        tag.putInt("Color", color);
    }

    @Override
    public void load(CompoundTag tag) {
        min = read(tag, "Min", 0.0);
        max = read(tag, "Max", 100.0);
        if (!(max > min)) {
            max = min + 1.0; // an empty or inverted scale would divide by zero
        }
        warn = read(tag, "Warn", 75.0);
        alarm = read(tag, "Alarm", 90.0);
        color = tag.contains("Color", Tag.TAG_ANY_NUMERIC) ? tag.getInt("Color") & 0xFFFFFF : DEFAULT_COLOR;
    }
}
