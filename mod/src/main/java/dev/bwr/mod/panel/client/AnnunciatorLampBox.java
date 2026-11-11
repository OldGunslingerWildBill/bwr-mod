package dev.bwr.mod.panel.client;

import dev.bwr.mod.panel.model.AnnunciatorBox;
import dev.bwr.mod.panel.model.BakedPanelModel;
import dev.bwr.mod.panel.model.PartDraw;

/**
 * Lays the annunciator window model ({@code ann_window}, built by {@code art/models/panel/annunciator}) onto a window's
 * cells. Unlike every other baked model it is not fitted with one uniform scale: windows tile a box with no space between
 * them at any size and on any slope, so the model is cut in nine (the four runs of the trim frame, its four corners and
 * the lens) and each piece is stretched onto the same rectangles the procedural window uses ({@link AnnunciatorBox#insets}):
 * the lens fills the tile, a frame run is drawn only on a side that is not joined to a neighbour and then reaches across
 * the gap to the neighbour's run, and a corner only where both its sides are open. A window inside a box is therefore its
 * lens and nothing else, the same number of quads as the procedural tile.
 *
 * <p>The model's lens front is put at {@link AnnunciatorBox#TILE_Z}, so the lit tile, the no-signal flag and the legend the
 * window model draws every frame sit on it exactly as they sit on the procedural tile.
 */
final class AnnunciatorLampBox {
    private AnnunciatorLampBox() {
    }

    /** Part names of the window model. */
    static final String LENS = "lens";
    static final String FRAME_TOP = "frame_top";
    static final String FRAME_BOTTOM = "frame_bottom";
    static final String FRAME_LEFT = "frame_left";
    static final String FRAME_RIGHT = "frame_right";
    static final String CORNER_TOP_LEFT = "corner_tl";
    static final String CORNER_TOP_RIGHT = "corner_tr";
    static final String CORNER_BOTTOM_LEFT = "corner_bl";
    static final String CORNER_BOTTOM_RIGHT = "corner_br";

    /**
     * Records the frame and the unlit lens of one window.
     *
     * @param edges   the window's edge word ({@link AnnunciatorBox})
     * @param insets  {@link AnnunciatorBox#insets} for those edges
     * @param lensRgb the colour of the unlit lens
     * @return false, having drawn nothing, when {@code model} is not a window model (no lens strictly inside its
     *         footprint): the caller then draws the procedural window
     */
    static boolean emit(QuadSink sink, BakedPanelModel model, int edges, float w, float h, float[] insets, int lensRgb) {
        BakedPanelModel.Part lens = model.part(LENS);
        if (lens == null || lens.faceCount == 0) {
            return false;
        }
        // The lens's outline is the tile; what lies outside it is the frame.
        float lx0 = Float.MAX_VALUE;
        float ly0 = Float.MAX_VALUE;
        float lx1 = -Float.MAX_VALUE;
        float ly1 = -Float.MAX_VALUE;
        float lz = 0.0f;
        for (int f = 0; f < lens.faceCount; f++) {
            for (int c = 0; c < 4; c++) {
                lx0 = Math.min(lx0, lens.corner(f, c, 0));
                ly0 = Math.min(ly0, lens.corner(f, c, 1));
                lx1 = Math.max(lx1, lens.corner(f, c, 0));
                ly1 = Math.max(ly1, lens.corner(f, c, 1));
                lz = Math.max(lz, lens.corner(f, c, 2));
            }
        }
        float fw = model.footprintW;
        float fh = model.footprintH;
        if (!(lx0 > 0.0f && ly0 > 0.0f && lx1 > lx0 && ly1 > ly0 && lx1 < fw && ly1 < fh && lz > 0.0f)) {
            return false;
        }
        float zScale = AnnunciatorBox.TILE_Z / lz;
        boolean left = (edges & AnnunciatorBox.LEFT) != 0;
        boolean top = (edges & AnnunciatorBox.TOP) != 0;
        boolean right = (edges & AnnunciatorBox.RIGHT) != 0;
        boolean bottom = (edges & AnnunciatorBox.BOTTOM) != 0;
        // The tile, and how far a frame run reaches: to the window's edge where that side is joined (it meets the
        // neighbour's run there), else to the corner piece.
        float tx0 = insets[0];
        float ty0 = insets[1];
        float tx1 = w - insets[2];
        float ty1 = h - insets[3];
        float rx0 = left ? 0.0f : tx0;
        float rx1 = right ? w : tx1;
        float ry0 = top ? 0.0f : ty0;
        float ry1 = bottom ? h : ty1;
        for (int i = 0; i < model.partCount(); i++) {
            BakedPanelModel.Part part = model.part(i);
            switch (part.name) {
                case LENS -> span(sink, part, lx0, lx1, tx0, tx1, ly0, ly1, ty0, ty1, zScale, lensRgb);
                case FRAME_TOP -> {
                    if (!top) {
                        span(sink, part, lx0, lx1, rx0, rx1, 0.0f, ly0, 0.0f, ty0, zScale, -1);
                    }
                }
                case FRAME_BOTTOM -> {
                    if (!bottom) {
                        span(sink, part, lx0, lx1, rx0, rx1, ly1, fh, ty1, h, zScale, -1);
                    }
                }
                case FRAME_LEFT -> {
                    if (!left) {
                        span(sink, part, 0.0f, lx0, 0.0f, tx0, ly0, ly1, ry0, ry1, zScale, -1);
                    }
                }
                case FRAME_RIGHT -> {
                    if (!right) {
                        span(sink, part, lx1, fw, tx1, w, ly0, ly1, ry0, ry1, zScale, -1);
                    }
                }
                case CORNER_TOP_LEFT -> {
                    if (!top && !left) {
                        span(sink, part, 0.0f, lx0, 0.0f, tx0, 0.0f, ly0, 0.0f, ty0, zScale, -1);
                    }
                }
                case CORNER_TOP_RIGHT -> {
                    if (!top && !right) {
                        span(sink, part, lx1, fw, tx1, w, 0.0f, ly0, 0.0f, ty0, zScale, -1);
                    }
                }
                case CORNER_BOTTOM_LEFT -> {
                    if (!bottom && !left) {
                        span(sink, part, 0.0f, lx0, 0.0f, tx0, ly1, fh, ty1, h, zScale, -1);
                    }
                }
                case CORNER_BOTTOM_RIGHT -> {
                    if (!bottom && !right) {
                        span(sink, part, lx1, fw, tx1, w, ly1, fh, ty1, h, zScale, -1);
                    }
                }
                default -> {
                    // A part this class has no rule for is not drawn.
                }
            }
        }
        return true;
    }

    /**
     * Records {@code part} with its model x from {@code ax0..ax1} (inches) laid onto the cells {@code bx0..bx1}, its y
     * likewise, and its z times {@code zScale}; recoloured with {@code baseRgb} when that is not negative.
     */
    private static void span(QuadSink sink, BakedPanelModel.Part part, float ax0, float ax1, float bx0, float bx1,
                             float ay0, float ay1, float by0, float by1, float zScale, int baseRgb) {
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
