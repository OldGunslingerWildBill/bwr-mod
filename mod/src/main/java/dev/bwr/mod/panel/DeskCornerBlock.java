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

import java.util.ArrayList;
import java.util.List;

/**
 * A 45 degree desk corner (CONTROL-PANELS-SPEC section 14): four blocks, placed whole or not at all, one item, whose
 * console turns through two 45 degree bends. The block at the middle of the angled section's level top (the
 * {@link DeskCornerGeometry#core core}) is a panel block with the panel block entity and the mounting face; the other
 * three are body only, like the body cells of the duplex board ({@link DuplexPanelBlock}): no face, no group, no block
 * entity, and a click on one of them goes to the core, so the whole top can be aimed at whichever block the crosshair
 * lands on ({@link #faceEntity}).
 *
 * <p>Placing and breaking follow the 2 m desk ({@link DeskPanelBlock}): the placed block is the origin, the others go
 * toward the operator's left and one block deep ({@link CornerPart}); breaking any block removes the others and drops
 * one item (the origin's loot, whichever was broken); every component is released when the core goes. A corner that
 * loses a block some other way falls apart through shape updates. No ticker; nothing saved beyond the core's components.
 */
public final class DeskCornerBlock extends PanelBlock {
    public static final EnumProperty<CornerPart> PART = EnumProperty.create("part", CornerPart.class);
    private static final MapCodec<DeskCornerBlock> CODEC_INSIDE = simpleCodec(p -> new DeskCornerBlock(p, true));
    private static final MapCodec<DeskCornerBlock> CODEC_OUTSIDE = simpleCodec(p -> new DeskCornerBlock(p, false));

    private final boolean inside;

    public DeskCornerBlock(Properties properties, boolean inside) {
        super(properties.pushReaction(PushReaction.BLOCK), inside ? PanelType.DESK_CORNER_INSIDE : PanelType.DESK_CORNER_OUTSIDE);
        this.inside = inside;
        // the default state is the core block: the one with a block entity (the computer face and other registries take their prototype from it)
        registerDefaultState(defaultBlockState().setValue(FACING, Direction.NORTH).setValue(PART, DeskCornerGeometry.core(inside)));
    }

    public boolean inside() {
        return inside;
    }

    public PanelType cornerType() {
        return inside ? PanelType.DESK_CORNER_INSIDE : PanelType.DESK_CORNER_OUTSIDE;
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return inside ? CODEC_INSIDE : CODEC_OUTSIDE;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(PART);
    }

    /** Whether the block in this state is the one with the face. */
    public boolean isCore(BlockState state) {
        return state.getValue(PART) == DeskCornerGeometry.core(inside);
    }

    @Override
    public PanelType panelType(BlockState state) {
        return isCore(state) ? cornerType() : null; // the body blocks have no face and no group
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return isCore(state) ? new PanelBlockEntity(pos, state) : null;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return DeskCornerGeometry.shape(inside, state.getValue(PART), state.getValue(FACING));
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return getShape(state, level, pos, context);
    }

    // ---- the corner

    /** The origin position of the corner the block at {@code pos} belongs to. */
    public static BlockPos origin(BlockPos pos, BlockState state) {
        DeskCornerBlock block = (DeskCornerBlock) state.getBlock();
        return state.getValue(PART).origin(pos, state.getValue(FACING), block.inside);
    }

    /** Position of the core (face) block of the corner the block at {@code pos} belongs to. */
    public static BlockPos corePos(BlockPos pos, BlockState state) {
        DeskCornerBlock block = (DeskCornerBlock) state.getBlock();
        return DeskCornerGeometry.core(block.inside).at(origin(pos, state), state.getValue(FACING), block.inside);
    }

