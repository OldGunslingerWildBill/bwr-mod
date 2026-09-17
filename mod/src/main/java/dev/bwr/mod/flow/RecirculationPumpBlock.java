package dev.bwr.mod.flow;

import com.mojang.serialization.MapCodec;
import dev.bwr.mod.reactor.ReactorControllerBlockEntity;
import dev.bwr.mod.registry.BwrBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Recirculation pump block. Uses the satellite pattern: on placement it looks
 * for a nearby controller and registers itself, and on removal it deregisters.
 * Neither event invalidates the reactor.
 *
 * <p>It also carries a server ticker, which does binding only — the speed
 * integration belongs to the controller's tick. Placement is not a reliable
 * moment to bind: a pump placed before the reactor exists, or a controller
 * broken and replaced under a pump that is already there, both leave the pump
 * orphaned and the core flow at zero with nothing to say so.
 */
public class RecirculationPumpBlock extends BaseEntityBlock implements dev.bwr.mod.steam.SteamLinePort {
    @Override public boolean acceptsSteamLineOn(BlockState state, net.minecraft.core.Direction face) { return true; }

    public static final MapCodec<RecirculationPumpBlock> CODEC =
            simpleCodec(RecirculationPumpBlock::new);

    public RecirculationPumpBlock(Properties properties) {
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
        return new RecirculationPumpBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                  BlockEntityType<T> type) {
        if (level.isClientSide()) {
            return null;
        }
        return createTickerHelper(type, BwrBlockEntities.RECIRCULATION_PUMP.get(),
                RecirculationPumpBlockEntity::serverTick);
    }

    /**
     * Opens the pump's own screen: the speed slider, the CC control toggle and
     * the power limit, per {@code SPEC.md} section 4.2.
     */
    @Override
    protected net.minecraft.world.InteractionResult useWithoutItem(
            BlockState state, Level level, BlockPos pos,
            net.minecraft.world.entity.player.Player player,
            net.minecraft.world.phys.BlockHitResult hit) {
        if (level.isClientSide()) {
            return net.minecraft.world.InteractionResult.SUCCESS;
        }
        if (player instanceof net.minecraft.server.level.ServerPlayer sp
                && level.getBlockEntity(pos) instanceof RecirculationPumpBlockEntity pump) {
            dev.bwr.mod.gui.RecirculationPumpMenu.open(sp, pump);
        }
        return net.minecraft.world.InteractionResult.CONSUME;
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer,
                            ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level.isClientSide()) {
            return;
        }
        if (level.getBlockEntity(pos) instanceof RecirculationPumpBlockEntity pump) {
            // A convenience only: it makes a pump placed next to an existing
            // plant join on the same tick instead of within two seconds. The
            // ticker is what actually guarantees binding.
            ReactorControllerBlockEntity controller =
                    RecirculationPumpBlockEntity.findController(level, pos);
            if (controller != null) {
                pump.bindController(controller);
            }
        }
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState,
                            boolean movedByPiston) {
        if (!state.is(newState.getBlock())
                && level.getBlockEntity(pos) instanceof RecirculationPumpBlockEntity pump) {
            BlockPos controllerPos = pump.getControllerPos();
            if (controllerPos != null
                    && level.getBlockEntity(controllerPos) instanceof ReactorControllerBlockEntity c) {
                c.removePump(pos);
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}
