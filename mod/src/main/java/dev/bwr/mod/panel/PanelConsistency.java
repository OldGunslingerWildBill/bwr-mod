package dev.bwr.mod.panel;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkTicketLevelUpdatedEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Bookkeeping for the claim/ownership repair of {@link PanelBlockEntity#verifyConsistency()}.
 *
 * <p>A straddling component is owned by one block entity and mirrored as claims in the blocks it covers. A
 * block removed while the other half's chunk is unloaded cannot tell it, so each side re-checks itself: when a
 * panel block entity loads ({@link #request}), and when a chunk it was waiting for becomes available. A check
 * that meets an unloaded position never loads it; it records the chunk here ({@link #await}) and is queued
 * again when that chunk's ticket level reaches FULL (the same event {@code PipeTopology} uses). Nothing polls:
 * the only per-tick work is draining the queue, which is empty unless a load or a chunk transition filled it.
 */
@EventBusSubscriber(modid = "bwr")
public final class PanelConsistency {
    private PanelConsistency() {
    }

    /** Block entities to verify at the end of the tick, in request order so repairs are deterministic. */
    private static final Set<PanelBlockEntity> QUEUE = Collections.newSetFromMap(new IdentityHashMap<>());
    private static final List<PanelBlockEntity> ORDER = new ArrayList<>();

    /** level -> chunk -> block entities (in other chunks) deferred until that chunk loads. */
    private static final Map<Level, Map<Long, Set<PanelBlockEntity>>> WAITING = new WeakHashMap<>();

    /** Queues a verification for the end of the tick (not inside chunk loading, where item drops are unsafe). */
    static void request(PanelBlockEntity be) {
        if (QUEUE.add(be)) {
            ORDER.add(be);
        }
    }

    /** Remembers that {@code be} could not decide until {@code chunk} (packed ChunkPos) is loaded. */
    static void await(PanelBlockEntity be, Level level, long chunk) {
        WAITING.computeIfAbsent(level, k -> new HashMap<>())
                .computeIfAbsent(chunk, k -> Collections.newSetFromMap(new IdentityHashMap<>())).add(be);
        be.awaitedChunks().add(chunk);
    }

    /** Drops every deferral and queued request of {@code be} (before it re-verifies, and when it is removed). */
    static void forget(PanelBlockEntity be) {
        Set<Long> chunks = be.awaitedChunks();
        if (!chunks.isEmpty() && be.getLevel() != null) {
            Map<Long, Set<PanelBlockEntity>> perChunk = WAITING.get(be.getLevel());
            if (perChunk != null) {
                for (long chunk : chunks) {
                    Set<PanelBlockEntity> set = perChunk.get(chunk);
                    if (set != null) {
                        set.remove(be);
                        if (set.isEmpty()) {
                            perChunk.remove(chunk);
                        }
                    }
                }
            }
        }
        chunks.clear();
        if (QUEUE.remove(be)) {
            ORDER.remove(be);
        }
    }

    /** Number of block entities deferred on a chunk of {@code level} (tests). */
    public static int waitingCount(Level level) {
        Map<Long, Set<PanelBlockEntity>> perChunk = WAITING.get(level);
        int total = 0;
        if (perChunk != null) {
            for (Set<PanelBlockEntity> set : perChunk.values()) {
                total += set.size();
            }
        }
        return total;
    }

    /**
     * Number of deferrals of {@code level} whose block entity stands at a position {@code accept} takes: how a test
     * counts only its own fixture, since the counts of other tests running in the same level move at the same time.
     */
    public static int waitingCount(Level level, java.util.function.Predicate<BlockPos> accept) {
        Map<Long, Set<PanelBlockEntity>> perChunk = WAITING.get(level);
        int total = 0;
        if (perChunk != null) {
            for (Set<PanelBlockEntity> set : perChunk.values()) {
                for (PanelBlockEntity be : set) {
                    if (accept.test(be.getBlockPos())) {
                        total++;
                    }
                }
            }
        }
        return total;
    }

    /** Every deferral of {@code level} as text, for test failure messages. */
    public static String describeWaiting(Level level) {
        Map<Long, Set<PanelBlockEntity>> perChunk = WAITING.get(level);
        StringBuilder out = new StringBuilder("waiting[");
        if (perChunk != null) {
            for (Map.Entry<Long, Set<PanelBlockEntity>> entry : perChunk.entrySet()) {
                out.append(ChunkPos.getX(entry.getKey())).append(',').append(ChunkPos.getZ(entry.getKey())).append("<-");
                for (PanelBlockEntity be : entry.getValue()) {
                    out.append(be.getBlockPos().toShortString()).append(' ');
                }
            }
        }
        return out.append(']').toString();
    }

    /** A chunk became available: queue everything that was waiting for it (public so tests can drive it). */
    public static void chunkAvailable(Level level, long chunk) {
        Map<Long, Set<PanelBlockEntity>> perChunk = WAITING.get(level);
        if (perChunk == null) {
            return;
        }
        Set<PanelBlockEntity> waiting = perChunk.remove(chunk);
        if (waiting == null) {
            return;
        }
        for (PanelBlockEntity be : new ArrayList<>(waiting)) {
            be.awaitedChunks().remove(chunk);
            request(be);
        }
    }

    /** Verifies everything queued now (the tick handler's body; public for tests). */
    public static void drain() {
        if (ORDER.isEmpty()) {
            return;
        }
        List<PanelBlockEntity> batch = new ArrayList<>(ORDER);
        ORDER.clear();
        QUEUE.clear();
        for (PanelBlockEntity be : batch) {
            if (!be.isRemoved() && be.getLevel() instanceof ServerLevel) {
                be.verifyConsistency();
            }
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        long probe = dev.bwr.mod.TickProbe.start();
        try {
            drain();
        } finally {
            dev.bwr.mod.TickProbe.stop("PanelConsistency", probe);
        }
    }

    @SubscribeEvent
    public static void availability(ChunkTicketLevelUpdatedEvent event) {
        boolean before = ChunkLevel.fullStatus(event.getOldTicketLevel()).isOrAfter(FullChunkStatus.FULL);
        boolean after = ChunkLevel.fullStatus(event.getNewTicketLevel()).isOrAfter(FullChunkStatus.FULL);
        ServerLevel level = event.getLevel();
        if (before == after || !after || event.getChunkHolder() == null) {
            return;
        }
        long chunk = event.getChunkPos();
        var holder = event.getChunkHolder();
        // The ticket level precedes the chunk being readable: wait for the full-chunk future, then queue
        // (never load, never poll). A verification that still finds the chunk unavailable re-defers.
        var server = level.getServer();
        server.tell(new net.minecraft.server.TickTask(server.getTickCount(),
                () -> holder.getFullChunkFuture().thenRun(() -> server.execute(() -> {
                    chunkAvailable(level, chunk);
                    verifyChunk(level.getChunkSource().getChunkNow(ChunkPos.getX(chunk), ChunkPos.getZ(chunk)));
                }))));
    }

    /**
     * A chunk reached FULL: verify its own panels. A chunk kept in memory at a lower level while its neighbours
     * changed comes back with its block entities intact and no {@code onLoad}, so this is the only place a
     * stale owner in it is noticed. Cost: one pass over that chunk's block entities per chunk load.
     */
    private static void verifyChunk(LevelChunk chunk) {
        if (chunk == null) {
            return;
        }
        for (BlockEntity be : chunk.getBlockEntities().values()) {
            if (be instanceof PanelBlockEntity panel && !panel.isRemoved()
                    && (panel.ownedCount() > 0 || !panel.foreignClaims().isEmpty())) {
                request(panel);
            }
        }
    }

    @SubscribeEvent
    public static void levelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof Level level) {
            WAITING.remove(level);
        }
    }
}
