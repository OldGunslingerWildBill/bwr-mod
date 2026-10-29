package dev.bwr.core.turbine;

import dev.bwr.core.Check;

/** Lube oil and bearings, the rotor bow and the turning gear, and the generator winding (TURBINE-AUX.md). */
public final class TurbineAuxTest {
    private TurbineAuxTest() {}

    private static final double DT = 0.05;

    /** Bearing temperature after t seconds rolling at rpm with a constant oil flow, starting at the given temperature; also the damage. */
    private static double[] roll(double startC, double rpm, double flow, double oilC, double seconds) {
        double b = startC, damage = 0;
        for (int i = 0; i < (int) Math.round(seconds / DT); i++) {
            b = TurbineAux.bearingStepC(b, TurbineAux.bearingTargetC(TurbineAux.film(flow, rpm), oilC, rpm), DT);
            damage += TurbineAux.bearingDamage(b, DT);
        }
        return new double[] {b, damage};
    }

    public static void test01_theShaftPumpMakesOilOnlyWithSpeed() {
        Check.exactly(0.0, TurbineAux.shaftPumpFlow(0), "no oil at standstill");
        Check.isTrue(TurbineAux.shaftPumpFlow(300) < TurbineAux.shaftPumpFlow(900), "more speed, more oil");
        Check.exactly(1.0, TurbineAux.shaftPumpFlow(0.9 * ShaftDynamics.RATED_RPM), "full delivery at 90 % speed");
        Check.exactly(1.0, TurbineAux.shaftPumpFlow(1500), "and no more above it");
        Check.isTrue(TurbineAux.shaftPumpFlow(450) < 0.12, "a coasting shaft at 30 % speed gives a trickle: %.3f", TurbineAux.shaftPumpFlow(450));
        Check.note("shaft pump: 30 %% speed %.3f, 60 %% speed %.3f, 90 %% speed %.3f of its full flow",
                TurbineAux.shaftPumpFlow(450), TurbineAux.shaftPumpFlow(900), TurbineAux.shaftPumpFlow(1350));
    }

    public static void test02_startupNeedsTheAuxiliaryPumpUntilNearSpeed() {
        for (double rpm : new double[] {150, 450, 900}) {
            double shaftOnly = TurbineAux.shaftPumpFlow(rpm);
            Check.isTrue(TurbineAux.film(shaftOnly, rpm) < 0.9, "at %.0f rpm the shaft pump alone leaves a poor film: %.2f", rpm, TurbineAux.film(shaftOnly, rpm));
            Check.exactly(1.0, TurbineAux.film(shaftOnly + TurbineAux.AC_PUMP_FLOW, rpm), "with the AC pump the film holds at " + rpm + " rpm");
        }
        Check.exactly(1.0, TurbineAux.film(TurbineAux.shaftPumpFlow(1350), 1350), "near rated speed the shaft pump carries the load alone");
        Check.isTrue(TurbineAux.film(TurbineAux.AC_PUMP_FLOW, 1500) < 0.75, "the AC pump alone cannot carry a shaft at full speed");
        Check.isTrue(TurbineAux.film(TurbineAux.DC_PUMP_FLOW, 100) > 0.85, "the emergency pump holds the film on a coasting shaft");
        Check.isTrue(TurbineAux.film(TurbineAux.DC_PUMP_FLOW, 1500) < 0.5, "but not at speed");
    }

    public static void test03_oilPressureIsTheSumOfTheRunningPumps() {
        Check.exactly(0.0, TurbineAux.oilPsig(0), "no pumps, no pressure");
        double ac = TurbineAux.oilPsig(TurbineAux.AC_PUMP_FLOW), dc = TurbineAux.oilPsig(TurbineAux.DC_PUMP_FLOW);
        Check.relative(ac + dc, TurbineAux.oilPsig(TurbineAux.AC_PUMP_FLOW + TurbineAux.DC_PUMP_FLOW), 1e-9, "two pumps add");
        Check.isTrue(TurbineAux.oilPsig(10) <= TurbineAux.OIL_PSIG_NOMINAL * 1.2 + 1e-9, "the relief valve caps the header");
        Check.exactly(0.0, TurbineAux.suction(0), "an empty tank gives nothing");
        Check.exactly(1.0, TurbineAux.suction(2000), "a tank above 5 % delivers fully");
    }

