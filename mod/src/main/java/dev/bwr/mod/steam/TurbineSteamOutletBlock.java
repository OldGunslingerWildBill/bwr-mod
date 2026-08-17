package dev.bwr.mod.steam;

import com.mojang.serialization.MapCodec;
import dev.bwr.mod.reactor.ReactorControllerBlockEntity;
import dev.bwr.mod.registry.BwrBlockEntities;
import dev.bwr.mod.registry.BwrBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * The turbine steam outlet block — the mod's steam boundary with Mekanism.
 *
 * <p>A satellite of the reactor in exactly the sense a recirculation pump is:
 * it finds a controller when placed and attaches itself, and breaking it never
 * invalidates the multiblock. Losing the outlet just means the steam stops
 * leaving, which the vessel expresses as rising pressure — the correct
 * consequence, arrived at by physics rather than by a rule.
 *
 * <p>Deliberately carries no blockstate properties. The outlet's condition is a
 * continuous flow, not an on/off pose, and a state property would only be
 * something an asset has to model for no visual gain.
 *
 * <p>A {@link SteamLinePort} on all six faces, which is the connection that
 * makes "is the turbine actually connected" a question a build can answer: this
 * is the downstream end of the main steam line, and a run of tube from the
 * vessel that lands on it visibly terminates rather than running into a wall.
 * All six faces because the block has no {@code FACING} and its model is
 * symmetric on four sides; there is no nozzle drawn anywhere to honour.
 */
public class TurbineSteamOutletBlock extends BaseEntityBlock implements SteamLinePort {

    public static final MapCodec<TurbineSteamOutletBlock> CODEC =
            simpleCodec(TurbineSteamOutletBlock::new);

    public TurbineSteamOutletBlock(Properties properties) {
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
        return new TurbineSteamOutletBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                  BlockEntityType<T> type) {
        if (level.isClientSide()) {
            return null;
        }
        return createTickerHelper(type, BwrBlockEntities.TURBINE_STEAM_OUTLET.get(),
                TurbineSteamOutletBlockEntity::serverTick);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer,
                            ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level.isClientSide()) {
            return;
        }
        if (level.getBlockEntity(pos) instanceof TurbineSteamOutletBlockEntity outlet) {
            ReactorControllerBlockEntity controller = findController(level, pos);
            if (controller != null) {
                outlet.bindController(controller);
            }
        }
    }

    /**
     * Analogue redstone commands flow, linearly from zero at signal 0 to rated
     * steam flow at signal 15.
     *
     * <p>The scale is a fixed constant, not a fraction of whatever the core
     * happens to be boiling. That distinction matters: a signal here is a valve
     * position, so the same signal always means the same demand and any
     * matching of demand to production is the player's control loop to write.
     * A computer holding control ignores redstone entirely, the same way the
     * SRVs and MSIVs do.
     */
    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighborBlock,
                                   BlockPos neighborPos, boolean movedByPiston) {
        super.neighborChanged(state, level, pos, neighborBlock, neighborPos, movedByPiston);
        if (level.isClientSide()) {
            return;
        }
        if (level.getBlockEntity(pos) instanceof TurbineSteamOutletBlockEntity outlet
                && !outlet.isComputerControlled()) {
            int signal = level.getBestNeighborSignal(pos);
            outlet.setCommandedFlowFractionOfRated(signal / 15.0);
        }
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hit) {
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (level.getBlockEntity(pos) instanceof TurbineSteamOutletBlockEntity outlet) {
            for (String line : outlet.statusLines()) {
                player.displayClientMessage(Component.literal(line), false);
            }
        }
        return InteractionResult.CONSUME;
    }

    private static ReactorControllerBlockEntity findController(Level level, BlockPos from) {
        int r = TurbineSteamOutletBlockEntity.SEARCH_RADIUS;
        for (BlockPos p : BlockPos.betweenClosed(from.offset(-r, -r, -r), from.offset(r, r, r))) {
            if (level.getBlockState(p).is(BwrBlocks.REACTOR_CONTROLLER.get())
                    && level.getBlockEntity(p) instanceof ReactorControllerBlockEntity c) {
                return c;
            }
        }
        return null;
    }
}
