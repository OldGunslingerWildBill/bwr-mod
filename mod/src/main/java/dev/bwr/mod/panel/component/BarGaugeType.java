package dev.bwr.mod.panel.component;

import dev.bwr.mod.BwrMod;
import net.minecraft.nbt.CompoundTag;

import java.util.List;

/**
 * Bar gauge, the owner's vertical edgewise indicator (the RPV level scales of a switchboard): a cream scale strip
 * with ticks, numbers and coloured zone bands, a black channel and a pointer that slides up it, and the units
 * written down the right hand side one letter per line. One input, "value". It reuses {@link GaugeData}: range,
 * units, damping and the colour bands, with a fourth band and two scale presets (narrow and wide range RPV level)
 * that the wrench's Preset field fills in. Display only: it never acts on anything.
 *
 * <p>About one block tall at its first size, like the other instruments; the last size is two blocks.
 */
public final class BarGaugeType extends ComponentType {
    public static final String ID_PATH = GaugeData.BAR_GAUGE;
    public static final List<Footprint> FOOTPRINTS = List.of(
            Footprint.of(10, 16), Footprint.of(8, 16), Footprint.of(12, 24), Footprint.of(16, 32));
    private static final List<ConfigField> SCHEMA = GaugeData.schema(ID_PATH);
    private static final List<PortSpec> INPUTS = List.of(PortSpec.of("value"));

    BarGaugeType() {
        super(BwrMod.id(ID_PATH));
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

    /** Choosing a scale preset in the wrench fills its range, units and bands in (see {@link GaugeData#adjustForPreset}). */
    @Override
    public void adjustConfig(PanelComponent component, CompoundTag proposed) {
        ((GaugeData) component.data()).adjustForPreset(proposed);
    }

    @Override
    public List<PortSpec> inputPorts(ComponentData data) {
        return INPUTS;
    }

    @Override
    public String labelPrefix() {
        return "BG";
    }
}
