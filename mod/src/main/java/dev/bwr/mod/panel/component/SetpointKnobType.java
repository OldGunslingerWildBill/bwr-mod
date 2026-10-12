package dev.bwr.mod.panel.component;

import dev.bwr.mod.BwrMod;

import java.util.List;

/**
 * Setpoint knob, 3x3 (spec 2.1): a continuous 0 to 100 percent output "value".
 * Scrolling over it moves it by the configured step; clicking the right half
 * raises it and the left half lowers it by five steps; the wrench or CC can
 * set it exactly (SET).
 */
public final class SetpointKnobType extends PanelInputType {
    public static final List<Footprint> FOOTPRINTS = List.of(Footprint.of(3, 3), Footprint.of(4, 4));
    /** A click moves the knob by this many steps. */
    public static final double CLICK_STEPS = 5.0;
    private static final List<ConfigField> SCHEMA = List.of(
            ConfigField.decimal("Step", "component.bwr.field.step", SetpointKnobData.MIN_STEP, SetpointKnobData.MAX_STEP),
            ConfigField.color("Color", "component.bwr.field.knob_color"));
    private static final List<PortSpec> OUTPUTS = List.of(PortSpec.of("value"));

    SetpointKnobType() {
        super(BwrMod.id("setpoint_knob"));
    }

    @Override
    public List<Footprint> footprints() {
        return FOOTPRINTS;
    }

    @Override
    public ComponentData createData() {
        return new SetpointKnobData();
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
        return "SK";
    }

    @Override
    public double clickValue(PanelComponent component, double cellX, double cellY) {
        return cellX < component.w() / 2.0 ? -1.0 : 1.0;
    }

    @Override
    public boolean scrollable() {
        return true;
    }

    @Override
    public void onLoaded(PanelComponent component) {
        component.storeOutput(0, ((SetpointKnobData) component.data()).value);
    }

    @Override
    public boolean operate(OperateContext context, OperateAction action, double value) {
        SetpointKnobData data = (SetpointKnobData) context.component().data();
        double next;
        switch (action) {
            case PRESS -> next = data.value + step(value) * data.step * CLICK_STEPS;
            case TURN -> next = data.value + value * data.step;
            case SET -> next = value;
            default -> {
                return false;
            }
        }
        data.value = clamp(next, 0.0, 100.0);
        markSaved(context);
        context.setOutput(0, data.value);
        return true;
    }
}
