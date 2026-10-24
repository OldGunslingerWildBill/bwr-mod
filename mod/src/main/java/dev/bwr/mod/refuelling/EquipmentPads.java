package dev.bwr.mod.refuelling;

import dev.bwr.mod.reactor.VesselInternal;
import dev.bwr.mod.reactor.VesselProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;

/** Sizes of the stored assemblies and the choice of a free pad for the bridge. */
public final class EquipmentPads {
    private EquipmentPads() {
    }

    public static double dryerHeight(VesselProfile p) {
        return p.dryerLift() - p.dryerBottom();
    }

    public static double separatorHeight(VesselProfile p) {
        return p.separatorLift() - p.separatorBottom();
    }

    /** Lifting point above the lowest point of an assembly, blocks. */
    public static double liftAboveBottom(VesselInternal which, VesselProfile p) {
        return which == VesselInternal.DRYER ? dryerHeight(p) : separatorHeight(p);
    }

    /** The free pad the bridge reaches that is nearest the vessel's centre, or null. */
    @Nullable
    public static BlockPos nearestFree(Level level, BridgeSite site, double fromX, double fromZ) {
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos p : EquipmentPadBlockEntity.pads(level)) {
            if (!level.isLoaded(p) || !(level.getBlockEntity(p) instanceof EquipmentPadBlockEntity pad) || pad.stored() != null) continue;
            if (!site.reaches(p.getX() + .5, p.getZ() + .5)) continue;
            double d = Math.hypot(p.getX() + .5 - fromX, p.getZ() + .5 - fromZ);
            if (d < bestD) {
                bestD = d;
                best = p;
            }
        }
        return best;
    }
}
