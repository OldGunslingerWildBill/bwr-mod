package dev.bwr.mod.refuelling;

import dev.bwr.core.radiation.DoseModel;
import dev.bwr.core.refuelling.BridgeMotion;
import dev.bwr.mod.radiation.BlockShielding;
import dev.bwr.mod.radiation.RadiationSources;
import dev.bwr.mod.spentfuel.SpentFuelPoolBlockEntity;
import dev.bwr.mod.spentfuel.SpentFuelPoolRegistry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * The spent fuel pool's stored fuel as a radiation source ({@link RadiationSources}): each rack that holds fuel is a point
 * source at the middle of its top, with the gamma share of the decay heat of the assemblies in it, behind the water over the
 * rack top ({@link BridgeMotion#transmission}: the reactor model's {@code e^(-7.07 x depth)}; no shielding when the rack
 * is uncovered); a point inside the pool's water is shielded by the water along the line to the rack top instead. One provider for every pool in the level, called a few times a second by an instrument and never per tick:
 * its work is the racks of the pools within range. A pool with more than {@value SpentFuelPoolBlockEntity#EXACT_DOSE_SOURCES} racks
 * holding fuel (up to 2,048) gives its far racks lumped by region ({@link SpentFuelPoolBlockEntity#forEachDoseSource}), so a reading
 * marches a few hundred rays at most, not one per rack.
 *
 * <p>The numbers are the ones the pool already keeps: {@code SpentFuelPoolBlockEntity} refreshes each assembly's decay heat
 * once a second, so a reading follows the cooling, and a pool that has boiled down to the rack tops reads thousands of times
 * higher with no change in the fuel.
 */
public final class PoolRadiation {
    private PoolRadiation() {
    }

    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("bwr", "spent_fuel_pool");

    public static void register() {
        RadiationSources.register(ID, PoolRadiation::doseRate);
    }

    /** Dose rate at {@code at} from the stored fuel of every formed pool of the level, mSv/h. */
    static double doseRate(Level level, Vec3 at) {
        if (level.isClientSide()) {
            return 0.0;
        }
        double[] total = {0.0};
        for (SpentFuelPoolBlockEntity pool : SpentFuelPoolRegistry.controllers(level)) {
            if (!pool.isFormed() || pool.layout() == null) {
                continue;
            }
            var l = pool.layout();
            // out of reach of the whole pool: skip its racks (the pool is at most 64 blocks across)
            double dx = Math.max(Math.max(l.min().getX() - at.x, at.x - l.max().getX() - 1), 0.0);
            double dz = Math.max(Math.max(l.min().getZ() - at.z, at.z - l.max().getZ() - 1), 0.0);
            if (dx > DoseModel.MAX_RANGE_M || dz > DoseModel.MAX_RANGE_M) {
                continue;
            }
            // A point in the pool's water is shielded by the water on the line between it and the rack top, with no credit for the water
            // above it (DoseModel.waterPathM); a point out of the water keeps the old rule, the water straight over the rack top.
            final boolean swimming = pool.inWater(at.x, at.y, at.z);
            final double surface = pool.surfaceY();
            // the pool's own walls and everything inside them are in the pool's numbers: not counted on the line
            final BlockShielding.Box own = BlockShielding.Box.of(l.min(), l.max());
            pool.forEachDoseSource(at.x, at.y, at.z, (x, topY, z, decayW, cover) -> {
                double gamma = BridgeMotion.gammaWatts(decayW);
                Vec3 source = new Vec3(x, topY, z);
                double distance = at.distanceTo(source);
                double water = swimming ? DoseModel.waterPathM(distance, topY, at.y, surface, Double.NEGATIVE_INFINITY) : cover;
                // the pool's water is in the transmission already; other blocks on the line, outside the pool, still shield (BlockShielding)
                total[0] += BlockShielding.pointSource(level, source, at, gamma, BridgeMotion.transmission(water), true, own);
            });
        }
        return total[0];
    }
}
