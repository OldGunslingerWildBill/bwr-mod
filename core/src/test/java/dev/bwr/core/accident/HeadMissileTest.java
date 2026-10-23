package dev.bwr.core.accident;

import dev.bwr.core.Check;
import dev.bwr.core.boundary.BoundaryStress;

/**
 * The vessel head as a missile: mass, launch energy from the failure's pressure and stored energy, the climb cap, and
 * the row-by-row containment bookkeeping. The world adapter (HeadMissileLauncher) feeds this the block ratings.
 */
public final class HeadMissileTest {
    private HeadMissileTest() {
    }

    private static final double FLASH_KG = BoundaryStress.HEAD_FAILURE_LIQUID_FRACTION * BoundaryStress.RATED_CORE_FLOW_KG_PER_S;
    /** Steam dome of the reference vessel that gives the documented 4.9 GJ at the code limit. */
    private static final double DOME_M3 = 185.0;

    private static double stored(double psig) {
        return BlastPhysics.headFailureEnergyJ(psig, DOME_M3, FLASH_KG);
    }

    /** Capacity of one row of {@code cells} blocks of the given blast resistance, J. */
    private static double row(double resistance, int cells) {
        return cells * HeadMissile.crushJ(ContainmentWallStrength.psi(resistance));
    }

    private static double[] repeat(double value, int n) {
        double[] out = new double[n];
        java.util.Arrays.fill(out, value);
        return out;
    }

    public static void test01_headMassFollowsTheFootprint() {
        Check.exactly(90_000.0, HeadMissile.massKg(17, 17), "reference head mass");
        Check.inRange(80_000.0, 100_000.0, HeadMissile.massKg(17, 17), "a real BWR head is 80 to 100 t");
        Check.isTrue(HeadMissile.massKg(7, 7) < HeadMissile.massKg(11, 11) && HeadMissile.massKg(11, 11) < HeadMissile.massKg(23, 23),
                "bigger vessel, heavier head");
        Check.relative(HeadMissile.massKg(9, 9) * 2.0, HeadMissile.massKg(9, 18), 1.0e-9, "mass is proportional to area");
        Check.finite(HeadMissile.massKg(0, -4), "nonsense size stays finite");
        Check.note("head mass: 7x7 %.1f t, 9x9 %.1f t, 17x17 %.1f t, 23x23 %.1f t", HeadMissile.massKg(7, 7) / 1000.0,
                HeadMissile.massKg(9, 9) / 1000.0, HeadMissile.massKg(17, 17) / 1000.0, HeadMissile.massKg(23, 23) / 1000.0);
    }

    public static void test02_launchEnergyComesFromPressureAndStoredEnergy() {
        double e1375 = stored(1375.0);
        Check.relative(4.9e9, e1375, 0.05, "stored energy at the code limit");
        double ke1375 = HeadMissile.kineticEnergyJ(e1375, 1375.0, 17, 17);
        Check.relative(HeadMissile.KINETIC_FRACTION * e1375, ke1375, 1.0e-9, "at the code limit the stored-energy share is the limit");
        Check.isTrue(HeadMissile.steamWorkJ(1375.0, 17, 17) > ke1375, "the steam could do more work than the share lets the head have");
        // A lower failure pressure gives a weaker launch.
        double previous = Double.MAX_VALUE;
        for (double psig : new double[] {1700, 1375, 1000, 700, 400, 100, 20}) {
            double ke = HeadMissile.kineticEnergyJ(stored(psig), psig, 17, 17);
            Check.isTrue(ke < previous, "launch energy must fall with pressure at %.0f psig", psig);
            previous = ke;
        }
        // Steam that cannot lift the head's weight does nothing.
        double weightPsi = HeadMissile.massKg(17, 17) * HeadMissile.GRAVITY / HeadMissile.areaM2(17, 17) / 6894.757;
        Check.exactly(0.0, HeadMissile.kineticEnergyJ(stored(weightPsi * 0.9), weightPsi * 0.9, 17, 17),
                "steam that cannot lift the head's weight does not launch it");
        // Head area sets the force: the same pressure on a wider head does more work.
        Check.isTrue(HeadMissile.steamWorkJ(300.0, 23, 23) > HeadMissile.steamWorkJ(300.0, 11, 11), "force follows area");
        Check.note("17x17 launch energy: %.0f MJ at 1,375 psig (stored %.2f GJ), %.0f MJ at 700, %.1f MJ at 100; steam work at 1,375 is %.2f GJ",
                ke1375 / 1e6, e1375 / 1e9, HeadMissile.kineticEnergyJ(stored(700), 700, 17, 17) / 1e6,
                HeadMissile.kineticEnergyJ(stored(100), 100, 17, 17) / 1e6, HeadMissile.steamWorkJ(1375.0, 17, 17) / 1e9);
    }

