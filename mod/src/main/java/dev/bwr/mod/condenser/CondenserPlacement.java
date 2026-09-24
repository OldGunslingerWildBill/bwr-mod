package dev.bwr.mod.condenser;

import dev.bwr.mod.eccs.PumpAssemblyBlock;
import dev.bwr.mod.power.PowerModuleBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Shared server/client alignment rule: one condenser, six blocks below an LP root. */
public final class CondenserPlacement {
    private CondenserPlacement() {}
    public record Attachment(BlockPos root, Direction facing, BlockPos turbine) {
        public boolean complete(Level level) {
            if (!level.isLoaded(turbine)) return false;
            var state = level.getBlockState(turbine);
            return state.getBlock() instanceof PowerModuleBlock block
                    && block.kind() == PumpAssemblyBlock.Kind.LP_TURBINE
                    && state.getValue(PumpAssemblyBlock.ASSEMBLED)
                    && CondenserLayout.INSTANCE.facingForTurbine(state.getValue(PumpAssemblyBlock.FACING)) == facing
                    && block.complete(level, turbine, state);
        }
    }
    public static Attachment attachment(Level level, BlockHitResult hit) {
        if (hit.getDirection() != Direction.DOWN || !level.isLoaded(hit.getBlockPos())) return null;
        var state = level.getBlockState(hit.getBlockPos());
        if (!(state.getBlock() instanceof PowerModuleBlock block)
                || block.kind() != PumpAssemblyBlock.Kind.LP_TURBINE) return null;
        var root = block.origin(hit.getBlockPos(), state);
        return new Attachment(root.below(6), CondenserLayout.INSTANCE.facingForTurbine(state.getValue(PumpAssemblyBlock.FACING)), root);
    }
    public static BlockPlaceContext align(BlockPlaceContext context) {
        if (context instanceof Snapped) return context;
        var clicked = context.replacingClickedOnBlock() ? context.getClickedPos()
                : context.getClickedPos().relative(context.getClickedFace().getOpposite());
        var attachment = attachment(context.getLevel(), new BlockHitResult(Vec3.atCenterOf(clicked),
                context.getClickedFace(), clicked, false));
        return attachment == null ? context : new Snapped(context, attachment);
    }
    public static boolean anchorReady(BlockPlaceContext context) {
        return !(context instanceof Snapped snapped) || snapped.attachment.complete(context.getLevel());
    }
    private static final class Snapped extends BlockPlaceContext {
        private final Attachment attachment;
        Snapped(BlockPlaceContext original, Attachment attachment) {
            super(original.getLevel(), original.getPlayer(), original.getHand(), original.getItemInHand(),
                    new BlockHitResult(Vec3.atCenterOf(attachment.root()), Direction.UP, attachment.root(), false));
            this.attachment = attachment;
        }
        @Override public Direction getHorizontalDirection() {
            return attachment == null ? Direction.SOUTH : attachment.facing().getOpposite();
        }
        // Never let vanilla move a blocked anchor to the adjacent block.
        @Override public BlockPos getClickedPos() {
            return attachment == null ? super.getClickedPos() : attachment.root();
        }
        @Override public boolean canPlace() {
            return getLevel().isLoaded(getClickedPos()) && getLevel().getBlockState(getClickedPos()).canBeReplaced();
        }
    }
}
