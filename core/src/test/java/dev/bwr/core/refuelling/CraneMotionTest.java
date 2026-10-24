package dev.bwr.core.refuelling;

import dev.bwr.core.Check;
import dev.bwr.core.refuelling.BridgeMotion.Point;

/** Speeds and legs of the reactor building crane and the timing and energy of the stud tensioner. */
public final class CraneMotionTest {

    private CraneMotionTest() {
    }

    public static void test01_aCraneLegTakesAsLongAsItsSlowestAxis() {
        Point a = new Point(0, 20, 0);
        Check.exactly(1200, CraneMotion.legTicks(a, new Point(30, 20, 0), true, false), "30 m of crane travel at 0.5 m/s is 60 s");
        Check.exactly(300, CraneMotion.legTicks(a, new Point(3, 20, 6), true, false), "the 6 m of trolley travel at 0.4 m/s sets the pace: 15 s");
        Check.exactly(300, CraneMotion.legTicks(a, new Point(6, 20, 3), false, false), "and the axes swap with the runway");
        Check.exactly(240, CraneMotion.legTicks(a, new Point(0, 14, 0), true, false), "6 m of empty hook at 0.5 m/s is 12 s");
        Check.exactly(960, CraneMotion.legTicks(a, new Point(0, 8, 0), true, true), "12 m of loaded hook at 0.25 m/s is 48 s");
        Check.exactly(1, CraneMotion.legTicks(a, a, true, true), "a leg is never zero ticks");
        Check.exactly(2 * CraneMotion.legTicks(a, new Point(0, 8, 0), true, false), CraneMotion.legTicks(a, new Point(0, 8, 0), true, true),
                "a loaded hook is half the speed of an empty one");
        Check.isTrue(CraneMotion.BRIDGE_SPEED_M_PER_S < BridgeMotion.BRIDGE_SPEED_M_PER_S, "a crane is slower than the refuelling platform");
    }

    public static void test02_aFullPassOfTheStudTensionerTakesRealTimeAndEnergy() {
        int n = CraneMotion.REFERENCE_STUDS;
        Check.exactly(74, n, "the reference vessel has 74 studs");
        Check.exactly(5920, CraneMotion.passTicks(n, true), "74 studs loosened at 4 s each is 296 s");
        Check.exactly(8880, CraneMotion.passTicks(n, false), "and tightened at 6 s each is 444 s");
        Check.exactly(222_000L, CraneMotion.passFe(n, true), "loosening takes 222,000 FE");
        Check.exactly(444_000L, CraneMotion.passFe(n, false), "tightening takes 444,000 FE");
        Check.exactly(0L, CraneMotion.passFe(-3, true), "no studs, no energy");
        Check.note("74 studs: %.0f s and %,d FE to detension, %.0f s and %,d FE to retension",
                CraneMotion.passTicks(n, true) / 20.0, CraneMotion.passFe(n, true), CraneMotion.passTicks(n, false) / 20.0, CraneMotion.passFe(n, false));
    }

    public static void test03_theHeadIsLiftableOnlyWithEveryStudFreeAndSealedOnlyWithEveryStudAtLoad() {
        Check.isFalse(CraneMotion.headLiftable(73, 74), "one stud still tensioned: the head stays");
        Check.isTrue(CraneMotion.headLiftable(74, 74), "all free: it lifts");
        Check.isFalse(CraneMotion.headLiftable(0, 0), "a vessel with no flange has no head to lift");
        Check.isTrue(CraneMotion.headSealed(0), "all at load: sealed");
        Check.isFalse(CraneMotion.headSealed(1), "one stud free: not sealed");
    }
}
