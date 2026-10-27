package dev.bwr.mod.refuelling;

import com.mojang.serialization.MapCodec;
import dev.bwr.mod.gui.RefuellingMenu;
import dev.bwr.mod.reactor.ReactorControllerBlockEntity;
import dev.bwr.mod.text.BwrText;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.level.BlockGetter;

/**
 * The bridge console: stands beside a run of {@link RefuellingRailBlock}s and is the bridge's controls. Right-click opens
 * the refuelling map as the bridge's control screen; sneak-click prints what the bridge sees (rails, reactor, pool, chute)
 * and what it is doing. It has no ticker: a moving bridge is ticked by scheduled block ticks that exist only while a
 * move is in progress ({@link RefuellingBridgeBlockEntity#scheduledTick}).
 */
public class RefuellingBridgeConsoleBlock extends BaseEntityBlock {

    public static final MapCodec<RefuellingBridgeConsoleBlock> CODEC = simpleCodec(RefuellingBridgeConsoleBlock::new);
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    private static final VoxelShape SHAPE = Block.box(1, 0, 1, 15, 14, 15);

    public RefuellingBridgeConsoleBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new RefuellingBridgeBlockEntity(pos, state);
    }

    /** The bridge's own pace: scheduled by the block entity while it works, never otherwise. */
    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (level.getBlockEntity(pos) instanceof RefuellingBridgeBlockEntity be) {
            be.scheduledTick(level);
        }
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (!(level.getBlockEntity(pos) instanceof RefuellingBridgeBlockEntity be) || !(player instanceof ServerPlayer sp)) {
            return InteractionResult.CONSUME;
        }
        BridgeSite site = be.refreshSite();
        if (player.isShiftKeyDown()) {
            for (Component line : report(be, site)) {
                player.displayClientMessage(line, false);
            }
            return InteractionResult.CONSUME;
        }
        if (!site.ok()) {
            player.displayClientMessage(site.problem, true);
            return InteractionResult.CONSUME;
        }
        ReactorControllerBlockEntity reactor = site.reactor(level);
        if (reactor == null) {
            player.displayClientMessage(BwrText.tr("status.bwr.refuelling_bridge.no_reactor",
                    "No formed reactor lies between the bridge's rails."), true);
            return InteractionResult.CONSUME;
        }
        RefuellingMenu.openBridge(sp, reactor, be);
        return InteractionResult.CONSUME;
    }

    static java.util.List<Component> report(RefuellingBridgeBlockEntity be, BridgeSite site) {
        java.util.List<Component> lines = new java.util.ArrayList<>();
        if (!site.ok()) {
            lines.add(site.problem);
            return lines;
        }
        lines.add(BwrText.tr("status.bwr.refuelling_bridge.report_rails",
                "Rails: %s blocks long along %s, %s apart, at y %s.", site.aHi - site.aLo + 1, site.axis.getName(), site.cHigh - site.cLow, site.railY));
        lines.add(site.reactorPos == null
                ? BwrText.tr("status.bwr.refuelling_bridge.report_no_reactor", "Reactor: none between the rails.")
                : BwrText.tr("status.bwr.refuelling_bridge.report_reactor", "Reactor: controller at %s.", site.reactorPos.toShortString()));
        lines.add(site.poolPos == null
                ? BwrText.tr("status.bwr.refuelling_bridge.report_no_pool", "Pool: none between the rails.")
                : BwrText.tr("status.bwr.refuelling_bridge.report_pool", "Pool: controller at %s.", site.poolPos.toShortString()));
        lines.add(BwrText.tr("status.bwr.refuelling_bridge.report_chute", "Cattle chute: %s.", be.chute().state().name().toLowerCase(java.util.Locale.ROOT)));
        if (be.chute().slot()) {
            lines.add(BwrText.tr("status.bwr.refuelling_bridge.report_slot",
                    "The chute runs through a slot in the pool wall: water surface y %s, floor y %s.",
                    String.format(java.util.Locale.ROOT, "%.2f", be.chute().surfaceY()), String.format(java.util.Locale.ROOT, "%.2f", be.chute().floorY())));
        }
        lines.add(BwrText.tr("status.bwr.refuelling_bridge.report_state", "Bridge: %s, %s queued%s.",
                be.phase().name().toLowerCase(java.util.Locale.ROOT), be.queued(), be.carried().isEmpty() ? "" : ", holding an assembly"));
        return lines;
    }

    /** Breaking the console drops what the grapple holds. */
    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState next, boolean moved) {
        if (state.getBlock() != next.getBlock() && level instanceof ServerLevel server
                && level.getBlockEntity(pos) instanceof RefuellingBridgeBlockEntity be) {
            be.dropCarried(server);
        }
        super.onRemove(state, level, pos, next, moved);
    }
}
