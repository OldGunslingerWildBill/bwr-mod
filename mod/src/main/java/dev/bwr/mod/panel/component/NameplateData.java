package dev.bwr.mod.panel.component;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/** Nameplate configuration: engraved text, plate colour, ink colour. No latched state. */
public final class NameplateData extends ComponentData {
    public static final int MAX_TEXT = 32;
    public static final int DEFAULT_PLATE = 0x151515;
    public static final int DEFAULT_INK = 0xE8E8E8;

    public String text = "";
    public int plate = DEFAULT_PLATE;
    public int ink = DEFAULT_INK;

    @Override
    public void save(CompoundTag tag) {
        tag.putString("Text", text);
        tag.putInt("Plate", plate);
        tag.putInt("Ink", ink);
    }

    @Override
    public void load(CompoundTag tag) {
        text = ConfigSchema.cleanText(tag.getString("Text"), MAX_TEXT);
        plate = tag.contains("Plate", Tag.TAG_ANY_NUMERIC) ? tag.getInt("Plate") & 0xFFFFFF : DEFAULT_PLATE;
        ink = tag.contains("Ink", Tag.TAG_ANY_NUMERIC) ? tag.getInt("Ink") & 0xFFFFFF : DEFAULT_INK;
    }
}
