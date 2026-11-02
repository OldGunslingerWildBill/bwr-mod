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
    public static final String ID_PATH = "dial_gauge";
    public static final List<Footprint> FOOTPRINTS = List.of(
            Footprint.of(12, 12), Footprint.of(16, 16), Footprint.of(20, 20), Footprint.of(24, 24),
            Footprint.of(32, 32),
            // real sizes: a 4-1/4 in and a 6 in switchboard meter
            Footprint.of(2, 2), Footprint.of(3, 3),
            // twice the real 4-1/4 in meter, where a new item starts
            Footprint.of(4, 4),
            // alpha.60 size table: 6x6 and 8x8
            Footprint.of(6, 6), Footprint.of(8, 8));
    /** Retired: the real sizes 2x2 and 3x3 (4x4 stays, and is where a new item starts). */
    private static final java.util.Set<Integer> RETIRED = java.util.Set.of(5, 6);
    private static final List<ConfigField> SCHEMA = GaugeData.schema(ID_PATH);
    private static final List<PortSpec> INPUTS = List.of(PortSpec.of("value"));

    DialGaugeType() {
        super(BwrMod.id(ID_PATH));
    }

    @Override
    public int realSizes() {
        return 3;
    }

    /** A new item starts at 4x4 (unchanged). */
    @Override
    public Footprint realDefaultFootprint() {
        return Footprint.of(4, 4);
    }

    /** Sizes added after the real sizes (the alpha.60 size table); see {@link ComponentType#appendedSizes()}. */
    @Override
    public int appendedSizes() {
        return 2;
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
        return new GaugeData(ID_PATH);
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
