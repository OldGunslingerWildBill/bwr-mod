package dev.bwr.mod.furniture;

import com.mojang.serialization.MapCodec;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The L-corner desk: two arms along the back and the east edge as the operator sees it, the open crook toward the
 * operator's left. Turn it with the facing to fit any of the four corners of a room or a run of desks.
 */
public final class CornerDeskBlock extends DeskBase {
    private static final MapCodec<CornerDeskBlock> CODEC = simpleCodec(CornerDeskBlock::new);

    /** The two arms of the top, the end panels and the modesty panels under them (sixteenths, facing north). */
    static final double[][] BODY = {
            {0, 11.2, 5.1, 5.1, 12, 14.7}, {5.1, 11.2, 1.3, 14.7, 12, 14.7}, {0, 0, 5.8, 0.6, 11.2, 14.1},
            {5.8, 0, 1.3, 14.1, 11.2, 1.9}, {0.6, 2.9, 13.4, 14.1, 11.2, 14.1}, {13.4, 2.9, 1.9, 14.1, 11.2, 13.4}};
    static final double[][] MONITOR_BOX = {{6.2, 12, 6.2, 13.6, 17.8, 13.6}};

    private static final VoxelShape[][] SHAPE = {
            FurnitureShapes.perFacing(BODY, false),
            FurnitureShapes.perFacing(FurnitureShapes.concat(BODY, MONITOR_BOX), false),
            FurnitureShapes.perFacing(FurnitureShapes.concat(BODY, MONITOR_BOX), false)};

    public CornerDeskBlock(Properties properties) {
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
