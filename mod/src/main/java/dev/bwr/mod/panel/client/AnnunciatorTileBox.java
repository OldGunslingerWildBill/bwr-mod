package dev.bwr.mod.panel.client;

import dev.bwr.mod.panel.model.AnnunciatorBox;
import dev.bwr.mod.panel.model.BakedPanelModel;
import dev.bwr.mod.panel.model.PartDraw;

/**
 * The Columbia style of an annunciator window: an ivory tray tile on a black plate, after the annunciator boxes of
 * Columbia Generating Station's P800 boards (the tile model {@code ann_window} and the proportions of
 * {@code art/models/panel/annunciator}, read off the owner's photographs).
 *
 * <h3>The tile</h3>
 * Unlike every other baked model the tile is not fitted with one scale: windows tile a box at every size, and one window
 * is often half a tile (one of the two alarms of a split tile, {@link AnnunciatorBox#UPPER} or {@link AnnunciatorBox#LOWER}).
 * So the model is cut at the outline of its face into four rounded corners, four straight runs of rim and the face, and
 * the pieces are laid onto the window's cells ({@link #emit}): the gap and the rim keep the share of the tile's width the
 * model gives them (so a corner stays round), the runs and the face stretch. An upper alarm has no bottom corners and no
 * bottom run: its side runs and its face reach its lower edge, where the lower alarm carries on, and each of the two
 * draws its half of the dark dividing line. The model's face is put at {@link AnnunciatorBox#TILE_Z}, so the lit face, the
 * no-signal flag and the legend, which {@link AnnunciatorModel} draws each frame, sit on it as on the procedural tile.
 *
 * <h3>Round the tiles</h3>
 * The black plate overhangs the box. A window draws the plate under itself and, on each side that is not joined to
 * another window, a strip of plate margin outside its own cells, with the corner block where two open sides meet; one
 * window of the box also draws the white nameplate under it and one the latch beside it ({@link AnnunciatorBox#layout}).
 * The sizes are the constants of {@link AnnunciatorBox}, in tile widths. The numerals and the nameplate's words are
 * text, drawn by {@link AnnunciatorModel}.
 *
 * <p>Nothing here can fail a frame: a model file that is missing or is not a tile gives no {@link Shape}, and the caller
 * draws the procedural window instead.
 */
final class AnnunciatorTileBox {
    private AnnunciatorTileBox() {
    }

    /** Part names of the tile model. */
    static final String FACE = "face";
    static final String PLATE = "plate";
    static final String RIM_TOP = "rim_top";
    static final String RIM_BOTTOM = "rim_bottom";
    static final String RIM_LEFT = "rim_left";
    static final String RIM_RIGHT = "rim_right";
    static final String CORNER_TOP_LEFT = "corner_tl";
    static final String CORNER_TOP_RIGHT = "corner_tr";
    static final String CORNER_BOTTOM_LEFT = "corner_bl";
    static final String CORNER_BOTTOM_RIGHT = "corner_br";

    /** The black plate (the procedural box's dark), the dividing line of a split tile, the nameplate and the latch. */
    static final int PLATE_RGB = AnnunciatorModel.BOX_DARK;
    static final int DIVIDER_RGB = 0x4A463C;
    static final int NAMEPLATE_RGB = 0xF4F2EA;
    static final int LATCH_RGB = ModelDraw.STEEL;
    /** The plate's front when the model does not say: 0.7 of the face's elevation. */
    private static final float PLATE_SHARE = 0.7f;
    /** The dividing line lies this far above the face (1/16 block), under the lit face. */
    private static final float DIVIDER_LIFT = 0.04f;
    /** The latch as an octagon with a flat on each side: the corners of a unit circle. */
    private static final float[] OCTAGON_X = new float[8];
    private static final float[] OCTAGON_Y = new float[8];

    static {
        for (int i = 0; i < 8; i++) {
            double a = Math.toRadians(22.5 + 45.0 * i);
            OCTAGON_X[i] = (float) Math.cos(a);
            OCTAGON_Y[i] = (float) Math.sin(a);
        }
    }

