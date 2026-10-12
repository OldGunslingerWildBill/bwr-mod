package dev.bwr.mod.panel.component;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/** Registry and codec for {@link InputSource} kinds. */
public final class InputSources {
    private InputSources() {
    }

    /** Constants are clamped to this magnitude; nothing on a panel needs more. */
    public static final double CONSTANT_LIMIT = 1.0e9;

    public static final String KIND_NONE = "none";
    public static final String KIND_CONSTANT = "constant";

    private static final Map<String, Function<CompoundTag, InputSource>> LOADERS = new ConcurrentHashMap<>();

    /** The empty source: the input reads "no signal". */
    public static final InputSource NONE = new InputSource() {
        @Override
        public String kind() {
            return KIND_NONE;
        }

        @Override
        public double sample(SampleContext context) {
            return Double.NaN;
        }

        @Override
        public void save(CompoundTag tag) {
        }
    };

    static {
        register(KIND_NONE, tag -> NONE);
        register(KIND_CONSTANT, tag -> constant(tag.getDouble("Value")));
    }

    public record Constant(double value) implements InputSource {
        @Override
        public String kind() {
            return KIND_CONSTANT;
        }

        @Override
        public double sample(SampleContext context) {
            return value;
        }

        @Override
        public void save(CompoundTag tag) {
            tag.putDouble("Value", value);
        }
    }

    /** A source whose kind this build does not know; kept verbatim so a save round-trips. */
    private record Unknown(String kind, CompoundTag raw) implements InputSource {
        @Override
        public double sample(SampleContext context) {
            return Double.NaN;
        }

        @Override
        public void save(CompoundTag tag) {
            tag.merge(raw);
        }
    }

    /**
     * A source kept verbatim and reading "no signal": what a kind's loader returns when the tag lacks the
     * fields it needs, so malformed or future data survives a load/save cycle instead of being rewritten.
     */
    public static InputSource preserved(CompoundTag tag) {
        CompoundTag raw = tag.copy();
        String kind = raw.getString("Kind");
        raw.remove("Kind");
        return new Unknown(kind, raw);
    }

    public static InputSource constant(double value) {
        if (!Double.isFinite(value)) {
            return NONE;
        }
        return new Constant(Math.max(-CONSTANT_LIMIT, Math.min(CONSTANT_LIMIT, value)));
    }

    /** Adds or replaces a kind. Call from mod setup before any world loads. */
    public static void register(String kind, Function<CompoundTag, InputSource> loader) {
        LOADERS.put(kind, loader);
    }

    public static boolean isKnown(String kind) {
        return LOADERS.containsKey(kind);
    }

    public static CompoundTag save(InputSource source) {
        CompoundTag tag = new CompoundTag();
        source.save(tag);
        tag.putString("Kind", source.kind());
        return tag;
    }

    public static InputSource load(CompoundTag tag) {
        String kind = tag.contains("Kind", Tag.TAG_STRING) ? tag.getString("Kind") : KIND_NONE;
        Function<CompoundTag, InputSource> loader = LOADERS.get(kind);
        if (loader == null) {
            CompoundTag raw = tag.copy();
            raw.remove("Kind");
            return new Unknown(kind, raw);
        }
        return loader.apply(tag);
    }
}
