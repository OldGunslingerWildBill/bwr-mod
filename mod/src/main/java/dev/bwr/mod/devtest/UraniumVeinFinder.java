package dev.bwr.mod.devtest;

import dev.bwr.mod.worldgen.UraniumVeinConfig;
import dev.bwr.mod.worldgen.UraniumVeins;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.List;

/**
 * Finds, in a world that has not been generated yet, a uranium vein whose chunks are in a given biome group. It uses only
 * the world seed, the biome source and the surface height, none of which generate a chunk, so the vein can be located
 * first and then generated (the worldgen check and the screenshots both do this).
 */
final class UraniumVeinFinder {

    static final List<String> GROUPS = List.of("arid", "highland", "semi_arid", "lowland", "aquatic");

    private UraniumVeinFinder() {
    }

    /** A vein and the chunk box that holds it. */
    record Found(UraniumVeins.Vein vein, String group, int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ, double[] middle) {
    }

    static UraniumVeins.Geometry geometry(ServerLevel level) {
        var holder = level.registryAccess().registryOrThrow(Registries.CONFIGURED_FEATURE)
                .getHolderOrThrow(ResourceKey.create(Registries.CONFIGURED_FEATURE, ResourceLocation.fromNamespaceAndPath("bwr", "uranium_vein_arid")));
        return ((UraniumVeinConfig) holder.value().config()).geometry();
    }

    static double chance(ServerLevel level, String group) {
        var holder = level.registryAccess().registryOrThrow(Registries.CONFIGURED_FEATURE)
                .getHolderOrThrow(ResourceKey.create(Registries.CONFIGURED_FEATURE, ResourceLocation.fromNamespaceAndPath("bwr", "uranium_vein_" + group)));
        return ((UraniumVeinConfig) holder.value().config()).veinChance();
    }

    /** The biome group of the surface at a block column of a world that is not generated, or null if it is in none. */
    static String groupAt(ServerLevel level, int x, int z) {
        var chunkSource = level.getChunkSource();
        var generator = chunkSource.getGenerator();
        var random = chunkSource.randomState();
        int y = generator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, level, random);
        Holder<Biome> biome = generator.getBiomeSource().getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(y), QuartPos.fromBlock(z), random.sampler());
        for (String g : GROUPS) {
            if (biome.is(TagKey.create(Registries.BIOME, ResourceLocation.fromNamespaceAndPath("bwr", "has_uranium/" + g)))) {
                return g;
            }
        }
        return null;
    }

    /**
     * The vein nearest the origin that exists at {@code maxRoll} and whose centre line is in {@code group} at a quarter, a
     * half and three quarters of its length; null when there is none within {@code radiusCells} cells.
     */
    static Found find(ServerLevel level, String group, double maxRoll, int radiusCells) {
        return find(level, group, maxRoll, radiusCells, Integer.MIN_VALUE);
    }

    /** As {@link #find(ServerLevel, String, double, int)}, but only a vein whose middle is at least {@code minMiddleY} high (shallow ones are easier to dig out). */
    static Found find(ServerLevel level, String group, double maxRoll, int radiusCells, int minMiddleY) {
        return find(level, group, maxRoll, radiusCells, minMiddleY, v -> true);
    }

    /** As above, and only a vein the caller accepts (the screenshots want one on dry land). */
    static Found find(ServerLevel level, String group, double maxRoll, int radiusCells, int minMiddleY, java.util.function.Predicate<UraniumVeins.Vein> accept) {
        long seed = level.getSeed();
        var geometry = geometry(level);
        Found best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int cx = -radiusCells; cx <= radiusCells; cx++) {
            for (int cz = -radiusCells; cz <= radiusCells; cz++) {
                var vein = UraniumVeins.vein(seed, cx, cz, geometry);
                if (vein.roll >= maxRoll) {
                    continue;
                }
                double[][] line = vein.centreLine;
                double[] a = line[line.length / 4], b = line[line.length / 2], c = line[line.length * 3 / 4];
                double distance = Math.hypot(b[0], b[2]);
                if (distance >= bestDistance || b[1] < minMiddleY || !accept.test(vein)) {
                    continue;
                }
                if (group.equals(groupAt(level, (int) a[0], (int) a[2])) && group.equals(groupAt(level, (int) b[0], (int) b[2]))
                        && group.equals(groupAt(level, (int) c[0], (int) c[2]))) {
                    best = new Found(vein, group, Math.floorDiv((int) vein.minX, 16), Math.floorDiv((int) vein.minZ, 16),
                            Math.floorDiv((int) vein.maxX, 16), Math.floorDiv((int) vein.maxZ, 16), b);
                    bestDistance = distance;
                }
            }
        }
        return best;
    }
}
