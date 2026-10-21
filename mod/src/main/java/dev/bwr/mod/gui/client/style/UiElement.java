package dev.bwr.mod.gui.client.style;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * One piece of a screen's layout. A screen is a tree of these (columns, cards, rows, meters, control rows); the base
 * screen measures the tree, places it inside the panel, draws it and asks it for tooltips and narration. That is what
 * keeps alignment, spacing, truncation and keyboard order the same on every screen: no screen does its own pixel
 * arithmetic.
 *
 * <p>The cycle is {@link #naturalHeight natural height at a width} (measure), {@link #place place at x, y, width and
 * an optional forced height} (position children and widgets) and {@link #render render}. Elements read the menu through
 * suppliers, so a row's label, value and status are looked up every frame and an element can show or hide itself
 * ({@link #showIf}) with the machine's state.
 */
public abstract class UiElement {
    protected int x;
    protected int y;
    protected int w;
    protected int h;
    private BooleanSupplier shown = () -> true;
    private Supplier<List<Component>> tip;

    /** Show this element only while {@code condition} holds. */
    @SuppressWarnings("unchecked")
    public <E extends UiElement> E showIf(BooleanSupplier condition) {
        this.shown = condition;
        return (E) this;
    }

    /** A tooltip for anywhere on this element. */
    @SuppressWarnings("unchecked")
    public <E extends UiElement> E tip(Supplier<List<Component>> tooltip) {
        this.tip = tooltip;
        return (E) this;
    }

    public <E extends UiElement> E tip(String text) {
        List<Component> lines = List.of(Component.literal(text));
        return tip(() -> lines);
    }

    public final boolean isVisible() {
        return shown.getAsBoolean();
    }

    /** Height this element wants at {@code width}; 0 while hidden. */
    public final int naturalHeight(int width) {
        return isVisible() ? measure(width) : 0;
    }

    protected abstract int measure(int width);

    /**
     * Positions the element. {@code forcedHeight} below zero means "your natural height"; a container passes a height
     * when it stretches a child (cards in a row are as tall as the tallest).
     */
    public final void place(int x, int y, int width, int forcedHeight) {
        this.x = x;
        this.y = y;
        if (!isVisible()) {
            this.w = 0;
            this.h = 0;
            hide();
            return;
        }
        this.w = width;
        this.h = forcedHeight >= 0 ? forcedHeight : measure(width);
        layout();
    }

    /** Hides the element and everything inside it, whatever their own conditions say. */
    public final void hideTree() {
        this.w = 0;
        this.h = 0;
        hide();
    }

    /** Positions children and widgets; the rectangle is already set. */
    protected void layout() {
    }

    /** Called instead of {@link #layout} while hidden, so widgets inside stop being drawn and focused. */
    protected void hide() {
    }

    /** Draws the element. Only called while visible. */
    public abstract void render(GuiGraphics graphics, int mouseX, int mouseY);

    /** Adds this element's widgets, in tab order. */
    public void collectWidgets(Consumer<AbstractWidget> out) {
    }

    /** The width this element would like when it is laid out right-aligned (title bar items); defaults to all of it. */
    public int preferredWidth(int available) {
        return available;
    }

    /** True when the layout may give this element the height left over in its column. */
    public boolean flexible() {
        return false;
    }

    public final boolean contains(int mouseX, int mouseY) {
        return isVisible() && w > 0 && mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }

    /** The tooltip for the pointer at {@code (mouseX, mouseY)}: the deepest element that has one. */
    public List<Component> tooltipAt(int mouseX, int mouseY) {
        if (!contains(mouseX, mouseY)) {
            return List.of();
        }
        return tip == null ? List.of() : tip.get();
    }

    /** Mouse wheel over {@code (mouseX, mouseY)}: scrolls a scroll area under the pointer; true if one did. */
    public boolean scroll(double mouseX, double mouseY, double delta) {
        return false;
    }

    /** Page Up (-1) or Page Down (1) from the keyboard: scrolls the first scroll area that can move; true if one did. */
    public boolean page(int direction) {
        return false;
    }

    /** Adds what a screen reader should say about this element (nothing for decoration). */
    public void narrate(List<String> out) {
    }

    // accessors for containers and the base screen
    public int x() {
        return x;
    }

    public int y() {
        return y;
    }

    public int width() {
        return w;
    }

    public int height() {
        return h;
    }
}
