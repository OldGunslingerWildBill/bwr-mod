package dev.bwr.mod.panel.component;

import dev.bwr.mod.BwrMod;

import java.util.List;

/**
 * Segmented bar graph, 1xN vertical or Nx1 horizontal (spec 2.1): one segment
 * per cell along its long side, lit up to the input value on the configured
 * scale, coloured by the warn and alarm bands ({@link BarGraphLogic}). No
 * signal shows every segment grey and unlit.
 */
public final class BarGraphType extends ComponentType {
    public static final List<Footprint> FOOTPRINTS = List.of(
            Footprint.of(1, 8), Footprint.of(1, 4), Footprint.of(1, 6), Footprint.of(1, 12), Footprint.of(1, 16),
            Footprint.of(8, 1), Footprint.of(4, 1), Footprint.of(6, 1), Footprint.of(12, 1), Footprint.of(16, 1));
    private static final List<ConfigField> SCHEMA = List.of(
            ConfigField.decimal("Min", "component.bwr.field.min", -BarGraphData.LIMIT, BarGraphData.LIMIT),
            ConfigField.decimal("Max", "component.bwr.field.max", -BarGraphData.LIMIT, BarGraphData.LIMIT),
            ConfigField.decimal("Warn", "component.bwr.field.warn", -BarGraphData.LIMIT, BarGraphData.LIMIT),
            ConfigField.decimal("Alarm", "component.bwr.field.alarm", -BarGraphData.LIMIT, BarGraphData.LIMIT),
            ConfigField.color("Color", "component.bwr.field.segment_color"));
    private static final List<PortSpec> INPUTS = List.of(PortSpec.of("value"));

    BarGraphType() {
        super(BwrMod.id("bar_graph"));
    }

    @Override
    public List<Footprint> footprints() {
        return FOOTPRINTS;
    }

    @Override
    public ComponentData createData() {
        return new BarGraphData();
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
        return "BG";
    }
}
