package dev.bwr.mod.furniture;

import dev.bwr.mod.panel.PanelFinish;
import dev.bwr.mod.world.AssemblyAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Base of the control-room furniture: decorative blocks with a facing, which may fill several blocks (a 2 high cabinet,
 * a 2 wide desk, a 3 x 2 display). One item places the whole piece or nothing at all; breaking any block removes the
 * whole piece and drops one item, from the origin block's loot table. There are no block entities and nothing mounts on
 * these blocks; the panel components keep to the panel bodies.
 *
 * <p>Subclasses describe the footprint: cell 0 is the origin (the block the player clicked), the other cells are placed
 * around it relative to the facing, and each cell is a state of the same block that says which cell it is.
 */
public abstract class AssemblyBlock extends Block {
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

    protected AssemblyBlock(Properties properties) {
        super(properties.noOcclusion().pushReaction(PushReaction.BLOCK));
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
        builder.add(PanelFinish.FINISH);
    }

    // ---- footprint (single block unless a subclass says otherwise)

    /** How many blocks one item fills. */
    public int cellCount() {
        return 1;
    }

    /** Position of cell {@code index} relative to the origin cell, for a facing. */
    protected Vec3i cellOffset(int index, Direction facing) {
        return Vec3i.ZERO;
    }

    /** Which cell of its piece this state is. */
    protected int cellIndex(BlockState state) {
        return 0;
    }

    /** The state of cell {@code index} when the origin cell has the given state. */
    protected BlockState withCell(BlockState origin, int index) {
        return origin;
    }

    /** Collision and selection shape of one block. */
    protected abstract VoxelShape shapeOf(BlockState state);

    /** The origin cell's position of the piece that the block at pos belongs to. */
    public BlockPos origin(BlockPos pos, BlockState state) {
        return pos.subtract(cellOffset(cellIndex(state), state.getValue(FACING)));
    }

    /** Position of cell {@code index} of the piece whose origin is at {@code origin}. */
    public BlockPos cellPos(BlockPos origin, Direction facing, int index) {
        return origin.offset(cellOffset(index, facing));
    }

    private boolean isCell(BlockState state, Direction facing, int index) {
        return state.is(this) && state.getValue(FACING) == facing && cellIndex(state) == index;
    }

    // ---- placement

    /** Refinement hook for subclasses: the state of the origin cell, with the facing already set. */
    protected BlockState placementState(BlockState state, BlockPlaceContext context) {
        return state;
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction facing = context.getHorizontalDirection().getOpposite();
        Level level = context.getLevel();
        BlockPos origin = context.getClickedPos();
        for (int i = 0; i < cellCount(); i++) {
            BlockPos at = cellPos(origin, facing, i);
            if (level.isOutsideBuildHeight(at) || !level.getWorldBorder().isWithinBounds(at)) {
                return null;
            }
            if (i > 0 && (!AssemblyAccess.permitted(level, context.getPlayer(), at)
                    || !level.getBlockState(at).canBeReplaced(context))) {
                return null;
            }
        }
        return placementState(defaultBlockState().setValue(FACING, facing), context);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (cellCount() < 2 || cellIndex(state) != 0) {
            return;
        }
        Direction facing = state.getValue(FACING);
        for (int i = 1; i < cellCount(); i++) {
            level.setBlock(cellPos(pos, facing, i), withCell(state, i), Block.UPDATE_ALL);
        }
    }

    // ---- breaking: the whole piece goes, once, with one drop

    @Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState neighbour, LevelAccessor level,
                                     BlockPos pos, BlockPos neighbourPos) {
        if (cellCount() > 1) {
            Direction facing = state.getValue(FACING);
            int mine = cellIndex(state);
            BlockPos origin = origin(pos, state);
            for (int i = 0; i < cellCount(); i++) {
                if (i != mine && cellPos(origin, facing, i).equals(neighbourPos) && !isCell(neighbour, facing, i)) {
                    return Blocks.AIR.defaultBlockState();
                }
            }
        }
        return state;
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide() && cellCount() > 1) {
            Direction facing = state.getValue(FACING);
            int mine = cellIndex(state);
            BlockPos origin = origin(pos, state);
            for (int i = 0; i < cellCount(); i++) {
                BlockPos other = cellPos(origin, facing, i);
                BlockState there = level.getBlockState(other);
                if (i == mine || !isCell(there, facing, i)) {
                    continue;
                }
                if (i == 0 && !player.isCreative()) {
                    // The one item of the piece: the origin cell's loot, whichever block the player broke.
                    Block.dropResources(there, level, other, null, player, player.getMainHandItem());
                }
                level.setBlock(other, Blocks.AIR.defaultBlockState(), Block.UPDATE_NEIGHBORS | Block.UPDATE_CLIENTS
                        | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS);
                level.levelEvent(2001, other, Block.getId(there));
            }
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    // ---- shape, rotation

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return shapeOf(state);
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return shapeOf(state);
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    /** Pick block gives the item with this block's finish. */
    @Override
    public net.minecraft.world.item.ItemStack getCloneItemStack(BlockState state, net.minecraft.world.phys.HitResult target,
                                                                net.minecraft.world.level.LevelReader level,
                                                                BlockPos pos, net.minecraft.world.entity.player.Player player) {
        return PanelFinish.withFinish(super.getCloneItemStack(state, target, level, pos, player), PanelFinish.of(state));
    }
}
