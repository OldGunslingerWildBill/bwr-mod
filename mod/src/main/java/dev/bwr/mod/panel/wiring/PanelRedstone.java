package dev.bwr.mod.panel.wiring;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Redstone input at a panel's back face. The back is the side opposite the panel's facing; the neighbour
 * there is read the way a repeater reads its input, except that the strong power the panel itself emits into
 * that neighbour is ignored, so a panel that outputs to the same block it reads from does not latch itself.
 */
public final class PanelRedstone {
    private PanelRedstone() {
    }

    /** Signal strength 0..15 arriving at {@code pos} from the block behind it (opposite {@code facing}). */
    public static int inputAt(Level level, BlockPos pos, Direction facing) {
        Direction back = facing.getOpposite();
        BlockPos behind = pos.relative(back);
        if (!level.isLoaded(behind)) {
            return 0;
        }
        BlockState state = level.getBlockState(behind);
        int best = state.getSignal(level, behind, back);
        if (state.isRedstoneConductor(level, behind)) {
            for (Direction side : Direction.values()) {
                if (side != facing) {
                    best = Math.max(best, level.getDirectSignal(behind.relative(side), side));
                }
            }
        }
        return Math.max(0, Math.min(15, best));
    }
}
