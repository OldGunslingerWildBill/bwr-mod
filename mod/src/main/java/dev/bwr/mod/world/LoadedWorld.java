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
}
