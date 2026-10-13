package dev.bwr.mod.panel.component;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/** Thumbwheel: digit colour and the latched value. The digit count is the component's width in cells divided by
 * {@link ThumbwheelType#DIGIT_W}. */
public final class ThumbwheelData extends ComponentData {
    public static final int MAX_DIGITS = 8;
    public static final long LIMIT = 100_000_000L;
    public static final int DEFAULT_COLOR = 0xF0F0F0;

    public int color = DEFAULT_COLOR;
    public long value;

    /** 10^digits. */
    public static long limit(int digits) {
        long limit = 1;
        for (int i = 0; i < digits; i++) {
            limit *= 10;
        }
        return limit;
    }

    /** Digit {@code index} (0 = leftmost of {@code digits}) of {@code value}. */
    public static int digit(long value, int digits, int index) {
        return (int) (value / limit(digits - 1 - index) % 10);
    }

    @Override
    public void save(CompoundTag tag) {
        tag.putInt("Color", color);
        tag.putLong("Value", value);
    }

    @Override
    public void load(CompoundTag tag) {
        color = tag.contains("Color", Tag.TAG_ANY_NUMERIC) ? tag.getInt("Color") & 0xFFFFFF : DEFAULT_COLOR;
        value = Math.max(0L, Math.min(LIMIT - 1, tag.getLong("Value")));
    }
}
