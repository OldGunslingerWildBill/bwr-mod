package dev.bwr.mod.gui.client;

import dev.bwr.mod.gui.CondensateTankMenu;
import dev.bwr.mod.gui.client.style.BwrStyle;
import dev.bwr.mod.gui.client.style.BwrStyle.Status;
import dev.bwr.mod.gui.client.style.Layout;
import dev.bwr.mod.gui.client.style.UiElement;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/** Tank dimensions come from the player's completed shell. */
public class CondensateTankScreen extends BwrScreen<CondensateTankMenu> {

    public CondensateTankScreen(CondensateTankMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, 400, 168);
    }

    @Override
    protected UiElement buildUi() {
        var water = Layout.card("Water",
                Layout.row("Water stored", () -> big(menu.stored), "kg"),
                Layout.row("Capacity", () -> big(menu.capacity), "kg"),
                Layout.meter("Tank fill", () -> menu.capacity > 0 ? menu.stored / menu.capacity : 0, null,
                        () -> pct(menu.capacity > 0 ? menu.stored / menu.capacity : 0) + "%"),
                Layout.gap(BwrStyle.SP1),
                Layout.row("Temperature", () -> num(menu.temperature, 0), "C"));
        var tank = Layout.card("Tank",
                Layout.row("Diameter / height", () -> menu.diameter + " / " + menu.height, "blocks").showIf(() -> menu.assembled),
                Layout.row("Construction blocks", () -> big(menu.blocks), "").showIf(() -> menu.assembled),
                Layout.note("To change its size, take the shell apart and rebuild it.").showIf(() -> menu.assembled),
                Layout.row("Base width", () -> "odd, 3 to 15", "blocks").tip("The base is square, and its width is an odd number of blocks from 3 to 15.").showIf(() -> !menu.assembled),
                Layout.row("Height", () -> "3 to 24", "blocks").showIf(() -> !menu.assembled),
                Layout.note("Build the floor, four walls and the roof from tank blocks, and leave the inside empty. The last block turns it into a tank by itself.")
                        .showIf(() -> !menu.assembled));
        return Layout.column(BwrStyle.SP2,
                Layout.statusLine(() -> menu.assembled ? (menu.ready ? Status.NORMAL : Status.CAUTION) : Status.INFO,
                        () -> menu.assembled ? (menu.ready ? "The tank is built and ready" : "The tank is incomplete, or part of it is in an unloaded chunk")
                                : "Build a complete shell to make a tank.", null),
                Layout.split(BwrStyle.SP2, water, tank).stackBelow(360),
                Layout.note("Water pipes connect to the four side flanges, one block above the base."));
    }
}
