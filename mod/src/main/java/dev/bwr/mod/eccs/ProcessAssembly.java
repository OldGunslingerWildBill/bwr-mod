package dev.bwr.mod.eccs;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/** Physical flanges shared by the STEP turbines and the scale-review pump models. */
public interface ProcessAssembly {
    BlockPos origin(BlockPos pos, BlockState state);
    boolean hasPort(AssemblyPort role);
    BlockPos portPosition(BlockPos root, BlockState state, AssemblyPort role);
    Direction portFace(BlockState state, AssemblyPort role);
    AssemblyPort portAt(BlockState state, Direction face);
    boolean complete(Level level, BlockPos root, BlockState state);
}
