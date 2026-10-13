package dev.bwr.core.accident;

import dev.bwr.core.Check;
import dev.bwr.core.CoreConfig;
import dev.bwr.core.ReactorCore;
import dev.bwr.core.boundary.BoundaryComponent;
import dev.bwr.core.boundary.BoundaryStress;
import dev.bwr.core.pool.RhrHeatExchanger;
import dev.bwr.core.pool.SuppressionPool;
import dev.bwr.core.thermal.PressureVessel;
import dev.bwr.core.thermal.Saturation;

import java.util.Arrays;

/** Operator-driven whole-plant transients using the shipped pool, gas, vessel and RHR models. */
public final class RhrContainmentScenarioTest {
    private RhrContainmentScenarioTest() {}

    private record State(double[] pool, double[] atmosphere, double peakDrywellPsig,
                         double peakWetwellPsig, double peakSteamKgPerS) {}

    /** Scrammed reactor, guillotine recirculation break, and an operator lined up ECCS pump. */
    private static State designBasisBlowdown(boolean print) {
        ReactorCore core = new ReactorCore(new CoreConfig());
        core.initialiseAtTotalPowerFraction(1.0);
        core.scram();
        core.setRecirculationFlowFraction(0);
        core.setTurbineSteamFlowKgPerS(0);
        core.setBypassSteamFlowKgPerS(0);
        core.setFeedwaterFlowKgPerS(0);
        core.getBoundaryStress().forceFailure(BoundaryComponent.RECIRCULATION_LINE, core.getPressurePsig());
        SuppressionPool pool = new SuppressionPool();
        var gas = core.getContainment();
        double peakDry = gas.getDrywellPressurePsig(), peakWet = gas.getWetwellPressurePsig();
        double peakFlow = 0, priorBreak = 0;
        final double dt = .25;
        if (print) Check.note("DBA t=0 s: RPV %.1f psig, pool %.1f C, drywell %.1f psig, wetwell %.1f psig",
                core.getPressurePsig(), pool.getTemperatureC(), peakDry, peakWet);
        for (int i = 1; i <= 1200; i++) {
            pool.setContainmentPressurePsia(gas.getWetwellPressurePsig() + ContainmentAtmosphere.ATMOSPHERIC_PA
                    / ContainmentAtmosphere.PA_PER_PSI);
            pool.condenseSteam(gas.getLastDowncomerSteamKgPerS(), core.getPressurePsig(), dt);
            gas.setSteamInputs(new ContainmentAtmosphere.SteamInputs(priorBreak, 2800,
                    pool.getUncondensedSteamKgPerS(), pool.getTemperatureC() + 273.15, 0, 0));
            core.setInjectionFlowKgPerS(2000);
            core.setInjectionTemperatureC(pool.getTemperatureC());
            core.step(dt);
            double vesselP = Saturation.psiaFromPsig(core.getPressurePsig());
            double containmentP = Math.max(.1, gas.getDrywellPressurePsig()
                    + ContainmentAtmosphere.ATMOSPHERIC_PA / ContainmentAtmosphere.PA_PER_PSI);
            double flash = Math.clamp((Saturation.liquidEnthalpyKJPerKg(vesselP)
                    - Saturation.liquidEnthalpyKJPerKg(containmentP))
                    / Math.max(1, Saturation.latentHeatKJPerKg(containmentP)), 0, 1);
            priorBreak = Math.max(0, core.getBreakSteamFlowKgPerS()
                    + core.getBreakLiquidFlowKgPerS() * flash);
            peakFlow = Math.max(peakFlow, priorBreak);
            peakDry = Math.max(peakDry, gas.getPeakDrywellPressurePsig());
            peakWet = Math.max(peakWet, gas.getPeakWetwellPressurePsig());
            if (print && (i == 120 || i == 240 || i == 480 || i == 1200))
                Check.note("DBA t=%d s: RPV %.1f psig, break %.0f kg/s, pool %.1f C, drywell %.1f psig, wetwell %.1f psig",
                        (int) (i * dt), core.getPressurePsig(), priorBreak,
                        pool.getTemperatureC(), gas.getDrywellPressurePsig(), gas.getWetwellPressurePsig());
        }
        return new State(pool.toArray(), gas.toArray(), peakDry, peakWet, peakFlow);
    }

