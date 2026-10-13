package dev.bwr.mod.panel.model;

import java.util.Locale;

/**
 * An immutable, validated panel component model, as exported by
 * {@code art/models/panel/panel_export.py} and read by {@link PanelModelParser}. Pure data: no Minecraft
 * types, so it loads and is tested on any side.
 *
 * <h3>Coordinates</h3>
 * Component model space, in inches: {@code x} right, {@code y} DOWN from the footprint's top-left corner, and
 * {@code z} out of the face (the exporter has already flipped Blender's y). {@link Placement} fits the
 * footprint into a component's cells.
 *
 * <h3>Parts</h3>
 * A part is a named group of flat-coloured faces with a {@link Role}. A part with a {@link Part#hasPivot pivot}
 * turns about its axis through the pivot; one with a {@link Part#hasTravel travel} slides along its axis.
 * Everything else is static. A rotation angle is positive clockwise as the operator sees the face when the axis
 * is {@code (0, 0, 1)}, and is applied as an ordinary right-handed rotation in this (y down) space.
 */
public final class BakedPanelModel {
    /** What the renderer does with a part's colour. */
    public enum Role {
        /** Painted body, colours as authored (a {@code tint} makes it recolourable). */
        PAINT,
        /** A lens: glows full-bright when its lamp is lit, dimmed when it is not. */
        LAMP,
        /** Recoloured by state (the target flag of a breaker control switch). */
        FLAG,
        /** A pointer. */
        NEEDLE,
        /** A window: drawn last, translucent. */
        GLASS;

        static Role parse(String name) {
            return switch (name.toLowerCase(Locale.ROOT)) {
                case "paint" -> PAINT;
                case "lamp" -> LAMP;
                case "flag" -> FLAG;
                case "needle" -> NEEDLE;
                case "glass" -> GLASS;
                default -> null;
            };
        }
    }

    /** Horizontal alignment of a legend about its anchor. */
    public enum Align {
        LEFT, CENTER, RIGHT;

        static Align parse(String name) {
            return switch (name.toLowerCase(Locale.ROOT)) {
                case "left" -> LEFT;
                case "center", "centre" -> CENTER;
                case "right" -> RIGHT;
                default -> null;
            };
        }
    }

    /** One named group of faces. Face data is flat arrays so drawing allocates nothing. */
    public static final class Part {
        public final String name;
        public final Role role;
        /** The state/colour name the exporter tagged (red, green, white, amber, blue), or null. */
        public final String tint;
        public final boolean hasPivot;
        public final float pivotX, pivotY, pivotZ;
        /** Unit axis; (0, 0, 1) when the part has neither pivot nor travel. */
        public final float axisX, axisY, axisZ;
        public final boolean hasTravel;
        /** Inches along the axis when fully operated; 0 without {@link #hasTravel}. */
        public final float travel;
        public final int faceCount;
        /** 12 floats per face: four corners of x, y, z (a triangle repeats its last corner). */
        final float[] positions;
        /** 3 floats per face, unit length. */
        final float[] normals;
        final int[] colors;
        /** Per face brightness relative to the part's mean, for recolouring without losing the authored shading. */
        final float[] shades;

        Part(String name, Role role, String tint, float[] pivot, float[] axis, float travel, boolean hasTravel,
             float[] positions, float[] normals, int[] colors, float[] shades) {
            this.name = name;
            this.role = role;
            this.tint = tint;
            this.hasPivot = pivot != null;
            this.pivotX = pivot == null ? 0 : pivot[0];
            this.pivotY = pivot == null ? 0 : pivot[1];
            this.pivotZ = pivot == null ? 0 : pivot[2];
            this.axisX = axis[0];
            this.axisY = axis[1];
            this.axisZ = axis[2];
            this.hasTravel = hasTravel;
            this.travel = hasTravel ? travel : 0.0f;
            this.faceCount = colors.length;
            this.positions = positions;
            this.normals = normals;
            this.colors = colors;
            this.shades = shades;
        }

        /** Whether the part moves with component state (rotates about a pivot or slides). */
        public boolean moving() {
            return hasPivot || hasTravel;
        }

        public int faceColor(int face) {
            return colors[face];
        }

        public float faceShade(int face) {
            return shades[face];
        }

        /** Coordinate {@code axis} (0 x, 1 y, 2 z) of corner {@code corner} (0..3) of {@code face}. */
        public float corner(int face, int corner, int axis) {
            return positions[face * 12 + corner * 3 + axis];
        }

        /** Component {@code axis} of the unit normal of {@code face}. */
        public float normal(int face, int axis) {
            return normals[face * 3 + axis];
        }
    }

    /** A text anchor: where the game draws a legend. */
    public static final class Label {
        public final String id;
        public final float x, y, z;
        /** Cap height in inches. */
        public final float height;
        public final Align align;
        /** Clockwise degrees as the operator sees it. */
        public final float rotation;
        /** Legend colour (0xRRGGBB) chosen to read on whatever surface sits behind the anchor. */
        public final int ink;

        Label(String id, float x, float y, float z, float height, Align align, float rotation, int ink) {
            this.id = id;
            this.x = x;
            this.y = y;
            this.z = z;
            this.height = height;
            this.align = align;
            this.rotation = rotation;
            this.ink = ink;
        }
    }

    public final String id;
    public final String source;
    public final float footprintW;
    public final float footprintH;
    private final Part[] parts;
    private final Label[] labels;
    private final int faceTotal;

    BakedPanelModel(String id, String source, float footprintW, float footprintH, Part[] parts, Label[] labels) {
        this.id = id;
        this.source = source;
        this.footprintW = footprintW;
        this.footprintH = footprintH;
        this.parts = parts;
        this.labels = labels;
        int total = 0;
        for (Part part : parts) {
            total += part.faceCount;
        }
        this.faceTotal = total;
    }

    public int partCount() {
        return parts.length;
    }

    public Part part(int index) {
        return parts[index];
    }

    /** The first part called {@code name}, or null. */
    public Part part(String name) {
        for (Part part : parts) {
            if (part.name.equals(name)) {
                return part;
            }
        }
        return null;
    }

    public int labelCount() {
        return labels.length;
    }

    public Label label(int index) {
        return labels[index];
    }

    /** The label with this id, or null. */
    public Label label(String labelId) {
        for (Label label : labels) {
            if (label.id.equals(labelId)) {
                return label;
            }
        }
        return null;
    }

    public int faceTotal() {
        return faceTotal;
    }

    @Override
    public String toString() {
        return "BakedPanelModel[" + id + " " + footprintW + "x" + footprintH + "in, " + parts.length + " parts, "
                + faceTotal + " faces, " + labels.length + " labels]";
    }
}
