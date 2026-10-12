package dev.bwr.mod.panel.client;

/**
 * Where a {@link ComponentModel} sends its geometry. Two implementations: the
 * {@link MeshBuilder} that records a component's static geometry once, and the
 * {@link PanelRenderContext} that draws immediately (the moving parts).
 *
 * <h3>Component space</h3>
 * {@code x} runs right and {@code y} down the face, both in grid cells from the
 * component's top-left corner (so a 2x2 lamp spans 0..2 both ways); {@code z}
 * is elevation above the face in 1/16 block. A rectangle is axis aligned in
 * that space; on a sloped panel it is drawn on the slope.
 */
public interface QuadSink {
    int NORMAL_FACE = 0;
    int NORMAL_LEFT = 1;
    int NORMAL_RIGHT = 2;
    int NORMAL_UP = 3;
    int NORMAL_DOWN = 4;

    /** One quad, corners in order around the perimeter. {@code rgb} is 0xRRGGBB. */
    void quad(float x0, float y0, float z0, float x1, float y1, float z1,
              float x2, float y2, float z2, float x3, float y3, float z3,
              int rgb, int normal, boolean emissive);

    /** A rectangle parallel to the face at elevation {@code z}. */
    default void rect(float x, float y, float w, float h, float z, int rgb, boolean emissive) {
        quad(x, y, z, x + w, y, z, x + w, y + h, z, x, y + h, z, rgb, NORMAL_FACE, emissive);
    }

    /** A solid block from elevation {@code z0} to {@code z1}: a top and four sides (the back is never seen). */
    default void box(float x, float y, float w, float h, float z0, float z1, int rgb, boolean emissive) {
        rect(x, y, w, h, z1, rgb, emissive);
        quad(x, y, z0, x, y, z1, x, y + h, z1, x, y + h, z0, rgb, NORMAL_LEFT, emissive);
        quad(x + w, y, z0, x + w, y + h, z0, x + w, y + h, z1, x + w, y, z1, rgb, NORMAL_RIGHT, emissive);
        quad(x, y, z0, x + w, y, z0, x + w, y, z1, x, y, z1, rgb, NORMAL_UP, emissive);
        quad(x, y + h, z0, x, y + h, z1, x + w, y + h, z1, x + w, y + h, z0, rgb, NORMAL_DOWN, emissive);
    }

    /** Scales each channel of {@code rgb} by {@code factor} (0..1), for off-state colours. */
    static int shade(int rgb, float factor) {
        int r = Math.min(255, (int) (((rgb >> 16) & 0xFF) * factor));
        int g = Math.min(255, (int) (((rgb >> 8) & 0xFF) * factor));
        int b = Math.min(255, (int) ((rgb & 0xFF) * factor));
        return r << 16 | g << 8 | b;
    }
}
