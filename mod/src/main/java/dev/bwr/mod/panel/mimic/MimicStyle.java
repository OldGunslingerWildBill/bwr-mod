package dev.bwr.mod.panel.mimic;

import dev.bwr.mod.panel.component.ConfigField;
import dev.bwr.mod.panel.component.ConfigSchema;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

import java.util.List;

/**
 * How one mimic path is drawn (spec 2): colour, width, solid or dashed, what each end looks like, optional arrows
 * along the path, and, for a bound path, whether those arrows move and the level above which the path counts as lit.
 * Pure data with an NBT form that carries a default for every key, so a save written before a key existed (or with a
 * bad value) loads as the default.
 */
public final class MimicStyle {
    public static final int DEFAULT_COLOR = 0x2E7DE0;
    public static final int MIN_WIDTH = 1;
    public static final int MAX_WIDTH = 4;
    public static final int DEFAULT_WIDTH = 2;

    /** What an end of a path looks like. */
    public static final int END_NONE = 0;
    public static final int END_ARROW = 1;
    public static final int END_DOT = 2;

    /** Spacing choices for the arrows along a path, in cells; 0 turns them off. */
    public static final int[] FLOW_CHOICES = {0, 8, 16, 32};

    /** Tolerance for "the signal is above the threshold" and the threshold's range. */
    public static final double MAX_THRESHOLD = 1.0e6;

    /** The editable fields, validated by {@link ConfigSchema#merge} for a configure request. */
    public static final List<ConfigField> SCHEMA = List.of(
            ConfigField.color("Color", "screen.bwr.mimic_path.color"),
            ConfigField.integer("Width", "screen.bwr.mimic_path.width", MIN_WIDTH, MAX_WIDTH),
            ConfigField.bool("Dashed", "screen.bwr.mimic_path.dashed"),
            ConfigField.integer("StartEnd", "screen.bwr.mimic_path.start", END_NONE, END_DOT),
            ConfigField.integer("EndEnd", "screen.bwr.mimic_path.end", END_NONE, END_DOT),
            ConfigField.integer("FlowEvery", "screen.bwr.mimic_path.flow", 0, 32),
            ConfigField.bool("Animate", "screen.bwr.mimic_path.animate"),
            ConfigField.decimal("Threshold", "screen.bwr.mimic_path.threshold", -MAX_THRESHOLD, MAX_THRESHOLD));

    public int color = DEFAULT_COLOR;
    public int width = DEFAULT_WIDTH;
    public boolean dashed;
    public int startEnd = END_NONE;
    public int endEnd = END_ARROW;
    public int flowEvery;
    public boolean animate = true;
    /** A bound path is lit while its signal is above this. */
    public double threshold;

    public MimicStyle copy() {
        MimicStyle copy = new MimicStyle();
        copy.color = color;
        copy.width = width;
        copy.dashed = dashed;
        copy.startEnd = startEnd;
        copy.endEnd = endEnd;
        copy.flowEvery = flowEvery;
        copy.animate = animate;
        copy.threshold = threshold;
        return copy;
    }

    /** The nearest allowed arrow spacing (so a hand-edited value never produces a stray one). */
    public static int snapFlow(int requested) {
        int best = 0;
        for (int choice : FLOW_CHOICES) {
            if (Math.abs(choice - requested) < Math.abs(best - requested)) {
                best = choice;
            }
        }
        return best;
    }

    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        save(tag);
        return tag;
    }

    public void save(CompoundTag tag) {
        tag.putInt("Color", color);
        tag.putInt("Width", width);
        tag.putBoolean("Dashed", dashed);
        tag.putInt("StartEnd", startEnd);
        tag.putInt("EndEnd", endEnd);
        tag.putInt("FlowEvery", flowEvery);
        tag.putBoolean("Animate", animate);
        tag.putDouble("Threshold", threshold);
    }

    /** Replaces every field from {@code tag}; a missing or malformed key takes its default. */
    public void load(CompoundTag tag) {
        color = tag.contains("Color", Tag.TAG_ANY_NUMERIC) ? tag.getInt("Color") & 0xFFFFFF : DEFAULT_COLOR;
        width = tag.contains("Width", Tag.TAG_ANY_NUMERIC)
                ? Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, tag.getInt("Width"))) : DEFAULT_WIDTH;
        dashed = tag.contains("Dashed", Tag.TAG_ANY_NUMERIC) && tag.getBoolean("Dashed");
        startEnd = tag.contains("StartEnd", Tag.TAG_ANY_NUMERIC)
                ? Math.max(END_NONE, Math.min(END_DOT, tag.getInt("StartEnd"))) : END_NONE;
        endEnd = tag.contains("EndEnd", Tag.TAG_ANY_NUMERIC)
                ? Math.max(END_NONE, Math.min(END_DOT, tag.getInt("EndEnd"))) : END_ARROW;
        flowEvery = tag.contains("FlowEvery", Tag.TAG_ANY_NUMERIC) ? snapFlow(tag.getInt("FlowEvery")) : 0;
        animate = !tag.contains("Animate", Tag.TAG_ANY_NUMERIC) || tag.getBoolean("Animate");
        double level = tag.contains("Threshold", Tag.TAG_ANY_NUMERIC) ? tag.getDouble("Threshold") : 0.0;
        threshold = Double.isFinite(level) ? Math.max(-MAX_THRESHOLD, Math.min(MAX_THRESHOLD, level)) : 0.0;
    }

    /** Applies a client's edit: only the declared fields, each validated; everything else is kept. */
    public void apply(CompoundTag edit) {
        load(ConfigSchema.merge(SCHEMA, toTag(), edit));
    }

    /** How far the decorations (arrow heads, end dots) reach beyond the centre line of a path of {@code width}. */
    public static double reach(int width) {
        return width * 1.1 + 1.5;
    }

    /** Whether the arrows along the path are drawn at all. */
    public boolean hasFlowArrows() {
        return flowEvery > 0;
    }
}
