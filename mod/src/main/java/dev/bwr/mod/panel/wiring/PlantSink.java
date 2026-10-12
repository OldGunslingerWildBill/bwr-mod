package dev.bwr.mod.panel.wiring;

import com.mojang.logging.LogUtils;
import dev.bwr.mod.panel.component.OutputSink;
import dev.bwr.mod.panel.component.OutputSinks;
import dev.bwr.mod.signal.CommandSpec;
import dev.bwr.mod.signal.Kind;
import dev.bwr.mod.signal.SignalProvider;
import dev.bwr.mod.signal.SignalProviders;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import org.slf4j.Logger;

/**
 * Output sink {@code plant}: a command of a linked plant device. Goes through {@link SignalProvider#command},
 * the same code as the device's CC setter, so power, interlocks and {@code computerControlled} behave exactly
 * as they do for a Lua program. The value sent is the output value times {@code scale} (so a 0 to 100 percent
 * knob can drive a 0 to 1 demand with scale 0.01). A PULSE command fires only for a non-zero value, so a
 * pushbutton wired to scram acts on press, not again on release.
 *
 * <p>An unloaded or missing device, an unknown key or a refusal simply does nothing; the sink never loads a
 * chunk and never retries.
 */
public final class PlantSink implements OutputSink {
    public static final String KIND = OutputSinks.KIND_PLANT;
    private static final Logger LOGGER = LogUtils.getLogger();

    private final BlockPos device;
    private final String key;
    private final double scale;

    public PlantSink(BlockPos device, String key, double scale) {
        this.device = device.immutable();
        this.key = key;
        this.scale = scale;
    }

    public BlockPos device() {
        return device;
    }

    public String key() {
        return key;
    }

    public double scale() {
        return scale;
    }

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public void fire(FireContext context, double value) {
        if (Double.isNaN(value)) {
            return;
        }
        SignalProvider provider = SignalProviders.at(context.level(), device);
        if (provider == null) {
            return;
        }
        for (CommandSpec spec : provider.commands()) {
            if (spec.key().equals(key)) {
                if (spec.kind() == Kind.PULSE && value == 0.0) {
                    return;
                }
                break;
            }
        }
        try {
            provider.command(key, value * scale);
        } catch (RuntimeException e) {
            LOGGER.warn("Panel command {} at {} failed", key, device, e);
        }
    }

    @Override
    public void save(CompoundTag tag) {
        tag.putInt("X", device.getX());
        tag.putInt("Y", device.getY());
        tag.putInt("Z", device.getZ());
        tag.putString("Key", key);
        tag.putDouble("Scale", scale);
    }

    static OutputSink load(CompoundTag tag) {
        if (!tag.contains("X", Tag.TAG_ANY_NUMERIC) || !tag.contains("Y", Tag.TAG_ANY_NUMERIC)
                || !tag.contains("Z", Tag.TAG_ANY_NUMERIC) || !tag.contains("Key", Tag.TAG_STRING)
                || tag.getString("Key").isEmpty()) {
            return OutputSinks.preserved(tag);
        }
        double scale = tag.contains("Scale", Tag.TAG_ANY_NUMERIC) && Double.isFinite(tag.getDouble("Scale"))
                ? tag.getDouble("Scale") : 1.0;
        return new PlantSink(new BlockPos(tag.getInt("X"), tag.getInt("Y"), tag.getInt("Z")), tag.getString("Key"), scale);
    }
}
