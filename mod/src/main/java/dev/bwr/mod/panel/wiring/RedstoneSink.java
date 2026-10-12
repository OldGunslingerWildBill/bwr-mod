package dev.bwr.mod.panel.wiring;

import dev.bwr.mod.panel.component.OutputSink;
import dev.bwr.mod.panel.component.OutputSinks;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/**
 * Output sink {@code redstone}: emits the output value (times {@code scale}, rounded, clamped to 0..15) from
 * the back face of the panel block that owns the component. A block with several redstone sinks emits the
 * strongest. The emission itself lives in {@code PanelBlock}/{@code PanelBlockEntity}; this sink only carries
 * the scale, and {@link PanelWiring} tells the block when an output changed.
 */
public final class RedstoneSink implements OutputSink {
    public static final String KIND = OutputSinks.KIND_REDSTONE;

    private final double scale;

    public RedstoneSink(double scale) {
        this.scale = scale;
    }

    public double scale() {
        return scale;
    }

    /** The emitted strength for an output value: 0 for no signal. */
    public int strength(double value) {
        double scaled = value * scale;
        if (Double.isNaN(scaled)) {
            return 0;
        }
        return (int) Math.max(0, Math.min(15, Math.round(scaled)));
    }

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public void save(CompoundTag tag) {
        tag.putDouble("Scale", scale);
    }

    static OutputSink load(CompoundTag tag) {
        double scale = tag.contains("Scale", Tag.TAG_ANY_NUMERIC) && Double.isFinite(tag.getDouble("Scale"))
                ? tag.getDouble("Scale") : 1.0;
        return new RedstoneSink(scale);
    }
}
