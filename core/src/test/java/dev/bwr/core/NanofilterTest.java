package dev.bwr.core;

import dev.bwr.core.chemistry.CoolantChemistry;
import dev.bwr.core.chemistry.FilterDemineralizer;
import dev.bwr.core.chemistry.RwcuLoop;
import dev.bwr.core.chemistry.RwcuPumpDesign;

/**
 * The RWCU stainless steel nanofilter (the unit that was the resin filter-demineraliser): it removes crud and dissolved
 * ions at full reactor temperature, loads and costs pressure drop, is spent at capacity, and the loop works hot without the
 * exchangers. The element replaced the resin, so there is no temperature limit to test for; what is tested is that there is none.
 */
public final class NanofilterTest {
    private NanofilterTest() {}

    private static final double LIQUID_KG = 300_000.0;
    private static final double HOT_C = 285.0;

    public static void test01_removesCrudAndDissolvedIonsAtReactorTemperature() {
        var filter = new FilterDemineralizer();
        double kg = 60.0 * 10;
        var r = filter.process(60, HOT_C, 0.0002, 0.002, 1.0e12, 10);
        Check.absolute(kg * 0.0002 * FilterDemineralizer.EFFICIENCY, r.removedIonG(), 1e-9, "it keeps its stated share of the dissolved ions at 285 C");
        Check.absolute(kg * 0.002 * FilterDemineralizer.CRUD_EFFICIENCY, r.removedCrudG(), 1e-9, "and of the crud");
        Check.absolute(r.removedCrudG() * 1.0e12, r.removedBq(), 1.0, "with the activity the crud carried");
        Check.absolute(FilterDemineralizer.EFFICIENCY, filter.lastEfficiency(), 1e-12, "full ion efficiency while the element is fresh");
        Check.absolute(0, new FilterDemineralizer().process(0, HOT_C, 0.0002, 0.002, 1.0e12, 10).removedIonG(), 0, "no flow, nothing removed");
        var plant = new CoolantChemistry();
        plant.addIons(30);
        double ionsBefore = plant.getIonicG();
        for (int i = 0; i < 3600; i++) {
            plant.step(LIQUID_KG, 1950, 1900, 1.0, 1.0);
            var s = filter.process(57, HOT_C, plant.ionicConcentrationGPerKg(LIQUID_KG), plant.crudConcentrationGPerKg(LIQUID_KG),
                    plant.specificActivityBqPerG(), 1.0);
            plant.removeIons(s.removedIonG());
            plant.removeCrud(s.removedCrudG());
        }
        Check.lessThan(ionsBefore - 5, plant.getIonicG(), "an hour of hot cleanup lowers the conductivity");
        Check.greaterThan(0.1, filter.crudLoadG(), "and fills the element with crud");
    }

    public static void test02_theTemperatureOfTheWaterMakesNoDifference() {
        var cold = new FilterDemineralizer();
        var hot = new FilterDemineralizer();
        var scorching = new FilterDemineralizer();
        for (int i = 0; i < 400; i++) {
            cold.process(60, 49, 0.0004, 0.001, 1.0e12, 1.0);
            hot.process(60, HOT_C, 0.0004, 0.001, 1.0e12, 1.0);
            scorching.process(60, 600.0, 0.0004, 0.001, 1.0e12, 1.0);
        }
        Check.absolute(cold.ionLoadG(), hot.ionLoadG(), 0, "ions: 49 C and 285 C are the same");
        Check.absolute(cold.crudLoadG(), hot.crudLoadG(), 0, "crud: the same");
        Check.absolute(cold.ionLoadG(), scorching.ionLoadG(), 0, "and 600 C is no different: there is no temperature limit");
        Check.absolute(100 - hot.ionLoadG(), hot.ionCapacityLeftG(), 1e-9, "no capacity is lost to heat");
    }

