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
    private BwrButton transfer;

    public OilFilterScreen(OilFilterMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, 336, 250);
    }

    @Override
    protected boolean trayLimitsBody() {
        return false;
    }

    private String state() {
        return !menu.cartridge ? "Side " + side(menu.sideB) + " in service, empty: the oil goes round"
                : menu.bypass ? "Side " + side(menu.sideB) + " in service, full: the oil goes round it" : "Side " + side(menu.sideB) + " in service";
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
        return !menu.otherCartridge ? "empty: open for a change"
                : menu.otherBypass ? "full, change it" : pct(menu.otherLife) + " % left, " + num(menu.otherDpPsi, 1) + " psid";
    }

    @Override
    protected UiElement buildUi() {
        if (transfer == null) {
            transfer = BwrButton.secondary("Transfer", "Throw the transfer valve: the other side goes into service and the one that was in service goes out of service, "
                            + "where it can be opened and its cartridge changed while the plant runs. An empty side in service runs the oil on its bypass.",
                    b -> menu.sendCommand(OilFilterMenu.CMD_TRANSFER, menu.sideB ? 0 : 1));
        }
        var line = Layout.line(this::state, Type.CAPTION)
                .tip("Only the side in service filters and loads. A clean cartridge in service passes the tank's oil through its element and cuts the oil's loss to a tenth. Empty, or full, the oil goes round it through the bypass.");
        var slot = Layout.card("Cartridges", line, Layout.gap(20), new SlotCaption(), Layout.gap(2), Layout.gap(18),
                Layout.controls(BwrStyle.SP1).add(transfer, 1));
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
                Layout.row("Oil loss", () -> pct(menu.factor), "%")
                        .tip("The share of the tank's unfiltered oil loss that gets through: 10 % with a cartridge in service, 100 % (the old rate) with the side in service empty or full."),
                Layout.row("Time left", this::hours)
                        .tip("The cartridge in service, at today's header pressure and oil temperature. Hot oil loads it faster."),
                Layout.row("Out of service", () -> "Side " + side(!menu.sideB) + ": " + otherSide())
                        .status(() -> menu.otherCartridge && !menu.otherBypass ? Status.NONE : Status.CAUTION)
                        .tip("The side out of service does not filter or load. Its cartridge can be taken out and changed while the plant runs."),
                Layout.row("Oil", () -> menu.attached ? num(menu.oilC, 1) + " C, " + num(menu.psig, 1) + " psig" : "-")).compact();

        var tank = Layout.note(() -> menu.attached ? "Serves a Main Oil Tank." : "Not on a Main Oil Tank: it filters nothing.").body()
                .status(() -> menu.attached ? Status.NONE : Status.CAUTION);
        return Layout.column(BwrStyle.SP2,
                Layout.note("There is no oil filter here.").body().status(() -> Status.ALARM).showIf(() -> !menu.present),
                Layout.split(BwrStyle.SP1).fixed(left, LEFT_COLUMN).add(Layout.column(BwrStyle.SP1, life, tank), 1).showIf(() -> menu.present));
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        if (transfer != null) {
            transfer.setMessage(Component.literal(menu.sideB ? "Transfer to side A" : "Transfer to side B"));
            transfer.active = menu.present;
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
                Ui.textCentred(g, "Side " + (s == 0 ? "A" : "B") + (inService ? " (in service)" : ""), cx, y + 1, Type.CAPTION,
                        inService ? BwrStyle.TEXT_PRIMARY : BwrStyle.TEXT_SECONDARY);
            }
        }

        @Override
        public List<Component> tooltipAt(int mouseX, int mouseY) {
            return contains(mouseX, mouseY)
                    ? List.of(Component.literal("Side A and side B, one Oil Filter Cartridge each. The cartridge in service cannot be taken out: throw the transfer valve first, "
                            + "then change the other side. Put one in by hand, shift-click, or from a hopper or pipe into an empty slot. Taking one out gives back a used one that remembers how full it is."))
                    : List.of();
        }

        @Override
        public void narrate(List<String> out) {
            out.add("Slots: oil filter cartridge, side A and side B");
        }
    }
}
