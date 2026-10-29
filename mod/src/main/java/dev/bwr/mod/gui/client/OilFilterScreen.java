package dev.bwr.mod.gui.client;

import dev.bwr.core.turbine.TurbineAux;
import dev.bwr.mod.gui.OilFilterMenu;
import dev.bwr.mod.gui.client.style.BwrButton;
import dev.bwr.mod.gui.client.style.BwrStyle;
import dev.bwr.mod.gui.client.style.BwrStyle.Status;
import dev.bwr.mod.gui.client.style.BwrStyle.Type;
import dev.bwr.mod.gui.client.style.Layout;
import dev.bwr.mod.gui.client.style.Ui;
import dev.bwr.mod.gui.client.style.UiElement;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

import java.util.List;
import java.util.Locale;

/**
 * The Oil Filter Housing, a duplex filter: the two cartridge slots, A and B, on the left with the transfer valve button, and on the
 * right what the cartridge in service has left, the pressure drop across it, whether the oil is going round it, how much of the tank's
 * unfiltered loss gets through, and what the side out of service holds.
 */
public class OilFilterScreen extends BwrScreen<OilFilterMenu> {
    private static final int LEFT_COLUMN = 164;
    private SelectorSwitch transfer;

    public OilFilterScreen(OilFilterMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, 336, 240);
    }

    @Override
    protected boolean trayLimitsBody() {
        return false;
    }

    private String state() {
        return !menu.cartridge ? side(menu.sideB) + " in service, empty: bypass"
                : menu.bypass ? side(menu.sideB) + " in service, full: bypass" : side(menu.sideB) + " in service";
    }

    private static String side(boolean b) {
        return b ? "B" : "A";
    }

    private String hours() {
        if (!menu.cartridge || menu.hoursLeft < 0) {
            return "-";
        }
        return menu.hoursLeft >= 1 ? String.format(Locale.ROOT, "%.1f h", menu.hoursLeft) : String.format(Locale.ROOT, "%.0f min", menu.hoursLeft * 60);
    }

    private String otherSide() {
        return !menu.otherCartridge ? "empty, open" : TurbineAux.GRADE_NAMES[menu.otherGrade] + " " + (menu.otherBypass ? "full" : pct(menu.otherLife) + " %");
    }

    private String element() {
        if (!menu.cartridge) return "none";
        return TurbineAux.GRADE_NAMES[menu.grade] + (menu.flowCap >= 0 ? " " + pct(menu.flowCap) + " %" : "");
    }

    @Override
    protected UiElement buildUi() {
        if (transfer == null) {
            transfer = new SelectorSwitch(side -> menu.sendCommand(OilFilterMenu.CMD_TRANSFER, side));
        }
        var line = Layout.line(this::state, Type.CAPTION)
                .tip("Only the side in service filters and loads. A clean cartridge in service passes the tank's oil through its element and cuts the oil's loss to a tenth. Empty, or full, the oil goes round it through the bypass.");
        var slot = Layout.card("Cartridges", line, Layout.gap(20), new SlotCaption(), Layout.gap(2), Layout.gap(18), Layout.gap(10),
                Layout.controls(BwrStyle.SP1).label("Transfer", 42).add(transfer, 1));
        var left = Layout.column(BwrStyle.SP2, slot, Layout.flex());

        var life = Layout.card("Filtration",
                Layout.meter("Cartridge life", () -> menu.cartridge ? menu.life : 0.0,
                        () -> !menu.cartridge ? Status.NONE : menu.changeDue ? Status.CAUTION : Status.NORMAL,
                        () -> menu.cartridge ? pct(menu.life) + " %" : "none").label("Life left"),
                Layout.row("Pressure drop", () -> menu.cartridge ? num(menu.dpPsi, 1) : "-", "psid")
                        .status(() -> menu.cartridge && menu.dpPsi >= TurbineAux.FILTER_DP_CHANGE_PSI ? Status.CAUTION : Status.NONE)
                        .tip("Across the cartridge in service, " + (int) TurbineAux.FILTER_DP_CLEAN_PSI + " psid when clean, rising as it loads. At " + (int) TurbineAux.FILTER_DP_CHANGE_PSI
                                + " psid a change is due; at " + (int) TurbineAux.FILTER_DP_FULL_PSI + " psid it is full and the bypass valve opens."),
                Layout.row("Bypass", () -> menu.bypass ? "open" : "shut")
                        .status(() -> menu.bypass ? Status.CAUTION : Status.NONE)
                        .tip("Open when the side in service has an empty slot or a full cartridge: the oil goes round the element."),
                Layout.row("Element", this::element)
                        .status(() -> menu.cartridge && menu.flowLimited ? Status.CAUTION : Status.NONE)
                        .tip("The grade of the cartridge in service and the flow it passes, as a share of what a whole train's bearings need at rated speed. A fine element passes 30 percent clean and less as it loads: one fine housing alone starves a train, it takes four side by side. A coarse element passes more than every pump together."),
                Layout.row("Bearing flow", () -> Double.isNaN(menu.bearingFlow) ? "-" : pct(menu.bearingFlow) + " %")
                        .status(() -> menu.flowVeryLow ? Status.ALARM : menu.flowLow ? Status.CAUTION : Status.NONE)
                        .tip("The oil delivered to the bearings of the train this tank serves, over what the speed needs. Under 90 % is a low-flow alarm (amber), 70 % or under a very-low one (red)."),
                Layout.row("Oil loss", () -> pct(menu.factor), "%")
                        .tip("The share of the tank's unfiltered oil loss that gets through: 10 % with a standard cartridge in service, 20 % coarse, 3 % fine, 100 % (the old rate) with the side in service empty or full."),
                Layout.row("Time left", this::hours)
                        .tip("The cartridge in service, at today's header pressure and oil temperature. Hot oil loads it faster."),
                Layout.row("Standby", () -> side(!menu.sideB) + ": " + otherSide())
                        .status(() -> menu.otherCartridge && !menu.otherBypass ? Status.NONE : Status.CAUTION)
                        .tip("The side out of service does not filter or load. Its cartridge can be taken out and changed while the plant runs."),
                Layout.row("Oil", () -> menu.attached ? num(menu.oilC, 1) + " C, " + num(menu.psig, 1) + " psig" : "-"),
                Layout.row("Cleanliness", () -> Double.isNaN(menu.cleanliness) ? "-" : pct(menu.cleanliness), "%")
                        .status(() -> !Double.isNaN(menu.cleanliness) && menu.cleanliness > 0.4 ? Status.CAUTION : Status.NONE)
                        .tip("The particles in the tank's oil; 20 % is the oil the plant has always run. Dirtier oil wears the bearings, shakes the shaft and degrades faster.")).compact();

        var tank = Layout.note(() -> menu.attached ? "On a Main Oil Tank's return line." : menu.supplyLine
                ? "Not on a return line: on a supply run it passes oil and filters nothing. Filters go between the bearing drains and the tank."
                : "Not on a return line or a tank: it filters nothing.").body()
                .status(() -> menu.attached ? Status.NONE : Status.CAUTION);
        return Layout.column(BwrStyle.SP2,
                Layout.note("There is no oil filter here.").body().status(() -> Status.ALARM).showIf(() -> !menu.present),
                Layout.split(BwrStyle.SP1).fixed(left, LEFT_COLUMN).add(Layout.column(BwrStyle.SP1, life, tank), 1).showIf(() -> menu.present));
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        if (transfer != null) {
            transfer.sideB = menu.sideB;
            transfer.active = menu.present;
        }
    }

    /**
     * The transfer valve as a two-position A/B selector switch: a recessed plate with the letters A and B at its ends and a knob (with a
     * pointer) at the position selected. Click the other end, or the knob, to throw it; Enter or Space on a focused switch throws it too.
     */
    private static final class SelectorSwitch extends net.minecraft.client.gui.components.AbstractButton {
        private final java.util.function.IntConsumer thrown;
        boolean sideB;

        SelectorSwitch(java.util.function.IntConsumer thrown) {
            super(0, 0, 100, BwrStyle.CONTROL, Component.literal("Transfer valve"));
            this.thrown = thrown;
            setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("The transfer valve, a two-position selector: A or B. "
                    + "The side selected goes into service; the other goes out of service, where it can be opened and its cartridge changed while the plant runs. "
                    + "An empty side in service runs the oil on its bypass."), Component.literal("Transfer valve")));
        }

        @Override
        public void onPress() {
            thrown.accept(sideB ? 0 : 1);
        }

        @Override
        @SuppressWarnings("deprecation")
        public void onClick(double mouseX, double mouseY) {
            // A click on an end throws it to that end; on the knob, between, it moves to the other end.
            int mid = getX() + getWidth() / 2;
            int target = mouseX < mid - 8 ? 0 : mouseX > mid + 8 ? 1 : (sideB ? 0 : 1);
            if ((target == 1) != sideB) thrown.accept(target);
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            int x = getX(), y = getY(), w = getWidth(), h = getHeight();
            boolean hover = isHovered() && active;
            g.fill(x, y, x + w, y + h, active ? BwrStyle.INSET : BwrStyle.SURFACE);
            Ui.frame(g, x, y, w, h, isFocused() ? BwrStyle.FOCUS : hover ? BwrStyle.BORDER_STRONG : BwrStyle.BORDER);
            // The slot the knob slides in, between the two letters.
            int slotL = x + 20, slotR = x + w - 20, cy = y + h / 2;
            g.fill(slotL, cy - 2, slotR, cy + 2, 0xFF05070A);
            Ui.frame(g, slotL, cy - 2, slotR - slotL, 4, BwrStyle.RULE);
            // The knob: raised, with a pointer toward the letter selected.
            int kw = 16, kh = h - 6;
            int kx = sideB ? slotR - kw + 2 : slotL - 2;
            int ky = y + 3;
            int face = !active ? BwrStyle.SURFACE : hover ? BwrStyle.RAISED_HOVER : BwrStyle.RAISED;
            g.fill(kx, ky, kx + kw, ky + kh, face);
            Ui.frame(g, kx, ky, kw, kh, active ? BwrStyle.BORDER_STRONG : BwrStyle.BORDER);
            int px = sideB ? kx + kw - 5 : kx + 3;
            g.fill(px, ky + 3, px + 2, ky + kh - 3, active ? BwrStyle.ACCENT : BwrStyle.TEXT_DISABLED);
            // The letters, the one selected lit.
            int ink = active ? BwrStyle.TEXT_SECONDARY : BwrStyle.TEXT_DISABLED;
            Ui.textCentred(g, "A", x + 10, y + (h - 8) / 2, Type.BODY, !sideB && active ? BwrStyle.ACCENT : ink);
            Ui.textCentred(g, "B", x + w - 10, y + (h - 8) / 2, Type.BODY, sideB && active ? BwrStyle.ACCENT : ink);
        }

        @Override
        protected void updateWidgetNarration(net.minecraft.client.gui.narration.NarrationElementOutput output) {
            output.add(net.minecraft.client.gui.narration.NarratedElementType.TITLE, Component.literal("Transfer valve: side " + (sideB ? "B" : "A") + " in service"));
        }
    }

    private final class SlotCaption extends UiElement {
        @Override
        protected int measure(int width) {
            return 10;
        }

        @Override
        public void render(GuiGraphics g, int mouseX, int mouseY) {
            for (int s = 0; s < 2; s++) {
                boolean inService = (s == 1) == menu.sideB;
                int cx = leftPos + menu.getSlot(s == 0 ? OilFilterMenu.SLOT_CARTRIDGE : OilFilterMenu.SLOT_B).x + 8;
                Ui.textCentred(g, (s == 0 ? "A" : "B") + (inService ? ": in service" : ": standby"), cx, y + 1, Type.CAPTION,
                        inService ? BwrStyle.TEXT_PRIMARY : BwrStyle.TEXT_SECONDARY);
            }
        }

        @Override
        public List<Component> tooltipAt(int mouseX, int mouseY) {
            return contains(mouseX, mouseY)
                    ? List.of(Component.literal("Side A and side B, one Oil Filter Cartridge each. The cartridge in service cannot be taken out: throw the transfer valve first, "
                            + "then change the other side. Put one in by hand, shift-click, or from a hopper into the empty side out of service (the canister in service is never opened, even empty). Taking one out gives back a used one that remembers how full it is."))
                    : List.of();
        }

        @Override
        public void narrate(List<String> out) {
            out.add("Slots: oil filter cartridge, side A and side B");
        }
    }
}
