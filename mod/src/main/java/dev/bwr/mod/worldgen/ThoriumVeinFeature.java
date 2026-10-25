package dev.bwr.mod.worldgen;

import dev.bwr.mod.config.GameplayConfig;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;

import java.util.Arrays;
import java.util.concurrent.atomic.LongAdder;

/**
 * Places the part of every thorium super vein that lies inside the chunk being generated. See {@link UraniumVeins} for why
 * that is all it ever does: the feature reads the world seed and the chunk position, rebuilds the veins of the grid cells
 * around, and writes only into the 16 x 16 columns of its own chunk. It never touches another chunk, so it cannot load
 * or write into one that is not generated yet or is finished already.
 *
 * <p>This is {@link UraniumVeinFeature} with its own server option and its own counters; the geometry ({@link UraniumVeins}) and
 * the configuration record are shared, and the thorium veins have a salt of their own, so they are a second, independent
 * set. See THORIUM.md.
 *
 * <p>It is a normal feature: it runs when a chunk is generated for the first time, from the biome's feature list, and
 * at no other time. There is no retrogen: nothing in this mod listens for a chunk loading.
 */
public final class ThoriumVeinFeature extends Feature<UraniumVeinConfig> {

    /** Totals since the JVM started, for the worldgen check and the tests. They cost one add per slice. */
    public static final LongAdder SLICES = new LongAdder(), BODY = new LongAdder(), ORE = new LongAdder(), RAW = new LongAdder(), NANOS = new LongAdder();

    /** Receives every block of a vein that was really placed (not those that hit air or other rock). Set only by the worldgen check. */
    public interface BodyProbe {
        void placed(int x, int y, int z);
    }

    public static volatile BodyProbe probe;

    private static final int UNKNOWN = Integer.MIN_VALUE;

    public ThoriumVeinFeature() {
        super(UraniumVeinConfig.CODEC);
    }

    @Override
    public boolean place(FeaturePlaceContext<UraniumVeinConfig> context) {
        // The server option. The biome modifier already leaves the feature out of the biomes when the option is off at
        // start; this also covers an option changed while the server runs.
        if (!GameplayConfig.thoriumOreGeneration()) {
            return false;
        }
        UraniumVeinConfig cfg = context.config();
        if (cfg.veinChance() <= 0f) {
            return false;
        }
        long startedAt = System.nanoTime();
        BodyProbe watcher = probe;
        WorldGenLevel level = context.level();
        ChunkPos cp = new ChunkPos(context.origin());
        ChunkAccess chunk = level.getChunk(cp.x, cp.z);
        long seed = level.getSeed();
        UraniumVeins.Geometry g = cfg.geometry();
        int x0 = cp.getMinBlockX(), z0 = cp.getMinBlockZ(), x1 = cp.getMaxBlockX(), z1 = cp.getMaxBlockZ();
        int y0 = level.getMinBuildHeight(), y1 = level.getMaxBuildHeight() - 1;
        int cellX = Math.floorDiv(x0, g.cellSize()), cellZ = Math.floorDiv(z0, g.cellSize());
        int reach = g.reachCells();

        int[] surface = new int[256];
        Arrays.fill(surface, UNKNOWN);
        long[] counts = new long[3];
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dz = -reach; dz <= reach; dz++) {
                UraniumVeins.Vein vein = UraniumVeins.vein(seed, cellX + dx, cellZ + dz, g);
                if (vein.roll >= cfg.veinChance() || !vein.intersects(x0, y0, z0, x1, y1, z1)) {
                    continue;
                }
                SLICES.increment();
                UraniumVeins.slice(vein, seed, x0, y0, z0, x1, y1, z1, cfg.oreDensity(), cfg.rawBlockChance(), (x, y, z, kind) -> {
                    int column = (z & 15) * 16 + (x & 15);
                    int top = surface[column];
                    if (top == UNKNOWN) {
                        top = surface[column] = level.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, x, z);
                    }
                    if (y > top - cfg.minDepth()) {
                        return;
                    }
                    LevelChunkSection section = chunk.getSection(chunk.getSectionIndex(y));
                    int lx = x & 15, ly = y & 15, lz = z & 15;
                    BlockState current = section.getBlockState(lx, ly, lz);
                    if (!current.is(cfg.host())) {
                        return;
                    }
                    boolean deep = current.is(BlockTags.DEEPSLATE_ORE_REPLACEABLES);
                    BlockState out = switch (kind) {
                        case FILL -> deep ? cfg.fillDeepslate() : cfg.fillStone();
                        case ORE -> deep ? cfg.oreDeepslate() : cfg.oreStone();
                        case RAW -> cfg.rawBlock();
                    };
                    if (out != current) {
                        section.setBlockState(lx, ly, lz, out, false);
                    }
                    counts[kind.ordinal()]++;
                    if (watcher != null) {
                        watcher.placed(x, y, z);
                    }
                });
            }
        }
        NANOS.add(System.nanoTime() - startedAt);
        BODY.add(counts[0] + counts[1] + counts[2]);
        ORE.add(counts[1]);
        RAW.add(counts[2]);
        return counts[0] + counts[1] + counts[2] > 0;
    }
}
