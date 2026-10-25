package dev.bwr.core;

import dev.bwr.core.chemistry.CoolantChemistry;
import dev.bwr.core.chemistry.FilterDemineralizer;
import dev.bwr.core.chemistry.RwcuCooler;
import dev.bwr.core.chemistry.RwcuLoop;

/** Coolant chemistry, the RWCU thermodynamics, the resin bed and the loop's fit with the vessel's mass and energy. */
public final class ReactorWaterCleanupTest {
    private ReactorWaterCleanupTest() {}

    private static final double LIQUID_KG = 300_000.0;

    public static void test01_conductivityRisesWithoutCleanup() {
        var chemistry = new CoolantChemistry();
        double start = chemistry.conductivityUsPerCm(LIQUID_KG);
        Check.absolute(CoolantChemistry.CLEAN_US_PER_CM, start, 1e-9, "a new plant is clean");
        for (int i = 0; i < 3600; i++) chemistry.step(LIQUID_KG, 1950, 1900, 1.0, 1.0);
        double hour = chemistry.conductivityUsPerCm(LIQUID_KG);
        Check.greaterThan(start + 0.05, hour, "an hour at power with no cleanup raises conductivity");
        Check.lessThan(start + 0.5, hour, "and only slowly");
        Check.greaterThan(0.1, chemistry.getCrudG(), "crud builds");
        Check.note("no cleanup: %.3f -> %.3f uS/cm in 1 h, crud %.2f g, activity %.2e Bq", start, hour,
                chemistry.getCrudG(), chemistry.getActivityBq());
    }

    public static void test02_cleanupBalancesTheSourcesAtAFewTenthsOfAMicrosiemens() {
        var chemistry = new CoolantChemistry();
        var bed = new FilterDemineralizer();
        for (int i = 0; i < 24 * 3600; i += 1) {
            chemistry.step(LIQUID_KG, 1950, 1900, 1.0, 1.0);
            var r = bed.process(57.0, 49, chemistry.ionicConcentrationGPerKg(LIQUID_KG), chemistry.crudConcentrationGPerKg(LIQUID_KG),
                    chemistry.specificActivityBqPerG(), 1.0);
            chemistry.removeIons(r.removedIonG());
            chemistry.removeCrud(r.removedCrudG());
            if (i % 600 == 0 && bed.exhaustion() > 0.9) bed.replaceBed();
        }
        double k = chemistry.conductivityUsPerCm(LIQUID_KG);
        Check.lessThan(0.5, k, "with RWCU running the water stays near BWR practice");
        Check.greaterThan(0.06, k, "above pure water");
        Check.note("RWCU running 24 h: %.3f uS/cm, crud %.2f g", k, chemistry.getCrudG());
    }

    public static void test03_resinExhaustsInProportionToTheImpurityItRemoves() {
        var bed = new FilterDemineralizer();
        double removed = 0;
        int seconds = 0;
        while (bed.ionCapacityLeftG() > 1e-9 && seconds < 200_000) {
            // Dirty water, 0.05 g/kg: a bed holds 100 g.
            var r = bed.process(60, 49, 0.05, 0, 0, 1.0);
            removed += r.removedIonG();
            seconds++;
        }
        Check.absolute(FilterDemineralizer.ION_CAPACITY_G, removed, FilterDemineralizer.SPENT_BELOW_G + 1e-6,
                "the bed took its capacity (less the spent margin), then no more");
        Check.absolute(0, bed.process(60, 49, 0.05, 0, 0, 1.0).removedIonG(), 1e-12, "an exhausted bed removes nothing");
        Check.isTrue(bed.exhaustion() >= 1.0, "it reads as exhausted");
        bed.replaceBed();
        Check.greaterThan(0.1, bed.process(60, 49, 0.05, 0, 0, 1.0).removedIonG(), "a replaced bed removes again");
    }

    public static void test04_theElementWorksAtReactorTemperatureAndIsNotDamagedByIt() {
        var bed = new FilterDemineralizer();
        for (int i = 0; i < 600; i++) bed.process(60, 49, 0.0005, 0, 0, 1.0);
        double held = bed.ionLoadG();
        Check.greaterThan(10, held, "the element holds impurity");
        double hotRemoved = 0;
        for (int i = 0; i < 600; i++) hotRemoved += bed.process(60, 285.0, 0.0005, 0.001, 1.0e12, 1.0).removedIonG();
        Check.greaterThan(1, hotRemoved, "at 285 C it goes on removing ions");
        Check.greaterThan(held, bed.ionLoadG(), "and nothing it held is let go (the load only rose)");
        Check.greaterThan(0, bed.crudLoadG(), "crud too");
        Check.absolute(100 - bed.ionLoadG(), bed.ionCapacityLeftG(), 1e-9, "capacity left is what it has not taken: nothing is lost to heat");
        var cool = new FilterDemineralizer();
        var hot = new FilterDemineralizer();
        for (int i = 0; i < 600; i++) {
            cool.process(60, 49, 0.0005, 0.001, 1.0e12, 1.0);
            hot.process(60, 285, 0.0005, 0.001, 1.0e12, 1.0);
        }
        Check.absolute(cool.ionLoadG(), hot.ionLoadG(), 0, "water temperature makes no difference to what it removes");
        Check.absolute(cool.crudLoadG(), hot.crudLoadG(), 0, "to crud either");
    }

