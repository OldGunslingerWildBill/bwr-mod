package dev.bwr.mod.lighting;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;

/**
 * Base of the six industrial lamps (LIGHTING.md). Redstone-controlled: powered means on. A lit lamp warms through three
 * stages (light 4, 8, 12, then 15) on scheduled block ticks; a lamp that goes out and is hot waits out its restrike
 * cooling before it can strike again. There is no per-block ticker: a tick is scheduled only when a stage or a cooling
 * step has to happen.
 *
 * <p>One tick per block is all vanilla keeps, so {@link #tick} reads the state and does whichever step is due, and
 * schedules the next one itself. A tick that is already pending is kept, which only moves the first step a little.
 */
public abstract class IndustrialLampBlock extends Block {
    /** Lit: the lamp is on (warming or full). */
    public static final BooleanProperty LIT = BooleanProperty.create("lit");
    /** Warm-up stage of a lit lamp: 0 is light 4, 3 is light 15. */
    public static final IntegerProperty WARMUP = IntegerProperty.create("warmup", 0, 3);
    /** Restrike cooling units still to run out after the lamp was switched off (0 = ready). */
    public static final IntegerProperty COOLING = IntegerProperty.create("cooling", 0, 3);

    private final LampType type;

    protected IndustrialLampBlock(Properties properties, LampType type) {
        super(properties);
        this.type = type;
        registerDefaultState(stateDefinition.any().setValue(LIT, false).setValue(WARMUP, 0).setValue(COOLING, 0));
    }

    public LampType lampType() {
        return type;
    }

    /** The light a lamp gives in this state (used by the block's light level). */
    public static int emission(BlockState state) {
        return state.getValue(LIT) ? LampType.lightAt(state.getValue(WARMUP)) : 0;
    }

    /** Whether the lamp at pos has its mount: a ceiling for the high bay, a wall for the wall pack. */
    protected abstract boolean hasSupport(LevelReader level, BlockPos pos, BlockState state);

    @Override
    public boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        return hasSupport(level, pos, state);
    }

    /** A lamp that loses its mount drops itself, like a torch does. */
    @Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState neighbour, LevelAccessor level,
            BlockPos pos, BlockPos neighbourPos) {
        if (!state.canSurvive(level, pos)) {
            if (level instanceof Level real && !real.isClientSide) {
                Block.dropResources(state, real, pos);
            }
            return Blocks.AIR.defaultBlockState();
        }
        return super.updateShape(state, direction, neighbour, level, pos, neighbourPos);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(LIT, WARMUP, COOLING);
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean moved) {
        super.onPlace(state, level, pos, oldState, moved);
        if (!level.isClientSide && !oldState.is(this)) {
            evaluate(level, pos, state);
        }
    }

    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighbour, BlockPos from,
            boolean moved) {
        if (!level.isClientSide) {
            evaluate(level, pos, state);
        }
    }

    /** Strikes the lamp when it is powered and ready, and puts it out (and into cooling) when the power goes. */
    private void evaluate(Level level, BlockPos pos, BlockState state) {
        boolean powered = level.hasNeighborSignal(pos);
        if (powered) {
            if (!state.getValue(LIT) && state.getValue(COOLING) == 0) {
                strike(level, pos, state);
            }
        } else if (state.getValue(LIT)) {
            int cooling = type.restrikeUnits();
            level.setBlock(pos, state.setValue(LIT, false).setValue(WARMUP, 0).setValue(COOLING, cooling), Block.UPDATE_ALL);
            if (cooling > 0) {
                level.scheduleTick(pos, this, LampType.RESTRIKE_UNIT_TICKS);
            }
        }
    }

    private void strike(Level level, BlockPos pos, BlockState state) {
        level.setBlock(pos, state.setValue(LIT, true).setValue(WARMUP, 0), Block.UPDATE_ALL);
        level.scheduleTick(pos, this, type.stepTicks());
    }

    @Override
    public void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (state.getValue(LIT)) {
            if (!level.hasNeighborSignal(pos)) {
                evaluate(level, pos, state);
                return;
            }
            int stage = state.getValue(WARMUP);
            if (stage < LampType.WARM_STEPS) {
                level.setBlock(pos, state.setValue(WARMUP, stage + 1), Block.UPDATE_ALL);
                if (stage + 1 < LampType.WARM_STEPS) {
                    level.scheduleTick(pos, this, type.stepTicks());
                }
            }
            return;
        }
        int cooling = state.getValue(COOLING);
        if (cooling > 1) {
            level.setBlock(pos, state.setValue(COOLING, cooling - 1), Block.UPDATE_ALL);
            level.scheduleTick(pos, this, LampType.RESTRIKE_UNIT_TICKS);
        } else if (cooling == 1) {
            BlockState ready = state.setValue(COOLING, 0);
            if (level.hasNeighborSignal(pos)) {
                strike(level, pos, ready);
            } else {
                level.setBlock(pos, ready, Block.UPDATE_ALL);
            }
        }
    }
}
