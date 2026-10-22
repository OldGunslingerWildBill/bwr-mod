package dev.bwr.mod.gui.client;

import dev.bwr.mod.gui.BwrMenu;
import dev.bwr.mod.gui.client.style.BwrEditBox;
import dev.bwr.mod.gui.client.style.BwrStyle;
import dev.bwr.mod.gui.client.style.BwrStyle.Status;
import dev.bwr.mod.gui.client.style.BwrStyle.Type;
import dev.bwr.mod.gui.client.style.Ui;
import dev.bwr.mod.gui.client.style.UiElement;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The base of every machine screen: the window (panel, title bar, optional title-bar tabs and chips), the layout engine
 * that places a tree of {@link UiElement}s in the body, the inventory tray with its slot wells for screens that have
 * slots, a scroll container for a body that does not fit, keyboard and narration support, and the number formatting
 * helpers.
 *
 * <h2>How a screen is written</h2>
 * A subclass passes its preferred size, builds its widgets (buttons, fields, sliders) once in its constructor or in
 * {@link #buildUi()}, and returns the root of its element tree from {@link #buildUi()}. It never positions anything:
 * the elements do. State that decides whether a control is enabled is set in {@code containerTick}, as before.
 *
 * <h2>Sizes</h2>
 * The panel is as large as the screen asks for but never larger than the window less a margin, and every screen is
 * designed to fit 320 x 240, the smallest GUI size the game gives a screen. A body that still does not fit scrolls
 * (mouse wheel, Page Up and Down, Home and End, and Tab reveals the next control).
 *
 * <h2>On colour</h2>
 * Colour here is typography, not logic. A pressure that has climbed past the design limit is drawn in amber or red,
 * with a shape and a word beside it, so a player's eye finds it, and that is the entire extent of it: no code reads a
 * colour, and nothing in the plant behaves differently because a number turned amber. The thresholds used are the
 * published design figures from {@code REFERENCE-DATA.md}; they are not setpoints, because nothing in this mod trips at
 * anything.
 */
public abstract class BwrScreen<T extends BwrMenu> extends AbstractContainerScreen<T> {

    private final int preferredWidth;
    private final int preferredHeight;

    private UiElement root;
    private UiElement titleItems;
    private final List<AbstractWidget> flow = new ArrayList<>();
    private final Set<AbstractWidget> scrolledOut = new HashSet<>();

    private int bodyX;
    private int bodyY;
    private int bodyW;
    private int bodyH;
    private int scroll;
    private int scrollMax;
    private int trayX;
    private int trayY;
    private int trayW;
    private int trayH;
    private boolean titleCut;
    private int titleRight;
    private boolean narratedOnce;
    private boolean fixedSize;

    private static final int TRAY_PAD = 3;

    protected BwrScreen(T menu, Inventory inventory, Component title, int preferredWidth, int preferredHeight) {
        super(menu, inventory, title);
        this.preferredWidth = preferredWidth;
        this.preferredHeight = preferredHeight;
        this.imageWidth = preferredWidth;
        this.imageHeight = preferredHeight;
    }

    // ---------------------------------------------------------------
    // Subclass hooks
    // ---------------------------------------------------------------

    /** The body: a tree of elements. Called on every (re)initialisation, so it may create widgets. */
    protected abstract UiElement buildUi();

    /** Things at the right of the title bar (tabs, a status chip), laid out right to left; null for none. */
    protected UiElement buildTitleItems() {
        return null;
    }

    // ---------------------------------------------------------------
    // Initialisation and layout
    // ---------------------------------------------------------------

    @Override
    protected void init() {
        // A screen with slots keeps the size the menu's slot positions were laid out for; the others shrink to the window.
        boolean fixed = !menu.slots.isEmpty();
        fixedSize = fixed;
        int usable = width - 2 * BwrStyle.WINDOW_MARGIN;
        if (usable < 320 && width >= 320) {
            // the smallest GUI size the game gives a screen is 320 wide: a panel may use all of it
            usable = 320;
        }
        // A screen with slots keeps its height (the slot positions are the menu's) but may be narrower, down to the slots.
        int slotsRight = 0;
        for (Slot slot : menu.slots) {
            slotsRight = Math.max(slotsRight, slot.x + 16 + 1 + TRAY_PAD + BwrStyle.SP2);
        }
        imageWidth = Math.min(preferredWidth, Math.max(Math.max(160, slotsRight), usable));
        imageHeight = fixed ? preferredHeight : Math.min(preferredHeight, Math.max(120, height - 2 * BwrStyle.WINDOW_MARGIN));
        super.init();

        root = buildUi();
        titleItems = buildTitleItems();
        flow.clear();
        scrolledOut.clear();
        if (titleItems != null) {
            titleItems.collectWidgets(flow::add);
        }
        root.collectWidgets(flow::add);
        for (AbstractWidget widget : flow) {
            addRenderableWidget(widget);
        }
        relayout();
    }

    /** Positions the body, the title items and the tray for the menu's current state. Cheap; called every frame. */
    protected final void relayout() {
        if (root == null) {
            return;
        }
        if (!fixedSize) {
            // As tall as the content needs, never shorter than the design height and never taller than the window.
            int wanted = BwrStyle.TITLE_BAR + BwrStyle.SP2 + root.naturalHeight(imageWidth - 2 * BwrStyle.SP2) + BwrStyle.SP2;
            int most = Math.max(120, height - 2 * BwrStyle.WINDOW_MARGIN);
            imageHeight = Math.min(Math.max(preferredHeight, wanted), most);
            topPos = (height - imageHeight) / 2;
        }
        computeTray();
        bodyX = leftPos + BwrStyle.SP2;
        bodyY = topPos + BwrStyle.TITLE_BAR + BwrStyle.SP2;
        bodyW = imageWidth - 2 * BwrStyle.SP2;
        int bottom = trayW > 0 && trayLimitsBody() ? trayY - BwrStyle.SP1 : topPos + imageHeight - BwrStyle.SP2;
        bodyH = Math.max(BwrStyle.ROW, bottom - bodyY);

        int natural = root.naturalHeight(bodyW);
        scrollMax = Math.max(0, natural - bodyH);
        scroll = Math.max(0, Math.min(scroll, scrollMax));
        if (scrollMax > 0) {
            root.place(bodyX, bodyY - scroll, bodyW - BwrStyle.SP1, natural);
        } else {
            root.place(bodyX, bodyY, bodyW, bodyH);
        }

        scrolledOut.clear();
        if (scrollMax > 0) {
            List<AbstractWidget> body = new ArrayList<>();
            root.collectWidgets(body::add);
            for (AbstractWidget widget : body) {
                if (widget.visible && (widget.getY() < bodyY || widget.getY() + widget.getHeight() > bodyY + bodyH)) {
                    widget.visible = false;
                    scrolledOut.add(widget);
                }
            }
        }

        if (titleItems != null) {
            int available = imageWidth - 2 * BwrStyle.SP2;
            int pw = Math.min(available, titleItems.preferredWidth(available));
            titleItems.place(leftPos + imageWidth - BwrStyle.SP2 - pw, topPos + 2, pw, BwrStyle.TITLE_BAR - 4);
            titleRight = leftPos + imageWidth - BwrStyle.SP2 - pw - BwrStyle.SP2;
        } else {
            titleRight = leftPos + imageWidth - BwrStyle.SP2;
        }
    }

    /** The inventory tray as {@code {x, y, width, height}} relative to the panel, from the menu's slots; null without any. */
    protected final int[] relativeTray() {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (Slot slot : menu.slots) {
            if (isTraySlot(slot)) {
                minX = Math.min(minX, slot.x - 1);
                minY = Math.min(minY, slot.y - 1);
                maxX = Math.max(maxX, slot.x + 17);
                maxY = Math.max(maxY, slot.y + 17);
            }
        }
        return minX == Integer.MAX_VALUE ? null
                : new int[]{minX - TRAY_PAD, minY - TRAY_PAD, maxX - minX + 2 * TRAY_PAD, maxY - minY + 2 * TRAY_PAD};
    }

    private void computeTray() {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (Slot slot : menu.slots) {
            if (isTraySlot(slot)) {
                minX = Math.min(minX, slot.x - 1);
                minY = Math.min(minY, slot.y - 1);
                maxX = Math.max(maxX, slot.x + 17);
                maxY = Math.max(maxY, slot.y + 17);
            }
        }
        if (minX == Integer.MAX_VALUE) {
            trayW = 0;
            trayH = 0;
            return;
        }
        trayX = leftPos + minX - TRAY_PAD;
        trayY = topPos + minY - TRAY_PAD;
        trayW = maxX - minX + 2 * TRAY_PAD;
        trayH = maxY - minY + 2 * TRAY_PAD;
    }

    /** Whether a slot belongs to the player's inventory tray (drawn as one card) rather than to the machine. */
    protected boolean isTraySlot(Slot slot) {
        return slot.container instanceof Inventory;
    }

    /**
     * Whether the body ends where the inventory tray begins (the default). A screen that keeps its machine cards beside
     * the tray rather than above it returns false and lays its own columns out so that nothing sits over the tray.
     */
    protected boolean trayLimitsBody() {
        return true;
    }

    /** True while the body is taller than the window allows and is being scrolled (the style's screens fit 426 x 240 without it). */
    public final boolean bodyScrolls() {
        return scrollMax > 0;
    }

    /** Where the player-inventory tray starts, relative to the panel, or -1 without slots. Screens use it to place side cards. */
    protected final int trayLeft() {
        return trayW > 0 ? trayX - leftPos : -1;
    }

    protected final int trayRight() {
        return trayW > 0 ? trayX - leftPos + trayW : -1;
    }

    protected final int bodyLeft() {
        return bodyX;
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        relayout();
    }

    // ---------------------------------------------------------------
    // Drawing
    // ---------------------------------------------------------------

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        relayout();
        Ui.panel(g, leftPos, topPos, imageWidth, imageHeight);

        // title
        String title = this.title.getString();
        int titleX = leftPos + BwrStyle.SP2;
        int room = titleRight - titleX;
        String shown = Ui.fit(title, Type.TITLE, room);
        titleCut = !shown.equals(title);
        Ui.text(g, shown, titleX, topPos + (BwrStyle.TITLE_BAR - 8) / 2, Type.TITLE);
        if (titleItems != null) {
            titleItems.render(g, mouseX, mouseY);
        }

        // inventory tray, then the body, then the slot wells on top of both (a machine's own slot sits inside a card)
        if (trayW > 0) {
            Ui.card(g, trayX, trayY, trayW, trayH);
        }
        if (scrollMax > 0) {
            Ui.enableScissor(g, bodyX, bodyY, bodyX + bodyW, bodyY + bodyH);
            Ui.clipped(true);
        }
        root.render(g, mouseX, mouseY);
        if (scrollMax > 0) {
            Ui.clipped(false);
            Ui.disableScissor(g);
            renderScrollbar(g);
        }
        for (Slot slot : menu.slots) {
            Ui.slotWell(g, leftPos + slot.x, topPos + slot.y);
        }
    }

    private void renderScrollbar(GuiGraphics g) {
        int x = bodyX + bodyW - 3;
        g.fill(x, bodyY, x + 3, bodyY + bodyH, BwrStyle.INSET);
        int thumb = Math.max(12, bodyH * bodyH / (bodyH + scrollMax));
        int top = bodyY + (int) ((long) (bodyH - thumb) * scroll / Math.max(1, scrollMax));
        g.fill(x, top, x + 3, top + thumb, BwrStyle.BORDER_STRONG);
    }

    /** No vanilla labels: the title is drawn by {@link #renderBg}, in the style's title bar. */
    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
    }

    /**
     * Fixed render order: panel, then the elements, widgets and slots, then tooltips on top of all of it. Screens
     * override the element tree and {@link #hoverTooltip} rather than this.
     */
    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (batchedDrawing() && !Ui.legacyDrawing()) {
            Ui.batch(g, () -> super.render(g, mouseX, mouseY, partialTick));
        } else {
            super.render(g, mouseX, mouseY, partialTick);
        }
        renderTooltip(g, mouseX, mouseY);
        if (hoveredSlot != null && hoveredSlot.hasItem()) {
            return;
        }
        List<Component> tip = hoverTooltip();
        if (tip.isEmpty() && titleCut && mouseY >= topPos && mouseY < topPos + BwrStyle.TITLE_BAR
                && mouseX >= leftPos && mouseX < titleRight) {
            tip = List.of(this.title);
        }
        if (tip.isEmpty()) {
            tip = root == null ? List.of() : root.tooltipAt(mouseX, mouseY);
        }
        if (!tip.isEmpty()) {
            g.renderComponentTooltip(font, tip, mouseX, mouseY);
        }
    }

    /**
     * Whether the panel, its elements and its widgets are drawn into one held-open vertex buffer, with text after fills
     * (see {@link Ui#batch}), instead of one draw call per fill and per string. Off by default. Turn it on only for a
     * screen with no item slots (items are drawn in the same pass) and none of whose fills is drawn over its own text.
     */
    protected boolean batchedDrawing() {
        return false;
    }

    /** Tooltip for something that is not an element or a slot, such as a lattice cell. Empty by default. */
    protected List<Component> hoverTooltip() {
        return List.of();
    }

    // ---------------------------------------------------------------
    // Keyboard, wheel and narration
    // ---------------------------------------------------------------

    @Override
    public boolean keyPressed(int key, int scan, int modifiers) {
        // An edit box swallows ordinary keys so that typing the inventory key does not close the screen.
        if (getFocused() instanceof BwrEditBox box && box.isFocused()) {
            if (key == 256) {
                onClose();
                return true;
            }
            if (key == 257 || key == 335) {
                box.submit();
                return true;
            }
            if (key != 258 && key != 264 && key != 265) {
                box.keyPressed(key, scan, modifiers);
                return true;
            }
        }
        if (root != null && (key == 266 || key == 267) && !(getFocused() instanceof BwrEditBox) && root.page(key == 266 ? -1 : 1)) {
            return true;
        }
        if (scrollMax > 0 || !scrolledOut.isEmpty()) {
            if (key == 258 && revealForTab(!hasShiftDown())) {
                return true;
            }
            if (!(getFocused() instanceof BwrEditBox)) {
                int page = Math.max(BwrStyle.ROW, bodyH - BwrStyle.ROW);
                switch (key) {
                    case 266 -> {
                        scrollBy(-page);
                        return true;
                    }
                    case 267 -> {
                        scrollBy(page);
                        return true;
                    }
                    case 268 -> {
                        scrollBy(-scrollMax);
                        return true;
                    }
                    case 269 -> {
                        scrollBy(scrollMax);
                        return true;
                    }
                    default -> {
                    }
                }
            }
        }
        return super.keyPressed(key, scan, modifiers);
    }

    /** Tab towards a control that is scrolled out of view: scroll it in and focus it. */
    private boolean revealForTab(boolean forward) {
        int current = -1;
        for (int i = 0; i < flow.size(); i++) {
            if (flow.get(i) == getFocused()) {
                current = i;
            }
        }
        int step = forward ? 1 : -1;
        for (int i = current + step; i >= 0 && i < flow.size(); i += step) {
            AbstractWidget candidate = flow.get(i);
            if (scrolledOut.contains(candidate)) {
                int target = candidate.getY() + scroll - bodyY - (bodyH - candidate.getHeight()) / 2;
                scroll = Math.max(0, Math.min(scrollMax, target));
                relayout();
                if (candidate.visible) {
                    setFocused(candidate);
                    return true;
                }
                return false;
            }
            if (candidate.visible && candidate.active) {
                return false;
            }
        }
        return false;
    }

    private void scrollBy(int pixels) {
        scroll = Math.max(0, Math.min(scrollMax, scroll + pixels));
        relayout();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)) {
            return true;
        }
        if (root != null && scrollY != 0.0 && root.scroll(mouseX, mouseY, scrollY)) {
            return true;
        }
        if (scrollMax > 0 && scrollY != 0.0 && mouseX >= leftPos && mouseX < leftPos + imageWidth
                && mouseY >= topPos && mouseY < topPos + imageHeight) {
            scrollBy((int) Math.round(-scrollY * BwrStyle.ROW * 2));
            return true;
        }
        return false;
    }

    /** What a screen reader should say when the screen opens: the title and every readout, once. */
    @Override
    protected void updateNarratedWidget(NarrationElementOutput output) {
        if (!narratedOnce && root != null) {
            narratedOnce = true;
            List<String> lines = new ArrayList<>();
            root.narrate(lines);
            if (!lines.isEmpty()) {
                output.add(NarratedElementType.HINT, Component.literal(String.join(". ", lines)));
            }
        }
        super.updateNarratedWidget(output);
    }

    // ---------------------------------------------------------------
    // Status helpers (colour is typography: see the class comment)
    // ---------------------------------------------------------------

    /** Dome pressure: 1250 psig is the design limit and 1375 the code limit from {@code REFERENCE-DATA.md}. */
    protected static Status pressureStatus(double psig) {
        if (psig >= 1375.0) {
            return Status.ALARM;
        }
        return psig >= 1250.0 ? Status.CAUTION : Status.NONE;
    }

    /** Peak cladding temperature: 1200 C is the Zr-water onset, 1477 C the 10CFR50.46 limit. */
    protected static Status cladStatus(double celsius) {
        if (celsius >= 1200.0) {
            return Status.ALARM;
        }
        return celsius >= 1000.0 ? Status.CAUTION : Status.NONE;
    }

    protected static Status fractionStatus(double fraction, double cautionAbove, double alarmAbove) {
        if (fraction >= alarmAbove) {
            return Status.ALARM;
        }
        return fraction >= cautionAbove ? Status.CAUTION : Status.NONE;
    }

    // ---------------------------------------------------------------
    // Formatting
    // ---------------------------------------------------------------

    /** A fraction as a percentage without its sign: "12.3". Pair with the unit "%". */
    protected static String pct(double fraction) {
        return String.format(Locale.ROOT, "%.1f", fraction * 100.0);
    }

    protected static String num(double value, int decimals) {
        return String.format(Locale.ROOT, "%." + decimals + "f", value);
    }

    /** A number with thousands separators and no decimals. */
    protected static String big(double value) {
        return String.format(Locale.ROOT, "%,.0f", value);
    }

    /** A number with thousands separators and the given decimals. */
    protected static String grouped(double value, int decimals) {
        return String.format(Locale.ROOT, "%,." + decimals + "f", value);
    }

    protected static String sci(double value) {
        return String.format(Locale.ROOT, "%.2e", value);
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
