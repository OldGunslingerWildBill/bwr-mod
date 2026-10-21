package dev.bwr.mod.gui.client.style;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

import java.util.Objects;

/**
 * The style's button: primary (the one action a panel is for), secondary (everything else) and danger (an action
 * that cannot be taken back, such as a scram). It is still a vanilla {@link Button}, so the keyboard (Tab, Enter and
 * Space), narration and click sound work as everywhere else; what changes is the look of the four states:
 * <ul>
 *   <li>hover: a lighter fill and an accent border;</li>
 *   <li>pressed: a darker fill and the label one pixel down, for as long as the mouse button is held;</li>
 *   <li>focus: a white ring one pixel outside the control, visible on every surface;</li>
 *   <li>disabled: the panel's surface colour, no border emphasis and disabled text.</li>
 * </ul>
 * Every button has a tooltip (the constructor insists), shown on hover and on keyboard focus; a label that does not
 * fit is cut with an ellipsis and the tooltip then starts with the whole label.
 */
public final class BwrButton extends Button {

    public enum Variant {
        PRIMARY, SECONDARY, DANGER
    }

    /** Room kept at the sides of the label: 3 px each, so a six-letter label fits a 42 px button. */
    private static final int FACE_PAD = 6;

    private final Variant variant;
    private String tooltipText;
    private boolean pressed;
    private String tooltipFor = "";

    public BwrButton(Variant variant, Component label, Component tooltip, Button.OnPress onPress) {
        super(0, 0, 80, BwrStyle.CONTROL, label, onPress, DEFAULT_NARRATION);
        this.variant = variant;
        this.tooltipText = tooltip.getString();
        refreshTooltip();
    }

    public static BwrButton primary(String label, String tooltip, Button.OnPress onPress) {
        return new BwrButton(Variant.PRIMARY, Component.literal(label), Component.literal(tooltip), onPress);
    }

    public static BwrButton secondary(String label, String tooltip, Button.OnPress onPress) {
        return new BwrButton(Variant.SECONDARY, Component.literal(label), Component.literal(tooltip), onPress);
    }

    public static BwrButton danger(String label, String tooltip, Button.OnPress onPress) {
        return new BwrButton(Variant.DANGER, Component.literal(label), Component.literal(tooltip), onPress);
    }

    public Variant variant() {
        return variant;
    }

    /** Replaces the explanation shown on hover and focus (for a button whose action changes with the machine's state). */
    public void setTooltipText(String text) {
        if (!text.equals(tooltipText)) {
            tooltipText = text;
            refreshTooltip();
        }
    }

    @Override
    public void setMessage(Component message) {
        if (Objects.equals(message, getMessage())) {
            return;
        }
        super.setMessage(message);
        refreshTooltip();
    }

    @Override
    public void setWidth(int width) {
        super.setWidth(width);
        refreshTooltip();
    }

    /** The tooltip carries the whole label when the face had to cut it. */
    private void refreshTooltip() {
        if (tooltipText == null) {
            return;
        }
        String label = Ui.sentence(getMessage().getString());
        boolean cut = Ui.overflows(label, BwrStyle.Type.BODY, getWidth() - FACE_PAD);
        String wanted = cut ? label + ": " + tooltipText : tooltipText;
        if (!wanted.equals(tooltipFor)) {
            tooltipFor = wanted;
            setTooltip(Tooltip.create(Component.literal(wanted), getMessage()));
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onClick(double mouseX, double mouseY) {
        pressed = true;
        super.onClick(mouseX, mouseY);
    }

    @Override
    public void onRelease(double mouseX, double mouseY) {
        pressed = false;
        super.onRelease(mouseX, mouseY);
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int x = getX();
        int y = getY();
        int w = getWidth();
        int h = getHeight();
        boolean hover = isHovered() && active;
        boolean down = pressed && hover;

        int fill;
        int border;
        int text;
        if (!active) {
            fill = BwrStyle.SURFACE;
            border = BwrStyle.BORDER;
            text = BwrStyle.TEXT_DISABLED;
        } else {
            switch (variant) {
                case PRIMARY -> {
                    fill = down ? BwrStyle.PRIMARY_PRESSED : hover ? BwrStyle.PRIMARY_HOVER : BwrStyle.PRIMARY;
                    border = BwrStyle.PRIMARY_BORDER;
                    text = BwrStyle.ON_FILL;
                }
                case DANGER -> {
                    fill = down ? BwrStyle.DANGER_PRESSED : hover ? BwrStyle.DANGER_HOVER : BwrStyle.DANGER;
                    border = BwrStyle.DANGER_BORDER;
                    text = BwrStyle.ON_FILL;
                }
                default -> {
                    fill = down ? BwrStyle.RAISED_PRESSED : hover ? BwrStyle.RAISED_HOVER : BwrStyle.RAISED;
                    border = hover ? BwrStyle.ACCENT : BwrStyle.BORDER_STRONG;
                    text = BwrStyle.TEXT_PRIMARY;
                }
            }
        }
        g.fill(x, y, x + w, y + h, fill);
        Ui.frame(g, x, y, w, h, border);
        if (active && !down) {
            // a one pixel top highlight gives the raised look without a texture
            g.fill(x + 1, y + 1, x + w - 1, y + 2, 0x18FFFFFF);
        }
        String label = Ui.fit(Ui.sentence(getMessage().getString()), BwrStyle.Type.BODY, w - FACE_PAD);
        int ty = y + (h - 8) / 2 + (down ? 1 : 0);
        Ui.textCentred(g, label, x + w / 2, ty, BwrStyle.Type.BODY, text);
        if (isFocused()) {
            Ui.focusRing(g, x, y, w, h);
        }
    }
}
