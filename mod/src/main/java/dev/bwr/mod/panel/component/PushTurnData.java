package dev.bwr.mod.panel.component;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/**
 * Push-to-turn switch configuration and position. The position (0 for the first legend, AUTO; 1 for the second,
 * ARM) is latched state, saved only when it is not the default; so is the spring return and the two legends. The
 * pushed state is momentary and never saved.
 */
public final class PushTurnData extends ComponentData {
    /** Longest legend, characters. */
    public static final int MAX_LEGEND = 8;
    public static final String DEFAULT_LEGEND_1 = "AUTO";
    public static final String DEFAULT_LEGEND_2 = "ARM";

    /** 0 at the first legend (AUTO, left), 1 at the second (ARM, right). Latched; not a config field. */
    public int position;
    /** Whether letting go of a knob that was turned to ARM returns it to AUTO, like the spring return of a real switch. */
    public boolean spring;
    public String legend1 = DEFAULT_LEGEND_1;
    public String legend2 = DEFAULT_LEGEND_2;

    @Override
    public void save(CompoundTag tag) {
        if (position != 0) {
            tag.putInt("Position", position);
        }
        if (spring) {
            tag.putBoolean("Spring", true);
        }
        if (!legend1.equals(DEFAULT_LEGEND_1)) {
            tag.putString("Legend1", legend1);
        }
        if (!legend2.equals(DEFAULT_LEGEND_2)) {
            tag.putString("Legend2", legend2);
        }
    }

    @Override
    public void load(CompoundTag tag) {
        position = tag.contains("Position", Tag.TAG_ANY_NUMERIC) && tag.getInt("Position") > 0 ? 1 : 0;
        spring = tag.getBoolean("Spring");
        legend1 = tag.contains("Legend1") ? ConfigSchema.cleanText(tag.getString("Legend1"), MAX_LEGEND) : DEFAULT_LEGEND_1;
        legend2 = tag.contains("Legend2") ? ConfigSchema.cleanText(tag.getString("Legend2"), MAX_LEGEND) : DEFAULT_LEGEND_2;
    }
}
