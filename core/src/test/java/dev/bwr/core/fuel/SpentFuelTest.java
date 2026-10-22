package dev.bwr.core.fuel;

import dev.bwr.core.Check;

/**
 * The reprocessing split of a spent assembly ({@link SpentFuel}). The expected grains are worked out independently
 * (a separate implementation of the same model, one assembly at a time), not read back from the class.
 */
public final class SpentFuelTest {

    private SpentFuelTest() {
    }

    private static FuelType type(String name) {
        return FuelType.presets().stream().filter(t -> t.name().equals(name)).findFirst().orElseThrow();
    }

    private static FuelAssembly spent(String name, double enrichment, double burnup) {
        FuelAssembly assembly = new FuelAssembly(type(name), enrichment, FuelAssembly.REFERENCE_HEAVY_METAL_MASS_KG);
        assembly.setBurnupMwdPerTonne(burnup);
        return assembly;
    }

    /** {fuel, enrichment, burnup, plutonium start, waste grains, plutonium grains, uranium grains}. */
    private static final Object[][] TABLE = {
            {"leu", 0.035, 20000.0, false, 748217L, 202007L, 35049776L},
            {"leu", 0.035, 29000.0, false, 1084915L, 243095L, 34671990L},
            {"leu", 0.035, 45000.0, false, 1683489L, 265255L, 34051256L},
            {"natural_uranium", 0.00711, 10000.0, false, 374108L, 121429L, 35504463L},
            {"uranium_495", 0.0495, 55000.0, false, 2057597L, 227437L, 33714966L},
            {"heu", 0.20, 40000.0, false, 1496434L, 91642L, 34411924L},
            {"depleted_uranium", 0.0025, 10000.0, false, 374108L, 75713L, 35550179L},
            {"depleted_uranium", 0.0025, 20000.0, false, 748217L, 85067L, 35166716L},
            {"mox", 0.07, 45000.0, true, 1654886L, 998041L, 33347073L},
            {"plutonium", 0.06, 45000.0, true, 1611851L, 1015525L, 33372624L},
    };

    /** The table of expected splits, grain for grain (a grain is a millionth of a nugget). */
    public static void test01_splitMatchesTheWorkedTable() {
        for (Object[] row : TABLE) {
            SpentFuel.Split split = SpentFuel.split(spent((String) row[0], (double) row[1], (double) row[2]), (boolean) row[3]);
            String what = row[0] + " at " + row[2] + " MWd/t";
            Check.exactly((long) row[4], split.fissionProducts(), what + ": fission product grains");
            Check.exactly((long) row[5], split.plutonium(), what + ": plutonium grains");
            Check.exactly((long) row[6], split.uranium(), what + ": uranium grains");
        }
    }

    /** Nothing is made or lost: the three always sum to the assembly, to the grain, at any burnup. */
    public static void test02_theSplitConservesHeavyMetalExactly() {
        for (String name : new String[] {"leu", "natural_uranium", "heu", "depleted_uranium", "mox", "plutonium", "uranium_8"}) {
            FuelType t = type(name);
            boolean pu = name.equals("mox") || name.equals("plutonium");
            for (double burnup : new double[] {1e-9, 1.0, 777.7, 5000, 12345.6, 29000, 45000, 80000, 150000, 1e6, 1e9}) {
                SpentFuel.Split split = SpentFuel.split(spent(name, t.nominalEnrichmentWeightFraction(), burnup), pu);
                Check.exactly(SpentFuel.ASSEMBLY_GRAINS, split.total(), name + " at " + burnup + ": the parts do not sum to the assembly");
                Check.isTrue(split.fissionProducts() >= 0 && split.plutonium() >= 0 && split.uranium() >= 0,
                        name + " at " + burnup + ": a negative amount " + split);
            }
        }
    }

    /** A fresh assembly has nothing to reprocess: no waste, no plutonium, every grain still uranium. */
    public static void test03_aFreshAssemblyHasNoWasteAndNoPlutonium() {
        SpentFuel.Split split = SpentFuel.split(new FuelAssembly(type("leu")), false);
        Check.exactly(0L, split.fissionProducts(), "fresh fission products");
        Check.exactly(0L, split.plutonium(), "fresh plutonium");
        Check.exactly(SpentFuel.ASSEMBLY_GRAINS, split.uranium(), "fresh uranium");
    }

    /** The waste is the heavy metal that was fissioned: about 1.04 g a MWd, the same at any enrichment, scaling with the fuel's heat per fission. */
    public static void test04_wasteIsTheFissionedMass() {
        double g = SpentFuel.fissionedFractionPerMwdPerTonne(type("leu"));
        Check.relative(1.0393e-6, g, 1e-3, "fraction fissioned per MWd/t at 202.5 MeV");
        Check.relative(g * 202.5 / 211.5, SpentFuel.fissionedFractionPerMwdPerTonne(type("plutonium")), 1e-12, "more energy per fission, fewer fissions per MWd");
        // linear in burnup, independent of enrichment: the fission product share is the same for LEU and natural uranium at one burnup
        double a = SpentFuel.inventory(spent("leu", 0.035, 30000), false).fissionProducts();
        double b = SpentFuel.inventory(spent("natural_uranium", 0.00711, 30000), false).fissionProducts();
        Check.relative(a, b, 1e-12, "fission products at one burnup do not depend on the enrichment");
        Check.relative(2.0 * a, SpentFuel.inventory(spent("leu", 0.035, 60000), false).fissionProducts(), 1e-12, "doubling burnup doubles the waste");
    }