    public static void test04_noOilWhileRollingWrecksTheBearings() {
        double[] noOil = roll(75, 1500, 0, 50, 120);
        Check.greaterThan(TurbineAux.BEARING_DAMAGE_C, noOil[0], "bearing temperature after 2 min at speed without oil");
        Check.greaterThan(0.2, noOil[1], "damage after 2 min at speed without oil");
        double[] oiled = roll(75, 1500, 1.5, 50, 600);
        Check.lessThan(100.0, oiled[0], "an oiled bearing at rated speed is under 100 C: " + oiled[0]);
        Check.exactly(0.0, oiled[1], "and takes no damage");
        double[] slow = roll(30, 15, 0, 50, 600);
        Check.exactly(0.0, slow[1], "a shaft barely turning needs no film to survive");
        Check.note("2 min rolling at 1500 rpm with no oil: bearing %.0f C, damage %.2f; with oil 10 min: %.0f C", noOil[0], noOil[1], oiled[0]);
    }

    public static void test05_hotOilHeatsTheBearingsMoreSlowlyThanNoOil() {
        double[] hot = roll(75, 1500, 1.5, 85, 120);
        double[] none = roll(75, 1500, 0, 50, 120);
        Check.lessThan(none[0], hot[0], "after 2 min hot oil leaves the bearing cooler than no oil");
        Check.greaterThan(TurbineAux.bearingTargetC(1, 50, 1500), TurbineAux.bearingTargetC(1, 85, 1500), "but hotter than cool oil");
        double[] hotLong = roll(75, 1500, 1.5, 90, 900);
        Check.greaterThan(TurbineAux.BEARING_DAMAGE_C, hotLong[0], "very hot oil eventually damages them: " + hotLong[0]);
        Check.note("oil at 85 C: bearing %.0f C after 2 min; no oil: %.0f C", hot[0], none[0]);
    }

    public static void test06_theOilCoolerNeedsWaterAndWarmOilNeedsTheCooler() {
        double with = TurbineAux.coolerKw(50, TurbineAux.OIL_COOLER_UA_KW_PER_K, 13, TurbineAux.OIL_COOLER_WATER_KG_PER_S);
        Check.exactly(0.0, TurbineAux.coolerKw(50, TurbineAux.OIL_COOLER_UA_KW_PER_K, 13, 0), "no water, no cooling");
        Check.greaterThan(2000, with, "kW at 50 C oil with cold water");
        double cooled = 45, dry = 45, l = TurbineAux.OIL_TANK_L;
        for (int i = 0; i < 20 * 3600; i++) {
            double heat = TurbineAux.oilHeatKw(1500, 1);
            cooled = TurbineAux.oilStepC(cooled, l, heat, TurbineAux.coolerKw(cooled, TurbineAux.OIL_COOLER_UA_KW_PER_K, 13, TurbineAux.OIL_COOLER_WATER_KG_PER_S), 0.05);
            dry = TurbineAux.oilStepC(dry, l, heat, 0, 0.05);
        }
        Check.inRange(40, 60, cooled, "a cooled oil system settles in the fifties");
        Check.greaterThan(80, dry, "oil with no cooling water after an hour");
        Check.note("after 1 h at rated speed: cooled oil %.1f C, uncooled oil %.1f C", cooled, dry);
    }

    public static void test07_oilLeaksAndLeaksMoreWhenHot() {
        double cool = TurbineAux.oilLossLitres(25, 45, 3600), hot = TurbineAux.oilLossLitres(25, 80, 3600);
        Check.inRange(2500, 4000, cool, "litres lost in an hour at full pressure and cool oil");
        Check.greaterThan(cool * 3, hot, "hot oil is consumed faster");
        Check.exactly(0.0, TurbineAux.oilLossLitres(0, 45, 3600), "a tank nobody pumps does not leak");
    }