    /** The measures of one window, worked out once per mesh: what {@link #emit} draws with and what the legend is laid out in. */
    static final class Shape {
        final BakedPanelModel model;
        final AnnunciatorBox.Tile tile;
        /** The model's tile outline and face, inches: {x0, y0, x1, y1}. */
        final float[] outline;
        final float[] face;
        /** Elevation (1/16 block) per model inch. */
        final float zScale;
        /** Half the gap between two tiles, the rim's width and half the dividing line's, in cells. */
        final float gap;
        final float rim;
        final float halfDivider;
        /** The plate's front, 1/16 block. */
        final float plateZ;
        /** The plate margins of the box, in cells: left, top, right, bottom. */
        final float marginLeft;
        final float marginTop;
        final float marginRight;
        final float marginBottom;
        /** Where the window's face starts from each side of its footprint, in cells: {left, top, right, bottom}. */
        final float[] insets;

        private Shape(BakedPanelModel model, AnnunciatorBox.Tile tile, float[] outline, float[] face, float faceZ, float plateZ,
                      float w, float h) {
            this.model = model;
            this.tile = tile;
            this.outline = outline;
            this.face = face;
            this.zScale = AnnunciatorBox.TILE_Z / faceZ;
            this.plateZ = plateZ * zScale;
            boolean whole = tile.role() == AnnunciatorBox.WHOLE;
            // Cells per model inch by the tile's width; less on a window too flat for the rim, so the face never closes up.
            float k = w / model.footprintW;
            float room = 0.4f * Math.min(w, whole ? h : 2.0f * h);
            float inset = face[0] * k;
            if (inset > room) {
                k *= room / inset;
            }
            this.gap = outline[0] * k;
            this.rim = (face[0] - outline[0]) * k;
            this.halfDivider = whole ? 0.0f : AnnunciatorBox.DIVIDER * model.footprintW * k / 2.0f;
            float unit = tile.unit();
            this.marginLeft = AnnunciatorBox.MARGIN_LEFT * unit;
            this.marginTop = AnnunciatorBox.MARGIN_TOP * unit;
            this.marginRight = AnnunciatorBox.MARGIN_RIGHT * unit;
            this.marginBottom = AnnunciatorBox.MARGIN_BOTTOM * unit;
            float edge = gap + rim;
            this.insets = new float[] {edge, tile.role() == AnnunciatorBox.LOWER ? halfDivider : edge, edge,
                    tile.role() == AnnunciatorBox.UPPER ? halfDivider : edge};
        }
    }

    /**
     * The measures of a {@code w x h} cell window that is {@code tile} of its box, or null when {@code model} is missing or
     * is not a tile model (no face strictly inside a rim that lies inside the footprint): the caller then draws the
     * procedural window.
     */
    static Shape shape(BakedPanelModel model, float w, float h, AnnunciatorBox.Tile tile) {
        if (model == null || tile == null || !(w > 0.0f) || !(h > 0.0f)) {
            return null;
        }
        BakedPanelModel.Part facePart = model.part(FACE);
        if (facePart == null || facePart.faceCount == 0) {
            return null;
        }
        float[] face = {Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, 0.0f};
        grow(face, facePart);
        float[] outline = {Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, 0.0f};
        float plateZ = 0.0f;
        for (int i = 0; i < model.partCount(); i++) {
            BakedPanelModel.Part part = model.part(i);
            if (part.name.startsWith("rim_") || part.name.startsWith("corner_")) {
                grow(outline, part);
            } else if (part.name.equals(PLATE)) {
                float[] plate = {Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, 0.0f};
                grow(plate, part);
                plateZ = plate[4];
            }
        }
        float faceZ = face[4];
        boolean sound = outline[0] >= 0.0f && outline[1] >= 0.0f && face[0] > outline[0] && face[1] > outline[1]
                && face[2] > face[0] && face[3] > face[1] && outline[2] > face[2] && outline[3] > face[3]
                && model.footprintW > 0.0f && model.footprintH > 0.0f && faceZ > 0.0f;
        if (!sound) {
            return null;
        }
        if (!(plateZ > 0.0f && plateZ < faceZ)) {
            plateZ = PLATE_SHARE * faceZ;
        }
        return new Shape(model, tile, outline, face, faceZ, plateZ, w, h);
    }

