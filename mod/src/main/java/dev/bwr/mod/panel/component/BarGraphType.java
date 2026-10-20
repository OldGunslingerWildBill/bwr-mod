package dev.bwr.mod.panel.component;

import dev.bwr.mod.BwrMod;

import java.util.List;

/**
 * Segmented bar graph, 3xN vertical or Nx3 horizontal (spec 2.1): one segment
 * per {@link #SEGMENT_CELLS} cells along its long side, lit up to the input value on the configured
 * scale, coloured by the warn and alarm bands ({@link BarGraphLogic}). No
 * signal shows every segment grey and unlit.
 */
public final class BarGraphType extends ComponentType {
    /** Cells along the long side that one segment covers. */
    public static final int SEGMENT_CELLS = 2;
    public static final List<Footprint> FOOTPRINTS = List.of(
            Footprint.of(3, 24), Footprint.of(3, 12), Footprint.of(3, 16), Footprint.of(3, 32),
            Footprint.of(24, 3), Footprint.of(12, 3), Footprint.of(16, 3), Footprint.of(32, 3),
            // real sizes: an LED bar column or row, one cell thick (estimate)
            Footprint.of(1, 3), Footprint.of(3, 1), Footprint.of(1, 6), Footprint.of(6, 1));
    private static final List<ConfigField> SCHEMA = java.util.stream.Stream.concat(java.util.stream.Stream.of(
            ConfigField.decimal("Min", "component.bwr.field.min", -BarGraphData.LIMIT, BarGraphData.LIMIT),
            ConfigField.decimal("Max", "component.bwr.field.max", -BarGraphData.LIMIT, BarGraphData.LIMIT),
            ConfigField.decimal("Warn", "component.bwr.field.warn", -BarGraphData.LIMIT, BarGraphData.LIMIT),
            ConfigField.decimal("Alarm", "component.bwr.field.alarm", -BarGraphData.LIMIT, BarGraphData.LIMIT),
            ConfigField.color("Color", "component.bwr.field.segment_color"),
            Measures.field()), PowerUnitsState.fields().stream()).toList();
    private static final List<PortSpec> INPUTS = List.of(PortSpec.of("value"));

    BarGraphType() {
        super(BwrMod.id("bar_graph"));
    }

    @Override
    public int realSizes() {
        return 4;
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
