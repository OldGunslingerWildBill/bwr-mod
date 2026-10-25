package dev.bwr.mod.gui.client;

import dev.bwr.mod.gui.RhrHeatExchangerMenu;
import dev.bwr.mod.gui.client.style.BwrStyle;
import dev.bwr.mod.gui.client.style.BwrStyle.Status;
import dev.bwr.mod.gui.client.style.Layout;
import dev.bwr.mod.gui.client.style.UiElement;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/**
 * Read-only instrumentation of the shell-and-tube RHR heat exchanger: the tube side (RHR pool or reactor water), the shell side
 * (river cooling water from the intake, returned through the outlet chute) and the heat moved between them.
 */
public class RhrHeatExchangerScreen extends BwrScreen<RhrHeatExchangerMenu> {

    public RhrHeatExchangerScreen(RhrHeatExchangerMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, 400, 176);
    }

    /** True when the heated cooling water has nowhere to go: the hot buffer is nearly full, so cooling is about to stop. */
    private boolean outletBackedUp() {
        return menu.hot >= 0.95 * dev.bwr.core.pool.RhrHeatExchanger.CAPACITY_KG;
    }

    @Override
    protected UiElement buildUi() {
        var tube = Layout.card("Tube side: RHR water",
                Layout.row("Inlet temperature", () -> menu.primaryFlow > 0 ? num(menu.primaryIn, 1) : "No flow", () -> menu.primaryFlow > 0 ? "C" : ""),
                Layout.row("Outlet temperature", () -> menu.primaryFlow > 0 ? num(menu.primaryOut, 1) : "No flow", () -> menu.primaryFlow > 0 ? "C" : ""),
                Layout.row("Flow", () -> num(menu.primaryFlow, 1), "kg/s"));
        var shell = Layout.card("Shell side: river cooling water",
                Layout.row("Inlet temperature", () -> num(menu.coldTemperature, 1), "C"),
                Layout.row("Outlet temperature", () -> menu.secondaryFlow > 0 ? num(menu.hotTemperature, 1) : "No flow", () -> menu.secondaryFlow > 0 ? "C" : ""),
                Layout.row("Flow", () -> num(menu.secondaryFlow, 1), "kg/s"));
        var duty = Layout.card("Duty",
                Layout.row("Heat transferred", () -> num(menu.heat, 2), "MW"),
                Layout.row("Water held, cold / heated", () -> big(menu.cold) + " / " + big(menu.hot), "kg"));
        return Layout.column(BwrStyle.SP2,
                Layout.statusLine(() -> !(menu.present && menu.connected) || outletBackedUp() ? Status.CAUTION : Status.NORMAL,
                        () -> !(menu.present && menu.connected) ? "Connect the tube-side outlet to the pool return"
                                : outletBackedUp() ? "The heated cooling water is backed up: check the outlet chute or line"
                                : "The pool return is connected and the cooling-water outlet is draining", null),
                Layout.split(BwrStyle.SP2, tube, shell).stackBelow(360),
                duty,
                Layout.note("Red and amber: tube side. Blue and cyan: shell side, from the intake pump to the outlet chute."));
    }
}
