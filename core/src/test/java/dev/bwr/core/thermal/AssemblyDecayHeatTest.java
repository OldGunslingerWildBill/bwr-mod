package dev.bwr.core.thermal;

import dev.bwr.core.Check;

/**
 * The decay heat of one discharged assembly ({@link AssemblyDecayHeat}) against reference points of the standard
 * infinite-operation curve (ANS-5.1 for U-235, the table in {@link DecayHeat}), against the reactor's own
 * {@link DecayHeat} group solution, and against the Way-Wigner approximation.
 */
public final class AssemblyDecayHeatTest {

    private AssemblyDecayHeatTest() {
    }

    /** A burnup so large that every group is saturated: the infinite-operation bounding case. */
    private static final double SATURATED_BURNUP = 1.0e9;

    /** {seconds after shutdown, ANS-5.1-style fraction of operating power} from the table in DecayHeat's javadoc. */
    private static final double[][] REFERENCE = {
            {1.0, 0.0620}, {10.0, 0.0490}, {100.0, 0.0340}, {1000.0, 0.0205}, {3600.0, 0.0135},
            {1.0e4, 0.0100}, {1.0e5, 0.00500}, {1.0e6, 0.00235}, {1.0e7, 0.00095},
    };

    public static void test01_infiniteOperationMatchesTheReferenceCurve() {
        double worst = 0.0;
        for (double[] point : REFERENCE) {
            double fraction = AssemblyDecayHeat.fractionOfOperatingPower(
                    AssemblyDecayHeat.operatingSeconds(SATURATED_BURNUP), point[0]);
            double error = fraction / point[1] - 1.0;
            worst = Math.max(worst, Math.abs(error));
            Check.relative(point[1], fraction, 0.025, "decay heat fraction " + point[0] + " s after discharge");
        }
        Check.note("worst error against the tabulated reference curve: %.2f %%", worst * 100.0);
    }

    /** The closed form is the solution of the reactor's own groups: same history, same age, same number. */
    public static void test02_agreesWithTheReactorsGroupSolution() {
        for (double operatingSeconds : new double[] {600.0, 3.6e4, 8.64e5, 3.156e7, 1.5e8}) {
            DecayHeat core = DecayHeat.afterOperatingFor(1.0, operatingSeconds);
            for (double age : new double[] {0.0, 1.0, 60.0, 3600.0, 8.64e4, 2.6e6, 3.1e7}) {
                Check.relative(core.projectedFractionOfRated(age),
                        AssemblyDecayHeat.fractionOfOperatingPower(operatingSeconds, age), 1.0e-9,
                        "operated " + operatingSeconds + " s, cooled " + age + " s");
            }
        }
    }

    /**
     * Way-Wigner: P/P0 = 0.0622 (t^-0.2 - (t+T)^-0.2). A coarse cross-check, not the model: it is known to run 30 to 40 %
     * low in the first ten minutes (recorded in the transcript, not asserted) and to agree to about 30 % from an hour to
     * a month, which is the range a pool works in and the range asserted. The fit is the model.
     */
    public static void test03_wayWignerCrossCheck() {
        double year = 3.156e7;
        double worst = 0.0;
        for (double age : new double[] {3600.0, 3.6e4, 3.6e5, 3.6e6}) {
            double wayWigner = 0.0622 * (Math.pow(age, -0.2) - Math.pow(age + year, -0.2));
            double fit = AssemblyDecayHeat.fractionOfOperatingPower(year, age);
            worst = Math.max(worst, Math.abs(fit / wayWigner - 1.0));
            Check.relative(wayWigner, fit, 0.30, "fit against Way-Wigner, one year at power, " + age + " s");
        }
        double earlyWayWigner = 0.0622 * (Math.pow(60.0, -0.2) - Math.pow(60.0 + year, -0.2));
        Check.note("fit / Way-Wigner, one year at power: %.0f %% worst from 1 h to six weeks; at one minute the fit is %.0f %% above it",
                worst * 100.0, (AssemblyDecayHeat.fractionOfOperatingPower(year, 60.0) / earlyWayWigner - 1.0) * 100.0);
    }

    public static void test04_shapeOfTheCurve() {
        double burnup = 30000.0;
        double previous = Double.MAX_VALUE;
        for (double age : new double[] {0.0, 1.0, 60.0, 3600.0, 8.64e4, 8.64e5, 8.64e6, 1.0e8}) {
            double w = AssemblyDecayHeat.powerWatts(burnup, 180.0, age);
            Check.isTrue(w < previous && w > 0.0, "decay power must fall with time: %s W at %s s after %s", w, age, previous);
            previous = w;
        }
        Check.exactly(0.0, AssemblyDecayHeat.powerWatts(0.0, 180.0, 10.0), "an unburned assembly has no decay heat");
        Check.exactly(0.0, AssemblyDecayHeat.powerWatts(30000.0, 0.0, 10.0), "no heavy metal, no heat");
        Check.exactly(AssemblyDecayHeat.powerWatts(30000.0, 180.0, 0.0), AssemblyDecayHeat.powerWatts(30000.0, 180.0, -50.0),
                "a negative age is treated as zero");
        // more burnup, more long-lived heat; a short run fills only the fast groups
        Check.isTrue(AssemblyDecayHeat.powerWatts(100.0, 180.0, 8.64e4) < AssemblyDecayHeat.powerWatts(20000.0, 180.0, 8.64e4),
                "a short run must leave less heat after a day than a long one");
        // linear in mass
        Check.relative(2.0 * AssemblyDecayHeat.powerWatts(30000.0, 180.0, 3600.0),
                AssemblyDecayHeat.powerWatts(30000.0, 360.0, 3600.0), 1.0e-12, "decay power is proportional to heavy metal");
    }

