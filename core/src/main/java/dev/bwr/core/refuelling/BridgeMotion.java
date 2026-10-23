package dev.bwr.core.refuelling;

import dev.bwr.core.radiation.DoseModel;

/**
 * The arithmetic of the refuelling bridge: how fast it moves, what a move is made of, how deep under water the carried
 * assembly is and what that does to the dose. Pure Java, no Minecraft; the block entity ({@code RefuellingBridgeBlockEntity})
 * only holds the state and calls these.
 *
 * <h2>A move</h2>
 * One assembly at a time, in these phases, each taking real time:
 * <ol>
 *   <li>{@link Phase#TRAVEL}: the bridge runs along its rails and the trolley across them, mast fully retracted;</li>
 *   <li>{@link Phase#LOWER_EMPTY}: the mast telescopes down until the grapple sits on the assembly's handle;</li>
 *   <li>{@link Phase#LATCH}: the grapple closes on the handle (the hoist-limit interlock is checked just before it);</li>
 *   <li>{@link Phase#RAISE_LOADED}: the assembly is lifted {@link #LIFT_M} clear of its seat;</li>
 *   <li>{@link Phase#TRANSIT}: the loaded trolley travels, through the cattle chute when it goes between the reactor cavity and
 *       the pool, one leg per waypoint, moving all three axes together;</li>
 *   <li>{@link Phase#LOWER_LOADED}: the assembly is set down on its new seat;</li>
 *   <li>{@link Phase#RELEASE}: the grapple opens;</li>
 *   <li>{@link Phase#RAISE_EMPTY}: the mast retracts.</li>
 * </ol>
 *
 * <h2>Speeds</h2>
 * Real bridges travel at fractions of a metre a second and hoist at about 10 cm/s loaded; the game compresses that so a
 * transfer takes minutes, not hours, and keeps the ratios (loaded hoist half the empty hoist, trolley slower than bridge).
 * Axes move together, so a leg takes as long as its slowest axis.
 *
 * <h2>Water</h2>
 * The top of the carried assembly (the handle, where the grapple holds it) is the point that matters: the water between it
 * and the surface is the shielding, {@code e^(-7.07 x depth)} as the reactor model uses for an open head
 * ({@link DoseModel#waterTransmission}). This class only measures (the water over the handle, the least of it along a route);
 * what the machine does with the number, its hoist limit, is the bridge's own and lives in the block entity, not in the
 * physics.
 */
public final class BridgeMotion {
    private BridgeMotion() {
    }

    /** Bridge speed along the rails, m/s. */
    public static final double BRIDGE_SPEED_M_PER_S = 1.0;
    /** Trolley speed across the bridge, m/s. */
    public static final double TROLLEY_SPEED_M_PER_S = 0.6;
    /** Mast speed with an empty grapple, m/s. */
    public static final double HOIST_EMPTY_M_PER_S = 0.6;
    /** Mast speed with an assembly on the grapple, m/s. */
    public static final double HOIST_LOADED_M_PER_S = 0.3;
    /** Time the grapple takes to close on a handle, ticks (4 s). */
    public static final int LATCH_TICKS = 80;
    /** Time the grapple takes to open, ticks (4 s). */
    public static final int RELEASE_TICKS = 80;

    /** A fuel assembly is about this long, m (the pool's racks are built for it). */
    public static final double ASSEMBLY_LENGTH_M = 4.5;
    /** How far an assembly is lifted off its seat to be carried, m: game scale (the water is not as deep as a real cavity). */
    public static final double LIFT_M = 1.0;
    /** The real lift, the whole length of the assembly, m. Only with the interlock overridden: it leaves the handle in the air. */
    public static final double FULL_LIFT_M = ASSEMBLY_LENGTH_M;
    /** Water kept over the assembly while it crosses the cattle chute, m. */
    public static final double CHUTE_COVER_M = 2.0;
    /** With the mast retracted the grapple hangs this far above the rail's top, m. */
    public static final double MAST_HOME_ABOVE_RAIL_M = 1.0;

    public enum Phase {
        IDLE, TRAVEL, LOWER_EMPTY, LATCH, RAISE_LOADED, TRANSIT, LOWER_LOADED, RELEASE, RAISE_EMPTY, HALTED;

        public static Phase byName(String name) {
            for (Phase p : values()) {
                if (p.name().equals(name)) {
                    return p;
                }
            }
            return IDLE;
        }
    }

    /** A point of the grapple in world coordinates: the column, and the height of the grapple tip (= the top of a carried assembly). */
    public record Point(double x, double y, double z) {
        public Point withY(double newY) {
            return new Point(x, newY, z);
        }
    }

    /**
     * Ticks a leg between two points takes. {@code runwayAlongX} says which horizontal axis the bridge itself runs on; the
     * other is the trolley's. Never less than one tick.
     */
    public static int legTicks(Point a, Point b, boolean runwayAlongX, boolean loaded) {
        double along = Math.abs(runwayAlongX ? b.x() - a.x() : b.z() - a.z());
        double across = Math.abs(runwayAlongX ? b.z() - a.z() : b.x() - a.x());
        double vertical = Math.abs(b.y() - a.y());
        double seconds = Math.max(along / BRIDGE_SPEED_M_PER_S, Math.max(across / TROLLEY_SPEED_M_PER_S,
                vertical / (loaded ? HOIST_LOADED_M_PER_S : HOIST_EMPTY_M_PER_S)));
        return (int) Math.max(1, Math.ceil(seconds * 20.0 - 1e-9));
    }

