package dev.bwr.mod.panel.component;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/**
 * An annunciator window's trigger, the two settings that decide when it alarms: {@code Condition} (above, below or
 * equal to) and {@code Setpoint}. The wrench screen has them among the window's fields; the binding screen shows them
 * too when it wires the window's input, and sends them as the config edit made here, which the server accepts from a
 * wiring tool because it can hold nothing else ({@link #restrict}).
 */
public final class AnnunciatorTrigger {
    public static final String KEY_CONDITION = "Condition";
    public static final String KEY_SETPOINT = "Setpoint";

    private AnnunciatorTrigger() {
    }

    /** A setpoint as a field shows it: without a fraction when it has none. */
    public static String format(double setpoint) {
        return setpoint == Math.rint(setpoint) && Math.abs(setpoint) < 1.0e9 ? Long.toString((long) setpoint)
                : Double.toString(setpoint);
    }

    /** The setpoint typed, limited to what a window accepts; null when it is not a finite number. */
    public static Double parse(String text) {
        try {
            double value = Double.parseDouble(text == null ? "" : text.trim());
            return Double.isFinite(value) ? Math.max(-AnnunciatorData.LIMIT, Math.min(AnnunciatorData.LIMIT, value)) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The config edit that sets a window's trigger: {@code {Config: {Condition, Setpoint}}}. */
    public static CompoundTag edit(String condition, double setpoint) {
        CompoundTag config = new CompoundTag();
        config.putString(KEY_CONDITION, condition);
        config.putDouble(KEY_SETPOINT, setpoint);
        CompoundTag edit = new CompoundTag();
        edit.put("Config", config);
        return edit;
    }

    /**
     * What a wiring tool may change of a window with a config edit: its condition and setpoint and nothing else. Returns
     * the {@code Config} part cut down to those two keys, or null when the edit carries neither.
     */
    public static CompoundTag restrict(CompoundTag edit) {
        if (edit == null || !edit.contains("Config", Tag.TAG_COMPOUND)) {
            return null;
        }
        CompoundTag config = edit.getCompound("Config");
        CompoundTag kept = new CompoundTag();
        if (config.contains(KEY_CONDITION, Tag.TAG_STRING)) {
            kept.putString(KEY_CONDITION, config.getString(KEY_CONDITION));
        }
        if (config.contains(KEY_SETPOINT, Tag.TAG_ANY_NUMERIC)) {
            kept.putDouble(KEY_SETPOINT, config.getDouble(KEY_SETPOINT));
        }
        return kept.isEmpty() ? null : kept;
    }
}
