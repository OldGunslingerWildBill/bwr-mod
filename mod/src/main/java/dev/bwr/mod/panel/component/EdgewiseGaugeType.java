package dev.bwr.mod.panel.component;

import dev.bwr.mod.BwrMod;

import java.util.List;

/**
 * Edgewise (strip) meter, vertical 2x6 or horizontal 6x2 (spec 2.1): scale with
 * colour bands, a pointer for input 0 "value" and an optional demand marker for
 * input 1 "demand" (shown only while that input has a signal). The two
 * orientations are two registered types sharing this class.
 */
public final class EdgewiseGaugeType extends ComponentType {
    private static final List<ConfigField> SCHEMA = GaugeData.schema();
    private static final List<PortSpec> INPUTS = List.of(PortSpec.of("value"), PortSpec.of("demand"));

    private final boolean vertical;
    private final List<Footprint> footprints;

    EdgewiseGaugeType(boolean vertical) {
        super(BwrMod.id(vertical ? "edgewise_gauge_vertical" : "edgewise_gauge_horizontal"));
        this.vertical = vertical;
        this.footprints = vertical
                ? List.of(Footprint.of(2, 6), Footprint.of(2, 8), Footprint.of(3, 6), Footprint.of(2, 10))
                : List.of(Footprint.of(6, 2), Footprint.of(8, 2), Footprint.of(6, 3), Footprint.of(10, 2));
    }

    public boolean vertical() {
        return vertical;
    }

    @Override
    public List<Footprint> footprints() {
        return footprints;
    }

    @Override
    public ComponentData createData() {
        return new GaugeData();
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
        return vertical ? "EV" : "EH";
    }
}
