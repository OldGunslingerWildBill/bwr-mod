package dev.bwr.mod.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration;

/**
 * What one biome group's uranium vein feature does (a {@code configured_feature} of type {@code bwr:uranium_vein}).
 * Every number here is read from the datapack JSON, so a pack can retune it.
 *
 * @param veinChance      share of grid cells that have a vein, 0 to 1, in chunks of this biome group. A vein that exists at
 *                        a chance exists at every higher chance, so a lower chance only removes veins
 * @param geometry        the shape of the veins; see {@link UraniumVeins.Geometry}
 * @param oreDensity      share of the vein body that is ore, at the centre line (the grade); the rest is filler
 * @param rawBlockChance  share of the ore spots that are raw uranium blocks
 * @param minDepth        a block of the vein is not placed within this many blocks of the surface, so the veins are
 *                        found by digging and in caves, not lying on the ground
 * @param host            the blocks a vein may replace (never air, water, other ores or anything built)
 * @param oreStone        ore in an ordinary host rock
 * @param oreDeepslate    ore in deepslate
 * @param rawBlock        the raw uranium block
 * @param fillStone       what the host rock becomes inside the body
 * @param fillDeepslate   what deepslate becomes inside the body
 */
public record UraniumVeinConfig(float veinChance, UraniumVeins.Geometry geometry, float oreDensity, float rawBlockChance,
                                int minDepth, TagKey<Block> host, BlockState oreStone, BlockState oreDeepslate,
                                BlockState rawBlock, BlockState fillStone, BlockState fillDeepslate) implements FeatureConfiguration {

    public static final TagKey<Block> DEFAULT_HOST = TagKey.create(Registries.BLOCK,
            ResourceLocation.fromNamespaceAndPath("bwr", "uranium_host_rock"));

    public static final Codec<UraniumVeinConfig> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.floatRange(0f, 1f).fieldOf("vein_chance").forGetter(UraniumVeinConfig::veinChance),
            UraniumVeins.Geometry.CODEC.optionalFieldOf("geometry", UraniumVeins.Geometry.DEFAULT).forGetter(UraniumVeinConfig::geometry),
            Codec.floatRange(0f, 1f).fieldOf("ore_density").forGetter(UraniumVeinConfig::oreDensity),
            Codec.floatRange(0f, 1f).optionalFieldOf("raw_block_chance", 0.05f).forGetter(UraniumVeinConfig::rawBlockChance),
            Codec.intRange(0, 64).optionalFieldOf("min_depth", 6).forGetter(UraniumVeinConfig::minDepth),
            TagKey.hashedCodec(Registries.BLOCK).optionalFieldOf("host", DEFAULT_HOST).forGetter(UraniumVeinConfig::host),
            BlockState.CODEC.fieldOf("ore_stone").forGetter(UraniumVeinConfig::oreStone),
            BlockState.CODEC.fieldOf("ore_deepslate").forGetter(UraniumVeinConfig::oreDeepslate),
            BlockState.CODEC.fieldOf("raw_block").forGetter(UraniumVeinConfig::rawBlock),
            BlockState.CODEC.fieldOf("fill_stone").forGetter(UraniumVeinConfig::fillStone),
            BlockState.CODEC.fieldOf("fill_deepslate").forGetter(UraniumVeinConfig::fillDeepslate)
    ).apply(i, UraniumVeinConfig::new));
}
