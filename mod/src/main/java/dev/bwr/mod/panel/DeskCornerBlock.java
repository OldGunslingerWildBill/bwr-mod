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
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.List;

/**
 * A 45 degree desk corner (CONTROL-PANELS-SPEC section 14) of length 1 to {@link DeskCornerGeometry#MAX_LENGTH}: a
 * {@code (length + 1) x (length + 1)} footprint of which the blocks that hold console are placed whole or not at all, one
 * item, whose console turns through two 45 degree bends with {@code length} angled units between them. The block at the
 * middle of each unit's level top (its {@link DeskCornerGeometry#core core}) is a panel block with the panel block entity
 * and a mounting face; the other blocks are body only, like the body cells of the duplex board ({@link DuplexPanelBlock}):
 * no face, no group, no block entity, and a click on any block of the corner goes to the face the view ray lands on
 * ({@link #faceEntity}), so a whole face can be aimed at whichever block the crosshair is on.
 *
 * <p>Placing and breaking follow the 2 m desk ({@link DeskPanelBlock}): the placed block is the origin (where the straight
 * run arrives), the others lie to the operator's left and deep ({@code left} and {@code deep} block properties, see
 * {@link DeskCornerGeometry.Part}); breaking any block removes the others and drops one item (the origin's loot); every
 * component is released when its core goes. A corner that loses a block some other way falls apart through shape
 * updates. No ticker; nothing saved beyond the cores' components.
 */
public final class DeskCornerBlock extends PanelBlock {
    public static final IntegerProperty LEFT = IntegerProperty.create("left", 0, 4);
    public static final IntegerProperty DEEP = IntegerProperty.create("deep", 0, 4);
    private static final List<MapCodec<DeskCornerBlock>> CODECS = new ArrayList<>();

    static {
        // the property ranges are literals (tools-audit-assets.py reads them): a footprint is at most MAX_LENGTH + 1 = 5 blocks across
        if (DeskCornerGeometry.MAX_LENGTH != 4) {
            throw new IllegalStateException("the left and deep properties cover 0..4");
        }
    }

    static {
        for (int i = 0; i < 2 * DeskCornerGeometry.MAX_LENGTH; i++) {
            boolean inside = i % 2 == 0;
            int length = i / 2 + 1;
            CODECS.add(simpleCodec(p -> new DeskCornerBlock(p, inside, length)));
        }
    }

    private final boolean inside;
    private final int length;

    public DeskCornerBlock(Properties properties, boolean inside, int length) {
        super(properties.pushReaction(PushReaction.BLOCK), inside ? PanelType.DESK_CORNER_INSIDE : PanelType.DESK_CORNER_OUTSIDE);
        this.inside = inside;
        this.length = length;
        // the default state is the core of the first face: the one with a block entity (the computer face and other
        // registries take their prototype from it)
        DeskCornerGeometry.Part core = DeskCornerGeometry.core(inside, length, 0);
        registerDefaultState(defaultBlockState().setValue(FACING, Direction.NORTH).setValue(LEFT, core.left()).setValue(DEEP, core.deep()));
    }

    public boolean inside() {
        return inside;
    }

    public int length() {
        return length;
    }

    public PanelType cornerType() {
        return inside ? PanelType.DESK_CORNER_INSIDE : PanelType.DESK_CORNER_OUTSIDE;
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODECS.get((length - 1) * 2 + (inside ? 0 : 1));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(LEFT, DEEP);
    }

    /** Which face (0 is leftmost) the block in this state holds the middle of, or -1 for a body block. */
    public int faceOf(BlockState state) {
        return DeskCornerGeometry.faceOf(inside, length, state.getValue(LEFT), state.getValue(DEEP));
    }

    /** Whether the block in this state is the core of a face. */
    public boolean isCore(BlockState state) {
        return faceOf(state) >= 0;
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
        return DeskCornerGeometry.shape(inside, length, state.getValue(LEFT), state.getValue(DEEP), state.getValue(FACING));
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return getShape(state, level, pos, context);
    }

    // ---- the corner

    private static Direction deepDirection(Direction facing, boolean inside) {
        return inside ? facing : facing.getOpposite();
    }

