package dev.bwr.mod.panel.wiring;

import dev.bwr.mod.panel.component.InputSource;
import dev.bwr.mod.panel.component.InputSources;
import dev.bwr.mod.signal.SignalProvider;
import dev.bwr.mod.signal.SignalProviders;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

import java.util.OptionalDouble;

/**
 * Input source {@code plant}: one signal of a linked plant device ({@link SignalProviders}). Sampled by the
 * panel sampler every {@code sampleTicks}. An unloaded chunk, a removed or unformed device, an unknown key or
 * a signal with no value all read NaN ("no signal"); sampling never loads a chunk.
 *
 * <p>Saved as the absolute device position ({@code X}, {@code Y}, {@code Z}) and the signal {@code Key}. The
 * device is always in the panel's own dimension (the linker refuses anything else).
 *
 * <p>A linear transform maps the raw signal to the displayed value: {@code raw * scale + offset} (defaults 1
 * and 0), so a 0 to 1 valve position can read 0 to 100 or a pressure can be shown in other units. NaN stays
 * NaN. {@code Scale} and {@code Offset} are saved only when they differ from the defaults, so a save from
 * before the transform existed loads unchanged and re-saves byte-identical.
 */
public final class PlantSource implements InputSource {
    public static final String KIND = "plant";

    private final BlockPos device;
    private final String key;
    private final double scale;
    private final double offset;

    public PlantSource(BlockPos device, String key) {
        this(device, key, 1.0, 0.0);
    }

    public PlantSource(BlockPos device, String key, double scale, double offset) {
        this.device = device.immutable();
        this.key = key;
        this.scale = scale;
        this.offset = offset;
    }

    /** Multiplier applied to the raw signal (default 1). */
    public double scale() {
        return scale;
    }

    /** Added after scaling (default 0). */
    public double offset() {
        return offset;
    }

    public BlockPos device() {
        return device;
    }

    public String key() {
        return key;
    }

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public boolean isLive() {
        return true;
    }

    @Override
    public double sample(SampleContext context) {
        SignalProvider provider = SignalProviders.at(context.level(), device);
        if (provider == null) {
            return Double.NaN;
        }
        OptionalDouble value = provider.read(key);
        return value.isPresent() ? value.getAsDouble() * scale + offset : Double.NaN;
    }

    @Override
    public void save(CompoundTag tag) {
        tag.putInt("X", device.getX());
        tag.putInt("Y", device.getY());
        tag.putInt("Z", device.getZ());
        tag.putString("Key", key);
        if (scale != 1.0) {
            tag.putDouble("Scale", scale);
        }
        if (offset != 0.0) {
            tag.putDouble("Offset", offset);
        }
    }

    /** Loader: a tag without position and key is kept verbatim rather than rewritten. */
    static InputSource load(CompoundTag tag) {
        if (!tag.contains("X", Tag.TAG_ANY_NUMERIC) || !tag.contains("Y", Tag.TAG_ANY_NUMERIC)
                || !tag.contains("Z", Tag.TAG_ANY_NUMERIC) || !tag.contains("Key", Tag.TAG_STRING)
                || tag.getString("Key").isEmpty()) {
            return InputSources.preserved(tag);
        }
        double scale = 1.0;
        double offset = 0.0;
        if (tag.contains("Scale")) {
            // A malformed or unusable transform is kept verbatim rather than guessed at.
            if (!tag.contains("Scale", Tag.TAG_ANY_NUMERIC) || !Double.isFinite(tag.getDouble("Scale"))
                    || tag.getDouble("Scale") == 0.0) {
                return InputSources.preserved(tag);
            }
            scale = tag.getDouble("Scale");
        }
        if (tag.contains("Offset")) {
            if (!tag.contains("Offset", Tag.TAG_ANY_NUMERIC) || !Double.isFinite(tag.getDouble("Offset"))) {
                return InputSources.preserved(tag);
            }
            offset = tag.getDouble("Offset");
        }
        return new PlantSource(new BlockPos(tag.getInt("X"), tag.getInt("Y"), tag.getInt("Z")), tag.getString("Key"),
                scale, offset);
    }
}
