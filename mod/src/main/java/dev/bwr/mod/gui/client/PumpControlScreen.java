package dev.bwr.mod.gui.client;

import dev.bwr.mod.gui.PumpControlMenu;
import dev.bwr.mod.gui.client.style.BwrButton;
import dev.bwr.mod.gui.client.style.BwrEditBox;
import dev.bwr.mod.gui.client.style.BwrStyle;
import dev.bwr.mod.gui.client.style.BwrStyle.Status;
import dev.bwr.mod.gui.client.style.BwrStyle.Type;
import dev.bwr.mod.gui.client.style.Layout;
import dev.bwr.mod.gui.client.style.UiElement;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

import java.util.Locale;

/**
 * Compact manual controls for the powered pumps (RHR, LPCS, HPCS, SLC, feed and recirculation). Readouts come from the
 * server's pump, not client estimates. Controls lock while a computer holds the pump, and say so.
 */
public class PumpControlScreen extends BwrScreen<PumpControlMenu> {
    private static final String LOCKED = "Locked: a computer holds the controls. ";

    private BwrEditBox speed;
    private BwrButton apply;
    private BwrButton run;
    private BwrButton control;
    private BwrButton suction;
    private BwrButton mode;
    private double displayedTarget = Double.NaN;

    public PumpControlScreen(PumpControlMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, 392, 208);
    }

    @Override
    protected UiElement buildUi() {
        if (speed == null) {
            speed = new BwrEditBox(font, Component.literal("Speed percent"),
                    Component.literal("Type the speed demand as a percentage from 0 to 100, one decimal at most, then press Enter or Apply."),
                    6, PercentInput.FILTER, this::applySpeed);
            apply = BwrButton.primary("Apply", "Send the typed speed demand to the pump.", b -> applySpeed());
            run = BwrButton.secondary("Start", "Start the pump.",
                    b -> menu.sendCommand(PumpControlMenu.RUN, menu.running ? 0 : 1));
            control = BwrButton.secondary("Control: Panel", "Choose who controls this pump.",
                    b -> menu.sendCommand(PumpControlMenu.CONTROL, menu.recirculation ? (menu.control == 2 ? 0 : 2) : (menu.control + 1) % 3));
            suction = BwrButton.secondary("Suction", "Choose the water source.",
                    b -> menu.sendCommand(PumpControlMenu.SUCTION, menu.poolSuction ? 0 : 1));
            mode = BwrButton.secondary("Mode", "Choose what the pump does.",
                    b -> menu.sendCommand(PumpControlMenu.MODE, menu.shutdownCooling ? 0 : menu.poolCooling ? 2 : 1));
        }
        var status = Layout.card("Status",
                Layout.statusLine(() -> !menu.present ? Status.CAUTION : menu.running ? Status.NORMAL : Status.OFF,
                        () -> !menu.present ? "Waiting for pump..." : menu.running ? "Running" : "Stopped",
                        () -> menu.present ? menu.drive : ""),
                Layout.row("Target", () -> pct(menu.target), "%"),
                Layout.row("Actual", () -> pct(menu.actual), "%"),
                Layout.meter("Actual speed", () -> menu.actual, null, () -> ""),
                Layout.row(() -> menu.recirculation ? "Core-flow share" : "Water flow", () -> num(menu.flow, 1), "kg/s")
                        .tip(() -> java.util.List.of(Component.literal(menu.recirculation
                                ? "Core-flow contribution: this pump's share of core circulation."
                                : "Water flow delivered by the pump."))),
                Layout.row(() -> menu.recirculation ? "Vessel pressure" : "Pressure rise", () -> num(menu.pressure, 1), "psi")
                        .tip(() -> java.util.List.of(Component.literal(menu.recirculation
                                ? "Vessel pressure."
                                : "Discharge differential pressure."))),
                Layout.row("Water temperature", () -> Double.isFinite(menu.temperature) ? num(menu.temperature, 1) : "Unavailable",
                        () -> Double.isFinite(menu.temperature) ? "C" : ""),
                Layout.row(() -> menu.turbine ? "Drive" : "Stored energy", () -> menu.turbine ? "Steam" : big(menu.energy),
                        () -> menu.turbine ? "" : "FE"),
                Layout.row("Inlet buffer", () -> big(menu.buffer) + " / 2,000", "kg").showIf(() -> !menu.rhr && !menu.slc && !menu.recirculation),
                Layout.gap(BwrStyle.SP1),
                Layout.line(() -> menu.connection, Type.CAPTION));
        var controls = Layout.card("Control",
                Layout.line(() -> "Speed (%)", Type.CAPTION),
                Layout.controls(BwrStyle.SP1).add(speed, 1).fixed(apply, 52),
                Layout.gap(BwrStyle.SP1),
                Layout.controls(BwrStyle.SP1).add(run, 1),
                Layout.gap(BwrStyle.SP1),
                Layout.controls(BwrStyle.SP1).add(control, 1),
                Layout.gap(BwrStyle.SP1).showIf(() -> menu.eccs && !menu.slc || menu.rhr),
                Layout.controls(BwrStyle.SP1).add(suction, 1).showIf(() -> menu.eccs && !menu.slc),
                Layout.gap(BwrStyle.SP1).showIf(() -> menu.rhr),
                Layout.controls(BwrStyle.SP1).add(mode, 1).showIf(() -> menu.rhr),
                Layout.note("Suction: finite boron tank").showIf(() -> menu.slc),
                Layout.note(() -> menu.recirculation ? "Speed determines circulation demand." : "Suction: tank / piped water")
                        .showIf(() -> !menu.eccs),
                Layout.note("Computer owns the controls.").status(() -> Status.INFO).showIf(() -> menu.control == 2));
        var split = Layout.split(BwrStyle.SP2).stackBelow(360);
        split.add(status, 1);
        split.fixed(controls, 168);
        return split;
    }

    @Override
    protected UiElement buildTitleItems() {
        return Layout.titleItems(Layout.chip(() -> Status.INFO, () -> "COMPUTER CONTROL").showIf(() -> menu.present && menu.control == 2));
    }

    private Integer percent() {
        return PercentInput.perMille(speed.getValue());
    }

    private void applySpeed() {
        Integer value = percent();
        if (value != null) {
            menu.sendCommand(PumpControlMenu.SPEED, value);
        }
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        if (!speed.isFocused() && Double.compare(displayedTarget, menu.target) != 0) {
            speed.setValue(String.format(Locale.ROOT, "%.1f", menu.target * 100));
            displayedTarget = menu.target;
        }
        boolean locked = menu.control == 2;
        speed.setEditable(menu.present && !locked);
        speed.setInvalid(!speed.getValue().isEmpty() && !locked && percent() == null);
        apply.active = menu.present && !locked && percent() != null;
        apply.setTooltipText(locked ? LOCKED + "Take control back with the Control button." : "Send the typed speed demand to the pump.");
        run.active = menu.present && !locked;
        run.setMessage(Component.literal(menu.running ? "Stop" : "Start"));
        run.setTooltipText(locked ? LOCKED : menu.running ? "Stop the pump." : "Start the pump.");
        control.active = menu.present;
        control.setMessage(Component.literal("Control: " + switch (menu.control) {
            case 1 -> "Redstone";
            case 2 -> "Computer";
            default -> "Panel";
        }));
        control.setTooltipText(menu.recirculation
                ? "Who controls this pump: the panel or a computer. Click to switch."
                : "Who controls this pump: the panel, redstone or a computer. Click to cycle.");
        suction.active = menu.present && !locked;
        suction.setMessage(Component.literal(menu.poolSuction ? "Suction: Suppression pool" : "Suction: Tank / piped water"));
        suction.setTooltipText(locked ? LOCKED : menu.poolSuction
                ? "Water source: the suppression pool. Click to draw from the condensate tank or piped water instead."
                : "Water source: the condensate tank or piped water. Click to draw from the suppression pool instead.");
        mode.active = !locked;
        mode.setMessage(Component.literal(menu.shutdownCooling ? "Mode: Shutdown cooling"
                : menu.poolCooling ? "Mode: Pool cooling" : "Mode: Reactor injection"));
        mode.setTooltipText(locked ? LOCKED : "What the RHR pump does: reactor injection, shutdown cooling or suppression pool cooling. Click to step through them.");
    }
}
