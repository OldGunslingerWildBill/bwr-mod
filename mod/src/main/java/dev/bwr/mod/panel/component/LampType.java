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
            Footprint.of(1, 1),
            // twice the real light, kept for packed indicator strips
            Footprint.of(2, 2),
            // alpha.60 size table: 3x3, where a new item starts
            Footprint.of(3, 3));
    /** Retired: the real size (1x1). */
    private static final java.util.Set<Integer> RETIRED = java.util.Set.of(2);
    private static final List<ConfigField> SCHEMA = PanelStyles.withLook(LampData.TYPE, List.of(
            ConfigField.color("Color", "component.bwr.field.color"),
            ConfigField.choice("Flash", "component.bwr.field.flash", LampData.FLASH_MODES)));
    private static final List<PortSpec> INPUTS = List.of(PortSpec.of("state"));

    LampType() {
        super(BwrMod.id("lamp"));
    }

    @Override
    public int realSizes() {
        return 2;
    }

    /** A new item starts at 3x3 (alpha.60 size table). */
    @Override
    public Footprint realDefaultFootprint() {
        return Footprint.of(3, 3);
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
