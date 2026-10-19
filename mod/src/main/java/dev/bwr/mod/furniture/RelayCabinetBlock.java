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
 * Relay cabinet / back-panel cabinet, two blocks tall, placed and broken like a door. The standard cabinet has double
 * doors, louvres and an indicator window at the front and a service door at the back; the plain-back variant has a
 * flat back at the block edge so two rows can stand back to back.
 */
public final class RelayCabinetBlock extends AssemblyBlock {
    public static final EnumProperty<FurnitureHalf> HALF = EnumProperty.create("half", FurnitureHalf.class);
    private static final MapCodec<RelayCabinetBlock> CODEC = simpleCodec(p -> new RelayCabinetBlock(p, false));
    private static final MapCodec<RelayCabinetBlock> CODEC_PLAIN = simpleCodec(p -> new RelayCabinetBlock(p, true));
    private static final VoxelShape[] SHAPE = FurnitureShapes.perFacing(new double[][] {{0, 0, 3.2, 16, 16, 16}}, false);

    private final boolean plainBack;

    public RelayCabinetBlock(Properties properties, boolean plainBack) {
        super(properties);
        this.plainBack = plainBack;
        registerDefaultState(defaultBlockState().setValue(HALF, FurnitureHalf.LOWER));
    }

    public boolean plainBack() {
        return plainBack;
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return plainBack ? CODEC_PLAIN : CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(HALF);
    }

    @Override
    public int cellCount() {
        return 2;
    }

    @Override
    protected Vec3i cellOffset(int index, Direction facing) {
        return new Vec3i(0, index, 0);
    }

    @Override
    protected int cellIndex(BlockState state) {
        return state.getValue(HALF) == FurnitureHalf.LOWER ? 0 : 1;
    }

    @Override
    protected BlockState withCell(BlockState origin, int index) {
        return origin.setValue(HALF, index == 0 ? FurnitureHalf.LOWER : FurnitureHalf.UPPER);
    }

    @Override
    protected VoxelShape shapeOf(BlockState state) {
        return SHAPE[FurnitureShapes.index(state.getValue(FACING))];
    }
}
