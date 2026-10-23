package dev.bwr.core.refuelling;

import dev.bwr.core.Check;
import dev.bwr.core.radiation.DoseModel;
import dev.bwr.core.refuelling.BridgeMotion.Chute;
import dev.bwr.core.refuelling.BridgeMotion.Point;
import dev.bwr.core.refuelling.BridgeMotion.Route;
import dev.bwr.core.refuelling.BridgeMotion.Stop;

/** Speeds, legs, the water over the handle, the route and the hoist-limit interlock of the refuelling bridge. */
public final class BridgeMotionTest {

    private BridgeMotionTest() {
    }

    public static void test01_aLegTakesAsLongAsItsSlowestAxis() {
        Point a = new Point(0, 10, 0);
        // 30 m along the rails at 1 m/s
        Check.exactly(600, BridgeMotion.legTicks(a, new Point(30, 10, 0), true, false), "30 m of bridge travel is 30 s");
        // 6 m across at 0.6 m/s is 10 s, slower than the 4 m of bridge travel beside it
        Check.exactly(200, BridgeMotion.legTicks(a, new Point(4, 10, 6), true, false), "the trolley sets the pace");
        // the same move with the rails along z: 6 m of bridge travel is 6 s, the 4 m across is 6.7 s of trolley
        Check.exactly(134, BridgeMotion.legTicks(a, new Point(4, 10, 6), false, false), "the axes swap with the runway");
        Check.exactly(120, BridgeMotion.legTicks(a, new Point(3, 10, 6), false, false), "and then the bridge sets the pace");
        // hoisting: 6 m empty at 0.6 m/s is 10 s, loaded at 0.3 m/s is 20 s
        Check.exactly(200, BridgeMotion.legTicks(a, new Point(0, 4, 0), true, false), "empty hoist");
        Check.exactly(400, BridgeMotion.legTicks(a, new Point(0, 4, 0), true, true), "loaded hoist is half the speed");
        Check.exactly(1, BridgeMotion.legTicks(a, a, true, true), "a leg is never zero ticks");
        Point mid = BridgeMotion.lerp(a, new Point(30, 4, 6), 150, 300);
        Check.absolute(15.0, mid.x(), 1e-9, "halfway along x");
        Check.absolute(7.0, mid.y(), 1e-9, "halfway in y");
    }

    public static void test02_theWaterOverTheHandleShieldsLikeTheReactorModelsOpenHead() {
        Check.exactly(0.0, BridgeMotion.cover(10.0, 11.0), "above the surface there is no cover");
        Check.exactly(0.0, BridgeMotion.cover(Double.NaN, 5.0), "no water at all is no cover");
        Check.exactly(2.5, BridgeMotion.cover(10.0, 7.5), "2.5 m of water");
        Check.exactly(1.0, BridgeMotion.transmission(0.0), "nothing shields in air");
        Check.exactly(DoseModel.waterTransmission(3.0), BridgeMotion.transmission(3.0), "the same law as the open head");
        Check.inRange(5.0e-10, 7.0e-10, BridgeMotion.transmission(3.0), "3 m of water");
        Check.inRange(2.0e-5, 3.0e-5, BridgeMotion.transmission(1.5), "1.5 m, the bridge's hoist limit");
        Check.relative(0.5 * 315_000.0, BridgeMotion.gammaWatts(315_000.0), 1e-12, "half of decay heat is gamma");
        Check.exactly(0.0, BridgeMotion.gammaWatts(Double.NaN), "NaN emits nothing");
        // What lifting does to the dose at the same distance: the same assembly, 3 m under, then at the interlock limit, then in air.
        double gamma = BridgeMotion.gammaWatts(100_000.0);
        double seated = DoseModel.pointSourceMsvPerH(gamma, 5.0, BridgeMotion.transmission(3.0));
        double limit = DoseModel.pointSourceMsvPerH(gamma, 5.0, BridgeMotion.transmission(1.5));
        double air = DoseModel.pointSourceMsvPerH(gamma, 5.0, BridgeMotion.transmission(0.0));
        Check.greaterThan(seated * 1.0e4, limit, "1.5 m less water is four decades");
        Check.greaterThan(limit * 1.0e4, air, "out of the water is another four decades");
        Check.note("100 kW assembly at 5 m: %.3g mSv/h under 3 m, %.3g under 1.5 m, %.3g in air", seated, limit, air);
    }

