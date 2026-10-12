package dev.bwr.mod.panel.wiring;

import dev.bwr.mod.panel.component.InputSource;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/**
 * Input source {@code cc}: a value a CC:Tweaked program sets with {@code bwr_panel.set}. It reads NaN
 * ("awaiting CC") until the first set, then holds the last value, which is saved so a panel survives a
 * restart without waiting for the program to run again. Not polled: {@link PanelWiring#setCc} pushes it.
 */
public final class CcSource implements InputSource {
    public static final String KIND = "cc";

    private double value = Double.NaN;

    public CcSource() {
    }

    public double value() {
        return value;
    }

    /** Whether nothing has been set yet. */
    public boolean awaiting() {
        return Double.isNaN(value);
    }

    void set(double value) {
        this.value = value;
    }

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public double sample(SampleContext context) {
        return value;
    }

    @Override
    public void save(CompoundTag tag) {
        if (!Double.isNaN(value)) {
            tag.putDouble("Value", value);
        }
    }

    static InputSource load(CompoundTag tag) {
        CcSource source = new CcSource();
        if (tag.contains("Value", Tag.TAG_ANY_NUMERIC) && Double.isFinite(tag.getDouble("Value"))) {
            source.value = tag.getDouble("Value");
        }
        return source;
    }
}