    public static void test08_aHotRotorAtStandstillBowsAndTheGearStraightensIt() {
        double bow = 0;
        for (int i = 0; i < 20 * 1200; i++) bow = TurbineAux.bowStep(bow, 285, 0, false, DT);
        Check.greaterThan(0.9, bow, "20 min at standstill at 285 C");
        double cold = 0;
        for (int i = 0; i < 20 * 3600; i++) cold = TurbineAux.bowStep(cold, 60, 0, false, DT);
        Check.exactly(0.0, cold, "a cold rotor does not bow");
        double rolling = 0;
        for (int i = 0; i < 20 * 3600; i++) rolling = TurbineAux.bowStep(rolling, 285, 3, true, DT);
        Check.exactly(0.0, rolling, "a rotor on the gear does not bow");
        double recovered = bow;
        for (int i = 0; i < 20 * 600; i++) recovered = TurbineAux.bowStep(recovered, 200, 3, true, DT);
        Check.lessThan(bow - 0.2, recovered, "10 min on the gear takes a third off the bow");
        for (int i = 0; i < 20 * 3000; i++) recovered = TurbineAux.bowStep(recovered, 200, 3, true, DT);
        Check.exactly(0.0, recovered, "an hour on the gear straightens it");
        double stays = bow;
        for (int i = 0; i < 20 * 600; i++) stays = TurbineAux.bowStep(stays, 100, 0, false, DT);
        Check.exactly(bow, stays, "a bow does not heal at rest");
        Check.note("20 min hot at rest: bow %.2f", bow);
    }

    public static void test09_theGearOnlyActsBelowItsOverrunSpeed() {
        Check.exactly(TurbineAux.GEAR_RPM, TurbineAux.gearSpeed(0), "from rest it rolls the shaft at its speed");
        Check.exactly(TurbineAux.GEAR_RPM, TurbineAux.gearSpeed(1), "a nearly stopped shaft is taken to its speed");
        Check.exactly(TurbineAux.GEAR_RPM, TurbineAux.gearSpeed(TurbineAux.GEAR_MAX_RPM), "at its overrun speed");
        Check.exactly(400.0, TurbineAux.gearSpeed(400), "above it, it does nothing");
    }

    public static void test10_aBowedRotorShakesMostThroughTheCriticalSpeedBand() {
        double clean = TurbineAux.vibrationMmPerS(0, 525), bowed = TurbineAux.vibrationMmPerS(1, 525);
        Check.lessThan(TurbineAux.VIBRATION_DAMAGE_MM_S, clean, "a straight rotor in the band is quiet");
        Check.greaterThan(TurbineAux.VIBRATION_DAMAGE_MM_S * 1.5, bowed, "a bowed rotor in the band");
        Check.lessThan(TurbineAux.VIBRATION_DAMAGE_MM_S, TurbineAux.vibrationMmPerS(1, 1500), "a bowed rotor at rated speed is past the worst of it");
        Check.exactly(0.0, TurbineAux.vibrationMmPerS(1, 0), "a stopped rotor does not vibrate");
        double damageBowed = 0, damageClean = 0;
        for (double rpm = 150; rpm < 900; rpm += 1) {
            damageBowed += TurbineAux.vibrationDamage(TurbineAux.vibrationMmPerS(1, rpm), 0.5);
            damageClean += TurbineAux.vibrationDamage(TurbineAux.vibrationMmPerS(0, rpm), 0.5);
        }
        Check.greaterThan(0.05, damageBowed, "rolling a fully bowed rotor up through the band");
        Check.exactly(0.0, damageClean, "a straight one takes none");
        Check.note("vibration in the band: straight %.1f mm/s, bowed %.1f mm/s; a roll-up damages a bowed rotor by %.3f", clean, bowed, damageBowed);
    }

