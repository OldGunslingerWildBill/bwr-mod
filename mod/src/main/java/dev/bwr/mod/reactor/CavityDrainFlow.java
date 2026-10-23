package dev.bwr.mod.reactor;

/**
 * The flow law of a Cavity Drain Port: gravity through an orifice, Q = Cd A rho sqrt(2 g H), times the opening of the
 * motor-operated valve on the line. H is the driving head: the depth of water over the port's sill plus the line's
 * elevation difference (the sill's height less the height of the receiving tank's inlet flange). Nothing else: no pipe
 * friction, no bends. At 1 m of head about 530 kg/s, at 4 m about 1,060, and the 0.2 m2 bore passes at most
 * {@value #LINE_LIMIT_KG_PER_S} kg/s (7.5 m/s), reached at about 8 m.
 */
public final class CavityDrainFlow {
    private CavityDrainFlow() {
    }

    public static final double DISCHARGE_COEFFICIENT = 0.6;
    /** The drain bore, m2 (about 0.5 m across). */
    public static final double BORE_AREA_M2 = 0.2;
    public static final double WATER_DENSITY_KG_PER_M3 = 1000.0;
    public static final double GRAVITY = 9.81;
    public static final double LINE_LIMIT_KG_PER_S = 1500.0;

    /** The flow of the open port at {@code headM} metres of driving head, kg/s; zero for no head. */
    public static double openKgPerS(double headM) {
        if (!(headM > 0.0) || !Double.isFinite(headM)) {
            return 0.0;
        }
        double q = DISCHARGE_COEFFICIENT * BORE_AREA_M2 * WATER_DENSITY_KG_PER_M3 * Math.sqrt(2.0 * GRAVITY * headM);
        return Math.min(LINE_LIMIT_KG_PER_S, q);
    }

    /** The flow through a valve open by {@code opening} (0 to 1): proportional, as a steam valve scales its supply. */
    public static double kgPerS(double headM, double opening) {
        if (!(opening > 0.0) || !Double.isFinite(opening)) {
            return 0.0;
        }
        return openKgPerS(headM) * Math.min(1.0, opening);
    }
}
