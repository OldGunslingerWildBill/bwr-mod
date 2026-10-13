package dev.bwr.mod.panel.component;

import dev.bwr.mod.BwrMod;

import java.util.List;

/**
 * Round dial gauge, 12x12 to 32x32 cells (spec 2.1): a needle over a 270 degree
 * scale with colour bands. One input, "value". No signal pegs the needle low
 * and lights the off-scale flag. Needle damping is a client smoothing, so a
 * fast signal costs no extra packets.
 */
public final class DialGaugeType extends ComponentType {
    public static final List<Footprint> FOOTPRINTS = List.of(
            Footprint.of(12, 12), Footprint.of(16, 16), Footprint.of(20, 20), Footprint.of(24, 24),
            Footprint.of(32, 32));
    private static final List<ConfigField> SCHEMA = GaugeData.schema();
    private static final List<PortSpec> INPUTS = List.of(PortSpec.of("value"));

    DialGaugeType() {
        super(BwrMod.id("dial_gauge"));
    }

    @Override
    public List<Footprint> footprints() {
        return FOOTPRINTS;
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
        return "DG";
    }
}
