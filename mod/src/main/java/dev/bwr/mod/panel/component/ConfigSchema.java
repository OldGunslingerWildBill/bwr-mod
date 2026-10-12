package dev.bwr.mod.panel.component;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

import java.util.List;

/**
 * Validation for config edits. Nothing the client sends is trusted beyond what a
 * field declares: unknown keys are dropped, wrong types are ignored, numbers are
 * clamped, strings are trimmed of control characters and truncated.
 */
public final class ConfigSchema {
    private ConfigSchema() {
    }

    /**
     * @return a copy of {@code current} in which every field of {@code fields}
     *         that {@code incoming} carries a valid value for has been replaced.
     *         Keys that are not declared fields are always taken from
     *         {@code current}, so latched state can never be edited this way.
     */
    public static CompoundTag merge(List<ConfigField> fields, CompoundTag current, CompoundTag incoming) {
        CompoundTag result = current.copy();
        for (ConfigField field : fields) {
            String key = field.key();
            if (!incoming.contains(key)) {
                continue;
            }
            switch (field.kind()) {
                case TEXT -> {
                    if (incoming.contains(key, Tag.TAG_STRING)) {
                        result.putString(key, cleanText(incoming.getString(key), field.maxLength()));
                    }
                }
                case COLOR -> {
                    if (incoming.contains(key, Tag.TAG_ANY_NUMERIC)) {
                        result.putInt(key, incoming.getInt(key) & 0xFFFFFF);
                    }
                }
                case BOOL -> {
                    if (incoming.contains(key, Tag.TAG_ANY_NUMERIC)) {
                        result.putBoolean(key, incoming.getBoolean(key));
                    }
                }
                case INT -> {
                    if (incoming.contains(key, Tag.TAG_ANY_NUMERIC)) {
                        long value = incoming.getInt(key);
                        result.putInt(key, (int) Math.max((long) field.min(), Math.min((long) field.max(), value)));
                    }
                }
                case DOUBLE -> {
                    if (incoming.contains(key, Tag.TAG_ANY_NUMERIC)) {
                        double value = incoming.getDouble(key);
                        if (Double.isFinite(value)) {
                            result.putDouble(key, Math.max(field.min(), Math.min(field.max(), value)));
                        }
                    }
                }
                case ENUM -> {
                    if (incoming.contains(key, Tag.TAG_STRING) && field.options().contains(incoming.getString(key))) {
                        result.putString(key, incoming.getString(key));
                    }
                }
            }
        }
        return result;
    }

    /** Printable text only, no section signs or control characters, at most {@code max} chars. */
    public static String cleanText(String text, int max) {
        StringBuilder out = new StringBuilder(Math.min(text.length(), max));
        for (int i = 0; i < text.length() && out.length() < max; i++) {
            char c = text.charAt(i);
            if (c >= 32 && c != 127 && c != '§') {
                out.append(c);
            }
        }
        return out.toString();
    }
}
