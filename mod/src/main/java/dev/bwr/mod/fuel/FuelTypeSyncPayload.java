package dev.bwr.mod.fuel;

import dev.bwr.core.fuel.FuelType;
import dev.bwr.core.fuel.FuelTypeSpec;
import dev.bwr.mod.BwrMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The server's SPEC 2.1 fuel registry, on the wire.
 *
 * <h2>Why this packet exists</h2>
 * {@link FuelTypes} is filled from {@code data/&lt;namespace&gt;/fuel_type/*.json}
 * by {@link FuelTypeLoader}, which is a datapack reload listener and therefore
 * server-side only. Without this packet a dedicated-server client keeps
 * {@link FuelType#presets()} forever, and
 * {@link FuelAssemblyItem#appendHoverText} — which runs client-side — computes
 * k-infinity, beta and prompt lifetime from those presets.
 *
 * <p>That was survivable for a pack that <i>adds</i> a fuel, because an unknown
 * name resolves through {@link FuelTypes#byNameOrFallback} and the tooltip says
 * so. It was not survivable for the override mechanism SPEC 2.1 actually
 * documents: a pack writing {@code data/bwr/fuel_type/leu.json} keeps the name
 * and changes the numbers, so the name still resolved on the client, no warning
 * appeared, and the player read the shipped preset's nuclear data as if it were
 * this server's. A player planning rod moves against a delayed neutron fraction
 * of 0.00650 when the server is running 0.00300 is being lied to about the
 * single number that decides how much control margin the core has.
 *
 * <h2>Shape</h2>
 * A list of (name, flat map of numbers) — exactly the datapack entry form, with
 * the name that a datapack takes from the file path carried alongside. Encoding
 * through {@link FuelTypeSpec#encode} rather than through a hand-written field
 * list is deliberate: {@code FuelTypeSpec} is the single definition of what a
 * fuel entry contains, so a parameter added there travels over the network
 * without anyone remembering to widen this packet. Hand-written field lists rot
 * silently; this one cannot.
 *
 * <p>The wire form is transport only. Turning a map of numbers back into a
 * {@link FuelType} — including every range check — is
 * {@link FuelTypeSpec#decode}'s job and happens in {@link FuelTypeSync}, so a
 * malformed entry is dropped with a log line instead of killing the connection
 * from inside the netty pipeline.
 */
public record FuelTypeSyncPayload(List<Entry> entries) implements CustomPacketPayload {

    /**
     * Ceiling on how many fuel types one packet may carry. Generous: the mod
     * ships five and a pack adding a hundred is unremarkable. It exists only so
     * a corrupt length cannot make the client allocate without bound.
     */
    public static final int MAX_ENTRIES = 4096;

    public static final CustomPacketPayload.Type<FuelTypeSyncPayload> TYPE =
            new CustomPacketPayload.Type<>(BwrMod.id("fuel_type_sync"));

    /**
     * One fuel type as a datapack entry: the registry name, and the flat object
     * of numbers {@link FuelTypeSpec} defines.
     */
    public record Entry(String name, Map<String, Double> fields) {

        /**
         * Ceiling on fields per entry. {@link FuelTypeSpec#fieldNames()} is nine
         * today; the slack is for parameters added later, and the cap is here
         * for the same allocation reason as {@link #MAX_ENTRIES}.
         */
        public static final int MAX_FIELDS = 256;

        // Declared inside Entry, not in the enclosing record, so that Entry's
        // static initialiser depends on nothing in FuelTypeSyncPayload. The
        // enclosing STREAM_CODEC reads Entry.STREAM_CODEC, and a field the two
        // classes shared would make that a circular class initialisation whose
        // outcome depends on which one is touched first.
        private static final StreamCodec<ByteBuf, Map<String, Double>> FIELDS_STREAM_CODEC =
                ByteBufCodecs.map(LinkedHashMap::new,
                        ByteBufCodecs.STRING_UTF8, ByteBufCodecs.DOUBLE, MAX_FIELDS);

        public static final StreamCodec<ByteBuf, Entry> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, Entry::name,
                FIELDS_STREAM_CODEC, Entry::fields,
                Entry::new);

        public static Entry of(FuelType type) {
            return new Entry(type.name(), FuelTypeSpec.encode(type));
        }
    }

    public static final StreamCodec<ByteBuf, FuelTypeSyncPayload> STREAM_CODEC =
            ByteBufCodecs.<ByteBuf, Entry, List<Entry>>collection(
                            ArrayList::new, Entry.STREAM_CODEC, MAX_ENTRIES)
                    .map(FuelTypeSyncPayload::new, FuelTypeSyncPayload::entries);

    /** The whole registry as one packet. */
    public static FuelTypeSyncPayload of(Collection<FuelType> types) {
        List<Entry> entries = new ArrayList<>(types.size());
        for (FuelType type : types) {
            entries.add(Entry.of(type));
        }
        return new FuelTypeSyncPayload(entries);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
