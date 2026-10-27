package dev.bwr.mod.isotope;

import com.mojang.serialization.MapCodec;
import dev.bwr.mod.eccs.TurbineAssemblyBlock;
import dev.bwr.mod.registry.BwrBlockEntities;
import dev.bwr.mod.text.BwrText;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.List;

/**
 * The Large RTG: one item places one 2 x 2 x 3 assembly (2 wide, 3 high, 2 deep) with one controller, as the mod's other
 * multiblocks do (PUMP-MODELS.md). The clicked block is the controller cell, cell 0, the corner of the footprint; the
 * assembly extends east and south of it in the north-facing frame and is turned to the player's facing. Every cell is a
 * full cube of this block with its own {@link #CELL} index; only the controller has a block entity, and that block entity
 * has the assembly's one ticker. Breaking any cell removes the whole assembly and returns the one item, and the
 * contents drop with it. Pistons cannot move it.
 *
 * <p>Why 2 x 2 x 3 and not 2 x 2 x 2: the housing is 40 cm of lead-equivalent wall (see {@link LargeRtgBlockEntity}), which
 * leaves a core cavity about 1.2 m across, and the radiator stack that sheds up to 920 kW of strontium heat (and many times
 * that for cobalt) wants a block of its own on top of a two-block shielded body. Two high left no room for both.
 */
public class LargeRtgBlock extends BaseEntityBlock {

    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final int WIDTH = 2;
    public static final int HEIGHT = 3;
    public static final int DEPTH = 2;
    public static final int CELLS = WIDTH * HEIGHT * DEPTH;
    /** Literal bounds: tools-audit-assets.py reads them from the source. {@link #CELLS} must be one more. */
    public static final IntegerProperty CELL = IntegerProperty.create("cell", 0, 11);
    /** The controller cell: the one with the block entity, the corner the player clicked. */
    public static final int CONTROLLER = 0;

    private static final ThreadLocal<Boolean> REMOVING = ThreadLocal.withInitial(() -> false);

    public static final MapCodec<LargeRtgBlock> CODEC = simpleCodec(LargeRtgBlock::new);

