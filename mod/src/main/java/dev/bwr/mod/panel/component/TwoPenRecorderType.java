package dev.bwr.mod.panel.component;

import dev.bwr.mod.BwrMod;

import java.util.List;

/**
 * Two pen strip recorder ({@code bwr:two_pen_recorder}): the labelled variant of the strip chart for the paired
 * recordings real boards use (the IRM / APRM two pen recorders, two temperatures, a level and its setpoint). Two coloured
 * pens, red and green, on a chart that scrolls upward from the pens at the bottom edge; inputs {@code pen1} and
 * {@code pen2}, each with its own scale (Min and Max for pen 1, Min2 and Max2 for pen 2). It is the strip chart underneath:
 * {@link StripChartData} keeps the history (recorded on change, thinned, saved as ages, rebuilt on the client from the
 * same input deltas), so a pen that does not move costs nothing. No outputs and nothing to operate.
 */
public final class TwoPenRecorderType extends ComponentType {
    public static final String ID_PATH = "two_pen_recorder";
    public static final List<Footprint> FOOTPRINTS = List.of(Footprint.of(16, 16), Footprint.of(24, 24), Footprint.of(32, 32),
            // The real tail: twice the size first (a new item starts there), then the exact real size. A 6 in chart recorder
            // in its case (about 19 cm) is 3 x 3; the 4 in one is 2 x 2.
            Footprint.of(6, 6), Footprint.of(3, 3), Footprint.of(4, 4), Footprint.of(2, 2));
    private static final List<ConfigField> SCHEMA = List.of(
            ConfigField.integer("Span", "component.bwr.field.span", StripChartData.MIN_SPAN_SECONDS, StripChartData.MAX_SPAN_SECONDS),
            ConfigField.decimal("Min", "component.bwr.field.pen1_min", -GaugeData.LIMIT, GaugeData.LIMIT),
            ConfigField.decimal("Max", "component.bwr.field.pen1_max", -GaugeData.LIMIT, GaugeData.LIMIT),
            ConfigField.decimal("Min2", "component.bwr.field.pen2_min", -GaugeData.LIMIT, GaugeData.LIMIT),
            ConfigField.decimal("Max2", "component.bwr.field.pen2_max", -GaugeData.LIMIT, GaugeData.LIMIT),
            ConfigField.color("Pen1Color", "component.bwr.field.pen1_color"),
            ConfigField.color("Pen2Color", "component.bwr.field.pen2_color"));
    private static final List<PortSpec> INPUTS = List.of(PortSpec.of("pen1"), PortSpec.of("pen2"));

    TwoPenRecorderType() {
        super(BwrMod.id(ID_PATH));
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
        return new TwoPenRecorderData();
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
    public void valuesChanged(PanelComponent component) {
        ((StripChartData) component.data()).record(component);
    }

    @Override
    public String labelPrefix() {
        return "TP";
    }
}
