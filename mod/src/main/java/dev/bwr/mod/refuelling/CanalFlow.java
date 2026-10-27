package dev.bwr.mod.refuelling;

/**
 * The flow law of the cattle chute when it is a slot joining a spent fuel pool and a reactor well: gravity through the slot, the
 * drain port's law ({@code dev.bwr.mod.reactor.CavityDrainFlow}) with the slot's cross-section, Q = Cd A rho sqrt(2 g H). A is the
 * slot's cross-section: the canal's width ({@value #WIDTH_M} m between its walls) times its height from the sill to the top of the
 * lowest-topped canal column, as the drain's bore is a fixed area. H is the head: the high surface less the low one, or less the sill
 * when the low side is below it (the water falls over the sill). Only the water over the sill moves. Capped at
 * {@value #LIMIT_KG_PER_S} kg/s (about 3.8 m/s through a slot 7 m deep). 0.5 m of head through a 2 m slot passes about 2,800 kg/s;
 * 1 m through 7 m about 14,000.
 */
public final class CanalFlow {
    private CanalFlow() {
    }

    public static final double DISCHARGE_COEFFICIENT = 0.6;
    /** Width of the water between the canal's walls, m (the trough is 12/16 of a block inside). */
    public static final double WIDTH_M = 0.75;
    public static final double WATER_DENSITY_KG_PER_M3 = 1000.0;
    public static final double GRAVITY = 9.81;
    public static final double LIMIT_KG_PER_S = 20_000.0;

    /** The flow through the open slot, kg/s, for {@code headM} of head through a slot {@code depthM} high from its sill; zero for none. */
    public static double kgPerS(double headM, double depthM) {
        if (!(headM > 0.0) || !(depthM > 0.0) || !Double.isFinite(headM) || !Double.isFinite(depthM)) {
            return 0.0;
        }
        double q = DISCHARGE_COEFFICIENT * WIDTH_M * depthM * WATER_DENSITY_KG_PER_M3 * Math.sqrt(2.0 * GRAVITY * headM);
        return Math.min(LIMIT_KG_PER_S, q);
    }
}