    public static void test03_heightIsCapped() {
        double mass = HeadMissile.massKg(17, 17);
        double ke = HeadMissile.kineticEnergyJ(stored(1375.0), 1375.0, 17, 17);
        double free = HeadMissile.freeRiseBlocks(mass, ke);
        Check.inRange(200.0, 400.0, free, "uncapped rise of the reference head, blocks");
        Check.exactly(40.0, HeadMissile.apexBlocks(mass, ke), "the climb is capped");
        Check.relative(28.0, HeadMissile.speedForRise(40.0), 0.01, "launch speed for 40 blocks, m/s");
        Check.relative(HeadMissile.speedForRise(10.0) * 2.0 / 9.81, HeadMissile.flightSeconds(10.0), 1.0e-9, "flight time up and back");
        Check.isTrue(HeadMissile.apexBlocks(mass, ke * 0.001) < 40.0, "a weak launch does not reach the cap");
        Check.note("reference head at the code limit: free rise %.0f blocks, v0 %.0f m/s uncapped; flight capped to 40 blocks (%.0f m/s, %.1f s)",
                free, Math.sqrt(2 * ke / mass), HeadMissile.speedForRise(40), HeadMissile.flightSeconds(40));
    }

    public static void test04_blocksResistOnTheWallRatingBasis() {
        double obsidian = ContainmentWallStrength.psi(1200.0);
        Check.relative(obsidian * 6894.757, HeadMissile.crushJ(obsidian), 1.0e-9, "one block: rating over one square metre through one metre");
        Check.isTrue(HeadMissile.crushJ(ContainmentWallStrength.psi(1.8)) < HeadMissile.crushJ(ContainmentWallStrength.psi(180.0)),
                "stronger block, more energy");
        Check.exactly(HeadMissile.crushJ(1.0), HeadMissile.crushJ(0.0), "zero-resistance blocks are floored like the blasts floor them");
        Check.note("a row of 227 obsidian-class blocks absorbs %.0f MJ; of resistance-180 reinforced concrete %.0f MJ; of stone %.1f MJ",
                row(1200.0, 227) / 1e6, row(180.0, 227) / 1e6, row(6.0, 227) / 1e6);
    }

    public static void test05_thickEnoughStackContainsAndThinOneDoesNot() {
        double mass = HeadMissile.massKg(17, 17);
        double ke = HeadMissile.kineticEnergyJ(stored(1375.0), 1375.0, 17, 17);
        double concrete = row(180.0, 227);
        int needed = 0;
        while (needed * concrete + needed * mass * HeadMissile.GRAVITY <= ke) needed++;
        // 'needed' rows are punched through; the next one stops the head.
        var held = HeadMissile.resolve(mass, ke, repeat(concrete, needed + 1));
        var through = HeadMissile.resolve(mass, ke, repeat(concrete, needed - 2));
        var spent = HeadMissile.resolve(mass, ke, repeat(concrete, needed - 1));
        Check.isTrue(held.contained() && held.last() == HeadMissile.Step.STOPPED, "%d rows of reinforced concrete hold the head", needed + 1);
        Check.inRange(0.0, 1.0, held.stopFraction(), "share of the stopping row used");
        Check.isTrue(!through.contained() && through.cleared(), "%d rows do not hold it", needed - 2);
        // Punch through all but the last row and there is too little left to climb on: it peaks just above the stack and falls back.
        Check.isTrue(spent.contained() && spent.last() == HeadMissile.Step.PEAKED && !spent.cleared(),
                "%d rows leave a head with nothing to fly on", needed - 1);
        Check.exactly(40.0, through.apexBlocks(), "a head that gets through flies to the cap");
        // The same failure at a lower pressure is held by a thinner stack.
        double keLow = HeadMissile.kineticEnergyJ(stored(500.0), 500.0, 17, 17);
        int low = 0;
        while (low * concrete + low * mass * HeadMissile.GRAVITY <= keLow) low++;
        Check.isTrue(low <= needed - 2, "500 psig needs %d rows, 1,375 psig %d", low, needed);
        Check.isTrue(HeadMissile.resolve(mass, keLow, repeat(concrete, low)).contained(), "500 psig is held by %d rows", low);
        Check.isTrue(HeadMissile.resolve(mass, ke, repeat(concrete, low)).cleared(), "the same rows do not hold 1,375 psig");
        Check.note("17x17 head, resistance-180 reinforced concrete (%.0f MJ a row): 1,375 psig needs %d rows (%.0f MJ), 500 psig needs %d (%.0f MJ)",
                concrete / 1e6, needed, ke / 1e6, low, keLow / 1e6);
    }

