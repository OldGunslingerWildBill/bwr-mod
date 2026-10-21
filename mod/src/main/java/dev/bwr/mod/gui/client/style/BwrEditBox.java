package dev.bwr.mod.gui.client.style;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

import java.util.function.Predicate;

/**
 * A text field in the style: an inset well, a focus ring, and an invalid state that shows an alarm border and icon
 * (the Apply button next to it is disabled by the screen at the same time, so the cue is not colour alone).
 *
 * <p>It is a vanilla {@link EditBox}, so editing, selection, clipboard and narration are vanilla's; the screen base
 * routes Enter to {@link #submit()} and keeps typed letters from reaching the inventory key.
 */
public final class BwrEditBox extends EditBox implements Placeable {
    private static final int INSET_X = 4;

    private final Runnable onSubmit;
    private boolean invalid;
    private boolean editable = true;

    public BwrEditBox(Font font, Component name, Component tooltip, int maxLength, Predicate<String> filter, Runnable onSubmit) {
        super(font, 0, 0, 60, BwrStyle.CONTROL, name);
        this.onSubmit = onSubmit;
        setBordered(false);
        setMaxLength(maxLength);
        setFilter(filter);
        setTextColor(BwrStyle.TEXT_PRIMARY);
        setTextColorUneditable(BwrStyle.TEXT_DISABLED);
        setTooltip(Tooltip.create(tooltip));
    }

    /** Marks the current text as one the screen will not send. */
    public void setInvalid(boolean invalid) {
        this.invalid = invalid;
    }

    public boolean isInvalid() {
        return invalid;
    }

    @Override
    public void setEditable(boolean enabled) {
        super.setEditable(enabled);
        this.editable = enabled;
    }

    /** Enter pressed: the screen's apply action. */
    public void submit() {
        onSubmit.run();
    }

    /** The frame fills the rectangle; the text sits inside it, inset on both sides. */
    @Override
    public void placeAt(int x, int y, int width, int height) {
        setX(x + INSET_X);
        setY(y);
        setWidth(Math.max(8, width - 2 * INSET_X));
        setHeight(height);
    }

    @Override
    public void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int x = getX() - INSET_X;
        int w = getWidth() + 2 * INSET_X;
        int y = getY();
        int h = getHeight();
        boolean live = active && editable;
        g.fill(x, y, x + w, y + h, live ? BwrStyle.INSET : BwrStyle.SURFACE);
        Ui.frame(g, x, y, w, h, invalid ? BwrStyle.Status.ALARM.colour : isHovered() && live ? BwrStyle.ACCENT : BwrStyle.BORDER_STRONG);
        var pose = g.pose();
        pose.pushPose();
        // Vanilla draws unframed text at the top edge of the box; centre it.
        pose.translate(0, (h - 8) / 2f, 0);
        super.renderWidget(g, mouseX, mouseY, partialTick);
        pose.popPose();
        if (invalid) {
            Ui.statusIcon(g, x + w - BwrStyle.ICON - BwrStyle.SP1, y + (h - BwrStyle.ICON) / 2, BwrStyle.Status.ALARM);
        }
        if (isFocused()) {
            Ui.focusRing(g, x, y, w, h);
        }
    }
}
