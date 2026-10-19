package dev.bwr.mod.furniture;

import dev.bwr.mod.world.AssemblyAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;

/**
 * An operator desk: 0.75 m high with an open knee and kick space under the top. Each desk block has an optional monitor
 * (none, a flat screen or a CRT) on top, which a bare hand cycles; sneaking with a bare hand flips a desk that has a
 * hand (the round-end desk). A held item is never intercepted, so blocks can still be placed against a desk.
 */
public abstract class DeskBase extends AssemblyBlock {
    public static final EnumProperty<DeskMonitor> MONITOR = EnumProperty.create("monitor", DeskMonitor.class);

    protected DeskBase(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(MONITOR, DeskMonitor.NONE));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(MONITOR);
    }

    /** The state a sneaking bare hand turns this one into, or null when the desk has nothing to flip. */
    protected BlockState alternate(BlockState state) {
        return null;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!player.getMainHandItem().isEmpty()) {
            return InteractionResult.PASS;
        }
        BlockState next = player.isShiftKeyDown() ? alternate(state) : state.setValue(MONITOR, state.getValue(MONITOR).next());
        if (next == null) {
            return InteractionResult.PASS;
        }
        if (!level.isClientSide()) {
            if (!AssemblyAccess.permitted(level, player, pos)) {
                return InteractionResult.PASS;
            }
            level.setBlock(pos, next, Block.UPDATE_ALL);
            level.playSound(null, pos, SoundEvents.LEVER_CLICK, SoundSource.BLOCKS, 0.4F, 1.3F);
        }
        return InteractionResult.sidedSuccess(level.isClientSide());
    }
}
