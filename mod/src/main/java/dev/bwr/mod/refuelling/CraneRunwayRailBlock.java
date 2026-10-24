package dev.bwr.mod.refuelling;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A length of runway beam for the reactor building crane, with the crane rail on its top. It does nothing by itself: no entity, no
 * ticker. Two parallel runs of it, high on opposite walls of the reactor hall, are what a crane console finds and the crane rides
 * ({@link BridgeSite#surveyCrane}). The block's axis is the direction the crane runs; it is set from the way the player faces when
 * placing it. The rail head is 13/16 of a block above the block's floor.
 */
public class CraneRunwayRailBlock extends Block {

    public static final EnumProperty<Direction.Axis> AXIS = BlockStateProperties.HORIZONTAL_AXIS;
    /** Where the crane's wheels run, above the floor of the block, m. */
    public static final double RAIL_TOP = 13.0 / 16.0;
    private static final VoxelShape SHAPE_X = Block.box(0, 0, 3, 16, 13, 13);
    private static final VoxelShape SHAPE_Z = Block.box(3, 0, 0, 13, 13, 16);

    public CraneRunwayRailBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(AXIS, Direction.Axis.X));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(AXIS);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(AXIS, context.getHorizontalDirection().getAxis());
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return rotation == Rotation.CLOCKWISE_90 || rotation == Rotation.COUNTERCLOCKWISE_90
                ? state.setValue(AXIS, state.getValue(AXIS) == Direction.Axis.X ? Direction.Axis.Z : Direction.Axis.X) : state;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return state.getValue(AXIS) == Direction.Axis.X ? SHAPE_X : SHAPE_Z;
    }
}
