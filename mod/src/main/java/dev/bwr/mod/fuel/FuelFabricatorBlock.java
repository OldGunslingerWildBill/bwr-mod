package dev.bwr.mod.fuel;

import com.mojang.serialization.MapCodec;
import dev.bwr.mod.registry.BwrBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
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
 * The fuel fabricator block. Right-click reports what is in the batch and what
 * it would come out as; everything else is done with pipes.
 *
 * <p>No blockstate properties, deliberately: siblings are authoring the model
 * and blockstate for {@code fuel_fabricator}, and a facing property would demand
 * variants they have not been asked for.
 */
public class FuelFabricatorBlock extends BaseEntityBlock {

    public static final MapCodec<FuelFabricatorBlock> CODEC = simpleCodec(FuelFabricatorBlock::new);

    public FuelFabricatorBlock(Properties properties) {
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
        return new FuelFabricatorBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                  BlockEntityType<T> type) {
        if (level.isClientSide()) {
            return null;
        }
        return createTickerHelper(type, BwrBlockEntities.FUEL_FABRICATOR.get(),
                FuelFabricatorBlockEntity::serverTick);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hit) {
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (level.getBlockEntity(pos) instanceof FuelFabricatorBlockEntity be) {
            // The screen has the output slot in it, so a plain right-click opens
            // it. Sneak-clicking still snatches a finished bundle and otherwise
            // prints the status, which is quicker when a player is walking past.
            if (player.isShiftKeyDown()
                    || !(player instanceof net.minecraft.server.level.ServerPlayer sp)) {
                ItemStack finished = be.output().extractItem(0, 1, false);
                if (!finished.isEmpty()) {
                    if (!player.getInventory().add(finished)) {
                        Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), finished);
                    }
                    return InteractionResult.CONSUME;
                }
                for (String line : be.statusLines()) {
                    player.displayClientMessage(Component.literal(line), false);
                }
            } else {
                dev.bwr.mod.gui.FuelFabricatorMenu.open(sp, be);
            }
        }
        return InteractionResult.CONSUME;
    }

    /**
     * A finished bundle is 180 kg of enriched heavy metal. Breaking the machine
     * drops it rather than deleting it — the partly-accumulated batch is lost,
     * which is a real cost, but a completed assembly is not silently voided.
     */
    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos,
                            BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock())
                && level.getBlockEntity(pos) instanceof FuelFabricatorBlockEntity be) {
            ItemStack finished = be.output().extractItem(0, 1, false);
            if (!finished.isEmpty()) {
                Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), finished);
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}
