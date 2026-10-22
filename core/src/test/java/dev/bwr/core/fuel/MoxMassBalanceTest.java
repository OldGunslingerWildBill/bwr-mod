package dev.bwr.core.fuel;

import dev.bwr.core.Check;

/** The MOX cost: plutonium nuggets and matrix ingots, worked by hand for the shipped grades. */
public final class MoxMassBalanceTest {

    private MoxMassBalanceTest() {
    }

    /**
     * 4 ingots of heavy metal are 36 nuggets: 4.5 % is 1.62 nuggets (2), 7 % is 2.52 (3), 9.5 % is 3.42 (4), and the
     * plutonium takes less than one ingot, so the matrix is 36 - n nuggets rounded up to ingots: 4 each.
     */
    public static void test01_theShippedGradesCostWhatIsWorkedByHand() {
        Check.exactly(2, MoxMassBalance.plutoniumNuggets(4, 0.045), "mox_lean plutonium nuggets");
        Check.exactly(3, MoxMassBalance.plutoniumNuggets(4, 0.07), "mox plutonium nuggets");
        Check.exactly(4, MoxMassBalance.plutoniumNuggets(4, 0.095), "mox_rich plutonium nuggets");
        Check.exactly(4, MoxMassBalance.matrixIngots(4, 2), "mox_lean matrix ingots");
        Check.exactly(4, MoxMassBalance.matrixIngots(4, 3), "mox matrix ingots");
        Check.exactly(4, MoxMassBalance.matrixIngots(4, 4), "mox_rich matrix ingots");
    }

    /** Rounding only loses: the nuggets and the matrix hold at least the heavy metal, and a fraction that lands on a nugget is not rounded up. */
    public static void test02_roundingNeverMintsHeavyMetalAndExactFractionsAreNotBumped() {
        for (double fraction : new double[] {0.01, 0.045, 0.06, 0.07, 0.085, 0.095, 0.2, 0.5, 0.9}) {
            int n = MoxMassBalance.plutoniumNuggets(4, fraction);
            Check.isTrue(n >= 36 * fraction - 1e-9 && n < 36 * fraction + 1.0, "nuggets for " + fraction + " are the fraction of 36 rounded up: " + n);
            int matrix = MoxMassBalance.matrixIngots(4, n);
            Check.isTrue(matrix * 9 + n >= 36, "the matrix and the plutonium hold the heavy metal for " + fraction);
            Check.isTrue(matrix * 9 + n < 36 + 9, "the matrix is not a whole ingot too many for " + fraction);
        }
        // 1/9 of an ingot's worth is exactly one nugget (the fraction 4 x 9 x f = 1 at f = 1/36 is off the 1e-5 grid; 0.25 is 9 nuggets exactly)
        Check.exactly(9, MoxMassBalance.plutoniumNuggets(4, 0.25), "a quarter plutonium is nine nuggets, exactly");
        Check.exactly(3, MoxMassBalance.matrixIngots(4, 9), "and the matrix is three ingots");
        Check.exactly(4, MoxMassBalance.matrixIngots(4, 0), "no plutonium leaves a four-ingot matrix");
    }

    /** A fraction that is not a plutonium fraction is refused rather than turned into something. */
    public static void test03_impossibleFractionsAreRefused() {
        for (double bad : new double[] {0.0, -0.1, 1.0, 1.5, Double.NaN}) {
            boolean refused = false;
            try {
                MoxMassBalance.plutoniumNuggets(4, bad);
            } catch (IllegalArgumentException e) {
                refused = true;
            }
            Check.isTrue(refused, "plutonium fraction " + bad + " is refused");
        }
    }
}
