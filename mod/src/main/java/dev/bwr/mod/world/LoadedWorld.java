package dev.bwr.mod.world;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

/** Structure surveys must never turn a read into a synchronous chunk load. */
public final class LoadedWorld {
    private LoadedWorld() {}
    public static final class MissingChunk extends RuntimeException {
        public final BlockPos pos;
        private MissingChunk(BlockPos pos) { super(null, null, false, false); this.pos=pos.immutable(); }
    }
    public static void require(Level level, BlockPos pos) {
        if (!level.isLoaded(pos)) throw new MissingChunk(pos);
    }
    public static BlockState block(Level level, BlockPos pos) {
        require(level,pos); return level.getBlockState(pos);
    }
    public static FluidState fluid(Level level, BlockPos pos) {
        require(level,pos); return level.getFluidState(pos);
    }

    /**
     * Whether the chunk holding {@code pos} is completely available right now, without touching the chunk system.
     *
     * <p>{@code Level.isLoaded} answers from the chunk's ticket level, which is already "loaded" while the chunk is
     * still being read, so the {@code getBlockEntity} that follows it can wait on a chunk that is not there yet. This
     * answers from the chunk object itself, and is false for a chunk that is only on its way.
     */
    public static boolean available(Level level, BlockPos pos) {
        return !level.isOutsideBuildHeight(pos)
                && level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) != null;
    }

    /**
     * The block entity at {@code pos} if its chunk is completely available, otherwise null; the replacement for
     * {@code level.isLoaded(pos) && level.getBlockEntity(pos)} wherever a machine reads another machine that may be
     * many chunks away.
     *
     * <p>{@code Level.getBlockEntity} asks for the chunk the way every block read does, and a read that misses the
     * chunk cache adds a one-tick "unknown" ticket at the chunk's loaded level. A machine that read a block entity two
     * chunks beyond the last ticking chunk every tick therefore kept that chunk from ever unloading (the ticket
     * renewed itself as long as the reader ticked), and one that read a chunk still being loaded blocked the server
     * thread until it finished. This reads the chunk object if it exists and nothing else: no ticket, no wait, no
     * load. Server thread only (off it the chunk is not available, as for {@code getBlockEntity}).
     */
    /** The block state at {@code pos} if its chunk is completely available (see {@link #blockEntity}), otherwise null. */
    public static BlockState state(Level level, BlockPos pos) {
        if (level.isOutsideBuildHeight(pos)) {
            return null;
        }
        net.minecraft.world.level.chunk.LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        return chunk == null ? null : chunk.getBlockState(pos);
    }

    public static net.minecraft.world.level.block.entity.BlockEntity blockEntity(Level level, BlockPos pos) {
        if (level.isOutsideBuildHeight(pos)) {
            return null;
        }
        net.minecraft.world.level.chunk.LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        return chunk == null ? null : chunk.getBlockEntity(pos, net.minecraft.world.level.chunk.LevelChunk.EntityCreationType.IMMEDIATE);
    }
}
