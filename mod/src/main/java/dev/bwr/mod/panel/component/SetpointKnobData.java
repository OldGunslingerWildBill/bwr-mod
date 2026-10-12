package dev.bwr.mod.panel.component;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/** Setpoint knob: scroll step, knob colour and the latched value in percent. */
public final class SetpointKnobData extends ComponentData {
    public static final double MIN_STEP = 0.1;
    public static final double MAX_STEP = 25.0;
    public static final int DEFAULT_COLOR = 0x303438;

    public double step = 1.0;
    public int color = DEFAULT_COLOR;
    /** 0 .. 100 percent. */
    public double value;

    @Override
    public void save(CompoundTag tag) {
        tag.putDouble("Step", step);
        tag.putInt("Color", color);
        tag.putDouble("Value", value);
    }

    @Override
    public void load(CompoundTag tag) {
        double savedStep = tag.getDouble("Step");
        step = tag.contains("Step", Tag.TAG_ANY_NUMERIC) && Double.isFinite(savedStep)
                ? Math.max(MIN_STEP, Math.min(MAX_STEP, savedStep)) : 1.0;
        color = tag.contains("Color", Tag.TAG_ANY_NUMERIC) ? tag.getInt("Color") & 0xFFFFFF : DEFAULT_COLOR;
        double saved = tag.getDouble("Value");
        value = Double.isFinite(saved) ? Math.max(0.0, Math.min(100.0, saved)) : 0.0;
    }
}
