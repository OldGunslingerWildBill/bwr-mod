package dev.bwr.core.refuelling;

import dev.bwr.core.refuelling.BridgeMotion.Point;

/**
 * The arithmetic of the reactor building crane (an overhead bridge crane on high runway rails) and of the stud tensioner
 * that frees the vessel head for it. Pure Java, no Minecraft; the block entities hold the state and call these.
 *
 * <h2>The crane</h2>
 * A move is the same shape as the refuelling bridge's, with the numbers of a big crane: it rides the runway, the trolley
 * crosses the bridge girder, the hoist pays out the hook; the load is slung, lifted clear, carried at the hook's rest height
 * (the hook block up under the girder), and set down. Axes move together, so a leg takes as long as its slowest one.
 * A crane is slower than a refuelling platform and a loaded hoist slower again; the game compresses real speeds about
 * threefold and keeps the ratios.
 *
 * <h2>The studs</h2>
 * The closure flange of the reference vessel has 74 studs (about 11 in apart). A hydraulic stud tensioner stretches one
 * stud, backs its nut off and moves to the next. The game does one stud in {@link #DETENSION_TICKS_PER_STUD} ticks going
 * loose and in {@link #RETENSION_TICKS_PER_STUD} coming back (tightening is staged to load the gasket evenly, so it is
 * slower), and takes {@link #DETENSION_FE_PER_STUD} / {@link #RETENSION_FE_PER_STUD} FE for each.
 */
public final class CraneMotion {
    private CraneMotion() {
    }

    /** Crane speed along the runway, m/s. */
    public static final double BRIDGE_SPEED_M_PER_S = 0.5;
    /** Trolley speed across the girder, m/s. */
    public static final double TROLLEY_SPEED_M_PER_S = 0.4;
    /** Hook speed with nothing on it, m/s. */
    public static final double HOIST_EMPTY_M_PER_S = 0.5;
    /** Hook speed with a slung load, m/s. */
    public static final double HOIST_LOADED_M_PER_S = 0.25;
    /** Time to hook on and take the weight, ticks (5 s). */
    public static final int LATCH_TICKS = 100;
    /** Time to ease the load onto its seat and unhook, ticks (5 s). */
    public static final int RELEASE_TICKS = 100;
    /** The hook, drawn up under the girder, hangs this far above the floor of the runway rail block, m (zero: level with it). */
    public static final double HOOK_HOME_ABOVE_RAIL_M = 0.0;
    /** The most blocks apart the two runway rails may be. */
    public static final int MAX_SPAN = 40;

    /** Ticks a leg between two points takes; never less than one. {@code runwayAlongX} says which horizontal axis the crane runs on. */
    public static int legTicks(Point a, Point b, boolean runwayAlongX, boolean loaded) {
        double along = Math.abs(runwayAlongX ? b.x() - a.x() : b.z() - a.z());
        double across = Math.abs(runwayAlongX ? b.z() - a.z() : b.x() - a.x());
        double vertical = Math.abs(b.y() - a.y());
        double seconds = Math.max(along / BRIDGE_SPEED_M_PER_S, Math.max(across / TROLLEY_SPEED_M_PER_S,
                vertical / (loaded ? HOIST_LOADED_M_PER_S : HOIST_EMPTY_M_PER_S)));
        return (int) Math.max(1, Math.ceil(seconds * 20.0 - 1e-9));
    }

    /** Studs in the closure flange: the nominal count of the reference vessel. */
    public static final int REFERENCE_STUDS = 74;
    /** Ticks to back one stud off (4 s). */
    public static final int DETENSION_TICKS_PER_STUD = 80;
    /** Ticks to bring one stud back to its load (6 s). */
    public static final int RETENSION_TICKS_PER_STUD = 120;
    /** FE one stud takes going loose. */
    public static final int DETENSION_FE_PER_STUD = 3_000;
    /** FE one stud takes coming back to load. */
    public static final int RETENSION_FE_PER_STUD = 6_000;

    public static int ticksPerStud(boolean loosening) {
        return loosening ? DETENSION_TICKS_PER_STUD : RETENSION_TICKS_PER_STUD;
    }

    public static int fePerStud(boolean loosening) {
        return loosening ? DETENSION_FE_PER_STUD : RETENSION_FE_PER_STUD;
    }

    /** Ticks a whole pass over {@code studs} studs takes. */
    public static int passTicks(int studs, boolean loosening) {
        return Math.max(0, studs) * ticksPerStud(loosening);
    }

    /** FE a whole pass over {@code studs} studs takes. */
    public static long passFe(int studs, boolean loosening) {
        return (long) Math.max(0, studs) * fePerStud(loosening);
    }

    /** The head may be lifted only when every stud is free. */
    public static boolean headLiftable(int studsOff, int studTotal) {
        return studTotal > 0 && studsOff >= studTotal;
    }

    /** The vessel can hold pressure only when every stud is at load. */
    public static boolean headSealed(int studsOff) {
        return studsOff <= 0;
    }
}
