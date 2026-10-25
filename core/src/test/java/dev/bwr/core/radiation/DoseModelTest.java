package dev.bwr.core.radiation;

import dev.bwr.core.Check;
import dev.bwr.core.PhysicalConstants;

/**
 * The conversion from the plant's hazard numbers to mSv/h: the anchors the documentation quotes, and the shapes
 * (inverse square, shielding, monotonic click rate) the instruments rely on.
 */
public final class DoseModelTest {

    private DoseModelTest() {
    }

    public static void test01_inventoryScaleIsGroundedInTheModelsSourceTerms() {
        // 0.5 x decay heat a day after shutdown x rated power: single digit megawatts of gamma.
        Check.inRange(8.0e6, 11.0e6, DoseModel.INVENTORY_GAMMA_WATTS, "gamma watts of a day-old whole inventory");
        Check.inRange(12.0, 13.0, DoseModel.CONTAINMENT_RADIUS_M, "equivalent radius of the containment free volume");
        // The bare figure is lethal in seconds; the structure factor brings it to about 1.5 Sv/h per 1e-3 of inventory.
        Check.inRange(1.0e5, 3.0e5, DoseModel.BARE_MSV_PER_H_PER_INVENTORY / 1000.0, "bare Sv/h per whole inventory");
        Check.inRange(1.0e6, 2.5e6, DoseModel.MSV_PER_H_PER_INVENTORY, "mSv/h per whole inventory");
        Check.note("whole inventory = %.3g mSv/h; gamma %.3g W; R %.2f m", DoseModel.MSV_PER_H_PER_INVENTORY,
                DoseModel.INVENTORY_GAMMA_WATTS, DoseModel.CONTAINMENT_RADIUS_M);
    }

    public static void test02_theEffectThresholdsLandWhereRadiologyPutsThem() {
        double hunger = DoseModel.doseRateFromHazard(1.0e-4);
        double weakness = DoseModel.doseRateFromHazard(1.0e-3);
        double wither = DoseModel.doseRateFromHazard(1.0e-2);
        Check.inRange(100.0, 300.0, hunger, "hunger threshold, mSv/h");
        Check.inRange(1000.0, 3000.0, weakness, "weakness threshold, mSv/h");
        Check.inRange(10_000.0, 30_000.0, wither, "wither threshold, mSv/h");
        Check.exactly(0.0, DoseModel.doseRateFromHazard(0.0), "no hazard, no dose");
        Check.exactly(0.0, DoseModel.doseRateFromHazard(Double.NaN), "NaN hazard reads zero");
        Check.exactly(0.0, DoseModel.doseRateFromHazard(-1.0), "negative hazard reads zero");
        Check.relative(2.0 * hunger, DoseModel.doseRateFromHazard(2.0e-4), 1e-12, "linear in the hazard");
    }

    public static void test03_aShieldedCoreReadsLowOutsideAndAnUncoveredOneReadsHigh() {
        double rated = DoseModel.coreGammaWatts(1.0 * PhysicalConstants.RATED_THERMAL_MW, 0.066 * PhysicalConstants.RATED_THERMAL_MW);
        double covered = DoseModel.pointSourceMsvPerH(rated, 10.0, DoseModel.lateralTransmission(1.0, false));
        double uncovered = DoseModel.pointSourceMsvPerH(rated, 10.0, DoseModel.lateralTransmission(0.0, false));
        Check.inRange(0.02, 1.0, covered, "rated power, closed head, 10 m, mSv/h");
        Check.inRange(1000.0, 1400.0, uncovered / covered, "losing the water in front of the fuel");
        double failed = DoseModel.pointSourceMsvPerH(rated, 10.0, DoseModel.lateralTransmission(0.0, true));
        Check.greaterThan(uncovered * 500.0, failed, "debris outside the vessel is far hotter than an uncovered core inside it");
        Check.note("rated 10 m: covered %.3g, uncovered %.3g, lower head failed %.3g mSv/h", covered, uncovered, failed);
    }

