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
    /** Flag value recorded meshes use for "this quad carries its own normal"; never passed to a sink as an enum normal. */
    int NORMAL_CUSTOM = 5;

    /** One quad, corners in order around the perimeter. {@code rgb} is 0xRRGGBB. */
    void quad(float x0, float y0, float z0, float x1, float y1, float z1,
              float x2, float y2, float z2, float x3, float y3, float z3,
              int rgb, int normal, boolean emissive);

    /**
     * One quad with an arbitrary normal, in component space (x right, y down the face, z out of the face; it
     * must be a unit vector). Baked models use this so curved handles and knobs shade smoothly instead of
     * snapping to the five axis directions. A sink that does not override it draws the quad with the nearest
     * axis normal, so every existing sink keeps working.
     */
    default void quad(float x0, float y0, float z0, float x1, float y1, float z1,
                      float x2, float y2, float z2, float x3, float y3, float z3,
                      int rgb, float nx, float ny, float nz, boolean emissive) {
        quad(x0, y0, z0, x1, y1, z1, x2, y2, z2, x3, y3, z3, rgb, nearestNormal(nx, ny, nz), emissive);
    }

    /**
     * One translucent quad (a meter window). Only the live {@link PanelRenderContext} draws it: recorded meshes
     * ignore it, because translucent geometry must come after everything it covers and so cannot be replayed
     * from the static mesh. {@code alpha} is 0..255.
     */
    default void glassQuad(float x0, float y0, float z0, float x1, float y1, float z1,
                           float x2, float y2, float z2, float x3, float y3, float z3,
                           int rgb, int alpha, float nx, float ny, float nz) {
    }

    /** The axis normal constant closest to the component-space direction {@code (nx, ny, nz)}. */
    static int nearestNormal(float nx, float ny, float nz) {
        float ax = Math.abs(nx);
        float ay = Math.abs(ny);
        float az = Math.abs(nz);
        if (az >= ax && az >= ay) {
            return NORMAL_FACE;
        }
        if (ax >= ay) {
            return nx < 0 ? NORMAL_LEFT : NORMAL_RIGHT;
        }
        return ny < 0 ? NORMAL_UP : NORMAL_DOWN;
    }

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