    public static void test05_regenerativeExchangerRecoversTheHeat() {
        double vesselC = 285.0;
        var with = RwcuLoop.solve(vesselC, 60, 0, 8.0);
        var without = RwcuLoop.solve(vesselC, 60, 0, 1000.0, 0.0);
        Check.greaterThan(250, with.returnC(), "the return to feedwater is hot with the regenerator");
        Check.lessThan(60, without.returnC(), "and cold without it");
        Check.lessThan(0.12 * without.vesselHeatLossMW(), with.vesselHeatLossMW(), "the regenerator recovers over 88% of the heat");
        Check.absolute(RwcuLoop.TARGET_C, with.demineraliserC(), 0.01, "the cooler brings the demineraliser water to 120 F");
        Check.absolute(with.coolerDutyMW(), with.vesselHeatLossMW(), 1e-6, "all heat the vessel loses goes to the cooling water");
        Check.note("regen: return %.1f C, vessel loss %.2f MW (%.2f MW without), regen recovers %.2f MW, cooler %.2f MW",
                with.returnC(), with.vesselHeatLossMW(), without.vesselHeatLossMW(), with.regenDutyMW(), with.coolerDutyMW());
    }

    public static void test06_theLoopConservesEnergy() {
        double vesselC = 285.0, flow = 60, reject = 15;
        var r = RwcuLoop.solve(vesselC, flow, reject, 8.0);
        double hv = RwcuLoop.enthalpy(vesselC), hx = RwcuLoop.enthalpy(r.demineraliserC()), hr = RwcuLoop.enthalpy(r.returnC());
        double in = flow * hv, out = (flow - reject) * hr + reject * hx + r.coolerDutyMW() * 1000;
        Check.relative(in, out, 1e-4, "enthalpy in equals enthalpy out");
        // The vessel is charged the loop's heat loss on top of the reject's own enthalpy leaving with its mass.
        Check.absolute((flow - reject) * (hv - hr) / 1000, r.vesselHeatLossMW(), 1e-6, "vessel heat loss is for the returned part only");
    }

    public static void test07_lostCoolingMakesTheFilterWaterHotAndThatIsHarmless() {
        var full = RwcuLoop.solve(285, 60, 0, 8.0);
        var none = RwcuLoop.solve(285, 60, 0, 0.0);
        var part = RwcuLoop.solve(285, 60, 0, 2.0);
        Check.greaterThan(140, none.demineraliserC(), "no cooling water leaves the filter at vessel temperature");
        Check.greaterThan(60.0, part.demineraliserC(), "short cooling leaves it warm");
        Check.lessThan(60.0, full.demineraliserC(), "full cooling leaves it cool");
        var element = new FilterDemineralizer();
        for (int i = 0; i < 100; i++) element.process(60, none.demineraliserC(), 0.0005, 0.001, 1.0e12, 1.0);
        Check.greaterThan(1, element.ionLoadG(), "and the element works in it all the same");
    }

    public static void test08_coolerCoolingWaterIsFinite() {
        var cooler = new RwcuCooler();
        Check.absolute(0, cooler.maxDutyMW(0.05), 0, "no cooling water, no duty");
        cooler.fillCold(RwcuCooler.CAPACITY_KG, dev.bwr.core.thermal.Saturation.subcooledLiquidEnthalpyKJPerKg(13), false);
        double max = cooler.maxDutyMW(0.05);
        Check.absolute(RwcuCooler.RATING_MW, max, 1e-9, "a full cooler passes its rating");
        double cold = cooler.cold();
        double duty = cooler.commit(5.0, 0.05);
        Check.absolute(5.0, duty, 1e-9, "duty taken");
        Check.lessThan(cold, cooler.cold(), "cooling water was used");
        Check.absolute(cold, cooler.cold() + cooler.hot(), 1e-9, "no water made or lost");
        Check.greaterThan(13, cooler.hotTemperatureC(), "and it left warmer");
    }

