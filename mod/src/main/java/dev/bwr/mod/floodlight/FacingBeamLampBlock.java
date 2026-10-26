package dev.bwr.mod.floodlight;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A fixed-aim beam lamp mounted on a ceiling or a wall: the lens faces the face that was clicked, so a lamp put under a
 * ceiling points down. The north-facing model is turned by the blockstate. The high-bay and the HPS flat panel.
 */
public abstract class FacingBeamLampBlock extends BeamLampBlock {
    public static final DirectionProperty FACING = BlockStateProperties.FACING;

    protected FacingBeamLampBlock(Properties properties) {
        super(properties);
    }

    /** The housing's shapes, one per facing. */
    protected abstract VoxelShape[] shapes();

    /** How far in front of the block centre the lens is, blocks. */
    protected abstract double lensOffset();

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getClickedFace()).setValue(LIT, false);
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return rotate(state, mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return shapes()[state.getValue(FACING).get3DDataValue()];
    }

    @Override
    public Direction facing(BlockState state) {
        return state.getValue(FACING);
    }

    @Override
    public Vec3 beamDirection(BlockState state, BeamLampBlockEntity lamp) {
        return Vec3.atLowerCornerOf(state.getValue(FACING).getNormal());
    }

    @Override
    public Vec3 beamOrigin(BlockState state, BeamLampBlockEntity lamp) {
        return Vec3.atCenterOf(lamp.getBlockPos()).add(beamDirection(state, lamp).scale(lensOffset()));
    }

    /** A fixed lamp has no aim to change, so an empty-hand click does nothing. */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        return InteractionResult.PASS;
    }
}
