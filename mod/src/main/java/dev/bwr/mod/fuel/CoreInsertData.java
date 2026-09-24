package dev.bwr.mod.fuel;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.bwr.core.fuel.CoreInsert;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/** Exposure belongs to a single cassette and survives unloading, trading and world reloads. */
public record CoreInsertData(CoreInsert.Kind kind, double exposureSeconds) {
    private static final Codec<CoreInsert.Kind> KIND = Codec.STRING.comapFlatMap(id -> {
        for (var k : CoreInsert.Kind.values()) if (k.id().equals(id)) return DataResult.success(k);
        return DataResult.error(() -> "Unknown core insert: " + id);
    }, CoreInsert.Kind::id);
    public static final Codec<CoreInsertData> CODEC = RecordCodecBuilder.create(i -> i.group(
            KIND.fieldOf("kind").forGetter(CoreInsertData::kind),
            Codec.DOUBLE.optionalFieldOf("exposure_seconds", 0.0).forGetter(CoreInsertData::exposureSeconds)
    ).apply(i, CoreInsertData::new));
    public static final StreamCodec<RegistryFriendlyByteBuf, CoreInsertData> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, d -> d.kind.id(), ByteBufCodecs.DOUBLE, CoreInsertData::exposureSeconds,
            (id, exposure) -> new CoreInsertData(CoreInsert.Kind.valueOf(id.toUpperCase(java.util.Locale.ROOT)), exposure));
    public CoreInsertData { exposureSeconds = new CoreInsert(kind, exposureSeconds).exposureSeconds(); }
    public CoreInsert toCore() { return new CoreInsert(kind, exposureSeconds); }
    public static CoreInsertData of(CoreInsert insert) { return new CoreInsertData(insert.kind(), insert.exposureSeconds()); }
}
