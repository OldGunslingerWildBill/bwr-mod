package dev.bwr.mod.gui.client;

import net.minecraft.client.gui.components.AbstractSliderButton;
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
 */
public class FractionSlider extends AbstractSliderButton {

    /** Smallest change worth a packet, so a drag does not send twenty a tick. */
    private static final double SEND_THRESHOLD = 0.005;

    private final String label;
    private final DoubleConsumer onApply;
    private double lastSent;
    private boolean suppress;

    public FractionSlider(int x, int y, int width, int height, String label,
                          double initial, DoubleConsumer onApply) {
        super(x, y, width, height, Component.empty(), clamp(initial));
        this.label = label;
        this.onApply = onApply;
        this.lastSent = clamp(initial);
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

    private static double clamp(double value) {
        return value < 0.0 ? 0.0 : Math.min(1.0, value);
    }
}
