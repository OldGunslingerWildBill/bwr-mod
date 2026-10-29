package dev.bwr.mod.floodlight;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Vector3f;

/**
 * The xenon floodlight: an aimable flood fixture with a 30-degree beam that reaches 32 blocks, or as far as it is set to
 * (8 to 128; see {@link XenonBeam}). The front faces the way the player was looking when it was placed. Empty-hand right-click
 * opens the aiming screen ({@link dev.bwr.mod.gui.FloodlightAimMenu}: heading 0 to 359, tilt -75 to +30, reach, in single
 * steps); sneak-right-click is the quick tilt (15 degrees a click, -75 to +30 and round). A redstone signal switches it on
 * at once.
 *
 * <p>The head (reflector, yoke, lens) is drawn by {@link dev.bwr.mod.floodlight.client.XenonFloodlightRenderer} so that it
 * turns to the aim; this block's model is the base and the ballast box.
 */
public class XenonFloodlightBlock extends BeamLampBlock {
    public static final MapCodec<XenonFloodlightBlock> CODEC = simpleCodec(XenonFloodlightBlock::new);
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    /** The trunnion (the head's pitch pivot) above the block's bottom, blocks. */
    public static final double PIVOT_Y = 0.625;

    private static final VoxelShape SHAPE = Block.box(1, 0, 1, 15, 15, 15);

    public XenonFloodlightBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(LIT, false));
    }

    public static BlockBehaviour.Properties lampProperties() {
        return BeamLampBlock.lampProperties(10);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, LIT);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection()).setValue(LIT, false);
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
        return SHAPE;
    }

    @Override
    public double range() {
        return 32;
    }

    @Override
    public double halfAngleDegrees() {
        return 15;
    }

    @Override
    public int lightStep() {
        return 2;
    }

    @Override
    public Direction facing(BlockState state) {
        return state.getValue(FACING);
    }

    /**
     * The model is authored for a north-facing lamp (front at -Z). The renderer turns it by the facing, then the yaw offset,
     * then the pitch about the trunnion; the vector goes through the same three turns.
     */
    @Override
    public Vec3 beamDirection(BlockState state, BeamLampBlockEntity lamp) {
        Vector3f v = new Vector3f(0f, 0f, -1f);
        v.rotateX((float) Math.toRadians(lamp.pitchDegrees()));
        v.rotateY((float) Math.toRadians(-lamp.yawDegrees()));
        v.rotateY((float) Math.toRadians(-BeamLampBlockEntity.yawOf(state.getValue(FACING))));
        return new Vec3(v.x, v.y, v.z);
    }

    @Override
    public Vec3 beamOrigin(BlockState state, BeamLampBlockEntity lamp) {
        BlockPos p = lamp.getBlockPos();
        return new Vec3(p.getX() + 0.5, p.getY() + PIVOT_Y, p.getZ() + 0.5).add(beamDirection(state, lamp).scale(0.45));
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (!(level.getBlockEntity(pos) instanceof BeamLampBlockEntity lamp)) {
            return InteractionResult.PASS;
        }
        if (!BeamLampBlockEntity.mayAim(level, player, pos)) {
            player.displayClientMessage(Component.translatable("message.bwr.radiation_monitor.not_permitted"), true);
            return InteractionResult.CONSUME;
        }
        if (player.isShiftKeyDown()) {
            // The quick tilt: 15 degrees a click, round from +30 to -75, with the aim on the action bar.
            lamp.stepAim(true);
            player.displayClientMessage(lamp.aimMessage(), true);
        } else if (player instanceof net.minecraft.server.level.ServerPlayer server) {
            dev.bwr.mod.gui.FloodlightAimMenu.open(server, pos);
        }
        return InteractionResult.CONSUME;
    }
}
