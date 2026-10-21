package dev.bwr.mod.gui.client;

import dev.bwr.mod.gui.client.style.BwrStyle;
import dev.bwr.mod.gui.client.style.Ui;
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
 *
 * <h2>Keyboard</h2>
 * Focused (Tab), the arrow keys move a cursor along the lattice, Enter or Space select the cursor cell exactly as a click
 * does, plus and minus zoom and Home fits the view. The cursor cell is outlined and its tooltip text is shown and narrated,
 * so the map is usable without a mouse. The widget draws its own inset well and a focus ring.
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
    private int cursor = -1;
    private boolean multiSelect, zoomable, panning;
    /** While a left drag paints the selection: true adds the cells passed over, false removes them; null otherwise. */
    private Boolean paintAdd;
    private int lastPainted = -1;
    private final java.util.LinkedHashSet<Integer> selection = new java.util.LinkedHashSet<>();
    private int zoomSteps;
    private double panX, panY;

    public void enableRefuellingControls() { multiSelect = zoomable = true; }
    public int[] selectedCells() { return selection.stream().mapToInt(Integer::intValue).toArray(); }
    public void clearSelection() { selection.clear(); selected = -1; }
    public void resetView() { zoomSteps = 0; panX = panY = 0; measure(); }

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
        selection.removeIf(i -> i < 0 || i >= this.cells.count());
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
        selection.clear(); if (cell >= 0) selection.add(cell);
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
        cellPx = Math.min(24, Math.max(MIN_CELL, Math.min(MAX_CELL, fit)) + zoomSteps * 2);
        double limitX = Math.max(0, (columns * cellPx - getWidth()) / 2.0);
        double limitY = Math.max(0, (rows * cellPx - getHeight()) / 2.0);
        panX = Math.clamp(panX, -limitX, limitX); panY = Math.clamp(panY, -limitY, limitY);
        originX = (int)Math.round(getX() + (getWidth() - columns * cellPx) / 2.0 + panX);
        originY = (int)Math.round(getY() + (getHeight() - rows * cellPx) / 2.0 + panY);
    }

    /** Screen centre of a cell and the cell size, as last laid out (for the GUI client check). */
    public double[] cellCentre(int cell) {
        measure();
        int width = Math.max(1, cells.latticeWidth());
        int index = cells.latticeIndex(cell);
        return new double[] {originX + (index % width - minColumn + 0.5) * cellPx, originY + (index / width - minRow + 0.5) * cellPx, cellPx};
    }

    /** The cell under a screen point, or -1 (for the GUI client check). */
    public int cellAtPoint(double x, double y) {
        measure();
        return cellAt(x, y);
    }

    private int cellAt(double mouseX, double mouseY) {
        if (!isMouseOver(mouseX, mouseY) || cells.count() == 0) return -1;
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
        if (cursor >= cells.count()) {
            cursor = -1;
        }
        Ui.well(graphics, getX(), getY(), getWidth(), getHeight());

        // GuiGraphics otherwise flushes after each fill: a large core made
        // over a thousand draw calls for tiny squares on every frame.
        graphics.enableScissor(getX() + 1, getY() + 1, getX() + getWidth() - 1, getY() + getHeight() - 1);
        graphics.drawManaged(() -> {
            int width = Math.max(1, cells.latticeWidth());
            for (int i = 0; i < cells.count(); i++) {
                int index = cells.latticeIndex(i);
                int x = originX + ((index % width) - minColumn) * cellPx;
                int y = originY + ((index / width) - minRow) * cellPx;
                if (x + cellPx <= getX() || x >= getX() + getWidth() || y + cellPx <= getY() || y >= getY() + getHeight()) continue;
                graphics.fill(x, y, x + cellPx - 1, y + cellPx - 1, cells.colour(i));
                if (i == marked) {
                    outline(graphics, x, y, 0xFFFFC24A);
                }
                if (multiSelect ? selection.contains(i) : i == selected) {
                    outline(graphics, x, y, 0xFFFFFFFF);
                } else if (i == hovered) {
                    outline(graphics, x, y, 0xFF9FD2FF);
                }
                if (i == cursor && isFocused()) {
                    outline(graphics, x, y, BwrStyle.ACCENT);
                    if (cellPx > 4) {
                        graphics.fill(x + 1, y + 1, x + 2, y + 2, 0xFF000000);
                        graphics.fill(x + cellPx - 3, y + cellPx - 3, x + cellPx - 2, y + cellPx - 2, 0xFF000000);
                    }
                }
            }
        });
        graphics.disableScissor();
        if (isFocused()) {
            Ui.focusRing(graphics, getX(), getY(), getWidth(), getHeight());
        }
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
        if (hovered >= 0) {
            return cells.tooltip(hovered);
        }
        return isFocused() && cursor >= 0 && cursor < cells.count() ? cells.tooltip(cursor) : List.of();
    }

    @Override
    public void onClick(double mouseX, double mouseY) {
        measure();
        int cell = cellAt(mouseX, mouseY);
        if (cell >= 0) {
            cursor = cell;
        }
        activate(cell);
        if (multiSelect && cell >= 0) {
            paintAdd = selection.contains(cell); // the press decides whether the drag adds or removes
            lastPainted = cell;
        }
    }

    /** A left drag over a refuelling map: every cell passed over joins (or leaves) the selection, in drag order. */
    private boolean paint(double x, double y) {
        measure();
        int cell = cellAt(x, y);
        if (cell >= 0 && cell != lastPainted) {
            lastPainted = cell;
            cursor = cell;
            if (paintAdd) {
                selection.add(cell);
                selected = cell;
            } else {
                selection.remove(cell);
                selected = selection.isEmpty() ? -1 : selection.getLast();
            }
            if (onSelect != null) {
                onSelect.accept(cell);
            }
        }
        return true;
    }

    /** Selects (or, on a refuelling map, toggles) a cell: what a click on it does. */
    private void activate(int cell) {
        if (cell >= 0) {
            selected = cell;
            if (multiSelect && !selection.add(cell)) {
                selection.remove(cell);
                selected = selection.isEmpty() ? -1 : selection.getLast();
            }
            if (onSelect != null) {
                onSelect.accept(cell);
            }
        }
    }

    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (!zoomable || !isMouseOver(x,y) || vertical == 0 || !Double.isFinite(vertical)) return false;
        measure(); double col=(x-originX)/cellPx, row=(y-originY)/cellPx;
        zoomSteps=Math.clamp(zoomSteps+(vertical>0?1:-1),0,11);
        measure();
        panX += x - (originX + col*cellPx); panY += y - (originY + row*cellPx);
        measure(); return true;
    }
    @Override public boolean mouseClicked(double x,double y,int button) {
        if (zoomable && button==1 && isMouseOver(x,y)) {panning=true;return true;}
        return super.mouseClicked(x,y,button);
    }
    @Override public boolean mouseDragged(double x,double y,int button,double dx,double dy) {
        if (panning && button==1) {panX+=dx;panY+=dy;measure();return true;}
        if (button == 0 && paintAdd != null) return paint(x, y);
        return super.mouseDragged(x,y,button,dx,dy);
    }
    @Override public boolean mouseReleased(double x,double y,int button) {
        if(button==1 && panning){panning=false;return true;}
        if (button == 0 && paintAdd != null) { paintAdd = null; lastPainted = -1; return true; }
        return super.mouseReleased(x,y,button);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE, Component.translatable("gui.bwr.core_map"));
        List<Component> lines = hoverTooltip();
        if (!lines.isEmpty()) {
            StringBuilder text = new StringBuilder();
            for (Component line : lines) {
                text.append(text.length() == 0 ? "" : ". ").append(line.getString());
            }
            output.add(NarratedElementType.HINT, Component.literal(text.toString()));
        }
        if (isFocused()) {
            output.add(NarratedElementType.USAGE, Component.literal(
                    "Arrow keys move over the map, Enter or Space select, plus and minus zoom, Home fits"));
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!isFocused() || cells.count() == 0) {
            return false;
        }
        switch (keyCode) {
            case 263 -> moveCursor(-1, 0);
            case 262 -> moveCursor(1, 0);
            case 265 -> moveCursor(0, -1);
            case 264 -> moveCursor(0, 1);
            case 257, 335, 32 -> {
                if (cursor < 0) {
                    cursor = nearestToCentre();
                }
                activate(cursor);
            }
            case 61, 334 -> zoomBy(1);
            case 45, 333 -> zoomBy(-1);
            case 268 -> resetView();
            default -> {
                return false;
            }
        }
        return true;
    }

    private int nearestToCentre() {
        measure();
        int width = Math.max(1, cells.latticeWidth());
        double cx = minColumn + columns / 2.0;
        double cy = minRow + rows / 2.0;
        int best = 0;
        double bestDistance = Double.MAX_VALUE;
        for (int i = 0; i < cells.count(); i++) {
            int index = cells.latticeIndex(i);
            double d = Math.hypot(index % width - cx, index / width - cy);
            if (d < bestDistance) {
                bestDistance = d;
                best = i;
            }
        }
        return best;
    }

    /** Moves the keyboard cursor one lattice step in a direction, skipping over gaps in a round core. */
    private void moveCursor(int dx, int dy) {
        if (cursor < 0 || cursor >= cells.count()) {
            cursor = nearestToCentre();
            return;
        }
        int width = Math.max(1, cells.latticeWidth());
        int index = cells.latticeIndex(cursor);
        int column = index % width;
        int row = index / width;
        for (int step = 1; step <= Math.max(columns, rows); step++) {
            int c = column + dx * step;
            int r = row + dy * step;
            if (c < minColumn || r < minRow || c >= minColumn + columns || r >= minRow + rows) {
                return;
            }
            int wanted = r * width + c;
            for (int i = 0; i < cells.count(); i++) {
                if (cells.latticeIndex(i) == wanted) {
                    cursor = i;
                    revealCursor();
                    return;
                }
            }
        }
    }

    private void zoomBy(int steps) {
        if (!zoomable) {
            return;
        }
        zoomSteps = Math.clamp(zoomSteps + steps, 0, 11);
        measure();
        revealCursor();
    }

    /** Pans a zoomed map so the cursor cell is inside the view. */
    private void revealCursor() {
        measure();
        if (cursor < 0 || cursor >= cells.count()) {
            return;
        }
        int width = Math.max(1, cells.latticeWidth());
        int index = cells.latticeIndex(cursor);
        int x = originX + (index % width - minColumn) * cellPx;
        int y = originY + (index / width - minRow) * cellPx;
        if (x < getX()) {
            panX += getX() - x;
        } else if (x + cellPx > getX() + getWidth()) {
            panX -= x + cellPx - (getX() + getWidth());
        }
        if (y < getY()) {
            panY += getY() - y;
        } else if (y + cellPx > getY() + getHeight()) {
            panY -= y + cellPx - (getY() + getHeight());
        }
        measure();
    }
}