    public static void test01_designBasisRecirculationLoca() {
        State result = designBasisBlowdown(true);
        SuppressionPool pool = new SuppressionPool(); pool.fromArray(result.pool());
        ContainmentAtmosphere gas = new ContainmentAtmosphere(); gas.fromArray(result.atmosphere());
        Check.greaterThan(500, result.peakSteamKgPerS(), "large recirculation break sends steam to suppression pool");
        Check.greaterThan(32, pool.getTemperatureC(), "suppression pool takes blowdown heat");
        Check.greaterThan(0, pool.getSubcoolingC(), "pool remains subcooled at DBA endpoint");
        Check.lessThan(gas.getFailurePressurePsig(), result.peakDrywellPsig(), "drywell peak below structural failure");
        // NRC/GE Mark I Browns Ferry references: 49.6 psig drywell, 27 psig
        // suppression chamber. Broad tolerances acknowledge a lumped vent and
        // heat-transfer model while requiring both volumes to be resolved.
        Check.inRange(40, 60, result.peakDrywellPsig(), "cited Mark I drywell DBA peak");
        Check.inRange(20, 35, result.peakWetwellPsig(), "cited Mark I wetwell DBA peak");
        Check.note("DBA peak: drywell %.1f, wetwell %.1f psig; break %.0f kg/s; pool %.1f C, subcooling %.1f C",
                result.peakDrywellPsig(), result.peakWetwellPsig(), result.peakSteamKgPerS(),
                pool.getTemperatureC(), pool.getSubcoolingC());
    }

    private record Tail(double peakP, double finalP, double poolC, double preVentP,
                        double peakDry, double peakWet, double finalDry, double finalWet) {}

    /** Tail heat is an explicit decaying steam source from an injected scrammed vessel. */
    private static Tail runTail(State start, boolean rhr, boolean vent, String name) {
        SuppressionPool pool = new SuppressionPool(); pool.fromArray(start.pool());
        ContainmentAtmosphere gas = new ContainmentAtmosphere(); gas.fromArray(start.atmosphere());
        // Keep the three operator lineups observable even if containment would fail.
        gas.setFailurePressurePsig(1000);
        double peak = gas.getPressurePsig(), beforeVent = Double.NaN;
        double peakDry = gas.getPeakDrywellPressurePsig(), peakWet = gas.getPeakWetwellPressurePsig();
        final double dt = 30;
        for (int i = 1; i <= 2880; i++) {
            double h = i * dt / 3600.0;
            double steam = 8 + 28 * Math.exp(-h / 4.0);
            if (vent && i == 1441) { beforeVent = gas.getPressurePsig(); gas.setVentOpen(true); }
            // Pool suction feeds the vessel; steam comes back through the downcomer.
            pool.drawSuctionKg(steam, dt);
            pool.setContainmentPressurePsia(gas.getPressurePsia());
            pool.condenseSteam(steam, 150, dt);
            if (rhr) pool.coolWithRhr(1, RhrHeatExchanger.RATING_MW, RhrHeatExchanger.COLD_C, dt);
            gas.setSteamInputs(new ContainmentAtmosphere.SteamInputs(0, 0,
                    pool.getUncondensedSteamKgPerS(), pool.getTemperatureC() + 273.15, 0, 0));
            gas.step(0, 0, 0, dt);
            peak = Math.max(peak, gas.getPressurePsig());
            peakDry = Math.max(peakDry, gas.getPeakDrywellPressurePsig());
            peakWet = Math.max(peakWet, gas.getPeakWetwellPressurePsig());
            if (i == 1 || i == 720 || i == 1440 || i == 2160 || i == 2880)
                Check.note("24h %s t=%.0f h: pool %.1f C, suppression %.2f, drywell %.1f / wetwell %.1f psig",
                        name, h, pool.getTemperatureC(), pool.getLastCondensationEffectiveness(),
                        gas.getDrywellPressurePsig(), gas.getWetwellPressurePsig());
        }
        return new Tail(peak, gas.getPressurePsig(), pool.getTemperatureC(), beforeVent,
                peakDry, peakWet, gas.getDrywellPressurePsig(), gas.getWetwellPressurePsig());
    }

