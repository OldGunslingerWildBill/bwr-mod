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
        var readouts = Layout.card("Readouts",
                Layout.row("Train", () -> menu.hpCount + " HP / " + menu.lpCount + " LP / " + menu.generatorCount + " gen.")
                        .tip(() -> java.util.List.of(Component.literal(menu.hpCount + " HP sections, " + menu.lpCount + " LP sections, "
                                + menu.generatorCount + " generator(s) in this shaft train."))),
                Layout.row("Shaft speed", () -> num(menu.rpm, 0), "rpm"),
                Layout.row("Train shaft power", () -> num(menu.trainMW, 2), "MW"),
                // generator
                Layout.row("Electrical output", () -> num(menu.electricMW, 2), "MW").showIf(() -> menu.generator),
                Layout.meter("Electrical output against the 1,500 MW rating", () -> menu.electricMW / 1500.0, null, () -> "of 1,500 MW")
                        .showIf(() -> menu.generator),
                Layout.row("Generation", () -> big(menu.electricMW * 1e6 / EccsPower.WATTS_PER_FE_PER_TICK), "FE/t").showIf(() -> menu.generator),
                Layout.row("Stored energy", () -> big(menu.energy), "FE").showIf(() -> menu.generator),
                Layout.meter("Stored energy", () -> menu.energy / 2e9, null, () -> pct(menu.energy / 2e9) + "%").showIf(() -> menu.generator),
                // turbine section
                Layout.row("Section shaft power", () -> num(menu.shaftMW, 2), "MW").showIf(() -> !menu.generator),
                Layout.row("Section steam flow", () -> num(menu.flow, 1), "kg/s").showIf(() -> !menu.generator),
                Layout.row("Steam in", () -> num(menu.inletP, 1), "psia").showIf(() -> !menu.generator),
                Layout.row("Steam out", () -> num(menu.outletP, 1), "psia").showIf(() -> !menu.generator),
                Layout.row("Inlet temperature", () -> num(menu.inletC, 1), "C").showIf(() -> !menu.generator),
                Layout.row("Exhaust temperature", () -> num(menu.outletC, 1), "C").showIf(() -> !menu.generator),
                Layout.row("Legacy water", () -> big(menu.water), "kg").tip("Legacy water awaiting transfer.")
                        .showIf(() -> !menu.generator && !menu.hp && menu.water > 0));
        var connections = Layout.card("Connections",
                Layout.note("FE output: copper terminal on right side.").body().showIf(() -> menu.generator),
                Layout.note("Join the end shafts at the same height.").showIf(() -> menu.generator),
                Layout.note("1 FE/t = 13.4 W, 98.5% conversion.").showIf(() -> menu.generator),
                Layout.note(() -> menu.hp ? "Top: steam in. Left: HP exhaust." : "Top: steam in. Bottom: condenser.").body().showIf(() -> !menu.generator),
                Layout.note("Steam is controlled by the upstream valve.").showIf(() -> !menu.generator),
                Layout.note("HP exhaust supplies the LP sections.").showIf(() -> !menu.generator),
                Layout.note(() -> menu.hp ? "Pipe HP exhaust to LP inlets." : "Condenser: six blocks below, same facing.")
                        .showIf(() -> !menu.generator));
        return Layout.column(BwrStyle.SP2,
                Layout.split(BwrStyle.SP2).add(readouts, 3).add(connections, 2).stackBelow(360),
                Layout.note(() -> menu.status).body());
    }

    @Override
    protected UiElement buildTitleItems() {
        return Layout.titleItems(Layout.chip(() -> menu.running ? Status.NORMAL : Status.OFF, () -> menu.running ? "RUNNING" : "STOPPED"));
    }
}
