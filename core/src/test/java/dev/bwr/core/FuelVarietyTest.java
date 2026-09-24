package dev.bwr.core;

import dev.bwr.core.fuel.*;
import dev.bwr.core.kinetics.*;
import dev.bwr.core.nodal.NodalFluxSolver;

public final class FuelVarietyTest {
    private static void check(boolean b, String why) { if (!b) throw new AssertionError(why); }
    private static void near(double a, double b) { check(Math.abs(a-b) < 1e-10, a + " != " + b); }
    private static FuelType type(String name) { return FuelType.presets().stream().filter(t -> t.name().equals(name)).findFirst().orElseThrow(); }
    public static void test01_catalogAndUraniumCurve() {
        check(FuelType.presets().size() == 19, "Missing fuel grades");
        check(FuelType.presets().stream().map(FuelType::name).distinct().count() == 19, "Duplicate grade");
        double previous = 0;
        for (String name : new String[]{"natural_uranium", "uranium_12", "uranium_14", "uranium_27", "leu", "uranium_495", "uranium_8"}) {
            var fuel = new FuelAssembly(type(name)); check(fuel.baseKInf() > previous, "Enrichment curve regressed: " + name); previous = fuel.baseKInf();
        }
        check(new FuelAssembly(type("natural_uranium")).kInf() < 1, "Natural uranium mislabeled as self-sustaining BWR fuel");
        check(new FuelAssembly(type("gadolinia_uranium")).kInf() < new FuelAssembly(FuelType.LEU).kInf(), "Gadolinia does not absorb");
        near(FuelType.LEU.kInfBase(), 1.22); near(FuelType.HEU.kInfBase(), 1.45);
    }
    public static void test02_insertsDoNotBecomeFuelAndShuffleKeepsExposure() {
        var loading = new CoreLoading(3); loading.load(0, new FuelAssembly(FuelType.LEU));
        var insert = new CoreInsert(CoreInsert.Kind.COBALT_TARGET, 333); loading.loadInsert(4, insert);
        check(loading.isOccupied(4) && loading.assemblyAt(4) == null, "Non-fuel occupancy lost");
        near(loading.loadedAssemblyCount(), 1); near(loading.totalHeavyMetalTonnes(), .18); near(loading.powerWeight(4), 0);
        try { loading.load(4, new FuelAssembly(FuelType.LEU)); throw new AssertionError("Overwrote target"); } catch (IllegalStateException expected) { }
        loading.swap(0, 4); check(loading.insertAt(0) == insert && loading.assemblyAt(4) != null, "Shuffle lost inventory");
        near(loading.insertAt(0).exposureSeconds(), 333); loading.unloadAll(); near(loading.insertCount(), 0);
    }
    public static void test03_absorbersReduceMultiplicationAndMakeNoHeat() {
        var config = new CoreConfig(); var loading = new CoreLoading(5);
        for (int i=0; i<25; i++) if (i != 12) loading.load(i, new FuelAssembly(FuelType.LEU));
        double clean = loading.kEffAllRodsOut();
        loading.loadInsert(12, new CoreInsert(CoreInsert.Kind.BORON_ABSORBER, 0));
        check(loading.kEffAllRodsOut() < clean, "Fixed absorber missing from bulk balance");
        var solver = new NodalFluxSolver(config, loading); loading.setPowerWeightSource(solver); loading.powerWeights();
        near(loading.powerWeight(12), 0); near(loading.assemblyThermalMW(3000)[12], 0);
        check(solver.lastSolution().assemblyFlux(12) > 0, "Target has no incident flux");
    }
    public static void test04_sourcesAreAdditiveAndSecondaryNeedsActivation() {
        var source = new CoreInsert(CoreInsert.Kind.ANTIMONY_BERYLLIUM_SOURCE, 0); near(source.sourcePerSecond(), 0);
        source.irradiate(1,300); near(source.progress(), .5); check(source.sourcePerSecond() > 0, "Secondary source not activated");
        var config = new CoreConfig(); var base = new PointKinetics(config, DelayedNeutronData.U235);
        var added = new PointKinetics(config, DelayedNeutronData.U235);
        added.setInstalledSourcePerSecond(.02); double before = added.getSourceStrengthPerSecond();
        for(int i=0;i<100;i++) { base.step(-.1,.05); added.step(-.1,.05); }
        check(added.getNeutronPowerFraction() > base.getNeutronPowerFraction(), "Source missing from kinetics");
        near(added.getSourceStrengthPerSecond(), before); added.setInstalledSourcePerSecond(0);
    }
    public static void test05_targetProgressIsFiniteAndBounded() {
        var target = new CoreInsert(CoreInsert.Kind.TRITIUM_TARGET, 0);
        for(double v:new double[]{0, -1, Double.NaN, Double.POSITIVE_INFINITY}) target.irradiate(v,1800);
        near(target.progress(),0); target.irradiate(.5,1800); near(target.progress(),.5);
        var restored = new CoreInsert(target.kind(),target.exposureSeconds()); restored.irradiate(1,1800);
        check(restored.complete(),"Target did not complete"); near(restored.progress(),1);
        restored.irradiate(100,1e9); near(restored.progress(),1);
        var empty = new CoreLoading(3);empty.loadInsert(4,restored);near(empty.loadedAssemblyCount(),0);near(empty.kEffAllRodsOut(),0);
    }
}
