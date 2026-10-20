package dev.bwr.mod.panel.client;

/**
 * Small drawing helpers shared by the placeholder control models, plus the
 * control-board palette (spec section 9). Nothing here allocates: models call
 * these every frame.
 */
final class ModelDraw {
    private ModelDraw() {
    }

    /** Seafoam benchboard paint and its bezel. */
    static final int FACE = 0xA7BCAE;
    static final int BEZEL = 0x7D9284;
    /** Dark housings and recesses. */
    static final int HOUSING = 0x23272C;
    static final int RECESS = 0x14171A;
    static final int STEEL = 0xC4C9CE;
    static final int ENGRAVED = 0xE8E8E8;
    static final int AMBER = 0xFFB000;
    static final int RED = 0xE02020;
    static final int GREEN = 0x30D060;
    static final int NO_SIGNAL = 0x6A6F73;

    /**
     * A bar from the point {@code (cx, cy)} in direction {@code angle} (radians, 0 = straight up the face,
     * positive clockwise), {@code length} cells long and {@code width} wide, as one face-parallel quad at
     * elevation {@code z}. Used for pointers and lever arms.
     */
    static void bar(QuadSink sink, float cx, float cy, float angle, float length, float width, float z, int rgb,
                    boolean emissive) {
        float dx = (float) Math.sin(angle);
        float dy = (float) -Math.cos(angle);
        float nx = -dy * width * 0.5f;
        float ny = dx * width * 0.5f;
        float ex = cx + dx * length;
        float ey = cy + dy * length;
        sink.quad(cx - nx, cy - ny, z, cx + nx, cy + ny, z, ex + nx, ey + ny, z, ex - nx, ey - ny, z,
                rgb, QuadSink.NORMAL_FACE, emissive);
    }

    /** A tick mark on a circle of radius {@code from..to} cells around the centre, at {@code angle}. */
    static void tick(QuadSink sink, float cx, float cy, float angle, float from, float to, float width, float z, int rgb) {
        float dx = (float) Math.sin(angle);
        float dy = (float) -Math.cos(angle);
        bar(sink, cx + dx * from, cy + dy * from, angle, to - from, width, z, rgb, false);
    }

    /** Angle of detent {@code index} of {@code count} on a 270 degree dial, symmetric about straight up. */
    static float dialAngle(int index, int count) {
        float t = count <= 1 ? 0.5f : (float) index / (count - 1);
        return (float) Math.toRadians(-135.0 + 270.0 * t);
    }

    /**
     * Draws {@code text} centred in the box {@code (bx, by, bw, bh)} (cells), as large as fits, one line.
     * Only call at {@link PanelRenderContext#LOD_FULL}.
     */
    static void centreText(PanelRenderContext context, String text, float bx, float by, float bw, float bh, float z,
                           int rgb, boolean emissive) {
        if (text.isEmpty()) {
            return;
        }
        float width = Math.max(1, context.font().width(text));
        float scale = centreScale(width, bw, bh, context.rowAspect());
        float x = bx + (bw - width * scale) / 2.0f;
        float y = by + (bh - 8.0f * scale * context.rowAspect()) / 2.0f;
        context.text(text, x, y, scale, z, rgb, emissive);
    }

    /** The font scale {@link #centreText} draws a line {@code width} font pixels wide at in a {@code bw x bh} box. */
    static float centreScale(float width, float bw, float bh, float rowAspect) {
        return Math.min(bw / Math.max(1.0f, width), bh / (8.0f * rowAspect));
    }
}
