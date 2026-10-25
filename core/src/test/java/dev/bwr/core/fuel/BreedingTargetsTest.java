package dev.bwr.core.fuel;

import dev.bwr.core.Check;

/** The reactor-bred isotopes: exposure to product for every new target kind, and mass and yield sanity (BREEDING-TARGETS.md). */
public final class BreedingTargetsTest {

    private BreedingTargetsTest() {
    }

    private static final CoreInsert.Kind[] NEW_KINDS = {CoreInsert.Kind.BISMUTH_TARGET, CoreInsert.Kind.THORIUM_TARGET,
            CoreInsert.Kind.URANIUM_TARGET, CoreInsert.Kind.NEPTUNIUM_TARGET, CoreInsert.Kind.RADIUM_TARGET, CoreInsert.Kind.THULIUM_TARGET};
    private static final double[] HOURS = {96, 168, 240, 120, 168, 48};

    /** The calibration: the cobalt cassette, 72 game hours, is about 5 % converted, a few hundred kilocuries of cobalt-60. */
    public static void test01_theCobaltCalibrationIsAnIndustrialBatch() {
        double g = BreedingTargets.cassetteYieldGrams(CoreInsert.Kind.COBALT_TARGET);
        Check.relative(473.6, g, 0.01, "cobalt-60 in 10 kg of cobalt after one cycle");
        double curies = g * 1131.0;   // specific activity of Co-60, 1,131 Ci/g
        Check.isTrue(curies > 3.0e5 && curies < 1.0e6, "an industrial source batch, %s Ci", curies);
        Check.exactly(182.5, BreedingTargets.FLUENCE_HOURS_PER_GAME_HOUR, "one game hour is 182.5 hours of flux");
        Check.note("Co-60 per 10 kg cassette: %.1f g, %.0f Ci", g, curies);
    }

    /** Each new kind is a target with the documented rated time, and exposure integrates to completion and no further. */
    public static void test02_everyNewKindIsATargetThatCompletesAtItsRatedTime() {
        for (int i = 0; i < NEW_KINDS.length; i++) {
            CoreInsert.Kind kind = NEW_KINDS[i];
            Check.isTrue(kind.target(), "%s is a target", kind);
            Check.exactly(HOURS[i] * 3600.0, kind.exposureSeconds, kind + " rated time");
            var insert = new CoreInsert(kind, 0);
            insert.irradiate(0.5, HOURS[i] * 3600.0);
            Check.relative(0.5, insert.progress(), 1e-12, kind + " at half flux for the rated time");
            Check.isFalse(insert.complete(), kind + " is not done at half");
            insert.irradiate(0.5, HOURS[i] * 3600.0);
            Check.isTrue(insert.complete(), "%s completes at rated flux for the rated time", kind);
            insert.irradiate(1.0, 1.0e9);
            Check.relative(1.0, insert.progress(), 1e-12, kind + " stops at 100 %");
            Check.isTrue(BreedingTargets.routeOf(kind) != null, "%s has a route", kind);
            Check.relative(HOURS[i], BreedingTargets.routeOf(kind).hours(), 1e-12, kind + " route time is the kind's");
        }
        for (var old : new CoreInsert.Kind[] {CoreInsert.Kind.BORON_ABSORBER, CoreInsert.Kind.ANTIMONY_BERYLLIUM_SOURCE}) {
            Check.isFalse(old.target(), old + " is still not a target");
        }
    }

    /** Every product is made, in the order of magnitude the physics gives, and no more than the starting atoms allow. */
    public static void test03_yieldsAreSaneAndNeverExceedTheStartingAtoms() {
        for (var route : BreedingTargets.ROUTES) {
            double[] grams = BreedingTargets.inventoryGrams(route, route.hours() * 3600.0);
            double atoms0 = route.payloadGrams() * route.startFraction() / route.chain().get(0).massNumber();
            double atoms = 0;
            for (int i = 0; i < grams.length; i++) {
                Check.isTrue(grams[i] >= 0, "%s: no negative mass", route.chain().get(i).name());
                atoms += grams[i] / route.chain().get(i).massNumber();
            }
            Check.isTrue(atoms <= atoms0 * (1 + 1e-9), "%s: atoms %s from %s", route.kind(), atoms, atoms0);
            double product = grams[grams.length - 1];
            Check.isTrue(product > 0 && product < route.payloadGrams() * route.startFraction() * 0.2,
                    "%s: product %s g of %s g", route.kind(), product, route.payloadGrams());
            Check.note("%s: %.4g g of %s from %.4g g in %.0f h", route.kind(), product, route.product().name(), route.payloadGrams(), route.hours());
        }
    }

