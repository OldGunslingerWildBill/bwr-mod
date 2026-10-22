package dev.bwr.mod.fuel;

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
 * The reprocessing plant block. Right-click opens its screen; sneak-clicking snatches a finished product, or prints
 * the status. Everything else is done with pipes (see {@link ReprocessingItemView}). No blockstate properties, like the
 * fabricator it is styled after.
 */
public class ReprocessingPlantBlock extends BaseEntityBlock {

    public static final MapCodec<ReprocessingPlantBlock> CODEC = simpleCodec(ReprocessingPlantBlock::new);

    public ReprocessingPlantBlock(Properties properties) {
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
        return new ReprocessingPlantBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                  BlockEntityType<T> type) {
        if (level.isClientSide()) {
            return null;
        }
        return dev.bwr.mod.TickProbe.wrap(createTickerHelper(type, BwrBlockEntities.REPROCESSING_PLANT.get(),
                ReprocessingPlantBlockEntity::serverTick));
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hit) {
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (level.getBlockEntity(pos) instanceof ReprocessingPlantBlockEntity be) {
            if (player.isShiftKeyDown() || !(player instanceof ServerPlayer sp)) {
                ItemStack finished = be.plutoniumOut().extractItem(0, 64, false);
                if (finished.isEmpty()) {
                    finished = be.uraniumOut().extractItem(0, 64, false);
                }
                if (finished.isEmpty()) {
                    finished = be.wasteOut().extractItem(0, 64, false);
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
                dev.bwr.mod.gui.ReprocessingPlantMenu.open(sp, be);
            }
        }
        return InteractionResult.CONSUME;
    }

    /** Breaking the plant drops everything it holds, including the assembly in it. */
    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos,
                            BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock())
                && level.getBlockEntity(pos) instanceof ReprocessingPlantBlockEntity be) {
            for (ItemStack drop : be.removalDrops()) {
                Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), drop);
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}
