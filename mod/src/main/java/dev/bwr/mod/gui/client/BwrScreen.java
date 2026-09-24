package dev.bwr.mod.gui.client;

import dev.bwr.mod.gui.BwrMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

import java.util.Locale;

/**
 * Shared chrome for the mod's screens: the panel background, a small set of
 * drawing helpers, and the colour palette.
 *
 * <h2>On colour</h2>
 * Colour here is typography, not logic. A pressure that has climbed past the
 * design limit is drawn in red so a player's eye finds it, and that is the
 * entire extent of it — no code anywhere reads a colour, and nothing in the
 * plant behaves differently because a number went orange. The thresholds these
 * helpers use are the published design figures from {@code REFERENCE-DATA.md}
 * so that the colouring means something, but they are not setpoints: nothing
 * trips at them, because nothing in this mod trips at anything.
 */
public abstract class BwrScreen<T extends BwrMenu> extends AbstractContainerScreen<T> {

    // --- palette ------------------------------------------------------
    public static final int TEXT = 0xFFC8CDD4;
    public static final int TEXT_DIM = 0xFF7C848F;
    public static final int TEXT_BRIGHT = 0xFFF2F4F7;
    public static final int GOOD = 0xFF6FD08C;
    public static final int WARN = 0xFFE8B44A;
    public static final int ALARM = 0xFFE05A5A;
    public static final int ACCENT = 0xFF6FA8DC;

    private final ResourceLocation background;

    protected BwrScreen(T menu, Inventory inventory, Component title,
                        ResourceLocation background, int width, int height) {
        super(menu, inventory, title);
        this.background = background;
        this.imageWidth = width;
        this.imageHeight = height;
        this.titleLabelX = 6;
        this.titleLabelY = 3;
        this.inventoryLabelX = 6;
        this.inventoryLabelY = height - 94;
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        graphics.blit(background, leftPos, topPos, 0, 0, imageWidth, imageHeight);
    }

    /**
     * Fixed render order: panel, then widgets and slots, then the screen's own
     * readouts, then tooltips on top of all of it. Screens override
     * {@link #renderContent} rather than this, because drawing text after the
     * tooltip pass would put readouts on top of the tooltip a player is trying
     * to read.
     */
    @Override
    public final void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        // Keep readouts in one batch; slots and tooltips retain their own ordering.
        graphics.drawManaged(() -> renderContent(graphics, mouseX, mouseY));
        renderTooltip(graphics, mouseX, mouseY);
        java.util.List<net.minecraft.network.chat.Component> extra = hoverTooltip();
        if (!extra.isEmpty()) {
            graphics.renderComponentTooltip(font, extra, mouseX, mouseY);
        }
    }

    /** The screen's readouts. Drawn above the panel, below any tooltip. */
    protected void renderContent(GuiGraphics graphics, int mouseX, int mouseY) {
    }

    /** Tooltip for a non-slot widget, such as a lattice cell. */
    protected java.util.List<net.minecraft.network.chat.Component> hoverTooltip() {
        return java.util.List.of();
    }

    // --- drawing helpers ----------------------------------------------

    /** One line of text at panel-relative coordinates. */
    protected void text(GuiGraphics graphics, String value, int x, int y, int colour) {
        graphics.drawString(font, value, leftPos + x, topPos + y, colour, false);
    }

    /** A "label  value" pair with the value right-aligned to {@code rightEdge}. */
    protected void readout(GuiGraphics graphics, String label, String value,
                           int x, int y, int rightEdge, int valueColour) {
        graphics.drawString(font, label, leftPos + x, topPos + y, TEXT_DIM, false);
        int width = font.width(value);
        graphics.drawString(font, value, leftPos + rightEdge - width, topPos + y,
                valueColour, false);
    }

    /** A horizontal bar filled to {@code fraction}. */
    protected void bar(GuiGraphics graphics, int x, int y, int width, int height,
                       double fraction, int colour) {
        int left = leftPos + x;
        int top = topPos + y;
        graphics.fill(left, top, left + width, top + height, 0xFF15181C);
        int filled = (int) Math.round(Math.max(0.0, Math.min(1.0, fraction)) * (width - 2));
        graphics.fill(left + 1, top + 1, left + 1 + filled, top + height - 1, colour);
        graphics.renderOutline(left, top, width, height, 0xFF4A525C);
    }

    // --- formatting ---------------------------------------------------

    protected static String pct(double fraction) {
        return String.format(Locale.ROOT, "%.1f%%", fraction * 100.0);
    }

    protected static String num(double value, int decimals) {
        return String.format(Locale.ROOT, "%." + decimals + "f", value);
    }

    protected static String big(double value) {
        return String.format(Locale.ROOT, "%,.0f", value);
    }

    protected static String sci(double value) {
        return String.format(Locale.ROOT, "%.2e", value);
    }

    /**
     * Colour for a dome pressure readout. 1250 psig is the design limit and
     * 1375 the code limit from {@code REFERENCE-DATA.md}; passing either changes
     * the colour of the text and nothing else whatsoever.
     */
    protected static int pressureColour(double psig) {
        if (psig >= 1375.0) {
            return ALARM;
        }
        return psig >= 1250.0 ? WARN : TEXT_BRIGHT;
    }

    /** Peak cladding temperature colour: 1200 C is the Zr-water onset, 1477 C the 10CFR50.46 limit. */
    protected static int cladColour(double celsius) {
        if (celsius >= 1200.0) {
            return ALARM;
        }
        return celsius >= 1000.0 ? WARN : TEXT_BRIGHT;
    }

    protected static int fractionColour(double fraction, double warnAbove, double alarmAbove) {
        if (fraction >= alarmAbove) {
            return ALARM;
        }
        return fraction >= warnAbove ? WARN : TEXT_BRIGHT;
    }

    /** Blue for cold, through white, to orange-red for a hot bundle. */
    public static int heatColour(double fraction) {
        double f = Math.max(0.0, Math.min(1.0, fraction));
        int r = (int) Math.round(40 + 205 * Math.min(1.0, f * 1.6));
        int g = (int) Math.round(60 + 150 * (1.0 - Math.abs(f - 0.55) * 1.6));
        int b = (int) Math.round(90 + 150 * Math.max(0.0, 1.0 - f * 2.0));
        return 0xFF000000 | (clampByte(r) << 16) | (clampByte(g) << 8) | clampByte(b);
    }

    private static int clampByte(int value) {
        return Math.max(0, Math.min(255, value));
    }
}
