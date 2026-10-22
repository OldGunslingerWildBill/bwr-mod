package dev.bwr.mod.spentfuel;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Stainless-lined concrete for the floor and walls of a spent fuel pool. No block entity, no ticker: placing or
 * breaking one asks the pools around it to look at their structure again.
 */
public class SpentFuelPoolWallBlock extends Block {

    public SpentFuelPoolWallBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState old, boolean moved) {
        super.onPlace(state, level, pos, old, moved);
        if (state.getBlock() != old.getBlock()) {
            SpentFuelPoolRegistry.changed(level, pos);
        }
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState next, boolean moved) {
        if (state.getBlock() != next.getBlock()) {
            SpentFuelPoolRegistry.changed(level, pos);
        }
        super.onRemove(state, level, pos, next, moved);
    }
}
