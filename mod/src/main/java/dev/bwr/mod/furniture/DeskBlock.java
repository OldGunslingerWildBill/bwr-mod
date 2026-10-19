package dev.bwr.mod.furniture;

import com.mojang.serialization.MapCodec;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

/** The straight operator desk, one block wide; desks stand side by side in a run. */
public final class DeskBlock extends DeskBase {
    private static final MapCodec<DeskBlock> CODEC = simpleCodec(DeskBlock::new);

    /** Top, two end panels, modesty panel and toe board, pencil drawer (sixteenths, facing north). */
    static final double[][] BODY = {
            {0, 11.2, 1.6, 16, 12, 14.4}, {0, 0, 2.2, 0.8, 11.2, 13.8}, {15.2, 0, 2.2, 16, 11.2, 13.8},
            {0.8, 2.9, 12.5, 15.2, 11.2, 13.1}, {0.8, 0, 11.8, 15.2, 1.6, 13.1}, {4.5, 9.6, 1.9, 11.5, 11.2, 8.3}};
    /** A flat monitor with its keyboard; a CRT is deeper. Both stand on the desk top, centred. */
    static final double[][] FLAT = {{4.2, 12, 2.2, 11.8, 18.4, 9}};
    static final double[][] CRT = {{4.5, 12, 2.2, 11.5, 17.1, 11.4}};

    private static final VoxelShape[][] SHAPE = {
            FurnitureShapes.perFacing(BODY, false),
            FurnitureShapes.perFacing(FurnitureShapes.concat(BODY, FLAT), false),
            FurnitureShapes.perFacing(FurnitureShapes.concat(BODY, CRT), false)};

    public DeskBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected VoxelShape shapeOf(BlockState state) {
        return SHAPE[state.getValue(MONITOR).ordinal()][FurnitureShapes.index(state.getValue(FACING))];
    }
}
