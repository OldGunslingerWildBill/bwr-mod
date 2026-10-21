package dev.bwr.mod.gui.client;

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

    public PowerModuleScreen(PowerModuleMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, 400, 184);
    }

    @Override
    protected UiElement buildUi() {
        var readouts = Layout.card("Readings",
                Layout.row("Train", () -> menu.hpCount + " HP / " + menu.lpCount + " LP / " + menu.generatorCount + " generator")
                        .tip(() -> java.util.List.of(Component.literal(menu.hpCount + " high-pressure (HP) sections, " + menu.lpCount + " low-pressure (LP) sections and "
                                + menu.generatorCount + " generator(s) in this shaft train."))),
                Layout.row("Shaft speed", () -> num(menu.rpm, 0), "rpm"),
                Layout.row("Train shaft power", () -> num(menu.trainMW, 2), "MW"),
                // generator
                Layout.row("Electrical output", () -> num(menu.electricMW, 2), "MW").showIf(() -> menu.generator),
                Layout.meter("Electrical output against the 1,500 MW rating", () -> menu.electricMW / 1500.0, null, () -> "of 1,500 MW")
                        .showIf(() -> menu.generator),
                Layout.row("Power generated", () -> big(menu.electricMW * 1e6 / EccsPower.WATTS_PER_FE_PER_TICK), "FE/t").showIf(() -> menu.generator),
                Layout.row("Stored energy", () -> big(menu.energy), "FE").showIf(() -> menu.generator),
                Layout.meter("Stored energy", () -> menu.energy / 2e9, null, () -> pct(menu.energy / 2e9) + "%").showIf(() -> menu.generator),
                // turbine section
                Layout.row("Section shaft power", () -> num(menu.shaftMW, 2), "MW").showIf(() -> !menu.generator),
                Layout.row("Section steam flow", () -> num(menu.flow, 1), "kg/s").showIf(() -> !menu.generator),
                Layout.row("Steam pressure in", () -> num(menu.inletP, 1), "psia").showIf(() -> !menu.generator),
                Layout.row("Steam pressure out", () -> num(menu.outletP, 1), "psia").showIf(() -> !menu.generator),
                Layout.row("Inlet temperature", () -> num(menu.inletC, 1), "C").showIf(() -> !menu.generator),
                Layout.row("Exhaust temperature", () -> num(menu.outletC, 1), "C").showIf(() -> !menu.generator),
                Layout.row("Legacy water", () -> big(menu.water), "kg").tip("Old-style water that is waiting to be transferred.")
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
        return Layout.column(BwrStyle.SP2,
                Layout.split(BwrStyle.SP2).add(readouts, 3).add(connections, 2).stackBelow(360),
                Layout.note(() -> Words.sentence(menu.status)).body());
    }

    @Override
    protected UiElement buildTitleItems() {
        return Layout.titleItems(Layout.chip(() -> menu.running ? Status.NORMAL : Status.OFF, () -> menu.running ? "Running" : "Stopped"));
    }
}
