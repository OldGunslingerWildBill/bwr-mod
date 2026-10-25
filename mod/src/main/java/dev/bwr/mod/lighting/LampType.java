package dev.bwr.mod.lighting;

/**
 * The three high-intensity-discharge lamp types (LIGHTING.md). Times are in game ticks, scaled from real life: the
 * real warm-up of 7 to 10 minutes (LPS), 3 to 5 minutes (HPS) and 4 to 7 minutes (mercury) becomes 40, 20 and 27 seconds.
 * A hot lamp that is switched off has to cool before it restrikes; {@code restrikeUnits} is how many 100-tick steps that
 * takes (15 seconds for the two that need it). The low-pressure lamp restarts at once.
 */
public enum LampType {
    LOW_PRESSURE_SODIUM("lps", 800, 0),
    HIGH_PRESSURE_SODIUM("hps", 400, 3),
    MERCURY("mercury", 540, 3);

    /** Warm-up stages after the strike: the light level is 4, 8, 12, then 15. */
    public static final int WARM_STEPS = 3;
    /** Light level of each warm-up stage (index = stage). */
    private static final int[] LIGHT = {4, 8, 12, 15};
    /** Ticks per restrike unit (the cooling counter counts these). */
    public static final int RESTRIKE_UNIT_TICKS = 100;

    private final String id;
    private final int warmupTicks;
    private final int restrikeUnits;

    LampType(String id, int warmupTicks, int restrikeUnits) {
        this.id = id;
        this.warmupTicks = warmupTicks;
        this.restrikeUnits = restrikeUnits;
    }

    public String id() {
        return id;
    }

    /** Ticks between two warm-up stages. */
    public int stepTicks() {
        return warmupTicks / WARM_STEPS;
    }

    /** Units the lamp must cool before it can strike again after it was switched off (0 = no wait). */
    public int restrikeUnits() {
        return restrikeUnits;
    }

    /** Light level at a warm-up stage, for a lit lamp. */
    public static int lightAt(int stage) {
        return LIGHT[Math.max(0, Math.min(WARM_STEPS, stage))];
    }
}
