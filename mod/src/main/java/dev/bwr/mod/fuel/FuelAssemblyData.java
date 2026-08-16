package dev.bwr.mod.fuel;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.bwr.core.fuel.FuelAssembly;
import dev.bwr.core.fuel.FuelType;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * Everything that has happened to one fuel bundle, carried on the itemstack.
 * SPEC 2.3.
 *
 * <h2>Why this lives on the item</h2>
 * Burnup on the item rather than on the core slot is the whole reason fuel
 * shuffling is a puzzle instead of a timer. Pull a partly-burned bundle out of
 * a hot central position, drop it on the periphery next cycle, and its exposure
 * travels with it in this component — so the loading pattern the player chose
 * three cycles ago is still visible in the k-infinity of the core today. Store
 * burnup in the multiblock instead and every position becomes interchangeable,
 * which quietly deletes the entire mechanic.
 *
 * <h2>Relationship to the physics</h2>
 * This record is <b>storage only</b>. It holds no nuclear data and computes no
 * reactivity: {@link dev.bwr.core.fuel.FuelAssembly} does all of that, and this
 * is the five numbers needed to reconstruct one exactly
 * ({@link FuelAssembly#restore}). Nothing here may ever disagree with the core
 * module — if a value is wanted, it is derived by round-tripping through
 * {@link FuelAssemblies#toCore(FuelAssemblyData)} rather than recomputed here.
 *
 * <p>In particular there is no "quality", "tier", "danger" or "condition" field.
 * A plutonium bundle is hazardous because its
 * {@link FuelType#beta() beta} is 0.002099 and the point kinetics does the rest;
 * there is deliberately nothing on the item that could be used to add a penalty
 * on top of that.
 *
 * <h2>Validation</h2>
 * The compact constructor clamps rather than throws. This is decoded from item
 * NBT written by possibly older versions of the mod, and an out-of-range double
 * must degrade into a sane bundle, not crash the decode and delete a player's
 * inventory.
 *
 * @param fuelTypeName             registry name of the fuel type, e.g. {@code "leu"}. Resolved
 *                                 through {@link FuelTypes}; the item layer is the only place a
 *                                 fuel name is allowed to exist (see {@link FuelType})
 * @param enrichmentWeightFraction fissile weight fraction as fabricated, dimensionless, in (0,1)
 * @param heavyMetalMassKg         heavy metal in the bundle, kg. Burnup is per tonne, so this is
 *                                 what converts exposure into energy actually produced
 * @param burnupMwdPerTonne        accumulated exposure, MWd per tonne of heavy metal
 * @param gadoliniaRemainingFraction fraction of the original burnable poison left, 1 fresh, 0 gone
 */
public record FuelAssemblyData(
        String fuelTypeName,
        double enrichmentWeightFraction,
        double heavyMetalMassKg,
        double burnupMwdPerTonne,
        double gadoliniaRemainingFraction
) {

    /**
     * Upper clamp on enrichment. {@link FuelAssembly} requires strictly less
     * than 1, and a bundle of pure fissile metal is a criticality accident
     * rather than a fuel assembly anyway.
     */
    public static final double MAX_ENRICHMENT_WEIGHT_FRACTION = 0.99;

    /** Lower clamp on enrichment. Below this there is no fuel, only heavy metal. */
    public static final double MIN_ENRICHMENT_WEIGHT_FRACTION = 1.0e-4;

    public FuelAssemblyData {
        if (fuelTypeName == null || fuelTypeName.isEmpty()) {
            fuelTypeName = FuelType.LEU.name();
        }
        enrichmentWeightFraction = clamp(enrichmentWeightFraction,
                MIN_ENRICHMENT_WEIGHT_FRACTION, MAX_ENRICHMENT_WEIGHT_FRACTION,
                FuelType.LEU.nominalEnrichmentWeightFraction());
        heavyMetalMassKg = clamp(heavyMetalMassKg, 1.0e-3, 1.0e6,
                FuelAssembly.REFERENCE_HEAVY_METAL_MASS_KG);
        burnupMwdPerTonne = clamp(burnupMwdPerTonne, 0.0, 1.0e9, 0.0);
        gadoliniaRemainingFraction = clamp(gadoliniaRemainingFraction, 0.0, 1.0, 1.0);
    }

    public static final Codec<FuelAssemblyData> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    Codec.STRING.fieldOf("fuel_type").forGetter(FuelAssemblyData::fuelTypeName),
                    Codec.DOUBLE.fieldOf("enrichment").forGetter(FuelAssemblyData::enrichmentWeightFraction),
                    Codec.DOUBLE.fieldOf("heavy_metal_kg").forGetter(FuelAssemblyData::heavyMetalMassKg),
                    Codec.DOUBLE.fieldOf("burnup_mwd_per_tonne").forGetter(FuelAssemblyData::burnupMwdPerTonne),
                    Codec.DOUBLE.fieldOf("gadolinia_remaining").forGetter(FuelAssemblyData::gadoliniaRemainingFraction)
            ).apply(instance, FuelAssemblyData::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, FuelAssemblyData> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, FuelAssemblyData::fuelTypeName,
                    ByteBufCodecs.DOUBLE, FuelAssemblyData::enrichmentWeightFraction,
                    ByteBufCodecs.DOUBLE, FuelAssemblyData::heavyMetalMassKg,
                    ByteBufCodecs.DOUBLE, FuelAssemblyData::burnupMwdPerTonne,
                    ByteBufCodecs.DOUBLE, FuelAssemblyData::gadoliniaRemainingFraction,
                    FuelAssemblyData::new);

    /** A never-irradiated bundle of the given fuel at its nominal enrichment and standard mass. */
    public static FuelAssemblyData fresh(FuelType type) {
        return fresh(type, type.nominalEnrichmentWeightFraction());
    }

    /** A never-irradiated bundle at a chosen enrichment — what the fabricator produces. */
    public static FuelAssemblyData fresh(FuelType type, double enrichmentWeightFraction) {
        return new FuelAssemblyData(type.name(), enrichmentWeightFraction,
                FuelAssembly.REFERENCE_HEAVY_METAL_MASS_KG, 0.0, 1.0);
    }

    /** Snapshot of a live core assembly, for writing exposure back onto the item. */
    public static FuelAssemblyData of(FuelAssembly assembly) {
        return new FuelAssemblyData(
                assembly.fuelType().name(),
                assembly.enrichmentWeightFraction(),
                assembly.heavyMetalMassKg(),
                assembly.burnupMwdPerTonne(),
                assembly.gadoliniaRemainingFraction());
    }

    /** This bundle with a different exposure. Records are immutable; the itemstack is rewritten. */
    public FuelAssemblyData withExposure(double newBurnupMwdPerTonne, double newGadoliniaRemaining) {
        return new FuelAssemblyData(fuelTypeName, enrichmentWeightFraction, heavyMetalMassKg,
                newBurnupMwdPerTonne, newGadoliniaRemaining);
    }

    /** True once this bundle has seen any flux at all. Purely descriptive. */
    public boolean isIrradiated() {
        return burnupMwdPerTonne > 0.0;
    }

    private static double clamp(double value, double min, double max, double fallback) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return fallback;
        }
        return value < min ? min : Math.min(value, max);
    }
}
