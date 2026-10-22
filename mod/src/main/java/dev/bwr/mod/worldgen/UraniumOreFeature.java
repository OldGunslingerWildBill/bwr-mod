package dev.bwr.mod.worldgen;

import dev.bwr.mod.config.GameplayConfig;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;

/**
 * The small uranium blobs: exactly vanilla's {@code minecraft:ore} (same configuration, same code), behind the server option
 * {@code uraniumOreGeneration}. Using a type of our own and not {@code minecraft:ore} directly means the option also stops the
 * blobs of a server that is already running, the same as it stops the veins.
 */
public final class UraniumOreFeature extends Feature<OreConfiguration> {

    public UraniumOreFeature() {
        super(OreConfiguration.CODEC);
    }

    @Override
    public boolean place(FeaturePlaceContext<OreConfiguration> context) {
        return GameplayConfig.uraniumOreGeneration() && Feature.ORE.place(context);
    }
}
