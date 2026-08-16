package dev.bwr.mod.fuel;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import dev.bwr.core.fuel.FuelType;
import dev.bwr.core.fuel.FuelTypeSpec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Loads {@code data/&lt;namespace&gt;/fuel_type/*.json} into the fuel registry.
 * SPEC 2.1.
 *
 * <h2>Why a reload listener and not a registry</h2>
 * A vanilla datapack registry would be tidier, and it would also abort world
 * load on a single bad entry. Fuel entries are the one thing in this mod a
 * player is most likely to hand-edit — the whole point of SPEC 2.1 is that
 * adding a fuel takes a text file and no Java — and a typo in one of them must
 * cost that fuel, not the save. So this is a plain reload listener that decodes
 * entries one at a time, logs whatever went wrong with the name of the file and
 * the field, and carries on with the rest.
 *
 * <h2>Naming</h2>
 * The file names the fuel. An entry at {@code data/bwr/fuel_type/leu.json} is
 * the fuel type {@code leu} — the same string the shipped preset uses and the
 * same string sitting in the data component of every fuel bundle in every chest
 * on the server, so replacing that file retunes existing bundles rather than
 * orphaning them. An entry from any other namespace keeps its namespace
 * ({@code mypack:super_fuel}), which makes collisions between packs impossible.
 *
 * <h2>What the client sees</h2>
 * Datapacks are server state, so this listener runs only on the server. The
 * client gets the resulting table from {@link FuelTypeSync}, which sends it on
 * login and after every {@code /reload}; in single player the integrated server
 * shares these statics with the client anyway and the packet is a no-op.
 *
 * <p>That packet is load-bearing rather than cosmetic. The fuel bundle tooltip
 * computes k-infinity, beta and prompt lifetime client-side, and SPEC 2.1's
 * override mechanism — writing {@code data/bwr/fuel_type/leu.json} — keeps the
 * fuel's <i>name</i> and changes only its numbers, so a client left holding the
 * compiled presets resolves the name perfectly happily and reports the wrong
 * physics with nothing to flag it. Physics itself is unaffected either way: it
 * runs server-side against the server's registry.
 */
public final class FuelTypeLoader extends SimpleJsonResourceReloadListener {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** {@code data/&lt;namespace&gt;/fuel_type/}. */
    public static final String DIRECTORY = "fuel_type";

    private static final Gson GSON = new Gson();

    /**
     * Members a fuel entry may carry that are not fuel parameters. Data files
     * generically grow these, and an entry carrying one is dropped down to its
     * numeric fields rather than rejected as malformed.
     *
     * <p><b>Known limitation:</b> a {@code neoforge:conditions} block is
     * discarded, not evaluated — a conditioned fuel entry loads unconditionally.
     * Evaluating it needs the reload's {@code ICondition.IContext}, which reaches
     * a listener only through {@code ContextAwareReloadListener}, and that is an
     * abstract class this one cannot also extend. Worth revisiting if a pack ever
     * wants a fuel that appears only alongside another mod.
     */
    private static final List<String> IGNORED_MEMBERS = List.of(
            "neoforge:conditions", "fabric:load_conditions", "replace", "type");

    public FuelTypeLoader() {
        super(GSON, DIRECTORY);
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> entries,
                         ResourceManager resourceManager,
                         ProfilerFiller profiler) {
        // TreeMap so the registry is built in a stable order no matter what
        // order the resource manager walked the packs in.
        Map<ResourceLocation, JsonElement> ordered = new TreeMap<>(
                Comparator.comparing(ResourceLocation::toString));
        ordered.putAll(entries);

        List<FuelType> loaded = new ArrayList<>();
        int rejected = 0;

        for (Map.Entry<ResourceLocation, JsonElement> entry : ordered.entrySet()) {
            ResourceLocation id = entry.getKey();
            String name = registryNameOf(id);
            try {
                JsonObject object = asFuelObject(name, entry.getValue());
                DataResult<FuelType> result = FuelTypeCodec.codecFor(name)
                        .parse(JsonOps.INSTANCE, object);
                if (result.error().isPresent()) {
                    rejected++;
                    LOGGER.error("Skipping fuel type {} ({}.json): {}",
                            name, id, result.error().get().message());
                    continue;
                }
                loaded.add(result.result().orElseThrow());
            } catch (IllegalArgumentException e) {
                // An authoring mistake. One line naming the file and the field
                // is what the pack author needs; a stack trace is noise.
                rejected++;
                LOGGER.error("Skipping fuel type {} ({}.json): {}", name, id, e.getMessage());
            } catch (RuntimeException e) {
                // Belt and braces. Nothing above should get here, and if
                // something does it still must not take the datapack load with
                // it — but that one does deserve a stack trace.
                rejected++;
                LOGGER.error("Skipping fuel type {} ({}.json)", name, id, e);
            }
        }

        if (loaded.isEmpty()) {
            // Every entry was bad, or there were none at all — a resource pack
            // problem rather than a fuel problem. Running with no fuel types at
            // all would make every bundle in the world unresolvable, so the
            // compiled presets stay in place and the reason is logged.
            FuelTypes.resetToDefaults();
            LOGGER.warn("No usable {} entries found ({} rejected); "
                            + "keeping the {} built-in fuel types",
                    DIRECTORY, rejected, FuelTypes.all().size());
            return;
        }

        FuelTypes.replaceAll(loaded);
        LOGGER.info("Loaded {} fuel type(s) from datapack{}: {}",
                loaded.size(),
                rejected > 0 ? " (" + rejected + " rejected, see above)" : "",
                FuelTypes.all().stream().map(FuelType::name).toList());
    }

    /**
     * Drops members that are not fuel parameters, so a condition block or a
     * {@code "type"} key does not fail the whole entry, and rejects anything
     * that is not a flat object of numbers with a message naming the member.
     */
    private static JsonObject asFuelObject(String name, JsonElement element) {
        if (!(element instanceof JsonObject source)) {
            throw new IllegalArgumentException("fuel type '" + name + "': expected a JSON object, got "
                    + element.getClass().getSimpleName());
        }
        JsonObject fields = new JsonObject();
        for (Map.Entry<String, JsonElement> member : source.entrySet()) {
            String key = member.getKey();
            if (IGNORED_MEMBERS.contains(key)) {
                continue;
            }
            JsonElement value = member.getValue();
            if (!(value instanceof JsonPrimitive primitive) || !primitive.isNumber()) {
                throw new IllegalArgumentException("fuel type '" + name + "': field '" + key
                        + "' must be a number; a fuel entry is a flat object of numbers ("
                        + FuelTypeSpec.fieldNames() + ")");
            }
            fields.add(key, value);
        }
        return fields;
    }

    /**
     * The registry name of an entry. {@code bwr:leu} is {@code leu} so shipped
     * fuels, presets and existing itemstacks all spell the same string; anything
     * else keeps its namespace.
     */
    public static String registryNameOf(ResourceLocation id) {
        return dev.bwr.mod.BwrMod.MOD_ID.equals(id.getNamespace()) ? id.getPath() : id.toString();
    }
}