    /** The point a leg has reached after {@code elapsed} of {@code total} ticks. */
    public static Point lerp(Point a, Point b, double elapsed, int total) {
        double f = total <= 0 ? 1.0 : Math.max(0.0, Math.min(1.0, elapsed / total));
        return new Point(a.x() + (b.x() - a.x()) * f, a.y() + (b.y() - a.y()) * f, a.z() + (b.z() - a.z()) * f);
    }

    /** The lift of an assembly off its seat while it is carried, m. */
    public static double lift(boolean interlockOverridden) {
        return interlockOverridden ? FULL_LIFT_M : LIFT_M;
    }

    /** Metres of water over a point at {@code y} under a surface at {@code surfaceY}; zero in air, and zero when there is no water ({@code NaN}). */
    public static double cover(double surfaceY, double y) {
        double c = surfaceY - y;
        return c > 0.0 && Double.isFinite(c) ? c : 0.0;
    }

    /** What gets through the water over the handle: the reactor model's open-head law; nothing is shielded in air. */
    public static double transmission(double coverM) {
        return coverM > 0.0 ? DoseModel.waterTransmission(coverM) : 1.0;
    }

    /** Gamma power of an assembly, watts, from its decay heat: the model's gamma share of decay heat. */
    public static double gammaWatts(double decayWatts) {
        return decayWatts > 0.0 && Double.isFinite(decayWatts) ? DoseModel.GAMMA_FRACTION_OF_DECAY_HEAT * decayWatts : 0.0;
    }

    /** A place the bridge can set down or pick up: the column, the height of the seated handle, and the surface of the water over it. */
    public record Stop(double x, double z, double seatTopY, double surfaceY) {
    }

    /**
     * The cattle chute on the way: where it begins and ends (columns), whether it holds water, where the surface of that water
     * is and where the top of its floor is (world Y). The chute is a body of its own: its level is its blocks', not the
     * cavity's or the pool's.
     */
    public record Chute(double entryX, double entryZ, double exitX, double exitZ, boolean filled, double surfaceY, double floorY) {
    }

    /** A waypoint of the loaded travel, and the surface of the water it is under ({@code NaN}: none). */
    public record Waypoint(Point at, double surfaceY) {
        public double coverM() {
            return cover(surfaceY, at.y());
        }
    }

    /** The loaded route from {@code from} to {@code to}, with the water cover at each waypoint and the least of them. */
    public record Route(Waypoint[] waypoints, double minCoverM) {
        public boolean crossesChute() {
            return waypoints.length == 4;
        }
    }

    /**
     * The waypoints of a loaded move: lifted over the source, (through the chute,) over the destination. The tip height
     * over each is its seat plus the lift. In the chute the assembly is brought to {@link #CHUTE_COVER_M} under the lower of
     * the two surfaces (an overridden lift stays as high as it is). {@code minCoverM} counts the drained chute as no cover.
     */
    public static Route route(Stop from, Stop to, Chute chute, boolean overridden) {
        double lift = lift(overridden);
        double yFrom = from.seatTopY() + lift;
        double yTo = to.seatTopY() + lift;
        if (chute == null) {
            Waypoint[] w = {new Waypoint(new Point(from.x(), yFrom, from.z()), from.surfaceY()),
                    new Waypoint(new Point(to.x(), yTo, to.z()), to.surfaceY())};
            return new Route(w, Math.min(w[0].coverM(), w[1].coverM()));
        }
        double canalSurface = chute.filled() ? chute.surfaceY() : Double.NaN;
        double lowest = Math.min(Math.min(from.surfaceY(), to.surfaceY()), chute.filled() ? chute.surfaceY() : Double.MAX_VALUE);
        double yChute = overridden ? Math.max(yFrom, yTo) : lowest - CHUTE_COVER_M;
        Waypoint[] w = {
                new Waypoint(new Point(from.x(), yFrom, from.z()), from.surfaceY()),
                new Waypoint(new Point(chute.entryX(), yChute, chute.entryZ()), from.surfaceY()),
                new Waypoint(new Point(chute.exitX(), yChute, chute.exitZ()), to.surfaceY()),
                new Waypoint(new Point(to.x(), yTo, to.z()), to.surfaceY())};
        double min = Double.MAX_VALUE;
        for (Waypoint p : w) {
            min = Math.min(min, p.coverM());
        }
        min = Math.min(min, cover(canalSurface, yChute));
        return new Route(w, min);
    }

    /**
     * Metres between the bottom of the assembly, hanging {@link #ASSEMBLY_LENGTH_M} below the grapple at the height it crosses
     * the chute, and the chute's floor; negative means it would hang through the floor. Infinite for a route that does not
     * cross the chute.
     */
    public static double chuteFloorMarginM(Route route, Chute chute) {
        return route.crossesChute() ? route.waypoints()[1].at().y() - ASSEMBLY_LENGTH_M - chute.floorY() : Double.POSITIVE_INFINITY;
    }

    /** Total ticks of a loaded route (its legs), not counting the lift and the lowering. */
    public static int routeTicks(Route route, boolean runwayAlongX) {
        int total = 0;
        for (int i = 1; i < route.waypoints().length; i++) {
            total += legTicks(route.waypoints()[i - 1].at(), route.waypoints()[i].at(), runwayAlongX, true);
        }
        return total;
    }
}
