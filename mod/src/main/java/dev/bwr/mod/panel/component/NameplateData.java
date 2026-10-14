package dev.bwr.mod.panel.component;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

import java.util.List;

/**
 * Nameplate configuration: up to three engraved lines, plate and ink colour, and the small fields of a valve
 * nameplate (the Columbia SRV plates): two corner fields at the bottom, an optional coloured tag with its own text
 * (a red {@code PAM} tag), an optional coloured dot, both at the bottom left, and an optional coloured stripe down
 * the left edge (red on the ADS valves). No latched state.
 *
 * <p>Line 1 is the original single line: it is saved under the old {@code Text} key, so a save from before the
 * extra fields loads into line 1 and the rest take their defaults (empty, no tag, no dot, no stripe).
 */
public final class NameplateData extends ComponentData implements PanelTextTarget {
    /** Longest engraved line, characters. */
    public static final int MAX_TEXT = 32;
    /** Longest corner field ("1101 PSIG") and tag ("PAM"). */
    public static final int MAX_CORNER = 16;
    public static final int MAX_TAG = 8;
    public static final int LINES = 3;
    public static final int DEFAULT_PLATE = 0x151515;
    public static final int DEFAULT_INK = 0xE8E8E8;
    public static final int DEFAULT_TAG = 0xC0261E;
    public static final int DEFAULT_DOT = 0x30C060;
    public static final int DEFAULT_STRIPE = 0xC0261E;
    /** The names {@link #setField} takes (CC {@code setTextField}). */
    public static final List<String> FIELDS = List.of("line1", "line2", "line3", "cornerLeft", "cornerRight", "tag");

    /** Line 1, saved as {@code Text}. */
    public String text = "";
    public String text2 = "";
    public String text3 = "";
    public String cornerLeft = "";
    public String cornerRight = "";
    public String tag = "";
    public int tagColor = DEFAULT_TAG;
    public boolean dot;
    public int dotColor = DEFAULT_DOT;
    public boolean stripe;
    public int stripeColor = DEFAULT_STRIPE;
    public int plate = DEFAULT_PLATE;
    public int ink = DEFAULT_INK;

    /** Pending patch for watching clients (server). */
    private boolean dirty;

    /** The engraved line {@code index} (0 to 2). */
    public String line(int index) {
        return index == 0 ? text : index == 1 ? text2 : text3;
    }

    /**
     * Replaces the first engraved line (the CC {@code setText} path). Printable characters only, at most
     * {@link #MAX_TEXT}; the change is saved and sent to watching clients as a small patch.
     *
     * @return whether the text changed
     */
    @Override
    public boolean setText(String newText) {
        return setField("line1", newText);
    }

    @Override
    public List<String> textFields() {
        return FIELDS;
    }

    @Override
    public boolean setField(String field, String newText) {
        String raw = newText == null ? "" : newText;
        switch (field) {
            case "line1" -> {
                String clean = ConfigSchema.cleanText(raw, MAX_TEXT);
                if (clean.equals(text)) {
                    return false;
                }
                text = clean;
            }
            case "line2" -> {
                String clean = ConfigSchema.cleanText(raw, MAX_TEXT);
                if (clean.equals(text2)) {
                    return false;
                }
                text2 = clean;
            }
            case "line3" -> {
                String clean = ConfigSchema.cleanText(raw, MAX_TEXT);
                if (clean.equals(text3)) {
                    return false;
                }
                text3 = clean;
            }
            case "cornerLeft" -> {
                String clean = ConfigSchema.cleanText(raw, MAX_CORNER);
                if (clean.equals(cornerLeft)) {
                    return false;
                }
                cornerLeft = clean;
            }
            case "cornerRight" -> {
                String clean = ConfigSchema.cleanText(raw, MAX_CORNER);
                if (clean.equals(cornerRight)) {
                    return false;
                }
                cornerRight = clean;
            }
            case "tag" -> {
                String clean = ConfigSchema.cleanText(raw, MAX_TAG);
                if (clean.equals(tag)) {
                    return false;
                }
                tag = clean;
            }
            default -> {
                return false;
            }
        }
        dirty = true;
        contentChanged();
        return true;
    }

    @Override
    public boolean syncsByPatch() {
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
    public void save(CompoundTag tag) {
        tag.putString("Text", text);
        tag.putInt("Plate", plate);
        tag.putInt("Ink", ink);
        // The extra fields are written only when set, so an old plate re-saves as it was.
        putIfSet(tag, "Text2", text2);
        putIfSet(tag, "Text3", text3);
        putIfSet(tag, "CornerLeft", cornerLeft);
        putIfSet(tag, "CornerRight", cornerRight);
        putIfSet(tag, "Tag", this.tag);
        if (tagColor != DEFAULT_TAG) {
            tag.putInt("TagColor", tagColor);
        }
        if (dot) {
            tag.putBoolean("Dot", true);
        }
        if (dotColor != DEFAULT_DOT) {
            tag.putInt("DotColor", dotColor);
        }
        if (stripe) {
            tag.putBoolean("Stripe", true);
        }
        if (stripeColor != DEFAULT_STRIPE) {
            tag.putInt("StripeColor", stripeColor);
        }
    }

    private static void putIfSet(CompoundTag tag, String key, String value) {
        if (!value.isEmpty()) {
            tag.putString(key, value);
        }
    }

    @Override
    public void load(CompoundTag tag) {
        text = ConfigSchema.cleanText(tag.getString("Text"), MAX_TEXT);
        text2 = ConfigSchema.cleanText(tag.getString("Text2"), MAX_TEXT);
        text3 = ConfigSchema.cleanText(tag.getString("Text3"), MAX_TEXT);
        cornerLeft = ConfigSchema.cleanText(tag.getString("CornerLeft"), MAX_CORNER);
        cornerRight = ConfigSchema.cleanText(tag.getString("CornerRight"), MAX_CORNER);
        this.tag = ConfigSchema.cleanText(tag.getString("Tag"), MAX_TAG);
        plate = colour(tag, "Plate", DEFAULT_PLATE);
        ink = colour(tag, "Ink", DEFAULT_INK);
        tagColor = colour(tag, "TagColor", DEFAULT_TAG);
        dotColor = colour(tag, "DotColor", DEFAULT_DOT);
        stripeColor = colour(tag, "StripeColor", DEFAULT_STRIPE);
        dot = tag.contains("Dot", Tag.TAG_ANY_NUMERIC) && tag.getBoolean("Dot");
        stripe = tag.contains("Stripe", Tag.TAG_ANY_NUMERIC) && tag.getBoolean("Stripe");
    }

    private static int colour(CompoundTag tag, String key, int fallback) {
        return tag.contains(key, Tag.TAG_ANY_NUMERIC) ? tag.getInt(key) & 0xFFFFFF : fallback;
    }
}
