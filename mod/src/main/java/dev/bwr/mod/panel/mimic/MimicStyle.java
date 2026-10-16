package dev.bwr.mod.panel.mimic;

import dev.bwr.mod.panel.component.ConfigField;
import dev.bwr.mod.panel.component.ConfigSchema;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

import java.util.List;

/**
 * How one mimic path is drawn (spec 2 and the owner's changes): colour, width, solid or dashed, its shape (straight
 * or elbow routing between the clicked points; sharp, rounded or curved corners, like draw.io connectors), what each
 * end looks like, the arrow heads' size and colour, optional arrows along the path, and, for a bound path, the level
 * above which it counts as lit. Everything is static: nothing on a path moves.
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

    /** How the path runs between the clicked points: straight, or elbow (right-angle bends, like draw.io's orthogonal). */
    public static final String ROUTE_STRAIGHT = "straight";
    public static final String ROUTE_ELBOW = "elbow";
    public static final List<String> ROUTES = List.of(ROUTE_STRAIGHT, ROUTE_ELBOW);
    /** What its corners look like: sharp (mitred), rounded (a fillet of {@link #radius}) or curved (one smooth curve). */
    public static final String CORNERS_SHARP = "sharp";
    public static final String CORNERS_ROUNDED = "rounded";
    public static final String CORNERS_CURVED = "curved";
    public static final List<String> CORNERS = List.of(CORNERS_SHARP, CORNERS_ROUNDED, CORNERS_CURVED);
    public static final int MIN_RADIUS = 1;
    public static final int MAX_RADIUS = 8;
    public static final int DEFAULT_RADIUS = 3;
    /** Arrow head length in cells; 0 is automatic (it follows the line width). */
    public static final int MAX_ARROW_SIZE = 8;

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
            ConfigField.choice("Route", "screen.bwr.mimic_path.route", ROUTES),
            ConfigField.choice("Corners", "screen.bwr.mimic_path.corners", CORNERS),
            ConfigField.integer("Radius", "screen.bwr.mimic_path.radius", MIN_RADIUS, MAX_RADIUS),
            ConfigField.integer("ArrowSize", "screen.bwr.mimic_path.arrow_size", 0, MAX_ARROW_SIZE),
            ConfigField.bool("ArrowOwnColor", "screen.bwr.mimic_path.arrow_own_color"),
            ConfigField.color("ArrowColor", "screen.bwr.mimic_path.arrow_color"),
            ConfigField.decimal("Threshold", "screen.bwr.mimic_path.threshold", -MAX_THRESHOLD, MAX_THRESHOLD));

    public int color = DEFAULT_COLOR;
    public int width = DEFAULT_WIDTH;
    public boolean dashed;
    public int startEnd = END_NONE;
    public int endEnd = END_ARROW;
    public int flowEvery;
    public String route = ROUTE_STRAIGHT;
    public String corners = CORNERS_SHARP;
    public int radius = DEFAULT_RADIUS;
    /** Arrow head length in cells, 0 for automatic. */
    public int arrowSize;
    /** Whether the arrow heads have their own colour ({@link #arrowColor}) instead of the line's. */
    public boolean arrowOwnColor;
    public int arrowColor = DEFAULT_COLOR;
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
        copy.route = route;
        copy.corners = corners;
        copy.radius = radius;
        copy.arrowSize = arrowSize;
        copy.arrowOwnColor = arrowOwnColor;
        copy.arrowColor = arrowColor;
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
        tag.putString("Route", route);
        tag.putString("Corners", corners);
        tag.putInt("Radius", radius);
        tag.putInt("ArrowSize", arrowSize);
        tag.putBoolean("ArrowOwnColor", arrowOwnColor);
        tag.putInt("ArrowColor", arrowColor);
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
        // A saved "Animate" key (from before paths were made static) is ignored.
        route = ROUTES.contains(tag.getString("Route")) ? tag.getString("Route") : ROUTE_STRAIGHT;
        corners = CORNERS.contains(tag.getString("Corners")) ? tag.getString("Corners") : CORNERS_SHARP;
        radius = tag.contains("Radius", Tag.TAG_ANY_NUMERIC)
                ? Math.max(MIN_RADIUS, Math.min(MAX_RADIUS, tag.getInt("Radius"))) : DEFAULT_RADIUS;
        arrowSize = tag.contains("ArrowSize", Tag.TAG_ANY_NUMERIC)
                ? Math.max(0, Math.min(MAX_ARROW_SIZE, tag.getInt("ArrowSize"))) : 0;
        arrowOwnColor = tag.contains("ArrowOwnColor", Tag.TAG_ANY_NUMERIC) && tag.getBoolean("ArrowOwnColor");
        arrowColor = tag.contains("ArrowColor", Tag.TAG_ANY_NUMERIC) ? tag.getInt("ArrowColor") & 0xFFFFFF : DEFAULT_COLOR;
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

    /** How far this path's decorations reach, its arrow size included. */
    public double reach() {
        return Math.max(reach(width), arrowSize > 0 ? arrowSize * 0.55 + 0.5 : 0.0);
    }

    /** The colour the arrow heads are drawn in. */
    public int arrowRgb() {
        return arrowOwnColor ? arrowColor : color;
    }

    /** Whether the path has anything but straight segments with sharp corners. */
    public boolean shaped() {
        return !ROUTE_STRAIGHT.equals(route) || !CORNERS_SHARP.equals(corners);
    }

    /** Whether the arrows along the path are drawn at all. */
    public boolean hasFlowArrows() {
        return flowEvery > 0;
    }
}