    /** Position of the block {@code left} to the operator's left and {@code deep} deep of an origin. */
    public static BlockPos partPos(BlockPos origin, Direction facing, boolean inside, int left, int deep) {
        return origin.relative(facing.getClockWise(), left).relative(deepDirection(facing, inside), deep);
    }

    /** The origin position of the corner the block at {@code pos} belongs to. */
    public static BlockPos origin(BlockPos pos, BlockState state) {
        DeskCornerBlock block = (DeskCornerBlock) state.getBlock();
        Direction facing = state.getValue(FACING);
        return pos.relative(facing.getCounterClockWise(), state.getValue(LEFT))
                .relative(deepDirection(facing, block.inside).getOpposite(), state.getValue(DEEP));
    }

    /** Position of the core block of face {@code face} of the corner the block at {@code pos} belongs to. */
    public static BlockPos corePos(BlockPos pos, BlockState state, int face) {
        DeskCornerBlock block = (DeskCornerBlock) state.getBlock();
        DeskCornerGeometry.Part core = DeskCornerGeometry.core(block.inside, block.length, face);
        return partPos(origin(pos, state), state.getValue(FACING), block.inside, core.left(), core.deep());
    }

    /** The positions of every block of the corner the block at {@code pos} belongs to. */
    public static List<BlockPos> piece(BlockPos pos, BlockState state) {
        DeskCornerBlock block = (DeskCornerBlock) state.getBlock();
        BlockPos origin = origin(pos, state);
        List<BlockPos> out = new ArrayList<>();
        for (DeskCornerGeometry.Part part : DeskCornerGeometry.parts(block.inside, block.length)) {
            out.add(partPos(origin, state.getValue(FACING), block.inside, part.left(), part.deep()));
        }
        return out;
    }