    public static void test09_blowdownLowersTheVesselAndTheLoopOnlyTakesHeat() {
        var base = rated();
        var drain = rated();
        var loop = rated();
        double m0 = base.getLiquidMassKg();
        double feed = base.getFeedwaterFlowKgPerS();
        for (int i = 0; i < 200; i++) {
            pressureOnly(base, feed);
            base.step();
            pressureOnly(drain, feed);
            drain.addCleanupLoop(0, 40.0);
            drain.step();
            pressureOnly(loop, feed);
            loop.addCleanupLoop(5.0, 0);
            loop.step();
        }
        double rejected = 40.0 * 200 * 0.05;
        Check.absolute(rejected, base.getLiquidMassKg() - drain.getLiquidMassKg(), 0.1 * rejected,
                "blowdown took about its mass out of the vessel, against the same plant without it");
        // 5 MW less to boil is 5 MW / 1.5 MJ/kg = 3.3 kg/s less steam, 33 kg in the 10 s; no mass flow of its own.
        Check.absolute(base.getLiquidMassKg() + 5.0e3 / 1500.0 * 10.0, loop.getLiquidMassKg(), 15.0,
                "a loop that rejects nothing moves no net mass: only the steam the heat sink stops making");
        Check.greaterThan(loop.getPressurePsig(), base.getPressurePsig(), "a heat sink leaves less to boil, so pressure is lower");
        Check.absolute(5.0, loop.getCleanupHeatLossMW(), 0, "readout");
        Check.absolute(40.0, drain.getCleanupBlowdownKgPerS(), 0, "readout");
        Check.absolute(0.0, base.getCleanupBlowdownKgPerS(), 0, "unreported expires");
    }

    public static void test10_crudActivityIsAGammaSourceCleanupRemoves() {
        var chemistry = new CoolantChemistry();
        for (int i = 0; i < 4 * 3600; i++) chemistry.step(LIQUID_KG, 1950, 1900, 1.0, 1.0);
        double w = chemistry.crudGammaWatts();
        Check.greaterThan(0.01, w, "activated crud emits gamma");
        double[] taken = chemistry.removeCrud(chemistry.getCrudG() * 0.9);
        Check.greaterThan(0, taken[1], "removal takes activity with the crud");
        Check.lessThan(w * 0.2, chemistry.crudGammaWatts(), "cleanup lowers the source");
        var restored = new CoolantChemistry();
        restored.fromArray(chemistry.toArray());
        Check.absolute(chemistry.getActivityBq(), restored.getActivityBq(), 0, "round trip");
        Check.absolute(chemistry.getIonicG(), restored.getIonicG(), 0, "round trip");
        Check.note("crud after 4 h: %.2f g, %.2f W gamma", chemistry.getCrudG() / 0.1, w);
    }

    public static void test11_conductiveWaterWearsThePipeSlowly() {
        var core = rated();
        double before = core.getBoundaryStress().getStress(dev.bwr.core.boundary.BoundaryComponent.MAIN_STEAM_LINE);
        // Dirty the water past the action level and run on.
        core.getChemistry().addIons(CoolantChemistry.ionicGramsFor(5.0, core.getLiquidMassKg()));
        for (int i = 0; i < 2000; i++) { regulated(core); core.step(); }
        double after = core.getBoundaryStress().getStress(dev.bwr.core.boundary.BoundaryComponent.MAIN_STEAM_LINE);
        Check.note("stress before %.6f after %.6f, pressure %.1f", before, after, core.getPressurePsig());
        Check.greaterThan(before, after, "conductive water adds structural wear");
        Check.lessThan(0.01, after - before, "gently");
        Check.greaterThan(0, core.getChemistry().getCladdingWear(), "and cladding wear");
        var clean = rated();
        for (int i = 0; i < 2000; i++) { regulated(clean); clean.step(); }
        Check.absolute(0, clean.getBoundaryStress().getStress(dev.bwr.core.boundary.BoundaryComponent.MAIN_STEAM_LINE), 0,
                "clean water wears nothing");
    }

    public static void test12_chemistryRestoresCleanWithoutARecord() {
        var core = rated();
        core.getChemistry().addIons(10);
        core.fromState(core.toState());
        Check.absolute(CoolantChemistry.CLEAN_US_PER_CM, core.getConductivityUsPerCm(), 1e-9,
                "a state with no chemistry beside it restores clean");
        Check.isFalse(core.getChemistry().differsFromClean(core.getLiquidMassKg()), "and writes nothing");
    }

    /** The pressure and level regulation a plant has (the harness's minimal one), for one step. */
    private static void regulated(ReactorCore core) {
        double pressureError = core.getPressurePsig() - PhysicalConstants.RATED_DOME_PRESSURE_PSIG;
        core.setTurbineSteamFlowKgPerS(Math.max(0.0, core.getSteamGenerationKgPerS() + 2.0 * pressureError));
        core.setFeedwaterFlowKgPerS(Math.max(0.0, core.getTurbineSteamFlowKgPerS() - 20.0 * (core.getCollapsedLevelIn() - 8.0)));
    }

    /** Pressure regulation only, feedwater held where it is: so a mass sink is not hidden by a level controller. */
    private static void pressureOnly(ReactorCore core, double feedwater) {
        double pressureError = core.getPressurePsig() - PhysicalConstants.RATED_DOME_PRESSURE_PSIG;
        core.setTurbineSteamFlowKgPerS(Math.max(0.0, core.getSteamGenerationKgPerS() + 2.0 * pressureError));
        core.setFeedwaterFlowKgPerS(feedwater);
    }

    private static ReactorCore rated() {
        var core = new ReactorCore(new CoreConfig());
        core.initialiseAtTotalPowerFraction(1.0);
        return core;
    }
}
