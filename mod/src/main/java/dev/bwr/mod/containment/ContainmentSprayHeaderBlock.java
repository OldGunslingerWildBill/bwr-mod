package dev.bwr.mod.containment;

import com.mojang.serialization.MapCodec;
import dev.bwr.mod.registry.BwrBlockEntities;
import dev.bwr.mod.piping.PipeTopology;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;

/** Passive water-fed spray nozzle. The linked room samples it on the reactor tick. */
public final class ContainmentSprayHeaderBlock extends BaseEntityBlock {
    public static final MapCodec<ContainmentSprayHeaderBlock> CODEC = simpleCodec(ContainmentSprayHeaderBlock::new);
    public ContainmentSprayHeaderBlock(Properties properties) { super(properties); }
    @Override protected MapCodec<? extends BaseEntityBlock> codec() { return CODEC; }
    @Override protected RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }
    @Override public ContainmentSprayHeaderBlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ContainmentSprayHeaderBlockEntity(pos, state);
    }
    @Override protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState old, boolean moving) {
        super.onPlace(state, level, pos, old, moving);
        if (state != old) PipeTopology.changed(level, pos);
    }
    @Override protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState next, boolean moving) {
        super.onRemove(state, level, pos, next, moving);
        if (state != next) PipeTopology.changed(level, pos);
    }
}
