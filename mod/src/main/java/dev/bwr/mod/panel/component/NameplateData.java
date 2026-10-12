package dev.bwr.mod.panel.component;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/** Nameplate configuration: engraved text, plate colour, ink colour. No latched state. */
public final class NameplateData extends ComponentData implements PanelTextTarget {
    public static final int MAX_TEXT = 32;
    public static final int DEFAULT_PLATE = 0x151515;
    public static final int DEFAULT_INK = 0xE8E8E8;

    public String text = "";
    public int plate = DEFAULT_PLATE;
    public int ink = DEFAULT_INK;

    /** Pending patch for watching clients (server). */
    private boolean dirty;

    /**
     * Replaces the engraved text (the CC {@code setText} path). Printable characters only, at most
     * {@link #MAX_TEXT}; the change is saved and sent to watching clients as a small patch.
     *
     * @return whether the text changed
     */
    public boolean setText(String newText) {
        String clean = ConfigSchema.cleanText(newText == null ? "" : newText, MAX_TEXT);
        if (clean.equals(text)) {
            return false;
        }
        text = clean;
        dirty = true;
        contentChanged();
        return true;
    }

    @Override
    public byte[] drainPatch() {
        if (!dirty) {
            return null;
        }
        dirty = false;
        return ContentPatches.encode(toTag());
    }

    @Override
    public void applyPatch(byte[] patch) {
        CompoundTag tag = ContentPatches.decode(patch);
        if (tag != null) {
            load(tag);
            if (host() != null) {
                host().touch();
            }
        }
    }

    @Override
    public boolean setText(String value) {
        String clean = ConfigSchema.cleanText(value, MAX_TEXT);
        if (clean.equals(text)) {
            return false;
        }
        text = clean;
        return true;
    }

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
