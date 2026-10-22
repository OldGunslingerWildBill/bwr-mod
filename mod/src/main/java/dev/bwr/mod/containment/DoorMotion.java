package dev.bwr.mod.containment;

/**
 * How a containment door moves, in one place so the server's collision, the block entities' timers and the renderers agree.
 *
 * <p>A door's travel is a single number {@code x}: 0 is closed and latched, 1 is fully open. Every stroke spends its first
 * {@code latch} share unlatching (hand-wheel turns, the vault's dogs slide out of their keepers) and the rest swinging the
 * leaf. The leaf is "clear" (walk-through) once it is halfway open; the server flips the collision of the door's cells at that
 * instant in both directions, so a door is never passable while it is drawn shut and never a wall while it is drawn wide.
 */
public final class DoorMotion {
    private DoorMotion() {}

    /** Share of a vault stroke spent unlatching. */
    public static final double VAULT_LATCH = 0.28;
    /** Share of an airlock leaf's travel spent turning its wheel before the leaf moves. */
    public static final double AIRLOCK_LATCH = 0.25;
    /** Open fraction of the swing at which the leaf stops blocking the opening. */
    public static final double CLEAR_SWING = 0.5;

    /** Vault: ticks for a powered stroke and for a hand-wheel stroke (see CONTAINMENT-UPDATE.md). */
    public static final int VAULT_POWERED_TICKS = 200;
    public static final int VAULT_MANUAL_TICKS = 1200;
    /** Airlock: ticks for one leaf to travel from closed to open (or back). */
    public static final int AIRLOCK_LEAF_TICKS = 12;

    /** Hinge pin of a vault leaf in block-local coordinates (x along the wall, z through it), see art/models/containment. */
    public static final double VAULT_PIVOT_X = 0.31, VAULT_PIVOT_Z = 0.38;
    /** How far a latch dog slides back into the leaf. */
    public static final double VAULT_DOG_TRAVEL = 0.26;
    /** Plane of the vault hand-wheel and its rim radius per door size. */
    public static final double VAULT_WHEEL_Z = 0.08;
    public static double vaultWheelRadius(int size) { return size == 3 ? 0.40 : 0.50; }
    /** Latch dog positions and the status lamp column, as authored in art/models/containment/build_models.py. */
    public static double[] vaultDogs(int size) {
        int n = size == 3 ? 2 : 3;
        double[] v = new double[n];
        for (int i = 0; i < n; i++) v[i] = 0.30 + (size - 0.60) * (i + 0.5) / n;
        return v;
    }
    public static double vaultLampY(int size) { double[] d = vaultDogs(size); return (d[0] + d[1]) / 2; }

    /** Airlock hinge pin x, the wheel position on a leaf, the lamp column and the equalising valve (block-local, art/models). */
    public static final double AIRLOCK_PIVOT_X = 0.07;
    public static final double AIRLOCK_WHEEL_X = 0.5, AIRLOCK_WHEEL_Y = 0.95;
    public static final double AIRLOCK_LAMP_X = 0.95, AIRLOCK_LAMP_Y = 1.15;
    public static final double[] AIRLOCK_VALVE = {0.70, 1.93, 1.5};
    /** Chamber face of each airlock plate (inner, outer) in block-local z, and which way the chamber lies. */
    public static final double[] AIRLOCK_FACE_Z = {0.58, 2.42};

    public static double clamp01(double v) { return v < 0 ? 0 : Math.min(v, 1); }

    private static double smooth(double t) { t = clamp01(t); return t * t * (3 - 2 * t); }

    /** Share of the unlatch travel done at stroke position x: 0 latched, 1 fully unlatched. */
    public static double unlatched(double x, double latch) { return smooth(x / latch); }

    /** Open fraction of the leaf swing at stroke position x (0 shut, 1 fully open). */
    public static double swing(double x, double latch) { return smooth((clamp01(x) - latch) / (1 - latch)); }

    /** Stroke position at which the leaf is clear of the opening. */
    public static double clearX(double latch) { return latch + (1 - latch) * CLEAR_SWING; }

    /** Hand-wheel turns while unlatching (about one and a half), a little more again while it settles. */
    public static double wheelTurns(double x, double latch) { return 1.5 * unlatched(x, latch); }

    /** Ticks from the start of a stroke of {@code total} ticks until the leaf is clear (opening) or shuts the opening (closing). */
    public static long gateTicks(long total, double latch, boolean closing) {
        double x = clearX(latch);
        return closing ? (long) Math.ceil(total * (1 - x)) : (long) Math.ceil(total * x);
    }

    /**
     * Client-side timing of one stroke. A stroke that starts while the client watches is timed from the moment its packet
     * arrives, which is also when the block updates that change the cells' collision arrive, so the drawn leaf and the
     * collision cannot drift apart by the server-to-client lag (or by a client clock that is a few ticks off the server's).
     * A client that joins mid-stroke has only the server's clock to go by.
     */
    public static final class StrokeClock {
        private long seenStart = Long.MIN_VALUE;
        private Object seenKey;
        private double origin;

        /** Progress 0..1 of the stroke that began at server tick {@code strokeStart} and lasts {@code duration}, at client time {@code now}. */
        public double fraction(long strokeStart, double duration, Object key, double now) {
            if (seenStart != strokeStart || !java.util.Objects.equals(seenKey, key)) {
                origin = seenStart == Long.MIN_VALUE ? strokeStart : now;
                seenStart = strokeStart;
                seenKey = key;
            }
            return clamp01((now - origin) / duration);
        }
    }
}