    public static void test03_aRouteInOneBodyOfWaterIsLiftedAndLowered() {
        // a core seat with 4.4 m of water over it to a core seat: lifted 1 m, 3.4 m of cover
        Stop a = new Stop(0, 0, 100.0, 104.4);
        Stop b = new Stop(5, 0, 100.0, 104.4);
        Route r = BridgeMotion.route(a, b, null, false);
        Check.exactly(2, r.waypoints().length, "no chute, two waypoints");
        Check.absolute(101.0, r.waypoints()[0].at().y(), 1e-12, "lifted one metre");
        Check.absolute(3.4, r.minCoverM(), 1e-9, "cover while carried");
        Check.inRange(1.5, 100.0, r.minCoverM(), "plenty of water over a lifted assembly");
        // the same seats under only 2 m of water: lifting one metre would leave 1 m
        Route shallow = BridgeMotion.route(new Stop(0, 0, 100.0, 102.0), new Stop(5, 0, 100.0, 102.0), null, false);
        Check.absolute(1.0, shallow.minCoverM(), 1e-9, "1 m of cover, short of the 1.5 m the bridge's hoist limit asks");
        // overridden, the lift is the whole assembly and the handle comes out of 4.4 m of water
        Route high = BridgeMotion.route(a, b, null, true);
        Check.absolute(104.5, high.waypoints()[0].at().y(), 1e-12, "the whole 4.5 m");
        Check.exactly(0.0, high.minCoverM(), "the handle is in the air");
    }

    public static void test04_throughTheChuteTheAssemblyStaysTwoMetresUnderTheLowerSurface() {
        Stop core = new Stop(0, 0, 100.0, 104.4);
        Stop rack = new Stop(30, 2, 96.0, 100.0);
        Chute chute = new Chute(8, 0, 20, 0, true, 103.0, 90.0);
        Route r = BridgeMotion.route(core, rack, chute, false);
        Check.isTrue(r.crossesChute() && r.waypoints().length == 4, "four waypoints through the chute");
        Check.absolute(98.0, r.waypoints()[1].at().y(), 1e-12, "2 m under the lowest of the three surfaces (the pool's 100)");
        Check.absolute(98.0, r.waypoints()[2].at().y(), 1e-12, "and level through the chute");
        Check.absolute(3.5, BridgeMotion.chuteFloorMarginM(r, chute), 1e-9, "the 4.5 m assembly hangs to 93.5, over a floor at 90");
        Check.absolute(-1.5, BridgeMotion.chuteFloorMarginM(r, new Chute(8, 0, 20, 0, true, 103.0, 95.0)), 1e-9, "a floor at 95 is hung through");
        Check.exactly(Double.POSITIVE_INFINITY, BridgeMotion.chuteFloorMarginM(BridgeMotion.route(core, new Stop(5, 0, 100.0, 104.4), null, false), chute), "no chute, no floor");
        Route lowChute = BridgeMotion.route(core, rack, new Chute(8, 0, 20, 0, true, 99.0, 90.0), false);
        Check.absolute(97.0, lowChute.waypoints()[1].at().y(), 1e-12, "a chute whose own surface is lowest sets the height"); 
        Check.absolute(97.0, r.waypoints()[3].at().y(), 1e-12, "lifted 1 m over the rack seat");
        Check.absolute(2.0, r.minCoverM(), 1e-9, "the least cover is the chute's 2 m (the rack is 100 - 97 = 3 m)");
        Route dry = BridgeMotion.route(core, rack, new Chute(8, 0, 20, 0, false, 103.0, 90.0), false);
        Check.exactly(0.0, dry.minCoverM(), "a drained chute is no cover");
        Check.isTrue(dry.minCoverM() < 1.5, "so any hoist limit would refuse it");
        int ticks = BridgeMotion.routeTicks(r, true);
        Check.isTrue(ticks > 20 * 30, "a transfer of 30 m takes over half a minute of travel: " + ticks);
        Check.note("core to rack route: %d ticks (%.0f s) loaded travel", ticks, ticks / 20.0);
    }

    public static void test05_aDeepPoolAndAShallowOneAgainstTheMinimum() {
        // the smallest pool: 7 m of water, racks 4 m tall: 3 m over the rack tops
        Route full = BridgeMotion.route(new Stop(0, 0, 4.0, 7.0), new Stop(2, 0, 4.0, 7.0), null, false);
        Check.absolute(2.0, full.minCoverM(), 1e-9, "a full small pool leaves 2 m with the 1 m lift");
        Route low = BridgeMotion.route(new Stop(0, 0, 4.0, 6.0), new Stop(2, 0, 4.0, 6.0), null, false);
        Check.absolute(1.0, low.minCoverM(), 1e-9, "a pool that has lost a metre leaves 1 m, under the 1.5 m asked");
    }
}
