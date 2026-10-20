package dev.bwr.mod.panel.component;

import dev.bwr.mod.BwrMod;

import java.util.List;

/**
 * Indicator lamp, 1x1 or 2x2 (spec 2.1): off, on, or flashing, in any colour,
 * drawn full-bright when active. One input, "state": any non-zero signal lights
 * it; no signal or zero leaves it dark. Flash rate is derived from game time on
 * the client, so a flashing lamp costs no packets and no ticks.
 */
public final class LampType extends ComponentType {
    public static final List<Footprint> FOOTPRINTS = List.of(Footprint.of(4, 4), Footprint.of(6, 6),
            // real size: a 30.5 mm indicating light
            Footprint.of(1, 1));
    private static final List<ConfigField> SCHEMA = PanelStyles.withLook(LampData.TYPE, List.of(
            ConfigField.color("Color", "component.bwr.field.color"),
            ConfigField.choice("Flash", "component.bwr.field.flash", LampData.FLASH_MODES)));
    private static final List<PortSpec> INPUTS = List.of(PortSpec.of("state"));

    LampType() {
        super(BwrMod.id("lamp"));
    }

    @Override
    public int realSizes() {
        return 1;
    }

    @Override
    public List<Footprint> footprints() {
        return FOOTPRINTS;
    }

    @Override
    public ComponentData createData() {
        return new LampData();
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
        return "LP";
    }
}
