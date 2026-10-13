package dev.bwr.mod.containment;

import dev.bwr.core.accident.ContainmentAtmosphere;
import dev.bwr.core.thermal.Saturation;
import dev.bwr.mod.reactor.ReactorControllerBlockEntity;
import dev.bwr.mod.suppression.SuppressionPoolBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/** Routes measured break flow through a room's wetwell without loading chunks. */
public final class ContainmentSteamCoupling {
    private ContainmentSteamCoupling() {}

    public static void beforeReactorStep(ReactorControllerBlockEntity reactor,
                                         ContainmentControllerBlockEntity room) {
        var core = reactor.core();
        if (core == null) return;
        var atmosphere = core.getContainment();
        Level level = reactor.getLevel();
        if (level == null) return;
        long now = level.getGameTime();
        SuppressionPoolBlockEntity wetwell = wetwell(level, reactor.getBlockPos(), room);
        double pressurePsia = Math.max(0.1, atmosphere.getPressurePsia());
        double vesselPsia = Saturation.psiaFromPsig(core.getPressurePsig());
        // A recirculation-line break discharges hot liquid. The fraction that
        // flashes at containment pressure joins the steam-space break flow.
        double flash = Math.clamp((Saturation.liquidEnthalpyKJPerKg(vesselPsia)
                - Saturation.liquidEnthalpyKJPerKg(pressurePsia))
                / Math.max(1.0, Saturation.latentHeatKJPerKg(pressurePsia)), 0.0, 1.0);
        double breakSteam = Math.max(0.0, core.getBreakSteamFlowKgPerS()
                + core.getBreakLiquidFlowKgPerS() * flash);
        if (core.getBoundaryStress().isInterfacingSystemsLoca()) breakSteam = 0.0;
        double wetSteam = 0.0, poolK = 0.0;
        double sprayFlow = 0.0, sprayTemperatureMoment = 0.0;
        if (wetwell != null) {
            wetwell.pool().setContainmentPressurePsia(pressurePsia);
            wetSteam = wetwell.escapedSteamKgPerS(now);
            poolK = wetwell.pool().getTemperatureC() + 273.15;
            // The drywell downcomer transit is one tick at this resolution.
            // A fraction of the blowdown contacts drywell structures before the
            // downcomers. The remainder enters the pool, whose uncondensed
            // share returns to the common airspace. No mass is counted twice.
            wetwell.reportSteamKgPerS(reactor.getBlockPos(), now,
                    breakSteam * (1.0 - ContainmentAtmosphere.DRYWELL_WALL_CONTACT_FRACTION), core.getPressurePsig());
        }
        double drySteam = wetwell == null ? breakSteam
                : breakSteam * ContainmentAtmosphere.DRYWELL_WALL_CONTACT_FRACTION;
        if (room != null) for (BlockPos pos : room.linkedSprayHeaders()) {
            if (!level.isLoaded(pos) || !(level.getBlockEntity(pos) instanceof ContainmentSprayHeaderBlockEntity header))
                continue;
            var sample = header.takeForRoom(now, .05);
            sprayFlow += sample.flowKgPerS();
            sprayTemperatureMoment += sample.flowKgPerS() * sample.temperatureC();
        }
        double sprayK = sprayFlow > 0.0 ? sprayTemperatureMoment / sprayFlow + 273.15 : 0.0;
        if (wetwell != null && sprayFlow > 0)
            wetwell.pool().addWaterKg(sprayFlow * .05, sprayK - 273.15);
        if (atmosphere.getLeakArea() >= 1.0) {
            drySteam = 0.0;
            wetSteam = 0.0;
            poolK = 0.0;
            sprayFlow = 0.0;
            sprayK = 0.0;
        }
        atmosphere.setSteamInputs(new ContainmentAtmosphere.SteamInputs(
                drySteam, Saturation.vapourEnthalpyKJPerKg(vesselPsia),
                wetSteam, poolK, sprayFlow, sprayK));
    }

    private static SuppressionPoolBlockEntity wetwell(Level level, BlockPos reactor,
                                                       ContainmentControllerBlockEntity room) {
        if (room == null) return null;
        SuppressionPoolBlockEntity best = null;
        double distance = Double.POSITIVE_INFINITY;
        for (BlockPos pos : room.linkedPools()) {
            if (!level.isLoaded(pos)) continue;
            if (!(level.getBlockEntity(pos) instanceof SuppressionPoolBlockEntity pool) || !pool.isFormed())
                continue;
            double candidate = pos.distSqr(reactor);
            if (candidate < distance) { best = pool; distance = candidate; }
        }
        return best;
    }
}
