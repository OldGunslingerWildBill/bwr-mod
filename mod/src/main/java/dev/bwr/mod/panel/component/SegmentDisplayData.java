package dev.bwr.mod.panel.component;

import dev.bwr.mod.panel.display.PanelFormat;
import dev.bwr.mod.panel.display.SevenSegment;
import dev.bwr.mod.panel.display.SixteenSegment;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

import java.util.List;

/**
 * State of a 7- or 16-segment display: how many digits it has (from its
 * footprint, 5 cells each), what it shows and in what colour.
 *
 * <h3>What it shows</h3>
 * {@link #format} (a config field) wins when it is not empty: it is run through
 * {@link PanelFormat} with inputs 0..3, so {@code PWR {0:5.1f}%} follows the
 * signal and plain text is just a format with no fields. When it is empty the
 * display shows {@link #text}, the latched text a CC program set with
 * {@link #setText}.
 *
 * <h3>Java API for a CC wrapper</h3>
 * {@link #setText(String)} (it empties the format, so the program's text is not
 * hidden behind an old format), {@link #digits()}, {@link #shown(PanelComponent)}.
 *
 * <h3>Client use</h3>
 * {@link #masks(PanelComponent)} returns the segment mask of every digit,
 * recomputed only when the text, an input or the config changed; the returned
 * array is reused, so a frame allocates nothing.
 */
public final class SegmentDisplayData extends ComponentData implements PanelTextTarget {
    public static final int MAX_FORMAT = 48;
    public static final int MAX_TEXT = 48;
    public static final int INPUTS = 4;
    public static final String RIGHT = "right";
    public static final String LEFT = "left";
    public static final List<String> ALIGNS = List.of(RIGHT, LEFT);
    public static final int DEFAULT_COLOR = 0xFFB000;

    private final boolean sixteen;
    /** Config: text or format string. */
    public String format = "";
    public int color = DEFAULT_COLOR;
    public String align = RIGHT;
    /** Latched text from {@link #setText}, shown when {@link #format} is empty. */
    public String text = "";

    private boolean stale = true;
    private boolean patchPending;
    private String shown = "";
    private int[] masks = new int[0];
    private int version;

    public SegmentDisplayData(boolean sixteen) {
        this.sixteen = sixteen;
    }

    public boolean sixteen() {
        return sixteen;
    }

    /** Number of digits: five cells each, at least one. */
    public int digits() {
        return host() == null ? 8 : Math.max(1, host().cellsWide() / SegmentDisplayType.DIGIT_W);
    }

    /**
     * Shows {@code newText} (CC {@code setText}). Characters the display cannot show render blank. The format is
     * cleared. Printable characters only, at most {@link #MAX_TEXT}.
     *
     * @return whether anything changed
     */
    @Override
    public boolean syncsByPatch() {
        return true;
    }

    @Override
    public boolean setText(String newText) {
        String clean = ConfigSchema.cleanText(newText == null ? "" : newText, MAX_TEXT);
        if (clean.equals(text) && format.isEmpty()) {
            return false;
        }
        text = clean;
        format = "";
        stale = true;
        patchPending = true;
        contentChanged();
        return true;
    }

    /** Called when an input changed. */
    public void invalidate() {
        stale = true;
    }

    /** Bumped whenever what the display shows may have changed; lets a renderer cache derived data. */
    public int version() {
        return version;
    }

    /** The string the display currently reads, before it is cut to the digits. */
    public String shown(PanelComponent component) {
        stale = true;
        refresh(component);
        return shown;
    }

    /** Segment mask per digit (reused array of {@link #digits()} entries). */
    public int[] masks(PanelComponent component) {
        refresh(component);
        return masks;
    }

    private void refresh(PanelComponent component) {
        int digits = digits();
        if (!stale && masks.length == digits) {
            return;
        }
        stale = false;
        version++;
        shown = format.isEmpty() ? text : PanelFormat.format(format, component::input);
        boolean right = RIGHT.equals(align);
        masks = sixteen ? SixteenSegment.layout(shown, digits, right) : SevenSegment.layout(shown, digits, right);
    }

    @Override
    protected void attached() {
        stale = true;
    }

    @Override
    public byte[] drainPatch() {
        if (!patchPending) {
            return null;
        }
        patchPending = false;
        return ContentPatches.encode(toTag());
    }

    @Override
    public void applyPatch(byte[] patch) {
        CompoundTag tag = ContentPatches.decode(patch);
        if (tag != null) {
            load(tag);
        }
    }

    @Override
    public void save(CompoundTag tag) {
        tag.putString("Format", format);
        tag.putInt("Color", color);
        tag.putString("Align", align);
        tag.putString("Text", text);
    }

    @Override
    public void load(CompoundTag tag) {
        format = ConfigSchema.cleanText(tag.getString("Format"), MAX_FORMAT);
        color = tag.contains("Color", Tag.TAG_ANY_NUMERIC) ? tag.getInt("Color") & 0xFFFFFF : DEFAULT_COLOR;
        String mode = tag.getString("Align");
        align = ALIGNS.contains(mode) ? mode : RIGHT;
        text = ConfigSchema.cleanText(tag.getString("Text"), MAX_TEXT);
        stale = true;
    }

    /** Config fields of both segment displays. */
    public static List<ConfigField> schema() {
        return List.of(
                ConfigField.text("Format", "component.bwr.field.format", MAX_FORMAT),
                ConfigField.color("Color", "component.bwr.field.segment_color"),
                ConfigField.choice("Align", "component.bwr.field.align", ALIGNS));
    }
}
