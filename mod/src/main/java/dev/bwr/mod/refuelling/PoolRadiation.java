package dev.bwr.mod.refuelling;

import dev.bwr.core.radiation.DoseModel;
import dev.bwr.core.refuelling.BridgeMotion;
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
 * is uncovered). One provider for every pool in the level, called a few times a second by an instrument and never per tick:
 * its work is the racks of the pools within range.
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
            // out of reach of the whole pool: skip its racks (the pool is at most 25 blocks across)
            double dx = Math.max(Math.max(l.min().getX() - at.x, at.x - l.max().getX() - 1), 0.0);
            double dz = Math.max(Math.max(l.min().getZ() - at.z, at.z - l.max().getZ() - 1), 0.0);
            if (dx > DoseModel.MAX_RANGE_M || dz > DoseModel.MAX_RANGE_M) {
                continue;
            }
            pool.forEachRackSource((x, topY, z, decayW, cover) -> {
                double gamma = BridgeMotion.gammaWatts(decayW);
                total[0] += DoseModel.pointSourceMsvPerH(gamma, at.distanceTo(new Vec3(x, topY, z)), BridgeMotion.transmission(cover));
            });
        }
        return total[0];
    }
}
