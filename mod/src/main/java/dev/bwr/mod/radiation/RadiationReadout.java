package dev.bwr.mod.radiation;

/**
 * The latest survey meter readout the client has received. Plain statics with no Minecraft types, so the packet handler
 * in common code can fill it and the client-only HUD and click code can read it. A reading older than
 * {@link #STALE_MILLIS} counts as no reading (the meter was put away, or the server stopped sending).
 */
public final class RadiationReadout {
    private RadiationReadout() {
    }

    /** A reading is current for this long, milliseconds. The server sends four a second. */
    public static final long STALE_MILLIS = 1500L;

    private static volatile double rate;
    private static volatile double dose;
    private static volatile long receivedAt = Long.MIN_VALUE;

    static void accept(double doseRateMsvPerHour, double personalDoseMsv) {
        rate = doseRateMsvPerHour;
        dose = personalDoseMsv;
        receivedAt = System.nanoTime() / 1_000_000L;
    }

    public static boolean fresh() {
        return receivedAt != Long.MIN_VALUE && System.nanoTime() / 1_000_000L - receivedAt <= STALE_MILLIS;
    }

    /** The dose rate at the holder, mSv/h. */
    public static double doseRate() {
        return rate;
    }

    /** The holder's accumulated dose, mSv. */
    public static double personalDose() {
        return dose;
    }

    public static void clear() {
        receivedAt = Long.MIN_VALUE;
    }
}
