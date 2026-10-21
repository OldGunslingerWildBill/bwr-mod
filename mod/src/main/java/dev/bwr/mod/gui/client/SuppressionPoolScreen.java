package dev.bwr.mod.gui.client;

import dev.bwr.mod.gui.SuppressionPoolMenu;
import dev.bwr.mod.gui.client.style.BwrButton;
import dev.bwr.mod.gui.client.style.BwrStyle;
import dev.bwr.mod.gui.client.style.BwrStyle.Status;
import dev.bwr.mod.gui.client.style.Layout;
import dev.bwr.mod.gui.client.style.UiElement;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/** Operator-selected filling/spray (concrete tank) or RHR duty (legacy dug pool), and the measured pool condition. */
public class SuppressionPoolScreen extends BwrScreen<SuppressionPoolMenu> {
    private FractionSlider rhr;
    private BwrButton fill;
    private BwrButton spray;

    public SuppressionPoolScreen(SuppressionPoolMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, 400, 200);
    }

    @Override
    protected UiElement buildUi() {
        if (rhr == null) {
            fill = BwrButton.secondary("Switch to regular fill", "Fill the pool from the return port, with no spray.",
                    b -> menu.sendCommand(SuppressionPoolMenu.CMD_FILL_MODE, 0));
            spray = BwrButton.secondary("Switch to tank spray", "Send the inlet water through the spray header, which condenses steam.",
                    b -> menu.sendCommand(SuppressionPoolMenu.CMD_FILL_MODE, 1));
            rhr = new FractionSlider("RHR duty", "Drag, or use the arrow keys, to set how hard the RHR cools the pool. The real cooling follows it.",
                    menu.rhrDuty, f -> menu.sendCommand(SuppressionPoolMenu.CMD_SET_RHR_DUTY, (int) Math.round(f * 1000)));
        }
        var water = Layout.card("Pool water",
                Layout.row("Water in the pool", () -> big(menu.massKg), "kg"),
                Layout.row("Capacity", () -> big(menu.capacityKg), "kg"),
                Layout.meter("Pool fill", () -> menu.massKg / Math.max(1, menu.capacityKg), null,
                        () -> pct(menu.massKg / Math.max(1, menu.capacityKg)) + "%"),
                Layout.row("Water temperature", () -> num(menu.temperatureC, 2), "C").status(() -> menu.boiling ? Status.CAUTION : Status.NONE)
                        .tip(() -> java.util.List.of(Component.literal(menu.boiling ? "The pool water is at its boiling point." : "The temperature of the pool water."))),
                Layout.row("Subcooling", () -> num(menu.subcoolingC, 1), "C").tip("How far the water is below its boiling point."),
                Layout.row("Cooling from the RHR", () -> num(menu.rhrDutyMW(), 2), "MW"),
                Layout.row("Natural cooling", () -> num(menu.passiveCoolingMW * 1000, 1), "kW"));
        var steam = Layout.card("Steam and spray",
                Layout.row("Steam received", () -> num(menu.steamIn, 2), "kg/s"),
                Layout.row("Steam inlet buffer", () -> num(menu.steamBuffer, 1), "kg"),
                Layout.row("Condensed in the pool", () -> pct(menu.condensationEffectiveness), "%"),
                Layout.row("Steam escaping", () -> num(menu.uncondensedSteamKgPerS, 2), "kg/s")
                        .status(() -> menu.uncondensedSteamKgPerS > 0 ? Status.CAUTION : Status.NONE)
                        .tip(() -> java.util.List.of(Component.literal("Steam that gets past the pool water and the spray without condensing."))),
                Layout.row("Spray header", () -> big(menu.sprayHeaderKg), "kg").tip("The amount of water waiting in the spray header."),
                Layout.row("Water spraying", () -> num(menu.sprayFlow, 1), "kg/s"),
                Layout.row("Steam captured", () -> num(menu.sprayCondensed, 1), "kg/s").tip("The steam the spray catches and condenses."));
        var controls = Layout.column(BwrStyle.SP2,
                Layout.controls(BwrStyle.SP2).label(() -> "Inlet mode: " + (menu.sprayMode ? "spray" : "regular fill"), 124)
                        .add(fill, 1).add(spray, 1).showIf(() -> menu.concrete),
                Layout.controls(BwrStyle.SP2).add(rhr, 1).showIf(() -> !menu.concrete));
        return Layout.column(BwrStyle.SP2,
                Layout.note("There is no pool controller here.").body().status(() -> Status.ALARM).showIf(() -> !menu.present),
                Layout.column(BwrStyle.SP1,
                        Layout.note("Tank shell incomplete.").body().status(() -> Status.ALARM),
                        Layout.note("Build a concrete floor, walls and roof.").body(),
                        Layout.note("Fit steam and water ports that face outward in the walls.").body(),
                        Layout.note("Sneak-click the controller for build details."))
                        .showIf(() -> menu.present && !menu.formed),
                Layout.column(BwrStyle.SP2,
                        Layout.split(BwrStyle.SP2, water, steam).stackBelow(360),
                        controls,
                        Layout.note(() -> menu.massKg <= 0 ? "The pool is empty. Pump water into the amber return port."
                                : "The spray uses the water you supply, and the heat stays in the pool.")
                                .status(() -> menu.massKg <= 0 ? Status.CAUTION : Status.NONE),
                        Layout.note("Leave room for spray water and condensed steam."))
                        .showIf(() -> menu.present && menu.formed));
    }

    @Override
    protected UiElement buildTitleItems() {
        return Layout.titleItems(Layout.chip(() -> menu.concrete ? Status.NONE : Status.INFO, () -> menu.concrete ? "" : "Legacy dug pool")
                .showIf(() -> menu.present && menu.formed));
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        fill.active = menu.formed && menu.sprayMode;
        spray.active = menu.formed && !menu.sprayMode;
        rhr.active = menu.formed && !menu.concrete;
        if (!rhr.isFocused()) {
            rhr.follow(menu.rhrDuty);
        }
        fill.setTooltipText(menu.sprayMode ? "Fill the pool from the return port, with no spray." : "The inlet is already in regular fill mode.");
        spray.setTooltipText(menu.sprayMode ? "The inlet is already in tank spray mode."
                : "Send the inlet water through the spray header, which condenses steam.");
    }
}
