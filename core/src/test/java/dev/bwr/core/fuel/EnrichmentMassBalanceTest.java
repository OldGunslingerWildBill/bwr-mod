package dev.bwr.core.fuel;

import dev.bwr.core.Check;

/**
 * The enrichment mass balance behind the fuel fabricator's item route. Every
 * expected number below is worked by hand from
 * {@code feed/product = (xp - 0.25%) / (0.711% - 0.25%)} (the denominator is
 * 0.461 percentage points), not read back from the code under test.
 */
public final class EnrichmentMassBalanceTest {

    private EnrichmentMassBalanceTest() {
    }

    /** Hand-worked ratios, to the 0.1 the design table quotes. */
    public static void test01_feedPerProductMatchesTheHandCalculation() {
        double[][] cases = {
                {0.00711, 1.0},          // natural: the feed is the product
                {0.027, 2.45 / 0.461},   // 5.3
                {0.035, 3.25 / 0.461},   // 7.05
                {0.0495, 4.70 / 0.461},  // 10.2
                {0.08, 7.75 / 0.461},    // 16.8
                {0.1975, 19.50 / 0.461}, // 42.3
                {0.20, 19.75 / 0.461},   // 42.8
        };
        for (double[] c : cases) {
            Check.relative(c[1], EnrichmentMassBalance.feedPerProduct(c[0]), 1e-12, "feed per product at " + c[0]);
        }
        Check.inRange(7.04, 7.06, EnrichmentMassBalance.feedPerProduct(0.035), "3.5 % feed per product, about 7.1");
    }

    /** Four units of product per assembly: the table the docs quote, every count worked by hand. */
    public static void test02_ingotCostsForFourUnitsOfProduct() {
        // {assay x 1e5, feed rounded up, tails rounded down}
        // 3.5 %: 4 x 7.0498 = 28.2 -> 29 in; floor(28.2) - 4 = 24 out
        int[][] expected = {
                {711, 4, 0},
                {1200, 9, 4},      // 4 x 2.0607 = 8.24
                {1400, 10, 5},     // 4 x 2.4946 = 9.98
                {2700, 22, 17},    // 4 x 5.3145 = 21.26
                {3500, 29, 24},    // 4 x 7.0498 = 28.20
                {4950, 41, 36},    // 4 x 10.195 = 40.78
                {8000, 68, 63},    // 4 x 16.811 = 67.24
                {19750, 170, 165}, // 4 x 42.299 = 169.20
                {20000, 172, 167}, // 4 x 42.842 = 171.37
        };
        for (int[] row : expected) {
            double assay = row[0] / 100_000.0;
            Check.exactly(row[1], EnrichmentMassBalance.feedUnits(4, assay), "feed units for 4 at " + assay);
            Check.exactly(row[2], EnrichmentMassBalance.tailsUnits(4, assay), "tails units for 4 at " + assay);
        }
    }

    /** The machine can lose to rounding but never gain: product + tails never exceeds the feed. */
    public static void test03_roundingNeverMintsUranium() {
        for (int product = 1; product <= 12; product++) {
            for (int g = 711; g <= 20_000; g += 37) {
                double assay = g / 100_000.0;
                int feed = EnrichmentMassBalance.feedUnits(product, assay);
                int tails = EnrichmentMassBalance.tailsUnits(product, assay);
                Check.isTrue(tails >= 0, "negative tails at %s", assay);
                Check.isTrue(product + tails <= feed, "%d product + %d tails exceeds %d feed at %s", product, tails, feed, assay);
                Check.isTrue(feed - product - tails <= 1, "lost more than one unit to rounding at %s", assay);
                // the feed really holds enough U-235: feed * xf >= product * xp + tails * xt
                double have = feed * EnrichmentMassBalance.NATURAL_ASSAY;
                double need = product * assay + (feed - product) * EnrichmentMassBalance.TAILS_ASSAY;
                Check.isTrue(have + 1e-9 >= need, "U-235 does not balance at %s: %s < %s", assay, have, need);
            }
        }
    }

    /** More enriched never costs less, and a natural product costs exactly itself. */
    public static void test04_costGrowsWithEnrichmentAndNaturalCostsItself() {
        int previous = 0;
        for (int g = 711; g <= 20_000; g += 111) {
            int feed = EnrichmentMassBalance.feedUnits(4, g / 100_000.0);
            Check.isTrue(feed >= previous, "feed fell as enrichment rose at %d", g);
            previous = feed;
        }
        for (int product = 1; product <= 100; product++) {
            Check.exactly(product, EnrichmentMassBalance.feedUnits(product, EnrichmentMassBalance.NATURAL_ASSAY),
                    "natural feed for " + product);
            Check.exactly(0, EnrichmentMassBalance.tailsUnits(product, EnrichmentMassBalance.NATURAL_ASSAY),
                    "natural tails for " + product);
        }
    }

    /** Nonsense is refused rather than turned into a number. */
    public static void test05_impossibleAssaysAreRefused() {
        double[][] bad = {{0.005, 0.00711, 0.0025}, {1.0, 0.00711, 0.0025}, {0.035, 0.00711, 0.00711}, {0.035, 0.00711, 0.0}};
        for (double[] b : bad) {
            boolean refused = false;
            try {
                EnrichmentMassBalance.feedUnits(4, b[0], b[1], b[2]);
            } catch (IllegalArgumentException expected) {
                refused = true;
            }
            Check.isTrue(refused, "assays %s were accepted", java.util.Arrays.toString(b));
        }
    }
}
