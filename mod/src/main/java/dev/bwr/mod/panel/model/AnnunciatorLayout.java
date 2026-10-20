package dev.bwr.mod.panel.model;

/**
 * Where the legend of an annunciator window goes and what colours the window and its lettering are: up to four lines,
 * every line centred in the lit tile, one scale for all of them so the legend reads as one block, shrunk until the
 * widest line fits across and all the lines fit down. Pure, so it can be tested without a font: the caller passes the
 * width of each line in font pixels.
 */
public final class AnnunciatorLayout {
    private AnnunciatorLayout() {
    }

    public static final int MAX_LINES = 4;
    /** Height of one line in font pixels (a cap is 7 and a pixel of leading). */
    public static final float LINE_PIXELS = 8.0f;

    /**
     * The placed legend: one scale (cells per font pixel) and the top-left of each line, in component cells.
     *
     * @param lines how many lines were placed; {@code x} and {@code y} have that many entries
     */
    public record Fit(float scale, int lines, float[] x, float[] y, float lineHeight) {
    }

    /** The margin kept free inside the tile: a share of 1 cell that shrinks on windows under 8 cells across. */
    public static float margin(float w, float h) {
        return Math.min(1.0f, Math.min(w, h) / 8.0f);
    }

    /**
     * @param w         the window's footprint width in cells
     * @param h         its height
     * @param insets    {left, top, right, bottom} of the tile from the footprint ({@link AnnunciatorBox#insets})
     * @param widths    the width in font pixels of each non-empty line, top to bottom (at most {@link #MAX_LINES} are placed)
     * @param rowAspect real length of a row relative to a column
     */
    public static Fit fit(float w, float h, float[] insets, int[] widths, float rowAspect) {
        int lines = Math.min(MAX_LINES, widths.length);
        float margin = margin(w, h);
        float tileW = w - insets[0] - insets[2];
        float tileH = h - insets[1] - insets[3];
        float innerW = tileW - 0.9f * margin;
        float innerH = tileH - 0.6f * margin;
        float widest = 1;
        for (int i = 0; i < lines; i++) {
            widest = Math.max(widest, widths[i]);
        }
        float scale = lines == 0 ? 0.0f : Math.min(innerW / widest, innerH / (lines * LINE_PIXELS * rowAspect));
        float lineHeight = LINE_PIXELS * scale * rowAspect;
        float top = insets[1] + (tileH - lineHeight * lines) / 2.0f;
        float[] x = new float[lines];
        float[] y = new float[lines];
        for (int i = 0; i < lines; i++) {
            x[i] = insets[0] + (tileW - Math.max(1, widths[i]) * scale) / 2.0f;
            y[i] = top + i * lineHeight;
        }
        return new Fit(scale, lines, x, y, lineHeight);
    }

    // ---- colours: the window's colour scheme (white, amber or red are the three the references show)

    /** The window body when it is dark: a dim version of its colour. */
    public static int offBody(int color) {
        return shade(color, 0.62f);
    }

    /** Whether a body colour is light enough to take dark lettering (white and amber are, red is not). */
    public static boolean lightBody(int rgb) {
        return luma(rgb) > 0.5f;
    }

    /**
     * The lettering colour: dark on a light tile, light on a dark one, in the tile's own hue; lit, it is full strength,
     * off the legend is the dim version of it.
     */
    public static int ink(int color, boolean lit) {
        int body = lit ? color : offBody(color);
        if (lightBody(color)) {
            return shade(body, lit ? 0.34f : 0.28f);
        }
        int bright = mixWhite(color, 0.72f);
        return lit ? bright : shade(bright, 0.74f);
    }

    public static float luma(int rgb) {
        return (0.299f * (rgb >> 16 & 0xFF) + 0.587f * (rgb >> 8 & 0xFF) + 0.114f * (rgb & 0xFF)) / 255.0f;
    }

    private static int shade(int rgb, float factor) {
        int r = Math.min(255, (int) ((rgb >> 16 & 0xFF) * factor));
        int g = Math.min(255, (int) ((rgb >> 8 & 0xFF) * factor));
        int b = Math.min(255, (int) ((rgb & 0xFF) * factor));
        return r << 16 | g << 8 | b;
    }

    private static int mixWhite(int rgb, float amount) {
        int r = (int) ((rgb >> 16 & 0xFF) + (255 - (rgb >> 16 & 0xFF)) * amount);
        int g = (int) ((rgb >> 8 & 0xFF) + (255 - (rgb >> 8 & 0xFF)) * amount);
        int b = (int) ((rgb & 0xFF) + (255 - (rgb & 0xFF)) * amount);
        return Math.min(255, r) << 16 | Math.min(255, g) << 8 | Math.min(255, b);
    }
}
