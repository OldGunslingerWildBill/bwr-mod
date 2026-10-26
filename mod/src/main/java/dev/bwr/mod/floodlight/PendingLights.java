package dev.bwr.mod.floodlight;

import dev.bwr.mod.BwrMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Light blocks a lamp gave up while their chunk was unloaded (the lamp was switched off, aimed away or broken). The lamp
 * cannot reach them without loading the chunk, which it never does, so they wait here, saved with the level, grouped by
 * chunk.
 *
 * <p>When a chunk with waiting cells loads, {@link ChunkEvent.Load} only marks it (touching the world inside a chunk load
 * deadlocks the chunk system). At the end of that server tick each marked chunk that is loaded is processed once: every
 * waiting cell that still holds a light is put back (water if the light is waterlogged, else the air it replaced), and the
 * chunk's entries are dropped whether or not they held a light. A chunk that only dropped below full and came back fires
 * no load event, so every {@link #SWEEP_TICKS} the queued chunks (only while there are any) are looked up and the loaded
 * ones processed the same way. A lamp that places a light into a queued cell drops the entry first (the queued light is
 * gone, the new one is not the queue's), so a cell is in at most one lamp's list or here, never both, and nothing is
 * removed twice.
 *
 * <p>The file is written only once something has been queued (a new save without floodlights never gets one).
 */
@EventBusSubscriber(modid = BwrMod.MOD_ID)
public final class PendingLights extends SavedData {
    private static final String NAME = "bwr_beam_light_pending";
    private static final SavedData.Factory<PendingLights> FACTORY =
            new SavedData.Factory<>(PendingLights::new, PendingLights::load);

    /** Chunk to (position to what the cell held before the light). */
    private final Map<Long, Map<Long, Byte>> byChunk = new HashMap<>();
    /** Chunks with waiting cells that have loaded since the last tick. */
    private final Set<Long> ready = ConcurrentHashMap.newKeySet();
    private static volatile boolean anyReady;
    /** Ticks between two sweeps of the queued chunks: 5 seconds. */
    public static final int SWEEP_TICKS = 100;
    /** Lights the queue has removed since the server started (tests, diagnostics). */
    private static final java.util.concurrent.atomic.AtomicLong RESTORED = new java.util.concurrent.atomic.AtomicLong();

    public static long restoredTotal() {
        return RESTORED.get();
    }

    public static PendingLights get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, NAME);
    }

    /** How many restores are waiting (tests, diagnostics). */
    public int size() {
        int n = 0;
        for (var cells : byChunk.values()) {
            n += cells.size();
        }
        return n;
    }

    /** How many restores are waiting in one chunk (tests). */
    public int sizeIn(ChunkPos chunk) {
        var cells = byChunk.get(chunk.toLong());
        return cells == null ? 0 : cells.size();
    }

    /** Whether {@code pos} is waiting to be restored. */
    public boolean contains(BlockPos pos) {
        var cells = byChunk.get(ChunkPos.asLong(pos));
        return cells != null && cells.containsKey(pos.asLong());
    }

    /** Queues a restore for a cell in an unloaded chunk. */
    public void add(BlockPos pos, byte kind) {
        byChunk.computeIfAbsent(ChunkPos.asLong(pos), k -> new HashMap<>()).put(pos.asLong(), kind);
        setDirty();
    }

    /**
     * Drops a queued cell that no longer holds the queued light (a lamp is about to place a new light into it; the old one
     * is gone, so the queue must not take the new one).
     */
    public void forget(BlockPos pos) {
        long chunk = ChunkPos.asLong(pos);
        var cells = byChunk.get(chunk);
        if (cells != null && cells.remove(pos.asLong()) != null) {
            if (cells.isEmpty()) {
                byChunk.remove(chunk);
            }
            setDirty();
        }
    }

    /**
     * Restores the waiting cells of every loaded chunk among {@code chunks} and drops their entries; returns how many lights it
     * removed. Cells of a chunk that is not loaded stay queued. Never loads a chunk.
     */
    public int process(ServerLevel level, Iterable<Long> chunks) {
        int removed = 0;
        for (long chunk : chunks) {
            Map<Long, Byte> cells = byChunk.get(chunk);
            if (cells == null || !level.hasChunk(ChunkPos.getX(chunk), ChunkPos.getZ(chunk))) {
                continue;
            }
            for (var entry : cells.entrySet()) {
                BlockPos p = BlockPos.of(entry.getKey());
                BlockState now = level.getBlockState(p);
                if (now.is(Blocks.LIGHT)) {
                    BeamLampBlockEntity.restoreLoaded(level, p, now, entry.getValue());
                    removed++;
                    RESTORED.incrementAndGet();
                }
            }
            byChunk.remove(chunk);
            setDirty();
        }
        return removed;
    }

    /** Processes every queued chunk that is loaded now (tests). */
    public int processLoaded(ServerLevel level) {
        return process(level, new java.util.ArrayList<>(byChunk.keySet()));
    }

    @SubscribeEvent
    public static void chunkLoaded(ChunkEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level && event.getChunk() instanceof LevelChunk) {
            PendingLights data = get(level);
            long chunk = event.getChunk().getPos().toLong();
            if (data.byChunk.containsKey(chunk)) {
                data.ready.add(chunk);
                anyReady = true;
            }
        }
    }

    @SubscribeEvent
    public static void serverTick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % SWEEP_TICKS == 0) {
            // A chunk that only dropped below full and came back (never unloaded) fires no load event: every 5 s the
            // queued chunks are looked up (only while something is queued) and the loaded ones processed.
            for (ServerLevel level : event.getServer().getAllLevels()) {
                PendingLights data = get(level);
                if (!data.byChunk.isEmpty()) {
                    data.processLoaded(level);
                }
            }
        }
        if (!anyReady) {
            return;
        }
        anyReady = false;
        for (ServerLevel level : event.getServer().getAllLevels()) {
            PendingLights data = get(level);
            if (data.ready.isEmpty()) {
                continue;
            }
            var chunks = new java.util.ArrayList<>(data.ready);
            data.ready.removeAll(chunks);
            for (long chunk : chunks) {
                if (!level.hasChunk(ChunkPos.getX(chunk), ChunkPos.getZ(chunk))) {
                    // Marked as it loaded, not full yet: look again next tick (an unload clears the mark).
                    data.ready.add(chunk);
                    anyReady = true;
                }
            }
            data.process(level, chunks);
        }
    }

    @SubscribeEvent
    public static void chunkUnloaded(ChunkEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level && anyReady) {
            get(level).ready.remove(event.getChunk().getPos().toLong());
        }
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        int n = size();
        long[] keys = new long[n];
        byte[] kinds = new byte[n];
        int i = 0;
        for (var cells : byChunk.values()) {
            for (var entry : cells.entrySet()) {
                keys[i] = entry.getKey();
                kinds[i++] = entry.getValue();
            }
        }
        tag.putLongArray("Cells", keys);
        tag.putByteArray("Kinds", kinds);
        return tag;
    }

    private static PendingLights load(CompoundTag tag, HolderLookup.Provider registries) {
        PendingLights data = new PendingLights();
        long[] keys = tag.getLongArray("Cells");
        byte[] kinds = tag.getByteArray("Kinds");
        for (int i = 0; i < keys.length; i++) {
            byte kind = i < kinds.length ? kinds[i] : XenonBeam.AIR;
            BlockPos p = BlockPos.of(keys[i]);
            data.byChunk.computeIfAbsent(ChunkPos.asLong(p), k -> new HashMap<>()).put(keys[i],
                    kind == XenonBeam.CAVE_AIR || kind == XenonBeam.WATER ? kind : XenonBeam.AIR);
        }
        return data;
    }
}
