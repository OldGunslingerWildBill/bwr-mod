package dev.bwr.mod.panel.component;

import dev.bwr.mod.panel.display.MimicShape;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/**
 * Mimic line piece: shape (straight, corner, tee), rotation, thickness and bus
 * colour. Input 0 "live" is optional: when something is bound to it the line
 * dims to gray while the signal is 0 or missing; unbound, it is always coloured.
 */
public final class MimicLineData extends ComponentData {
    public static final int DEFAULT_COLOR = 0x2E7DE0;
    public static final int MAX_THICKNESS = 8;
    public static final int DEFAULT_THICKNESS = 3;

    public String shape = MimicShape.STRAIGHT;
    public int rotation;
    public int thickness = DEFAULT_THICKNESS;
    public int color = DEFAULT_COLOR;

    @Override
    public void save(CompoundTag tag) {
        tag.putString("Shape", shape);
        tag.putInt("Rotation", rotation);
        tag.putInt("Thickness", thickness);
        tag.putInt("Color", color);
    }

    @Override
    public void load(CompoundTag tag) {
        String saved = tag.getString("Shape");
        shape = MimicShape.SHAPES.contains(saved) ? saved : MimicShape.STRAIGHT;
        rotation = tag.contains("Rotation", Tag.TAG_ANY_NUMERIC) ? Math.max(0, Math.min(3, tag.getInt("Rotation"))) : 0;
        thickness = tag.contains("Thickness", Tag.TAG_ANY_NUMERIC)
                ? Math.max(1, Math.min(MAX_THICKNESS, tag.getInt("Thickness"))) : DEFAULT_THICKNESS;
        color = tag.contains("Color", Tag.TAG_ANY_NUMERIC) ? tag.getInt("Color") & 0xFFFFFF : DEFAULT_COLOR;
    }
}
