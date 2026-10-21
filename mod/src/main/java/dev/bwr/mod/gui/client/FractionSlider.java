package dev.bwr.mod.gui.client;

import dev.bwr.mod.gui.client.style.BwrStyle;
import dev.bwr.mod.gui.client.style.Ui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

import java.util.Locale;
import java.util.function.DoubleConsumer;

/**
 * A continuously variable 0-100% slider.
 *
 * <p>Continuous rather than notched because the hardware is: a recirculation
 * pump and an RHR heat exchanger both take any duty you ask for. The value it
 * carries is a <i>demand</i> — the pump ramps to it through its own first-order
 * lag, exactly as it would if the number had arrived from Lua, so dragging this
 * and calling {@code setSpeed()} from a computer produce the same behaviour.
 *
 * <p>{@link #active} false greys it out and stops it responding, which is how
 * the screen shows that a computer owns the demand.
 *
 * <h2>Keyboard</h2>
 * When focused, Left and Right change the value by 1 % (10 % with Shift), Page Up and Page Down by 10 %, Home and End
 * go to 0 and 100 %. They send the same demand the mouse does, through the same threshold. (Vanilla's slider needs
 * Enter first and moves one pixel's worth of travel per press, 0.36 % on a 280 px slider.)
 */
public class FractionSlider extends AbstractSliderButton {

    /** Smallest change worth a packet, so a drag does not send twenty a tick. */
    private static final double SEND_THRESHOLD = 0.005;

    private final String label;
    private final DoubleConsumer onApply;
    private double lastSent;
    private boolean suppress;

    public FractionSlider(String label, String tooltip, double initial, DoubleConsumer onApply) {
        super(0, 0, 160, BwrStyle.CONTROL, Component.empty(), clamp(initial));
        this.label = label;
        this.onApply = onApply;
        this.lastSent = clamp(initial);
        setTooltip(Tooltip.create(Component.literal(tooltip)));
        updateMessage();
    }

    public double fraction() {
        return value;
    }

    /**
     * Follow the server's value without sending it back. Used while a computer
     * holds control, so the greyed-out slider still shows what Lua commanded.
     */
    public void follow(double fraction) {
        if (Math.abs(value - clamp(fraction)) < 1.0e-6) {
            return;
        }
        suppress = true;
        value = clamp(fraction);
        lastSent = value;
        updateMessage();
        suppress = false;
    }

    @Override
    protected void updateMessage() {
        setMessage(Component.literal(
                String.format(Locale.ROOT, "%s %.0f%%", label, value * 100.0)));
    }

    @Override
    protected void applyValue() {
        updateMessage();
        if (suppress) {
            return;
        }
        if (Math.abs(value - lastSent) >= SEND_THRESHOLD || value == 0.0 || value == 1.0) {
            lastSent = value;
            onApply.accept(value);
        }
    }

    @Override
    public void onRelease(double mouseX, double mouseY) {
        super.onRelease(mouseX, mouseY);
        // Always send the final position, so a small nudge is not swallowed by
        // the packet threshold and the slider never lies about the demand.
        lastSent = value;
        onApply.accept(value);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!active || !isFocused()) {
            return false;
        }
        double fine = (modifiers & 1) != 0 ? 0.10 : 0.01;
        switch (keyCode) {
            case 263 -> nudge(-fine);
            case 262 -> nudge(fine);
            case 266 -> nudge(0.10);
            case 267 -> nudge(-0.10);
            case 268 -> set(0.0);
            case 269 -> set(1.0);
            default -> {
                return false;
            }
        }
        return true;
    }

    private void nudge(double delta) {
        set(value + delta);
    }

    private void set(double target) {
        double next = clamp(target);
        if (next == value) {
            return;
        }
        value = next;
        applyValue();
    }

    /**
     * Two rows: the label at the left and the value at the right on top, the track below. The knob therefore never
     * crosses the text, so the text keeps the contrast of the surface it sits on.
     */
    @Override
    public void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int x = getX();
        int y = getY();
        int w = getWidth();
        int h = getHeight();
        boolean hover = isHovered() && active;
        int textColour = active ? BwrStyle.TEXT_PRIMARY : BwrStyle.TEXT_DISABLED;
        String percent = String.format(Locale.ROOT, "%.0f%%", value * 100.0);
        int percentW = Ui.width(percent, BwrStyle.Type.BODY);
        Ui.text(g, Ui.fit(label, BwrStyle.Type.BODY, w - percentW - BwrStyle.SP2), x, y, BwrStyle.Type.BODY,
                active ? BwrStyle.TEXT_SECONDARY : BwrStyle.TEXT_DISABLED);
        Ui.textRight(g, percent, x + w, y, BwrStyle.Type.BODY, textColour);

        int trackH = BwrStyle.METER;
        int ty = y + h - trackH;
        g.fill(x, ty, x + w, ty + trackH, active ? BwrStyle.INSET : BwrStyle.SURFACE);
        int knob = 4;
        int travel = w - 2 - knob;
        int filled = (int) Math.round(value * travel);
        g.fill(x + 1, ty + 1, x + 1 + filled + knob / 2, ty + trackH - 1, active ? BwrStyle.PRIMARY : BwrStyle.RAISED);
        Ui.frame(g, x, ty, w, trackH, hover ? BwrStyle.ACCENT : BwrStyle.BORDER_STRONG);
        int handleX = x + 1 + filled;
        g.fill(handleX, ty - 2, handleX + knob, ty + trackH, active ? BwrStyle.FOCUS : BwrStyle.TEXT_DISABLED);
        if (isFocused()) {
            Ui.focusRing(g, x, y, w, h);
        }
    }

    private static double clamp(double value) {
        return value < 0.0 ? 0.0 : Math.min(1.0, value);
    }
}