    public LargeRtgBlock(Properties properties) {
        super(properties.pushReaction(PushReaction.BLOCK));
        if (CELLS != CELL.getPossibleValues().size()) {
            throw new IllegalStateException("The Large RTG cell property does not match its footprint");
        }
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(CELL, CONTROLLER));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, CELL);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return Shapes.block();
    }

    // ---- the layout ----------------------------------------------------------------

    /** Offset of {@code cell} from the controller in the north-facing frame: x east, y up, z south. */
    public static BlockPos offset(int cell) {
        return new BlockPos(cell % WIDTH, cell / (WIDTH * DEPTH), (cell / WIDTH) % DEPTH);
    }

    /** World position of {@code cell} of the assembly whose controller is at {@code root}. */
    public static BlockPos cellPos(BlockPos root, Direction facing, int cell) {
        return root.offset(TurbineAssemblyBlock.turn(offset(cell), facing));
    }

    /** The controller position of the assembly {@code pos} belongs to. */
    public static BlockPos rootOf(BlockPos pos, BlockState state) {
        return pos.subtract(TurbineAssemblyBlock.turn(offset(state.getValue(CELL)), state.getValue(FACING)));
    }

    public static boolean isController(BlockState state) {
        return state.getValue(CELL) == CONTROLLER;
    }

    private static boolean owned(BlockState actual, BlockState s, int cell) {
        return actual.getBlock() instanceof LargeRtgBlock && actual.getValue(CELL) == cell && actual.getValue(FACING) == s.getValue(FACING);
    }

    /** Whether every cell is loaded and belongs to the assembly {@code root}/{@code s} describes. */
    public static boolean complete(Level level, BlockPos root, BlockState s) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int i = 0; i < CELLS; i++) {
            BlockPos p = cellPos(root, s.getValue(FACING), i);
            cursor.set(p);
            if (level.isOutsideBuildHeight(cursor) || !level.isLoaded(cursor) || !owned(level.getBlockState(cursor), s, i)) {
                return false;
            }
        }
        return true;
    }

    /** True when every cell's chunk is loaded (a footprint is in one to four chunks). */
    public static boolean allLoaded(Level level, BlockPos root, Direction facing) {
        for (int i = 0; i < CELLS; i++) {
            if (!level.isLoaded(cellPos(root, facing, i))) {
                return false;
            }
        }
        return true;
    }

    /** The controller's block entity of the assembly {@code pos} belongs to, or null (unloaded, incomplete or removed). */
    public static LargeRtgBlockEntity controller(Level level, BlockPos pos, BlockState s) {
        BlockPos root = rootOf(pos, s);
        if (!level.isLoaded(root) || !owned(level.getBlockState(root), s, CONTROLLER)) {
            return null;
        }
        return level.getBlockEntity(root) instanceof LargeRtgBlockEntity be && !be.isRemoved() ? be : null;
    }

    // ---- block entity: the controller cell only --------------------------------------

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return isController(state) ? new LargeRtgBlockEntity(pos, state) : null;
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide() || !isController(state)) {
            return null;
        }
        return dev.bwr.mod.TickProbe.wrap(createTickerHelper(type, BwrBlockEntities.LARGE_RTG.get(), LargeRtgBlockEntity::serverTick));
    }

    // ---- placement and removal -----------------------------------------------------------

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        BlockState s = defaultBlockState().setValue(FACING, ctx.getHorizontalDirection().getOpposite()).setValue(CELL, CONTROLLER);
        Level level = ctx.getLevel();
        for (int i = 0; i < CELLS; i++) {
            BlockPos p = cellPos(ctx.getClickedPos(), s.getValue(FACING), i);
            if (!dev.bwr.mod.world.AssemblyAccess.permitted(level, ctx.getPlayer(), p) || !level.isLoaded(p) || level.isOutsideBuildHeight(p)
                    || !level.getWorldBorder().isWithinBounds(p) || !level.getBlockState(p).canBeReplaced()
                    || !level.isUnobstructed(s.setValue(CELL, i), p, CollisionContext.empty())) {
                return null;
            }
        }
        return s;
    }

    @Override
    public void setPlacedBy(Level level, BlockPos root, BlockState s, LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, root, s, placer, stack);
        if (level.isClientSide() || !isController(s)) {
            return;
        }
        for (int i = 1; i < CELLS; i++) {
            BlockPos p = cellPos(root, s.getValue(FACING), i);
            if (!dev.bwr.mod.world.AssemblyAccess.permitted(level, placer, p) || !level.isLoaded(p) || !level.getBlockState(p).canBeReplaced()) {
                dev.bwr.mod.world.AssemblyAccess.refusePlacement(level, root, placer, stack);
                return;
            }
        }
        for (int i = 1; i < CELLS; i++) {
            if (!level.setBlock(cellPos(root, s.getValue(FACING), i), s.setValue(CELL, i), 3)) {
                dev.bwr.mod.world.AssemblyAccess.refusePlacement(level, root, placer, stack);
                return;
            }
        }
        for (int i = 0; i < CELLS; i++) {
            level.invalidateCapabilities(cellPos(root, s.getValue(FACING), i));
        }
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState s, Player player) {
        // A part cell hands out the controller's one drop, so breaking any cell returns the item.
        if (!level.isClientSide() && !isController(s) && !player.isCreative() && player.hasCorrectToolForDrops(s)) {
            BlockPos root = rootOf(pos, s);
            if (level.isLoaded(root) && owned(level.getBlockState(root), s, CONTROLLER)) {
                Block.dropResources(level.getBlockState(root), level, root, level.getBlockEntity(root), player, player.getMainHandItem());
            }
        }
        return super.playerWillDestroy(level, pos, s, player);
    }

    @Override
    protected void onRemove(BlockState s, Level level, BlockPos pos, BlockState replacement, boolean moving) {
        // The controller's contents drop where the controller stood, whichever cell was broken.
        if (!level.isClientSide() && !replacement.is(this) && !REMOVING.get()) {
            BlockPos root = rootOf(pos, s);
            if (level.isLoaded(root) && level.getBlockEntity(root) instanceof LargeRtgBlockEntity be) {
                be.dropContents();
            }
        }
        super.onRemove(s, level, pos, replacement, moving);
        // Part cells have no block entity, so NeoForge cannot invalidate their forwarded capabilities for us.
        if (!level.isClientSide() && !s.equals(replacement)) {
            level.invalidateCapabilities(pos);
        }
        if (replacement.is(this) || level.isClientSide() || REMOVING.get()) {
            return;
        }
        REMOVING.set(true);
        try {
            BlockPos root = rootOf(pos, s);
            for (int i = 0; i < CELLS; i++) {
                BlockPos p = cellPos(root, s.getValue(FACING), i);
                if (!p.equals(pos) && level.isLoaded(p) && owned(level.getBlockState(p), s, i)) {
                    level.removeBlock(p, false);
                }
            }
        } finally {
            REMOVING.set(false);
        }
    }

    /** A part cell whose controller is gone (for example after /setblock) removes itself; nothing else changes. */
    @Override
    protected void neighborChanged(BlockState s, Level level, BlockPos pos, Block block, BlockPos changed, boolean moving) {
        super.neighborChanged(s, level, pos, block, changed, moving);
        if (level.isClientSide() || isController(s) || REMOVING.get()) {
            return;
        }
        BlockPos root = rootOf(pos, s);
        if (level.isLoaded(root) && !owned(level.getBlockState(root), s, CONTROLLER)) {
            level.removeBlock(pos, false);
        }
    }

    @Override
    protected BlockState rotate(BlockState s, Rotation r) {
        return s.setValue(FACING, r.rotate(s.getValue(FACING)));
    }

    // ---- use -------------------------------------------------------------------------------

    /** Right-click with isotope material in the hand loads it (the loading hatch): as much as fits, up to the 1,000 kg cap. */
    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState s, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (!RtgFuels.isFuel(stack)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (level.isClientSide()) {
            return ItemInteractionResult.SUCCESS;
        }
        LargeRtgBlockEntity be = controller(level, pos, s);
        if (be != null) {
            int taken = be.loadFrom(stack.getCount(), stack);
            if (taken > 0 && !player.isCreative()) {
                stack.shrink(taken);
            }
            if (taken == 0) {
                player.displayClientMessage(Component.translatable("status.bwr.large_rtg.refused"), true);
            }
        }
        return ItemInteractionResult.CONSUME;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState s, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        LargeRtgBlockEntity be = controller(level, pos, s);
        if (be != null) {
            if (player.isShiftKeyDown() || !(player instanceof ServerPlayer sp)) {
                for (Component line : be.statusComponents()) {
                    player.displayClientMessage(line, false);
                }
            } else {
                dev.bwr.mod.gui.LargeRtgMenu.open(sp, be);
            }
        }
        return InteractionResult.CONSUME;
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> lines, TooltipFlag flag) {
        lines.add(BwrText.tr("status.bwr.large_rtg_item.size", "2 wide x 3 high x 2 deep. Leave it all clear; the clicked block is the corner."));
        lines.add(BwrText.tr("status.bwr.large_rtg_item.capacity", "Holds up to 1,000 kg of isotope ingots and blocks, mixed. Load by hand, hopper or pipe."));
        lines.add(BwrText.tr("status.bwr.large_rtg_item.heavy", "40 cm of lead-equivalent housing. Read the dose on its screen."));
        super.appendHoverText(stack, context, lines, flag);
    }
}
