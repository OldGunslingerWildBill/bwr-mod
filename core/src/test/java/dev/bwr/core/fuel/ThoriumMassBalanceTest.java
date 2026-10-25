package dev.bwr.core.fuel;

import dev.bwr.core.Check;

/** The thorium assembly's cost: natural uranium for the driver, thorium and depleted uranium, worked by hand for the shipped grades. */
public final class ThoriumMassBalanceTest {

    private ThoriumMassBalanceTest() {
    }

    /**
     * Four ingots of heavy metal, driver at 19.75 percent, natural feed 0.711 percent, tails 0.25 percent: feed per driver ingot is
     * (0.1975 - 0.0025) / (0.00711 - 0.0025) = 42.30.
     * 2 percent: driver 4 x 0.02 / 0.1975 = 0.4051, feed 17.13 (18), tails 16.73 (16), thorium 3.595 (4).
     * 3 percent: driver 0.6076, feed 25.70 (26), tails 25.09 (25), thorium 3.392 (4).
     * 5 percent: driver 1.0127, feed 42.84 (43), tails 41.83 (41), thorium 2.987 (3).
     */
    public static void test01_theShippedGradesCostWhatIsWorkedByHand() {
        int[][] expected = {{18, 16, 4}, {26, 25, 4}, {43, 41, 3}};   // feed, tails, thorium
        double[] fractions = {0.02, 0.03, 0.05};
        for (int i = 0; i < 3; i++) {
            Check.exactly(expected[i][0], ThoriumMassBalance.feedIngots(4, fractions[i]), "natural uranium for " + fractions[i]);
            Check.exactly(expected[i][1], ThoriumMassBalance.tailsIngots(4, fractions[i]), "depleted uranium for " + fractions[i]);
            Check.exactly(expected[i][2], ThoriumMassBalance.thoriumIngots(4, fractions[i]), "thorium for " + fractions[i]);
        }
        Check.absolute(0.4051, ThoriumMassBalance.driverIngots(4, 0.02), 1e-4, "driver ingots at 2 percent");
    }

    /** The machine never gains: the uranium in is at least the exact feed and the tails are at most the exact tails; thorium plus driver cover the heavy metal. */
    public static void test02_roundingOnlyLoses() {
        for (double f : new double[] {0.005, 0.02, 0.03, 0.05, 0.08, 0.15}) {
            double driver = ThoriumMassBalance.driverIngots(4, f);
            double feedExact = driver * EnrichmentMassBalance.feedPerProduct(ThoriumMassBalance.DRIVER_ASSAY);
            int feed = ThoriumMassBalance.feedIngots(4, f);
            int tails = ThoriumMassBalance.tailsIngots(4, f);
            Check.isTrue(feed >= feedExact - 1e-9 && feed < feedExact + 1.0, "feed is the exact feed rounded up at %s: %s vs %s", f, feed, feedExact);
            Check.isTrue(tails <= feedExact - driver + 1e-9 && tails > feedExact - driver - 1.0, "tails are the exact tails rounded down at %s", f);
            Check.isTrue(feed - tails >= driver - 1e-9, "uranium in less uranium out holds the driver at %s", f);
            int thorium = ThoriumMassBalance.thoriumIngots(4, f);
            Check.isTrue(thorium + driver >= 4 - 1e-9 && thorium + driver < 5, "thorium and the driver hold the heavy metal at %s", f);
        }
    }

    /** A fraction the driver cannot supply is refused. */
    public static void test03_impossibleFractionsAreRefused() {
        for (double bad : new double[] {0.0, -0.1, 0.1975, 0.5, Double.NaN}) {
            boolean refused = false;
            try {
                ThoriumMassBalance.feedIngots(4, bad);
            } catch (IllegalArgumentException e) {
                refused = true;
            }
            Check.isTrue(refused, "driver fraction %s is refused", bad);
        }
    }
}
