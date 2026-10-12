package dev.bwr.mod.panel.component;

import dev.bwr.mod.panel.PanelGeometry;

/**
 * A component's size on the mounting grid, in cells: {@code w} across and
 * {@code h} down. One cell is 1/16 block. A footprint may not span more than
 * {@link PanelGeometry#MAX_SPAN_BLOCKS} blocks either way, wherever on the grid
 * it is anchored.
 */
public record Footprint(int w, int h) {
    public static final int MAX_CELLS = PanelGeometry.GRID * PanelGeometry.MAX_SPAN_BLOCKS;

    public Footprint {
        if (w < 1 || h < 1 || w > MAX_CELLS || h > MAX_CELLS) {
            throw new IllegalArgumentException("footprint " + w + "x" + h);
        }
    }

    public static Footprint of(int w, int h) {
        return new Footprint(w, h);
    }

    @Override
    public String toString() {
        return w + "x" + h;
    }
}