    public static void test04_shutDownCoreIsDecayHeatGamma() {
        double scrammed = DoseModel.coreGammaWatts(0.0, 0.0655 * PhysicalConstants.RATED_THERMAL_MW);
        double rated = DoseModel.coreGammaWatts(PhysicalConstants.RATED_THERMAL_MW, 0.0);
        Check.relative(0.5 * 0.0655 * PhysicalConstants.RATED_THERMAL_MW * 1e6, scrammed, 1e-12, "half of decay heat is gamma");
        Check.relative(0.07 * PhysicalConstants.RATED_THERMAL_MW * 1e6, rated, 1e-12, "seven percent of fission power is gamma");
        Check.greaterThan(scrammed, rated, "rated power is louder than a scrammed core");
        Check.exactly(0.0, DoseModel.coreGammaWatts(0.0, 0.0), "a cold core emits nothing");
        Check.exactly(0.0, DoseModel.coreGammaWatts(1.0e-12, 1.0e-12), "numerical residue of a cold core emits nothing");
        Check.exactly(0.0, DoseModel.coreGammaWatts(Double.NaN, Double.NaN), "NaN emits nothing");
    }

    public static void test05_openHeadLooksThroughTheWaterOnly() {
        double w = DoseModel.coreGammaWatts(0.0, 0.01 * PhysicalConstants.RATED_THERMAL_MW);
        double beside = DoseModel.pointSourceMsvPerH(w, 8.0, DoseModel.lateralTransmission(1.0, false));
        double dry = DoseModel.pointSourceMsvPerH(w, 8.0, DoseModel.openHeadTransmission(0.0));
        double shallow = DoseModel.pointSourceMsvPerH(w, 8.0, DoseModel.openHeadTransmission(1.0));
        double deep = DoseModel.pointSourceMsvPerH(w, 8.0, DoseModel.openHeadTransmission(5.0));
        Check.greaterThan(beside * 1.0e6, dry, "an open head over dry fuel dwarfs the shielded reading");
        Check.greaterThan(shallow * 100.0, dry, "a metre of water takes the reading down by a thousandfold");
        Check.lessThan(shallow, deep, "five metres of water is far better than one");
        Check.lessThan(0.01, deep, "a flooded refuelling cavity reads low, mSv/h");
        Check.note("open head, 8 m: dry %.3g, 1 m water %.3g, 5 m water %.3g mSv/h; closed %.3g", dry, shallow, deep, beside);
    }

    public static void test06_pointSourceFallsOffAsTheInverseSquare() {
        double near = DoseModel.pointSourceMsvPerH(1.0e6, 5.0, 1.0);
        double far = DoseModel.pointSourceMsvPerH(1.0e6, 10.0, 1.0);
        Check.inRange(4.0, 4.4, near / far, "doubling the distance cuts the dose to a quarter (a little more with air)");
        Check.exactly(0.0, DoseModel.pointSourceMsvPerH(0.0, 3.0, 1.0), "no source, no dose");
        Check.exactly(0.0, DoseModel.pointSourceMsvPerH(1.0e6, 3.0, 0.0), "total shielding, no dose");
        Check.greaterThan(0.0, DoseModel.pointSourceMsvPerH(1.0e12, DoseModel.MAX_RANGE_M, 1.0), "a strong source still reads at the edge of its range");
        Check.exactly(0.0, DoseModel.pointSourceMsvPerH(1.0e12, DoseModel.MAX_RANGE_M + 0.01, 1.0), "and reads exactly zero past it");
        Check.finite(DoseModel.pointSourceMsvPerH(1.0e6, 0.0, 1.0), "standing in the source does not blow up");
        Check.exactly(DoseModel.pointSourceMsvPerH(1.0e6, 0.5, 1.0), DoseModel.pointSourceMsvPerH(1.0e6, 0.0, 1.0),
                "nearer than half a metre counts as half a metre");
    }

