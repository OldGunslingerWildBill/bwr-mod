package dev.bwr.mod.isotope;

import com.mojang.serialization.MapCodec;
import dev.bwr.mod.registry.BwrBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionResult;
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
 * The isotope separator block, styled and handled like the reprocessing plant it follows: right-click opens the screen,
 * sneak-right-click snatches a finished product or prints the status, pipes use {@link IsotopeSeparatorItemView}.
 */
public class IsotopeSeparatorBlock extends BaseEntityBlock {

    public static final MapCodec<IsotopeSeparatorBlock> CODEC = simpleCodec(IsotopeSeparatorBlock::new);

    public IsotopeSeparatorBlock(Properties properties) {
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
        return new IsotopeSeparatorBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide()) {
            return null;
        }
        return dev.bwr.mod.TickProbe.wrap(createTickerHelper(type, BwrBlockEntities.ISOTOPE_SEPARATOR.get(),
                IsotopeSeparatorBlockEntity::serverTick));
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (level.getBlockEntity(pos) instanceof IsotopeSeparatorBlockEntity be) {
            if (player.isShiftKeyDown() || !(player instanceof ServerPlayer sp)) {
                ItemStack finished = ItemStack.EMPTY;
                for (int slot = IsotopeSeparatorBlockEntity.STRONTIUM; slot <= IsotopeSeparatorBlockEntity.STRIPPED && finished.isEmpty(); slot++) {
                    finished = be.items().extractItem(slot, 64, false);
                }
                if (!finished.isEmpty()) {
                    if (!player.getInventory().add(finished)) {
                        Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), finished);
                    }
                    return InteractionResult.CONSUME;
                }
                for (Component line : be.statusComponents()) {
                    player.displayClientMessage(line, false);
                }
            } else {
                dev.bwr.mod.gui.IsotopeSeparatorMenu.open(sp, be);
            }
        }
        return InteractionResult.CONSUME;
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && level.getBlockEntity(pos) instanceof IsotopeSeparatorBlockEntity be) {
            for (ItemStack drop : be.removalDrops()) {
                Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), drop);
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}
