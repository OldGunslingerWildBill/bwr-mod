package dev.bwr.mod.gui.client.style;

import net.minecraft.client.gui.components.AbstractWidget;

/** A widget that wants to decide for itself how it fills the rectangle a layout gives it. */
public interface Placeable {
    void placeAt(int x, int y, int width, int height);

    /** Places any widget: its own way if it is {@link Placeable}, otherwise by its bounds. */
    static void place(AbstractWidget widget, int x, int y, int width, int height) {
        if (widget instanceof Placeable p) {
            p.placeAt(x, y, width, height);
        } else {
            widget.setX(x);
            widget.setY(y);
            widget.setWidth(width);
            widget.setHeight(height);
        }
    }
}
