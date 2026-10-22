package dev.bwr.mod.radiation;

import dev.bwr.core.PhysicalConstants;
import dev.bwr.core.ReactorCore;
import dev.bwr.core.accident.ContainmentAtmosphere;
import dev.bwr.core.boundary.BoundaryComponent;
import dev.bwr.core.radiation.DoseModel;
import dev.bwr.core.thermal.PressureVessel;
import dev.bwr.mod.reactor.FormedReactorRegistry;
import dev.bwr.mod.reactor.ReactorControllerBlockEntity;
import dev.bwr.mod.reactor.ReactorSevereAccidentEffects;
import dev.bwr.mod.reactor.ReactorStructure;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * The dose rate at a point, in mSv/h, read from the plant's existing models and nothing else.
 *
 * <p>Four reactor terms, then whatever {@link RadiationSources} adds:
 * <ul>
 *   <li><b>airborne</b>: activity airborne in containment, through the same in-plant hazard and distance falloff the game
 *       applies to players ({@code ReactorSevereAccidentEffects.plantHazardAt});</li>
 *   <li><b>deposited</b>: activity settled on plant surfaces, decaying with the contamination half-life, same falloff;</li>
 *   <li><b>release</b>: the decaying environmental release, through the site hazard and its wider falloff;</li>
 *   <li><b>core</b>: gamma from the fuel itself, running or shut down, behind its shielding (see {@link DoseModel}): the
 *       vessel and biological shield beside it, only the water above it when you look down an open vessel.</li>
 * </ul>
 * The conversion from the model's fraction-of-inventory hazard to mSv/h is {@link DoseModel#MSV_PER_H_PER_INVENTORY}.
 * Contamination uses the distance to the reactor controller and stops at the model's own
 * {@code MAX_DOSE_DISTANCE_BLOCKS}, so a reading and the effects on a player can never disagree.
 *
 * <p>Cost: a loop over the formed reactors of the level (a handful) and the registered sources, about a dozen
 * multiplications each. Nothing here scans blocks or entities, and nothing here is called per tick: the survey meter
 * samples four times a second for its holder, the area monitor at most once per five ticks per monitor.
 */
public final class RadiationField {
    private RadiationField() {
    }

    /** What a reading is made of, each term in mSv/h. */
    public record Reading(double airborne, double deposited, double release, double core, double other) {
        public static final Reading ZERO = new Reading(0, 0, 0, 0, 0);

        public double total() {
            double t = airborne + deposited + release + core + other;
            return t > 0.0 && Double.isFinite(t) ? t : 0.0;
        }
    }

    /** The total dose rate at {@code at}, mSv/h. Zero on a client. */
    public static double doseRateMsvPerHour(Level level, Vec3 at) {
        return read(level, at).total();
    }

    /** The dose rate at {@code at} broken down by source, mSv/h. All zero on a client. */
    public static Reading read(Level level, Vec3 at) {
        if (!(level instanceof ServerLevel server) || at == null) {
            return Reading.ZERO;
        }
        double airborne = 0, deposited = 0, release = 0, core = 0;
        for (ReactorControllerBlockEntity reactor : FormedReactorRegistry.controllers(server)) {
            ReactorCore physics = reactor.core();
            if (physics == null) {
                continue;
            }
            BlockPos pos = reactor.getBlockPos();
            double d2 = at.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
            ContainmentAtmosphere containment = physics.getContainment();
            double limit = ReactorSevereAccidentEffects.MAX_DOSE_DISTANCE_BLOCKS;
            if (d2 <= limit * limit) {
                double air = containment.getAirborneFraction();
                double plant = containment.getPlantContaminationIndex();
                airborne += DoseModel.doseRateFromHazard(ReactorSevereAccidentEffects.plantHazardAt(air, d2));
                deposited += DoseModel.doseRateFromHazard(
                        ReactorSevereAccidentEffects.plantHazardAt(Math.max(0.0, plant - air), d2));
                release += DoseModel.doseRateFromHazard(
                        ReactorSevereAccidentEffects.siteHazardAt(containment.getSiteContaminationIndex(), d2));
                core += coreDoseRate(reactor, physics, at);
            }
        }
        double other = RadiationSources.doseRateMsvPerHour(level, at);
        return new Reading(airborne, deposited, release, core, other);
    }

    /** Gamma from the fuel of one formed reactor at {@code at}, mSv/h. */
    public static double coreDoseRate(ReactorControllerBlockEntity reactor, ReactorCore core, Vec3 at) {
        double decay = core.getDecayHeatMW();
        double gamma = DoseModel.coreGammaWatts(Math.max(0.0, core.getThermalPowerMW() - decay), decay);
        if (!(gamma > 0.0)) {
            return 0.0;
        }
        ReactorStructure structure = reactor.structure();
        Vec3 centre;
        boolean overOpening = false;
        double waterAboveFuel = 0.0;
        if (structure != null) {
            BlockPos min = structure.interiorMin();
            BlockPos max = structure.interiorMax();
            double top = structure.topOfActiveFuelY() + 1.0;
            double height = PressureVessel.ACTIVE_FUEL_HEIGHT_IN * 0.0254;
            centre = new Vec3((min.getX() + max.getX() + 1) / 2.0, top - height / 2.0, (min.getZ() + max.getZ() + 1) / 2.0);
            boolean headOpen = !reactor.vesselState().canHoldPressure()
                    || core.getBoundaryStress().isBroken(BoundaryComponent.REACTOR_VESSEL_HEAD);
            overOpening = headOpen && at.x >= min.getX() && at.x <= max.getX() + 1 && at.z >= min.getZ()
                    && at.z <= max.getZ() + 1 && at.y > top;
            waterAboveFuel = Math.max(0.0,
                    (core.getCollapsedLevelIn() - PhysicalConstants.TAF_ON_INSTRUMENT_SCALE_IN) * 0.0254);
        } else {
            BlockPos pos = reactor.getBlockPos();
            centre = new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        }
        double covered = 1.0 - core.getUncoveredFuelFraction();
        double transmission = overOpening
                ? DoseModel.openHeadTransmission(waterAboveFuel)
                : DoseModel.lateralTransmission(covered, core.getSevereAccident().isLowerHeadFailed());
        return DoseModel.pointSourceMsvPerH(gamma, at.distanceTo(centre), transmission);
    }
}
