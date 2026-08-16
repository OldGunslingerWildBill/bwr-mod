package dev.bwr.mod.fuel;

import dev.bwr.core.fuel.FuelType;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Name-to-{@link FuelType} lookup for the item layer.
 *
 * <p>{@code FuelType} is explicit that names exist for the item layer, the
 * refuelling GUI and the datapack loader and for nothing else — physics code
 * that branches on a fuel name is a bug. This class is that item layer: the
 * itemstack has to persist <i>something</i> identifying which nuclear data a
 * bundle was built with, and a stable string is the only thing that survives a
 * datapack reload and a world reload.
 *
 * <h2>Where the contents come from</h2>
 * {@link FuelTypeLoader} fills this from {@code data/&lt;namespace&gt;/fuel_type/*.json}
 * on every datapack load, which is the SPEC 2.1 registry. {@link FuelType#presets()}
 * is what the table holds before the first load and what it falls back to if a
 * pack somehow supplies no usable entries at all — the shipped
 * {@code data/bwr/fuel_type/} files are byte-for-byte those presets, so in a
 * normal install the two are the same five fuels and the swap is invisible.
 *
 * <p>A datapack load is server-side, so on a client this table is filled
 * instead by {@link FuelTypeSync}, which carries the server's copy over on login
 * and after every {@code /reload}. Both writers go through
 * {@link #replaceAll(Collection)}; there is no third.
 *
 * <h2>Threading</h2>
 * The datapack load runs off-thread and the map is read from the server thread,
 * the client thread and any Lua callback, so the reference is {@code volatile}
 * and every map it ever points at is immutable. Readers see either the whole old
 * table or the whole new one, never a half-built one.
 */
public final class FuelTypes {

    private FuelTypes() {
    }

    private static volatile Map<String, FuelType> byName = index(FuelType.presets());

    /**
     * Fallback when an itemstack names a fuel type this install does not have.
     *
     * <p>Prefers whatever the registry has under {@link FuelType#LEU}'s name, so
     * a pack that retunes LEU retunes the fallback with it, and drops back to the
     * compiled preset only if the registry has no such entry.
     */
    public static FuelType fallback() {
        // One read of the volatile field, so this cannot answer out of two
        // different tables halfway through a reload.
        Map<String, FuelType> table = byName;
        FuelType leu = table.get(FuelType.LEU.name());
        if (leu != null) {
            return leu;
        }
        // No LEU at all. Any fuel beats inventing one, and the compiled preset
        // beats nothing.
        for (FuelType type : table.values()) {
            return type;
        }
        return FuelType.LEU;
    }

    /** The named fuel type, or null if this install has never heard of it. */
    public static FuelType byName(String name) {
        return name == null ? null : byName.get(name);
    }

    /**
     * The named fuel type: from the registry, else from the compiled presets,
     * else {@link #fallback()}.
     *
     * <p>Substituting LEU for a fuel type that has vanished is a deliberate
     * choice: the bundle keeps its exposure and its enrichment, so it stays
     * recognisable and stays worth what it was worth, and the player can see
     * from the tooltip that it is not what they put in the chest.
     *
     * <p><b>The preset step is not optional, do not remove it.</b> It is what
     * makes this method and {@link #byNameOrPreset} agree, and they have to
     * agree because they are the two halves of one round trip: the fabricator
     * resolves a name through {@code byNameOrPreset} and stamps that name onto
     * the itemstack, and every later read of that stack — the tooltip, the
     * refuelling GUI, loading it into the core — comes back through here. When
     * only the fabricator consulted the presets, a name it could reach was a
     * name this method could not, so the guard in
     * {@link FuelFabrication#select} did not prevent the failure it was written
     * for; it relabelled it. A bundle stamped {@code plutonium} was run by the
     * core with LEU's beta of 0.006502 instead of 0.002099 — 3.1x the control
     * margin the fuel actually has, which is the exact number this whole model
     * exists to get right.
     *
     * <p>The reachable trigger is not a pack maliciously deleting a fuel. It is
     * a typo: {@link FuelTypeLoader} drops any entry that fails to decode, so
     * one misspelled field in a pack's {@code plutonium.json} removes plutonium
     * from the registry while leaving it perfectly reachable at fabrication
     * time.
     *
     * <p>Registry first, always — a pack that <i>retunes</i> a shipped fuel
     * still wins, which is the SPEC 2.1 override mechanism.
     */
    public static FuelType byNameOrFallback(String name) {
        FuelType type = byNameOrPreset(name);
        return type != null ? type : fallback();
    }

    /**
     * True when the name is in <i>this install's registry</i> — not merely
     * resolvable.
     *
     * <p>Narrower than {@link #byNameOrFallback} on purpose: false here means
     * the bundle's nuclear data came from the compiled presets rather than from
     * the loaded fuel table, which the tooltip reports as provenance. Do not use
     * it to decide whether a substitution happened; compare the resolved
     * {@link FuelType#name()} against the stored name for that, because a
     * preset-resolved fuel is not a substituted one.
     */
    public static boolean isKnown(String name) {
        return byName(name) != null;
    }

    /** Every fuel type this install knows, in the order the loader supplied them. */
    public static Collection<FuelType> all() {
        return byName.values();
    }

    /**
     * The named fuel type, or the compiled preset of that name, or null.
     *
     * <p>For the caller that must not go through {@link #fallback()}: the
     * fabricator, which asks for a specific fuel by name and would otherwise
     * silently turn a plutonium batch into LEU if a pack deleted the plutonium
     * entry. {@link #byNameOrFallback} is this method plus that last fallback,
     * so anything reachable here stays reachable when the itemstack is read
     * back.
     */
    public static FuelType byNameOrPreset(String name) {
        FuelType type = byName(name);
        if (type != null) {
            return type;
        }
        for (FuelType preset : FuelType.presets()) {
            if (preset.name().equals(name)) {
                return preset;
            }
        }
        return null;
    }

    /**
     * Replaces the whole table. Called by {@link FuelTypeLoader} once per
     * datapack load on the server, by {@link FuelTypeSync} once per sync on the
     * client, and by nothing else.
     *
     * <p>Entries are indexed by {@link FuelType#name()}, so a later entry with
     * the same name wins — which is how a pack overrides a shipped fuel.
     */
    public static void replaceAll(Collection<FuelType> types) {
        byName = index(types);
    }

    /**
     * Puts the compiled presets back. Used when a datapack load produced nothing
     * usable, so the fuel bundles already in the world still resolve to
     * something with the right nuclear data.
     */
    public static void resetToDefaults() {
        byName = index(FuelType.presets());
    }

    private static Map<String, FuelType> index(Collection<FuelType> types) {
        Map<String, FuelType> map = new LinkedHashMap<>();
        for (FuelType type : types) {
            map.put(type.name(), type);
        }
        return java.util.Collections.unmodifiableMap(map);
    }
}
