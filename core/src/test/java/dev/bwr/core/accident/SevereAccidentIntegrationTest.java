package dev.bwr.core.accident;

import dev.bwr.core.Check;
import dev.bwr.core.CoreConfig;
import dev.bwr.core.ReactorCore;

/**
 * The severe accident and containment models wired into the whole reactor:
 * the lower head breach drains the vessel, debris heat reaches the water,
 * water leaving through a breach cools corium, and fuel hydrogen reaches
 * containment. Each case starts from a saved damage state rather than
 * spending simulated hours getting there.
 */
public final class SevereAccidentIntegrationTest {

    private SevereAccidentIntegrationTest() {
    }

    /** A core one minute after a scram from rated, isolated, relief valves holding pressure. */
    static ReactorCore scrammedIsolated() {
        ReactorCore core = new ReactorCore(new CoreConfig());
        core.initialiseAtTotalPowerFraction(1.0);
        core.scram();
        core.setRecirculationFlowFraction(0.0);
        core.setTurbineSteamFlowKgPerS(0.0);
        core.setBypassSteamFlowKgPerS(0.0);
        core.setFeedwaterFlowKgPerS(0.0);
        step(core, 60, 0.25);
        return core;
    }

    static boolean relief;

    static void step(ReactorCore core, double seconds, double dt) {
        int steps = (int) Math.round(seconds / dt);
        for (int i = 0; i < steps; i++) {
            double p = core.getPressurePsig();
            if (p > 1105) relief = true;
            if (p < 1050) relief = false;
            core.setReliefSteamFlowKgPerS(relief ? 900.0 : 0.0);
            core.step(dt);
        }
    }

    static double[] snapshot(double failedClad, double moltenInCore, double plenum, double exVessel,
                             double debrisC, double headDamage, boolean headFailed) {
        SevereAccident sa = new SevereAccident();
        double[] a = sa.toArray();
        a[1] = failedClad;
        a[2] = moltenInCore;
        a[3] = plenum;
        a[4] = exVessel;
        a[5] = debrisC;
        a[6] = headDamage;
        a[7] = headFailed ? 1 : 0;
        a[10] = SevereAccident.GAP_RELEASE_FRACTION * failedClad;
        a[13] = headFailed ? SevereAccident.Stage.LOWER_HEAD_FAILURE.number() : SevereAccident.Stage.FUEL_MELT.number();
        a[14] = 1;
        a[15] = 1;
        return a;
    }

    public static void test01_aFailedLowerHeadDrainsTheVesselOntoTheCorium() {
        ReactorCore core = scrammedIsolated();
        double before = core.getLiquidMassKg();
        core.getSevereAccident().fromArray(snapshot(1.0, 0.0, 0.0, 0.3, 0.0, 1.0, true));
        step(core, 30, 0.25);
        double drained = before - core.getLiquidMassKg();
        Check.greaterThan(20_000.0, drained, "water lost through the lower head in 30 s");
        Check.greaterThan(0.0, core.getBreakLiquidFlowKgPerS(), "liquid leaving through the breach");
        Check.greaterThan(0.0, core.getSevereAccident().getCoriumWaterCoolingMW(),
                "water from the breach is cooling the corium");
        Check.lessThan(0.001, core.getSevereAccident().getAblationDepthM(),
                "concrete ablation while the vessel is pouring water onto the corium");
        Check.note("lower head breach: %.0f t of water out in 30 s at %.0f kg/s, %.1f MW removed from corium",
                drained / 1000, core.getBreakLiquidFlowKgPerS(), core.getSevereAccident().getCoriumWaterCoolingMW());
    }

    public static void test02_debrisInAFloodedLowerPlenumBoilsTheWater() {
        ReactorCore core = scrammedIsolated();
        core.getSevereAccident().fromArray(snapshot(1.0, 0.0, 0.3, 0.0, 2200.0, 0.0, false));
        step(core, 1.0, 0.25);
        double heat = core.getSevereAccident().getDebrisHeatToVesselMW();
        Check.greaterThan(100.0, heat, "a fresh hot debris bed dumps heat into the water");
        step(core, 300, 0.25);
        SevereAccident sa = core.getSevereAccident();
        Check.lessThan(SevereAccident.LOWER_HEAD_CREEP_ONSET_C - 200.0, sa.getDebrisTemperatureC(), "debris quenched below creep onset under water");
        Check.lessThan(0.05, sa.getLowerHeadDamage(), "lower head creep while quenched");
        Check.isFalse(sa.isLowerHeadFailed(), "a flooded head holds");
        Check.note("30%% of the core into a full lower plenum: %.0f MW on contact, debris %.0f degC after 5 min",
                heat, sa.getDebrisTemperatureC());
    }

    public static void test03_relocatedFuelTakesItsHeatOutOfTheCoreNode() {
        ReactorCore intact = scrammedIsolated();
        ReactorCore damaged = scrammedIsolated();
        damaged.getSevereAccident().fromArray(snapshot(1.0, 0.0, 0.5, 0.0, 300.0, 0.0, false));
        double ncIntact = intact.naturalCirculationFlowFraction();
        double ncDamaged = damaged.naturalCirculationFlowFraction();
        Check.relative(0.5 * ncIntact, ncDamaged, 1e-9, "half the core slumped halves the natural circulation path");
        step(intact, 60, 0.25);
        step(damaged, 60, 0.25);
        Check.lessThan(intact.getFuelTemperatureC(), damaged.getFuelTemperatureC() + 1e-9,
                "the remaining core runs no hotter than an intact one");
    }

    public static void test04_fuelHydrogenReachesContainment() {
        ReactorCore core = scrammedIsolated();
        double before = core.getHydrogenGeneratedKg();
        double containmentBefore = core.getContainment().getHydrogenKg();
        // Hold the clad at an oxidising temperature with the vessel still boiling,
        // so there is steam for the reaction.
        for (int i = 0; i < 40; i++) {
            core.getFuelThermal().restoreTemperatures(1500.0, 1500.0);
            core.step(0.25);
        }
        double generated = core.getHydrogenGeneratedKg() - before;
        double arrived = core.getContainment().getHydrogenKg() - containmentBefore;
        Check.greaterThan(0.0, generated, "fuel generated hydrogen");
        Check.relative(generated, arrived, 0.01, "every kilogram generated arrives in containment");
    }
}