    public static void test07_geigerRateIsMonotonicCompressedAndCapped() {
        double previous = -1.0;
        for (double mSv = 0.0; mSv < 1.0e7; mSv = mSv == 0.0 ? 1.0e-6 : mSv * 3.0) {
            double cps = DoseModel.geigerClicksPerSecond(mSv);
            Check.isTrue(cps >= previous, "click rate never falls as the dose rate rises (%.3g mSv/h: %.3g)", mSv, cps);
            Check.inRange(DoseModel.GEIGER_IDLE_CPS, DoseModel.GEIGER_MAX_CPS, cps, "click rate stays in range");
            previous = cps;
        }
        Check.exactly(DoseModel.GEIGER_IDLE_CPS, DoseModel.geigerClicksPerSecond(0.0), "background idle rate");
        Check.inRange(2.5, 4.0, DoseModel.geigerClicksPerSecond(0.001), "1 uSv/h is a few clicks a second");
        Check.inRange(20.0, 30.0, DoseModel.geigerClicksPerSecond(1.0), "1 mSv/h is a busy counter");
        Check.exactly(DoseModel.GEIGER_MAX_CPS, DoseModel.geigerClicksPerSecond(1.0e5), "very high is capped");
        Check.exactly(DoseModel.GEIGER_IDLE_CPS, DoseModel.geigerClicksPerSecond(Double.NaN), "NaN reads as idle");
    }

    public static void test08_formatting() {
        Check.isTrue(DoseModel.formatRate(0.0).equals("0.00 µSv/h"), "zero: %s", DoseModel.formatRate(0.0));
        Check.isTrue(DoseModel.formatRate(0.0123).equals("12.3 µSv/h"), "microsieverts: %s", DoseModel.formatRate(0.0123));
        Check.isTrue(DoseModel.formatRate(2.5).equals("2.50 mSv/h"), "millisieverts: %s", DoseModel.formatRate(2.5));
        Check.isTrue(DoseModel.formatRate(16400.0).equals("16.4 Sv/h"), "sieverts: %s", DoseModel.formatRate(16400.0));
        Check.isTrue(DoseModel.formatDose(0.5).equals("500 µSv"), "dose: %s", DoseModel.formatDose(0.5));
        Check.isTrue(DoseModel.formatDose(Double.NaN).equals("0.00 µSv"), "NaN dose: %s", DoseModel.formatDose(Double.NaN));
    }

    // ---- water on the line between the source and the dose point (swimmers)

    public static void test09_aPointAboveTheSurfaceKeepsTheOldVerticalRule() {
        // surface at 7, source (a rack top) at 4: the old figure is the 3 m straight over the source, however far aside the point is
        Check.exactly(3.0, DoseModel.waterPathM(4.5, 4.0, 8.5, 7.0, Double.NEGATIVE_INFINITY), "directly over");
        Check.exactly(3.0, DoseModel.waterPathM(9.0, 4.0, 8.5, 7.0, Double.NEGATIVE_INFINITY), "far aside, still the vertical water");
        Check.exactly(3.0, DoseModel.waterPathM(9.0, 4.0, 7.0, 7.0, Double.NEGATIVE_INFINITY), "a point exactly at the surface is above it");
        Check.exactly(0.0, DoseModel.waterPathM(2.0, 8.0, 9.0, 7.0, Double.NEGATIVE_INFINITY), "a source out of the water has none over it");
        // the open head: the source is the middle of the fuel, the water counts from the top of the fuel
        Check.relative(4.4, DoseModel.waterPathM(6.0, 2.0, 9.0, 8.4, 4.0), 1e-12, "open head, 4.4 m over the fuel");
    }

