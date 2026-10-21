package dev.bwr.mod.panel.component;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/**
 * Synchroscope: the colours of its two lamps and the sense of its pointer. Nothing is latched: the pointer and the
 * lamps are the live inputs. A missing key loads as the default.
 */
public final class SynchroscopeData extends ComponentData {
    public static final int DEFAULT_LAMP_1 = 0xFFD060;
    public static final int DEFAULT_LAMP_2 = 0xFFD060;

    /** Pointer turns anticlockwise for a generator that is fast (some instruments are wired that way). */
    public boolean reverse;
    public int lamp1Color = DEFAULT_LAMP_1;
    public int lamp2Color = DEFAULT_LAMP_2;

    @Override
    public void save(CompoundTag tag) {
        if (reverse) {
            tag.putBoolean("Reverse", true);
        }
        tag.putInt("Lamp1Color", lamp1Color);
        tag.putInt("Lamp2Color", lamp2Color);
    }

    @Override
    public void load(CompoundTag tag) {
        reverse = tag.getBoolean("Reverse");
        lamp1Color = tag.contains("Lamp1Color", Tag.TAG_ANY_NUMERIC) ? tag.getInt("Lamp1Color") & 0xFFFFFF : DEFAULT_LAMP_1;
        lamp2Color = tag.contains("Lamp2Color", Tag.TAG_ANY_NUMERIC) ? tag.getInt("Lamp2Color") & 0xFFFFFF : DEFAULT_LAMP_2;
    }
}
