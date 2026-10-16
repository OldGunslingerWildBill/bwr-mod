package dev.bwr.mod.panel;

import com.mojang.serialization.MapCodec;
import dev.bwr.mod.panel.component.ComponentItem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
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
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A control panel block: vertical board, benchboard or console, depending on
 * its {@link PanelType}. The body is a plain model; everything on the face is
 * state in the {@link PanelBlockEntity} drawn by its renderer.
 *
 * <p>All three styles are one class because they differ only in geometry
 * ({@link PanelGeometry}) and in which neighbours continue the plane
 * ({@link PanelType}).
 */
public final class PanelBlock extends BaseEntityBlock {
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    private static final MapCodec<PanelBlock> CODEC_VERTICAL = simpleCodec(p -> new PanelBlock(p, PanelType.VERTICAL));
    private static final MapCodec<PanelBlock> CODEC_BENCHBOARD = simpleCodec(p -> new PanelBlock(p, PanelType.BENCHBOARD));
    private static final MapCodec<PanelBlock> CODEC_CONSOLE = simpleCodec(p -> new PanelBlock(p, PanelType.CONSOLE));

    private final PanelType type;

    public PanelBlock(Properties properties, PanelType type) {
        super(properties.noOcclusion());
        this.type = type;
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    public PanelType panelType() {
        return type;
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return switch (type) {
            case VERTICAL -> CODEC_VERTICAL;
            case BENCHBOARD -> CODEC_BENCHBOARD;
            case CONSOLE -> CODEC_CONSOLE;
        };
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new PanelBlockEntity(pos, state);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return PanelGeometry.shape(type, state.getValue(FACING));
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return PanelGeometry.shape(type, state.getValue(FACING));
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction facing = context.getHorizontalDirection().getOpposite();
        // A block that would grow its group past the configured size is not placed.
        return PanelGroup.placementAllowed(context.getLevel(), context.getClickedPos(), type, facing)
                ? defaultBlockState().setValue(FACING, facing) : null;
    }

    // ---- interaction (the vanilla path; the client input hook normally sends packets instead)

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
                                              InteractionHand hand, BlockHitResult hit) {
        boolean component = stack.getItem() instanceof ComponentItem;
        boolean wrench = stack.getItem() instanceof PanelWrenchItem;
        boolean linker = stack.getItem() instanceof PanelLinkerItem;
        boolean tape = stack.getItem() instanceof dev.bwr.mod.panel.mimic.MimicTapeItem;
        if (!component && !wrench && !linker && !tape) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (level.isClientSide()) {
            return ItemInteractionResult.SUCCESS;
        }
        if (!(player instanceof ServerPlayer sender) || !(level.getBlockEntity(pos) instanceof PanelBlockEntity be)) {
            return ItemInteractionResult.CONSUME;
        }
        int[] cell = new int[2];
        if (!PanelOps.cellAt(be, sender.getEyePosition(), sender.getLookAngle(), cell)) {
            return ItemInteractionResult.CONSUME;
        }
        if (tape) {
            // The vanilla path of the mimic tape; the client input hook normally sends the exact cursor in a packet.
            double[] exact = new double[2];
            PanelGeometry.Frame frame = PanelGeometry.frame(panelType(), state.getValue(FACING));
            Vec3 eye = sender.getEyePosition();
            Vec3 look = sender.getLookAngle();
            if (PanelGeometry.rayToCell(frame, pos.getX(), pos.getY(), pos.getZ(), eye.x, eye.y, eye.z, look.x, look.y,
                    look.z, exact)) {
                dev.bwr.mod.panel.mimic.MimicOps.use(sender, hand, pos, exact[0], exact[1]);
            }
        } else if (linker) {
            PanelBlockEntity.Claim claim = be.claimAt(cell[0], cell[1]);
            if (claim != null && !sender.isShiftKeyDown()) {
                dev.bwr.mod.panel.wiring.WiringOps.openScreen(sender, claim.owner(), claim.id());
            } else if (claim == null && !sender.isShiftKeyDown()) {
                var path = mimicUnderCursor(be, sender);
                if (path != null) {
                    dev.bwr.mod.panel.wiring.WiringOps.openScreen(sender, pos, -path.localId());
                }
            }
        } else if (component) {
            if (sender.isShiftKeyDown()) {
                ComponentItem.cycle(stack, sender);
            } else {
                PanelOps.place(sender, hand, pos, cell[0], cell[1]);
            }
        } else {
            PanelBlockEntity.Claim claim = be.claimAt(cell[0], cell[1]);
            if (claim != null) {
                if (sender.isShiftKeyDown()) {
                    PanelOps.remove(sender, claim.owner(), claim.id());
                } else {
                    PanelSync.sendOpenConfig(sender, claim.owner(), claim.id());
                }
            } else if (!sender.isShiftKeyDown()) {
                // No component under the cursor: the wrench opens the style screen of a mimic path there.
                var path = mimicUnderCursor(be, sender);
                if (path != null) {
                    net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(sender,
                            new dev.bwr.mod.panel.mimic.MimicPayloads.OpenConfig(pos, path.localId()));
                }
            }
        }
        return ItemInteractionResult.CONSUME;
    }