    public static void test02_twentyFourHourLocaRhrAndVenting() {
        State start = designBasisBlowdown(false);
        Tail noRhr = runTail(start, false, false, "no RHR");
        Tail cooled = runTail(start, true, false, "RHR");
        Tail vented = runTail(start, false, true, "vent at 12h");
        Check.greaterThan(cooled.poolC() + 10, noRhr.poolC(), "RHR bounds suppression pool temperature");
        Check.greaterThan(cooled.peakP(), noRhr.peakP(), "RHR bounds pressure");
        Check.lessThan(vented.preVentP(), vented.finalP(), "wetwell vent reduces pressure after opening");
        Check.note("24h peaks: no RHR dry %.1f/wet %.1f psig, pool %.1f C; RHR dry %.1f/wet %.1f psig, pool %.1f C; vent final dry %.1f/wet %.1f psig",
                noRhr.peakDry(), noRhr.peakWet(), noRhr.poolC(),
                cooled.peakDry(), cooled.peakWet(), cooled.poolC(), vented.finalDry(), vented.finalWet());
    }

    public static void test03_stationBlackoutRcicSrvAndPowerRestoration() {
        SuppressionPool pool = new SuppressionPool();
        ContainmentAtmosphere gas = new ContainmentAtmosphere();
        gas.setFailurePressurePsig(1000);
        double initial = gas.getPressurePsig(), preRhr = 0, peak = initial;
        final double dt = 30;
        for (int i = 1; i <= 1080; i++) {
            double h = i * dt / 3600.0;
            double exhaustSteam = h <= 6 ? 35 : 12; // RCIC drive and SRV decay-heat discharge.
            pool.drawSuctionKg(exhaustSteam, dt);
            pool.setContainmentPressurePsia(gas.getPressurePsia());
            pool.condenseSteam(exhaustSteam, 150, dt);
            if (h > 6) pool.coolWithRhr(1, RhrHeatExchanger.RATING_MW, RhrHeatExchanger.COLD_C, dt);
            gas.setSteamInputs(new ContainmentAtmosphere.SteamInputs(0, 0,
                    pool.getUncondensedSteamKgPerS(), pool.getTemperatureC() + 273.15, 0, 0));
            gas.step(0, 0, 0, dt);
            peak = Math.max(peak, gas.getPressurePsig());
            if (i == 720) preRhr = gas.getPressurePsig();
            if (i == 1 || i == 240 || i == 480 || i == 720 || i == 840 || i == 1080)
                Check.note("SBO t=%.0f h %s: pool %.1f C, drywell %.1f / wetwell %.1f psig",
                        h, h <= 6 ? "RCIC/SRV" : "RHR restored", pool.getTemperatureC(),
                        gas.getDrywellPressurePsig(), gas.getWetwellPressurePsig());
        }
        Check.greaterThan(initial + 1, preRhr, "RCIC/SRV heating raises containment pressure");
        Check.lessThan(preRhr, gas.getPressurePsig(), "restored RHR turns pressure down");
        Check.note("SBO peaks drywell %.1f / wetwell %.1f psig; 6h %.1f psig; 9h %.1f psig; final pool %.1f C",
                gas.getPeakDrywellPressurePsig(), gas.getPeakWetwellPressurePsig(),
                preRhr, gas.getPressurePsig(), pool.getTemperatureC());
    }

    private record Cooldown(double hours, double peakDutyMW, double peakRateCPerHour,
                            double thermalStress) {}

