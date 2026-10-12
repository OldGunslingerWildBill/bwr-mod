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
 */
public final class PlantSource implements InputSource {
    public static final String KIND = "plant";

    private final BlockPos device;
    private final String key;

    public PlantSource(BlockPos device, String key) {
        this.device = device.immutable();
        this.key = key;
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
        return value.isPresent() ? value.getAsDouble() : Double.NaN;
    }

    @Override
    public void save(CompoundTag tag) {
        tag.putInt("X", device.getX());
        tag.putInt("Y", device.getY());
        tag.putInt("Z", device.getZ());
        tag.putString("Key", key);
    }

    /** Loader: a tag without position and key is kept verbatim rather than rewritten. */
    static InputSource load(CompoundTag tag) {
        if (!tag.contains("X", Tag.TAG_ANY_NUMERIC) || !tag.contains("Y", Tag.TAG_ANY_NUMERIC)
                || !tag.contains("Z", Tag.TAG_ANY_NUMERIC) || !tag.contains("Key", Tag.TAG_STRING)
                || tag.getString("Key").isEmpty()) {
            return InputSources.preserved(tag);
        }
        return new PlantSource(new BlockPos(tag.getInt("X"), tag.getInt("Y"), tag.getInt("Z")), tag.getString("Key"));
    }
}
