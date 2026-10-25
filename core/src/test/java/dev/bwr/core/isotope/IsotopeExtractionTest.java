package dev.bwr.core.isotope;

import dev.bwr.core.Check;
import dev.bwr.core.fuel.SpentFuel;

/** Mass conservation of the isotope separator's split, to the grain. */
public final class IsotopeExtractionTest {

    private IsotopeExtractionTest() {
    }

    /** A waste item is 5 kg and a grain 5 mg; the four parts add up to the input for any input. */
    public static void test01_splitConservesMassToTheGrain() {
        Check.relative(0.005, IsotopeExtraction.GRAMS_PER_GRAIN, 1e-12, "a grain is 5 mg");
        long seed = 12345;
        for (int i = 0; i < 20_000; i++) {
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            long grains = Math.floorMod(seed >>> 20, 5_000_000_000L);
            IsotopeExtraction.Split s = IsotopeExtraction.split(grains);
            Check.exactly(grains, s.total(), "the four parts add up to the input");
            Check.isTrue(s.strontium() >= 0 && s.caesium() >= 0 && s.americium() >= 0 && s.stripped() >= 0, "no negative part");
        }
        Check.exactly(0L, IsotopeExtraction.split(0L).total(), "nothing in, nothing out");
        Check.exactly(0L, IsotopeExtraction.split(-5L).total(), "a negative input is nothing");
    }

    /** One waste item: 109.7 g of strontium, 181.3 g of caesium, 75 g of americium, 4,634 g stripped. */
    public static void test02_oneWasteItemYieldsTheDocumentedMasses() {
        IsotopeExtraction.Split s = IsotopeExtraction.split(SpentFuel.GRAINS_PER_NINTH);
        Check.exactly(21_945L, s.strontium(), "strontium grains of one waste item");
        Check.exactly(36_267L, s.caesium(), "caesium grains of one waste item");
        Check.exactly(15_000L, s.americium(), "americium grains of one waste item");
        Check.relative(109.725, IsotopeExtraction.grams(s.strontium()), 1e-9, "grams of Sr-90 per waste item");
        Check.relative(181.335, IsotopeExtraction.grams(s.caesium()), 1e-9, "grams of Cs-137 per waste item");
        Check.relative(75.0, IsotopeExtraction.grams(s.americium()), 1e-9, "grams of Am-241 per waste item");
        Check.exactly(50_000L, IsotopeExtraction.grainsOf(250.0), "a 250 g capsule is 50,000 grains");
        Check.note("per waste item: Sr %.1f g, Cs %.1f g, Am %.1f g, stripped %.1f g", IsotopeExtraction.grams(s.strontium()),
                IsotopeExtraction.grams(s.caesium()), IsotopeExtraction.grams(s.americium()), IsotopeExtraction.grams(s.stripped()));
    }

    /** Chained splits never create mass: whole waste items in, grains out, summed over many items. */
    public static void test03_manyItemsAddUp() {
        long in = 0;
        long out = 0;
        for (int n = 1; n <= 500; n++) {
            long grains = n * SpentFuel.GRAINS_PER_NINTH + n * 7L;
            in += grains;
            out += IsotopeExtraction.split(grains).total();
        }
        Check.exactly(in, out, "grains in equal grains out over 500 batches");
    }
}
