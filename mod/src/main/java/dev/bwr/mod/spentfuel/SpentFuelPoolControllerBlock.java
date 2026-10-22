package dev.bwr.mod.spentfuel;

import com.mojang.serialization.MapCodec;
import dev.bwr.mod.registry.BwrBlockEntities;
import dev.bwr.mod.registry.BwrItems;
import dev.bwr.mod.text.BwrText;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * The controller of a spent fuel pool, one block of its shell. Right-click opens the pool panel, sneak-click prints the
 * build status, and a fuel assembly in hand goes into the first free cell. It is the pool's only ticker.
 */
public class SpentFuelPoolControllerBlock extends BaseEntityBlock {

    public static final MapCodec<SpentFuelPoolControllerBlock> CODEC = simpleCodec(SpentFuelPoolControllerBlock::new);

    public SpentFuelPoolControllerBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SpentFuelPoolBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide()) {
            return null;
        }
        return dev.bwr.mod.TickProbe.wrap(createTickerHelper(type, BwrBlockEntities.SPENT_FUEL_POOL.get(),
                SpentFuelPoolBlockEntity::serverTick));
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState old, boolean moved) {
        super.onPlace(state, level, pos, old, moved);
        if (state.getBlock() != old.getBlock()) {
            SpentFuelPoolRegistry.changed(level, pos);
        }
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (level.getBlockEntity(pos) instanceof SpentFuelPoolBlockEntity be) {
            be.refreshForReport();
            if (player.isShiftKeyDown() || !(player instanceof ServerPlayer sp)) {
                for (Component line : be.statusComponents()) {
                    player.displayClientMessage(line, false);
                }
            } else {
                dev.bwr.mod.gui.SpentFuelPoolMenu.open(sp, be);
            }
        }
        return InteractionResult.CONSUME;
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
                                              InteractionHand hand, BlockHitResult hit) {
        if (!stack.is(BwrItems.FUEL_ASSEMBLY.get())) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (level.isClientSide()) {
            return ItemInteractionResult.SUCCESS;
        }
        if (level.getBlockEntity(pos) instanceof SpentFuelPoolBlockEntity be) {
            if (be.insertAnywhere(stack.copyWithCount(1))) {
                if (!player.getAbilities().instabuild) {
                    stack.shrink(1);
                }
            } else {
                player.displayClientMessage(be.isFormed()
                        ? BwrText.tr("status.bwr.spent_fuel_pool.pool_full", "Every cell is taken.")
                        : BwrText.tr("status.bwr.spent_fuel_pool.rack_unformed", "This rack is not in a formed pool."), true);
            }
        }
        return ItemInteractionResult.CONSUME;
    }

    /** Breaking the controller drops every assembly the pool holds; the water is lost with the block. */
    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState next, boolean moved) {
        if (state.getBlock() != next.getBlock()) {
            if (level.getBlockEntity(pos) instanceof SpentFuelPoolBlockEntity be) {
                for (ItemStack drop : be.removalDrops()) {
                    Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), drop);
                }
            }
            SpentFuelPoolRegistry.changed(level, pos);
        }
        super.onRemove(state, level, pos, next, moved);
    }
}
