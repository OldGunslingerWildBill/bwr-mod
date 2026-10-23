package dev.bwr.core.thermal;

import dev.bwr.core.Check;
import dev.bwr.core.CoreConfig;

/**
 * The refuelling well above the vessel flange: the volume-to-level mapping with a well, the annulus plateau, the spill of
 * water the well cannot hold, and that a vessel without a well is exactly what it was.
 */
public final class PressureVesselWellTest {

    private PressureVesselWellTest() {
    }

    private static PressureVessel cold() {
        PressureVessel v = new PressureVessel(new CoreConfig());
        v.initialiseCold();
        return v;
    }

    /** A 4 m2 annulus 1 m high and a 3-layer well of 80 m2: 1 m, 1 m and 0.5 m. */
    private static PressureVessel.Well well() {
        return new PressureVessel.Well(4.0, new double[] {1.0, 1.0, 0.5}, new double[] {80.0, 80.0, 60.0});
    }

    public static void test01_noWellLeavesEveryLevelAsItWas() {
        PressureVessel plain = cold();
        PressureVessel same = cold();
        same.setWell(null);
        for (double level : new double[] {-400, -167, 8, 59.9, 60, 61, 120, 400}) {
            plain.setCollapsedLevelIn(level);
            same.setCollapsedLevelIn(level);
            Check.exactly(plain.getCollapsedLevelIn(), same.getCollapsedLevelIn(), "level " + level + " with no well");
            Check.exactly(plain.getLiquidMassKg(), same.getLiquidMassKg(), "inventory " + level + " with no well");
        }
        Check.exactly(0.0, plain.getWellSpillKgPerS(), "no spill without a well");
    }

    public static void test02_belowTheFlangeAWellChangesNothing() {
        PressureVessel plain = cold();
        PressureVessel withWell = cold();
        withWell.setWell(well());
        for (double level : new double[] {-400, -167, 8, 59.0, 60}) {
            plain.setCollapsedLevelIn(level);
            withWell.setCollapsedLevelIn(level);
            Check.exactly(plain.getLiquidMassKg(), withWell.getLiquidMassKg(), "inventory at " + level + " in with a well");
            Check.exactly(plain.getCollapsedLevelIn(), withWell.getCollapsedLevelIn(), "level at " + level + " in with a well");
        }
    }

    public static void test03_theAnnulusFillsWhileTheLevelHoldsAtTheLip() {
        PressureVessel v = cold();
        v.setWell(well());
        v.setCollapsedLevelIn(60.0);
        double atLip = v.getLiquidMassKg();
        double rho = atLip / v.volumeAtFlangeM3();
        v.restoreLiquidMassKg(atLip + 2.0 * rho);
        Check.absolute(60.0, v.getCollapsedLevelIn(), 1.0e-9, "half the annulus in: the level is still the flange");
        v.restoreLiquidMassKg(atLip + 4.0 * rho);
        Check.absolute(60.0, v.getCollapsedLevelIn(), 1.0e-9, "annulus just full: the level is still the flange");
        v.restoreLiquidMassKg(atLip + 4.0 * rho + 80.0 * rho * 0.5);
        Check.absolute(60.0 + 0.5 / PressureVessel.METRES_PER_INCH, v.getCollapsedLevelIn(), 1.0e-9, "half a metre up the well");
    }

    public static void test04_theLevelRisesByTheLayersAreaAndIsInvertible() {
        PressureVessel v = cold();
        v.setWell(well());
        for (double metres : new double[] {0.25, 1.0, 1.75, 2.0, 2.4, 3.0}) {
            double level = 60.0 + metres / PressureVessel.METRES_PER_INCH;
            v.setCollapsedLevelIn(level);
            Check.absolute(level, v.getCollapsedLevelIn(), 1.0e-9, "setting " + metres + " m over the flange reads back");
        }
        // A 80 m2 layer: 1 m is 80 m3 of water, against 22 m2 below the flange.
        PressureVessel a = cold();
        a.setWell(well());
        a.setCollapsedLevelIn(60.0 + 1.0 / PressureVessel.METRES_PER_INCH);
        PressureVessel b = cold();
        b.setCollapsedLevelIn(60.0);
        double extraKg = a.getLiquidMassKg() - b.getLiquidMassKg();
        double rho = b.getLiquidMassKg() / b.volumeAtFlangeM3();
        Check.absolute(84.0, extraKg / rho, 1.0e-6, "one metre over the flange is the annulus (4 m3) plus 80 m3");
    }

    public static void test05_aWellWithNoSpaceHoldsNothingAndSpillsAtTheLip() {
        PressureVessel v = cold();
        v.setWell(PressureVessel.Well.none());
        v.setCollapsedLevelIn(60.0);
        v.setWellSpilling(true);
        v.setFeedwaterFlowKgPerS(1500.0);
        double worst = 0.0;
        for (int i = 0; i < 400; i++) {
            v.step(0.0, 0.0, 0.0, 0.0, 0.0, 0.05);
            worst = Math.max(worst, v.getCollapsedLevelIn());
        }
        Check.lessThan(60.0001, worst, "level never passes the flange with no well and a 1500 kg/s feed");
        Check.greaterThan(1400.0, v.getWellSpillKgPerS(), "the feed spills (kg/s)");
        Check.lessThan(1700.0, v.getWellSpillKgPerS(), "and about what the feed brings (a little more: the cold feed flashes)");
    }

