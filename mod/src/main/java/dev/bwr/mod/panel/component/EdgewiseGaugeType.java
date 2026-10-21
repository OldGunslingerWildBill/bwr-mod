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
    public static final String VERTICAL_PATH = "edgewise_gauge_vertical";
    public static final String HORIZONTAL_PATH = "edgewise_gauge_horizontal";
    private static final List<PortSpec> INPUTS = List.of(PortSpec.of("value"), PortSpec.of("demand"));

    private final boolean vertical;
    private final List<Footprint> footprints;
    private final List<ConfigField> schema;

    EdgewiseGaugeType(boolean vertical) {
        super(BwrMod.id(vertical ? VERTICAL_PATH : HORIZONTAL_PATH));
        this.vertical = vertical;
        this.schema = GaugeData.schema(vertical ? VERTICAL_PATH : HORIZONTAL_PATH);
        this.footprints = vertical
                ? List.of(Footprint.of(6, 16), Footprint.of(6, 24), Footprint.of(8, 24), Footprint.of(8, 32),
                        // real size: a 6 in GE Type 180 edgewise meter, 2.3 x 6.1 in
                        Footprint.of(1, 3),
                        // twice the real size, where a new item starts
                        Footprint.of(2, 6))
                : List.of(Footprint.of(16, 6), Footprint.of(24, 6), Footprint.of(24, 8), Footprint.of(32, 8),
                        Footprint.of(3, 1),
                        Footprint.of(6, 2));
    }

    public boolean vertical() {
        return vertical;
    }

    @Override
    public int realSizes() {
        return 2;
    }

    /** A new item starts at twice the real size, in each dimension ({@link ComponentType#realDefaultFootprint}). */
    @Override
    public Footprint realDefaultFootprint() {
        return vertical ? Footprint.of(2, 6) : Footprint.of(6, 2);
    }

    @Override
    public List<Footprint> footprints() {
        return footprints;
    }

    @Override
    public ComponentData createData() {
        return new GaugeData(vertical ? VERTICAL_PATH : HORIZONTAL_PATH);
    }

    @Override
    public List<ConfigField> configSchema() {
        return schema;
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