    /**
     * The panel block entity a click on the block at {@code pos} is meant for: the block's own, or, for a body block of a
     * desk corner, the core's of its corner (the face reaches into every block of the corner). Null when there is none.
     * The client aiming and the server's vanilla paths both go through here.
     */
    public static PanelBlockEntity faceEntity(BlockGetter level, BlockPos pos) {
        if (level.getBlockEntity(pos) instanceof PanelBlockEntity be) {
            return be;
        }
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof DeskCornerBlock) {
            return level.getBlockEntity(corePos(pos, state)) instanceof PanelBlockEntity core ? core : null;
        }
        return null;
    }

    private boolean isPart(BlockState state, Direction facing, CornerPart part) {
        return state.is(this) && state.getValue(FACING) == facing && state.getValue(PART) == part;
    }

    private BlockPos partPos(BlockPos origin, Direction facing, CornerPart part) {
        return part.at(origin, facing, inside);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction facing = context.getHorizontalDirection().getOpposite();
        Level level = context.getLevel();
        BlockPos origin = context.getClickedPos();
        BlockState first = defaultBlockState().setValue(FACING, facing).setValue(PART, CornerPart.ORIGIN);
        CollisionContext collision = context.getPlayer() == null ? CollisionContext.empty() : CollisionContext.of(context.getPlayer());
        for (CornerPart part : CornerPart.values()) {
            BlockPos at = partPos(origin, facing, part);
            if (level.isOutsideBuildHeight(at) || !level.getWorldBorder().isWithinBounds(at)) {
                return null;
            }
            if (part != CornerPart.ORIGIN && (!AssemblyAccess.permitted(level, context.getPlayer(), at)
                    || !level.getBlockState(at).canBeReplaced(context)
                    || !level.isUnobstructed(first.setValue(PART, part), at, collision))) {
                return null;
            }
        }
        // the face is its own group of one block; the cap cannot be exceeded, but ask as every panel does
        return PanelGroup.placementAllowed(level, DeskCornerGeometry.core(inside).at(origin, facing, inside).immutable(),
                cornerType(), facing) ? first : null;
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (state.getValue(PART) == CornerPart.ORIGIN) {
            Direction facing = state.getValue(FACING);
            for (CornerPart part : CornerPart.values()) {
                if (part != CornerPart.ORIGIN) {
                    level.setBlock(partPos(pos, facing, part), state.setValue(PART, part), Block.UPDATE_ALL);
                }
            }
        }
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState neighbour, LevelAccessor level,
                                     BlockPos pos, BlockPos neighbourPos) {
        Direction facing = state.getValue(FACING);
        CornerPart mine = state.getValue(PART);
        BlockPos origin = mine.origin(pos, facing, inside);
        for (CornerPart part : CornerPart.values()) {
            if (part != mine && partPos(origin, facing, part).equals(neighbourPos) && !isPart(neighbour, facing, part)) {
                return Blocks.AIR.defaultBlockState(); // a block of the corner went: the corner falls apart
            }
        }
        return super.updateShape(state, direction, neighbour, level, pos, neighbourPos);
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide()) {
            Direction facing = state.getValue(FACING);
            CornerPart mine = state.getValue(PART);
            BlockPos origin = mine.origin(pos, facing, inside);
            List<BlockPos> others = new ArrayList<>();
            for (CornerPart part : CornerPart.values()) {
                BlockPos other = partPos(origin, facing, part);
                BlockState there = level.getBlockState(other);
                if (part == mine || !isPart(there, facing, part)) {
                    continue;
                }
                if (part == CornerPart.ORIGIN && !player.isCreative()) {
                    // The one item of the corner: the origin's loot, whichever block the player broke.
                    Block.dropResources(there, level, other, level.getBlockEntity(other), player, player.getMainHandItem());
                }
                others.add(other);
            }
            for (BlockPos other : others) {
                BlockState there = level.getBlockState(other);
                // Known shape: no shape updates, so the rest of the corner is not also torn down (and dropped) by them.
                // Removing the core releases its components (PanelBlock.onRemove).
                level.setBlock(other, Blocks.AIR.defaultBlockState(), Block.UPDATE_NEIGHBORS | Block.UPDATE_CLIENTS
                        | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS);
                level.levelEvent(2001, other, Block.getId(there));
            }
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    // ---- a click on a body block is a click on the face

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
                                              InteractionHand hand, BlockHitResult hit) {
        if (!isCore(state)) {
            BlockPos core = corePos(pos, state);
            BlockState coreState = level.getBlockState(core);
            return isCoreOf(coreState, state) ? super.useItemOn(stack, coreState, level, core, player, hand, hit)
                    : ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        return super.useItemOn(stack, state, level, pos, player, hand, hit);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!isCore(state)) {
            BlockPos core = corePos(pos, state);
            BlockState coreState = level.getBlockState(core);
            return isCoreOf(coreState, state) ? super.useWithoutItem(coreState, level, core, player, hit) : InteractionResult.PASS;
        }
        return super.useWithoutItem(state, level, pos, player, hit);
    }

    private boolean isCoreOf(BlockState core, BlockState body) {
        return core.is(this) && core.getValue(FACING) == body.getValue(FACING) && isCore(core);
    }

    /** Components hold the cells of the core only, so a body block has no cells of its own. */
    @Override
    public boolean cellUsable(BlockState state, int cx, int cy) {
        return isCore(state) && super.cellUsable(state, cx, cy);
    }
}
