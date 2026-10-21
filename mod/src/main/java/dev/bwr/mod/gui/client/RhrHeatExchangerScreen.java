package dev.bwr.mod.gui.client;

import dev.bwr.mod.gui.RhrHeatExchangerMenu;
import dev.bwr.mod.gui.client.style.BwrStyle;
import dev.bwr.mod.gui.client.style.BwrStyle.Status;
import dev.bwr.mod.gui.client.style.Layout;
import dev.bwr.mod.gui.client.style.UiElement;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/** Read-only instrumentation of the RHR heat exchanger: pool-water side, cooling-water side and the heat moved. */
public class RhrHeatExchangerScreen extends BwrScreen<RhrHeatExchangerMenu> {

    public RhrHeatExchangerScreen(RhrHeatExchangerMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, 400, 160);
    }

    @Override
    protected UiElement buildUi() {
        var flows = Layout.card("Flow and heat",
                Layout.row("Pool-water flow", () -> num(menu.primaryFlow, 1), "kg/s"),
                Layout.row("Cooling-water flow", () -> num(menu.secondaryFlow, 1), "kg/s"),
                Layout.row("Heat transferred", () -> num(menu.heat, 2), "MW"),
                Layout.row("Cold water held", () -> big(menu.cold), "kg"),
                Layout.row("Hot water held", () -> big(menu.hot), "kg"));
        var temperatures = Layout.card("Temperatures",
                Layout.row("Cooling-water inlet", () -> num(menu.coldTemperature, 1), "C"),
                Layout.row("Cooling water outlet", () -> menu.secondaryFlow > 0 ? num(menu.hotTemperature, 1) : "No flow",
                        () -> menu.secondaryFlow > 0 ? "C" : ""),
                Layout.row("Pool water return", () -> menu.primaryFlow > 0 ? num(menu.primaryOut, 1) : "No flow",
                        () -> menu.primaryFlow > 0 ? "C" : ""));
        return Layout.column(BwrStyle.SP2,
                Layout.statusLine(() -> menu.present && menu.connected ? Status.NORMAL : Status.CAUTION,
                        () -> menu.present && menu.connected ? "The pool return is connected" : "Connect the primary outlet to the pool return", null),
                Layout.split(BwrStyle.SP2, flows, temperatures).stackBelow(360),
                Layout.note("Pool side: red pipe in, amber pipe out. Cooling side: blue pipe in, cyan pipe out."),
                Layout.note("Supply cooling water, and drain the heated water from the outlet."));
    }
}
