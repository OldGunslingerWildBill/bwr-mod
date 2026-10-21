package dev.bwr.mod.gui.client;

import dev.bwr.mod.gui.CondenserMenu;
import dev.bwr.mod.gui.client.style.BwrStyle;
import dev.bwr.mod.gui.client.style.BwrStyle.Status;
import dev.bwr.mod.gui.client.style.Layout;
import dev.bwr.mod.gui.client.style.UiElement;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/** Read-only instrumentation of the surface condenser: its water and steam inventories and what it is doing with them. */
public class CondenserScreen extends BwrScreen<CondenserMenu> {

    public CondenserScreen(CondenserMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, 400, 168);
    }

    private boolean running() {
        return menu.present && menu.ready;
    }

    @Override
    protected UiElement buildUi() {
        var inventory = Layout.card("Water and steam",
                Layout.row("Cold cooling water", () -> big(menu.cold), "kg").tip("Cooling water waiting to be pumped through the tubes."),
                Layout.row("Hot cooling water", () -> big(menu.hot), "kg").tip("Cooling water that has passed through the tubes."),
                Layout.row("Hotwell water", () -> big(menu.condensate), "kg").tip("Hotwell / condensate: condensed steam collected in the hotwell."),
                Layout.row("Buffered steam", () -> num(menu.steam, 1), "kg"),
                Layout.row("Cooling water in", () -> num(menu.coldC, 1), "C"),
                Layout.row("Cooling water out", () -> num(menu.hotC, 1), "C"),
                Layout.row("Hotwell temperature", () -> num(menu.condensateC, 1), "C"));
        var performance = Layout.card("Performance",
                Layout.row("Steam condensed", () -> num(menu.steamRate, 1), "kg/s"),
                Layout.row("Cooling-water flow", () -> big(menu.coolingRate), "kg/s"),
                Layout.row("Heat rejected", () -> num(menu.rejectedMW, 1), "MW"),
                Layout.row("Backpressure", () -> num(menu.pressure, 2), "psia"),
                Layout.row("Vacuum", () -> num(menu.vacuum, 1), "inHg"));
        return Layout.column(BwrStyle.SP2,
                Layout.statusLine(() -> running() ? Status.NORMAL : Status.CAUTION,
                        () -> running() ? "Surface condenser" : "Waiting for complete, loaded condenser", null),
                Layout.split(BwrStyle.SP2, inventory, performance).stackBelow(360),
                Layout.note("Round side ports: hotwell makeup water in."));
    }
}
