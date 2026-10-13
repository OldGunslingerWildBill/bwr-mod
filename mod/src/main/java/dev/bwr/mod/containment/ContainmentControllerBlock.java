package dev.bwr.mod.containment;

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

/** Placed inside an enclosed room; right-click reports the last survey. */
public final class ContainmentControllerBlock extends BaseEntityBlock {
    public static final MapCodec<ContainmentControllerBlock> CODEC = simpleCodec(ContainmentControllerBlock::new);

    public ContainmentControllerBlock(Properties properties) { super(properties); }

    @Override protected MapCodec<? extends BaseEntityBlock> codec() { return CODEC; }
    @Override protected RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ContainmentControllerBlockEntity(pos, state);
    }

    @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                             BlockEntityType<T> type) {
        if (level.isClientSide()) return null;
        return createTickerHelper(type, BwrBlockEntities.CONTAINMENT_CONTROLLER.get(),
                ContainmentControllerBlockEntity::serverTick);
    }

    @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                                           Player player, BlockHitResult hit) {
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        if (level.getBlockEntity(pos) instanceof ContainmentControllerBlockEntity controller)
            for (Component line : controller.statusComponents()) player.displayClientMessage(line, false);
        return InteractionResult.CONSUME;
    }

    @Override protected void neighborChanged(BlockState state, Level level, BlockPos pos,
                                              Block neighbour, BlockPos neighbourPos, boolean moving) {
        super.neighborChanged(state, level, pos, neighbour, neighbourPos, moving);
        if (!level.isClientSide()
                && level.getBlockEntity(pos) instanceof ContainmentControllerBlockEntity controller)
            controller.markDirty();
    }
}