    /** The mimic path under the player's look ray on this block, or null. */
    private dev.bwr.mod.panel.mimic.MimicPath mimicUnderCursor(PanelBlockEntity be, ServerPlayer player) {
        if (be.mimicCount() == 0) {
            return null;
        }
        double[] exact = new double[2];
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getLookAngle();
        BlockPos pos = be.getBlockPos();
        PanelGeometry.Frame frame = PanelGeometry.frame(be.panelType(), be.facing());
        return PanelGeometry.rayToCell(frame, pos.getX(), pos.getY(), pos.getZ(), eye.x, eye.y, eye.z, look.x, look.y,
                look.z, exact) ? dev.bwr.mod.panel.mimic.MimicOps.pick(be, exact[0], exact[1]) : null;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                               BlockHitResult hit) {
        if (!(level.getBlockEntity(pos) instanceof PanelBlockEntity be)) {
            return InteractionResult.PASS;
        }
        // Hit location comes from the vanilla ray cast against the block's outline; recompute the cell from the
        // view ray against the true mounting plane instead.
        int[] cell = new int[2];
        if (!PanelOps.cellAt(be, player.getEyePosition(), player.getLookAngle(), cell)) {
            return InteractionResult.PASS;
        }
        PanelBlockEntity.Claim claim = be.claimAt(cell[0], cell[1]);
        if (claim == null) {
            return InteractionResult.PASS;
        }
        if (level.isClientSide()) {
            // The client has the same claim table; swing only for components that do something.
            var owner = level.getBlockEntity(claim.owner());
            if (owner instanceof PanelBlockEntity ownerBe && ownerBe.component(claim.id()) != null
                    && ownerBe.component(claim.id()).type() != null
                    && ownerBe.component(claim.id()).type().isInteractive()) {
                return InteractionResult.SUCCESS;
            }
            return InteractionResult.PASS;
        }
        if (player instanceof ServerPlayer sender) {
            return PanelOps.forwardUse(sender, be, cell[0], cell[1], cell[0] + 0.5, cell[1] + 0.5);
        }
        return InteractionResult.PASS;
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean moving) {
        super.onPlace(state, level, pos, oldState, moving);
        // Server only: the new block's own entity does not exist yet, but its neighbours' cached groups are stale.
        if (!level.isClientSide()) {
            PanelBlockEntity.invalidateAround(level, pos, state);
        }
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState replacement, boolean moving) {
        // Read the block entity before super removes it: every component owned here, or covering any cell
        // here, comes off the panel and drops its item.
        if (!level.isClientSide() && !replacement.is(this) && level.getBlockEntity(pos) instanceof PanelBlockEntity be) {
            be.releaseEverything();
        }
        super.onRemove(state, level, pos, replacement, moving);
        if (!level.isClientSide()) {
            PanelBlockEntity.invalidateAround(level, pos, state);
        }
    }

    // ---- redstone: input at the back face, output from it (spec section 3)

    @Override
    protected boolean isSignalSource(BlockState state) {
        return true;
    }

    @Override
    protected int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        // `direction` points from the receiving block toward this one, so the receiver behind the panel
        // asks with the panel's own facing; the other sides get nothing.
        if (direction == state.getValue(FACING) && level.getBlockEntity(pos) instanceof PanelBlockEntity be) {
            return be.redstonePower();
        }
        return 0;
    }

    @Override
    protected int getDirectSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        return getSignal(state, level, pos, direction);
    }

    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighbour, BlockPos neighbourPos,
                                   boolean movedByPiston) {
        super.neighborChanged(state, level, pos, neighbour, neighbourPos, movedByPiston);
        if (!level.isClientSide() && level.getBlockEntity(pos) instanceof PanelBlockEntity be) {
            be.redstoneNeighbourChanged();
        }
    }

    /** Tells the block behind a panel (and its neighbours, except the panel) that the emitted strength changed. */
    static void notifyBack(Level level, BlockPos pos, Block block, Direction facing) {
        BlockPos behind = pos.relative(facing.getOpposite());
        level.neighborChanged(behind, block, pos);
        level.updateNeighborsAtExceptFromFacing(behind, block, facing);
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }
}
