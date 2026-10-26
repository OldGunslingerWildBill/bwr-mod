package dev.bwr.mod.refuelling;

import dev.bwr.mod.accident.ShieldPlugConcreteBlock;
import dev.bwr.mod.text.BwrText;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;

/**
 * The Plug Seam Marker: cuts a shield plug into separately liftable sections (BUILDING-CRANE.md).
 *
 * <p>Right-click a plug block <b>near the edge where you want the cut</b>: the joint between that block and the plug block on the
 * other side of that edge becomes a seam (a groove is drawn along it), so the crane no longer takes the two together. Click the same
 * place again and the seam is gone. Sneak-right-click a block to clear every seam on it. Nothing else changes: the blocks stay where
 * they are and are not harmed, and the marker is not used up.
 */
public class PlugSeamMarkerItem extends Item {

    public PlugSeamMarkerItem(Properties properties) {
        super(properties);
    }

    /** Which edge of the clicked face the click was nearest: the side whose joint the player means. */
    public static Direction edgeOf(Direction face, Vec3 local) {
        Direction best = null;
        double bestD = Double.MAX_VALUE;
        for (Direction d : Direction.values()) {
            if (d.getAxis() == face.getAxis()) {
                continue;
            }
            double c = switch (d.getAxis()) {
                case X -> local.x;
                case Y -> local.y;
                case Z -> local.z;
            };
            double dist = d.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1.0 - c : c;
            if (dist < bestD - 1e-9) {
                bestD = dist;
                best = d;
            }
        }
        return best;
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof ShieldPlugConcreteBlock)) {
            return InteractionResult.PASS;
        }
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        var player = context.getPlayer();
        if (player != null && player.isShiftKeyDown()) {
            int cleared = clearSeams(level, pos);
            player.displayClientMessage(cleared == 0
                    ? BwrText.tr("status.bwr.plug_seam.none_to_clear", "That plug block has no seams.")
                    : BwrText.tr("status.bwr.plug_seam.cleared", "Cleared %s seam(s) on that plug block.", cleared), true);
            return InteractionResult.CONSUME;
        }
        Vec3 local = context.getClickLocation().subtract(pos.getX(), pos.getY(), pos.getZ());
        Direction side = edgeOf(context.getClickedFace(), local);
        Boolean now = toggle(level, pos, side);
        if (player != null) {
            player.displayClientMessage(now == null
                    ? BwrText.tr("status.bwr.plug_seam.no_neighbour", "A seam goes between two plug blocks, and there is no shield plug block on the %s side of this one.", side.getName())
                    : now ? BwrText.tr("status.bwr.plug_seam.added", "Seam marked on the %s side: the crane will take the blocks either side of it separately.", side.getName())
                    : BwrText.tr("status.bwr.plug_seam.removed", "Seam removed on the %s side.", side.getName()), true);
        }
        return InteractionResult.CONSUME;
    }

    /**
     * Toggles the seam between the plug block at {@code pos} and the plug block on {@code side} of it (both blocks carry it). Returns the new
     * state, or null when there is no plug block on that side. Only block states change; no block is moved or notified.
     */
    @Nullable
    public static Boolean toggle(Level level, BlockPos pos, Direction side) {
        BlockPos other = pos.relative(side);
        BlockState a = level.getBlockState(pos);
        BlockState b = level.getBlockState(other);
        if (!(a.getBlock() instanceof ShieldPlugConcreteBlock) || !(b.getBlock() instanceof ShieldPlugConcreteBlock)) {
            return null;
        }
        boolean now = !(ShieldPlugConcreteBlock.seamed(a, side) || ShieldPlugConcreteBlock.seamed(b, side.getOpposite()));
        level.setBlock(pos, a.setValue(ShieldPlugConcreteBlock.seamProperty(side), now), 2);
        level.setBlock(other, b.setValue(ShieldPlugConcreteBlock.seamProperty(side.getOpposite()), now), 2);
        return now;
    }

    /** Clears every seam on the plug block at {@code pos}, and the matching face of each neighbour. Returns how many joints were cleared. */
    public static int clearSeams(Level level, BlockPos pos) {
        int cleared = 0;
        for (Direction d : Direction.values()) {
            BlockState a = level.getBlockState(pos);
            BlockPos other = pos.relative(d);
            BlockState b = level.getBlockState(other);
            boolean had = ShieldPlugConcreteBlock.seamed(a, d);
            if (had) {
                level.setBlock(pos, a.setValue(ShieldPlugConcreteBlock.seamProperty(d), false), 2);
            }
            if (ShieldPlugConcreteBlock.seamed(b, d.getOpposite())) {
                level.setBlock(other, b.setValue(ShieldPlugConcreteBlock.seamProperty(d.getOpposite()), false), 2);
                had = true;
            }
            if (had) {
                cleared++;
            }
        }
        return cleared;
    }
}
