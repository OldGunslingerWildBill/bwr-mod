package dev.bwr.mod.radiation;

import com.mojang.serialization.MapCodec;
import dev.bwr.core.radiation.DoseModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The area radiation monitor: a small box that mounts on any face of any block (a wall, a floor, a ceiling, the back of a
 * control panel) with its face and lamp looking out. It reads the dose rate where it hangs, shows it to a player who
 * clicks it, drives a lamp and exposes a reading and two booleans to panels and computers. It never acts on anything.
 *
 * <ul>
 *   <li><b>Right-click</b> (empty hand): the dose rate and the two setpoints on the action bar.</li>
 *   <li><b>Sneak-right-click</b> (empty hand): the next higher alarm setpoint from a ladder of presets, wrapping round
 *       (a computer or a panel knob can set any value).</li>
 *   <li><b>LAMP</b>: 0 dark, 1 amber at the alert setpoint (a tenth of the alarm), 2 red at the alarm setpoint; it is
 *       also the redstone output (0, 7, 15) and glows (light level 0, 4, 8).</li>
 * </ul>
 * With a wrench or linker in hand the click goes to the tool, so the monitor wires like any other device.
 */
public final class AreaRadiationMonitorBlock extends BaseEntityBlock {
    public static final MapCodec<AreaRadiationMonitorBlock> CODEC = simpleCodec(AreaRadiationMonitorBlock::new);
    /** The way the face looks; the back is against what it is mounted on. */
    public static final DirectionProperty FACING = BlockStateProperties.FACING;
    public static final IntegerProperty LAMP = IntegerProperty.create("lamp", 0, 2);

    /** The alarm setpoints a sneak-click steps through, mSv/h. */
    public static final double[] SETPOINT_LADDER = {0.01, 0.05, 0.1, 0.5, 1.0, 5.0, 10.0, 50.0, 100.0, 1000.0};

    private static final VoxelShape[] SHAPES = new VoxelShape[6];

    static {
        for (Direction facing : Direction.values()) {
            // The box of a north-facing monitor: 10 x 10 wide, 6 deep against the south side of the block.
            double[] a = {3, 3, 10};
            double[] b = {13, 13, 16};
            double[] lo = rotate(a, facing);
            double[] hi = rotate(b, facing);
            SHAPES[facing.get3DDataValue()] = Block.box(Math.min(lo[0], hi[0]), Math.min(lo[1], hi[1]),
                    Math.min(lo[2], hi[2]), Math.max(lo[0], hi[0]), Math.max(lo[1], hi[1]), Math.max(lo[2], hi[2]));
        }
    }

    /** A point of the north-facing model turned to face {@code facing} (the same turns as the blockstate file). */
    private static double[] rotate(double[] p, Direction facing) {
        double x = p[0], y = p[1], z = p[2];
        return switch (facing) {
            case NORTH -> new double[] {x, y, z};
            case SOUTH -> new double[] {16 - x, y, 16 - z};
            case EAST -> new double[] {16 - z, y, x};
            case WEST -> new double[] {z, y, 16 - x};
            case UP -> new double[] {x, 16 - z, y};
            case DOWN -> new double[] {x, z, 16 - y};
        };
    }

    public AreaRadiationMonitorBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(LAMP, 0));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, LAMP);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getClickedFace());
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
        return SHAPES[state.getValue(FACING).get3DDataValue()];
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new AreaRadiationMonitorBlockEntity(pos, state);
    }

    @Override
    protected boolean isSignalSource(BlockState state) {
        return true;
    }

    @Override
    protected int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        return switch (state.getValue(LAMP)) {
            case 2 -> 15;
            case 1 -> 7;
            default -> 0;
        };
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
                                              net.minecraft.world.InteractionHand hand, BlockHitResult hit) {
        // A wiring tool is the item's business (select the monitor, open its Networking screen), not the block's.
        if (stack.getItem() instanceof dev.bwr.mod.panel.NetworkingWrenchItem
                || stack.getItem() instanceof dev.bwr.mod.panel.PanelLinkerItem
                || stack.getItem() instanceof dev.bwr.mod.panel.PanelWrenchItem) {
            return ItemInteractionResult.SKIP_DEFAULT_BLOCK_INTERACTION;
        }
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (!(level.getBlockEntity(pos) instanceof AreaRadiationMonitorBlockEntity monitor)) {
            return InteractionResult.PASS;
        }
        if (player.isShiftKeyDown() && player.getMainHandItem().isEmpty() && player.getOffhandItem().isEmpty()) {
            if (!player.getAbilities().mayBuild || !dev.bwr.mod.world.AssemblyAccess.permitted(level, player, pos)) {
                player.displayClientMessage(Component.translatable("message.bwr.radiation_monitor.not_permitted"), true);
                return InteractionResult.CONSUME;
            }
            monitor.setHighSetpoint(nextSetpoint(monitor.highSetpoint()));
            monitor.invalidate();
            monitor.refresh();
            player.displayClientMessage(Component.translatable("message.bwr.radiation_monitor.setpoint",
                    DoseModel.formatRate(monitor.highSetpoint()), DoseModel.formatRate(monitor.alertSetpoint())), true);
            return InteractionResult.CONSUME;
        }
        player.displayClientMessage(Component.translatable("message.bwr.radiation_monitor.reading",
                DoseModel.formatRate(monitor.doseRate()), DoseModel.formatRate(monitor.alertSetpoint()),
                DoseModel.formatRate(monitor.highSetpoint())), true);
        return InteractionResult.CONSUME;
    }

    /** The next rung of {@link #SETPOINT_LADDER} above {@code current}, or the first when it is at the top. */
    public static double nextSetpoint(double current) {
        for (double rung : SETPOINT_LADDER) {
            if (rung > current * 1.0000001) {
                return rung;
            }
        }
        return SETPOINT_LADDER[0];
    }
}
