package dev.bwr.mod.panel.component;

import dev.bwr.mod.BwrMod;

import java.util.List;

/**
 * Rotary selector, 3x3 (spec 2.1): two to six labelled detents; output
 * "position" is the 0-based index. Clicking the right half turns it one
 * detent up and the left half one down; scrolling does the same. It stops at
 * the ends rather than wrapping.
 */
public final class RotarySelectorType extends PanelInputType {
    public static final List<Footprint> FOOTPRINTS = List.of(Footprint.of(3, 3), Footprint.of(4, 4));
    private static final List<ConfigField> SCHEMA = List.of(
            ConfigField.integer("Positions", "component.bwr.field.positions",
                    RotarySelectorData.MIN_POSITIONS, RotarySelectorData.MAX_POSITIONS),
            ConfigField.text("Names", "component.bwr.field.names", RotarySelectorData.MAX_NAMES),
            ConfigField.color("Color", "component.bwr.field.knob_color"));
    private static final List<PortSpec> OUTPUTS = List.of(PortSpec.of("position"));

    RotarySelectorType() {
        super(BwrMod.id("rotary_selector"));
    }

    @Override
    public List<Footprint> footprints() {
        return FOOTPRINTS;
    }

    @Override
    public ComponentData createData() {
        return new RotarySelectorData();
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
        return "RS";
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
        RotarySelectorData data = (RotarySelectorData) component.data();
        component.storeOutput(0, Math.min(data.position, data.positions - 1));
    }

    @Override
    public boolean operate(OperateContext context, OperateAction action, double value) {
        RotarySelectorData data = (RotarySelectorData) context.component().data();
        int next;
        switch (action) {
            case PRESS, TURN -> next = data.position + (value == 0.0 ? 1 : step(value));
            case SET -> next = (int) Math.round(clamp(value, 0, data.positions - 1));
            default -> {
                return false;
            }
        }
        data.position = Math.max(0, Math.min(data.positions - 1, next));
        markSaved(context);
        context.setOutput(0, data.position);
        return true;
    }
}
