package dev.bwr.mod.fuel;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import dev.bwr.core.fuel.FuelType;
import dev.bwr.core.fuel.FuelTypeSpec;

import java.util.Map;

/**
 * The {@link Codec} that turns a datapack entry into a {@link FuelType}.
 * SPEC 2.1.
 *
 * <h2>This class is transport, not meaning</h2>
 * Everything about what a fuel entry <i>means</i> — which fields exist, their
 * units, which are required, what an omitted optional field defaults to, and
 * which values are physically impossible — lives in {@link FuelTypeSpec} in the
 * pure-Java {@code :core} module, where it is covered by the acceptance suite
 * and needs no Minecraft to run. This class only carries a flat object of
 * numbers across the DataFixerUpper boundary and hands it over.
 *
 * <p>The split is deliberate. A second, Minecraft-side definition of what
 * {@code beta} means is exactly how a fuel ends up with different nuclear data
 * depending on whether it arrived from a datapack or from the presets.
 *
 * <h2>The name is not in the file</h2>
 * A fuel entry lives at {@code data/<namespace>/fuel_type/<name>.json} and is
 * named by that path, so the codec has to be told the name — hence
 * {@link #codecFor(String)} rather than a bare constant. Naming by file is what
 * lets a pack override a shipped fuel by writing {@code data/bwr/fuel_type/leu.json},
 * and it makes two fuels with the same name impossible rather than merely
 * discouraged.
 */
public final class FuelTypeCodec {

    private FuelTypeCodec() {
    }

    /**
     * The wire form: a flat object of numbers. Non-numeric members are a decode
     * failure here; {@link FuelTypeLoader} strips the ones Minecraft itself adds
     * to data files (conditions and the like) before it gets this far.
     */
    public static final Codec<Map<String, Double>> FIELDS_CODEC =
            Codec.unboundedMap(Codec.STRING, Codec.DOUBLE);

    /**
     * A codec for the fuel type stored under a given registry name.
     *
     * <p>Decode failures come back as {@link DataResult} errors carrying
     * {@link FuelTypeSpec}'s message verbatim — which names the fuel and the
     * offending field — so the loader can log something a pack author can act on
     * instead of a stack trace.
     */
    public static Codec<FuelType> codecFor(String name) {
        return FIELDS_CODEC.flatXmap(
                fields -> decode(name, fields),
                type -> DataResult.success(FuelTypeSpec.encode(type)));
    }

    /** Same as {@link #codecFor}'s decode half, for a caller that already has the map. */
    public static DataResult<FuelType> decode(String name, Map<String, Double> fields) {
        try {
            return DataResult.success(FuelTypeSpec.decode(name, fields));
        } catch (IllegalArgumentException | NullPointerException e) {
            return DataResult.error(() -> String.valueOf(e.getMessage()));
        }
    }
}
