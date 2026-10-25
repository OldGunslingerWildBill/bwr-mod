package dev.bwr.core.refuelling;

import dev.bwr.core.radiation.DoseModel;

/**
 * The arithmetic of the new fuel elevator: the path a fuel assembly and the carriage take between the loading hatch on the
 * rim of a spent fuel pool and a rack cell, how long each leg takes, and where the assembly is (and so how much water is over
 * its handle) at any moment. Pure Java, no Minecraft. The block entity only stores when a trip began and calls these; the
 * client draws the pose from the same numbers, and the radiation provider evaluates the same pose when the meter asks, so
 * nothing ticks while an assembly is on its way.
 *
 * <h2>A trip down</h2>
 * <ol>
 *   <li>{@link Leg#DESCEND}: the carriage rides the guide frame with the assembly from the hatch to {@code transferY}, one metre
 *       over the rack seat;</li>
 *   <li>{@link Leg#SLIDE}: the assembly leaves the carriage and runs over the rack tops, at that height, to the cell;</li>
 *   <li>{@link Leg#SEAT}: it is lowered onto its seat. <b>The assembly enters the rack cell at the end of this leg.</b></li>
 *   <li>{@link Leg#RETURN}: the empty carriage goes back up to the hatch.</li>
 * </ol>
 * A trip up is the same in reverse: the empty carriage descends, the assembly <b>leaves its rack cell when the carriage
 * arrives</b> (end of the first leg), is lifted a metre, slides to the guide, and rides the carriage back up to the hatch.
 *
 * <h2>Water</h2>
 * The top of the assembly (its handle) is the point that matters, as it is for the refuelling bridge: the water between the handle
 * and the surface is the shielding ({@link BridgeMotion#transmission}). An assembly at the hatch has its handle above the surface
 * of a full pool, so a hot one there is a source in air. Fresh fuel makes no gamma and is never a source.
 */
public final class FuelElevatorMotion {
    private FuelElevatorMotion() {
    }

    /** Carriage speed on the guide with an assembly on it, m/s (the bridge's loaded hoist is 0.3). */
    public static final double GUIDE_LOADED_M_PER_S = 0.5;
    /** Carriage speed on the guide with nothing on it, m/s. */
    public static final double GUIDE_EMPTY_M_PER_S = 1.0;
    /** Speed of the assembly across the rack tops, m/s (the bridge's trolley speed). */
    public static final double SLIDE_M_PER_S = 0.6;
    /** Speed of the assembly raised from or lowered onto its seat, m/s. */
    public static final double SEAT_M_PER_S = 0.3;
    /** How far above its seat the assembly runs over the racks, m (the bridge's lift). */
    public static final double CLEARANCE_M = BridgeMotion.LIFT_M;
    /** Water that must stand over the handle of an assembly at the transfer height, m: the refuelling bridge's hoist limit. */
    public static final double MIN_COVER_M = 1.5;
    /** Height of the hatch (the carriage's rest position, the handle of the assembly waiting in it) over the foot of the elevator block, m. */
    public static final double HATCH_ABOVE_BLOCK_M = 1.3;

    public enum Kind {
        LOWER, RAISE;

        public static Kind byName(String name) {
            return "RAISE".equals(name) ? RAISE : LOWER;
        }
    }

    public enum Leg {
        DESCEND, SLIDE, SEAT, RETURN, LIFT, ASCEND
    }

    /**
     * Where everything is at one moment. The assembly's handle is at ({@code ax}, {@code ay}, {@code az}) while
     * {@code hasAssembly}; the carriage is always in the guide column at height {@code carriageY} (the height of its hook).
     */
    public record Pose(boolean hasAssembly, double ax, double ay, double az, double carriageY, boolean carriageLoaded, int legIndex, Leg leg) {
    }

    /** The geometry of one trip, world coordinates: the guide column, the heights, and the rack cell column. */
    public record Trip(Kind kind, double guideX, double guideZ, double hatchY, double transferY, double seatY,
                       double cellX, double cellZ, int[] legTicks) {
        public int totalTicks() {
            int t = 0;
            for (int n : legTicks) {
                t += n;
            }
            return t;
        }

        /** Ticks from the start until the assembly changes hands with the rack: it is put in (down) or taken out (up). */
        public int handoffTicks() {
            return kind == Kind.LOWER ? legTicks[0] + legTicks[1] + legTicks[2] : legTicks[0];
        }
    }

    private static int ticks(double seconds) {
        return (int) Math.max(1, Math.ceil(seconds * 20.0 - 1e-9));
    }

