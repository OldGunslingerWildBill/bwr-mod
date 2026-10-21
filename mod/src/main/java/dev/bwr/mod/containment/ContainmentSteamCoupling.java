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
        double pressurePsia = Math.max(0.1, atmosphere.getDrywellPressurePsig()
                + ContainmentAtmosphere.ATMOSPHERIC_PA / ContainmentAtmosphere.PA_PER_PSI);
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
        double drySprayFlow = 0.0, drySprayMoment = 0.0;
        double wetSprayFlow = 0.0, wetSprayMoment = 0.0;
        if (wetwell != null) {
            wetwell.pool().setContainmentPressurePsia(Math.max(0.1, atmosphere.getWetwellPressurePsig()
                    + ContainmentAtmosphere.ATMOSPHERIC_PA / ContainmentAtmosphere.PA_PER_PSI));
            double captured = atmosphere.getLastPoolSurfaceCondensedKgPerS() * .05;
            double evaporated = atmosphere.getLastPoolEvaporatedKgPerS();
            if (captured > 0) {
                wetwell.pool().addHeatMJ(captured * ContainmentAtmosphere.STEAM_LATENT_HEAT_J_PER_KG / 1_000_000);
                wetwell.pool().addWaterKg(captured, wetwell.pool().getTemperatureC());
            }
            if (evaporated > 0) {
                wetwell.pool().drawSuctionKg(evaporated, .05);
                wetwell.pool().addHeatMJ(-evaporated * .05
                        * ContainmentAtmosphere.STEAM_LATENT_HEAT_J_PER_KG / 1_000_000);
            }
            wetSteam = wetwell.escapedSteamKgPerS(now);
            poolK = wetwell.pool().getTemperatureC() + 273.15;
            // The atmosphere's pressure-driven downcomer discharge from the
            // preceding reactor step reaches the pool this tick. Its escaped
            // share enters wetwell airspace on the following tick.
            wetwell.reportSteamKgPerS(reactor.getBlockPos(), now,
                    atmosphere.getLastDowncomerSteamKgPerS(), core.getPressurePsig());
        }
        double drySteam = breakSteam;
        if (room != null) for (BlockPos pos : room.linkedSprayHeaders()) {
            if (!(dev.bwr.mod.world.LoadedWorld.blockEntity(level, pos) instanceof ContainmentSprayHeaderBlockEntity header))
                continue;
            var sample = header.takeForRoom(now, .05);
            if (wetwell != null && wetwell.isWetwellSprayHeader(pos)) {
                wetSprayFlow += sample.flowKgPerS();
                wetSprayMoment += sample.flowKgPerS() * sample.temperatureC();
            } else {
                drySprayFlow += sample.flowKgPerS();
                drySprayMoment += sample.flowKgPerS() * sample.temperatureC();
            }
        }
        double drySprayK = drySprayFlow > 0 ? drySprayMoment / drySprayFlow + 273.15 : 0;
        double wetSprayK = wetSprayFlow > 0 ? wetSprayMoment / wetSprayFlow + 273.15 : 0;
        if (wetwell != null && wetSprayFlow > 0)
            wetwell.pool().addWaterKg(wetSprayFlow * .05, wetSprayK - 273.15);
        if (atmosphere.getLeakArea() >= 1.0) {
            drySteam = 0.0;
            wetSteam = 0.0;
            poolK = 0.0;
            drySprayFlow = wetSprayFlow = 0.0;
            drySprayK = wetSprayK = 0.0;
        }
        atmosphere.setSteamInputs(new ContainmentAtmosphere.SteamInputs(
                drySteam, Saturation.vapourEnthalpyKJPerKg(vesselPsia),
                wetSteam, poolK, drySprayFlow, drySprayK, wetSprayFlow, wetSprayK));
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