    /** Widens {@code box} ({x0, y0, x1, y1, highest z}) to hold {@code part}. */
    private static void grow(float[] box, BakedPanelModel.Part part) {
        for (int f = 0; f < part.faceCount; f++) {
            for (int c = 0; c < 4; c++) {
                box[0] = Math.min(box[0], part.corner(f, c, 0));
                box[1] = Math.min(box[1], part.corner(f, c, 1));
                box[2] = Math.max(box[2], part.corner(f, c, 0));
                box[3] = Math.max(box[3], part.corner(f, c, 1));
                box[4] = Math.max(box[4], part.corner(f, c, 2));
            }
        }
    }

    /**
     * Records one window: the plate under it and its margins, the tile, the dividing line of a split tile, and the
     * nameplate and the latch if this window draws them.
     *
     * @param edges   the window's edge word ({@link AnnunciatorBox}): a side that is not joined gets a plate margin
     * @param faceRgb the colour of the unlit face
     */
    static void emit(QuadSink sink, Shape s, int edges, float w, float h, int faceRgb) {
        AnnunciatorBox.Tile tile = s.tile;
        float z = s.plateZ;
        boolean left = (edges & AnnunciatorBox.LEFT) == 0;
        boolean top = (edges & AnnunciatorBox.TOP) == 0;
        boolean right = (edges & AnnunciatorBox.RIGHT) == 0;
        boolean bottom = (edges & AnnunciatorBox.BOTTOM) == 0;
        // The plate: under the window, then the margin outside each open side, then the corner blocks.
        sink.rect(0.0f, 0.0f, w, h, z, PLATE_RGB, false);
        if (left) {
            plate(sink, -s.marginLeft, 0.0f, s.marginLeft, h, z, AnnunciatorBox.LEFT);
        }
        if (right) {
            plate(sink, w, 0.0f, s.marginRight, h, z, AnnunciatorBox.RIGHT);
        }
        if (top) {
            plate(sink, 0.0f, -s.marginTop, w, s.marginTop, z, AnnunciatorBox.TOP);
        }
        if (bottom) {
            plate(sink, 0.0f, h, w, s.marginBottom, z, AnnunciatorBox.BOTTOM);
        }
        if (left && top) {
            plate(sink, -s.marginLeft, -s.marginTop, s.marginLeft, s.marginTop, z, AnnunciatorBox.LEFT | AnnunciatorBox.TOP);
        }
        if (right && top) {
            plate(sink, w, -s.marginTop, s.marginRight, s.marginTop, z, AnnunciatorBox.RIGHT | AnnunciatorBox.TOP);
        }
        if (left && bottom) {
            plate(sink, -s.marginLeft, h, s.marginLeft, s.marginBottom, z, AnnunciatorBox.LEFT | AnnunciatorBox.BOTTOM);
        }
        if (right && bottom) {
            plate(sink, w, h, s.marginRight, s.marginBottom, z, AnnunciatorBox.RIGHT | AnnunciatorBox.BOTTOM);
        }

        // The tile. A split tile's upper alarm is closed at the top only, its lower alarm at the bottom only.
        boolean closedTop = tile.role() != AnnunciatorBox.LOWER;
        boolean closedBottom = tile.role() != AnnunciatorBox.UPPER;
        float[] o = s.outline;
        float[] f = s.face;
        float ox0 = s.gap;
        float ox1 = w - s.gap;
        float oy0 = closedTop ? s.gap : 0.0f;
        float oy1 = closedBottom ? h - s.gap : h;
        float fx0 = s.insets[0];
        float fx1 = w - s.insets[2];
        float fy0 = s.insets[1];
        float fy1 = h - s.insets[3];
        // Where the side runs start and end: at the corners of a closed end, at the window's edge of an open one.
        float ry0 = closedTop ? oy0 + s.rim : 0.0f;
        float ry1 = closedBottom ? oy1 - s.rim : h;
        float zs = s.zScale;
        for (int i = 0; i < s.model.partCount(); i++) {
            BakedPanelModel.Part part = s.model.part(i);
            switch (part.name) {
                case FACE -> span(sink, part, f[0], f[2], fx0, fx1, f[1], f[3], fy0, fy1, zs, faceRgb);
                case RIM_LEFT -> span(sink, part, o[0], f[0], ox0, fx0, f[1], f[3], ry0, ry1, zs, -1);
                case RIM_RIGHT -> span(sink, part, f[2], o[2], fx1, ox1, f[1], f[3], ry0, ry1, zs, -1);
                case RIM_TOP -> {
                    if (closedTop) {
                        span(sink, part, f[0], f[2], fx0, fx1, o[1], f[1], oy0, oy0 + s.rim, zs, -1);
                    }
                }
                case RIM_BOTTOM -> {
                    if (closedBottom) {
                        span(sink, part, f[0], f[2], fx0, fx1, f[3], o[3], oy1 - s.rim, oy1, zs, -1);
                    }
                }
                case CORNER_TOP_LEFT -> {
                    if (closedTop) {
                        span(sink, part, o[0], f[0], ox0, fx0, o[1], f[1], oy0, oy0 + s.rim, zs, -1);
                    }
                }
                case CORNER_TOP_RIGHT -> {
                    if (closedTop) {
                        span(sink, part, f[2], o[2], fx1, ox1, o[1], f[1], oy0, oy0 + s.rim, zs, -1);
                    }
                }
                case CORNER_BOTTOM_LEFT -> {
                    if (closedBottom) {
                        span(sink, part, o[0], f[0], ox0, fx0, f[3], o[3], oy1 - s.rim, oy1, zs, -1);
                    }
                }
                case CORNER_BOTTOM_RIGHT -> {
                    if (closedBottom) {
                        span(sink, part, f[2], o[2], fx1, ox1, f[3], o[3], oy1 - s.rim, oy1, zs, -1);
                    }
                }
                default -> {
                    // The model's own plate and dividing line are for its picture: the box draws its own.
                }
            }
        }
        // Half the dividing line, along the edge the two alarms of a split tile share.
        if (!closedBottom) {
            sink.rect(fx0, h - s.halfDivider, fx1 - fx0, s.halfDivider, AnnunciatorBox.TILE_Z + DIVIDER_LIFT, DIVIDER_RGB, false);
        } else if (!closedTop) {
            sink.rect(fx0, 0.0f, fx1 - fx0, s.halfDivider, AnnunciatorBox.TILE_Z + DIVIDER_LIFT, DIVIDER_RGB, false);
        }

        float unit = tile.unit();
        if (tile.nameplate()) {
            float nw = AnnunciatorBox.NAMEPLATE_W * unit;
            sink.box(tile.nameX2() / 2.0f - nw / 2.0f, h + AnnunciatorBox.NAMEPLATE_GAP * unit, nw, AnnunciatorBox.NAMEPLATE_H * unit,
                    z, z + nameplateThickness(unit), NAMEPLATE_RGB, false);
        }
        if (tile.latch()) {
            latch(sink, w + s.marginRight / 2.0f, tile.latchY2() / 2.0f, AnnunciatorBox.LATCH_R * unit, z,
                    z + AnnunciatorBox.LATCH_H * unit);
        }
    }

