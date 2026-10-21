package dev.bwr.mod.panel.component;

import dev.bwr.mod.BwrMod;

import java.util.List;

/**
 * Annunciator window, 4x3 (spec 2.1): a backlit tile with up to four engraved lines; windows that touch draw as one
 * grouped box (the client's {@code AnnunciatorBoxes}).
 * The alarm condition (input above / below / equal to a setpoint) is display
 * behaviour only; the sequence is ISA-18.1 A, see {@link AnnunciatorLogic}.
 * Acknowledge and reset come from a pushbutton configured with that role for the
 * same group number ({@link AnnunciatorGroups}).
 *
 * <p>The state machine runs on both sides from the same input deltas, so the
 * flashing needs no packets of its own; the latched state is part of the
 * full-state sync and the save.
 */
public final class AnnunciatorType extends ComponentType {
    public static final List<Footprint> FOOTPRINTS = List.of(Footprint.of(12, 8), Footprint.of(16, 12),
            // real sizes: one window tile, 2.8125 x 2.625 in, and a double size one
            Footprint.of(1, 1), Footprint.of(2, 2));
    private static final List<ConfigField> SCHEMA = List.of(
            ConfigField.text("Line1", "component.bwr.field.line1", AnnunciatorData.MAX_TEXT),
            ConfigField.text("Line2", "component.bwr.field.line2", AnnunciatorData.MAX_TEXT),
            ConfigField.text("Line3", "component.bwr.field.line3", AnnunciatorData.MAX_TEXT),
            ConfigField.text("Line4", "component.bwr.field.line4", AnnunciatorData.MAX_TEXT),
            ConfigField.color("Color", "component.bwr.field.tile_color"),
            ConfigField.choice("Condition", "component.bwr.field.condition", AnnunciatorData.CONDITIONS),
            ConfigField.decimal("Setpoint", "component.bwr.field.setpoint", -AnnunciatorData.LIMIT, AnnunciatorData.LIMIT),
            ConfigField.integer("AnnGroup", "component.bwr.field.ann_group", AnnunciatorData.MIN_GROUP, AnnunciatorData.MAX_GROUP),
            ConfigField.choice("Reset", "component.bwr.field.reset_mode", AnnunciatorData.RESET_MODES));
    private static final List<PortSpec> INPUTS = List.of(PortSpec.of("input"));

    AnnunciatorType() {
        super(BwrMod.id("annunciator"));
    }

    @Override
    public int realSizes() {
        return 2;
    }

    @Override
    public List<Footprint> footprints() {
        return FOOTPRINTS;
    }

    @Override
    public ComponentData createData() {
        return new AnnunciatorData();
    }

    @Override
    public List<ConfigField> configSchema() {
        return SCHEMA;
    }

    @Override
    public List<PortSpec> inputPorts(ComponentData data) {
        return INPUTS;
    }

    @Override
    public String labelPrefix() {
        return "AN";
    }

    @Override
    public void valuesChanged(PanelComponent component) {
        evaluate(component);
    }

    /** A config edit may have moved the setpoint: judge the current input again. */
    @Override
    public void onLoaded(PanelComponent component) {
        evaluate(component);
    }

    private static void evaluate(PanelComponent component) {
        double input = component.input(0);
        if (Double.isNaN(input)) {
            return; // no signal holds the state
        }
        AnnunciatorData data = (AnnunciatorData) component.data();
        if (data.logic.update(data.alarming(input))) {
            data.latchedChanged();
        }
    }
}