    public static void test11_aWindingHeldBelowItsLimitByFullCoolingAndNotWithout() {
        double w = 25, c = 25;
        for (int i = 0; i < 20 * 3600; i++) {
            double cooling = TurbineAux.coolingFraction(1, TurbineAux.H2_FULL_PSIG);
            double toCoolant = TurbineAux.windingToCoolantKw(w, c, cooling);
            w = TurbineAux.windingStepC(w, TurbineAux.windingLossKw(1, 1500), toCoolant, DT);
            c = TurbineAux.coolantStepC(c, toCoolant, TurbineAux.coolerKw(c, TurbineAux.GEN_COOLER_UA_KW_PER_K, 13, TurbineAux.GEN_COOLER_WATER_KG_PER_S), DT);
        }
        Check.inRange(80, 120.0, w, "winding at rated load with full cooling");
        double[] noPumps = windingAfter(1.0, 0, 20 * 600, true);
        Check.greaterThan(TurbineAux.WINDING_LIMIT_C, noPumps[0], "winding at rated load with the stator water stopped");
        Check.greaterThan(0, noPumps[1], "damage when the stator water is stopped at rated load");
        double[] reduced = windingAfter(0.7, 0, 20 * 1800, true);
        Check.lessThan(TurbineAux.WINDING_LIMIT_C, reduced[0], "the player cuts the load to 70 %: " + reduced[0]);
        double[] noWater = windingAfter(1.0, 1, 20 * 600, false);
        Check.greaterThan(noPumps[0] - 60, noWater[0], "no cooling water: the coolant soaks the heat");
        Check.note("rated load, 10 min: stator pumps stopped %.0f C (damage %.2f); at 70 %% load 30 min %.0f C", noPumps[0], noPumps[1], reduced[0]);
    }

    /** Winding temperature and damage after the given ticks from a settled, fully cooled state. */
    private static double[] windingAfter(double load, double statorFlow, int ticks, boolean water) {
        double w = 102, c = 46, damage = 0;
        for (int i = 0; i < ticks; i++) {
            double cooling = TurbineAux.coolingFraction(statorFlow, TurbineAux.H2_FULL_PSIG);
            double toCoolant = TurbineAux.windingToCoolantKw(w, c, cooling);
            w = TurbineAux.windingStepC(w, TurbineAux.windingLossKw(load, 1500), toCoolant, DT);
            c = TurbineAux.coolantStepC(c, toCoolant, water ? TurbineAux.coolerKw(c, TurbineAux.GEN_COOLER_UA_KW_PER_K, 13, TurbineAux.GEN_COOLER_WATER_KG_PER_S) : 0, DT);
            damage += TurbineAux.windingDamage(w, DT);
        }
        return new double[] {w, damage};
    }

    public static void test12_hydrogenIsPressureAndFewerCylindersMeanWorseCooling() {
        Check.exactly(TurbineAux.H2_FULL_PSIG, TurbineAux.h2Psig(TurbineAux.H2_FULL_KG), "a full charge is the design pressure");
        Check.exactly(0.0, TurbineAux.h2Psig(0), "no gas, no pressure");
        Check.greaterThan(TurbineAux.coolingFraction(1, 15), TurbineAux.coolingFraction(1, 30), "half the pressure cools less");
        Check.greaterThan(TurbineAux.coolingFraction(1, 0), TurbineAux.coolingFraction(1, 30), "air in the casing cools less than hydrogen");
        Check.exactly(1.0, TurbineAux.coolingFraction(1, 30), "full cooling is 1");
        Check.isTrue(TurbineAux.coolingFraction(0, 30) < 0.6 && TurbineAux.coolingFraction(0, 30) > 0.5, "hydrogen alone is 55 %");
    }

    /** Seconds a litres-sized charge lasts at the given pressure and oil temperature through a filter leaving {@code factor} of the loss. */
    private static double lastsS(double litres, double psig, double oilC, double factor) {
        double left = litres, t = 0;
        while (left > 0 && t < 4e6) {
            left -= TurbineAux.oilLossLitres(psig, oilC, 1, factor);
            t += 1;
        }
        return t;
    }