    public static void test03_loadingRaisesThePressureDropAndLowersTheLoopFlow() {
        var filter = new FilterDemineralizer();
        Check.absolute(FilterDemineralizer.DP_CLEAN_PSI, filter.pressureDropAtRatedPsi(), 1e-12, "a clean element costs its clean pressure drop");
        double none = RwcuPumpDesign.loopFlowKgPerS(1.0, 0);
        Check.absolute(RwcuPumpDesign.INSTANCE.ratedFlowKgPerS(), none, 1e-6, "with no extra resistance the pump makes its rated flow");
        double previousDp = filter.pressureDropAtRatedPsi();
        double previousFlow = RwcuPumpDesign.loopFlowKgPerS(1.0, previousDp);
        Check.lessThan(none, previousFlow, "even a clean element costs a little flow");
        for (int step = 1; step <= 4; step++) {
            filter.fromArray(new double[]{1, 0, FilterDemineralizer.CRUD_CAPACITY_G * step / 4.0 * 0.99, 0, 0});
            double dp = filter.pressureDropAtRatedPsi();
            double flow = RwcuPumpDesign.loopFlowKgPerS(1.0, dp);
            Check.greaterThan(previousDp, dp, "more crud in the element, more pressure drop (" + step + ")");
            Check.lessThan(previousFlow, flow, "and less water through the loop");
            previousDp = dp;
            previousFlow = flow;
        }
        Check.lessThan(0.85 * none, previousFlow, "a full element costs the loop about a fifth of its flow");
        Check.greaterThan(0.7 * none, previousFlow, "but does not stop it");
        Check.absolute(filter.pressureDropAtRatedPsi() / 4.0, filter.pressureDropPsi(30), 1e-9, "the drop is quadratic in flow");
        var ionsOnly = new FilterDemineralizer();
        ionsOnly.fromArray(new double[]{1, 90, 0, 0, 0});
        Check.absolute(FilterDemineralizer.DP_CLEAN_PSI, ionsOnly.pressureDropAtRatedPsi(), 1e-12, "dissolved ions are no cake: only crud adds pressure drop");
        var empty = new FilterDemineralizer();
        empty.removeBed();
        Check.absolute(0, empty.pressureDropAtRatedPsi(), 0, "a housing with no element is an open spool");
        Check.note("loop flow: %.1f kg/s open, %.1f clean, %.1f full (dp %.1f psid)", none, RwcuPumpDesign.loopFlowKgPerS(1.0, FilterDemineralizer.DP_CLEAN_PSI),
                previousFlow, filter.pressureDropAtRatedPsi());
    }

    public static void test04_theElementIsSpentAtItsCapacityAndTheNewOneStartsClean() {
        var filter = new FilterDemineralizer();
        double removed = 0;
        int seconds = 0;
        while (filter.ionCapacityLeftG() > 0 && seconds < 200_000) {
            removed += filter.process(60, HOT_C, 0.01, 0.0001, 1.0e12, 1.0).removedIonG();
            seconds++;
        }
        Check.absolute(FilterDemineralizer.ION_CAPACITY_G, removed, FilterDemineralizer.SPENT_BELOW_G + 1e-6, "it took its capacity, less the spent margin");
        Check.absolute(1.0, filter.exhaustion(), 0, "and reads as exhausted");
        Check.absolute(0, filter.process(60, HOT_C, 0.01, 0.0001, 1.0e12, 1.0).removedIonG(), 0, "an exhausted element removes no ions");
        double[] gone = filter.spentBed();
        Check.absolute(removed, gone[0], 1e-9, "the changed-out element carries what it took");
        Check.greaterThan(0, gone[2], "and its activity");
        Check.greaterThan(0, filter.gammaWatts(), "so it is a gamma source in service");
        filter.replaceBed();
        Check.isTrue(filter.exhaustion() == 0 && filter.activityBq() == 0, "a new element is new");
        Check.greaterThan(0.1, filter.process(60, HOT_C, 0.01, 0, 0, 1.0).removedIonG(), "and removes again");
        // A crud-full element stops taking crud, and is spent on crud capacity alone.
        var crudFull = new FilterDemineralizer();
        double crud = 0;
        for (int i = 0; i < 100_000 && crudFull.crudLoadG() < FilterDemineralizer.CRUD_CAPACITY_G - 1e-9; i++)
            crud += crudFull.process(60, HOT_C, 0, 0.01, 1.0e12, 1.0).removedCrudG();
        Check.absolute(FilterDemineralizer.CRUD_CAPACITY_G, crud, 1e-6, "the element took its crud capacity");
        Check.absolute(0, crudFull.process(60, HOT_C, 0, 0.01, 1.0e12, 1.0).removedCrudG(), 0, "and then no more crud");
        Check.absolute(1.0, crudFull.exhaustion(), 1e-9, "a crud-full element reads as used up");
    }

