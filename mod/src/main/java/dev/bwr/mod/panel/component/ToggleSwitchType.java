package dev.bwr.mod.panel.component;

import dev.bwr.mod.BwrMod;

import java.util.List;

/**
 * Maintained toggle switch, 2x3 (spec 2.1): output "state" is 0 or 1 and stays
 * where the player left it, including across a restart. A click flips it;
 * scrolling up switches it on and down off.
 */
public final class ToggleSwitchType extends PanelInputType {
    public static final List<Footprint> FOOTPRINTS = List.of(Footprint.of(6, 8), Footprint.of(8, 10),
            // real size: a heavy duty toggle on its plate, about 6 cm
            Footprint.of(1, 1));
    private static final List<ConfigField> SCHEMA = List.of(
            ConfigField.color("Color", "component.bwr.field.lever_color"));
    private static final List<PortSpec> OUTPUTS = List.of(PortSpec.of("state"));

    ToggleSwitchType() {
        super(BwrMod.id("toggle_switch"));
    }

    @Override
    public int realSizes() {
        return 1;
    }

    @Override
    public List<Footprint> footprints() {
        return FOOTPRINTS;
    }

    @Override
    public ComponentData createData() {
        return new ToggleSwitchData();
    }

    @Override
    public List<ConfigField> configSchema() {
        return SCHEMA;
    }

    @Override
    public List<PortSpec> outputPorts(ComponentData data) {
        return OUTPUTS;
    }

    @Override
    public String labelPrefix() {
        return "TS";
    }

    @Override
    public boolean scrollable() {
        return true;
    }

    @Override
    public void onLoaded(PanelComponent component) {
        component.storeOutput(0, ((ToggleSwitchData) component.data()).on ? 1.0 : 0.0);
    }

    @Override
    public boolean operate(OperateContext context, OperateAction action, double value) {
        ToggleSwitchData data = (ToggleSwitchData) context.component().data();
        boolean next;
        switch (action) {
            case PRESS -> next = !data.on;
            case TURN -> next = value > 0;
            case SET -> next = value >= 0.5;
            default -> {
                return false;
            }
        }
        data.on = next;
        markSaved(context);
        context.setOutput(0, next ? 1.0 : 0.0);
        return true;
    }
}
