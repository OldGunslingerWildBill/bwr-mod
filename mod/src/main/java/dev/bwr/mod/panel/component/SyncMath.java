package dev.bwr.mod.panel.component;

/**
 * The arithmetic of the synchroscope, pure so that both the model and the tests use the same: a phase angle is in degrees
 * and 0 is "in phase" (the pointer at 12 o'clock); positive is the generator leading the grid, which turns the pointer
 * clockwise (towards FAST). Slip is in Hz, positive when the generator is faster, which turns the pointer clockwise too.
 */
public final class SyncMath {
    private SyncMath() {
    }

    /** {@code degrees} brought into -180 (exclusive) to +180 (inclusive). */
    public static double wrap(double degrees) {
        double d = degrees % 360.0;
        if (d > 180.0) {
            d -= 360.0;
        } else if (d <= -180.0) {
            d += 360.0;
        }
        return d;
    }

    /** The pointer angle after {@code dtSeconds} at {@code slipHz}: a full turn a second per hertz. */
    public static double advance(double angle, double slipHz, double dtSeconds) {
        return wrap(angle + 360.0 * slipHz * dtSeconds);
    }

    /** The pointer angle moved towards {@code target} along the short way round, by the share a lag of {@code tau} seconds closes in {@code dt}. */
    public static double follow(double angle, double target, double dtSeconds, double tau) {
        if (tau <= 1.0e-6) {
            return wrap(target);
        }
        double share = 1.0 - Math.exp(-Math.max(0.0, dtSeconds) / tau);
        return wrap(angle + wrap(target - angle) * share);
    }

    /**
     * How bright a synchronising lamp wired across the open breaker's poles is at {@code phaseDegrees}, 0 to 1: the
     * voltage across it, sin squared of half the angle. Dark at 0 (the dark lamp method), full at 180.
     */
    public static double darkLamp(double phaseDegrees) {
        double s = Math.sin(Math.toRadians(phaseDegrees) / 2.0);
        return s * s;
    }

    /** The complementary lamp (the bright lamp method): full at 0, dark at 180. */
    public static double brightLamp(double phaseDegrees) {
        return 1.0 - darkLamp(phaseDegrees);
    }
}