    /**
     * Bismuth to polonium burns nothing, so the only atoms that leave the chain are polonium's own decay to lead-206: the chain never
     * gains an atom and loses only about as many as the polonium that has decayed (a few parts in 100,000), a check that the matrix
     * exponential has no leak.
     */
    public static void test04_bismuthLosesOnlyWhatPoloniumDecays() {
        var r = BreedingTargets.BISMUTH;
        double[] g = BreedingTargets.inventoryGrams(r, r.hours() * 3600.0);
        double atoms = g[0] / 209 + g[1] / 210 + g[2] / 210;
        double atoms0 = r.payloadGrams() / 209;
        Check.isTrue(atoms <= atoms0 * (1 + 1e-12), "the chain gained atoms: %s from %s", atoms, atoms0);
        double lost = atoms0 - atoms;
        double poloniumAtoms = g[2] / 210;
        double lambda = Math.log(2) / (138.376 * 86_400.0);
        double meanTime = BreedingTargets.fluenceSeconds(r.hours() * 3600.0) / 2;   // polonium accumulates through the run
        double expected = poloniumAtoms * lambda * meanTime;
        Check.isTrue(lost > 0 && lost / atoms0 < 1e-4 && lost > 0.3 * expected && lost < 3 * expected,
                "lost %s atoms of %s, polonium decay would take about %s", lost, atoms0, expected);
        Check.exactly(0.0, BreedingTargets.productGrams(r, 0.0), "nothing at the start");
        Check.note("Bi-209 chain: %.3e of the atoms decayed to Pb-206 (expected about %.3e)", lost / atoms0, expected / atoms0);
    }

    /** Against the textbook small-conversion limit: product = N0 sigma phi / lambda (1 - exp(-lambda T)), the 5-day delay a rounding error. */
    public static void test05_polonium210AgreesWithTheHandCalculation() {
        double lambda = Math.log(2) / (138.376 * 86_400.0);
        double rate = 0.0162e-24 * BreedingTargets.THERMAL_FLUX;
        double t = BreedingTargets.fluenceSeconds(96 * 3600.0);
        double hand = 10_000.0 / 209 * rate / lambda * (1 - Math.exp(-lambda * t)) * 210;
        double model = BreedingTargets.productGrams(BreedingTargets.BISMUTH, 96 * 3600.0);
        Check.relative(hand, model, 0.03, "polonium-210 in a bismuth cassette");
        Check.isTrue(model > 0.07 && model < 0.09, "about 82 mg of polonium: %s g", model);
        Check.note("Po-210: %.1f mg, %.1f W", model * 1000, model * 140);
    }

    /** More exposure never makes less until burn-up bites, and the short-lived products saturate (polonium, thulium). */
    public static void test06_saturationAndMonotonicity() {
        for (var route : BreedingTargets.ROUTES) {
            double prev = -1;
            for (double f : new double[] {0.1, 0.25, 0.5, 1.0}) {
                double g = BreedingTargets.productGrams(route, route.hours() * 3600.0 * f);
                Check.isTrue(g > prev, "%s grows with exposure up to the rated time", route.kind());
                prev = g;
            }
        }
        double po1 = BreedingTargets.productGrams(BreedingTargets.BISMUTH, 96 * 3600.0);
        double po2 = BreedingTargets.productGrams(BreedingTargets.BISMUTH, 192 * 3600.0);
        Check.isTrue(po2 / po1 < 1.06, "polonium has saturated at the rated time: doubling it adds %s", po2 / po1 - 1);
        double tm1 = BreedingTargets.productGrams(BreedingTargets.THULIUM, 48 * 3600.0);
        double tm2 = BreedingTargets.productGrams(BreedingTargets.THULIUM, 96 * 3600.0);
        Check.isTrue(tm2 / tm1 < 1.06 && tm2 > tm1, "thulium-170 is saturating: %s", tm2 / tm1 - 1);
        double np1 = BreedingTargets.productGrams(BreedingTargets.NEPTUNIUM, 120 * 3600.0);
        double np2 = BreedingTargets.productGrams(BreedingTargets.NEPTUNIUM, 480 * 3600.0);
        Check.isTrue(np2 < np1 * 1.05, "plutonium-238 burns away if left in: %s then %s", np1, np2);
    }

