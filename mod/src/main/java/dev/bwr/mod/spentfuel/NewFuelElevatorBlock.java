package dev.bwr.mod.spentfuel;

import com.mojang.serialization.MapCodec;
import dev.bwr.mod.text.BwrText;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The new fuel elevator ({@link NewFuelElevatorBlockEntity}): stood on the rim of a formed spent fuel pool wall it lowers a fuel assembly
 * down a guide frame under the water into the first free rack cell. Right-click with an assembly puts it in the hatch; a bare hand opens the
 * screen; sneaking with a bare hand takes the assembly waiting at the hatch, or prints what the elevator sees. It has no ticker: a trip
 * schedules ordinary block ticks for its two moments.
 *
 * <p>The block is drawn entirely by its renderer (the hoist on top, the guide frame down the pool, the carriage and the assembly), so the
 * block model is empty; {@link #FACING} only says which way the hoist faces until the elevator has found its pool, and then the pool says.
 */
public class NewFuelElevatorBlock extends BaseEntityBlock {
    public static final MapCodec<NewFuelElevatorBlock> CODEC = simpleCodec(NewFuelElevatorBlock::new);
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    private static final VoxelShape SHAPE = Shapes.box(0.0, 0.0, 0.0, 1.0, 1.5, 1.0);

    public NewFuelElevatorBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, net.minecraft.core.Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<net.minecraft.world.level.block.Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.ENTITYBLOCK_ANIMATED;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new NewFuelElevatorBlockEntity(pos, state);
    }

    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (level.getBlockEntity(pos) instanceof NewFuelElevatorBlockEntity be) {
            be.scheduledTick(level);
        }
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
                                              InteractionHand hand, BlockHitResult hit) {
        if (!stack.is(dev.bwr.mod.registry.BwrItems.FUEL_ASSEMBLY.get())) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (level.isClientSide()) {
            return ItemInteractionResult.SUCCESS;
        }
        if (level.getBlockEntity(pos) instanceof NewFuelElevatorBlockEntity be) {
            var why = be.offer(stack.copyWithCount(1), false);
            if (why == null) {
                if (!player.getAbilities().instabuild) {
                    stack.shrink(1);
                }
                player.displayClientMessage(BwrText.tr("status.bwr.new_fuel_elevator.loaded", "Loaded into the hatch."), true);
            } else {
                player.displayClientMessage(why.text(), true);
            }
        }
        return ItemInteractionResult.CONSUME;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (level.getBlockEntity(pos) instanceof NewFuelElevatorBlockEntity be) {
            if (player.isShiftKeyDown() || !(player instanceof ServerPlayer sp)) {
                ItemStack landed = be.takeLanding();
                if (!landed.isEmpty()) {
                    if (!player.getInventory().add(landed)) {
                        Containers.dropItemStack(level, pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, landed);
                    }
                } else {
                    for (Component line : be.statusComponents()) {
                        player.displayClientMessage(line, false);
                    }
                }
            } else {
                be.link();
                dev.bwr.mod.gui.NewFuelElevatorMenu.open(sp, be);
            }
        }
        return InteractionResult.CONSUME;
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState next, boolean moved) {
        if (state.getBlock() != next.getBlock() && level.getBlockEntity(pos) instanceof NewFuelElevatorBlockEntity be) {
            for (ItemStack drop : be.removalDrops()) {
                Containers.dropItemStack(level, pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, drop);
            }
        }
        super.onRemove(state, level, pos, next, moved);
    }
}
