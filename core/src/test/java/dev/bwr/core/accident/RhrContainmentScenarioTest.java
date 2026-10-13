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

    private record State(double[] pool, double[] atmosphere, double peakPsig, double peakSteamKgPerS) {}

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
        double peakP = gas.getPressurePsig(), peakFlow = 0, priorBreak = 0;
        final double dt = .25;
        if (print) Check.note("DBA t=0 s: RPV %.1f psig, pool %.1f C, containment %.1f psig",
                core.getPressurePsig(), pool.getTemperatureC(), peakP);
        for (int i = 1; i <= 1200; i++) {
            pool.setContainmentPressurePsia(gas.getPressurePsia());
            double drywellContact = priorBreak * ContainmentAtmosphere.DRYWELL_WALL_CONTACT_FRACTION;
            pool.condenseSteam(priorBreak - drywellContact, core.getPressurePsig(), dt);
            gas.setSteamInputs(new ContainmentAtmosphere.SteamInputs(drywellContact, 2800,
                    pool.getUncondensedSteamKgPerS(), pool.getTemperatureC() + 273.15, 0, 0));
            core.setInjectionFlowKgPerS(2000);
            core.setInjectionTemperatureC(pool.getTemperatureC());
            core.step(dt);
            double vesselP = Saturation.psiaFromPsig(core.getPressurePsig());
            double containmentP = Math.max(.1, gas.getPressurePsia());
            double flash = Math.clamp((Saturation.liquidEnthalpyKJPerKg(vesselP)
                    - Saturation.liquidEnthalpyKJPerKg(containmentP))
                    / Math.max(1, Saturation.latentHeatKJPerKg(containmentP)), 0, 1);
            priorBreak = Math.max(0, core.getBreakSteamFlowKgPerS()
                    + core.getBreakLiquidFlowKgPerS() * flash);
            peakFlow = Math.max(peakFlow, priorBreak);
            peakP = Math.max(peakP, gas.getPressurePsig());
            if (print && (i == 120 || i == 240 || i == 480 || i == 1200))
                Check.note("DBA t=%d s: RPV %.1f psig, break %.0f kg/s, pool %.1f C, containment %.1f psig",
                        (int) (i * dt), core.getPressurePsig(), priorBreak,
                        pool.getTemperatureC(), gas.getPressurePsig());
        }
        return new State(pool.toArray(), gas.toArray(), peakP, peakFlow);
    }

    public static void test01_designBasisRecirculationLoca() {
        State result = designBasisBlowdown(true);
        SuppressionPool pool = new SuppressionPool(); pool.fromArray(result.pool());
        ContainmentAtmosphere gas = new ContainmentAtmosphere(); gas.fromArray(result.atmosphere());
        Check.greaterThan(500, result.peakSteamKgPerS(), "large recirculation break sends steam to suppression pool");
        Check.greaterThan(32, pool.getTemperatureC(), "suppression pool takes blowdown heat");
        Check.greaterThan(0, pool.getSubcoolingC(), "pool remains subcooled at DBA endpoint");
        Check.lessThan(gas.getFailurePressurePsig(), result.peakPsig(), "peak below structural failure");
        // With the corrected cold-pool saturation cap, this single shared
        // airspace node peaks near 5 psig during a subcooled-pool DBA. The old
        // >25 psig assertion depended on the warmer wall incorrectly setting
        // the cap. The model still has to show a positive break-driven rise.
        Check.greaterThan(4, result.peakPsig(), "subcooled-pool DBA raises shared airspace pressure");
        Check.lessThan(50, result.peakPsig(), "peak stays below cited Mark I 49.6 psig drywell value");
        Check.note("DBA peak: %.1f psig (NRC Mark I wetwell 27, drywell 49.6); break %.0f kg/s; pool %.1f C, subcooling %.1f C",
                result.peakPsig(), result.peakSteamKgPerS(), pool.getTemperatureC(), pool.getSubcoolingC());
    }

    private record Tail(double peakP, double finalP, double poolC, double preVentP) {}

    /** Tail heat is an explicit decaying steam source from an injected scrammed vessel. */
    private static Tail runTail(State start, boolean rhr, boolean vent, String name) {
        SuppressionPool pool = new SuppressionPool(); pool.fromArray(start.pool());
        ContainmentAtmosphere gas = new ContainmentAtmosphere(); gas.fromArray(start.atmosphere());
        // Keep the three operator lineups observable even if containment would fail.
        gas.setFailurePressurePsig(1000);
        double peak = gas.getPressurePsig(), beforeVent = Double.NaN;
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
            if (i == 1 || i == 720 || i == 1440 || i == 2160 || i == 2880)
                Check.note("24h %s t=%.0f h: pool %.1f C, suppression %.2f, containment %.1f psig",
                        name, h, pool.getTemperatureC(), pool.getLastCondensationEffectiveness(), gas.getPressurePsig());
        }
        return new Tail(peak, gas.getPressurePsig(), pool.getTemperatureC(), beforeVent);
    }

    public static void test02_twentyFourHourLocaRhrAndVenting() {
        State start = designBasisBlowdown(false);
        Tail noRhr = runTail(start, false, false, "no RHR");
        Tail cooled = runTail(start, true, false, "RHR");
        Tail vented = runTail(start, false, true, "vent at 12h");
        Check.greaterThan(cooled.poolC() + 10, noRhr.poolC(), "RHR bounds suppression pool temperature");
        Check.greaterThan(cooled.peakP(), noRhr.peakP(), "RHR bounds pressure");
        Check.lessThan(vented.preVentP(), vented.finalP(), "wetwell vent reduces pressure after opening");
        Check.note("24h peaks: no RHR %.1f psig/%.1f C; RHR %.1f psig/%.1f C; vent final %.1f psig",
                noRhr.peakP(), noRhr.poolC(), cooled.peakP(), cooled.poolC(), vented.finalP());
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
                Check.note("SBO t=%.0f h %s: pool %.1f C, containment %.1f psig",
                        h, h <= 6 ? "RCIC/SRV" : "RHR restored", pool.getTemperatureC(), gas.getPressurePsig());
        }
        Check.greaterThan(initial + 1, preRhr, "RCIC/SRV heating raises containment pressure");
        Check.lessThan(preRhr, gas.getPressurePsig(), "restored RHR turns pressure down");
        Check.note("SBO peak %.1f psig; 6h %.1f psig; 9h %.1f psig; final pool %.1f C",
                peak, preRhr, gas.getPressurePsig(), pool.getTemperatureC());
    }

    public static void test04_scramToColdShutdownAndHighPressureLineup() {
        PressureVessel vessel = new PressureVessel(new CoreConfig());
        vessel.initialiseToNormalLevel();
        vessel.restorePressurePsig(130);
        RhrHeatExchanger exchanger = new RhrHeatExchanger();
        double start = vessel.getSaturationTemperatureC(), peakDuty = 0, reachedSeconds = Double.NaN;
        Check.note("shutdown t=0 min: vessel %.1f C, pressure %.1f psig", start, vessel.getPressurePsig());
        for (int i = 1; i <= 3600 * 20; i++) {
            exchanger.drainHot(120, false);
            exchanger.fillCold(120, false);
            double duty = exchanger.stepReactor(vessel.getSaturationTemperatureC(),
                    vessel.getLiquidMassKg(), 1500, .05);
            peakDuty = Math.max(peakDuty, duty);
            vessel.setShutdownCoolingMW(duty);
            vessel.step(20, 0, 0, 0, 0, .05);
            if (i % (900 * 20) == 0)
                Check.note("shutdown t=%d min: vessel %.1f C, pressure %.1f psig, HX %.1f MW",
                        i / 1200, vessel.getSaturationTemperatureC(), vessel.getPressurePsig(), duty);
            if (vessel.getSaturationTemperatureC() < 100) { reachedSeconds = i * .05; break; }
        }
        Check.isTrue(Double.isFinite(reachedSeconds), "operator-lined RHR reaches <100 C within one hour");
        Check.note("shutdown: %.1f -> %.1f C in %.1f min; peak exchanger %.1f MW",
                start, vessel.getSaturationTemperatureC(), reachedSeconds / 60, peakDuty);

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
        Check.note("inerting: steam %.1f%%, H2 %.1f%%, O2 %.1f%%, burns %d",
                room.getSteamPercent(), room.getHydrogenPercent(), room.getOxygenPercent(), room.getBurnCount());
        double elapsed = 0;
        while (elapsed < 1000 && room.getBurnCount() == 0) {
            room.setSteamInputs(new ContainmentAtmosphere.SteamInputs(0, 0, 0, 0, 1000, 283.15));
            room.step(0, 0, 0, .05);
            elapsed += .05;
        }
        Check.greaterThan(0, room.getBurnCount(), "spray uncovers stored combustible mixture");
        Check.note("spray after %.1f s: steam %.1f%%, burns %d, peak %.1f psig",
                elapsed, room.getSteamPercent(), room.getBurnCount(), room.getLastBurnPeakPressurePsig());
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
        Check.note("v2 load: steam %.0f kg, gas %.1f C, pressure %.3f -> %.3f psig in 60 s",
                loaded.getSteamKg(), loaded.getGasTemperatureC(), before, loaded.getPressurePsig());
    }
}