    public static void test13_aCleanCartridgeMakesADrumLastTenTimesAsLong() {
        Check.exactly(1.0, TurbineAux.filterFactor(false, 0), "no cartridge, no filtering");
        Check.exactly(TurbineAux.OIL_FILTER_FACTOR, TurbineAux.filterFactor(true, 0), "a clean cartridge cuts the loss to its factor");
        Check.exactly(TurbineAux.OIL_FILTER_FACTOR, TurbineAux.filterFactor(true, 0.99), "and keeps it until it is full");
        Check.exactly(1.0, TurbineAux.filterFactor(true, 1), "a full cartridge is bypassed: today's loss again");
        Check.exactly(TurbineAux.oilLossLitres(25, 60, 7), TurbineAux.oilLossLitres(25, 60, 7, 1.0), "factor 1 is the old law, bit for bit");
        double bare = lastsS(1000, 25, 50, 1), filtered = lastsS(1000, 25, 50, TurbineAux.filterFactor(true, 0));
        Check.inRange(17 * 60, 19 * 60, bare, "an unfiltered drum at rated pressure and 50 C lasts about 18 minutes (s): " + bare);
        Check.relative(10.0, filtered / bare, 0.01, "a filtered drum lasts ten times as long");
        Check.inRange(3.0 * 3600, 3.2 * 3600, filtered, "about three hours (s): " + filtered);
        Check.exactly(0.0, TurbineAux.oilLossLitres(0, 50, 3600, 0.1), "a tank nobody pumps still loses nothing");
        double hotBare = TurbineAux.oilLossLitres(25, 80, 3600), hotFiltered = TurbineAux.oilLossLitres(25, 80, 3600, 0.1);
        Check.relative(10.0, hotBare / hotFiltered, 1e-9, "the tenth holds when the oil is hot");
        Check.note("1,000 L drum at 25 psig and 50 C: %.1f min unfiltered, %.2f h through a clean cartridge; a 20,000 L tank %.1f h and %.1f h",
                bare / 60, filtered / 3600, bare * 20 / 3600, filtered * 20 / 3600);
    }

    public static void test14_theCartridgeLoadsUpRaisesItsPressureDropAndBypasses() {
        Check.exactly(TurbineAux.FILTER_DP_CLEAN_PSI, TurbineAux.filterDpPsi(0), "a clean cartridge drops a few psi");
        Check.exactly(TurbineAux.FILTER_DP_FULL_PSI, TurbineAux.filterDpPsi(1), "a full one drops what the bypass valve is set to");
        double last = -1;
        for (int i = 0; i <= 10; i++) {
            double dp = TurbineAux.filterDpPsi(i / 10.0);
            Check.greaterThan(last, dp + 1e-12, "the pressure drop only rises with the load, at " + i / 10.0);
            last = dp;
        }
        Check.isTrue(TurbineAux.filterDpPsi(0.5) < TurbineAux.FILTER_DP_CHANGE_PSI, "half full is not yet time to change it");
        Check.isTrue(TurbineAux.filterDpPsi(0.8) > TurbineAux.FILTER_DP_CHANGE_PSI, "four fifths full is");
        Check.isFalse(TurbineAux.filterBypassOpen(0.999), "the bypass stays shut until the cartridge is full");
        Check.isTrue(TurbineAux.filterBypassOpen(1), "and opens when it is");
        // Its life: six hours at rated pressure and 50 C; hot oil and a header at pressure fill it faster, an idle header not at all.
        double load = 0;
        for (int i = 0; i < 21_600; i++) load = TurbineAux.filterLoadStep(load, 25, 50, 1);
        Check.relative(1.0, load, 1e-9, "full after six hours at rated pressure and 50 C");
        double hot = 0;
        for (int i = 0; i < 10_800; i++) hot = TurbineAux.filterLoadStep(hot, 25, 65, 1);
        Check.relative(1.0, hot, 1e-9, "oil at 65 C fills it in half the time");
        Check.exactly(0.0, TurbineAux.filterLoadStep(0, 0, 80, 3600), "with no pressure on the header nothing flows through it");
        Check.exactly(1.0, TurbineAux.filterLoadStep(0.99999, 30, 100, 3600), "and the load stops at full");
        Check.exactly(0.3, TurbineAux.filterLoadStep(0.3, 25, 50, 0), "no time, no change");
        Check.relative(21_600, TurbineAux.filterSecondsLeft(0, 25, 50), 1e-9, "a new cartridge has its six hours");
        Check.relative(10_800, TurbineAux.filterSecondsLeft(0.5, 25, 50), 1e-9, "half used, half left");
        Check.relative(5_400, TurbineAux.filterSecondsLeft(0.5, 25, 65), 1e-9, "and half that with oil 15 K hotter");
        Check.isTrue(Double.isInfinite(TurbineAux.filterSecondsLeft(0.5, 0, 50)), "nothing flows with no pressure");
    }