    /** The ingot in the data map is one cassette's yield, to 3 %, so one target makes one ingot. */
    public static void test07_oneCassetteMakesOneIngot() {
        double[][] pairs = {
                {BreedingTargets.PO210_INGOT_GRAMS, BreedingTargets.cassetteYieldGrams(CoreInsert.Kind.BISMUTH_TARGET)},
                {BreedingTargets.U233_INGOT_GRAMS, BreedingTargets.cassetteYieldGrams(CoreInsert.Kind.THORIUM_TARGET)},
                {BreedingTargets.NP237_INGOT_GRAMS, BreedingTargets.cassetteYieldGrams(CoreInsert.Kind.URANIUM_TARGET)},
                {BreedingTargets.PU238_INGOT_GRAMS, BreedingTargets.cassetteYieldGrams(CoreInsert.Kind.NEPTUNIUM_TARGET)},
                {BreedingTargets.AC227_INGOT_GRAMS, BreedingTargets.cassetteYieldGrams(CoreInsert.Kind.RADIUM_TARGET)},
                {BreedingTargets.TM170_INGOT_GRAMS, BreedingTargets.cassetteYieldGrams(CoreInsert.Kind.THULIUM_TARGET)}};
        for (int i = 0; i < pairs.length; i++) {
            Check.relative(pairs[i][1], pairs[i][0], 0.03, NEW_KINDS[i] + ": ingot grams against the cassette's yield");
        }
        Check.relative(8 * BreedingTargets.NP237_INGOT_GRAMS, BreedingTargets.NEPTUNIUM.payloadGrams(), 0.01, "eight neptunium ingots");
    }

    /** Heat from each ingot at its specific power: polonium and thulium are tens of watts, plutonium-238 a fraction of one, actinium milliwatts. */
    public static void test08_theHeatOfEachIngot() {
        double po = BreedingTargets.PO210_INGOT_GRAMS * 140.0;
        double tm = BreedingTargets.TM170_INGOT_GRAMS * 11.5;
        double pu = BreedingTargets.PU238_INGOT_GRAMS * 0.5674;
        double ac = BreedingTargets.AC227_INGOT_GRAMS * 14.6;
        Check.isTrue(po > 10 && po < 13, "Po-210 ingot %s W", po);
        Check.isTrue(tm > 200 && tm < 240, "Tm-170 ingot %s W", tm);
        Check.isTrue(pu > 0.1 && pu < 0.2, "Pu-238 ingot %s W", pu);
        Check.isTrue(ac > 0.01 && ac < 0.02, "Ac-227 ingot %s W", ac);
        Check.note("heat per ingot: Po-210 %.1f W, Tm-170 %.0f W, Pu-238 %.2f W, Ac-227 %.0f mW", po, tm, pu, ac * 1000);
    }

    /** U-233 as the driver of thorium fuel: worked by hand for the shipped grades; rounding only costs the player. */
    public static void test09_u233DriverCostsWhatIsWorkedByHand() {
        // 4 ingot-units x 45 kg x fraction / 480 g: 2 % = 3,600 g = 7.5 (8); 3 % = 5,400 g = 11.25 (12); 5 % = 9,000 g = 18.75 (19)
        int[] expected = {8, 12, 19};
        double[] fractions = {0.02, 0.03, 0.05};
        for (int i = 0; i < 3; i++) {
            Check.exactly(expected[i], ThoriumMassBalance.u233Ingots(4, fractions[i]), "U-233 ingots for " + fractions[i]);
            Check.exactly(4, ThoriumMassBalance.thoriumIngotsWithU233(4, fractions[i]), "thorium for " + fractions[i]);
            double grams = ThoriumMassBalance.u233Ingots(4, fractions[i]) * (double) ThoriumMassBalance.U233_INGOT_GRAMS;
            Check.isTrue(grams >= 4 * 45_000.0 * fractions[i] - 1e-6 && grams < 4 * 45_000.0 * fractions[i] + 480, "the driver is covered, rounded up: %s g", grams);
        }
        Check.exactly(18, ThoriumMassBalance.feedIngots(4, 0.02), "natural uranium route, unchanged");
        Check.relative(480.0, BreedingTargets.cassetteYieldGrams(CoreInsert.Kind.THORIUM_TARGET), 0.03, "U-233 per thorium cassette");
        boolean refused = false;
        try {
            ThoriumMassBalance.u233Ingots(4, 0.5);
        } catch (IllegalArgumentException e) {
            refused = true;
        }
        Check.isTrue(refused, "an impossible driver fraction is refused");
    }
}
