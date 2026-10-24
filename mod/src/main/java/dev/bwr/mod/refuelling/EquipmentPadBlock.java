package dev.bwr.mod.refuelling;

import com.mojang.serialization.MapCodec;
import dev.bwr.mod.reactor.ReactorControllerBlockEntity;
import dev.bwr.mod.text.BwrText;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The equipment storage pad: a steel stand on the floor of a pool of water the refuelling bridge reaches, for the steam dryer
 * or the separator assembly while the vessel is open. A pad holding an assembly cannot be broken by hand: lift it back first.
 */
public class EquipmentPadBlock extends BaseEntityBlock {
    public static final MapCodec<EquipmentPadBlock> CODEC = simpleCodec(EquipmentPadBlock::new);
    private static final VoxelShape SHAPE = Block.box(0, 0, 0, 16, 4, 16);

    public EquipmentPadBlock(Properties properties) {
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
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new EquipmentPadBlockEntity(pos, state);
    }

    @Override
    protected float getDestroyProgress(BlockState state, Player player, BlockGetter level, BlockPos pos) {
        if (level.getBlockEntity(pos) instanceof EquipmentPadBlockEntity pad && pad.stored() != null) return 0.0F;
        return super.getDestroyProgress(state, player, level, pos);
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        // A creative player or a command removed a pad that held an assembly: it goes back into its vessel rather than vanishing.
        if (!level.isClientSide() && !state.is(newState.getBlock()) && level.getBlockEntity(pos) instanceof EquipmentPadBlockEntity pad
                && pad.stored() != null && level.isLoaded(pad.reactorPos())
                && level.getBlockEntity(pad.reactorPos()) instanceof ReactorControllerBlockEntity reactor) {
            reactor.internalIn(pad.stored());
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide() && level.getBlockEntity(pos) instanceof EquipmentPadBlockEntity pad) {
            var what = pad.stored();
            player.displayClientMessage(what == null
                    ? BwrText.tr("status.bwr.equipment_pad.empty", "Empty. The refuelling bridge sets the steam dryer or the separator assembly down here.")
                    : BwrText.tr("status.bwr.equipment_pad.holding", "Holding the %s of the reactor at %s, under %s m of water.",
                    what.title, pad.reactorPos().toShortString(), String.format(java.util.Locale.ROOT, "%.1f", pad.coverM())), true);
        }
        return InteractionResult.sidedSuccess(level.isClientSide());
    }
}
