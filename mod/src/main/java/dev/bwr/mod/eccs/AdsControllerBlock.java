package dev.bwr.mod.eccs;

import com.mojang.serialization.MapCodec;
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
 * The ADS controller block: the solenoid rack and nitrogen bottles that hold
 * the relief valves open on command.
 *
 * <p>Redstone opens it, and so does Lua. Nothing else ever does — there is no
 * level permissive, no confirmatory pressure signal and no timer, which are
 * exactly the three things that make the real system automatic and exactly the
 * three things {@code SPEC.md} section 9 leaves to the player.
 */
public class AdsControllerBlock extends BaseEntityBlock {

    public static final MapCodec<AdsControllerBlock> CODEC = simpleCodec(AdsControllerBlock::new);

    public AdsControllerBlock(Properties properties) {
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
        return new AdsControllerBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                  BlockEntityType<T> type) {
        if (level.isClientSide()) {
            return null;
        }
        return createTickerHelper(type, dev.bwr.mod.registry.BwrBlockEntities.ADS_CONTROLLER.get(),
                AdsControllerBlockEntity::serverTick);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer,
                            ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide() && level.getBlockEntity(pos) instanceof AdsControllerBlockEntity be) {
            be.markBindingDirty();
        }
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState,
                            boolean movedByPiston) {
        if (!state.is(newState.getBlock())
                && level.getBlockEntity(pos) instanceof AdsControllerBlockEntity be) {
            be.detach();
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighborBlock,
                                   BlockPos neighborPos, boolean movedByPiston) {
        super.neighborChanged(state, level, pos, neighborBlock, neighborPos, movedByPiston);
        if (!level.isClientSide() && level.getBlockEntity(pos) instanceof AdsControllerBlockEntity be) {
            be.markBindingDirty();
            if (!be.isComputerControlled()) {
                boolean powered = level.hasNeighborSignal(pos);
                if (powered != be.isOpen()) {
                    be.setOpen(powered);
                }
            }
        }
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hit) {
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (level.getBlockEntity(pos) instanceof AdsControllerBlockEntity be) {
            for (String line : be.statusLines()) {
                player.displayClientMessage(Component.literal(line), false);
            }
        }
        return InteractionResult.CONSUME;
    }
}
