package dev.bwr.mod.gui.client;

import dev.bwr.core.turbine.TurbineAux;
import dev.bwr.mod.gui.TurbineAuxMenu;
import dev.bwr.mod.gui.client.style.BwrButton;
import dev.bwr.mod.gui.client.style.BwrStyle;
import dev.bwr.mod.gui.client.style.BwrStyle.Status;
import dev.bwr.mod.gui.client.style.Layout;
import dev.bwr.mod.gui.client.style.UiElement;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/** A turbine auxiliary skid: the Main Oil Tank (kind 0), the Shaft-Driven Main Oil Pump (1) or the Generator Cooling Skid (2). */
public class TurbineAuxScreen extends BwrScreen<TurbineAuxMenu> {
    private BwrButton acPump, dcPump, statorPumps;

    public TurbineAuxScreen(TurbineAuxMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, 400, 210);
    }

    private boolean tank() { return menu.kind == 0; }
    private boolean shaftPump() { return menu.kind == 1; }
    private boolean cooling() { return menu.kind == 2; }

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
                Layout.row("Oil in the tank", () -> grouped(menu.oilFraction * TurbineAux.OIL_TANK_L, 0), "L").showIf(this::tank),
                Layout.meter("Oil level", () -> menu.oilFraction, () -> menu.oilFraction < TurbineAux.OIL_LEVEL_ALARM ? Status.CAUTION : Status.NORMAL,
                        () -> pct(menu.oilFraction) + "%").showIf(this::tank),
                Layout.row("Oil temperature", () -> num(menu.oilC, 1), "C").showIf(this::tank),
                Layout.row("Bearing temperature", () -> num(menu.bearingC, 1), "C"),
                Layout.row("Shaft speed", () -> num(menu.rpm, 0), "rpm"),
                Layout.row("Shaft pump delivery", () -> pct(menu.shaftFlow), "%").showIf(this::shaftPump)
                        .tip("The shaft-driven pump delivers nothing at standstill and its full flow near 90% of rated speed."),
                Layout.row("AC pump", () -> menu.acRun ? "running" : menu.acOn ? "no power" : "off").showIf(this::tank),
                Layout.row("DC pump", () -> menu.dcRun ? "running" : menu.dcOn ? "no power" : "off").showIf(this::tank),
                Layout.row("AC bus", () -> big(menu.acFe), "FE").showIf(this::tank),
                Layout.row("DC bus", () -> big(menu.dcFe), "FE").showIf(this::tank),
                Layout.row("Cooler duty", () -> num(menu.coolerKw, 0), "kW").showIf(this::tank)).showIf(() -> !cooling());
        var gen = Layout.card("Generator cooling",
                Layout.row("Winding temperature", () -> num(menu.windingC, 1), "C"),
                Layout.meter("Winding against its 130 C limit", () -> menu.windingC / TurbineAux.WINDING_LIMIT_C,
                        () -> menu.windingC > TurbineAux.WINDING_LIMIT_C ? Status.ALARM : menu.windingC > TurbineAux.WINDING_ALARM_C ? Status.CAUTION : Status.NORMAL, () -> ""),
                Layout.row("Electrical output", () -> num(menu.electricMW, 1), "MW"),
                Layout.row("Stator water flow", () -> pct(menu.statorFlow), "%"),
                Layout.row("Stator pumps", () -> menu.statorRun ? "running" : menu.statorOn ? "no power" : "off"),
                Layout.row("Hydrogen pressure", () -> num(menu.h2Psig, 1), "psig"),
                Layout.row("Hydrogen in the casing", () -> num(menu.h2Kg, 1), "kg"),
                Layout.row("Coolant temperature", () -> num(menu.coolantC, 1), "C"),
                Layout.row("Cooler duty", () -> num(menu.coolerKw, 0), "kW"),
                Layout.row("Supply bus", () -> big(menu.acFe), "FE")).showIf(this::cooling);
        var shaftNote = Layout.card("Shaft-driven pump",
                Layout.note("Bolted on the free shaft end of the generator and driven by the shaft. Its oil comes from the Main Oil Tank on the same train.").body(),
                Layout.note("The AC pump carries the shaft below about 90% of rated speed; this pump carries it above.")).showIf(this::shaftPump);
        var water = Layout.card("Cooling water",
                Layout.row("Cold side", () -> grouped(menu.coldKg, 0) + " kg at " + num(menu.coldC, 1), "C"),
                Layout.row("Hot side", () -> grouped(menu.hotKg, 0) + " kg at " + num(menu.hotC, 1), "C"),
                Layout.note("Cold water in on the left flange, warmed water out on the right, seen from the front.").body()).showIf(() -> !shaftPump());
        var controls = Layout.card("Switches",
                Layout.controls(BwrStyle.SP1).add(acPump, 1).add(dcPump, 1).showIf(this::tank),
                Layout.controls(BwrStyle.SP1).add(statorPumps, 1).showIf(this::cooling),
                Layout.note("Pumps run when you switch them on. Nothing switches them, or stops the turbine, for you.")).showIf(() -> !shaftPump());
        return Layout.column(BwrStyle.SP2,
                Layout.split(BwrStyle.SP2).add(oil, 3).add(gen, 3).add(shaftNote, 3).add(Layout.column(BwrStyle.SP2, water, controls), 2).stackBelow(360),
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
        acPump.setMessage(Component.literal(menu.acOn ? "AC pump: ON" : "AC pump: off"));
        dcPump.setMessage(Component.literal(menu.dcOn ? "DC pump: ON" : "DC pump: off"));
        statorPumps.setMessage(Component.literal(menu.statorOn ? "Stator pumps: ON" : "Stator pumps: off"));
        acPump.active = dcPump.active = statorPumps.active = menu.present;
    }
}