    public static void test06_climbBookkeeping() {
        double mass = 50_000.0;
        double grav = mass * HeadMissile.GRAVITY;
        // Open rows cost gravity only.
        var open = new HeadMissile.Climb(mass, 10.5 * grav);
        for (int i = 0; i < 10; i++) Check.isTrue(open.next(0.0) == HeadMissile.Step.PASSED, "open row %d", i);
        Check.isTrue(open.next(0.0) == HeadMissile.Step.PEAKED, "energy gone in the air");
        Check.exactly(11, open.rows(), "rows crossed when it peaked");
        // A row it cannot punch stops it and records how far it got.
        var strong = new HeadMissile.Climb(mass, 100.0e6);
        Check.isTrue(strong.next(400.0e6) == HeadMissile.Step.STOPPED, "stopped");
        Check.relative(0.25, strong.stopFraction(), 1.0e-9, "a quarter of the row's capacity used");
        Check.exactly(0, strong.rows(), "stopped at the first row");
        // A row nothing can break stops it with nothing used.
        var wall = new HeadMissile.Climb(mass, 1.0e12);
        Check.isTrue(wall.next(Double.POSITIVE_INFINITY) == HeadMissile.Step.STOPPED, "unbreakable row stops any head");
        Check.exactly(0.0, wall.stopFraction(), "nothing used on an unbreakable row");
        // A punched row costs its capacity plus the height.
        var punch = new HeadMissile.Climb(mass, 100.0e6);
        Check.isTrue(punch.canPunch(60.0e6) && !punch.canPunch(160.0e6), "canPunch compares the energy left");
        Check.isTrue(punch.next(60.0e6) == HeadMissile.Step.PASSED, "punched");
        Check.relative(100.0e6 - 60.0e6 - grav, punch.energyJ(), 1.0e-9, "energy after a punched row");
        // NaN and negative capacities are open rows.
        var odd = new HeadMissile.Climb(mass, 1.0e9);
        Check.isTrue(odd.next(Double.NaN) == HeadMissile.Step.PASSED && odd.next(-5.0) == HeadMissile.Step.PASSED, "odd capacities are open");
        Check.finite(odd.energyJ(), "energy stays finite");
        // The climb ends at the cap.
        var climb = new HeadMissile.Climb(mass, 1.0e12);
        for (int i = 0; i < HeadMissile.MAX_CLIMB_BLOCKS; i++) climb.next(0.0);
        Check.isTrue(climb.cleared() && climb.apexBlocks(mass) == HeadMissile.MAX_CLIMB_BLOCKS, "cleared at the cap");
    }

    public static void test07_impactEnergy() {
        double mass = HeadMissile.massKg(17, 17);
        double v = HeadMissile.speedForRise(40.0) / HeadMissile.TICKS_PER_SECOND;
        double e = HeadMissile.impactEnergyJ(mass, -v);
        Check.relative(mass * HeadMissile.GRAVITY * 40.0, e, 1.0e-9, "falling 40 blocks arrives with m g h");
        Check.note("a 90 t head falling 40 blocks lands with %.0f MJ; a row of 227 stone blocks absorbs %.1f MJ", e / 1e6, row(6.0, 227) / 1e6);
    }

    public static void test08_aThrownHeadComesDownClearOfItsVessel() {
        for (int[] size : new int[][] {{7, 7}, {11, 11}, {17, 17}, {23, 11}, {23, 23}}) {
            double min = HeadMissile.driftDistanceBlocks(size[0], size[1], 0.0);
            double max = HeadMissile.driftDistanceBlocks(size[0], size[1], 1.0);
            // The head's near edge is at least DRIFT_MIN beyond the vessel's corner.
            double corner = Math.hypot(size[0], size[1]) / 2.0;
            Check.isTrue(min - Math.max(size[0], size[1]) / 2.0 >= corner + HeadMissile.DRIFT_MIN_BLOCKS - 1.0e-9,
                    "%dx%d: the head lands on its vessel", size[0], size[1]);
            Check.relative(HeadMissile.DRIFT_SPREAD_BLOCKS, max - min, 1.0e-9, "spread of the landing distance");
        }
        Check.note("a thrown head comes down %.1f to %.1f blocks from the axis of an 11x11 vessel, %.1f to %.1f from a 23x23",
                HeadMissile.driftDistanceBlocks(11, 11, 0.0), HeadMissile.driftDistanceBlocks(11, 11, 1.0),
                HeadMissile.driftDistanceBlocks(23, 23, 0.0), HeadMissile.driftDistanceBlocks(23, 23, 1.0));
    }
}
