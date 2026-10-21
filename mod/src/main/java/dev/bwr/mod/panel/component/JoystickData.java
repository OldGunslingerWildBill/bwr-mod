package dev.bwr.mod.panel.component;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/**
 * Joystick bypass switch: the lever position (0 centre, 1 up, 2 down, 3 left, 4 right), whether it springs back to the
 * centre when let go, and the four legends. The position is latched (the lever stays where it was put) and is saved only when
 * it is not the centre and the switch has no spring return; a sprung lever is never left off centre in a save. A missing key
 * loads as the default.
 */
public final class JoystickData extends ComponentData {
    public static final int CENTRE = 0;
    public static final int UP = 1;
    public static final int DOWN = 2;
    public static final int LEFT = 3;
    public static final int RIGHT = 4;
    public static final int POSITIONS = 5;
    /** Longest legend, characters. */
    public static final int MAX_LEGEND = 6;
    public static final String DEFAULT_UP = "UP";
    public static final String DEFAULT_DOWN = "DOWN";
    public static final String DEFAULT_LEFT = "LEFT";
    public static final String DEFAULT_RIGHT = "RIGHT";

    /** Latched: where the lever is. */
    public int position;
    /** The lever springs back to the centre when it is let go (a four way, spring return joystick). */
    public boolean spring;
    public String legendUp = DEFAULT_UP;
    public String legendDown = DEFAULT_DOWN;
    public String legendLeft = DEFAULT_LEFT;
    public String legendRight = DEFAULT_RIGHT;

    @Override
    public void save(CompoundTag tag) {
        if (position != CENTRE && !spring) {
            tag.putInt("Position", position);
        }
        if (spring) {
            tag.putBoolean("Spring", true);
        }
        tag.putString("LegendUp", legendUp);
        tag.putString("LegendDown", legendDown);
        tag.putString("LegendLeft", legendLeft);
        tag.putString("LegendRight", legendRight);
    }

    @Override
    public void load(CompoundTag tag) {
        spring = tag.getBoolean("Spring");
        int saved = tag.contains("Position", Tag.TAG_ANY_NUMERIC) ? tag.getInt("Position") : CENTRE;
        position = spring || saved < CENTRE || saved >= POSITIONS ? CENTRE : saved;
        legendUp = tag.contains("LegendUp") ? ConfigSchema.cleanText(tag.getString("LegendUp"), MAX_LEGEND) : DEFAULT_UP;
        legendDown = tag.contains("LegendDown") ? ConfigSchema.cleanText(tag.getString("LegendDown"), MAX_LEGEND) : DEFAULT_DOWN;
        legendLeft = tag.contains("LegendLeft") ? ConfigSchema.cleanText(tag.getString("LegendLeft"), MAX_LEGEND) : DEFAULT_LEFT;
        legendRight = tag.contains("LegendRight") ? ConfigSchema.cleanText(tag.getString("LegendRight"), MAX_LEGEND) : DEFAULT_RIGHT;
    }
}
