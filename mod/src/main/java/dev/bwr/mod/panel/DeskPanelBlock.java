package dev.bwr.mod.panel;

import com.mojang.serialization.MapCodec;
import dev.bwr.mod.world.AssemblyAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * The 2 m control desk (the owner's "Control Desk" Fusion model): two blocks side by side, each a {@link PanelType#DESK}
 * panel block with the panel block entity, so the two tops are one mounting face 32 cells wide (neighbouring blocks of
 * the same style join into one group whether they were placed as one desk or as two 1 m desks). The 1 m desk is a
 * plain {@link PanelBlock}.
 *
 * <p>Placing and breaking follow the duplex board ({@link DuplexPanelBlock}) and the room furniture: the desk is placed
 * whole or not at all, from the right half, the second block going to the operator's left; breaking either half removes
 * the other and drops one item (the right half's loot, whichever was broken); every panel block that goes drops its
 * mounted components as a panel block always does. A desk that loses a half some other way (an explosion, a command)
 * falls apart through shape updates. No ticker, no saved data of its own: old saves are unaffected.
 */
public final class DeskPanelBlock extends PanelBlock {
    public static final EnumProperty<DeskHalf> HALF = EnumProperty.create("half", DeskHalf.class);
    static final MapCodec<DeskPanelBlock> CODEC = simpleCodec(DeskPanelBlock::new);

    public DeskPanelBlock(Properties properties) {
        super(properties.pushReaction(PushReaction.BLOCK), PanelType.DESK);
        registerDefaultState(defaultBlockState().setValue(FACING, Direction.NORTH).setValue(HALF, DeskHalf.RIGHT));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(HALF);
    }

    /** The origin (right half) position of the desk the block at {@code pos} belongs to. */
    public static BlockPos origin(BlockPos pos, BlockState state) {
        return state.getValue(HALF).origin(pos, state.getValue(FACING));
    }

    private boolean isHalf(BlockState state, Direction facing, DeskHalf half) {
        return state.is(this) && state.getValue(FACING) == facing && state.getValue(HALF) == half;
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction facing = context.getHorizontalDirection().getOpposite();
        Level level = context.getLevel();
        BlockPos origin = context.getClickedPos();
        BlockState right = defaultBlockState().setValue(FACING, facing).setValue(HALF, DeskHalf.RIGHT);
        CollisionContext collision = context.getPlayer() == null ? CollisionContext.empty() : CollisionContext.of(context.getPlayer());
        for (DeskHalf half : DeskHalf.values()) {
            BlockPos at = half.at(origin, facing);
            if (level.isOutsideBuildHeight(at) || !level.getWorldBorder().isWithinBounds(at)) {
                return null;
            }
            if (half != DeskHalf.RIGHT && (!AssemblyAccess.permitted(level, context.getPlayer(), at)
                    || !level.getBlockState(at).canBeReplaced(context)
                    || !level.isUnobstructed(right.setValue(HALF, half), at, collision))) {
                return null;
            }
        }
        // A desk that would grow a run past the configured group size is not placed. Both halves are measured together:
        // each alone could fit while the pair makes the run one block too long.
        if (!PanelGroup.placementAllowed(level, java.util.List.of(origin, DeskHalf.LEFT.at(origin, facing)), PanelType.DESK, facing)) {
            return null;
        }
        return right;
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (state.getValue(HALF) == DeskHalf.RIGHT) {
            level.setBlock(DeskHalf.LEFT.at(pos, state.getValue(FACING)), state.setValue(HALF, DeskHalf.LEFT), Block.UPDATE_ALL);
        }
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState neighbour, LevelAccessor level,
                                     BlockPos pos, BlockPos neighbourPos) {
        Direction facing = state.getValue(FACING);
        DeskHalf mine = state.getValue(HALF);
        DeskHalf other = mine == DeskHalf.RIGHT ? DeskHalf.LEFT : DeskHalf.RIGHT;
        if (other.at(mine.origin(pos, facing), facing).equals(neighbourPos) && !isHalf(neighbour, facing, other)) {
            return Blocks.AIR.defaultBlockState(); // the other half went: the desk falls apart
        }
        return super.updateShape(state, direction, neighbour, level, pos, neighbourPos);
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide()) {
            Direction facing = state.getValue(FACING);
            DeskHalf mine = state.getValue(HALF);
            DeskHalf other = mine == DeskHalf.RIGHT ? DeskHalf.LEFT : DeskHalf.RIGHT;
            BlockPos otherPos = other.at(mine.origin(pos, facing), facing);
            BlockState there = level.getBlockState(otherPos);
            if (isHalf(there, facing, other)) {
                if (other == DeskHalf.RIGHT && !player.isCreative()) {
                    // The one item of the desk: the right half's loot, whichever block the player broke.
                    Block.dropResources(there, level, otherPos, level.getBlockEntity(otherPos), player, player.getMainHandItem());
                }
                // Known shape: no shape updates, so the rest of the desk is not also torn down (and dropped) by them.
                // Removing a panel block still releases its components (PanelBlock.onRemove).
                level.setBlock(otherPos, Blocks.AIR.defaultBlockState(), Block.UPDATE_NEIGHBORS | Block.UPDATE_CLIENTS
                        | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS);
                level.levelEvent(2001, otherPos, Block.getId(there));
            }
        }
        return super.playerWillDestroy(level, pos, state, player);
    }
}
