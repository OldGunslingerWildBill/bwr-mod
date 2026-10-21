package dev.bwr.mod.gui.client.style;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;

/**
 * One tab of a {@link Layout.Tabs} strip. The selected tab is the raised one: a card-coloured fill that continues into
 * the content below, an accent bar on top and bright text; the others are recessed. Selection is therefore shown by
 * shape and brightness, not by colour alone, and a selected tab is announced as such to a screen reader.
 *
 * <p>Left and Right move to the neighbouring tab (selecting it), as in every tab strip; Enter and Space select the
 * focused one.
 */
public final class BwrTab extends Button {
    private final BooleanSupplier selected;
    private final IntConsumer moveBy;

    public BwrTab(String label, String tooltip, BooleanSupplier selected, Button.OnPress onPress, IntConsumer moveBy) {
        super(0, 0, 60, BwrStyle.TAB, Component.literal(label), onPress, DEFAULT_NARRATION);
        this.selected = selected;
        this.moveBy = moveBy;
        setTooltip(Tooltip.create(Component.literal(tooltip)));
    }

    public boolean isSelected() {
        return selected.getAsBoolean();
    }

    @Override
    protected net.minecraft.network.chat.MutableComponent createNarrationMessage() {
        Component name = getMessage();
        return Component.literal("Tab " + name.getString() + (isSelected() ? ", selected" : ""));
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (isFocused() && (keyCode == 263 || keyCode == 262)) {
            moveBy.accept(keyCode == 263 ? -1 : 1);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int x = getX();
        int y = getY();
        int w = getWidth();
        int h = getHeight();
        boolean on = isSelected();
        boolean hover = isHovered() && active;
        int fill = on ? BwrStyle.SURFACE : hover ? BwrStyle.RAISED_HOVER : BwrStyle.PANEL;
        g.fill(x, y, x + w, y + h, fill);
        Ui.frame(g, x, y, w, h, on ? BwrStyle.BORDER_STRONG : BwrStyle.BORDER);
        if (on) {
            g.fill(x + 1, y + 1, x + w - 1, y + 3, BwrStyle.ACCENT);
            // the selected tab joins the content: no line under it
            g.fill(x + 1, y + h - 1, x + w - 1, y + h, BwrStyle.SURFACE);
        }
        String label = Ui.fit(getMessage().getString(), BwrStyle.Type.BODY, w - BwrStyle.SP2);
        int colour = !active ? BwrStyle.TEXT_DISABLED : on ? BwrStyle.TEXT_PRIMARY : BwrStyle.TEXT_SECONDARY;
        Ui.textCentred(g, label, x + w / 2, y + (h - 8) / 2 + (on ? 1 : 0), BwrStyle.Type.BODY, colour);
        if (isFocused()) {
            Ui.focusRing(g, x, y, w, h);
        }
    }

    /** Focuses {@code other} on the open screen (used when an arrow key moves between tabs). */
    static void focus(BwrTab other) {
        var screen = Minecraft.getInstance().screen;
        if (screen != null) {
            screen.setFocused(other);
        }
    }
}
