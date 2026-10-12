package dev.bwr.mod.panel.component;

import dev.bwr.mod.BwrMod;

import java.util.List;

/**
 * Mimic symbol (spec 2.1): valve, pump, tank, turbine or breaker, 2x2 and up,
 * coloured by one bound state. Pure display: it never commands anything.
 */
public final class MimicSymbolType extends ComponentType {
    public static final List<Footprint> FOOTPRINTS = List.of(
            Footprint.of(2, 2), Footprint.of(3, 3), Footprint.of(4, 4), Footprint.of(5, 5), Footprint.of(6, 6));
    private static final List<ConfigField> SCHEMA = List.of(
            ConfigField.choice("Symbol", "component.bwr.field.symbol", MimicSymbolData.SYMBOLS),
            ConfigField.color("OnColor", "component.bwr.field.on_color"),
            ConfigField.color("OffColor", "component.bwr.field.off_color"));
    private static final List<PortSpec> INPUTS = List.of(PortSpec.of("state"));

    MimicSymbolType() {
        super(BwrMod.id("mimic_symbol"));
    }

    @Override
    public List<Footprint> footprints() {
        return FOOTPRINTS;
    }

    @Override
    public ComponentData createData() {
        return new MimicSymbolData();
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
        return "MS";
    }
}
