package dev.bwr.mod.world;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.function.Predicate;

/**
 * Finds blocks of a few kinds in a box without reading the box one block at a time.
 *
 * <p>A machine that looks for "the reactor, the pool and the tank within 24 blocks" used to read all 117,649 positions
 * of the 49-block cube, each through {@code isLoaded} and {@code getBlockState}, a few milliseconds on the server
 * thread every time it rescanned. Almost every 16-block section of such a cube holds none of the three blocks, and a
 * section's palette says so without reading a block: {@link LevelChunkSection#maybeHas} answers from a handful of
 * entries. This walks the box in exactly the order {@link BlockPos#betweenClosed} does (x fastest, then y, then z) and
 * reads only the cells of sections whose palette could hold a candidate, so the blocks it reports, and the order it
 * reports them in, are the ones the old block-by-block loop found.
 *
 * <p>It never loads a chunk. A chunk that is not completely available ({@code getChunkNow} is null: not loaded, or its
 * ticket arrived but the chunk is still being read) is skipped, and the result says so, so a caller can tell "nothing
 * there" from "could not look".
 *
 * <p>Server thread only (it reads chunk sections directly).
 */
public final class BlockScan {
    private BlockScan() {
    }

    /** Called for each matching block, in scan order; the position is a shared cursor, copy it with {@code immutable()}. */
    @FunctionalInterface
    public interface Visitor {
        /** @return false to stop the scan */
        boolean visit(BlockPos.MutableBlockPos pos, BlockState state);
    }

    private static long scans;
    private static long cellsRead;

    /** Scans started since the JVM began (a counter for tests and diagnostics). */
    public static long scanCount() {
        return scans;
    }

    /** Block states actually read by scans since the JVM began. */
    public static long cellsRead() {
        return cellsRead;
    }

    /**
     * The binding to keep after a rescan. What the scan found; or, when it found nothing but could not read the whole
     * box, the old binding, unless the block it points at is plainly gone; or null when the whole box was read and
     * the block is not in it.
     *
     * <p>A machine that cleared its binding before every scan lost it whenever a part of the search cube was in a chunk
     * that was not loaded, and kept the loss until the next scan (up to thirty seconds after the chunk came back).
     */
    public static BlockPos rebound(Level level, boolean complete, BlockPos found, BlockPos old,
                                   net.minecraft.world.level.block.Block block) {
        if (found != null || complete || old == null) {
            return found;
        }
        return mayStillBe(level, old, block) ? old : null;
    }

    /** Whether the chunk holding {@code pos} can be read right now (complete and loaded). Never loads a chunk. */
    public static boolean readable(Level level, BlockPos pos) {
        return level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) != null;
    }

    /**
     * Whether a remembered position may still hold {@code block}: true when its chunk cannot be read right now (so
     * nothing can be said against it), or when the block is there. False only when the chunk is readable and the block
     * is gone. Never loads a chunk.
     */
    public static boolean mayStillBe(Level level, BlockPos pos, net.minecraft.world.level.block.Block block) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        return chunk == null || chunk.getBlockState(pos).is(block);
    }

    /**
     * Visits every position of the inclusive box whose block state satisfies {@code candidate}.
     *
     * <p>{@code candidate} must be false for air, and is evaluated on a section's palette first (so it must be a pure
     * function of the block state).
     *
     * @return true when every chunk the box touches was available, false when some part of it could not be read
     */
    public static boolean scan(Level level, BlockPos a, BlockPos b, Predicate<BlockState> candidate, Visitor visitor) {
        scans++;
        int minX = Math.min(a.getX(), b.getX()), maxX = Math.max(a.getX(), b.getX());
        int minZ = Math.min(a.getZ(), b.getZ()), maxZ = Math.max(a.getZ(), b.getZ());
        int minY = Math.max(Math.min(a.getY(), b.getY()), level.getMinBuildHeight());
        int maxY = Math.min(Math.max(a.getY(), b.getY()), level.getMaxBuildHeight() - 1);
        if (minY > maxY) {
            return true;
        }
        int cx0 = minX >> 4, cx1 = maxX >> 4, cz0 = minZ >> 4, cz1 = maxZ >> 4;
        int nx = cx1 - cx0 + 1, nz = cz1 - cz0 + 1;
        boolean complete = true;
        LevelChunk[] chunks = new LevelChunk[nx * nz];
        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cz = cz0; cz <= cz1; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                chunks[(cz - cz0) * nx + (cx - cx0)] = chunk;
                complete &= chunk != null;
            }
        }
        int sec0 = level.getSectionIndex(minY);
        int secs = level.getSectionIndex(maxY) - sec0 + 1;
        // 0 = not asked yet, 1 = the section cannot hold a candidate, 2 = it might
        byte[] may = new byte[nx * nz * secs];
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int z = minZ; z <= maxZ; z++) {
            int rowZ = ((z >> 4) - cz0) * nx;
            for (int y = minY; y <= maxY; y++) {
                int section = level.getSectionIndex(y) - sec0;
                for (int cx = cx0; cx <= cx1; cx++) {
                    LevelChunk chunk = chunks[rowZ + (cx - cx0)];
                    if (chunk == null) {
                        continue;
                    }
                    int slot = (rowZ + (cx - cx0)) * secs + section;
                    if (may[slot] == 0) {
                        LevelChunkSection s = chunk.getSection(sec0 + section);
                        may[slot] = (byte) (!s.hasOnlyAir() && s.maybeHas(candidate) ? 2 : 1);
                    }
                    if (may[slot] == 1) {
                        continue;
                    }
                    LevelChunkSection s = chunk.getSection(sec0 + section);
                    int xFrom = Math.max(minX, cx << 4), xTo = Math.min(maxX, (cx << 4) + 15);
                    for (int x = xFrom; x <= xTo; x++) {
                        cellsRead++;
                        BlockState state = s.getBlockState(x & 15, y & 15, z & 15);
                        if (candidate.test(state)) {
                            cursor.set(x, y, z);
                            if (!visitor.visit(cursor, state)) {
                                return complete;
                            }
                        }
                    }
                }
            }
        }
        return complete;
    }
}
