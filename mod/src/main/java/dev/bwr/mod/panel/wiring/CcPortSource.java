package dev.bwr.mod.panel.wiring;

import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.PanelConfig;
import dev.bwr.mod.panel.MachinePorts;
import dev.bwr.mod.panel.component.InputSource;
import dev.bwr.mod.panel.component.InputSources;
import dev.bwr.mod.signal.SignalProvider;
import dev.bwr.mod.signal.SignalProviders;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.List;
import java.util.OptionalDouble;

/**
 * Input source {@code cc_port}: one signal of the machine that answers on a CC port (0000 to 9999) of the CC wired
 * network the panel is on. This is not line linking: nothing names or draws lines for it, and the machine is found by
 * its port, never by position. A port with no machine, or with two machines on it, reads NaN ("no signal"): nothing is
 * guessed. A machine in another dimension, beyond {@code linkRange}, or in an unloaded chunk reads NaN too; sampling
 * never loads a chunk. The same transform as a plant source applies: {@code raw * scale + offset}.
 *
 * <p>Saved as {@code Port}, {@code Key}, and {@code Scale} / {@code Offset} only when they are not the defaults.
 */
public final class CcPortSource implements InputSource {
    public static final String KIND = "cc_port";

    private final int port;
    private final String key;
    private final double scale;
    private final double offset;

    public CcPortSource(int port, String key, double scale, double offset) {
        this.port = port;
        this.key = key;
        this.scale = scale;
        this.offset = offset;
    }

    public int port() {
        return port;
    }

    public String key() {
        return key;
    }

    public double scale() {
        return scale;
    }

    public double offset() {
        return offset;
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
        PanelBlockEntity panel = context.owner();
        Level level = context.level();
        if (panel == null || level == null || level.isClientSide() || !MachinePorts.valid(port)) {
            return Double.NaN;
        }
        List<BlockEntity> machines = CcPortBridge.machinesOnPort(panel, port);
        if (machines.size() != 1) {
            return Double.NaN; // nothing there, or a conflict
        }
        BlockEntity machine = machines.get(0);
        long range = PanelConfig.linkRange();
        if (machine.isRemoved() || machine.getLevel() != level || !level.isLoaded(machine.getBlockPos())
                || machine.getBlockPos().distSqr(panel.getBlockPos()) > range * range) {
            return Double.NaN;
        }
        SignalProvider provider = SignalProviders.of(machine);
        if (provider == null) {
            return Double.NaN;
        }
        OptionalDouble value = provider.read(key);
        return value.isPresent() ? value.getAsDouble() * scale + offset : Double.NaN;
    }

    @Override
    public void save(CompoundTag tag) {
        tag.putInt("Port", port);
        tag.putString("Key", key);
        if (scale != 1.0) {
            tag.putDouble("Scale", scale);
        }
        if (offset != 0.0) {
            tag.putDouble("Offset", offset);
        }
    }

    /** Loader: a tag without a usable port and key is kept verbatim rather than rewritten. */
    static InputSource load(CompoundTag tag) {
        if (!tag.contains("Port", Tag.TAG_ANY_NUMERIC) || !MachinePorts.valid(tag.getInt("Port"))
                || !tag.contains("Key", Tag.TAG_STRING) || tag.getString("Key").isEmpty()) {
            return InputSources.preserved(tag);
        }
        double scale = 1.0;
        double offset = 0.0;
        if (tag.contains("Scale")) {
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
        return new CcPortSource(tag.getInt("Port"), tag.getString("Key"), scale, offset);
    }
}
