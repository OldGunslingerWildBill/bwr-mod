package dev.bwr.mod.panel.client;

import dev.bwr.mod.panel.component.ComponentItem;
import dev.bwr.mod.panel.component.ComponentType;
import dev.bwr.mod.panel.component.Footprint;
import dev.bwr.mod.panel.net.PanelPayloads;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Locale;

/**
 * The size picker, opened with Alt+click while holding a component item: every size of the item's type from the
 * smallest to the biggest, one row each, with the size in cells, what it measures on a real board and a small
 * outline of the footprint (all to one scale, so they can be compared; the smallest are drawn at least 3 pixels across). The current choice is marked. A click
 * sends {@link PanelPayloads.SetSize}, which the server validates like the cycling, and closes the screen. The rows
 * scroll with the wheel and a scrollbar ({@link ScrollRows}), like the wrench screens.
 */
public final class SizePickerScreen extends Screen {
    /** One cell is 1/16 block, so 6.25 cm. */
    private static final double CELL_CM = 100.0 / 16.0;
    /** Longest side of an outline, pixels, for the biggest size of the item; the others are drawn to the same scale. */
    private static final int PREVIEW_MAX = 20;
    /** The least an outline measures either way, pixels, so the real sizes (a cell or two) do not vanish. */
    private static final int PREVIEW_MIN = 3;
    private static final int PREVIEW_WIDTH = 24;

    private final ComponentItem item;
    private final ComponentType type;
    private final int current;
    private final boolean offHand;
    private final int[] sizes;
    private ConfigLayout layout;
    private ScrollRows rows;
    private double pixelsPerCell;

    public SizePickerScreen(ComponentItem item, int current, boolean offHand) {
        super(Component.translatable("screen.bwr.panel_size"));
        this.item = item;
        this.type = item.type();
        this.current = current;
        this.offHand = offHand;
        this.sizes = item.sizes();
    }

    /** "12.5 cm" / "1.5 m": a length in centimetres as the picker prints it. */
    static String length(double cm) {
        if (cm >= 100.0) {
            double metres = cm / 100.0;
            return String.format(Locale.ROOT, metres == Math.rint(metres) ? "%.0f m" : "%.2f m", metres);
        }
        return String.format(Locale.ROOT, cm == Math.rint(cm) ? "%.0f cm" : "%.1f cm", cm);
    }

    /** The real size of a footprint, "width x height". */
    static String realSize(Footprint footprint) {
        return length(footprint.w() * CELL_CM) + " x " + length(footprint.h() * CELL_CM);
    }

    @Override
    protected void init() {
        layout = new ConfigLayout(width, height, sizes.length);
        rows = new ScrollRows(layout);
        int biggest = 1;
        for (int index : sizes) {
            Footprint footprint = type.footprints().get(index);
            biggest = Math.max(biggest, Math.max(footprint.w(), footprint.h()));
        }
        pixelsPerCell = (double) PREVIEW_MAX / biggest;
        int selected = 0;
        for (int row = 0; row < sizes.length; row++) {
            int index = sizes[row];
            if (index == current) {
                selected = row;
            }
            Footprint footprint = type.footprints().get(index);
            Component label = Component.translatable(index == current ? "screen.bwr.panel_size.row_current"
                    : "screen.bwr.panel_size.row", footprint.w(), footprint.h(), realSize(footprint));
            rows.add(addRenderableWidget(Button.builder(label, button -> {
                PacketDistributor.sendToServer(new PanelPayloads.SetSize(offHand, index));
                onClose();
            }).bounds(layout.left + PREVIEW_WIDTH + 4, layout.rowY(row), layout.width - PREVIEW_WIDTH - 4,
                    layout.rowHeight - 2).build()));
        }
        // Bring the current size into view (the list starts at its top).
        if (selected > 0) {
            rows.scroll(-Math.max(0, selected - 1));
        }
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), button -> onClose())
                .bounds(layout.left, layout.buttonY, layout.width, 20).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partial) {
        renderBackground(graphics, mouseX, mouseY, partial);
        graphics.fill(layout.left - 6, layout.top - 4, layout.left + layout.width + 6, layout.panelBottom + 2, 0xC0101010);
        graphics.drawString(font, Component.translatable("screen.bwr.panel_size.title",
                Component.translatable(type.translationKey())), layout.left, layout.titleY(), 0xFFFFFF);
        // Screen.render would blur the background again; draw the widgets ourselves.
        for (Renderable widget : renderables) {
            widget.render(graphics, mouseX, mouseY, partial);
        }
        for (int row = 0; row < sizes.length; row++) {
            int top = rows.y(row);
            // The button is two pixels shorter than the row, and is shown while it is wholly inside the viewport.
            if (top < layout.viewTop - 1 || top + layout.rowHeight - 2 > layout.viewBottom + 1) {
                continue;
            }
            Footprint footprint = type.footprints().get(sizes[row]);
            int pw = Math.max(PREVIEW_MIN, (int) Math.round(footprint.w() * pixelsPerCell));
            int ph = Math.max(PREVIEW_MIN, (int) Math.round(footprint.h() * pixelsPerCell));
            int x = layout.left + (PREVIEW_WIDTH - pw) / 2;
            int y = rows.y(row) + (layout.rowHeight - 2 - ph) / 2;
            int colour = sizes[row] == current ? 0xFF7CFF7C : 0xFFB8C4D0;
            graphics.fill(x, y, x + pw, y + ph, colour);
            graphics.fill(x + 1, y + 1, x + pw - 1, y + ph - 1, 0xFF283038);
        }
        rows.renderScrollbar(graphics);
    }

    /** The wheel scrolls the rows. */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        return rows.scroll(scrollY) || super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
