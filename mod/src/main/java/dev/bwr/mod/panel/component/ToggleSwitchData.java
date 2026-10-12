package dev.bwr.mod.panel.component;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/** Toggle switch: lever colour, and the latched position. */
public final class ToggleSwitchData extends ComponentData {
    public static final int DEFAULT_COLOR = 0xD8D8D8;

    public int color = DEFAULT_COLOR;
    public boolean on;

    @Override
    public void save(CompoundTag tag) {
        tag.putInt("Color", color);
        tag.putBoolean("On", on);
    }

    @Override
    public void load(CompoundTag tag) {
        color = tag.contains("Color", Tag.TAG_ANY_NUMERIC) ? tag.getInt("Color") & 0xFFFFFF : DEFAULT_COLOR;
        on = tag.getBoolean("On");
    }
}
