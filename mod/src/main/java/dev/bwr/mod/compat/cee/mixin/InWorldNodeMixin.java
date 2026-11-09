package dev.bwr.mod.compat.cee.mixin;

import com.george_vi.electroenergetics.foundation.nodes.InWorldNode;
import dev.bwr.mod.grid.GridTieBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * CEE picks a node only among the nodes of the block under the crosshair, nearest the hit point within its threshold. The Grid Tie's six
 * nodes belong to its controller cell, so a click on any other assembled cell (a flange cell, say) searches the controller's nodes. Covers
 * the client's hover highlight and every server-side closestNode caller (the spools, node renaming). It tests only bwr's own block, so it
 * coexists with the same redirect CEE Grid does for its transformers.
 */
@Mixin(value = InWorldNode.class, remap = false)
public abstract class InWorldNodeMixin {

    @Inject(method = "closestNode(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;FLnet/minecraft/world/phys/Vec3;)Lcom/george_vi/electroenergetics/foundation/nodes/InWorldNode;",
            at = @At("HEAD"), cancellable = true)
    private static void bwr$throughGridTieCells(Level level, BlockPos pos, BlockState state, float threshold, Vec3 clicked,
                                                CallbackInfoReturnable<InWorldNode> cir) {
        if (state.getBlock() instanceof GridTieBlock tie && state.getValue(GridTieBlock.ASSEMBLED) && state.getValue(GridTieBlock.CELL) != GridTieBlock.CONTROLLER) {
            BlockPos c = tie.origin(pos, state);
            BlockState cs = level.getBlockState(c);
            boolean ours = cs.getBlock() == tie && cs.getValue(GridTieBlock.ASSEMBLED) && cs.getValue(GridTieBlock.CELL) == GridTieBlock.CONTROLLER
                    && cs.getValue(GridTieBlock.FACING) == state.getValue(GridTieBlock.FACING);
            cir.setReturnValue(ours ? InWorldNode.closestNode(level, c, cs, threshold, clicked) : null);
        }
    }
}