    /** More burnup, more waste; the plutonium of a uranium fuel is bred, so it grows from nothing and levels off rather than climbing without end. */
    public static void test05_plutoniumGrowsThenLevelsOffForUraniumFuel() {
        double previous = 0.0;
        for (double burnup = 5000; burnup <= 45000; burnup += 5000) {
            double p = SpentFuel.inventory(spent("leu", 0.035, burnup), false).plutonium();
            Check.greaterThan(previous, p, "plutonium of spent LEU at " + burnup + " MWd/t rises");
            previous = p;
        }
        double at45 = SpentFuel.inventory(spent("leu", 0.035, 45000), false).plutonium();
        double at90 = SpentFuel.inventory(spent("leu", 0.035, 90000), false).plutonium();
        Check.lessThan(at45 * 1.1, at90, "plutonium does not keep climbing at twice the burnup");
        // typical LWR spent fuel holds about 0.7 to 1 % plutonium (fissile part about 0.7 here)
        Check.inRange(0.005, 0.009, at45, "spent LEU plutonium share at 45 GWd/t");
    }

    /** A blanket makes plutonium through its breeding gain: more than a net absorber that does not breed would, and from zero. */
    public static void test06_depletedUraniumBreedsPlutonium() {
        Check.exactly(0.0, SpentFuel.inventory(new FuelAssembly(type("depleted_uranium")), false).plutonium(), "fresh blanket");
        double at10 = SpentFuel.inventory(spent("depleted_uranium", 0.0025, 10000), false).plutonium();
        double at20 = SpentFuel.inventory(spent("depleted_uranium", 0.0025, 20000), false).plutonium();
        Check.greaterThan(0.0015, at10, "blanket plutonium at 10 GWd/t");
        Check.greaterThan(at10, at20, "more plutonium at 20 GWd/t than 10");
        // the breeding gain is what lifts the fissile balance above where the blanket started
        FuelAssembly blanket = spent("depleted_uranium", 0.0025, 20000);
        Check.greaterThan(0.0025, blanket.fissileEquivalentWeightFraction(), "a breeder's fissile balance is above its start");
        FuelType noGain = new FuelType("du_no_gain", 0.262, 0.006502, 4e-05, 5e-07, -1.6e-05, 0, 202.5, 0.0025, 0.0);
        FuelAssembly flat = new FuelAssembly(noGain, 0.0025, FuelAssembly.REFERENCE_HEAVY_METAL_MASS_KG);
        flat.setBurnupMwdPerTonne(20000);
        Check.lessThan(0.0025, flat.fissileEquivalentWeightFraction(), "the same blanket without the gain only loses fissile");
        Check.greaterThan(SpentFuel.inventory(flat, false).plutonium(), at20, "the breeding gain adds plutonium");
    }

    /** The fissile balance is the inverse of the enrichment response: a fresh assembly gives back its enrichment, burning lowers it. */
    public static void test07_fissileEquivalentInvertsTheEnrichmentResponse() {
        for (String name : new String[] {"leu", "natural_uranium", "heu", "depleted_uranium", "mox", "uranium_27"}) {
            FuelAssembly fresh = new FuelAssembly(type(name));
            Check.relative(fresh.enrichmentWeightFraction(), fresh.fissileEquivalentWeightFraction(), 1e-9, name + " fresh gives back its enrichment");
        }
        FuelAssembly leu = spent("leu", 0.035, 30000);
        Check.lessThan(0.035, leu.fissileEquivalentWeightFraction(), "burned LEU has less fissile");
        Check.greaterThan(0.015, leu.fissileEquivalentWeightFraction(), "but not none");
        // worked by hand at 45 GWd/t: 1.22 k_inf, 0.2925 lost, back through u = k / 1.674: about 1.65 %
        Check.relative(0.016494, spent("leu", 0.035, 45000).fissileEquivalentWeightFraction(), 1e-4, "burned LEU at 45 GWd/t");
    }

    /** Recycling: spent MOX returns less plutonium than it started with (burned), but a real share of it. */
    public static void test08_mixedOxideReturnsPartOfItsPlutonium() {
        SpentFuel.Inventory inventory = SpentFuel.inventory(spent("mox", 0.07, 45000), true);
        Check.inRange(0.02, 0.04, inventory.plutonium(), "spent MOX plutonium share");
        Check.lessThan(0.07, inventory.plutonium(), "less than it started with");
        // the same assembly counted as a uranium fuel has none of its starting plutonium, only what it bred
        Check.lessThan(inventory.plutonium(), SpentFuel.inventory(spent("mox", 0.07, 45000), false).plutonium(), "a plutonium start keeps what it began with");
    }
}