    /**
     * The panel block entity a click on the block at {@code pos} is meant for. For a block of a desk corner it is the core
     * of the face the view ray lands on (any block of the corner may be the one under the crosshair, and the faces reach
     * into several); with no ray ({@code eye} null) the first face's. For any other block, its own. Null when there is none.
     * The client aiming, the server's vanilla paths and the computer face go through here.
     */
    public static PanelBlockEntity faceEntity(BlockGetter level, BlockPos pos, Vec3 eye, Vec3 look) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof DeskCornerBlock block)) {
            return level.getBlockEntity(pos) instanceof PanelBlockEntity be ? be : null;
        }
        PanelBlockEntity first = null;
        double[] cell = new double[2];
        for (int face = 0; face < block.length; face++) {
            BlockPos core = corePos(pos, state, face);
            if (!(level.getBlockEntity(core) instanceof PanelBlockEntity be)) {
                continue;
            }
            if (first == null) {
                first = be;
            }
            if (eye != null && PanelGeometry.rayToCell(PanelGeometry.frame(be), core.getX(), core.getY(), core.getZ(), eye.x, eye.y, eye.z,
                    look.x, look.y, look.z, cell) && cell[0] >= -0.5 && cell[0] <= PanelGeometry.GRID + 0.5
                    && cell[1] >= -0.5 && cell[1] <= PanelGeometry.rows(block.cornerType()) + 0.5) {
                return be;
            }
        }
        return first;
    }

    public static PanelBlockEntity faceEntity(BlockGetter level, BlockPos pos) {
        return faceEntity(level, pos, null, null);
    }

    private boolean isPart(BlockState state, Direction facing, int left, int deep) {
        return state.is(this) && state.getValue(FACING) == facing && state.getValue(LEFT) == left && state.getValue(DEEP) == deep;
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction facing = context.getHorizontalDirection().getOpposite();
        Level level = context.getLevel();
        BlockPos origin = context.getClickedPos();
        BlockState first = defaultBlockState().setValue(FACING, facing).setValue(LEFT, 0).setValue(DEEP, 0);
        CollisionContext collision = context.getPlayer() == null ? CollisionContext.empty() : CollisionContext.of(context.getPlayer());
        for (DeskCornerGeometry.Part part : DeskCornerGeometry.parts(inside, length)) {
            BlockPos at = partPos(origin, facing, inside, part.left(), part.deep());
            if (level.isOutsideBuildHeight(at) || !level.getWorldBorder().isWithinBounds(at)) {
                return null;
            }
            if ((part.left() != 0 || part.deep() != 0) && (!AssemblyAccess.permitted(level, context.getPlayer(), at)
                    || !level.getBlockState(at).canBeReplaced(context)
                    || !level.isUnobstructed(first.setValue(LEFT, part.left()).setValue(DEEP, part.deep()), at, collision))) {
                return null;
            }
        }
        // each face is its own group of one block; the cap cannot be exceeded, but ask as every panel does
        DeskCornerGeometry.Part core = DeskCornerGeometry.core(inside, length, 0);
        return PanelGroup.placementAllowed(level, partPos(origin, facing, inside, core.left(), core.deep()).immutable(),
                cornerType(), facing) ? first : null;
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (state.getValue(LEFT) == 0 && state.getValue(DEEP) == 0) {
            Direction facing = state.getValue(FACING);
            for (DeskCornerGeometry.Part part : DeskCornerGeometry.parts(inside, length)) {
                if (part.left() != 0 || part.deep() != 0) {
                    level.setBlock(partPos(pos, facing, inside, part.left(), part.deep()),
                            state.setValue(LEFT, part.left()).setValue(DEEP, part.deep()), Block.UPDATE_ALL);
                }
            }
        }
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState neighbour, LevelAccessor level,
                                     BlockPos pos, BlockPos neighbourPos) {
        Direction facing = state.getValue(FACING);
        BlockPos origin = origin(pos, state);
        for (DeskCornerGeometry.Part part : DeskCornerGeometry.parts(inside, length)) {
            if (partPos(origin, facing, inside, part.left(), part.deep()).equals(neighbourPos)
                    && !isPart(neighbour, facing, part.left(), part.deep())) {
                return Blocks.AIR.defaultBlockState(); // a block of the corner went: the corner falls apart
            }
        }
        return super.updateShape(state, direction, neighbour, level, pos, neighbourPos);
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide()) {
            Direction facing = state.getValue(FACING);
            BlockPos origin = origin(pos, state);
            List<BlockPos> others = new ArrayList<>();
            for (DeskCornerGeometry.Part part : DeskCornerGeometry.parts(inside, length)) {
                BlockPos other = partPos(origin, facing, inside, part.left(), part.deep());
                BlockState there = level.getBlockState(other);
                if (other.equals(pos) || !isPart(there, facing, part.left(), part.deep())) {
                    continue;
                }
                if (part.left() == 0 && part.deep() == 0 && !player.isCreative()) {
                    // The one item of the corner: the origin's loot, whichever block the player broke.
                    Block.dropResources(there, level, other, level.getBlockEntity(other), player, player.getMainHandItem());
                }
                others.add(other);
            }
            for (BlockPos other : others) {
                BlockState there = level.getBlockState(other);
                // Known shape: no shape updates, so the rest of the corner is not also torn down (and dropped) by them.
                // Removing a core releases its components (PanelBlock.onRemove).
                level.setBlock(other, Blocks.AIR.defaultBlockState(), Block.UPDATE_NEIGHBORS | Block.UPDATE_CLIENTS
                        | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS);
                level.levelEvent(2001, other, Block.getId(there));
            }
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    // ---- a click on any block is a click on the face under the view ray

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
                                              InteractionHand hand, BlockHitResult hit) {
        PanelBlockEntity face = faceEntity(level, pos, player.getEyePosition(), player.getLookAngle());
        if (face == null) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        return super.useItemOn(stack, face.getBlockState(), level, face.getBlockPos(), player, hand, hit);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        PanelBlockEntity face = faceEntity(level, pos, player.getEyePosition(), player.getLookAngle());
        if (face == null) {
            return InteractionResult.PASS;
        }
        return super.useWithoutItem(face.getBlockState(), level, face.getBlockPos(), player, hit);
    }

    /** Components hold the cells of a core only, so a body block has no cells of its own. */
    @Override
    public boolean cellUsable(BlockState state, int cx, int cy) {
        return isCore(state) && super.cellUsable(state, cx, cy);
    }
}
