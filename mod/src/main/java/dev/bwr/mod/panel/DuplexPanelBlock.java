package dev.bwr.mod.panel;

import com.mojang.serialization.MapCodec;
import dev.bwr.mod.world.AssemblyAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The duplex control board: the owner's Fusion model, a vertical board with a bench in front and a tilted annunciator
 * header overhanging the operator, built from 1 block wide columns that line up sideways. One item places a whole
 * column of six blocks ({@link DuplexCell}): the bench, the body behind it, two board blocks, the top of the slab and
 * the header. The bench, the two board blocks and the header are panel blocks with the panel block entity; components
 * mount on them as on any panel, each surface joining the same surface of the neighbouring columns
 * ({@link PanelType#DUPLEX_BENCH}, {@link PanelType#DUPLEX_BOARD}, {@link PanelType#DUPLEX_HEADER}). The body and
 * the top have no block entity and nothing mounts there.
 *
 * <p>Placing and breaking follow the room furniture ({@code AssemblyBlock}): the column is placed whole or not at
 * all; breaking any block of it removes the whole column with one drop (from the bench's loot table, whichever block
 * was broken), and every panel block that goes drops its mounted components as a panel block always does. A column
 * that loses a block some other way (an explosion, a command) falls apart, block by block, through shape updates.
 */
public final class DuplexPanelBlock extends PanelBlock {
    public static final EnumProperty<DuplexCell> CELL = EnumProperty.create("cell", DuplexCell.class);
    static final MapCodec<DuplexPanelBlock> CODEC = simpleCodec(DuplexPanelBlock::new);

    public DuplexPanelBlock(Properties properties) {
        // No single style: each cell's comes from its state (panelType(BlockState)).
        super(properties.pushReaction(PushReaction.BLOCK), null);
        registerDefaultState(defaultBlockState().setValue(FACING, Direction.NORTH).setValue(CELL, DuplexCell.BENCH));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(CELL);
    }

    @Override
    public PanelType panelType(BlockState state) {
        return state.getValue(CELL).panelType();
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return panelType(state) == null ? null : new PanelBlockEntity(pos, state);
    }

    private static VoxelShape shapeOf(BlockState state) {
        PanelType type = state.getValue(CELL).panelType();
        // The body and the top are the same 0.8 deep slab as the board.
        return PanelGeometry.shape(type == null ? PanelType.DUPLEX_BOARD : type, state.getValue(FACING));
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return shapeOf(state);
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return shapeOf(state);
    }

    // ---- the column

    /** Whether {@code state} is cell {@code cell} of a column facing {@code facing}. */
    private boolean isCell(BlockState state, Direction facing, DuplexCell cell) {
        return state.is(this) && state.getValue(FACING) == facing && state.getValue(CELL) == cell;
    }

    /** The bench position of the column the block at {@code pos} belongs to. */
    public static BlockPos origin(BlockPos pos, BlockState state) {
        return state.getValue(CELL).origin(pos, state.getValue(FACING));
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction facing = context.getHorizontalDirection().getOpposite();
        Level level = context.getLevel();
        BlockPos origin = context.getClickedPos();
        BlockState bench = defaultBlockState().setValue(FACING, facing).setValue(CELL, DuplexCell.BENCH);
        CollisionContext collision = context.getPlayer() == null ? CollisionContext.empty() : CollisionContext.of(context.getPlayer());
        for (DuplexCell cell : DuplexCell.values()) {
            BlockPos at = cell.at(origin, facing);
            if (level.isOutsideBuildHeight(at) || !level.getWorldBorder().isWithinBounds(at)) {
                return null;
            }
            if (cell != DuplexCell.BENCH && (!AssemblyAccess.permitted(level, context.getPlayer(), at)
                    || !level.getBlockState(at).canBeReplaced(context)
                    || !level.isUnobstructed(bench.setValue(CELL, cell), at, collision))) {
                return null;
            }
        }
        // A column that would grow a surface past the configured group size is not placed. The blocks of one surface are
        // measured together (the two board blocks are one group), not one at a time.
        java.util.Map<PanelType, java.util.List<BlockPos>> surfaces = new java.util.EnumMap<>(PanelType.class);
        for (DuplexCell cell : DuplexCell.values()) {
            if (cell.panelType() != null) {
                surfaces.computeIfAbsent(cell.panelType(), type -> new java.util.ArrayList<>()).add(cell.at(origin, facing));
            }
        }
        for (java.util.Map.Entry<PanelType, java.util.List<BlockPos>> surface : surfaces.entrySet()) {
            if (!PanelGroup.placementAllowed(level, surface.getValue(), surface.getKey(), facing)) {
                return null;
            }
        }
        return bench;
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (state.getValue(CELL) != DuplexCell.BENCH) {
            return;
        }
        Direction facing = state.getValue(FACING);
        for (DuplexCell cell : DuplexCell.values()) {
            if (cell != DuplexCell.BENCH) {
                level.setBlock(cell.at(pos, facing), state.setValue(CELL, cell), Block.UPDATE_ALL);
            }
        }
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState neighbour, LevelAccessor level,
                                     BlockPos pos, BlockPos neighbourPos) {
        Direction facing = state.getValue(FACING);
        DuplexCell mine = state.getValue(CELL);
        BlockPos origin = mine.origin(pos, facing);
        for (DuplexCell cell : DuplexCell.values()) {
            if (cell != mine && cell.at(origin, facing).equals(neighbourPos) && !isCell(neighbour, facing, cell)) {
                return Blocks.AIR.defaultBlockState(); // a block of the column went: the column falls apart
            }
        }
        return super.updateShape(state, direction, neighbour, level, pos, neighbourPos);
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide()) {
            Direction facing = state.getValue(FACING);
            DuplexCell mine = state.getValue(CELL);
            BlockPos origin = mine.origin(pos, facing);
            for (DuplexCell cell : DuplexCell.values()) {
                BlockPos other = cell.at(origin, facing);
                BlockState there = level.getBlockState(other);
                if (cell == mine || !isCell(there, facing, cell)) {
                    continue;
                }
                if (cell == DuplexCell.BENCH && !player.isCreative()) {
                    // The one item of the column: the bench's loot, whichever block the player broke.
                    Block.dropResources(there, level, other, level.getBlockEntity(other), player, player.getMainHandItem());
                }
                // Known shape: no shape updates, so the rest of the column is not also torn down (and dropped) by them.
                // Removing a panel block still releases its components (PanelBlock.onRemove).
                level.setBlock(other, Blocks.AIR.defaultBlockState(), Block.UPDATE_NEIGHBORS | Block.UPDATE_CLIENTS
                        | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS);
                level.levelEvent(2001, other, Block.getId(there));
            }
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    // ---- the body cells take no tools

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
                                              InteractionHand hand, BlockHitResult hit) {
        return panelType(state) == null ? ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION
                : super.useItemOn(stack, state, level, pos, player, hand, hit);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        return panelType(state) == null ? InteractionResult.PASS : super.useWithoutItem(state, level, pos, player, hit);
    }
}
