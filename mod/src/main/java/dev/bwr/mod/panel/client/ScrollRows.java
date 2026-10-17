package dev.bwr.mod.panel.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;

import java.util.ArrayList;
import java.util.List;

/**
 * Scrolling for the rows of a {@link ConfigLayout} screen whose rows do not all fit (a gauge's wrench screen has well
 * over a dozen, with its look selectors at the bottom). Each row widget is registered with its unscrolled position;
 * the rows move together, a row that is not wholly inside the viewport is hidden (so it can be neither seen nor
 * clicked), and a thin scrollbar shows where the view is. The mouse wheel over a selector still turns the selector
 * (vanilla); anywhere else it scrolls the rows. Done and Cancel are not rows: they stay put below the viewport.
 */
final class ScrollRows {
    private final ConfigLayout layout;
    private final List<AbstractWidget> widgets = new ArrayList<>();
    private final List<Integer> baseY = new ArrayList<>();
    private int scroll;

    ScrollRows(ConfigLayout layout) {
        this.layout = layout;
    }

    /** Registers a row widget at its unscrolled position; returns it for chaining. */
    <T extends AbstractWidget> T add(T widget) {
        widgets.add(widget);
        baseY.add(widget.getY());
        place(widgets.size() - 1);
        return widget;
    }

    /** Where row {@code row} is drawn now. */
    int y(int row) {
        return layout.rowY(row) - scroll;
    }

    /** Whether row {@code row} is wholly inside the viewport (its label is drawn). */
    boolean visible(int row) {
        int y = y(row);
        return y >= layout.viewTop - 1 && y + layout.rowHeight <= layout.viewBottom + 1;
    }

    /** Scrolls by the wheel; returns whether it moved. */
    boolean scroll(double wheel) {
        if (!layout.scrollable || wheel == 0.0) {
            return false;
        }
        int next = Math.max(0, Math.min(layout.maxScroll, scroll - (int) Math.round(wheel * layout.rowHeight)));
        if (next == scroll) {
            return false;
        }
        scroll = next;
        for (int i = 0; i < widgets.size(); i++) {
            place(i);
        }
        return true;
    }

    private void place(int index) {
        AbstractWidget widget = widgets.get(index);
        int y = baseY.get(index) - scroll;
        widget.setY(y);
        widget.visible = y >= layout.viewTop - 1 && y + widget.getHeight() <= layout.viewBottom + 1;
    }

    /** The scrollbar at the right edge of the panel, when the rows overflow. */
    void renderScrollbar(GuiGraphics graphics) {
        if (!layout.scrollable) {
            return;
        }
        int x = layout.left + layout.width + 2;
        int top = layout.viewTop;
        int height = layout.viewBottom - layout.viewTop;
        graphics.fill(x, top, x + 3, top + height, 0x60FFFFFF);
        int content = height + layout.maxScroll;
        int thumb = Math.max(12, height * height / content);
        int thumbTop = top + (int) ((long) (height - thumb) * scroll / Math.max(1, layout.maxScroll));
        graphics.fill(x, thumbTop, x + 3, thumbTop + thumb, 0xFFDDDDDD);
    }
}
