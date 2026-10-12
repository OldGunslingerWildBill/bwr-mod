package dev.bwr.mod.panel.component;

import dev.bwr.mod.BwrMod;
import dev.bwr.mod.panel.display.MimicShape;

import java.util.List;

/**
 * Mimic line (spec 2.1): a coloured bus segment, straight, corner or tee, laid
 * on the panel to draw the plant diagram. The footprint is the bounding box;
 * the bus runs from its centre to the connected sides. One optional input
 * "live" dims it to gray at 0 or no signal.
 */
public final class MimicLineType extends ComponentType {
    public static final List<Footprint> FOOTPRINTS = List.of(
            Footprint.of(8, 1), Footprint.of(4, 1), Footprint.of(2, 1), Footprint.of(1, 1), Footprint.of(16, 1),
            Footprint.of(1, 8), Footprint.of(1, 4), Footprint.of(1, 2), Footprint.of(1, 16),
            Footprint.of(2, 2), Footprint.of(3, 3), Footprint.of(4, 4), Footprint.of(6, 6),
            Footprint.of(8, 2), Footprint.of(2, 8), Footprint.of(32, 1), Footprint.of(1, 32));
    private static final List<ConfigField> SCHEMA = List.of(
            ConfigField.choice("Shape", "component.bwr.field.shape", MimicShape.SHAPES),
            ConfigField.integer("Rotation", "component.bwr.field.rotation", 0, 3),
            ConfigField.integer("Thickness", "component.bwr.field.thickness", 1, MimicLineData.MAX_THICKNESS),
            ConfigField.color("Color", "component.bwr.field.line_color"));
    private static final List<PortSpec> INPUTS = List.of(PortSpec.of("live"));

    MimicLineType() {
        super(BwrMod.id("mimic_line"));
    }

    @Override
    public List<Footprint> footprints() {
        return FOOTPRINTS;
    }

    @Override
    public ComponentData createData() {
        return new MimicLineData();
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
        return "ML";
    }
}
