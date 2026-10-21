package dev.bwr.mod.gui.client;

import dev.bwr.core.turbine.CoolingWaterUnit.Design;
import dev.bwr.mod.gui.CoolingMenu;
import dev.bwr.mod.gui.client.style.BwrButton;
import dev.bwr.mod.gui.client.style.BwrEditBox;
import dev.bwr.mod.gui.client.style.BwrStyle;
import dev.bwr.mod.gui.client.style.BwrStyle.Status;
import dev.bwr.mod.gui.client.style.BwrStyle.Type;
import dev.bwr.mod.gui.client.style.Layout;
import dev.bwr.mod.gui.client.style.UiElement;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/** Cooling towers, circulating and makeup pumps and the screened intake, on one layout. Pumps and mechanical towers take a speed. */
public class CoolingScreen extends BwrScreen<CoolingMenu> {
    private BwrEditBox speed;
    private BwrButton apply;
    private BwrButton stop;
    private boolean seeded;

    public CoolingScreen(CoolingMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, 400, 192);
    }

    private boolean tower() {
        return menu.design.tower;
    }

    private boolean motor() {
        return menu.design.watts > 0;
    }

    private String statusText() {
        if (!menu.ready) {
            return "Waiting for complete, loaded structure";
        }
        if (menu.design == Design.INTAKE) {
            return menu.submerged ? "Submerged intake" : "Intake requires source water";
        }
        return menu.actual > 0 ? "Operating" : "Stopped / no power";
    }

    private Status statusKind() {
        if (!menu.ready) {
            return Status.CAUTION;
        }
        if (menu.design == Design.INTAKE) {
            return menu.submerged ? Status.NORMAL : Status.CAUTION;
        }
        return menu.actual > 0 ? Status.NORMAL : Status.OFF;
    }

    @Override
    protected UiElement buildUi() {
        if (speed == null) {
            speed = new BwrEditBox(font, Component.literal("Speed percent"),
                    Component.literal("Type the speed as a percentage from 0 to 100, one decimal at most, then press Enter or Apply."),
                    5, PercentInput.FILTER, this::applyTyped);
            apply = BwrButton.primary("Apply", "Send the typed speed to the machine.", b -> applyTyped());
            stop = BwrButton.secondary("Stop", "Set the speed to 0 %.", b -> menu.sendCommand(0, 0));
        }
        var water = Layout.card("Water",
                Layout.row(() -> tower() ? "Hot water" : "Inlet water", () -> big(menu.input), "kg").tip(() -> java.util.List.of(Component.literal(tower() ? "Hot-water inventory." : "Inlet inventory."))),
                Layout.row(() -> tower() ? "Cold basin" : "Outlet water", () -> big(menu.output), "kg").tip(() -> java.util.List.of(Component.literal(tower() ? "Cold basin inventory." : "Outlet inventory."))),
                Layout.row("Water processed", () -> num(menu.flow, 1), "kg/s"),
                Layout.row("Rated capacity", () -> big(menu.design.flow), "kg/s"),
                Layout.row("Heat rejected", () -> num(menu.heat, 1), "MW").showIf(this::tower),
                Layout.row("Makeup demand", () -> num(menu.loss, 1), "kg/s").showIf(this::tower),
                Layout.row("Connected water sources", () -> Integer.toString(menu.connectedSources)).showIf(() -> !tower() && motor()),
                Layout.row("Water in / out", () -> num(menu.inputC, 1) + " -> " + num(menu.outputC, 1), "C"),
                Layout.note("Combined supply is limited by the pump rating.").showIf(() -> !tower() && motor()),
                Layout.note(() -> menu.design == Design.INTAKE ? "Passive screen - connect a makeup pump downstream."
                        : "Natural draft - no motor or fan required.").showIf(() -> !motor()));
        var drive = Layout.card(() -> tower() ? "Fan" : "Motor",
                Layout.row(() -> tower() ? "Fan speed" : "Motor speed", () -> num(menu.actual * 100, 1), "%"),
                Layout.row(() -> tower() ? "Fan power" : "Motor power", () -> big(menu.draw), "FE/t"),
                Layout.gap(BwrStyle.SP1),
                Layout.line(() -> "Speed (%)", Type.CAPTION),
                Layout.controls(BwrStyle.SP1).add(speed, 1).fixed(apply, 56),
                Layout.gap(BwrStyle.SP1),
                Layout.controls(BwrStyle.SP1).add(stop, 1)).showIf(this::motor);
        return Layout.column(BwrStyle.SP2,
                Layout.statusLine(this::statusKind, this::statusText, null),
                Layout.split(BwrStyle.SP2, water, drive).stackBelow(360),
                Layout.note(() -> menu.design == Design.INTAKE ? "Source water at two outer sides; lakebed mounting is OK."
                        : motor() ? "Connect water pipes to flanges; FE to electrical box."
                        : "Connect water pipes to the three basin flanges."));
    }

    private Integer value() {
        return PercentInput.perMille(speed.getValue());
    }

    private void applyTyped() {
        Integer n = value();
        if (n != null) {
            menu.sendCommand(0, n);
        }
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        if (menu.present && !seeded) {
            speed.setValue(num(menu.target * 100, 1));
            seeded = true;
        }
        speed.setInvalid(!speed.getValue().isEmpty() && value() == null);
        apply.active = menu.present && value() != null;
        stop.active = menu.present;
    }
}
