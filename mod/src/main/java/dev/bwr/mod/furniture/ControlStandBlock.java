package dev.bwr.mod.furniture;

import com.mojang.serialization.MapCodec;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A stand-alone control stand: a narrow pedestal with a small tilted top carrying a lamp and button panel, for a
 * status point or a pushbutton station in the middle of a room. Decorative only; nothing mounts on it.
 */
public final class ControlStandBlock extends AssemblyBlock {
    private static final MapCodec<ControlStandBlock> CODEC = simpleCodec(ControlStandBlock::new);
    private static final VoxelShape[] SHAPE = FurnitureShapes.perFacing(new double[][] {
            {2.9, 0, 2.9, 13.1, 1, 13.1}, {5.4, 1, 5.4, 10.6, 12.8, 10.6}, {2.6, 12.8, 3.5, 13.4, 15.7, 12.4}}, false);

    public ControlStandBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected VoxelShape shapeOf(BlockState state) {
        return SHAPE[FurnitureShapes.index(state.getValue(FACING))];
    }
}
