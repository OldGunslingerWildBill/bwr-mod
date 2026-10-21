package dev.bwr.mod.gui.client;

import dev.bwr.mod.gui.TurbineValveMenu;
import dev.bwr.mod.gui.client.style.BwrButton;
import dev.bwr.mod.gui.client.style.BwrEditBox;
import dev.bwr.mod.gui.client.style.BwrStyle;
import dev.bwr.mod.gui.client.style.BwrStyle.Type;
import dev.bwr.mod.gui.client.style.Layout;
import dev.bwr.mod.gui.client.style.UiElement;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/** A turbine control valve (any opening from 0 to 100 %) or stop valve (fully open or fully closed). */
public class TurbineValveScreen extends BwrScreen<TurbineValveMenu> {
    private BwrEditBox percent;
    private BwrButton apply;
    private BwrButton less;
    private BwrButton more;
    private BwrButton open;
    private BwrButton close;
    private boolean seeded;

    public TurbineValveScreen(TurbineValveMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, 368, 160);
    }

    @Override
    protected UiElement buildUi() {
        if (percent == null) {
            percent = new BwrEditBox(font, Component.literal("Valve opening in percent"),
                    Component.literal("Type how far open you want the valve as a percentage from 0 to 100, with at most one decimal, then press Enter or Apply."),
                    5, PercentInput.FILTER, this::applyTyped);
            apply = BwrButton.primary("Apply", "Send the typed opening to the valve.", b -> applyTyped());
            less = BwrButton.secondary("Less (-0.1)", "Close the valve a little: lower the requested opening by 0.1 percentage points.", b -> adjust(-1));
            more = BwrButton.secondary("More (+0.1)", "Open the valve a little: raise the requested opening by 0.1 percentage points.", b -> adjust(1));
            close = BwrButton.secondary("Close valve", "Tell the valve to close all the way (0%).", b -> menu.sendCommand(0, 0));
            open = BwrButton.secondary("Open valve", "Tell the valve to open all the way (100%).", b -> menu.sendCommand(0, 1000));
        }
        var position = Layout.card("Position",
                Layout.row("Requested opening", () -> num(menu.target * 100, 1), "%"),
                Layout.row("Actual opening", () -> num(menu.position * 100, 1), "%"),
                Layout.meter("Valve position", () -> menu.position, null, () -> ""),
                Layout.gap(BwrStyle.SP1),
                Layout.row("Steam flow", () -> grouped(menu.flow, 2), "kg/s"));
        var command = Layout.card("Command",
                Layout.note(() -> menu.stop ? "A stop valve is either fully open or fully closed." : "Opening you want (%)"),
                Layout.controls(BwrStyle.SP1).add(percent, 1).fixed(apply, 56).showIf(() -> !menu.stop),
                Layout.gap(BwrStyle.SP1).showIf(() -> !menu.stop),
                Layout.controls(BwrStyle.SP1).add(less, 1).add(more, 1).showIf(() -> !menu.stop),
                Layout.gap(BwrStyle.SP1),
                Layout.controls(BwrStyle.SP1).add(close, 1).add(open, 1));
        return Layout.column(BwrStyle.SP2,
                Layout.split(BwrStyle.SP2, position, command).stackBelow(340),
                Layout.note("This valve controls the steam supply through this line. Connect pipes to its two opposite flanges."));
    }

    private Integer value() {
        return PercentInput.perMille(percent.getValue());
    }

    private void applyTyped() {
        Integer n = value();
        if (n != null) {
            menu.sendCommand(0, n);
        }
    }

    private void adjust(int step) {
        int p = Math.max(0, Math.min(1000, (int) Math.round(menu.target * 1000) + step));
        percent.setValue(num(p / 10.0, 1));
        menu.sendCommand(0, p);
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        if (menu.present && !seeded) {
            percent.setValue(num(menu.target * 100, 1));
            seeded = true;
        }
        percent.setInvalid(!percent.getValue().isEmpty() && value() == null);
        apply.active = menu.present && value() != null;
        less.active = menu.present;
        more.active = menu.present;
        close.active = menu.present;
        open.active = menu.present;
    }
}