    /** How far the nameplate stands off the plate, 1/16 block: thick enough to stay clear of the plate at any size. */
    static float nameplateThickness(float unit) {
        return Math.max(0.05f, AnnunciatorBox.NAMEPLATE_T * unit);
    }

    /**
     * A piece of plate from the board to elevation {@code z}: its front, and its edge along each side of {@code sides}
     * ({@link AnnunciatorBox#LEFT} and so on), the sides that are the outside of the box.
     */
    private static void plate(QuadSink sink, float x, float y, float w, float h, float z, int sides) {
        sink.rect(x, y, w, h, z, PLATE_RGB, false);
        if ((sides & AnnunciatorBox.LEFT) != 0) {
            sink.quad(x, y, 0.0f, x, y, z, x, y + h, z, x, y + h, 0.0f, PLATE_RGB, QuadSink.NORMAL_LEFT, false);
        }
        if ((sides & AnnunciatorBox.RIGHT) != 0) {
            sink.quad(x + w, y, 0.0f, x + w, y + h, 0.0f, x + w, y + h, z, x + w, y, z, PLATE_RGB, QuadSink.NORMAL_RIGHT, false);
        }
        if ((sides & AnnunciatorBox.TOP) != 0) {
            sink.quad(x, y, 0.0f, x + w, y, 0.0f, x + w, y, z, x, y, z, PLATE_RGB, QuadSink.NORMAL_UP, false);
        }
        if ((sides & AnnunciatorBox.BOTTOM) != 0) {
            sink.quad(x, y + h, 0.0f, x, y + h, z, x + w, y + h, z, x + w, y + h, 0.0f, PLATE_RGB, QuadSink.NORMAL_DOWN, false);
        }
    }