    public static void test05_theLoopWorksHotWithoutTheExchangersAndStillConservesEnergy() {
        double vesselC = HOT_C, flow = 55, reject = 10;
        // No regenerative exchanger and no cooler: the water goes round at reactor temperature and the vessel loses nothing.
        var bare = RwcuLoop.solve(vesselC, flow, reject, 0.0, 0.0);
        Check.absolute(vesselC, bare.demineraliserC(), 0.01, "the filter sees reactor water");
        Check.absolute(vesselC, bare.returnC(), 0.01, "and it returns at reactor temperature");
        Check.absolute(0, bare.vesselHeatLossMW(), 1e-9, "so the vessel loses no heat");
        Check.absolute(0, bare.coolerDutyMW(), 1e-9, "and the cooling water gets none");
        double hv = RwcuLoop.enthalpy(vesselC);
        Check.relative(flow * hv, (flow - reject) * RwcuLoop.enthalpy(bare.returnC()) + reject * RwcuLoop.enthalpy(bare.rejectC()), 1e-4, "energy closes with no exchangers");
        // The regenerator alone has nothing to exchange with (the cold side is as hot as the hot side); the cooler alone cools some.
        var regenOnly = RwcuLoop.solve(vesselC, flow, reject, 0.0);
        Check.absolute(0, regenOnly.vesselHeatLossMW(), 1e-6, "a regenerator without a cooler recovers nothing and loses nothing");
        var coolerOnly = RwcuLoop.solve(vesselC, flow, reject, 8.0, 0.0);
        Check.greaterThan(0.5, coolerOnly.vesselHeatLossMW(), "a cooler without a regenerator takes its duty from the vessel");
        Check.lessThan(vesselC, coolerOnly.demineraliserC(), "and cools the water it can");
        // A loaded element throttles the flow; the balance still closes there.
        var full = new FilterDemineralizer();
        full.fromArray(new double[]{1, 0, FilterDemineralizer.CRUD_CAPACITY_G * 0.99, 0, 0});
        double throttled = RwcuPumpDesign.loopFlowKgPerS(1.0, full.pressureDropAtRatedPsi());
        var r = RwcuLoop.solve(vesselC, throttled, reject, 8.0);
        double hx = RwcuLoop.enthalpy(r.demineraliserC()), hr = RwcuLoop.enthalpy(r.returnC());
        Check.relative(throttled * hv, (throttled - reject) * hr + reject * hx + r.coolerDutyMW() * 1000, 1e-4, "enthalpy in equals enthalpy out at the throttled flow");
        Check.absolute((throttled - reject) * (hv - hr) / 1000, r.vesselHeatLossMW(), 1e-6, "and the vessel is charged for the returned part only");
        Check.lessThan(RwcuLoop.solve(vesselC, 60, reject, 8.0).vesselHeatLossMW(), r.vesselHeatLossMW(), "a throttled loop takes less heat from the vessel");
    }

    public static void test06_aSavedResinBedLoadsAsAnElementWithTheSameLoading() {
        var element = new FilterDemineralizer();
        // Five numbers as the resin bed wrote them: present, ions, crud, activity, heat damage.
        element.fromArray(new double[]{1, 62.5, 91.0, 2.5e13, 0.4});
        Check.absolute(62.5, element.ionLoadG(), 0, "ions kept");
        Check.absolute(91.0, element.crudLoadG(), 0, "crud kept");
        Check.absolute(2.5e13, element.activityBq(), 0, "activity kept");
        Check.absolute(100 - 62.5, element.ionCapacityLeftG(), 1e-9, "the old heat damage is dropped, not charged to the new element");
        var copy = new FilterDemineralizer();
        copy.fromArray(element.toArray());
        Check.arraysExactly(element.toArray(), copy.toArray(), "round trip");
        Check.absolute(0, element.toArray()[4], 0, "the fifth number is always zero now");
        var untouched = new FilterDemineralizer();
        untouched.fromArray(new double[]{1, 1, 1});
        Check.absolute(0, untouched.ionLoadG(), 0, "a short array is ignored");
    }
}
