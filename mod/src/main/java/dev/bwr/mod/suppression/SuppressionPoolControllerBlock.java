package dev.bwr.mod.suppression;

import com.mojang.serialization.MapCodec;
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

/**
 * Controller for the suppression pool. Right-click reports pool condition; all
 * actual control is through the peripheral or redstone.
 */
public class SuppressionPoolControllerBlock extends BaseEntityBlock {

    public static final MapCodec<SuppressionPoolControllerBlock> CODEC =
            simpleCodec(SuppressionPoolControllerBlock::new);

    public SuppressionPoolControllerBlock(Properties properties) {
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
        return new SuppressionPoolBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                  BlockEntityType<T> type) {
        if (level.isClientSide()) {
            return null;
        }
        return createTickerHelper(type, BwrBlockEntities.SUPPRESSION_POOL.get(),
                SuppressionPoolBlockEntity::serverTick);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hit) {
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (level.getBlockEntity(pos) instanceof SuppressionPoolBlockEntity be) {
            // A player asking for a report gets a current one. Neither the
            // quencher nor the tube run that reaches it is next to this block,
            // so nothing about building a discharge line makes this controller
            // rescan; without this the board would go on describing the plant
            // as it stood up to half a minute ago.
            be.refreshForReport(level);
            // Panel on a plain right-click, the build-time status text on a
            // sneak-click, same convention as the reactor controller.
            if (player.isShiftKeyDown()
                    || !(player instanceof net.minecraft.server.level.ServerPlayer sp)) {
                for (String line : be.statusLines()) {
                    player.displayClientMessage(Component.literal(line), false);
                }
            } else {
                dev.bwr.mod.gui.SuppressionPoolMenu.open(sp, be);
            }
        }
        return InteractionResult.CONSUME;
    }

    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighborBlock,
                                   BlockPos neighborPos, boolean movedByPiston) {
        super.neighborChanged(state, level, pos, neighborBlock, neighborPos, movedByPiston);
        if (!level.isClientSide()
                && level.getBlockEntity(pos) instanceof SuppressionPoolBlockEntity be) {
            be.markStructureDirty();
        }
    }
}
