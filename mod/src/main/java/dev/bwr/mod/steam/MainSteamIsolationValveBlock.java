package dev.bwr.mod.steam;

import com.mojang.serialization.MapCodec;
import dev.bwr.mod.registry.BwrBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;

/**
 * MSIV block. Redstone or Lua closes it; nothing else does.
 *
 * <p>A {@link SteamLinePort} on all six faces. A real isolation valve is an
 * in-line body with steam entering one end and leaving the other, so restricting
 * connection to a single axis would be the more faithful thing — but this block
 * has no {@code FACING} property and its model is an unoriented cube, so there
 * is no axis to restrict to. Inventing one would refuse pipes on the strength of
 * an orientation the player has no way to see or choose. If the valve ever gains
 * a facing and a modelled body, override
 * {@link SteamLinePort#acceptsSteamLineOn} here and the tubes follow.
 */
public class MainSteamIsolationValveBlock extends BaseEntityBlock implements SteamLinePort {

    public static final MapCodec<MainSteamIsolationValveBlock> CODEC =
            simpleCodec(MainSteamIsolationValveBlock::new);

    public static final BooleanProperty OPEN = BooleanProperty.create("open");

    public MainSteamIsolationValveBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(OPEN, true));
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
        return new MainSteamIsolationValveBlockEntity(pos, state);
    }

    /**
     * The valve strokes on the server tick. Without this ticker the block
     * entity never ran at all, so {@code position} stayed at whatever it was
     * constructed with and closing an MSIV had no effect on anything.
     */
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                  BlockEntityType<T> type) {
        if (level.isClientSide()) {
            return null;
        }
        return createTickerHelper(type, BwrBlockEntities.MSIV.get(),
                MainSteamIsolationValveBlockEntity::serverTick);
    }

    /** Redstone signal closes the valve, matching real fail-closed isolation logic. */
    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighborBlock,
                                   BlockPos neighborPos, boolean movedByPiston) {
        super.neighborChanged(state, level, pos, neighborBlock, neighborPos, movedByPiston);
        if (level.isClientSide()) {
            return;
        }
        if (level.getBlockEntity(pos) instanceof MainSteamIsolationValveBlockEntity be
                && !be.isComputerControlled()) {
            boolean shouldBeOpen = !level.hasNeighborSignal(pos);
            if (shouldBeOpen != be.isDemandOpen()) {
                be.setDemandOpen(shouldBeOpen);
                level.setBlock(pos, state.setValue(OPEN, shouldBeOpen), Block.UPDATE_ALL);
            }
        }
    }
}