    public static void test10_aPointInTheWaterGetsOnlyTheWaterBetweenItAndTheSource() {
        // straight over the source: the depth from the point down to the source, not the water above the point
        Check.relative(1.0, DoseModel.waterPathM(1.0, 4.0, 5.0, 7.0, Double.NEGATIVE_INFINITY), 1e-12, "1 m over the rack top, 2 m of water above the diver");
        Check.relative(2.0, DoseModel.waterPathM(2.0, 4.0, 6.0, 7.0, Double.NEGATIVE_INFINITY), 1e-12, "2 m over");
        // beside it and level with it: the whole line is water
        Check.relative(3.0, DoseModel.waterPathM(3.0, 4.0, 4.0, 7.0, Double.NEGATIVE_INFINITY), 1e-12, "level, 3 m away");
        Check.relative(5.0, DoseModel.waterPathM(5.0, 4.0, 3.0, 7.0, Double.NEGATIVE_INFINITY), 1e-12, "below the source's level");
        // slanted: the whole line is under the surface, so the whole distance
        Check.relative(5.0, DoseModel.waterPathM(5.0, 4.0, 7.0 - 1e-9 - 2.0, 7.0, Double.NEGATIVE_INFINITY), 1e-12, "a slanted line is all water");
        // less than the old figure would give a point above: swimming near the fuel is worse than the deck over it
        double deck = DoseModel.waterPathM(4.5, 4.0, 8.5, 7.0, Double.NEGATIVE_INFINITY);
        double swimmer = DoseModel.waterPathM(1.0, 4.0, 5.0, 7.0, Double.NEGATIVE_INFINITY);
        Check.lessThan(deck, swimmer, "a swimmer 1 m over the fuel has 1 m of water, the deck has 3");
        // more water means less dose, along the line
        double near = DoseModel.pointSourceMsvPerH(1.0e5, 1.0, DoseModel.waterTransmission(1.0));
        double far = DoseModel.pointSourceMsvPerH(1.0e5, 2.0, DoseModel.waterTransmission(2.0));
        Check.greaterThan(far * 100.0, near, "a metre of water is a factor of about a thousand");
    }

    public static void test11_theOpenHeadCountsOnlyTheWaterAboveTheFuelOnTheLineToItsMiddle() {
        // fuel 2 m high, top at 4, middle at 3; surface 8.4; a diver 1 m over the top (y = 5), straight over the middle: 4 m from it
        Check.relative(1.0, DoseModel.waterPathM(2.0, 3.0, 5.0, 8.4, 4.0), 1e-12, "1 m of water above the fuel, the 1 m inside the fuel does not count");
        // beside the fuel's top, level with it: no water over the fuel on the line, the line is inside the fuel
        Check.exactly(0.0, DoseModel.waterPathM(2.0, 3.0, 3.0, 8.4, 4.0), "level with the middle of the fuel");
        // continuous at the top of the fuel and at the surface (straight over)
        Check.relative(4.4, DoseModel.waterPathM(5.4, 3.0, 8.399999, 8.4, 4.0), 1e-5, "just under the surface");
        Check.relative(4.4, DoseModel.waterPathM(5.4, 3.0, 8.4, 8.4, 4.0), 1e-9, "at the surface");
        Check.exactly(0.0, DoseModel.waterPathM(1.0, 3.0, 3.99, 8.4, 4.0), "below the top of the fuel there is no water over it");
        Check.exactly(0.0, DoseModel.waterPathM(Double.NaN, 3.0, 5.0, 8.4, 4.0), "NaN distance is no path");
    }

    public static void test12_hazardFromDoseRateInvertsTheConversion() {
        Check.relative(1.0e-3, DoseModel.hazardFromDoseRate(DoseModel.doseRateFromHazard(1.0e-3)), 1e-12, "round trip");
        Check.exactly(0.0, DoseModel.hazardFromDoseRate(0.0), "zero");
        Check.exactly(0.0, DoseModel.hazardFromDoseRate(Double.NaN), "NaN");
        Check.exactly(0.0, DoseModel.hazardFromDoseRate(-5.0), "negative");
    }
}
