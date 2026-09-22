package dev.bwr.mod.reactor;

import dev.bwr.mod.registry.BwrBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import com.mojang.serialization.MapCodec;

/**
 * The reactor controller. One per plant; it owns the {@code ReactorCore}
 * instance, ticks the physics server-side, and is the block a CC:Tweaked
 * computer wraps to get at the plant.
 *
 * <p>Right-clicking reports structure status. It deliberately does not offer
 * any control: rod motion, flow, valves and scram are all actuated through the
 * peripheral or redstone, because the player's Lua is the control system.
 */
public class ReactorControllerBlock extends BaseEntityBlock {

    public static final MapCodec<ReactorControllerBlock> CODEC =
            simpleCodec(ReactorControllerBlock::new);

    public ReactorControllerBlock(Properties properties) {
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
        return new ReactorControllerBlockEntity(pos, state);
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState replacement, boolean moving) {
        if (!level.isClientSide() && !state.is(replacement.getBlock())
                && level.getBlockEntity(pos) instanceof ReactorControllerBlockEntity reactor) {
            for (var bundle : reactor.takeFuelForRemoval()) Block.popResource(level, pos, bundle);
        }
        super.onRemove(state, level, pos, replacement, moving);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                  BlockEntityType<T> type) {
        // Physics is server-authoritative. The client only renders synced state.
        if (level.isClientSide()) {
            return null;
        }
        return createTickerHelper(type, BwrBlockEntities.REACTOR_CONTROLLER.get(),
                ReactorControllerBlockEntity::serverTick);
    }

    /**
     * Right-click opens the control room panel; sneak-right-click prints the
     * structure status as text, which is what a player wants while they are
     * still building the thing and the panel has nothing to show.
     */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hit) {
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (level.getBlockEntity(pos) instanceof ReactorControllerBlockEntity be) {
            if (player.isShiftKeyDown() || !(player instanceof net.minecraft.server.level.ServerPlayer sp)) {
                for (String line : be.statusLines()) {
                    player.displayClientMessage(Component.literal(line), false);
                }
            } else {
                dev.bwr.mod.gui.ReactorPanelMenu.open(sp, be);
            }
        }
        return InteractionResult.CONSUME;
    }

    /**
     * Structure validation runs on neighbour change, never per tick — scanning
     * a vessel every tick would be pointless work for a structure that only
     * changes when someone builds or breaks something.
     */
    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighborBlock,
                                   BlockPos neighborPos, boolean movedByPiston) {
        super.neighborChanged(state, level, pos, neighborBlock, neighborPos, movedByPiston);
        if (!level.isClientSide()
                && level.getBlockEntity(pos) instanceof ReactorControllerBlockEntity be) {
            be.markStructureDirty();
        }
    }
}