    private static Cooldown runCooldown(double serviceWaterKgPerS, String name) {
        PressureVessel vessel = new PressureVessel(new CoreConfig());
        vessel.initialiseToNormalLevel();
        vessel.restorePressurePsig(130);
        RhrHeatExchanger exchanger = new RhrHeatExchanger();
        BoundaryStress boundary = new BoundaryStress();
        double start = vessel.getSaturationTemperatureC(), peakDuty = 0, peakRate = 0;
        double reachedSeconds = Double.NaN;
        Check.note("shutdown %s t=0 h: vessel %.1f C, metal %.1f C, pressure %.1f psig",
                name, start, vessel.getMetalTemperatureC(), vessel.getPressurePsig());
        // The former one-hour case was predicated on an uncited 100-MW single
        // exchanger. Keep the cold-shutdown endpoint, extend the observation
        // to the cited 24-hour operating horizon, and run at one-second steps.
        for (int i = 1; i <= 24 * 3600; i++) {
            exchanger.drainHot(serviceWaterKgPerS, false);
            exchanger.fillCold(serviceWaterKgPerS, false);
            double duty = exchanger.stepReactor(vessel.getSaturationTemperatureC(),
                    vessel.getLiquidMassKg(), 1500, 1.0);
            peakDuty = Math.max(peakDuty, duty);
            vessel.setShutdownCoolingMW(duty);
            vessel.step(20, 0, 0, 0, 0, 1.0);
            double rate = vessel.getCooldownRateCPerHour();
            peakRate = Math.max(peakRate, rate);
            boundary.recordCooldownRateCPerHour(rate, 1.0);
            if (i % 3600 == 0)
                Check.note("shutdown %s t=%d h: vessel %.1f C, metal %.1f C, pressure %.1f psig, HX %.1f MW",
                        name, i / 3600, vessel.getSaturationTemperatureC(),
                        vessel.getMetalTemperatureC(), vessel.getPressurePsig(), duty);
            if (vessel.getSaturationTemperatureC() < 100) { reachedSeconds = i; break; }
        }
        Check.isTrue(Double.isFinite(reachedSeconds), "operator-lined RHR reaches <100 C within 24 hours");
        Check.note("shutdown %s: %.1f -> %.1f C in %.2f h; peak HX %.1f MW; peak rate %.1f C/h; damage %.5f",
                name, start, vessel.getSaturationTemperatureC(), reachedSeconds / 3600,
                peakDuty, peakRate, boundary.getCooldownThermalStress());
        return new Cooldown(reachedSeconds / 3600, peakDuty, peakRate, boundary.getCooldownThermalStress());
    }

    public static void test04_scramToColdShutdownAndHighPressureLineup() {
        Cooldown full = runCooldown(2400, "full");
        Cooldown throttled = runCooldown(500, "throttled");
        Check.greaterThan(full.hours(), throttled.hours(), "throttling service water extends cooldown");
        Check.greaterThan(throttled.peakDutyMW(), full.peakDutyMW(), "full lineup extracts more heat");

        ReactorCore highPressure = new ReactorCore(new CoreConfig());
        highPressure.initialiseAtTotalPowerFraction(1);
        highPressure.getBoundaryStress().forceInterfacingSystemsLoca();
        double before = highPressure.getLiquidMassKg();
        highPressure.step(.05);
        Check.greaterThan(0, highPressure.getBreakLiquidFlowKgPerS(), "high pressure lineup creates outside break flow");
        Check.lessThan(before, highPressure.getLiquidMassKg(), "outside break loses vessel inventory");
        Check.note("high-pressure lineup: rating %.0f psig, vessel %.0f psig, outside break %.0f kg/s",
                BoundaryStress.RHR_PIPE_DESIGN_PRESSURE_PSIG, highPressure.getPressurePsig(),
                highPressure.getBreakLiquidFlowKgPerS());
    }

