package dev.bwr.mod.gui.client;

import dev.bwr.core.display.DisplayUnits.Quantity;
import dev.bwr.mod.client.DisplayUnitReadouts;
import dev.bwr.mod.eccs.EccsPower;
import dev.bwr.mod.gui.PowerModuleMenu;
import dev.bwr.mod.gui.client.style.BwrStyle;
import dev.bwr.mod.gui.client.style.BwrStyle.Status;
import dev.bwr.mod.gui.client.style.Layout;
import dev.bwr.mod.gui.client.style.UiElement;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/** Readouts for any part of a large shaft module: an HP turbine, an LP turbine or the generator. */
public class PowerModuleScreen extends BwrScreen<PowerModuleMenu> {

    private dev.bwr.mod.gui.client.style.BwrButton gear;
    private int tab;

    public PowerModuleScreen(PowerModuleMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, 400, 184);
    }

    private boolean aux() { return menu.lubeGov || menu.bowGov || menu.genGov; }
    private static Status alarm(boolean on) { return on ? Status.ALARM : Status.NORMAL; }

    @Override
    protected UiElement buildUi() {
        var readouts = Layout.card("Readings",
                Layout.row("Train", () -> menu.hpCount + " HP / " + menu.lpCount + " LP / " + menu.generatorCount + " generator")
                        .tip(() -> java.util.List.of(Component.literal(menu.hpCount + " high-pressure (HP) sections, " + menu.lpCount + " low-pressure (LP) sections and "
                                + menu.generatorCount + " generator(s) in this shaft train."))),
                Layout.row("Shaft speed", () -> num(menu.rpm, 0), "rpm"),
                Layout.row("Train shaft power", () -> num(menu.trainMW, 2), "MW"),
                // generator
                Layout.row("Electrical output", () -> num(menu.electricMW, 2), () -> DisplayUnitReadouts.unit(Quantity.POWER_ELECTRIC))
                        .showIf(() -> menu.generator),
                Layout.meter("Electrical output against the 1,500 MW rating", () -> menu.electricMW / 1500.0, null,
                        () -> "of 1,500 " + DisplayUnitReadouts.unit(Quantity.POWER_ELECTRIC))
                        .showIf(() -> menu.generator),
                Layout.row("Power generated", () -> big(menu.electricMW * 1e6 / EccsPower.WATTS_PER_FE_PER_TICK), "FE/t").showIf(() -> menu.generator),
                Layout.row("Stored energy", () -> big(menu.energy), "FE").showIf(() -> menu.generator),
                Layout.meter("Stored energy", () -> menu.energy / 2e9, null, () -> pct(menu.energy / 2e9) + "%").showIf(() -> menu.generator),
                // turbine section
                Layout.row("Section shaft power", () -> num(menu.shaftMW, 2), "MW").showIf(() -> !menu.generator),
                Layout.row("Section steam flow", () -> num(DisplayUnitReadouts.value(Quantity.MASS_FLOW, menu.flow), 1),
                        () -> DisplayUnitReadouts.unit(Quantity.MASS_FLOW)).showIf(() -> !menu.generator),
                Layout.row("Steam pressure in", () -> DisplayUnitReadouts.pressure(Quantity.PRESSURE_ABS, menu.inletP, 1, false), () -> DisplayUnitReadouts.pressureUnit(Quantity.PRESSURE_ABS, menu.inletP)).showIf(() -> !menu.generator),
                Layout.row("Steam pressure out", () -> DisplayUnitReadouts.pressure(Quantity.PRESSURE_ABS, menu.outletP, 1, false), () -> DisplayUnitReadouts.pressureUnit(Quantity.PRESSURE_ABS, menu.outletP)).showIf(() -> !menu.generator),
                Layout.row("Inlet temperature", () -> num(DisplayUnitReadouts.value(Quantity.TEMPERATURE, menu.inletC), 1),
                        () -> DisplayUnitReadouts.unit(Quantity.TEMPERATURE)).showIf(() -> !menu.generator),
                Layout.row("Exhaust temperature", () -> num(DisplayUnitReadouts.value(Quantity.TEMPERATURE, menu.outletC), 1),
                        () -> DisplayUnitReadouts.unit(Quantity.TEMPERATURE)).showIf(() -> !menu.generator),
                Layout.row("Legacy water", () -> big(DisplayUnitReadouts.value(Quantity.MASS, menu.water)),
                        () -> DisplayUnitReadouts.unit(Quantity.MASS)).tip("Old-style water that is waiting to be transferred.")
                        .showIf(() -> !menu.generator && !menu.hp && menu.water > 0));
        var connections = Layout.card("Connections",
                Layout.note("The FE output is the copper terminal on the right side.").body().showIf(() -> menu.generator),
                Layout.note("Join the end shafts at the same height.").showIf(() -> menu.generator),
                Layout.note("1 FE/t is 13.4 W, at 98.5% conversion.").showIf(() -> menu.generator),
                Layout.note(() -> menu.hp ? "Steam comes in at the top. The high-pressure exhaust leaves at the left." : "Steam comes in at the top and leaves at the bottom, to the condenser.").body().showIf(() -> !menu.generator),
                Layout.note("The valve upstream controls the steam.").showIf(() -> !menu.generator),
                Layout.note("The high-pressure exhaust feeds the low-pressure sections.").showIf(() -> !menu.generator),
                Layout.note(() -> menu.hp ? "Pipe the high-pressure exhaust to the low-pressure inlets." : "The condenser goes six blocks below, facing the same way.")
                        .showIf(() -> !menu.generator));
        if (gear == null) {
            gear = dev.bwr.mod.gui.client.style.BwrButton.secondary("Turning gear",
                    "Engage or release the turning gear. It rolls the shaft at 3 rpm to keep a hot rotor straight, draws FE from the Main Oil Tank's AC bus, and cannot be engaged above 10 rpm. Nothing engages it for you.",
                    b -> menu.sendCommand(PowerModuleMenu.CMD_TURNING_GEAR, menu.gearOn ? 0 : 1));
        }
        var left = Layout.card("Lube oil and bearings",
                Layout.statusLine(() -> alarm((menu.alarms & 3) != 0), () -> menu.lubeGov ? (menu.oilTank ? "Lube oil" : "No oil tank on this train") : "Not fitted",
                        () -> menu.lubeGov ? DisplayUnitReadouts.pressure(Quantity.PRESSURE_GAUGE, menu.oilPsig, 1, false) + " " + DisplayUnitReadouts.pressureUnit(Quantity.PRESSURE_GAUGE, menu.oilPsig) : ""),
                Layout.row("Oil temperature", () -> Double.isNaN(menu.oilC) ? "-" : num(DisplayUnitReadouts.value(Quantity.TEMPERATURE, menu.oilC), 1),
                        () -> DisplayUnitReadouts.unit(Quantity.TEMPERATURE)).showIf(() -> menu.lubeGov),
                Layout.row("Oil level", () -> Double.isNaN(menu.oilLevel) ? "-" : pct(menu.oilLevel), "%").showIf(() -> menu.lubeGov),
                Layout.row("Oil film", () -> pct(menu.oilFilm), "%").showIf(() -> menu.lubeGov),
                Layout.row("Bearing temperature", () -> num(DisplayUnitReadouts.value(Quantity.TEMPERATURE, menu.bearingC), 1),
                        () -> DisplayUnitReadouts.unit(Quantity.TEMPERATURE)).showIf(() -> menu.lubeGov),
                Layout.row("Shaft vibration", () -> num(menu.vibration, 1), "mm/s").showIf(() -> menu.bowGov),
                Layout.row("Rotor temperature", () -> num(DisplayUnitReadouts.value(Quantity.TEMPERATURE, menu.rotorC), 0),
                        () -> DisplayUnitReadouts.unit(Quantity.TEMPERATURE)).showIf(() -> menu.bowGov && !menu.generator),
                Layout.row("Rotor bow", () -> pct(menu.bow), "%").showIf(() -> menu.bowGov && !menu.generator),
                Layout.note("Nothing here is fitted to this train.").showIf(() -> !aux()));
        var right = Layout.card("Gear and generator",
                Layout.row("Turning gear", () -> menu.gearRolling ? "rolling" : menu.gearOn ? (menu.gearFed ? "engaged, over 10 rpm" : "engaged, no power") : "off")
                        .showIf(() -> menu.generator),
                Layout.controls(BwrStyle.SP1).add(gear, 1).showIf(() -> menu.generator),
                Layout.row("Winding", () -> num(DisplayUnitReadouts.value(Quantity.TEMPERATURE, menu.windingC), 1),
                        () -> DisplayUnitReadouts.unit(Quantity.TEMPERATURE)).showIf(() -> menu.generator && menu.genGov),
                Layout.row("Stator flow", () -> pct(menu.statorFlow), "%").showIf(() -> menu.generator && menu.genGov),
                Layout.row("Hydrogen", () -> Double.isNaN(menu.h2Psig) ? "-" : DisplayUnitReadouts.pressure(Quantity.PRESSURE_GAUGE, menu.h2Psig, 1, false), () -> DisplayUnitReadouts.pressureUnit(Quantity.PRESSURE_GAUGE, menu.h2Psig)).showIf(() -> menu.generator && menu.genGov),
                Layout.note("Rolls the shaft at 3 rpm below 10 rpm, on the oil tank's AC bus.").body().showIf(() -> menu.generator),
                Layout.note("The turning gear and the winding are on the generator's screen.").body().showIf(() -> !menu.generator),
                Layout.note("Alarms are readings. Nothing trips the turbine.").showIf(this::aux));
        var main = Layout.split(BwrStyle.SP2).add(readouts, 3).add(connections, 2).stackBelow(360).showIf(() -> tab == 0);
        var auxTab = Layout.split(BwrStyle.SP2).add(left, 1).add(right, 1).stackBelow(360).showIf(() -> tab == 1);
        return Layout.column(BwrStyle.SP2, main, auxTab, Layout.note(() -> Words.sentence(menu.status)).body());
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        if (gear != null) {
            gear.setMessage(Component.literal(menu.gearOn ? "Turning gear: ON" : "Turning gear: off"));
            gear.active = menu.present && menu.generator;
        }
    }

    @Override
    protected UiElement buildTitleItems() {
        return Layout.titleItems(Layout.chip(() -> menu.running ? Status.NORMAL : Status.OFF, () -> menu.running ? "Running" : "Stopped"),
                Layout.tabs(new String[]{"READINGS", "AUXILIARIES"},
                        new String[]{"The steam, speed and power readings.", "Lube oil, bearings, rotor bow, the turning gear and the generator's cooling."},
                        () -> tab, i -> tab = i));
    }
}
