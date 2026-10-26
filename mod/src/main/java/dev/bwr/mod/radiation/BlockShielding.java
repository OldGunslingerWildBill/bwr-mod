package dev.bwr.mod.radiation;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.bwr.core.radiation.DoseModel;
import dev.bwr.core.radiation.Shielding;
import dev.bwr.mod.BwrMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.TagsUpdatedEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.registries.datamaps.DataMapType;
import net.neoforged.neoforge.registries.datamaps.DataMapsUpdatedEvent;
import net.neoforged.neoforge.registries.datamaps.RegisterDataMapTypesEvent;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Blocks on the line between a gamma source and a dose point (RADIATION.md, "Shielding").
 *
 * <p><b>Materials are data</b>: the block data map {@code bwr:radiation_shielding} ({@code data/<ns>/data_maps/block/radiation_shielding.json})
 * gives a block, or a tag of blocks, a half-value layer in centimetres at about 1 MeV ({@code hvl_cm}) and optionally the share of a block's
 * path that is that material ({@code fill}; without it, the share of the cell its collision shape fills, so a slab is half a block). The mod's
 * own file maps the family tags {@code bwr:radiation_shielding/<family>} (lead, steel, concrete, leaded_concrete, earth, wood, glass) and the
 * three shield plug concrete tiers; a pack adds a block to a family tag or gives it its own entry. A block with no entry, air, and every
 * non-solid block shield nothing. Water (a water block, or a waterlogged block's water in the part of the cell the block does not fill) is
 * the dose model's own water, 7.07 per m. The arithmetic is {@link Shielding}.
 *
 * <p><b>What is not counted</b>, so nothing is counted twice:
 * <ul>
 *   <li>the blocks of the mod's formed structures, tag {@code bwr:radiation_shielding_exempt} (the reactor and its machines, the spent fuel
 *       pool's lining, racks and ports, the RTG and the Large RTG): their shielding is in their sources' calibrated numbers. This wins over
 *       any data map entry;</li>
 *   <li>the cell the source is in, and a source's own box ({@link Box}: the spent fuel pool's whole walls-and-interior box for its racks,
 *       the Large RTG's footprint): a source's housing is its own transmission;</li>
 *   <li>water, for a source whose own transmission already has the water on the line in it ({@code waterModelled}: the spent fuel pool's
 *       racks, an assembly on the bridge or the crane, the elevator, the equipment pads);</li>
 *   <li>the reactor core term ({@code RadiationField.coreDoseRate}) is not marched at all: its calibrated 1e-4 "biological shield and
 *       building" is the shielding around the reactor, the containment the player built among it.</li>
 * </ul>
 *
 * <p><b>Cost.</b> Nothing ticks and nothing scans: a ray is marched only when an instrument samples, only through loaded chunks (a cell in a
 * chunk that is not loaded right now is unknown and counts as no shielding, and the chunk is never loaded or asked for), only for a source
 * within the dose model's 256 m (the ray length cap: beyond it the source reads zero anyway), and it stops at a transmission of zero. A result
 * is cached per source position, dose point block, and options for {@link #TTL_TICKS} ticks; a block update in any chunk the ray crossed (every
 * {@code setBlock} that notifies neighbours: a player's, a fluid's, an explosion's, a command's), or that chunk loading or unloading, drops
 * it at once; a change that notifies nobody (a piston's move) is seen after the time limit. A ray that crossed an unloaded chunk is not cached.
 */
@EventBusSubscriber(modid = BwrMod.MOD_ID)
public final class BlockShielding {
    private BlockShielding() {
    }

    /** One entry of the data map. */
    public record Material(double hvlCm, Optional<Double> fill) {
        public static final Codec<Material> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.doubleRange(1.0e-6, 1.0e9).fieldOf("hvl_cm").forGetter(Material::hvlCm),
                Codec.doubleRange(0.0, 1.0).optionalFieldOf("fill").forGetter(Material::fill)).apply(i, Material::new));
    }

    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("bwr", "radiation_shielding");

    /** The shielding of a block: server side only (doses are computed on the server), so it is not synced. */
    public static final DataMapType<Block, Material> SHIELDING = DataMapType.builder(ID, Registries.BLOCK, Material.CODEC).build();

    public static void register(RegisterDataMapTypesEvent event) {
        event.register(SHIELDING);
    }

    /** How long a cached ray is trusted at most, ticks (a change that notifies no neighbour, a piston's for one, is seen after this). */
    public static final int TTL_TICKS = 40;
    /** Cached rays per level before the cache is emptied. */
    public static final int MAX_ENTRIES = 50_000;
    private static final int PRUNE_TICKS = 100;

    /** Blocks never counted, whatever the data map says: the cells of the mod's formed structures, whose shielding is in their sources' numbers. */
    public static final TagKey<Block> EXEMPT = TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath("bwr", "radiation_shielding_exempt"));

    /** An axis-aligned box of block cells whose blocks are not counted (inclusive bounds). */
    public record Box(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        public boolean contains(int x, int y, int z) {
            return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
        }

        /** The box spanning two cells, inclusive, in either order. */
        public static Box of(BlockPos a, BlockPos b) {
            return new Box(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()),
                    Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));
        }
    }

    /** What one cell of a block state does: attenuation of its solid part per metre (fill applied) and the share of the cell that is water. */
    private record CellMaterial(double solidPerM, double water) {
        static final CellMaterial NONE = new CellMaterial(0.0, 0.0);
    }

    /** The result of one march. */
    public record Path(double opticalDepth, boolean unknown, int cells, long[] chunks) {
        public double transmission() {
            return Shielding.transmission(opticalDepth);
        }

        public double leadEquivalentCm() {
            return Shielding.leadEquivalentCm(opticalDepth);
        }
    }

    private record Key(double sx, double sy, double sz, long cell, boolean waterModelled, @Nullable Box exclude) {
    }

    private record Entry(double transmission, long tick, long[] chunks) {
    }

    private static final class LevelCache {
        final Map<Key, Entry> entries = new HashMap<>();
        final Map<Long, Long> changed = new HashMap<>();
        long lastPrune;
    }

    private static final Map<BlockState, CellMaterial> STATES = new ConcurrentHashMap<>();
    private static final Map<Level, LevelCache> CACHES = new WeakHashMap<>();
    private static volatile boolean anyCache;

    // counters for the performance tests and diagnostics
    private static long marches, cellsRead, hits, lookups;

    /** Marches, cells read, cache hits and lookups since the last {@link #resetStats}. */
    public static synchronized long[] stats() {
        return new long[] {marches, cellsRead, hits, lookups};
    }

    public static synchronized void resetStats() {
        marches = cellsRead = hits = lookups = 0;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Materials

    private static CellMaterial material(BlockState state) {
        if (state.isAir()) {
            return CellMaterial.NONE;
        }
        CellMaterial cached = STATES.get(state);
        if (cached == null) {
            cached = resolve(state);
            STATES.put(state, cached);
        }
        return cached;
    }

    private static CellMaterial resolve(BlockState state) {
        return resolve(state, state.getBlockHolder().getData(SHIELDING));
    }

    private static CellMaterial resolve(BlockState state, @Nullable Material entry) {
        if (state.is(EXEMPT)) {
            return CellMaterial.NONE;
        }
        double shape = shapeFraction(state);
        double solid = entry == null ? 0.0 : Shielding.attenuationPerM(entry.hvlCm()) * entry.fill().orElse(shape);
        double water = state.getFluidState().is(FluidTags.WATER) ? Math.max(0.0, 1.0 - shape) : 0.0;
        return solid > 0.0 || water > 0.0 ? new CellMaterial(solid, water) : CellMaterial.NONE;
    }

    /** The share of a cell a block's collision shape fills, 0..1 (a block whose shape needs a world counts as full). */
    static double shapeFraction(BlockState state) {
        try {
            VoxelShape shape = state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
            if (shape.isEmpty()) {
                return 0.0;
            }
            double v = 0.0;
            for (AABB box : shape.toAabbs()) {
                v += Math.max(0.0, Math.min(1.0, box.maxX) - Math.max(0.0, box.minX))
                        * Math.max(0.0, Math.min(1.0, box.maxY) - Math.max(0.0, box.minY))
                        * Math.max(0.0, Math.min(1.0, box.maxZ) - Math.max(0.0, box.minZ));
            }
            return Math.min(1.0, v);
        } catch (RuntimeException needsAWorld) {
            return 1.0;
        }
    }

    /** The half-value layer a block state's solid part has per metre of path, cm (infinite for none); tests and the docs. */
    public static double effectiveHvlCm(BlockState state) {
        return Shielding.hvlCm(material(state).solidPerM());
    }

    /** As {@link #effectiveHvlCm(BlockState)} if the data map gave the block {@code entry} (a pack's entry; tests): the exempt tag still wins. */
    public static double effectiveHvlCm(BlockState state, Material entry) {
        return Shielding.hvlCm(resolve(state, entry).solidPerM());
    }

    // ------------------------------------------------------------------------------------------------------------
    // Dose

    /**
     * The dose rate of a point source at {@code at}, mSv/h: {@link DoseModel#pointSourceMsvPerH} with the source's own transmission,
     * times what the blocks on the line let through.
     *
     * @param waterModelled the source's own transmission already holds the water on the line, so water blocks are not counted again
     */
    public static double pointSource(Level level, Vec3 source, Vec3 at, double gammaWatts, double transmission, boolean waterModelled) {
        return pointSource(level, source, at, gammaWatts, transmission, waterModelled, null);
    }

    /** As {@link #pointSource(Level, Vec3, Vec3, double, double, boolean)}, with the source's own box not counted. */
    public static double pointSource(Level level, Vec3 source, Vec3 at, double gammaWatts, double transmission, boolean waterModelled,
                                     @Nullable Box exclude) {
        double distance = at.distanceTo(source);
        double bare = DoseModel.pointSourceMsvPerH(gammaWatts, distance, transmission);
        if (!(bare > 0.0)) {
            return 0.0;
        }
        return bare * transmission(level, source, at, waterModelled, exclude);
    }

    /** What the blocks between {@code from} (the source) and {@code to} (the dose point) let through, 0..1. Cached; 1 on a client. */
    public static double transmission(Level level, Vec3 from, Vec3 to, boolean waterModelled, @Nullable Box exclude) {
        if (!(level instanceof ServerLevel server) || from == null || to == null) {
            return 1.0;
        }
        if (from.distanceToSqr(to) > DoseModel.MAX_RANGE_M * DoseModel.MAX_RANGE_M) {
            return 1.0; // the source reads zero there anyway: nothing to march
        }
        long now = server.getGameTime();
        Key key = new Key(from.x, from.y, from.z, BlockPos.asLong(floor(to.x), floor(to.y), floor(to.z)), waterModelled, exclude);
        synchronized (BlockShielding.class) {
            lookups++;
            LevelCache cache = CACHES.get(server);
            if (cache != null) {
                prune(cache, now);
                Entry entry = cache.entries.get(key);
                if (entry != null && valid(cache, entry, now)) {
                    hits++;
                    return entry.transmission();
                }
            }
        }
        Path path = trace(server, from, to, waterModelled, exclude);
        double t = path.transmission();
        synchronized (BlockShielding.class) {
            if (!path.unknown()) {
                LevelCache cache = CACHES.computeIfAbsent(server, unused -> new LevelCache());
                anyCache = true;
                if (cache.entries.size() >= MAX_ENTRIES) {
                    cache.entries.clear();
                }
                cache.entries.put(key, new Entry(t, now, path.chunks()));
            } else {
                Map<Key, Entry> entries = CACHES.containsKey(server) ? CACHES.get(server).entries : null;
                if (entries != null) {
                    entries.remove(key);
                }
            }
        }
        return t;
    }

    private static boolean valid(LevelCache cache, Entry entry, long now) {
        if (now - entry.tick() > TTL_TICKS || now < entry.tick()) {
            return false;
        }
        for (long chunk : entry.chunks()) {
            Long changedAt = cache.changed.get(chunk);
            if (changedAt != null && changedAt >= entry.tick()) {
                return false;
            }
        }
        return true;
    }

    private static void prune(LevelCache cache, long now) {
        if (now - cache.lastPrune < PRUNE_TICKS && now >= cache.lastPrune) {
            return;
        }
        cache.lastPrune = now;
        cache.entries.values().removeIf(e -> now - e.tick() > TTL_TICKS || now < e.tick());
        // a change older than every live entry can no longer invalidate one
        cache.changed.values().removeIf(t -> now - t > TTL_TICKS + 1 || now < t);
    }

    /**
     * Marches the ray from {@code from} to {@code to} through the loaded blocks and sums their optical depth, uncached. Server thread
     * (off it no chunk is available, and the whole path is unknown).
     */
    public static Path trace(ServerLevel level, Vec3 from, Vec3 to, boolean waterModelled, @Nullable Box exclude) {
        int sourceX = floor(from.x), sourceY = floor(from.y), sourceZ = floor(from.z);
        int minY = level.getMinBuildHeight(), maxY = level.getMaxBuildHeight();
        var walker = new Object() {
            double depth;
            boolean unknown;
            int cells;
            long lastChunk = Long.MIN_VALUE;
            LevelChunk chunk;
            long[] chunks = new long[8];
            int chunkCount;
        };
        Shielding.march(from.x, from.y, from.z, to.x, to.y, to.z, (x, y, z, length) -> {
            if (length <= 0.0 || x == sourceX && y == sourceY && z == sourceZ || y < minY || y >= maxY
                    || exclude != null && exclude.contains(x, y, z)) {
                return true;
            }
            long chunkKey = ChunkPos.asLong(x >> 4, z >> 4);
            if (chunkKey != walker.lastChunk) {
                walker.lastChunk = chunkKey;
                walker.chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
                if (walker.chunkCount == walker.chunks.length) {
                    walker.chunks = java.util.Arrays.copyOf(walker.chunks, walker.chunks.length * 2);
                }
                walker.chunks[walker.chunkCount++] = chunkKey;
            }
            if (walker.chunk == null) {
                walker.unknown = true; // not loaded right now: unknown, so no shielding (conservative); never loaded for this
                return true;
            }
            LevelChunkSection section = walker.chunk.getSection(walker.chunk.getSectionIndex(y));
            walker.cells++;
            if (section.hasOnlyAir()) {
                return true;
            }
            CellMaterial m = material(section.getBlockState(x & 15, y & 15, z & 15));
            if (m != CellMaterial.NONE) {
                walker.depth += Shielding.cellAttenuationPerM(m.solidPerM(), m.water(), !waterModelled) * length;
            }
            return walker.depth < Shielding.MAX_OPTICAL_DEPTH;
        });
        synchronized (BlockShielding.class) {
            marches++;
            cellsRead += walker.cells;
        }
        return new Path(walker.depth, walker.unknown, walker.cells, java.util.Arrays.copyOf(walker.chunks, walker.chunkCount));
    }

    private static int floor(double v) {
        return (int) Math.floor(v);
    }

    // ------------------------------------------------------------------------------------------------------------
    // Invalidation

    private static void changed(Level level, int chunkX, int chunkZ) {
        if (!anyCache || level == null || level.isClientSide() || !(level instanceof ServerLevel server)) {
            return;
        }
        synchronized (BlockShielding.class) {
            LevelCache cache = CACHES.get(server);
            if (cache != null && !cache.entries.isEmpty()) {
                cache.changed.put(ChunkPos.asLong(chunkX, chunkZ), server.getGameTime());
            }
        }
    }

    @SubscribeEvent
    public static void onNeighborNotify(BlockEvent.NeighborNotifyEvent event) {
        if (anyCache && event.getLevel() instanceof Level level) {
            BlockPos pos = event.getPos();
            changed(level, pos.getX() >> 4, pos.getZ() >> 4);
        }
    }

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (anyCache && event.getLevel() instanceof Level level) {
            changed(level, event.getChunk().getPos().x, event.getChunk().getPos().z);
        }
    }

    @SubscribeEvent
    public static void onChunkUnload(ChunkEvent.Unload event) {
        if (anyCache && event.getLevel() instanceof Level level) {
            changed(level, event.getChunk().getPos().x, event.getChunk().getPos().z);
        }
    }

    /** New data (a datapack reload): every block's material and every cached ray are recomputed. */
    public static synchronized void clearAll() {
        STATES.clear();
        CACHES.clear();
        anyCache = false;
    }

    @SubscribeEvent
    public static void onDataMaps(DataMapsUpdatedEvent event) {
        clearAll();
    }

    @SubscribeEvent
    public static void onTags(TagsUpdatedEvent event) {
        clearAll();
    }
}
