package dev.bwr.mod.furniture;

import com.mojang.serialization.MapCodec;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The round-end desk: a straight desk with a semicircular end on its east side as the operator sees it (the west side
 * when mirrored), for the end of a run. Sneaking with a bare hand mirrors it.
 */
public final class RoundDeskBlock extends DeskBase {
    public static final BooleanProperty MIRRORED = BooleanProperty.create("mirrored");
    private static final MapCodec<RoundDeskBlock> CODEC = simpleCodec(RoundDeskBlock::new);

    /** The top in three slices around the bullnose, the west end panel, modesty panel and the half drum under the end. */
    static final double[][] BODY = {
            {0, 11.2, 1.6, 9.6, 12, 14.4}, {9.6, 11.2, 2.4, 12.2, 12, 13.6}, {12.2, 11.2, 3.9, 14.5, 12, 12.1},
            {14.5, 11.2, 5.5, 16, 12, 10.5}, {0, 0, 2.2, 0.8, 11.2, 13.8}, {0.8, 2.9, 12.5, 9.6, 11.2, 13.1},
            {0.8, 0, 11.8, 9.6, 1.6, 13.1}, {9.6, 0, 2.9, 14.7, 11.2, 13.1}};
    static final double[][] FLAT = {{1.6, 12, 2.2, 9.3, 18.4, 9}};
    static final double[][] CRT = {{1.9, 12, 2.2, 9, 17.1, 11.4}};

    private static final VoxelShape[][][] SHAPE = new VoxelShape[2][3][];

    static {
        double[][][] monitor = {{}, FLAT, CRT};
        for (int m = 0; m < 2; m++) {
            for (int k = 0; k < 3; k++) {
                SHAPE[m][k] = FurnitureShapes.perFacing(FurnitureShapes.concat(BODY, monitor[k]), m == 1);
            }
        }
    }

    public RoundDeskBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(MIRRORED, false));
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(MIRRORED);
    }

    @Override
    protected BlockState alternate(BlockState state) {
        return state.setValue(MIRRORED, !state.getValue(MIRRORED));
    }

    @Override
    protected VoxelShape shapeOf(BlockState state) {
        return SHAPE[state.getValue(MIRRORED) ? 1 : 0][state.getValue(MONITOR).ordinal()]
                [FurnitureShapes.index(state.getValue(FACING))];
    }
}
