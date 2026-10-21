package dev.bwr.mod.gui.client;

import dev.bwr.mod.gui.RecirculationPumpMenu;
import dev.bwr.mod.gui.client.style.BwrButton;
import dev.bwr.mod.gui.client.style.BwrStyle;
import dev.bwr.mod.gui.client.style.BwrStyle.Status;
import dev.bwr.mod.gui.client.style.Layout;
import dev.bwr.mod.gui.client.style.UiElement;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

import java.util.Locale;

/**
 * Recirculation pump control, {@code SPEC.md} section 4.2.
 *
 * <p>The slider commands a target; the pump ramps to it through a three second
 * spin-up and an eleven second coastdown, so the actual speed lags the demand
 * and the screen shows both. When the pump cannot get there on the energy it is
 * receiving, the mismatch is spelled out — "commanded 80% / limited to 50%
 * (power)" — rather than the slider silently doing nothing.
 *
 * <p>The CC toggle decides who writes the demand. While a computer holds it the
 * slider is greyed out and follows what Lua commanded, which removes the
 * confusing case where a player drags a control that is being overwritten
 * underneath them.
 */
public class RecirculationPumpScreen extends BwrScreen<RecirculationPumpMenu> {

    private FractionSlider slider;
    private BwrButton controlToggle;

    public RecirculationPumpScreen(RecirculationPumpMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, 376, 176);
    }

    @Override
    protected UiElement buildUi() {
        if (slider == null) {
            slider = new FractionSlider("Speed demand", "Drag, or use the arrow keys, to set the speed you want. The pump speeds up or slows down to reach it.",
                    menu.targetSpeedFraction,
                    f -> menu.sendCommand(RecirculationPumpMenu.CMD_SET_TARGET, toPerMille(f)));
            controlToggle = BwrButton.secondary("Controlled from this panel", "Hand the speed demand to a computer, or take it back.",
                    b -> menu.sendCommand(RecirculationPumpMenu.CMD_SET_COMPUTER_CONTROL, menu.computerControlled ? 0 : 1));
        }
        var pump = Layout.card("Pump",
                Layout.row("Speed demanded", () -> pct(menu.targetSpeedFraction), "%"),
                Layout.row("Actual speed", () -> pct(menu.actualSpeedFraction), "%")
                        .status(() -> menu.actualSpeedFraction > 0.0 ? Status.NORMAL : Status.NONE),
                Layout.meter("Actual speed", () -> menu.actualSpeedFraction, null, () -> ""),
                Layout.row("Power-limited max", () -> pct(menu.maxAchievableSpeedFraction), "%")
                        .status(() -> menu.powerLimited ? Status.CAUTION : Status.NONE),
                Layout.row("Stored energy", () -> String.format(Locale.ROOT, "%,.0f", menu.energyStoredFe), "FE"),
                Layout.row("Energy capacity", () -> String.format(Locale.ROOT, "%,.0f", menu.energyCapacityFe), "FE"),
                Layout.row("Reactor", () -> menu.attachedToReactor ? "Connected" : "Not connected")
                        .status(() -> menu.attachedToReactor ? Status.NORMAL : Status.CAUTION));
        var demand = Layout.card("Demand",
                Layout.controls(BwrStyle.SP1).add(slider, 1),
                Layout.gap(BwrStyle.SP2),
                Layout.controls(BwrStyle.SP1).add(controlToggle, 1),
                Layout.gap(BwrStyle.SP1),
                Layout.note(() -> menu.computerControlled ? "A computer is setting the speed, and the slider follows it." : "You set the speed from this panel."),
                Layout.note(() -> String.format(Locale.ROOT, "At full speed it uses %,.0f FE per tick, and it stops if it gets less than 1,000 FE per tick.",
                        RecirculationPumpMenu.maxDrawFePerTick())));
        return Layout.column(BwrStyle.SP2,
                Layout.note("No pump here.").status(() -> Status.ALARM).showIf(() -> !menu.present),
                Layout.split(BwrStyle.SP2, pump, demand).stackBelow(340).showIf(() -> menu.present),
                Layout.note(() -> menu.powerLimited
                        ? String.format(Locale.ROOT, "Asked for %.0f%%, but limited to %.0f%% by the available power",
                        menu.targetSpeedFraction * 100.0, menu.maxAchievableSpeedFraction * 100.0)
                        : String.format(Locale.ROOT, "Asked for %.0f%%; running at %.0f%% now",
                        menu.targetSpeedFraction * 100.0, menu.actualSpeedFraction * 100.0))
                        .body().status(() -> menu.powerLimited ? Status.CAUTION : Status.NONE).showIf(() -> menu.present));
    }

    private static int toPerMille(double fraction) {
        return (int) Math.round(Math.max(0.0, Math.min(1.0, fraction)) * 1000.0);
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        // The demand is one field with two writers. Whoever does not hold it
        // follows it, so the two never show different numbers.
        //
        // "Whoever does not hold it" includes the player who has not touched
        // the slider yet, which is why this is not gated on computerControlled
        // alone. init() seeds the slider from menu.targetSpeedFraction, but on
        // the client that field is still 0.0 at that moment: the packet
        // listener builds the menu and calls init() straight away, and the
        // first snapshot cannot arrive until the server's next
        // broadcastChanges(). So a panel-controlled pump running at 100% opened
        // with its slider reading 0%, and one click anywhere on the track sent
        // that 0% straight back and dropped core flow. follow() suppresses the
        // echo packet, so following here can never command anything; it stops
        // once the player takes the widget, which is what isFocused() means
        // after a click.
        slider.active = !menu.computerControlled;
        if (menu.computerControlled || !slider.isFocused()) {
            slider.follow(menu.targetSpeedFraction);
        }
        controlToggle.setMessage(Component.literal(menu.computerControlled ? "Controlled by a computer" : "Controlled from this panel"));
        controlToggle.setTooltipText(menu.computerControlled ? "A computer holds the speed demand. Click to take it back to this panel."
                : "This panel holds the speed demand. Click to hand it to a computer.");
    }
}
