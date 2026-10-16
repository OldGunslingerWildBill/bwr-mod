package dev.bwr.mod.panel.component;

import dev.bwr.mod.BwrMod;
import net.minecraft.nbt.CompoundTag;

import java.util.ArrayList;
import java.util.List;

/**
 * Paperless recorder (Yokogawa DX1000 / DX2000 styles): one input per channel, sized by the configured channel
 * count, a screen with trend, digital, bar, overview and alarm summary displays, and the front keys of the real unit.
 * It records and indicates only. Its alarms drive nothing; the one thing a player can do to its alarms is acknowledge
 * them, which clears the flashing of the indication and nothing else.
 *
 * <p>Sampling rides the existing {@code PanelSampler}: a recorder with any bound input is sampled with the other
 * live panels ({@link #keepsSampling}), there is no ticker of its own. The data ({@link PaperlessRecorderData})
 * closes dots and runs the alarms in {@code sampleContent}; the client only draws what it was sent.
 */
public final class PaperlessRecorderType extends ComponentType {
    /**
     * Square, in the scale of the other instruments (a 4.3 inch meter is 16 cells, one block): a DX1000 at 16 cells (the default),
     * a DX2000 at 32 (twice a DX1000, the second size), then the other settings of each make: DX1000 12, 20, 24; DX2000 40, 48.
     * (The DX2000's 24 is the DX1000's 24; see {@link RecorderStyle#forFootprint}.)
     */
    public static final List<Footprint> FOOTPRINTS = List.of(Footprint.of(16, 16), Footprint.of(32, 32),
            Footprint.of(12, 12), Footprint.of(20, 20), Footprint.of(24, 24), Footprint.of(40, 40), Footprint.of(48, 48));
    private static final List<ConfigField> SCHEMA = PaperlessRecorderData.schema();
    private static final List<PortSpec> PORTS = ports();

    PaperlessRecorderType() {
        super(BwrMod.id("paperless_recorder"));
    }

    private static List<PortSpec> ports() {
        List<PortSpec> list = new ArrayList<>();
        for (int c = 0; c < PaperlessRecorderData.MAX_CHANNELS; c++) {
            list.add(PortSpec.of("ch" + (c < 9 ? "0" : "") + (c + 1)));
        }
        return List.copyOf(list);
    }

    @Override
    public List<Footprint> footprints() {
        return FOOTPRINTS;
    }

    @Override
    public ComponentData createData() {
        return new PaperlessRecorderData();
    }

    @Override
    public ComponentData createData(Footprint footprint) {
        return new PaperlessRecorderData(RecorderStyle.forFootprint(footprint));
    }

    @Override
    public List<ConfigField> configSchema() {
        return SCHEMA;
    }

    @Override
    public void adjustConfig(PanelComponent component, CompoundTag proposed) {
        ((PaperlessRecorderData) component.data()).adjust(proposed);
    }

    @Override
    public List<PortSpec> inputPorts(ComponentData data) {
        return PORTS.subList(0, ((PaperlessRecorderData) data).channels);
    }

    @Override
    public String labelPrefix() {
        return "REC";
    }

    @Override
    public boolean isInteractive() {
        return true;
    }

    /** The recorder keeps its clock running whenever something is wired to it, even a value no sampler re-reads. */
    @Override
    public boolean keepsSampling(PanelComponent component) {
        for (int i = 0; i < component.inputCount(); i++) {
            if (!InputSources.KIND_NONE.equals(component.source(i).kind())) {
                return true;
            }
        }
        return false;
    }

    /** A key press: {@code value} is a {@link RecorderActions} code, validated here. */
    @Override
    public boolean operate(OperateContext context, OperateAction action, double value) {
        if (action != OperateAction.PRESS || value != Math.rint(value)) {
            return false;
        }
        return RecorderActions.apply((PaperlessRecorderData) context.component().data(), (int) value);
    }
}
