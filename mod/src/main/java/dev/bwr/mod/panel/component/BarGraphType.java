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
            Footprint.of(1, 3), Footprint.of(3, 1), Footprint.of(1, 6), Footprint.of(6, 1),
            // twice the real size (an LED bar column), where a new item starts
            Footprint.of(2, 6),
            // alpha.60 size table: the horizontal 6x2
            Footprint.of(6, 2));
    /** Retired: the four one-thick sizes, 1x3, 3x1, 1x6 and 6x1. */
    private static final java.util.Set<Integer> RETIRED = java.util.Set.of(8, 9, 10, 11);
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
        return 5;
    }

    /** A new item starts at 2x6 (unchanged). */
    @Override
    public Footprint realDefaultFootprint() {
        return Footprint.of(2, 6);
    }

    /** Sizes added after the real sizes (the alpha.60 size table); see {@link ComponentType#appendedSizes()}. */
    @Override
    public int appendedSizes() {
        return 1;
    }

    /** Retired sizes, by index into {@link #FOOTPRINTS}: still placeable from an old stack or a saved part, no longer offered ({@link Footprints}). */
    @Override
    public java.util.Set<Integer> retiredFootprints() {
        return RETIRED;
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
