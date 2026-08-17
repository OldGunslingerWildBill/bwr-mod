package dev.bwr.mod.steam;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;

/**
 * Safety/relief valve block. Redstone opens it; so does Lua. Physics never does.
 *
 * <p>A {@link SteamLinePort} on all six faces, including the bottom. The
 * discharge does leave downwards — {@link SafetyReliefValveBlockEntity} looks
 * straight down for the pool surface — but that search reads fluid states and
 * walks past solid blocks, so a tube underneath neither helps nor hinders it.
 * Refusing the connection would only mean a player who chooses to run the tailpipe
 * in tube gets a stub instead, over a distinction the model does not make.
 */
public class SafetyReliefValveBlock extends BaseEntityBlock implements SteamLinePort {

    public static final MapCodec<SafetyReliefValveBlock> CODEC =
            simpleCodec(SafetyReliefValveBlock::new);

    public static final BooleanProperty OPEN = BooleanProperty.create("open");

    public SafetyReliefValveBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(OPEN, false));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(OPEN);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SafetyReliefValveBlockEntity(pos, state);
    }

    /**
     * Redstone is a first-class actuation path, so a player who has not written
     * any Lua can still build a relief scheme out of comparators and repeaters.
     * A computer-controlled valve ignores redstone so the two cannot fight.
     */
    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighborBlock,
                                   BlockPos neighborPos, boolean movedByPiston) {
        super.neighborChanged(state, level, pos, neighborBlock, neighborPos, movedByPiston);
        if (level.isClientSide()) {
            return;
        }
        if (level.getBlockEntity(pos) instanceof SafetyReliefValveBlockEntity be) {
            be.revalidateDischarge(level);
            if (!be.isComputerControlled()) {
                boolean powered = level.hasNeighborSignal(pos);
                if (powered != be.isOpen()) {
                    be.setOpen(powered);
                    level.setBlock(pos, state.setValue(OPEN, powered), Block.UPDATE_ALL);
                }
            }
        }
    }
}
