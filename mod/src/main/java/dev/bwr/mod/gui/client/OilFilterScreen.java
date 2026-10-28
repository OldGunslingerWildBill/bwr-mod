package dev.bwr.mod.gui.client;

import dev.bwr.core.turbine.TurbineAux;
import dev.bwr.mod.gui.OilFilterMenu;
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
 * The Oil Filter Housing: the cartridge slot on the left, and on the right what the cartridge has left, the pressure drop across
 * it, whether the oil is going round it and how much of the tank's unfiltered loss gets through.
 */
public class OilFilterScreen extends BwrScreen<OilFilterMenu> {
    private static final int LEFT_COLUMN = 164;

    public OilFilterScreen(OilFilterMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, 336, 224);
    }

    @Override
    protected boolean trayLimitsBody() {
        return false;
    }

    private String state() {
        return !menu.cartridge ? "Empty: the oil goes round" : menu.bypass ? "Full: the oil goes round it" : "In service";
    }

    private String hours() {
        if (!menu.cartridge || menu.hoursLeft < 0) {
            return "-";
        }
        return menu.hoursLeft >= 1 ? String.format(Locale.ROOT, "%.1f h", menu.hoursLeft) : String.format(Locale.ROOT, "%.0f min", menu.hoursLeft * 60);
    }

    @Override
    protected UiElement buildUi() {
        var line = Layout.line(this::state, Type.CAPTION)
                .tip("A clean cartridge passes the tank's oil through its element and cuts the oil's loss to a tenth. Empty, or full, the oil goes round it through the bypass and the tank loses oil as it does without a filter.");
        var slot = Layout.card("Cartridge", line, Layout.gap(20), new SlotCaption(), Layout.gap(2), Layout.gap(18));
        var left = Layout.column(BwrStyle.SP2, slot, Layout.flex());

        var life = Layout.card("Filtration",
                Layout.meter("Cartridge life", () -> menu.cartridge ? menu.life : 0.0,
                        () -> !menu.cartridge ? Status.NONE : menu.changeDue ? Status.CAUTION : Status.NORMAL,
                        () -> menu.cartridge ? pct(menu.life) + " %" : "none").label("Life left"),
                Layout.row("Pressure drop", () -> menu.cartridge ? num(menu.dpPsi, 1) : "-", "psid")
                        .status(() -> menu.cartridge && menu.dpPsi >= TurbineAux.FILTER_DP_CHANGE_PSI ? Status.CAUTION : Status.NONE)
                        .tip("Across the cartridge, " + (int) TurbineAux.FILTER_DP_CLEAN_PSI + " psid when clean, rising as it loads. At " + (int) TurbineAux.FILTER_DP_CHANGE_PSI
                                + " psid a change is due; at " + (int) TurbineAux.FILTER_DP_FULL_PSI + " psid it is full and the bypass valve opens."),
                Layout.row("Bypass", () -> menu.bypass ? "open" : "shut")
                        .status(() -> menu.bypass ? Status.CAUTION : Status.NONE)
                        .tip("Open when the slot is empty or the cartridge is full: the oil goes round the element."),
                Layout.row("Oil loss", () -> pct(menu.factor), "%")
                        .tip("The share of the tank's unfiltered oil loss that gets through: 10 % with a cartridge in service, 100 % (the old rate) with the slot empty or the cartridge full."),
                Layout.row("Time left", this::hours)
                        .tip("At today's header pressure and oil temperature. Hot oil loads the cartridge faster."),
                Layout.row("Oil", () -> menu.attached ? num(menu.oilC, 1) + " C, " + num(menu.psig, 1) + " psig" : "-")).compact();

        var tank = Layout.note(() -> menu.attached ? "Bolted on a Main Oil Tank." : "Not against a Main Oil Tank: it filters nothing.").body()
                .status(() -> menu.attached ? Status.NONE : Status.CAUTION);
        return Layout.column(BwrStyle.SP2,
                Layout.note("There is no oil filter here.").body().status(() -> Status.ALARM).showIf(() -> !menu.present),
                Layout.split(BwrStyle.SP1).fixed(left, LEFT_COLUMN).add(Layout.column(BwrStyle.SP1, life, tank), 1).showIf(() -> menu.present));
    }

    private final class SlotCaption extends UiElement {
        @Override
        protected int measure(int width) {
            return 10;
        }

        @Override
        public void render(GuiGraphics g, int mouseX, int mouseY) {
            Ui.textCentred(g, "Cartridge", leftPos + menu.getSlot(OilFilterMenu.SLOT_CARTRIDGE).x + 8, y + 1, Type.CAPTION, BwrStyle.TEXT_SECONDARY);
        }

        @Override
        public List<Component> tooltipAt(int mouseX, int mouseY) {
            return contains(mouseX, mouseY)
                    ? List.of(Component.literal("An Oil Filter Cartridge. Put it in by hand, shift-click, or from a hopper or pipe into an empty slot. Taking it out gives back a used one that remembers how full it is. Nothing pulls it out for you."))
                    : List.of();
        }

        @Override
        public void narrate(List<String> out) {
            out.add("Slot: oil filter cartridge");
        }
    }
}
