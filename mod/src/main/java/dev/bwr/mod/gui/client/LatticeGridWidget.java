package dev.bwr.mod.gui.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.IntConsumer;

/**
 * The one grid widget, per {@code SPEC.md} section 10: "It's a lattice grid
 * already, so build one grid widget and reuse it."
 *
 * <p>It knows nothing about reactors. It is handed a set of cells, each with a
 * position in some square lattice, a colour and a tooltip, and it draws them,
 * hit-tests them and reports clicks. Three things use it so far — the flux
 * overlay, the burnup overlay and the rod position overlay — and the rod
 * overlay is on a different lattice from the other two, which is exactly the
 * reuse the spec was asking for.
 *
 * <h2>Fitting</h2>
 * Legacy fuel uses a 31x31 lattice and compact fuel a 42x42 lattice. Only
 * occupied geometry is drawn: the bounding box of the supplied cells sets the
 * scale, and two-pixel cells keep maximum cores within the panel.
 */
public class LatticeGridWidget extends AbstractWidget {

    /** Cells never render smaller than this, even if that means clipping. */
    private static final int MIN_CELL = 2;
    /** Nor larger, so a 9-rod core does not get dinner-plate cells. */
    private static final int MAX_CELL = 14;

    /** What to draw. Implemented by the screens against their own snapshot. */
    public interface Cells {
        /** How many cells there are. */
        int count();

        /**
         * Columns in the lattice the positions index into. Not necessarily the
         * number of rows: the assembly lattice is square, but the rod lattice
         * follows the vessel footprint and a rectangular vessel is ordinary.
         */
        int latticeWidth();

        /** Position of a cell in that lattice, {@code row * latticeWidth + column}. */
        int latticeIndex(int cell);

        /** Fill colour, ARGB. */
        int colour(int cell);

        /** Hover text. May be empty. */
        List<Component> tooltip(int cell);
    }

    private static final Cells EMPTY = new Cells() {
        @Override
        public int count() {
            return 0;
        }

        @Override
        public int latticeWidth() {
            return 1;
        }

        @Override
        public int latticeIndex(int cell) {
            return 0;
        }

        @Override
        public int colour(int cell) {
            return 0;
        }

        @Override
        public List<Component> tooltip(int cell) {
            return List.of();
        }
    };

    private Cells cells = EMPTY;
    private final IntConsumer onSelect;

    private int selected = -1;
    private int marked = -1;
    private int hovered = -1;

    // Geometry of the last render, used for hit testing.
    private int cellPx = MIN_CELL;
    private int originX;
    private int originY;
    private int minColumn;
    private int minRow;
    private int columns = 1;
    private int rows = 1;

    public LatticeGridWidget(int x, int y, int width, int height, IntConsumer onSelect) {
        super(x, y, width, height, Component.empty());
        this.onSelect = onSelect;
    }

    public void setCells(Cells cells) {
        this.cells = cells == null ? EMPTY : cells;
        if (selected >= this.cells.count()) {
            selected = -1;
        }
        if (marked >= this.cells.count()) {
            marked = -1;
        }
    }

    /** The cell the player last clicked, or -1. */
    public int selected() {
        return selected;
    }

    public void setSelected(int cell) {
        this.selected = cell;
    }

    /** A second, differently outlined cell — the source end of a shuffle. */
    public int marked() {
        return marked;
    }

    public void setMarked(int cell) {
        this.marked = cell;
    }

    public int hovered() {
        return hovered;
    }

    // -----------------------------------------------------------------

    private void measure() {
        int count = cells.count();
        if (count == 0) {
            columns = rows = 1;
            cellPx = MIN_CELL;
            originX = getX();
            originY = getY();
            return;
        }
        int width = Math.max(1, cells.latticeWidth());
        int minC = Integer.MAX_VALUE;
        int maxC = Integer.MIN_VALUE;
        int minR = Integer.MAX_VALUE;
        int maxR = Integer.MIN_VALUE;
        for (int i = 0; i < count; i++) {
            int index = cells.latticeIndex(i);
            int c = index % width;
            int r = index / width;
            minC = Math.min(minC, c);
            maxC = Math.max(maxC, c);
            minR = Math.min(minR, r);
            maxR = Math.max(maxR, r);
        }
        minColumn = minC;
        minRow = minR;
        columns = maxC - minC + 1;
        rows = maxR - minR + 1;

        int fit = Math.min(getWidth() / columns, getHeight() / rows);
        cellPx = Math.max(MIN_CELL, Math.min(MAX_CELL, fit));
        originX = getX() + (getWidth() - columns * cellPx) / 2;
        originY = getY() + (getHeight() - rows * cellPx) / 2;
    }

    private int cellAt(double mouseX, double mouseY) {
        int width = Math.max(1, cells.latticeWidth());
        int column = (int) Math.floor((mouseX - originX) / cellPx) + minColumn;
        int row = (int) Math.floor((mouseY - originY) / cellPx) + minRow;
        // Bound by the block of squares actually drawn, which measure() has
        // already worked out. The old test was "row >= width", i.e. it bounded
        // the row by the *column* count; on a lattice with more rows than
        // columns — a 2-wide by 5-deep rod grid, say — that silently swallowed
        // every click below the second row.
        if (column < minColumn || row < minRow
                || column >= minColumn + columns || row >= minRow + rows) {
            return -1;
        }
        int wanted = row * width + column;
        for (int i = 0; i < cells.count(); i++) {
            if (cells.latticeIndex(i) == wanted) {
                return i;
            }
        }
        return -1;
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        measure();
        hovered = isMouseOver(mouseX, mouseY) ? cellAt(mouseX, mouseY) : -1;

        // GuiGraphics otherwise flushes after each fill: a large core made
        // over a thousand draw calls for tiny squares on every frame.
        graphics.drawManaged(() -> {
            int width = Math.max(1, cells.latticeWidth());
            for (int i = 0; i < cells.count(); i++) {
                int index = cells.latticeIndex(i);
                int x = originX + ((index % width) - minColumn) * cellPx;
                int y = originY + ((index / width) - minRow) * cellPx;
                graphics.fill(x, y, x + cellPx - 1, y + cellPx - 1, cells.colour(i));
                if (i == marked) {
                    outline(graphics, x, y, 0xFFFFC24A);
                }
                if (i == selected) {
                    outline(graphics, x, y, 0xFFFFFFFF);
                } else if (i == hovered) {
                    outline(graphics, x, y, 0xFF9FD2FF);
                }
            }
        });
    }

    private void outline(GuiGraphics graphics, int x, int y, int colour) {
        int size = cellPx - 1;
        graphics.fill(x, y, x + size, y + 1, colour);
        graphics.fill(x, y + size - 1, x + size, y + size, colour);
        graphics.fill(x, y, x + 1, y + size, colour);
        graphics.fill(x + size - 1, y, x + size, y + size, colour);
    }

    /** Tooltip for whatever is under the cursor. Empty when nothing is. */
    public List<Component> hoverTooltip() {
        return hovered >= 0 ? cells.tooltip(hovered) : List.of();
    }

    @Override
    public void onClick(double mouseX, double mouseY) {
        int cell = cellAt(mouseX, mouseY);
        if (cell >= 0) {
            selected = cell;
            if (onSelect != null) {
                onSelect.accept(cell);
            }
        }
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE, Component.translatable("gui.bwr.core_map"));
    }
}
