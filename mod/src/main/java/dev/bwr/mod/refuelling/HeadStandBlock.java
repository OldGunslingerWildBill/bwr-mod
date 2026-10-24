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
 * The head stand: a steel plinth marking the middle of the place on the refuel floor where the reactor building crane lays the
 * vessel head. A stand holding a head cannot be broken by hand: lift the head back first.
 */
public class HeadStandBlock extends BaseEntityBlock {
    public static final MapCodec<HeadStandBlock> CODEC = simpleCodec(HeadStandBlock::new);
    private static final VoxelShape SHAPE = Block.box(0, 0, 0, 16, 4, 16);

    public HeadStandBlock(Properties properties) {
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
        return new HeadStandBlockEntity(pos, state);
    }

    @Override
    protected float getDestroyProgress(BlockState state, Player player, BlockGetter level, BlockPos pos) {
        if (level.getBlockEntity(pos) instanceof HeadStandBlockEntity stand && stand.holdsHead()) return 0.0F;
        return super.getDestroyProgress(state, player, level, pos);
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        // A creative player or a command removed a stand that held a head: the head is laid down on the floor instead of vanishing.
        if (!level.isClientSide() && !state.is(newState.getBlock()) && level.getBlockEntity(pos) instanceof HeadStandBlockEntity stand
                && stand.holdsHead() && level.isLoaded(stand.reactorPos())
                && level.getBlockEntity(stand.reactorPos()) instanceof ReactorControllerBlockEntity reactor) {
            reactor.headMoved(null);
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide() && level.getBlockEntity(pos) instanceof HeadStandBlockEntity stand) {
            player.displayClientMessage(stand.holdsHead()
                    ? BwrText.tr("status.bwr.head_stand.holding", "Holding the head of the reactor at %s.", stand.reactorPos().toShortString())
                    : BwrText.tr("status.bwr.head_stand.empty",
                    "Empty. The reactor building crane lays the vessel head down here; keep the floor round it clear, as wide as the vessel, and between the crane's rails."), true);
        }
        return InteractionResult.sidedSuccess(level.isClientSide());
    }
}
