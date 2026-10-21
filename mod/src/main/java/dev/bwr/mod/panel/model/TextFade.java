package dev.bwr.mod.panel.model;

/**
 * Distance fade of printed panel text. Seen from across a room a real size legend is a few screen pixels tall and
 * turns to speckle: each stroke is a fraction of a pixel and the sampling decides which ones show. Below a size on the
 * screen the glyphs are therefore faded out and a plain soft strip in the text's colour (what ink looks like from afar)
 * fades in. Everything is a function of the glyph's projected cap height in screen pixels, which the renderer works out
 * once per component per frame from the distance, so nothing here is per glyph. The blend is continuous, so there is no
 * boundary to flicker at. Pure.
 */
public final class TextFade {
    private TextFade() {
    }

    /** At and below this cap height (screen pixels) the text is gone and only the strip is left. */
    public static final float GONE_PX = 2.0f;
    /** At and above this cap height the text is drawn as normal. */
    public static final float FULL_PX = 4.5f;
    /** The strip fades out again below this size (ink too small to see at all) and is complete from {@link #STRIP_FULL_PX}. */
    public static final float STRIP_GONE_PX = 0.25f;
    public static final float STRIP_FULL_PX = 1.2f;
    /** The strip's strongest opacity: ink covers about half of a line of text. */
    public static final float STRIP_ALPHA = 0.5f;

    private static float smooth(float edge0, float edge1, float x) {
        float t = Math.max(0.0f, Math.min(1.0f, (x - edge0) / (edge1 - edge0)));
        return t * t * (3.0f - 2.0f * t);
    }

    /** The text's opacity at a cap height in screen pixels: 0 at or below {@link #GONE_PX}, 1 at or above {@link #FULL_PX}. */
    public static float textAlpha(float capPixels) {
        return smooth(GONE_PX, FULL_PX, capPixels);
    }

    /** The strip's opacity: it takes over as the text leaves, and itself fades out when the line is under a pixel. */
    public static float stripAlpha(float capPixels) {
        return (1.0f - textAlpha(capPixels)) * STRIP_ALPHA * smooth(STRIP_GONE_PX, STRIP_FULL_PX, capPixels);
    }

    /**
     * Cap height on the screen in pixels.
     *
     * @param capCells    cap height in panel cells (a cell is 1/16 block)
     * @param pixelsPerBlockAtOne screen pixels one block spans at distance 1 (half the window height times the
     *                    projection's focal length); the size falls off with the distance
     * @param distance    distance from the camera to the component in blocks
     */
    public static float capPixels(float capCells, float pixelsPerBlockAtOne, float distance) {
        return capCells / 16.0f * pixelsPerBlockAtOne / Math.max(0.05f, distance);
    }
}
