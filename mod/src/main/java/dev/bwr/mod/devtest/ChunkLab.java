package dev.bwr.mod.devtest;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ChunkEvent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Chunk control for the chunk-boundary tests.
 *
 * <p>Holds the chunks a fixture forced, answers "is this chunk loaded / ticking right now", and watches
 * {@link ChunkEvent.Load}: any chunk that loads while a {@code dev.bwr.mod} frame outside the test classes is on the
 * stack was loaded <em>synchronously by our code</em> (a {@code getBlockState} or {@code getBlockEntity} on an
 * unloaded position blocks until the chunk exists and then fires this event on the same stack). Those are recorded in
 * {@link #syncLoads} with the first three of our frames. A load started by a ticket arrives on the server's own task
 * stack and is not recorded.
 *
 * <p>Chunk levels, for reading the tests: a forced chunk sits at level 31 (entity ticking); the ticket spreads out one
 * level per chunk, so its neighbours at distance 1 are block ticking (32), at distance 2 loaded but not ticking (33),
 * and from distance 3 on unloaded. A chunk therefore unloads only when every forced chunk is at least three away.
 */
final class ChunkLab implements AutoCloseable {
    final ServerLevel level;
    private final Set<Long> forced = new HashSet<>();
    final List<String> syncLoads = new CopyOnWriteArrayList<>();
    private final Consumer<ChunkEvent.Load> listener = this::onLoad;
    private boolean closed;

    ChunkLab(ServerLevel level) {
        this.level = level;
        NeoForge.EVENT_BUS.addListener(listener);
    }

    private void onLoad(ChunkEvent.Load event) {
        if (event.getLevel() != level) {
            return;
        }
        List<String> ours = new ArrayList<>();
        for (StackTraceElement frame : new Throwable().getStackTrace()) {
            String cls = frame.getClassName();
            if (cls.startsWith("dev.bwr.mod.") && !cls.startsWith("dev.bwr.mod.devtest.")) {
                ours.add(cls.substring("dev.bwr.mod.".length()) + "." + frame.getMethodName() + ":" + frame.getLineNumber());
                if (ours.size() == 3) {
                    break;
                }
            }
        }
        if (!ours.isEmpty()) {
            ChunkPos pos = event.getChunk().getPos();
            syncLoads.add("chunk " + pos.x + "," + pos.z + " loaded synchronously by " + String.join(" <- ", ours));
        }
    }

    // ------------------------------------------------------------------ forcing

    void force(int cx, int cz) {
        level.setChunkForced(cx, cz, true);
        forced.add(ChunkPos.asLong(cx, cz));
        level.getChunk(cx, cz);
    }

    /** Adds the ticket without loading the chunk, so the load is the server's, with the plant running. */
    void request(int cx, int cz) {
        level.setChunkForced(cx, cz, true);
        forced.add(ChunkPos.asLong(cx, cz));
    }

    void unforce(int cx, int cz) {
        level.setChunkForced(cx, cz, false);
        forced.remove(ChunkPos.asLong(cx, cz));
    }

    /** Forces every chunk of the inclusive chunk rectangle and loads it now. */
    void forceRect(int cx0, int cz0, int cx1, int cz1) {
        for (int x = cx0; x <= cx1; x++) {
            for (int z = cz0; z <= cz1; z++) {
                force(x, z);
            }
        }
    }

    void unforceRect(int cx0, int cz0, int cx1, int cz1) {
        for (int x = cx0; x <= cx1; x++) {
            for (int z = cz0; z <= cz1; z++) {
                unforce(x, z);
            }
        }
    }

    void releaseAll() {
        for (long packed : new ArrayList<>(forced)) {
            level.setChunkForced(ChunkPos.getX(packed), ChunkPos.getZ(packed), false);
        }
        forced.clear();
    }

    // ------------------------------------------------------------------ questions

    /**
     * The block entity at a position, or null, without touching the chunk system's tickets. {@code Level.getBlockEntity}
     * asks for the chunk the way every block read does, and a read that misses the four-entry chunk cache adds a
     * one-tick ticket at the loaded level; a test that polls a far chunk every tick that way keeps it from ever
     * unloading. Tests that wait for a chunk to go use this instead.
     */
    net.minecraft.world.level.block.entity.BlockEntity peek(BlockPos pos) {
        var chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        return chunk == null ? null : chunk.getBlockEntity(pos);
    }

    /** Whether a full chunk object exists (never loads one). */
    boolean loaded(int cx, int cz) {
        return level.getChunkSource().getChunkNow(cx, cz) != null;
    }

    boolean loaded(BlockPos pos) {
        return loaded(pos.getX() >> 4, pos.getZ() >> 4);
    }

    /** Whether block entities in the chunk are being ticked. */
    boolean ticking(BlockPos pos) {
        return level.isPositionEntityTicking(pos);
    }

    /** The server's own account of a chunk: ticket level and status ("null" when it has no holder). */
    String describe(int cx, int cz) {
        String debug = level.getChunkSource().chunkMap.getChunkDebugData(new ChunkPos(cx, cz)).replace((char) 10, (char) 32);
        return debug + " forcedByUs=" + forced.contains(ChunkPos.asLong(cx, cz))
                + " forcedChunks=" + level.getForcedChunks().size();
    }

    static int cx(BlockPos pos) {
        return pos.getX() >> 4;
    }

    static int cz(BlockPos pos) {
        return pos.getZ() >> 4;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        NeoForge.EVENT_BUS.unregister(listener);
        releaseAll();
    }
}