    /** Numbers a player will meet: a full-burnup LEU assembly. Recorded in the run transcript. */
    public static void test05_scaleOfARealAssembly() {
        double burnup = 45000.0;
        double p0 = AssemblyDecayHeat.powerWatts(burnup, 180.0, 0.0);
        double hour = AssemblyDecayHeat.powerWatts(burnup, 180.0, 3600.0);
        double day = AssemblyDecayHeat.powerWatts(burnup, 180.0, 86400.0);
        double year = AssemblyDecayHeat.powerWatts(burnup, 180.0, 3.156e7);
        Check.note("45 GWd/t assembly: %.0f kW at discharge, %.1f kW after 1 h, %.1f kW after 1 d, %.2f kW after 1 y",
                p0 / 1e3, hour / 1e3, day / 1e3, year / 1e3);
        // A real offloaded assembly gives about 0.3 MW at shutdown and roughly 20 kW after a day.
        Check.inRange(2.5e5, 3.5e5, p0, "decay power of a full-burnup assembly at discharge, W");
        Check.inRange(1.5e4, 3.0e4, day, "decay power of a full-burnup assembly after a day, W");
        Check.inRange(5.0e2, 8.0e3, year, "decay power of a full-burnup assembly after a year, W");
        Check.isTrue(AssemblyDecayHeat.REFERENCE_OPERATING_POWER_W > 4.0e6 && AssemblyDecayHeat.REFERENCE_OPERATING_POWER_W < 5.5e6,
                "the reference assembly power must be a few MW: %s", AssemblyDecayHeat.REFERENCE_OPERATING_POWER_W);
    }

    public static void test06_timeToCoolBelowALimit() {
        double burnup = 45000.0;
        for (double limit : new double[] {1.0e5, 5.0e4, 2.0e4, 5.0e3}) {
            double t = AssemblyDecayHeat.secondsToCoolBelow(burnup, 180.0, limit);
            Check.finite(t, "cool-down time to " + limit + " W");
            Check.relative(limit, AssemblyDecayHeat.powerWatts(burnup, 180.0, t), 2.0e-3, "power at the returned time, limit " + limit);
            Check.isTrue(AssemblyDecayHeat.powerWatts(burnup, 180.0, t * 0.9) > limit, "must still be above the limit before the time");
        }
        Check.exactly(0.0, AssemblyDecayHeat.secondsToCoolBelow(10.0, 180.0, 1.0e6), "already below the limit");
        Check.isTrue(Double.isInfinite(AssemblyDecayHeat.secondsToCoolBelow(burnup, 180.0, 1.0e-3)),
                "a limit below what the slowest group leaves is never reached");
        Check.note("45 GWd/t assembly cools below 50 kW after %.0f min", AssemblyDecayHeat.secondsToCoolBelow(burnup, 180.0, 5.0e4) / 60.0);
    }

    /**
     * The pool's per-assembly curve (worked out once, evaluated every second) gives the very same number as the plain formula,
     * to the last bit, for every burnup, mass and age a pool meets, including the degenerate ones.
     */
    public static void test07_aPrecomputedCurveIsBitForBitThePlainFormula() {
        double[] burnups = {0.0, -5.0, 1.0, 10.0, 950.0, 12_345.6, 45_000.0, 60_000.0, 1.0e9, Double.NaN};
        double[] masses = {0.0, -1.0, 1.0, 150.0, 180.0, 212.7, Double.POSITIVE_INFINITY};
        double[] ages = {-50.0, 0.0, 0.05, 1.0, 59.95, 3600.0, 86_400.0, 2.6e6, 3.156e7, AssemblyDecayHeat.LONG_COOLED_SECONDS, 1.0e10, Double.NaN};
        int compared = 0;
        for (double burnup : burnups) {
            for (double mass : masses) {
                AssemblyDecayHeat.Curve curve = new AssemblyDecayHeat.Curve(burnup, mass);
                for (double age : ages) {
                    double plain = AssemblyDecayHeat.powerWatts(burnup, mass, age);
                    double fast = curve.powerWatts(age);
                    Check.isTrue(Double.doubleToLongBits(plain) == Double.doubleToLongBits(fast),
                            "burnup %s, %s kg, %s s: the curve gives %s W, the formula %s W", burnup, mass, age, fast, plain);
                    compared++;
                }
            }
        }
        Check.note("%d burnup, mass and age combinations agree to the bit", compared);
    }
}