    public static void test06_aSealedWellFillsToItsTopThenSpillsOverTheEdge() {
        PressureVessel v = cold();
        v.setWell(well());
        v.setCollapsedLevelIn(60.0);
        v.setWellSpilling(true);
        v.setFeedwaterFlowKgPerS(1500.0);
        double top = 60.0 + 2.5 / PressureVessel.METRES_PER_INCH;
        for (int i = 0; i < 20000; i++) {
            v.step(0.0, 0.0, 0.0, 0.0, 0.0, 0.05);
        }
        Check.absolute(top, v.getCollapsedLevelIn(), 0.05, "the well fills to the top and no further");
        Check.greaterThan(1400.0, v.getWellSpillKgPerS(), "water spills over the top");
    }

    public static void test07_noSpillWhileTheHeadIsOn() {
        PressureVessel v = cold();
        v.setWell(PressureVessel.Well.none());
        v.setCollapsedLevelIn(80.0);
        v.setWellSpilling(false);
        double before = v.getLiquidMassKg();
        for (int i = 0; i < 40; i++) {
            v.step(0.0, 0.0, 0.0, 0.0, 0.0, 0.05);
        }
        Check.greaterThan(80.0, v.getCollapsedLevelIn() + 1.0e-6, "the level above the lip stays where it is with the head on");
        Check.exactly(0.0, v.getWellSpillKgPerS(), "nothing spills with the head on");
        Check.relative(before, v.getLiquidMassKg(), 1.0e-9, "the inventory is unchanged");
    }

    public static void test08_closingTheHeadOrDroppingTheWellNeverMovesWater() {
        PressureVessel v = cold();
        v.setWell(well());
        v.setCollapsedLevelIn(60.0 + 1.5 / PressureVessel.METRES_PER_INCH);
        double mass = v.getLiquidMassKg();
        double level = v.getCollapsedLevelIn();
        v.setWellSpilling(true);
        v.setWellSpilling(false);
        Check.exactly(mass, v.getLiquidMassKg(), "toggling the head leaves the inventory alone");
        Check.exactly(level, v.getCollapsedLevelIn(), "and the level");
        v.setWell(null);
        Check.exactly(mass, v.getLiquidMassKg(), "removing the well leaves the inventory alone");
        Check.greaterThan(level, v.getCollapsedLevelIn(), "and the level reads taller over the single area, as before");
    }
    public static void test09_aDrainTakesWellWaterDownToItsSillAndNeverBelowTheFlange() {
        PressureVessel v = cold();
        v.setWell(well());
        v.setCollapsedLevelIn(60.0 + 1.5 / PressureVessel.METRES_PER_INCH);
        double rho = v.getLiquidMassKg() / (v.volumeAtFlangeM3() + v.getWellWaterM3());
        double before = v.getWellWaterM3();
        double massBefore = v.getLiquidMassKg();
        // 4 m3 annulus + 80 m3 + 40 m3 (half of the second layer) = 124 m3 over the flange
        Check.absolute(124.0, before, 1.0e-6, "the water over the flange");
        // asking for ten tonnes takes ten tonnes
        double taken = v.drainWellWaterKg(10000.0, 0.0);
        Check.absolute(10000.0, taken, 1.0e-9, "a small request is met");
        Check.absolute(massBefore - 10000.0, v.getLiquidMassKg(), 1.0e-6, "the inventory fell by what was taken");
        Check.absolute(before - 10000.0 / rho, v.getWellWaterM3(), 1.0e-6, "and so did the water over the flange");
        // a sill at 84 m3 (annulus plus the first layer) stops it there
        taken = v.drainWellWaterKg(1.0e9, 84.0);
        Check.absolute(84.0, v.getWellWaterM3(), 1.0e-6, "the level stops at the sill");
        Check.exactly(0.0, v.drainWellWaterKg(1.0e9, 84.0), "nothing more leaves at the sill");
        // the whole well may go, and then the flange level is where it stops: the vessel below is not the well's to empty
        v.drainWellWaterKg(1.0e9, 0.0);
        Check.absolute(0.0, v.getWellWaterM3(), 1.0e-9, "emptied to the flange");
        Check.absolute(60.0, v.getCollapsedLevelIn(), 1.0e-6, "the vessel is left full to the lip");
        Check.exactly(0.0, v.drainWellWaterKg(1.0e9, 0.0), "a drain at the flange takes nothing from the vessel body");
        Check.exactly(0.0, v.drainWellWaterKg(-5.0, 0.0), "a negative request takes nothing");
        Check.exactly(0.0, v.drainWellWaterKg(Double.NaN, 0.0), "NaN takes nothing");
        // level and inventory stay one thing after a drain
        v.setCollapsedLevelIn(60.0 + 2.0 / PressureVessel.METRES_PER_INCH);
        v.drainWellWaterKg(30000.0, 0.0);
        PressureVessel same = cold();
        same.setWell(well());
        same.restoreLiquidMassKg(v.getLiquidMassKg());
        Check.absolute(same.getCollapsedLevelIn(), v.getCollapsedLevelIn(), 1.0e-9, "the level is the one the inventory maps to");
    }
}