    /** The trip that takes an assembly from the hatch to the seat of a cell (down) or from the seat to the hatch (up). */
    public static Trip trip(Kind kind, double guideX, double guideZ, double hatchY, double seatY, double cellX, double cellZ) {
        double transfer = seatY + CLEARANCE_M;
        double fall = Math.max(0.0, hatchY - transfer);
        double slide = Math.hypot(cellX - guideX, cellZ - guideZ);
        int[] legs;
        if (kind == Kind.LOWER) {
            legs = new int[] {ticks(fall / GUIDE_LOADED_M_PER_S), ticks(slide / SLIDE_M_PER_S), ticks(CLEARANCE_M / SEAT_M_PER_S),
                    ticks(fall / GUIDE_EMPTY_M_PER_S)};
        } else {
            legs = new int[] {ticks(fall / GUIDE_EMPTY_M_PER_S), ticks(CLEARANCE_M / SEAT_M_PER_S), ticks(slide / SLIDE_M_PER_S),
                    ticks(fall / GUIDE_LOADED_M_PER_S)};
        }
        return new Trip(kind, guideX, guideZ, hatchY, transfer, seatY, cellX, cellZ, legs);
    }

    private static double lerp(double a, double b, double elapsed, int total) {
        double f = total <= 0 ? 1.0 : Math.max(0.0, Math.min(1.0, elapsed / total));
        return a + (b - a) * f;
    }

    /** The pose {@code elapsedTicks} (fractional, for smooth drawing) after the trip began; clamped to the trip. */
    public static Pose poseAt(Trip t, double elapsedTicks) {
        double e = Math.max(0.0, elapsedTicks);
        int index = 0;
        for (; index < t.legTicks().length - 1 && e >= t.legTicks()[index]; index++) {
            e -= t.legTicks()[index];
        }
        int n = t.legTicks()[index];
        if (t.kind() == Kind.LOWER) {
            return switch (index) {
                case 0 -> {
                    double y = lerp(t.hatchY(), t.transferY(), e, n);
                    yield new Pose(true, t.guideX(), y, t.guideZ(), y, true, 0, Leg.DESCEND);
                }
                case 1 -> new Pose(true, lerp(t.guideX(), t.cellX(), e, n), t.transferY(), lerp(t.guideZ(), t.cellZ(), e, n),
                        t.transferY(), false, 1, Leg.SLIDE);
                case 2 -> new Pose(true, t.cellX(), lerp(t.transferY(), t.seatY(), e, n), t.cellZ(), t.transferY(), false, 2, Leg.SEAT);
                default -> new Pose(false, 0, 0, 0, lerp(t.transferY(), t.hatchY(), e, n), false, 3, Leg.RETURN);
            };
        }
        return switch (index) {
            case 0 -> new Pose(false, 0, 0, 0, lerp(t.hatchY(), t.transferY(), e, n), false, 0, Leg.DESCEND);
            case 1 -> new Pose(true, t.cellX(), lerp(t.seatY(), t.transferY(), e, n), t.cellZ(), t.transferY(), false, 1, Leg.LIFT);
            case 2 -> new Pose(true, lerp(t.cellX(), t.guideX(), e, n), t.transferY(), lerp(t.cellZ(), t.guideZ(), e, n),
                    t.transferY(), false, 2, Leg.SLIDE);
            default -> {
                double y = lerp(t.transferY(), t.hatchY(), e, n);
                yield new Pose(true, t.guideX(), y, t.guideZ(), y, true, 3, Leg.ASCEND);
            }
        };
    }

    /** Whether the assembly has been taken out of its cell by this moment of an up trip, or put into it by this moment of a down trip. */
    public static boolean handedOff(Trip t, double elapsedTicks) {
        return elapsedTicks >= t.handoffTicks();
    }

    /**
     * Whether an assembly may be lowered to a seat at {@code seatY}: the water over its handle at the transfer height
     * ({@code surfaceY - transferY}) must be at least {@link #MIN_COVER_M}.
     */
    public static boolean coveredEnough(double surfaceY, double seatY) {
        return surfaceY - (seatY + CLEARANCE_M) >= MIN_COVER_M - 1.0e-9;
    }

    /** Dose rate of an assembly of {@code gammaWatts} whose handle is at {@code handleY} under a surface at {@code surfaceY}, seen from {@code distanceM}. */
    public static double doseRateMsvPerH(double gammaWatts, double distanceM, double surfaceY, double handleY) {
        return DoseModel.pointSourceMsvPerH(gammaWatts, distanceM, BridgeMotion.transmission(BridgeMotion.cover(surfaceY, handleY)));
    }
}
