package dev.bwr.mod.panel.component;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/** Rotary selector: how many positions, their labels (comma separated) and the latched position. */
public final class RotarySelectorData extends ComponentData {
    public static final String TYPE = "rotary_selector";
    public static final int MIN_POSITIONS = 2;
    public static final int MAX_POSITIONS = 6;
    public static final int MAX_NAMES = 60;
    public static final int DEFAULT_COLOR = 0xE8E8E8;

    public int positions = 3;
    /** Comma separated labels, one per position; missing ones read as the position number. */
    public String names = "";
    public int color = DEFAULT_COLOR;
    /** Latched position, 0 .. positions-1. */
    public int position;
    /** Look: {@link PanelStyles} style and handle variant. */
    public String style = PanelStyles.newStyle(TYPE);
    public String variant = PanelStyles.defaultVariant(TYPE, style);

    /** The label of position {@code index} (0-based): the configured name, else its 1-based number. */
    public String nameAt(int index) {
        String[] parts = names.split(",", -1);
        if (index >= 0 && index < parts.length && !parts[index].isBlank()) {
            return parts[index].strip();
        }
        return Integer.toString(index + 1);
    }

    @Override
    public void save(CompoundTag tag) {
        tag.putInt("Positions", positions);
        tag.putString("Names", names);
        tag.putInt("Color", color);
        tag.putInt("Position", position);
        PanelStyles.save(tag, style, variant);
    }

    @Override
    public void load(CompoundTag tag) {
        positions = tag.contains("Positions", Tag.TAG_ANY_NUMERIC)
                ? Math.max(MIN_POSITIONS, Math.min(MAX_POSITIONS, tag.getInt("Positions"))) : 3;
        names = ConfigSchema.cleanText(tag.getString("Names"), MAX_NAMES);
        color = tag.contains("Color", Tag.TAG_ANY_NUMERIC) ? tag.getInt("Color") & 0xFFFFFF : DEFAULT_COLOR;
        position = Math.max(0, Math.min(positions - 1, tag.getInt("Position")));
        style = PanelStyles.loadStyle(TYPE, tag);
        variant = PanelStyles.loadVariant(TYPE, tag, style);
    }
}
