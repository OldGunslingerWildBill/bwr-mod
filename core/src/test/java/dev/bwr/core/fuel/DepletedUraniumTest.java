package dev.bwr.core.fuel;

import dev.bwr.core.Check;
import dev.bwr.core.CoreConfig;
import dev.bwr.core.ReactorCore;

/**
 * Depleted uranium as a fuel: a blanket that absorbs when fresh and breeds
 * plutonium as it burns. The numbers are justified in FUELS-AND-RODS.md; these
 * tests pin the behaviour they exist to produce.
 */
public final class DepletedUraniumTest {

    private DepletedUraniumTest() {
    }

    private static FuelType du() {
        return FuelType.presets().stream().filter(t -> t.name().equals("depleted_uranium")).findFirst().orElseThrow();
    }

    private static FuelType natural() {
        return FuelType.presets().stream().filter(t -> t.name().equals("natural_uranium")).findFirst().orElseThrow();
    }

    /** Fresh, it cannot sustain a chain reaction on its own, and well short of natural uranium, which cannot either. */
    public static void test01_freshDepletedUraniumIsANetAbsorber() {
        FuelAssembly fresh = new FuelAssembly(du());
        Check.exactly(0.0025, du().nominalEnrichmentWeightFraction(), "assay of the tails");
        Check.lessThan(0.5, fresh.kInf(), "fresh depleted uranium k-infinity is well below 1 (a net absorber)");
        Check.lessThan(natural().kInfBase(), fresh.kInf(), "depleted uranium is weaker than natural uranium");
        Check.exactly(natural().beta(), du().beta(), "beta like natural uranium");
        Check.exactly(natural().dopplerCoeffPerC(), du().dopplerCoeffPerC(), "Doppler like natural uranium");
        Check.note("fresh depleted uranium k_inf %.4f (natural uranium %.4f)", fresh.kInf(), new FuelAssembly(natural()).kInf());
    }

    /** U-238 capture breeds plutonium, so k-infinity climbs with burnup (thorium's mechanism, a smaller gain). */
    public static void test02_kInfinityRisesWithBurnup() {
        Check.isTrue(du().isNetBreeder(), "depleted uranium is a net breeder");
        Check.greaterThan(0.0, du().conversionGainKInf(), "positive conversion gain");
        Check.lessThan(0.1, du().conversionGainKInf(), "smaller gain than thorium's 0.1 (U-238 captures less than Th-232)");
        FuelAssembly assembly = new FuelAssembly(du());
        double previous = assembly.kInf();
        double fresh = previous;
        for (int step = 1; step <= 10; step++) {
            assembly.setBurnupMwdPerTonne(1000.0 * step);
            double now = assembly.kInf();
            Check.greaterThan(previous, now, "k-infinity at " + 1000 * step + " MWd/t must exceed the value 1000 MWd/t earlier");
            previous = now;
        }
        Check.note("depleted uranium k_inf %.4f fresh -> %.4f at 10 GWd/t", fresh, previous);
        // it never becomes a fuel: the bred plutonium cannot lift it near 1
        assembly.setBurnupMwdPerTonne(25_000.0);
        Check.lessThan(0.5, assembly.kInf(), "even bred-in, depleted uranium stays far below critical");
        // and natural uranium, which does not breed here, only falls
        FuelAssembly nat = new FuelAssembly(natural());
        double natFresh = nat.kInf();
        nat.setBurnupMwdPerTonne(10_000.0);
        Check.lessThan(natFresh, nat.kInf(), "natural uranium falls with burnup");
    }

    /** A blanket among fuel: the core loads it, steps and keeps finite numbers, and it lowers the multiplication. */
    public static void test03_aDepletedUraniumAssemblyLoadsIntoACoreAndRuns() {
        ReactorCore core = new ReactorCore(new CoreConfig());
        CoreLoading loading = core.getCoreLoading();
        double before = loading.kEffAllRodsOut();
        int replaced = 0;
        for (int i = loading.positionCount() / 2; i < loading.positionCount() && replaced < 20; i++) { // from the middle of the lattice, where the flux is
            if (loading.assemblyAt(i) != null) {
                loading.load(i, new FuelAssembly(du()));
                replaced++;
            }
        }
        Check.exactly(20, replaced, "positions given to depleted uranium");
        Check.lessThan(before, loading.kEffAllRodsOut(), "a blanket in place of fuel lowers k_eff");
        Check.finiteAndPositive(loading.aggregateKInf(), "aggregate k-infinity with a blanket");
        for (int i = 0; i < 200; i++) {
            core.step();
        }
        Check.finite(core.getNeutronPowerFraction(), "neutron power after 200 steps");
        Check.finite(core.getThermalPowerMW(), "thermal power after 200 steps");
        Check.finite(loading.effectiveBeta(), "effective beta");
        Check.note("20 blanket assemblies: k_eff(ARO) %.4f -> %.4f", before, loading.kEffAllRodsOut());
    }
}