    public static void test05_steamInertingSprayAndHydrogenBurn() {
        ContainmentAtmosphere room = new ContainmentAtmosphere();
        // Deliberately strengthened 1,000-psig test boundary: isolate steam
        // inerting and spray-induced combustion from structural rupture. This
        // fixture exceeds the normal 140-psig failure rating and is NOT a
        // survivable Mark I accident or a containment-strength validation.
        room.setFailurePressurePsig(1000);
        for (int i = 0; i < 10000 && room.getSteamPercent() < 70; i++) {
            room.setSteamInputs(new ContainmentAtmosphere.SteamInputs(1200, 2800, 0, 0, 0, 0));
            room.step(0, 0, 0, .05);
        }
        for (int i = 0; i < 400; i++) {
            room.setSteamInputs(new ContainmentAtmosphere.SteamInputs(1200, 2800, 0, 0, 0, 0));
            room.step(5, 0, 0, .05);
        }
        Check.isTrue(room.isSteamInerted(), "steam exceeds 55 vol percent");
        Check.exactly(0, room.getBurnCount(), "stored hydrogen remains inerted");
        Check.isFalse(room.isFailed(), "strengthened inerting fixture remains intact before spray");
        Check.note("inerting fixture failure threshold %.0f psig (normal %.0f psig)",
                room.getFailurePressurePsig(), ContainmentAtmosphere.FAILURE_PRESSURE_PSIG);
        Check.note("inerting: steam %.1f%%, H2 %.1f%%, O2 %.1f%%, drywell %.1f / wetwell %.1f psig, burns %d",
                room.getSteamPercent(), room.getHydrogenPercent(), room.getOxygenPercent(),
                room.getDrywellPressurePsig(), room.getWetwellPressurePsig(), room.getBurnCount());
        double elapsed = 0;
        while (elapsed < 1000 && room.getBurnCount() == 0) {
            room.setSteamInputs(new ContainmentAtmosphere.SteamInputs(0, 0, 0, 0, 1000, 283.15));
            room.step(0, 0, 0, .05);
            elapsed += .05;
        }
        Check.greaterThan(0, room.getBurnCount(), "spray uncovers stored combustible mixture");
        Check.note("spray after %.1f s: steam %.1f%%, burns %d, peak drywell %.1f / wetwell %.1f psig",
                elapsed, room.getSteamPercent(), room.getBurnCount(),
                room.getPeakDrywellPressurePsig(), room.getPeakWetwellPressurePsig());
    }

    public static void test06_v2SaveLoadsWithoutSteam() {
        ContainmentAtmosphere original = new ContainmentAtmosphere();
        double[] v2 = Arrays.copyOf(original.toArray(), ContainmentAtmosphere.V2_SNAPSHOT_LENGTH);
        v2[0] = 2;
        ContainmentAtmosphere loaded = new ContainmentAtmosphere();
        loaded.fromArray(v2);
        Check.absolute(0, loaded.getSteamKg(), 0, "v2 has no steam inventory");
        Check.absolute(ContainmentAtmosphere.GAS_TEMPERATURE_K, loaded.getGasTemperatureK(), 0,
                "v2 uses old fixed temperature");
        double before = loaded.getPressurePsig();
        for (int i = 0; i < 1200; i++) loaded.step(0, 0, 0, .05);
        ContainmentAtmosphere legacyReference = new ContainmentAtmosphere();
        for (int i = 0; i < 1200; i++) legacyReference.step(0, 0, 0, .05);
        Check.absolute(legacyReference.getPressurePsig(), loaded.getPressurePsig(), 1e-9,
                "v2 zero-input pressure matches the legacy gas-only path, including design leakage");
        Check.note("v2 load: steam %.0f kg, gas %.1f C, representative %.3f -> %.3f psig in 60 s; drywell %.3f / wetwell %.3f psig",
                loaded.getSteamKg(), loaded.getGasTemperatureC(), before, loaded.getPressurePsig(),
                loaded.getDrywellPressurePsig(), loaded.getWetwellPressurePsig());
    }
}