    public static void test15_aRunningConditionerAndACleanCartridgeKeepATankForAboutOneRefuellingCycle() {
        double bare = lastsS(TurbineAux.OIL_TANK_L, 25, 50, 1);
        double filter = lastsS(TurbineAux.OIL_TANK_L, 25, 50, TurbineAux.OIL_FILTER_FACTOR);
        double both = lastsS2(TurbineAux.OIL_TANK_L, 25, 50, TurbineAux.OIL_FILTER_FACTOR, TurbineAux.conditionerFactor(true));
        double conditionerOnly = lastsS2(TurbineAux.OIL_TANK_L, 25, 50, 1, TurbineAux.conditionerFactor(true));
        Check.inRange(6.0 * 3600, 6.4 * 3600, bare, "a full 20,000 L tank unfiltered lasts about 6.2 h (s): " + bare);
        Check.inRange(61 * 3600, 63 * 3600, filter, "through a clean cartridge alone about 62 h (s): " + filter);
        Check.relative(TurbineAux.OIL_LIFE_CONDITIONED_S, both, 0.002, "with the conditioner running too it lasts the target, 8.5 days: " + both / 3600 + " h");
        Check.inRange(7 * 24 * 3600, 10 * 24 * 3600, both, "which is inside one refuelling cycle of 7 to 10 days");
        Check.relative(204 * 3600, both, 0.002, "204 hours of running");
        Check.inRange(19.5 * 3600, 21.5 * 3600, conditionerOnly, "the conditioner alone (bypassed filter) about 20 h (s): " + conditionerOnly / 3600);
        Check.relative(TurbineAux.CONDITIONER_FACTOR * TurbineAux.OIL_FILTER_FACTOR, 1.0 / (both / bare), 0.002, "the two factors multiply: the filter protects the bearings, the conditioner the oil");
        Check.inRange(0.29, 0.31, TurbineAux.CONDITIONER_FACTOR, "the conditioner leaves about 30 % of the degradation: " + TurbineAux.CONDITIONER_FACTOR);
        double hot = TurbineAux.oilLossLitres(25, 80, 3600, 0.1, TurbineAux.CONDITIONER_FACTOR), hotBare = TurbineAux.oilLossLitres(25, 80, 3600);
        Check.relative(1.0 / (0.1 * TurbineAux.CONDITIONER_FACTOR), hotBare / hot, 1e-9, "the product holds with hot oil");
        Check.exactly(0.0, TurbineAux.oilLossLitres(0, 50, 3600, 0.1, TurbineAux.CONDITIONER_FACTOR), "a tank nobody pumps still loses nothing");
        Check.note("20,000 L at 25 psig and 50 C: %.1f h bare, %.1f h clean cartridge, %.1f h conditioner alone, %.1f h (%.2f days) both",
                bare / 3600, filter / 3600, conditionerOnly / 3600, both / 3600, both / 86400);
    }

    public static void test16_withoutPowerOrFlowTheOilIsBackOnTheFilterOnlyRate() {
        Check.exactly(1.0, TurbineAux.conditionerFactor(false), "a stopped conditioner changes nothing");
        Check.exactly(TurbineAux.CONDITIONER_FACTOR, TurbineAux.conditionerFactor(true), "a running one leaves its factor");
        Check.exactly(TurbineAux.oilLossLitres(25, 60, 7, 0.1), TurbineAux.oilLossLitres(25, 60, 7, 0.1, TurbineAux.conditionerFactor(false)),
                "the filter-only loss is the old law's, bit for bit, when the conditioner is not running");
        Check.exactly(TurbineAux.oilLossLitres(25, 60, 7), TurbineAux.oilLossLitres(25, 60, 7, 1.0, 1.0), "and with neither it is the unfiltered law");
        Check.isTrue(TurbineAux.CONDITIONER_W > 5_000 && TurbineAux.CONDITIONER_W < 30_000, "a side-stream skid draws a few kW to tens of kW");
    }

    private static double lastsS2(double litres, double psig, double oilC, double filterFactor, double conditionerFactor) {
        double left = litres, t = 0;
        while (left > 0 && t < 4e6) {
            left -= TurbineAux.oilLossLitres(psig, oilC, 1, filterFactor, conditionerFactor);
            t += 1;
        }
        return t;
    }
}
