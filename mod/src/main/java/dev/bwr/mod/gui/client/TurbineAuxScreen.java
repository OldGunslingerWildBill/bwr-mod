package dev.bwr.mod.gui.client;

import dev.bwr.core.turbine.TurbineAux;
import dev.bwr.mod.gui.TurbineAuxMenu;
import dev.bwr.mod.gui.client.style.BwrButton;
import dev.bwr.mod.gui.client.style.BwrStyle;
import dev.bwr.mod.gui.client.style.BwrStyle.Status;
import dev.bwr.mod.gui.client.style.Layout;
import dev.bwr.mod.gui.client.style.UiElement;
import dev.bwr.mod.power.TurbineAuxTrain;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/** A turbine auxiliary skid: the Main Oil Tank (kind 0), the Shaft-Driven Main Oil Pump (1) or the Generator Cooling Skid (2). */
public class TurbineAuxScreen extends BwrScreen<TurbineAuxMenu> {
    private BwrButton acPump, dcPump, statorPumps;

    public TurbineAuxScreen(TurbineAuxMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, 400, 150);
    }

    private boolean tank() { return menu.kind == 0; }
    private boolean shaftPump() { return menu.kind == 1; }
    private boolean cooling() { return menu.kind == 2; }

    private static String pump(boolean running, boolean on, double fe) {
        return (running ? "running" : on ? "no power" : "off") + ", " + big(fe) + " FE";
    }

    @Override
    protected UiElement buildUi() {
        if (acPump == null) {
            acPump = BwrButton.secondary("AC pump", "Start or stop the AC auxiliary oil pump. It needs FE on the side or bottom faces and carries the shaft until the shaft-driven pump takes over near rated speed.",
                    b -> menu.sendCommand(TurbineAuxMenu.CMD_AC_PUMP, menu.acOn ? 0 : 1));
            dcPump = BwrButton.secondary("DC pump", "Start or stop the emergency DC oil pump. It draws FE from the top face, where you wire a battery. Nothing starts it for you.",
                    b -> menu.sendCommand(TurbineAuxMenu.CMD_DC_PUMP, menu.dcOn ? 0 : 1));
            statorPumps = BwrButton.secondary("Stator water pumps", "Start or stop the stator cooling water pumps. They draw FE; without them the winding runs hotter with load.",
                    b -> menu.sendCommand(TurbineAuxMenu.CMD_STATOR_PUMPS, menu.statorOn ? 0 : 1));
        }
        var oil = Layout.card("Lube oil",
                Layout.row("Oil pressure", () -> num(menu.psig, 1), "psig"),
                Layout.row("Oil film", () -> pct(menu.film), "%"),
                Layout.meter("Oil level", () -> menu.oilFraction, () -> menu.oilFraction < TurbineAuxTrain.ALARM_OIL_LEVEL ? Status.CAUTION : Status.NORMAL,
                        () -> grouped(menu.oilFraction * TurbineAux.OIL_TANK_L, 0) + " L"),
                Layout.row("Oil temperature", () -> num(menu.oilC, 1), "C"),
                Layout.row("AC pump", () -> pump(menu.acRun, menu.acOn, menu.acFe)),
                Layout.row("DC pump", () -> pump(menu.dcRun, menu.dcOn, menu.dcFe)),
                Layout.row("Cooler duty", () -> num(menu.coolerKw, 0), "kW")).showIf(this::tank);
        var gen = Layout.card("Generator cooling",
                Layout.row("Winding temperature", () -> num(menu.windingC, 1), "C"),
                Layout.meter("Winding against its 130 C limit", () -> menu.windingC / TurbineAux.WINDING_LIMIT_C,
                        () -> menu.windingC > TurbineAux.WINDING_LIMIT_C ? Status.ALARM : menu.windingC > TurbineAuxTrain.ALARM_WINDING_C ? Status.CAUTION : Status.NORMAL, () -> ""),
                Layout.row("Stator water flow", () -> pct(menu.statorFlow), "%"),
                Layout.row("Stator pumps", () -> pump(menu.statorRun, menu.statorOn, menu.acFe)),
                Layout.row("Hydrogen", () -> num(menu.h2Psig, 1) + " psig, " + num(menu.h2Kg, 1) + " kg"),
                Layout.row("Coolant temperature", () -> num(menu.coolantC, 1), "C")).showIf(this::cooling);
        var shaftCard = Layout.card("Shaft-driven pump",
                Layout.row("Shaft speed", () -> num(menu.rpm, 0), "rpm"),
                Layout.row("Delivery", () -> pct(menu.shaftFlow), "%").tip("The shaft-driven pump delivers nothing at standstill and its full flow near 90% of rated speed."),
                Layout.note("Bolted on the free shaft end of the generator and driven by the shaft. Its oil comes from the Main Oil Tank on the same train.").body(),
                Layout.note("The AC pump carries the shaft below about 90% of rated speed; this pump carries it above.")).showIf(this::shaftPump);
        var right = Layout.card("Cooling water and switches",
                Layout.row("Cold side", () -> grouped(menu.coldKg, 0) + " kg, " + num(menu.coldC, 1) + " C"),
                Layout.row("Warm side", () -> grouped(menu.hotKg, 0) + " kg, " + num(menu.hotC, 1) + " C"),
                Layout.controls(BwrStyle.SP1).add(acPump, 1).add(dcPump, 1).showIf(this::tank),
                Layout.controls(BwrStyle.SP1).add(statorPumps, 1).showIf(this::cooling),
                Layout.note("Cold water in on the left flange, warm out on the right, seen from the front.")).showIf(() -> !shaftPump());
        return Layout.column(BwrStyle.SP2,
                Layout.split(BwrStyle.SP2).add(oil, 3).add(gen, 3).add(shaftCard, 3).add(right, 2).stackBelow(360),
                Layout.note(() -> menu.status).body());
    }

    @Override
    protected UiElement buildTitleItems() {
        return Layout.titleItems(Layout.chip(() -> menu.attached ? Status.NORMAL : Status.OFF, () -> menu.attached ? "Attached" : "Not attached"));
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        if (acPump == null) return;
        acPump.setMessage(Component.literal(menu.acOn ? "AC pump on" : "AC pump off"));
        dcPump.setMessage(Component.literal(menu.dcOn ? "DC pump on" : "DC pump off"));
        statorPumps.setMessage(Component.literal(menu.statorOn ? "Stator pumps: ON" : "Stator pumps: off"));
        acPump.active = dcPump.active = statorPumps.active = menu.present;
    }
}
