package dev.bwr.mod.panel.component;

import dev.bwr.mod.BwrMod;

import java.util.List;

/**
 * Strip-chart recorder, 6x6 cells (spec 2.1): 1 to 4 pens on scrolling paper,
 * the span configurable from 10 s to an hour. Inputs "pen1".."pen4" (as many as
 * the pen count). The server records the history (see {@link StripChartData});
 * clients redraw the paper from it.
 */
public final class StripChartType extends ComponentType {
    public static final List<Footprint> FOOTPRINTS = List.of(Footprint.of(16, 16), Footprint.of(24, 16), Footprint.of(32, 24));
    private static final List<ConfigField> SCHEMA = StripChartData.schema();
    private static final List<List<PortSpec>> PORTS = List.of(
            List.of(PortSpec.of("pen1")),
            List.of(PortSpec.of("pen1"), PortSpec.of("pen2")),
            List.of(PortSpec.of("pen1"), PortSpec.of("pen2"), PortSpec.of("pen3")),
            List.of(PortSpec.of("pen1"), PortSpec.of("pen2"), PortSpec.of("pen3"), PortSpec.of("pen4")));

    StripChartType() {
        super(BwrMod.id("strip_chart"));
    }

    @Override
    public List<Footprint> footprints() {
        return FOOTPRINTS;
    }

    @Override
    public ComponentData createData() {
        return new StripChartData();
    }

    @Override
    public List<ConfigField> configSchema() {
        return SCHEMA;
    }

    @Override
    public List<PortSpec> inputPorts(ComponentData data) {
        int pens = ((StripChartData) data).pens;
        return PORTS.get(Math.max(1, Math.min(StripChartData.MAX_PENS, pens)) - 1);
    }

    @Override
    public void valuesChanged(PanelComponent component) {
        ((StripChartData) component.data()).record(component);
    }

    @Override
    public String labelPrefix() {
        return "SC";
    }
}
