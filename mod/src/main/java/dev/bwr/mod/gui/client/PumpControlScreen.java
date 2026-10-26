package dev.bwr.mod.gui.client;

import dev.bwr.core.display.DisplayUnits.Quantity;
import dev.bwr.mod.client.DisplayUnitReadouts;
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
 *
 * <p>The words follow one rule: a button that cycles through states says the state it is in, and its tooltip says what
 * a click does; a button that does one thing says what it does.
 */
public class PumpControlScreen extends BwrScreen<PumpControlMenu> {
    private static final String LOCKED = "This is locked because a computer is controlling the pump. ";
    private static final String TAKE_BACK = "Use the control button to take control back.";

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
            speed = new BwrEditBox(font, Component.literal("Speed demand in percent"),
                    Component.literal("Type the speed you want as a percentage from 0 to 100, with at most one decimal, then press Enter or Apply."),
                    6, PercentInput.FILTER, this::applySpeed);
            apply = BwrButton.primary("Apply", "Send the typed speed to the pump.", b -> applySpeed());
            run = BwrButton.secondary("Start pump", "Start the pump.",
                    b -> menu.sendCommand(PumpControlMenu.RUN, menu.running ? 0 : 1));
            control = BwrButton.secondary("Controlled from this panel", "Choose who controls this pump.",
                    b -> menu.sendCommand(PumpControlMenu.CONTROL, menu.recirculation ? (menu.control == 2 ? 0 : 2) : (menu.control + 1) % 3));
            suction = BwrButton.secondary("Suction", "Choose where the pump takes its water from.",
                    b -> menu.sendCommand(PumpControlMenu.SUCTION, menu.poolSuction ? 0 : 1));
            mode = BwrButton.secondary("Mode", "Choose what the pump does.",
                    b -> menu.sendCommand(PumpControlMenu.MODE, menu.shutdownCooling ? 0 : menu.poolCooling ? 2 : 1));
        }
        var status = Layout.card("Pump",
                Layout.statusLine(() -> !menu.present ? Status.CAUTION : menu.running ? Status.NORMAL : Status.OFF,
                        () -> !menu.present ? "Waiting for the pump to report"
                                : menu.running ? "Running at " + Math.round(menu.actual * 100) + "% of its " + Math.round(menu.target * 100) + "% target"
                                : "Stopped",
                        null),
                Layout.row("Target speed", () -> pct(menu.target), "%"),
                Layout.row("Actual speed", () -> pct(menu.actual), "%"),
                Layout.meter("Actual speed", () -> menu.actual, null, () -> ""),
                Layout.row(() -> menu.recirculation ? "Share of core flow" : "Water flow",
                        () -> num(DisplayUnitReadouts.waterFlow(menu.flow, menu.temperature), 1),
                        () -> DisplayUnitReadouts.unit(Quantity.WATER_FLOW))
                        .tip(() -> java.util.List.of(Component.literal(menu.recirculation
                                ? "How much of the core's circulation this pump supplies."
                                : "How much water the pump is delivering."))),
                Layout.row(() -> menu.recirculation ? "Vessel pressure" : "Pressure rise", () -> num(menu.pressure, 1), "psi")
                        .tip(() -> java.util.List.of(Component.literal(menu.recirculation
                                ? "The pressure in the reactor vessel."
                                : "How much pressure the pump adds between its suction and its discharge."))),
                Layout.row("Water temperature",
                        () -> Double.isFinite(menu.temperature) ? num(DisplayUnitReadouts.value(Quantity.TEMPERATURE, menu.temperature), 1) : "Unavailable",
                        () -> Double.isFinite(menu.temperature) ? DisplayUnitReadouts.unit(Quantity.TEMPERATURE) : ""),
                Layout.row("Drive", () -> menu.present ? menu.drive : "-"),
                Layout.row("Stored energy", () -> big(menu.energy), "FE").showIf(() -> !menu.turbine),
                Layout.row("Inlet buffer",
                        () -> big(DisplayUnitReadouts.value(Quantity.MASS, menu.buffer)) + " / " + big(DisplayUnitReadouts.value(Quantity.MASS, 2000.0)),
                        () -> DisplayUnitReadouts.unit(Quantity.MASS)).showIf(() -> !menu.rhr && !menu.slc && !menu.recirculation && !menu.drive.contains("RWCU")),
                Layout.gap(BwrStyle.SP1),
                Layout.line(() -> Words.sentence(menu.connection), Type.CAPTION));
        var controls = Layout.card("Controls",
                Layout.line(() -> "Speed demand (%)", Type.CAPTION),
                Layout.controls(BwrStyle.SP1).add(speed, 1).fixed(apply, 52),
                Layout.gap(BwrStyle.SP1),
                Layout.controls(BwrStyle.SP1).add(run, 1),
                Layout.gap(BwrStyle.SP1),
                Layout.controls(BwrStyle.SP1).add(control, 1),
                Layout.gap(BwrStyle.SP1).showIf(() -> menu.eccs && !menu.slc || menu.rhr),
                Layout.controls(BwrStyle.SP1).add(suction, 1).showIf(() -> menu.eccs && !menu.slc),
                Layout.gap(BwrStyle.SP1).showIf(() -> menu.rhr),
                Layout.controls(BwrStyle.SP1).add(mode, 1).showIf(() -> menu.rhr),
                Layout.note("Water comes from the finite boron tank.").showIf(() -> menu.slc),
                Layout.note(() -> menu.recirculation ? "The speed sets how much water the pump circulates."
                        : menu.drive.contains("RWCU") ? "Draws reactor water through the cleanup skid and back."
                        : "Water comes from the tank or the piped supply.")
                        .showIf(() -> !menu.eccs),
                Layout.note("A computer is in control.").status(() -> Status.INFO)
                        .showIf(() -> menu.control == 2));
        var split = Layout.split(BwrStyle.SP2).stackBelow(360);
        split.add(status, 1);
        split.fixed(controls, 168);
        return split;
    }

    @Override
    protected UiElement buildTitleItems() {
        return Layout.titleItems(Layout.chip(() -> Status.INFO, () -> "Computer in control").showIf(() -> menu.present && menu.control == 2));
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
        String why = !menu.present ? "The pump has not reported yet, so there is nothing to control. " : locked ? LOCKED : "";
        speed.setEditable(menu.present && !locked);
        speed.setInvalid(!speed.getValue().isEmpty() && !locked && percent() == null);
        apply.active = menu.present && !locked && percent() != null;
        apply.setTooltipText(locked ? why + TAKE_BACK
                : !menu.present ? why
                : percent() == null ? "The speed has to be a number from 0 to 100, with at most one decimal."
                : "Send the typed speed to the pump.");
        run.active = menu.present && !locked;
        run.setMessage(Component.literal(menu.running ? "Stop pump" : "Start pump"));
        run.setTooltipText(!why.isEmpty() ? why + (locked ? TAKE_BACK : "") : menu.running ? "Stop the pump." : "Start the pump.");
        control.active = menu.present;
        control.setMessage(Component.literal(switch (menu.control) {
            case 1 -> "Controlled by redstone";
            case 2 -> "Controlled by a computer";
            default -> "Controlled from this panel";
        }));
        control.setTooltipText(!menu.present ? why : menu.recirculation
                ? (menu.control == 2 ? "A computer controls this pump. Click to take control back to this panel."
                : "This panel controls this pump. Click to hand control to a computer.")
                : switch (menu.control) {
                    case 1 -> "A redstone signal starts and stops this pump. Click to hand control to a computer.";
                    case 2 -> "A computer controls this pump. Click to take control back to this panel.";
                    default -> "This panel controls this pump. Click to switch to redstone control.";
                });
        suction.active = menu.present && !locked;
        suction.setMessage(Component.literal(menu.poolSuction ? "Taking water from the pool" : "Taking tank or piped water"));
        suction.setTooltipText(!why.isEmpty() ? why + (locked ? TAKE_BACK : "") : menu.poolSuction
                ? "The pump is taking water from the suppression pool. Click to take it from the condensate tank or piped water instead."
                : "The pump is taking water from the condensate tank or piped water. Click to take it from the suppression pool instead.");
        mode.active = !locked;
        mode.setMessage(Component.literal(menu.shutdownCooling ? "In shutdown cooling mode"
                : menu.poolCooling ? "In pool cooling mode" : "In reactor injection mode"));
        mode.setTooltipText(locked ? why + TAKE_BACK : menu.shutdownCooling
                ? "The pump is cooling the shut-down reactor through the heat exchanger. Click to switch to reactor injection mode."
                : menu.poolCooling ? "The pump is cooling the suppression pool through the heat exchangers. Click to switch to shutdown cooling mode."
                : "The pump is putting water into the reactor. Click to switch to pool cooling mode.");
    }
}
