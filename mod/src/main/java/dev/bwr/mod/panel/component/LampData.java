package dev.bwr.mod.panel.component;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

import java.util.List;

/** Lamp configuration: lens colour and how it shows "on". No latched state. */
public final class LampData extends ComponentData {
    public static final String STEADY = "steady";
    public static final String SLOW = "slow";
    public static final String FAST = "fast";
    public static final List<String> FLASH_MODES = List.of(STEADY, SLOW, FAST);
    public static final int DEFAULT_COLOR = 0xE02020;

    public int color = DEFAULT_COLOR;
    /** How the lamp shows its active state: steady, or flashing slow (1 Hz) or fast (4 Hz). */
    public String flash = STEADY;

    /** A lamp is active when its input has a signal and the signal is non-zero. */
    public static boolean active(double input) {
        return !Double.isNaN(input) && input != 0.0;
    }

    @Override
    public void save(CompoundTag tag) {
        tag.putInt("Color", color);
        tag.putString("Flash", flash);
    }

    @Override
    public void load(CompoundTag tag) {
        color = tag.contains("Color", Tag.TAG_ANY_NUMERIC) ? tag.getInt("Color") & 0xFFFFFF : DEFAULT_COLOR;
        String mode = tag.getString("Flash");
        flash = FLASH_MODES.contains(mode) ? mode : STEADY;
    }
}
