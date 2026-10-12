package dev.bwr.mod.panel.component;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/** Registry and codec for {@link OutputSink} kinds; the output-side twin of {@link InputSources}. */
public final class OutputSinks {
    private OutputSinks() {
    }

    public static final String KIND_PLANT = "plant";
    public static final String KIND_REDSTONE = "redstone";

    private static final Map<String, Function<CompoundTag, OutputSink>> LOADERS = new ConcurrentHashMap<>();

    /** A sink whose kind this build does not know; kept verbatim so a save round-trips, and does nothing. */
    private record Unknown(String kind, CompoundTag raw) implements OutputSink {
        @Override
        public void save(CompoundTag tag) {
            tag.merge(raw);
        }
    }

    /** Adds or replaces a kind. Call from mod setup before any world loads. */
    public static void register(String kind, Function<CompoundTag, OutputSink> loader) {
        LOADERS.put(kind, loader);
    }

    public static boolean isKnown(String kind) {
        return LOADERS.containsKey(kind);
    }

    /** A sink kept verbatim (see {@link InputSources#preserved}). */
    public static OutputSink preserved(CompoundTag tag) {
        CompoundTag raw = tag.copy();
        String kind = raw.getString("Kind");
        raw.remove("Kind");
        return new Unknown(kind, raw);
    }

    public static CompoundTag save(OutputSink sink) {
        CompoundTag tag = new CompoundTag();
        sink.save(tag);
        tag.putString("Kind", sink.kind());
        return tag;
    }

    public static OutputSink load(CompoundTag tag) {
        String kind = tag.contains("Kind", Tag.TAG_STRING) ? tag.getString("Kind") : "";
        Function<CompoundTag, OutputSink> loader = LOADERS.get(kind);
        return loader == null ? preserved(tag) : loader.apply(tag);
    }
}
