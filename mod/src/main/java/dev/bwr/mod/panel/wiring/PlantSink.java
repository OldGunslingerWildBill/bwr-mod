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
 * <p>Mode {@link Mode#FOLLOW} (the default, and what every save without a {@code Mode} key loads as) sends that
 * value on every change. Mode {@link Mode#MOMENTARY} is for momentary inputs (a spring-return control switch,
 * a pushbutton) driving a BOOLEAN or LEVEL command: when the output takes a non-zero value it sends the fixed
 * configured command value once, and the return to zero sends nothing, so the equipment seals in the way a
 * real GE switch's contacts do. It keeps no state of its own: only a player or CC operated output change
 * reaches {@link #fire}, so a load, resample or rebind never sends anything.
 *
 * <p>An unloaded or missing device, an unknown key or a refusal simply does nothing; the sink never loads a
 * chunk and never retries.
 */
public final class PlantSink implements OutputSink {
    public static final String KIND = OutputSinks.KIND_PLANT;
    private static final Logger LOGGER = LogUtils.getLogger();

    /** How an output change becomes a command value. The ordinal travels in {@code PanelPayloads.Bind}. */
    public enum Mode {
        /** Send {@code value * scale} on every change. */
        FOLLOW,
        /** Send the fixed value when the output becomes non-zero; ignore the return to zero. */
        MOMENTARY;

        public static Mode byId(int id) {
            Mode[] all = values();
            return id >= 0 && id < all.length ? all[id] : null;
        }
    }

    private final BlockPos device;
    private final String key;
    private final double scale;
    private final Mode mode;
    private final double fixed;

    public PlantSink(BlockPos device, String key, double scale) {
        this(device, key, scale, Mode.FOLLOW, 0.0);
    }

    public PlantSink(BlockPos device, String key, double scale, Mode mode, double fixed) {
        this.device = device.immutable();
        this.key = key;
        this.scale = scale;
        this.mode = mode;
        this.fixed = fixed;
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

    public Mode mode() {
        return mode;
    }

    /** The command value a MOMENTARY sink sends (unused in FOLLOW). */
    public double fixed() {
        return fixed;
    }

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public void fire(FireContext context, double value) {
        if (Double.isNaN(value) || mode == Mode.MOMENTARY && value == 0.0) {
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
            provider.command(key, mode == Mode.MOMENTARY ? fixed : value * scale);
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
        if (mode == Mode.MOMENTARY) {
            // Absent means FOLLOW, so saves from before the mode existed load unchanged.
            tag.putString("Mode", "momentary");
            tag.putDouble("Fixed", fixed);
        }
    }

    static OutputSink load(CompoundTag tag) {
        if (!tag.contains("X", Tag.TAG_ANY_NUMERIC) || !tag.contains("Y", Tag.TAG_ANY_NUMERIC)
                || !tag.contains("Z", Tag.TAG_ANY_NUMERIC) || !tag.contains("Key", Tag.TAG_STRING)
                || tag.getString("Key").isEmpty()) {
            return OutputSinks.preserved(tag);
        }
        double scale = tag.contains("Scale", Tag.TAG_ANY_NUMERIC) && Double.isFinite(tag.getDouble("Scale"))
                ? tag.getDouble("Scale") : 1.0;
        Mode mode = Mode.FOLLOW;
        double fixed = 0.0;
        if (tag.contains("Mode")) {
            // An unknown mode or a missing fixed value is kept inert rather than guessed at.
            if (!tag.contains("Mode", Tag.TAG_STRING) || !"momentary".equals(tag.getString("Mode"))
                    || !tag.contains("Fixed", Tag.TAG_ANY_NUMERIC) || !Double.isFinite(tag.getDouble("Fixed"))) {
                return OutputSinks.preserved(tag);
            }
            mode = Mode.MOMENTARY;
            fixed = tag.getDouble("Fixed");
        }
        return new PlantSink(new BlockPos(tag.getInt("X"), tag.getInt("Y"), tag.getInt("Z")), tag.getString("Key"), scale,
                mode, fixed);
    }
}
