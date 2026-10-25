package dev.bwr.core.refuelling;

import dev.bwr.core.Check;
import dev.bwr.core.radiation.DoseModel;
import dev.bwr.core.refuelling.FuelElevatorMotion.Kind;
import dev.bwr.core.refuelling.FuelElevatorMotion.Leg;

/**
 * The new fuel elevator's paths and timings: the order of the legs, where the assembly is at each moment, when it changes hands with
 * the rack, and the water over its handle (the same law as the refuelling bridge).
 */
public final class FuelElevatorMotionTest {

    private FuelElevatorMotionTest() {
    }

    /** A pool whose rim top is at y = 108, rack seat at 105 (floor 101 + 4), guide at x = 1.5, cell at x = 6.25. */
    private static FuelElevatorMotion.Trip down() {
        return FuelElevatorMotion.trip(Kind.LOWER, 1.5, 3.5, 109.0, 105.0, 6.25, 3.75);
    }

    private static FuelElevatorMotion.Trip up() {
        return FuelElevatorMotion.trip(Kind.RAISE, 1.5, 3.5, 109.0, 105.0, 6.25, 3.75);
    }

    public static void test01_aTripDownIsDescendSlideSeatReturnInThatOrder() {
        var t = down();
        Check.exactly(4, t.legTicks().length, "four legs");
        var p0 = FuelElevatorMotion.poseAt(t, 0);
        Check.isTrue(p0.hasAssembly() && p0.leg() == Leg.DESCEND && p0.carriageLoaded(), "starts with the assembly on the carriage in the hatch");
        Check.exactly(109.0, p0.ay(), "at the hatch");
        Check.exactly(1.5, p0.ax(), "in the guide column");
        // after the descent: at the transfer height, one metre over the seat
        var p1 = FuelElevatorMotion.poseAt(t, t.legTicks()[0]);
        Check.isTrue(p1.leg() == Leg.SLIDE, "then it slides: %s", p1.leg());
        Check.exactly(106.0, p1.ay(), "transfer height is a metre over the seat");
        var mid = FuelElevatorMotion.poseAt(t, t.legTicks()[0] + t.legTicks()[1] / 2.0);
        Check.isTrue(mid.leg() == Leg.SLIDE && !mid.carriageLoaded(), "the carriage stays at depth while the assembly slides");
        Check.relative((1.5 + 6.25) / 2.0, mid.ax(), 1e-2, "half way across");
        Check.exactly(106.0, mid.carriageY(), "carriage waits at the transfer height");
        var seat = FuelElevatorMotion.poseAt(t, t.legTicks()[0] + t.legTicks()[1] + t.legTicks()[2] - 1e-9);
        Check.isTrue(seat.leg() == Leg.SEAT, "seating: %s", seat.leg());
        Check.relative(105.0, seat.ay(), 1e-6, "ends on the seat");
        Check.relative(6.25, seat.ax(), 1e-12, "over the cell");
        var back = FuelElevatorMotion.poseAt(t, t.totalTicks() - 1e-9);
        Check.isTrue(!back.hasAssembly() && back.leg() == Leg.RETURN, "the empty carriage returns");
        Check.relative(109.0, back.carriageY(), 1e-6, "to the hatch");
        var end = FuelElevatorMotion.poseAt(t, t.totalTicks() + 500);
        Check.exactly(109.0, end.carriageY(), "clamped at the end");
    }

    public static void test02_theAssemblyChangesHandsWithTheRackAtTheRightMoment() {
        var d = down();
        Check.exactly(d.legTicks()[0] + d.legTicks()[1] + d.legTicks()[2], d.handoffTicks(), "down: put in when it is seated");
        Check.isTrue(!FuelElevatorMotion.handedOff(d, d.handoffTicks() - 1), "not before");
        Check.isTrue(FuelElevatorMotion.handedOff(d, d.handoffTicks()), "and then");
        var u = up();
        Check.exactly(u.legTicks()[0], u.handoffTicks(), "up: taken out when the empty carriage arrives");
        var before = FuelElevatorMotion.poseAt(u, u.legTicks()[0] - 1);
        Check.isTrue(!before.hasAssembly(), "until then nothing is in flight");
        var lift = FuelElevatorMotion.poseAt(u, u.legTicks()[0]);
        Check.isTrue(lift.hasAssembly() && lift.leg() == Leg.LIFT, "then it is lifted: %s", lift.leg());
        Check.exactly(105.0, lift.ay(), "from its seat");
        var top = FuelElevatorMotion.poseAt(u, u.totalTicks() - 1e-9);
        Check.isTrue(top.hasAssembly() && top.carriageLoaded() && top.leg() == Leg.ASCEND, "rides up on the carriage");
        Check.relative(109.0, top.ay(), 1e-6, "to the hatch");
    }

    public static void test03_timesAreTheSlowestAxisAndNeverZero() {
        var t = down();
        // 3 m of fall at 0.5 m/s is 6 s = 120 ticks; slide 5.08 m at 0.6 m/s; seat 1 m at 0.3 m/s
        Check.exactly(120, t.legTicks()[0], "descent");
        Check.exactly((int) Math.ceil(Math.hypot(4.75, 0.25) / 0.6 * 20.0 - 1e-9), t.legTicks()[1], "slide");
        Check.exactly((int) Math.ceil(1.0 / 0.3 * 20.0 - 1e-9), t.legTicks()[2], "seat");
        Check.exactly(60, t.legTicks()[3], "empty carriage at 1 m/s");
        var same = FuelElevatorMotion.trip(Kind.LOWER, 2.0, 2.0, 108.0, 107.0, 2.0, 2.0);
        for (int n : same.legTicks()) {
            Check.isTrue(n >= 1, "every leg takes at least a tick: %s", n);
        }
        Check.note("trip down in a small pool: %d ticks (%.0f s)", t.totalTicks(), t.totalTicks() / 20.0);
    }

    public static void test04_coverAtTheTransferHeightIsTheBridgesHoistLimit() {
        // seat 105, transfer 106: the surface must be at least 107.5
        Check.isTrue(FuelElevatorMotion.coveredEnough(107.5, 105.0), "exactly the limit");
        Check.isTrue(FuelElevatorMotion.coveredEnough(112.0, 105.0), "a full pool");
        Check.isTrue(!FuelElevatorMotion.coveredEnough(107.4, 105.0), "a little low");
        Check.isTrue(!FuelElevatorMotion.coveredEnough(105.0, 105.0), "at the rack top");
        Check.isTrue(!FuelElevatorMotion.coveredEnough(Double.NaN, 105.0), "no water at all");
    }

    public static void test05_aHotAssemblyAtTheHatchIsLoudAndUnderWaterItIsQuiet() {
        double gamma = BridgeMotion.gammaWatts(315_000.0); // a just-discharged 45 GWd/t assembly
        double surface = 112.0;
        double inAir = FuelElevatorMotion.doseRateMsvPerH(gamma, 3.0, surface, 113.0);
        double deep = FuelElevatorMotion.doseRateMsvPerH(gamma, 3.0, surface, 106.0);
        Check.greaterThan(1.0e5 * deep, inAir, "out of the water the reading is more than a hundred thousand times higher");
        Check.exactly(DoseModel.pointSourceMsvPerH(gamma, 3.0, 1.0), inAir, "in air nothing shields");
        Check.exactly(0.0, FuelElevatorMotion.doseRateMsvPerH(0.0, 3.0, surface, 113.0), "fresh fuel makes no gamma");
    }
}
