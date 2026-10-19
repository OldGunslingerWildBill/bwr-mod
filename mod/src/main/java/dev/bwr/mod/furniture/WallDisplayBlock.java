package dev.bwr.mod.furniture;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Wall display / overview panel: a large flat display frame with a dark screen, 2 or 3 blocks wide and 2 tall (the
 * AP1000 and ABWR wide display panel look). One item places the whole frame, with the origin at the bottom block on the
 * operator's right and the rest toward the operator's left and up. No live content.
 */
public final class WallDisplayBlock extends AssemblyBlock {
    public static final IntegerProperty COL = IntegerProperty.create("col", 0, 2);
    public static final IntegerProperty ROW = IntegerProperty.create("row", 0, 1);
    private static final MapCodec<WallDisplayBlock> CODEC2 = simpleCodec(p -> new WallDisplayBlock(p, 2));
    private static final MapCodec<WallDisplayBlock> CODEC3 = simpleCodec(p -> new WallDisplayBlock(p, 3));
    private static final VoxelShape[] SHAPE = FurnitureShapes.perFacing(new double[][] {{0, 0, 13.4, 16, 16, 16}}, false);

    private final int width;

    public WallDisplayBlock(Properties properties, int width) {
        super(properties);
        if (width != 2 && width != 3) {
            throw new IllegalArgumentException("wall display width " + width);
        }
        this.width = width;
        registerDefaultState(defaultBlockState().setValue(COL, 0).setValue(ROW, 0));
    }

    public int width() {
        return width;
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return width == 2 ? CODEC2 : CODEC3;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(COL, ROW);
    }

    @Override
    public int cellCount() {
        return width * 2;
    }

    /** Cells run along the model's x axis (the operator's left) and then up: index = col + row * width. */
    @Override
    protected Vec3i cellOffset(int index, Direction facing) {
        int col = index % width;
        int row = index / width;
        Vec3i along = facing.getClockWise().getNormal();
        return new Vec3i(along.getX() * col, row, along.getZ() * col);
    }

    @Override
    protected int cellIndex(BlockState state) {
        return state.getValue(COL) + state.getValue(ROW) * width;
    }

    @Override
    protected BlockState withCell(BlockState origin, int index) {
        return origin.setValue(COL, index % width).setValue(ROW, index / width);
    }

    @Override
    protected VoxelShape shapeOf(BlockState state) {
        return SHAPE[FurnitureShapes.index(state.getValue(FACING))];
    }
}
