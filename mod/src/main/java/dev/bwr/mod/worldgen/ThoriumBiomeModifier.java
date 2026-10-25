package dev.bwr.mod.worldgen;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.bwr.mod.config.GameplayConfig;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.neoforged.neoforge.common.world.BiomeModifier;
import net.neoforged.neoforge.common.world.ModifiableBiomeInfo;

/**
 * {@code neoforge:add_features}, with the server option {@code thoriumOreGeneration} as its condition: when the option is
 * off the modifier adds nothing, and the biomes keep exactly the features they have without this mod.
 *
 * <p>Why a modifier type of our own and not a {@code neoforge:conditions} entry on the stock one: a NeoForge condition on
 * a datapack file is evaluated while the data is loaded, which is before the world's server config exists, so it could
 * only ever read the default. A biome modifier runs when the server starts, after the server config is loaded, so this is
 * the first place the option can be read. The feature checks the option again when it places, which covers a change made
 * while the server runs.
 *
 * <p>The JSON is the same as the stock type's: {@code biomes} (an id, a list or a {@code #tag}), {@code features} (placed
 * features) and {@code step}.
 */
public record ThoriumBiomeModifier(HolderSet<Biome> biomes, HolderSet<PlacedFeature> features,
                                   GenerationStep.Decoration step) implements BiomeModifier {

    public static final MapCodec<ThoriumBiomeModifier> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Biome.LIST_CODEC.fieldOf("biomes").forGetter(ThoriumBiomeModifier::biomes),
            PlacedFeature.LIST_CODEC.fieldOf("features").forGetter(ThoriumBiomeModifier::features),
            GenerationStep.Decoration.CODEC.fieldOf("step").forGetter(ThoriumBiomeModifier::step)
    ).apply(i, ThoriumBiomeModifier::new));

    @Override
    public void modify(Holder<Biome> biome, Phase phase, ModifiableBiomeInfo.BiomeInfo.Builder builder) {
        if (phase == Phase.ADD && GameplayConfig.thoriumOreGeneration() && biomes.contains(biome)) {
            features.forEach(feature -> builder.getGenerationSettings().addFeature(step, feature));
        }
    }

    @Override
    public MapCodec<? extends BiomeModifier> codec() {
        return CODEC;
    }
}