    /** The round latch: an octagonal knob of radius {@code r} about {@code (cx, cy)}, from {@code z0} to {@code z1}. */
    private static void latch(QuadSink sink, float cx, float cy, float r, float z0, float z1) {
        int side = QuadSink.shade(LATCH_RGB, 0.72f);
        for (int i = 0; i < 8; i++) {
            int j = (i + 1) % 8;
            float ax = cx + r * OCTAGON_X[i];
            float ay = cy + r * OCTAGON_Y[i];
            float bx = cx + r * OCTAGON_X[j];
            float by = cy + r * OCTAGON_Y[j];
            float nx = OCTAGON_X[i] + OCTAGON_X[j];
            float ny = OCTAGON_Y[i] + OCTAGON_Y[j];
            float length = (float) Math.sqrt(nx * nx + ny * ny);
            sink.quad(ax, ay, z0, bx, by, z0, bx, by, z1, ax, ay, z1, side, nx / length, ny / length, 0.0f, false);
        }
        cap(sink, cx, cy, r, z1, 0, 1, 2, 3);
        cap(sink, cx, cy, r, z1, 0, 3, 4, 7);
        cap(sink, cx, cy, r, z1, 4, 5, 6, 7);
    }

    /** One quad of the latch's front, through four corners of the octagon. */
    private static void cap(QuadSink sink, float cx, float cy, float r, float z, int a, int b, int c, int d) {
        sink.quad(cx + r * OCTAGON_X[a], cy + r * OCTAGON_Y[a], z, cx + r * OCTAGON_X[b], cy + r * OCTAGON_Y[b], z,
                cx + r * OCTAGON_X[c], cy + r * OCTAGON_Y[c], z, cx + r * OCTAGON_X[d], cy + r * OCTAGON_Y[d], z,
                LATCH_RGB, QuadSink.NORMAL_FACE, false);
    }

    /**
     * Records {@code part} with its model x from {@code ax0..ax1} (inches) laid onto the cells {@code bx0..bx1}, its y
     * likewise, and its z times {@code zScale}; recoloured with {@code baseRgb} when that is not negative. A model range
     * of no length draws nothing.
     */
    private static void span(QuadSink sink, BakedPanelModel.Part part, float ax0, float ax1, float bx0, float bx1,
                             float ay0, float ay1, float by0, float by1, float zScale, int baseRgb) {
        if (!(ax1 > ax0) || !(ay1 > ay0)) {
            return;
        }
        float kx = (bx1 - bx0) / (ax1 - ax0);
        float ky = (by1 - by0) / (ay1 - ay0);
        for (int f = 0; f < part.faceCount; f++) {
            sink.quad(bx0 + (part.corner(f, 0, 0) - ax0) * kx, by0 + (part.corner(f, 0, 1) - ay0) * ky, part.corner(f, 0, 2) * zScale,
                    bx0 + (part.corner(f, 1, 0) - ax0) * kx, by0 + (part.corner(f, 1, 1) - ay0) * ky, part.corner(f, 1, 2) * zScale,
                    bx0 + (part.corner(f, 2, 0) - ax0) * kx, by0 + (part.corner(f, 2, 1) - ay0) * ky, part.corner(f, 2, 2) * zScale,
                    bx0 + (part.corner(f, 3, 0) - ax0) * kx, by0 + (part.corner(f, 3, 1) - ay0) * ky, part.corner(f, 3, 2) * zScale,
                    PartDraw.faceColor(part, f, baseRgb, 1.0f), part.normal(f, 0), part.normal(f, 1), part.normal(f, 2), false);
        }
    }
}
