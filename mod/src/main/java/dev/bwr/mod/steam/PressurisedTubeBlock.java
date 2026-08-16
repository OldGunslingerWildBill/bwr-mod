package dev.bwr.mod.steam;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.PipeBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;

/**
 * Pressurised steam tube — {@code SPEC.md} section 6.4.
 *
 * <p>Mekanism's pipes move gas by amount and have no concept of pressure, so a
 * custom tube is needed. But this tube deliberately does <b>not</b> solve
 * pressure per segment: a per-block pressure network is heavy, prone to
 * oscillation, and buys almost no visible fidelity.
 *
 * <p>Instead the pressure model uses two lumped volumes — the RPV steam dome
 * and the main steam header — and these blocks provide <b>connectivity and
 * validation only</b>. Their job is to answer structural questions: is this SRV
 * on a valid steam line, is the turbine actually connected, does this discharge
 * reach water.
 */
public class PressurisedTubeBlock extends PipeBlock {

    public static final MapCodec<PressurisedTubeBlock> CODEC =
            simpleCodec(PressurisedTubeBlock::new);

    @Override
    protected MapCodec<? extends PipeBlock> codec() {
        return CODEC;
    }

    public PressurisedTubeBlock(Properties properties) {
        super(0.25F, properties);
        BlockState base = stateDefinition.any();
        for (Direction d : Direction.values()) {
            base = base.setValue(PROPERTY_BY_DIRECTION.get(d), false);
        }
        registerDefaultState(base);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        for (Direction d : Direction.values()) {
            builder.add(PROPERTY_BY_DIRECTION.get(d));
        }
    }
}
