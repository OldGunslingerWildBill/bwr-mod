package dev.bwr.mod.furniture;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The shift supervisor's desk, two blocks wide: a drawer pedestal on the operator's right and an open knee space with
 * an end panel on the left. It is placed and broken as one piece; each half has its own monitor.
 */
public final class SupervisorDeskBlock extends DeskBase {
    public static final EnumProperty<DeskPart> PART = EnumProperty.create("part", DeskPart.class);
    private static final MapCodec<SupervisorDeskBlock> CODEC = simpleCodec(SupervisorDeskBlock::new);

    static final double[][] DRAWERS_BODY = {
            {0, 11.2, 0.8, 16, 12.2, 15.2}, {0, 0, 1.6, 0.8, 11.2, 14.4}, {0.8, 1.3, 1.6, 9.6, 11.2, 14.4}};
    static final double[][] KNEE_BODY = {
            {0, 11.2, 0.8, 16, 12.2, 15.2}, {15.2, 0, 1.6, 16, 11.2, 14.4}, {0, 2.9, 13.4, 15.2, 11.2, 14.1},
            {5.6, 9.6, 1.9, 12.8, 11.2, 8}};

    private static final VoxelShape[][][] SHAPE = new VoxelShape[2][3][];

    static {
        double[][][] monitor = {{}, DeskBlock.FLAT, DeskBlock.CRT};
        double[][][] body = {DRAWERS_BODY, KNEE_BODY};
        for (int p = 0; p < 2; p++) {
            for (int k = 0; k < 3; k++) {
                SHAPE[p][k] = FurnitureShapes.perFacing(FurnitureShapes.concat(body[p], monitor[k]), false);
            }
        }
    }

    public SupervisorDeskBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(PART, DeskPart.DRAWERS));
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(PART);
    }

    @Override
    public int cellCount() {
        return 2;
    }

    /** The knee block is one block toward the operator's left, which is the facing turned clockwise. */
    @Override
    protected Vec3i cellOffset(int index, Direction facing) {
        return index == 0 ? Vec3i.ZERO : facing.getClockWise().getNormal();
    }

    @Override
    protected int cellIndex(BlockState state) {
        return state.getValue(PART) == DeskPart.DRAWERS ? 0 : 1;
    }

    @Override
    protected BlockState withCell(BlockState origin, int index) {
        return origin.setValue(PART, index == 0 ? DeskPart.DRAWERS : DeskPart.KNEE);
    }

    @Override
    protected VoxelShape shapeOf(BlockState state) {
        return SHAPE[state.getValue(PART).ordinal()][state.getValue(MONITOR).ordinal()]
                [FurnitureShapes.index(state.getValue(FACING))];
    }
}
