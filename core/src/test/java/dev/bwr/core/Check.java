package dev.bwr.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Assertions and diagnostic notes for the acceptance suite.
 *
 * <h2>Why this is not JUnit</h2>
 * {@code core/build.gradle} declares JUnit 5 and the tests here would run under
 * it unchanged in shape — but a plain {@code javac}/{@code java} invocation
 * against this module has no way to resolve the JUnit jars, and the acceptance
 * criteria for this model are physics results that must actually be executed,
 * not annotation style. So the suite is plain Java: every test is a
 * {@code public static void test*()} method, {@link AcceptanceTests} finds them
 * by reflection, and the process exits non-zero if any of them fail. It runs
 * anywhere a JDK does.
 *
 * <h2>Notes are part of the output</h2>
 * A physics test that passes silently tells you nothing about whether it passed
 * for the right reason. {@link #note} records the actual number a test measured
 * — prompt jump ratio, asymptotic period, count rate, sub-step count — so the
 * run transcript is a readable record of what the model did, not just a row of
 * green ticks.
 */
public final class Check {

    private Check() {
    }

    /** Failure of one assertion. */
    public static final class Failure extends AssertionError {
        private static final long serialVersionUID = 1L;

        public Failure(String message) {
            super(message);
        }
    }

    private static final List<String> NOTES = new ArrayList<>();

    /** Record a measured value for the run transcript. */
    public static void note(String format, Object... args) {
        NOTES.add(String.format(Locale.ROOT, format, args));
    }

    /** Notes recorded since {@link #clearNotes()}, in order. */
    public static List<String> notes() {
        return new ArrayList<>(NOTES);
    }

    /** Drop recorded notes. The runner calls this between tests. */
    public static void clearNotes() {
        NOTES.clear();
    }

    // ---------------------------------------------------------------
    // Assertions
    // ---------------------------------------------------------------

    public static void fail(String format, Object... args) {
        throw new Failure(String.format(Locale.ROOT, format, args));
    }

    public static void isTrue(boolean condition, String format, Object... args) {
        if (!condition) {
            fail("%s", String.format(Locale.ROOT, format, args));
        }
    }

    public static void isFalse(boolean condition, String format, Object... args) {
        isTrue(!condition, format, args);
    }

    /** Bit-for-bit equality, the criterion for a persistence round trip. */
    public static void exactly(double expected, double actual, String what) {
        if (Double.compare(expected, actual) != 0) {
            fail("%s: expected exactly %.17g, got %.17g (differs by %.3g)",
                    what, expected, actual, actual - expected);
        }
    }

    public static void exactly(int expected, int actual, String what) {
        if (expected != actual) {
            fail("%s: expected %d, got %d", what, expected, actual);
        }
    }

    public static void exactly(long expected, long actual, String what) {
        if (expected != actual) {
            fail("%s: expected %d, got %d", what, expected, actual);
        }
    }

    /**
     * Equality of a latched flag. Exists so that a reflective comparator can
     * treat a {@code boolean} record component exactly like every other one —
     * {@code scramActive} is plant state and a round trip that drops it
     * un-scrams a saved reactor.
     */
    public static void exactly(boolean expected, boolean actual, String what) {
        if (expected != actual) {
            fail("%s: expected %s, got %s", what, expected, actual);
        }
    }

    /** Relative agreement: |actual-expected| <= relativeTolerance * |expected|. */
    public static void relative(double expected, double actual, double relativeTolerance, String what) {
        finite(actual, what);
        double allowed = Math.abs(expected) * relativeTolerance;
        double error = Math.abs(actual - expected);
        if (!(error <= allowed)) {
            fail("%s: expected %.6g +/- %.3g%% (%.3g), got %.6g — off by %.3g (%.3f%%)",
                    what, expected, 100.0 * relativeTolerance, allowed, actual, actual - expected,
                    expected == 0.0 ? Double.NaN : 100.0 * (actual - expected) / expected);
        }
    }

    /** Absolute agreement: |actual-expected| <= absoluteTolerance. */
    public static void absolute(double expected, double actual, double absoluteTolerance, String what) {
        finite(actual, what);
        double error = Math.abs(actual - expected);
        if (!(error <= absoluteTolerance)) {
            fail("%s: expected %.6g +/- %.3g, got %.6g — off by %.3g",
                    what, expected, absoluteTolerance, actual, actual - expected);
        }
    }

    public static void inRange(double low, double high, double actual, String what) {
        finite(actual, what);
        if (!(actual >= low && actual <= high)) {
            fail("%s: expected within [%.6g, %.6g], got %.6g", what, low, high, actual);
        }
    }

    public static void greaterThan(double bound, double actual, String what) {
        finite(actual, what);
        if (!(actual > bound)) {
            fail("%s: expected greater than %.6g, got %.6g", what, bound, actual);
        }
    }

    public static void lessThan(double bound, double actual, String what) {
        finite(actual, what);
        if (!(actual < bound)) {
            fail("%s: expected less than %.6g, got %.6g", what, bound, actual);
        }
    }

    public static void finite(double actual, String what) {
        if (Double.isNaN(actual)) {
            fail("%s: is NaN", what);
        }
        if (Double.isInfinite(actual)) {
            fail("%s: is %s", what, actual > 0 ? "+Infinity" : "-Infinity");
        }
    }

    public static void finiteAndPositive(double actual, String what) {
        finite(actual, what);
        if (!(actual > 0.0)) {
            fail("%s: expected strictly positive, got %.6g", what, actual);
        }
    }

    public static void finiteAndNonNegative(double actual, String what) {
        finite(actual, what);
        if (!(actual >= 0.0)) {
            fail("%s: expected non-negative, got %.6g", what, actual);
        }
    }

    public static void allFiniteAndNonNegative(double[] values, String what) {
        for (int i = 0; i < values.length; i++) {
            finiteAndNonNegative(values[i], what + "[" + i + "]");
        }
    }

    public static void arraysExactly(double[] expected, double[] actual, String what) {
        if (expected.length != actual.length) {
            fail("%s: length %d vs %d", what, expected.length, actual.length);
        }
        for (int i = 0; i < expected.length; i++) {
            exactly(expected[i], actual[i], what + "[" + i + "]");
        }
    }

    public static void arraysExactly(int[] expected, int[] actual, String what) {
        if (expected.length != actual.length) {
            fail("%s: length %d vs %d", what, expected.length, actual.length);
        }
        for (int i = 0; i < expected.length; i++) {
            exactly(expected[i], actual[i], what + "[" + i + "]");
        }
    }
}
