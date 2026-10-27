package dev.bwr.mod.refuelling;

import dev.bwr.mod.text.BwrText;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * One block of the cattle chute: a lined trench, laid in a straight row between the reactor cavity and the spent fuel
 * pool. A fuel assembly is 4.5 m long and stands upright in it, so the trench is built deep: canal blocks stacked on one
 * another (the lowest has the floor, the highest the water surface, the ones between are open channel). Its {@code water}
 * flag says whether the trench holds water; a bucket fills or drains the whole stack it is used on, and a drained chute is a
 * thing a player can do and the survey meter notices when a fuel assembly goes through one. Nothing here ticks or flows:
 * the flag is the water. A canal cut as a slot through a spent fuel pool's wall and joined to a reactor well is different: the
 * pool's controller steps the water through it ({@link CanalExchange}) and sets the flags and the {@link #FILL} of the row to
 * the connected level. See {@link BridgeSite} for how the row is found and {@link CanalGateBlock} for the gates.
 */
public class TransferCanalBlock extends Block {

    public static final EnumProperty<Direction.Axis> AXIS = BlockStateProperties.HORIZONTAL_AXIS;
    public static final BooleanProperty WATER = BooleanProperty.create("water");
    /** No canal block below: this one has the floor. Kept up to date by neighbour updates. */
    public static final BooleanProperty FLOOR = BooleanProperty.create("floor");
    /** No canal block above: this one has the water surface. */
    public static final BooleanProperty TOP = BooleanProperty.create("top");
    /**
     * Where the water surface stands in this block, in sixteenths, when the canal is a slot joined to a pool and a reactor well
     * ({@link CanalExchange} sets it from the connected level): 1 to 16. 0, the default and every old block, is the canal's own
     * water as before: to {@link #SURFACE_IN_BLOCK} in the top block, to the full height in the ones under it.
     */
    public static final IntegerProperty FILL = IntegerProperty.create("fill", 0, 16);

    /** Height of the water surface in the top block, blocks. */
    public static final double SURFACE_IN_BLOCK = 13.0 / 16.0;
    /** Thickness of the floor, blocks. */
    public static final double FLOOR_THICKNESS = 2.0 / 16.0;

    private static final VoxelShape FLOOR_SHAPE = Block.box(0, 0, 0, 16, 2, 16);
    private static final VoxelShape WALLS_X = Shapes.or(Block.box(0, 0, 0, 16, 16, 2), Block.box(0, 0, 14, 16, 16, 16));
    private static final VoxelShape WALLS_Z = Shapes.or(Block.box(0, 0, 0, 2, 16, 16), Block.box(14, 0, 0, 16, 16, 16));

    public TransferCanalBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(AXIS, Direction.Axis.X).setValue(WATER, true)
                .setValue(FLOOR, true).setValue(TOP, true).setValue(FILL, 0));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(AXIS, WATER, FLOOR, TOP, FILL);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        BlockState below = level.getBlockState(pos.below());
        BlockState above = level.getBlockState(pos.above());
        boolean wet = !(below.getBlock() instanceof TransferCanalBlock) || below.getValue(WATER);
        return defaultBlockState().setValue(AXIS, context.getHorizontalDirection().getAxis())
                .setValue(FLOOR, !(below.getBlock() instanceof TransferCanalBlock))
                .setValue(TOP, !(above.getBlock() instanceof TransferCanalBlock))
                .setValue(WATER, wet);
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState neighbour, LevelAccessor level,
                                     BlockPos pos, BlockPos neighbourPos) {
        if (direction == Direction.DOWN) {
            return state.setValue(FLOOR, !(neighbour.getBlock() instanceof TransferCanalBlock));
        }
        if (direction == Direction.UP) {
            return state.setValue(TOP, !(neighbour.getBlock() instanceof TransferCanalBlock));
        }
        return state;
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState old, boolean moved) {
        super.onPlace(state, level, pos, old, moved);
        if (!(old.getBlock() instanceof TransferCanalBlock)) {
            // a canal block can be a slot in a pool's wall: the pools around it look at their structure again
            dev.bwr.mod.spentfuel.SpentFuelPoolRegistry.changed(level, pos);
        }
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState next, boolean moved) {
        if (!(next.getBlock() instanceof TransferCanalBlock)) {
            dev.bwr.mod.spentfuel.SpentFuelPoolRegistry.changed(level, pos);
        }
        super.onRemove(state, level, pos, next, moved);
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return rotation == Rotation.CLOCKWISE_90 || rotation == Rotation.COUNTERCLOCKWISE_90
                ? state.setValue(AXIS, state.getValue(AXIS) == Direction.Axis.X ? Direction.Axis.Z : Direction.Axis.X) : state;
    }

    static VoxelShape trough(BlockState state) {
        VoxelShape walls = state.getValue(AXIS) == Direction.Axis.X ? WALLS_X : WALLS_Z;
        return state.getValue(FLOOR) ? Shapes.or(FLOOR_SHAPE, walls) : walls;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return trough(state);
    }

    /** Sets the water flag of every canal block in the column through {@code pos}. */
    private static void setColumn(Level level, BlockPos pos, boolean wet) {
        BlockPos at = pos;
        while (level.getBlockState(at.above()).getBlock() instanceof TransferCanalBlock) {
            at = at.above();
        }
        for (int n = 0; n < 32 && level.getBlockState(at).getBlock() instanceof TransferCanalBlock; n++) {
            level.setBlock(at, level.getBlockState(at).setValue(WATER, wet).setValue(FILL, 0), 3);
            at = at.below();
        }
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
                                              InteractionHand hand, BlockHitResult hit) {
        boolean wet = state.getValue(WATER);
        if (stack.is(Items.BUCKET) && wet) {
            if (!level.isClientSide()) {
                setColumn(level, pos, false);
                level.playSound(null, pos, SoundEvents.BUCKET_FILL, SoundSource.BLOCKS, 1.0f, 1.0f);
                if (!player.getAbilities().instabuild) {
                    stack.shrink(1);
                    ItemStack full = new ItemStack(Items.WATER_BUCKET);
                    if (!player.getInventory().add(full)) {
                        player.drop(full, false);
                    }
                }
                player.displayClientMessage(BwrText.tr("status.bwr.refuelling_bridge.canal_drained",
                        "The canal is drained here. Nothing shields a fuel assembly in it."), true);
            }
            return ItemInteractionResult.sidedSuccess(level.isClientSide());
        }
        if (stack.is(Items.WATER_BUCKET) && !wet) {
            if (!level.isClientSide()) {
                setColumn(level, pos, true);
                level.playSound(null, pos, SoundEvents.BUCKET_EMPTY, SoundSource.BLOCKS, 1.0f, 1.0f);
                if (!player.getAbilities().instabuild) {
                    stack.shrink(1);
                    ItemStack empty = new ItemStack(Items.BUCKET);
                    if (!player.getInventory().add(empty)) {
                        player.drop(empty, false);
                    }
                }
                player.displayClientMessage(BwrText.tr("status.bwr.refuelling_bridge.canal_filled", "The canal is filled here."), true);
            }
            return ItemInteractionResult.sidedSuccess(level.isClientSide());
        }
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }
}
