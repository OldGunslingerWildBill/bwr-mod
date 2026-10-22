package dev.bwr.mod.panel.plant;

import dev.bwr.mod.panel.model.GaugeScale;

/**
 * The text grid of one plant computer: how many character cells fit its footprint at the wrench's Text size. A character
 * cell is {@link #BASE_W} x {@link #BASE_H} panel cells at 100 percent (the same slider and range as the gauges,
 * {@link GaugeScale}), with a one cell margin round the screen, so a 4 x 3 block display (64 x 48) is 31 x 15
 * characters at 100 percent and 62 x 30 at 50 percent. Programs get the size from {@code plantInfo}.
 *
 * <p>The last row is where soft keys sit: key {@code n} of {@link PlantLimits#MAX_KEYS} is the n-th sixth of its width.
 */
public record PlantLayout(int cols, int rows, float left, float top, float cellW, float cellH) {
    public static final float BASE_W = 2.0f;
    public static final float BASE_H = 3.0f;
    /** The screen's margin inside the component, in panel cells. */
    public static final float MARGIN = 1.0f;

    public static PlantLayout of(int w, int h, int textPercent) {
        float factor = GaugeScale.factor(textPercent);
        float usableW = Math.max(1.0f, w - 2.0f * MARGIN);
        float usableH = Math.max(1.0f, h - 2.0f * MARGIN);
        int cols = Math.max(1, Math.min(PlantLimits.MAX_COLS, (int) Math.floor(usableW / (BASE_W * factor))));
        int rows = Math.max(1, Math.min(PlantLimits.MAX_ROWS, (int) Math.floor(usableH / (BASE_H * factor))));
        return new PlantLayout(cols, rows, MARGIN, MARGIN, usableW / cols, usableH / rows);
    }

    /** First character column (0-based) of soft key {@code key} (1 to 6). */
    public int keyStart(int key) {
        return (key - 1) * cols / PlantLimits.MAX_KEYS;
    }

    /** Columns of soft key {@code key}. */
    public int keyWidth(int key) {
        return key * cols / PlantLimits.MAX_KEYS - keyStart(key);
    }

    /** The soft key under character cell ({@code col}, {@code row}), 0-based, or 0 when it is not on the key row. */
    public int keyAt(int col, int row) {
        if (row != rows - 1 || col < 0 || col >= cols) {
            return 0;
        }
        for (int key = 1; key <= PlantLimits.MAX_KEYS; key++) {
            if (col >= keyStart(key) && col < keyStart(key) + keyWidth(key)) {
                return key;
            }
        }
        return 0;
    }

    /** The character cell under a point of the component, in panel cells; {@code {col, row}} 0-based, or null outside the grid. */
    public int[] cellAt(double x, double y) {
        double c = (x - left) / cellW;
        double r = (y - top) / cellH;
        if (c < 0 || r < 0 || c >= cols || r >= rows) {
            return null;
        }
        return new int[] {(int) c, (int) r};
    }
}
