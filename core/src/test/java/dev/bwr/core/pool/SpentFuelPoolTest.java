package dev.bwr.core.pool;

import dev.bwr.core.Check;
import dev.bwr.core.thermal.Saturation;

/**
 * The spent fuel pool's heat balance, boiling, level, makeup and the fuel in air ({@link SpentFuelPool}), each
 * against a number worked out here from the formulas, and the cooling loop ({@link SpentFuelPoolCooler}).
 */
public final class SpentFuelPoolTest {

    private SpentFuelPoolTest() {
    }

    private static final double CP = SpentFuelPool.CP_KJ_PER_KG_K * 1000.0;

    /** A 7 x 7 x 6 m pool filled to {@code fraction} at {@code celsius}. */
    private static SpentFuelPool pool(double fraction, double celsius) {
        SpentFuelPool p = new SpentFuelPool();
        p.configure(49, 6);
        p.restore(p.capacityKg() * fraction, celsius);
        return p;
    }

    private static SpentFuelPool.Slot[] slots(int n, double decayW, double topM) {
        SpentFuelPool.Slot[] s = new SpentFuelPool.Slot[n];
        for (int i = 0; i < n; i++) {
            s[i] = new SpentFuelPool.Slot(topM);
            s[i].occupied = true;
            s[i].decayW = decayW;
        }
        return s;
    }

    public static void test01_saturationPressureMatchesTheSteamTables() {
        // CRC / IAPWS: 25 C 3.169 kPa, 40 C 7.384 kPa, 60 C 19.946 kPa, 100 C 101.325 kPa
        Check.relative(3.169, SpentFuelPool.saturationPressureKpa(25.0), 0.01, "p_sat 25 C");
        Check.relative(7.384, SpentFuelPool.saturationPressureKpa(40.0), 0.01, "p_sat 40 C");
        Check.relative(19.946, SpentFuelPool.saturationPressureKpa(60.0), 0.01, "p_sat 60 C");
        Check.relative(101.325, SpentFuelPool.saturationPressureKpa(100.0), 0.01, "p_sat 100 C");
    }

    public static void test02_evaporationMatchesTheHandCalculation() {
        // 49 m2 at 35 C, coefficient 0.089 + 0.0782 * 0.1 = 0.09682, Y = 2400
        double expected = 49.0 * (SpentFuelPool.saturationPressureKpa(35.0) - 1.58) * (0.089 + 0.00782) / 2400.0;
        Check.relative(expected, SpentFuelPool.evaporationKgPerS(49.0, 35.0), 1.0e-12, "evaporation at 35 C");
        Check.inRange(6.0e-3, 9.0e-3, SpentFuelPool.evaporationKgPerS(49.0, 35.0), "49 m2 at 35 C evaporates about 7 g/s (25 kg/h)");
        Check.exactly(0.0, SpentFuelPool.evaporationKgPerS(49.0, 10.0), "air that is damper than the water's vapour pressure takes none");
        Check.note("evaporation of a 49 m2 pool: %.1f kg/h at 35 C, %.0f kg/h at 80 C",
                SpentFuelPool.evaporationKgPerS(49.0, 35.0) * 3600.0, SpentFuelPool.evaporationKgPerS(49.0, 80.0) * 3600.0);
    }

    /** At 100 C with no cooling the level falls at (decay power - passive losses) / 2257 kJ/kg, worked out by hand. */
    public static void test03_boilOffRateIsTheEnergyBalance() {
        SpentFuelPool p = pool(1.0, 100.0);
        SpentFuelPool.Slot[] s = slots(40, 25_000.0, 1.0);   // 1 MW
        double before = p.massKg();
        p.step(1.0, s);
        double passive = (SpentFuelPool.SURFACE_W_PER_M2_K * 49.0 + SpentFuelPool.WALL_W_PER_M2_K * wetted(49, 6.0))
                * (100.0 - SpentFuelPool.AMBIENT_C);
        double expectedKgPerS = (1.0e6 - passive) / (2257.0 * 1000.0);
        Check.relative(expectedKgPerS, p.boilKgPerS(), 1.0e-6, "boil-off rate");
        Check.relative(expectedKgPerS, before - p.massKg(), 1.0e-6, "mass lost in one second");
        Check.exactly(100.0, p.temperatureC(), "a boiling pool stays at 100 C");
        // an hour: the level falls by rate * 3600 / (1000 * 49)
        double level0 = p.levelM();
        for (int i = 0; i < 3599; i++) {
            p.step(1.0, s);
        }
        double drop = level0 - p.levelM();
        Check.relative(expectedKgPerS * 3599.0 / (1000.0 * 49.0), drop, 0.002, "level drop in an hour at 1 MW");
        Check.note("1 MW into a 49 m2 pool at 100 C boils %.2f kg/s, the level falls %.1f cm per hour", expectedKgPerS, drop * 100.0);
    }

    /** Wetted area of a square pool of this footprint filled to this level, as the pool computes it. */
    private static double wetted(int footprint, double levelM) {
        return footprint + 4.0 * Math.sqrt(footprint) * levelM;
    }

    /** Without cooling the pool heats from 30 C to boiling, taking longer than the constant-loss estimate. */
    public static void test04_withoutCoolingItHeatsToBoiling() {
        SpentFuelPool p = pool(1.0, 30.0);
        SpentFuelPool.Slot[] s = slots(40, 25_000.0, 1.0);
        p.step(1.0, s);
        double estimate = p.secondsToBoil();
        Check.finite(estimate, "estimated time to boil");
        double seconds = 1.0;
        while (p.temperatureC() < 99.999 && seconds < 400_000.0) {
            p.step(1.0, s);
            seconds += 1.0;
        }
        Check.isTrue(p.temperatureC() >= 99.999, "the pool must reach boiling: %s C after %s s", p.temperatureC(), seconds);
        // ideal: 294,000 kg from 30 to 100 C at 1 MW, no losses
        double ideal = 49.0 * 6.0 * 1000.0 * CP * 70.0 / 1.0e6;
        Check.isTrue(seconds > ideal && seconds < 2.0 * ideal, "time to boil %s s against %s s with no losses", seconds, ideal);
        Check.isTrue(seconds > estimate, "the estimate takes the losses at the start; the heat-up meets larger ones: %s vs %s", seconds, estimate);
        for (int i = 0; i < 600; i++) {
            p.step(1.0, s);
        }
        Check.greaterThan(0.0, p.cumulativeBoiledKg(), "boiled mass once at 100 C");
        Check.note("1 MW heats a full 49 m2 x 6 m pool 30 -> 100 C in %.1f h (no-loss ideal %.1f h)", seconds / 3600.0, ideal / 3600.0);
    }

    /** The first law over a heat-up: decay heat in = stored energy + passive and evaporative losses. */
    public static void test05_energyIsConserved() {
        SpentFuelPool p = pool(0.9, 32.0);
        SpentFuelPool.Slot[] s = slots(16, 20_000.0, 1.0);
        double stored0 = p.massKg() * CP * p.temperatureC();
        double in = 0.0;
        double out = 0.0;
        double evaporatedSensible = 0.0;
        for (int i = 0; i < 7200; i++) {
            double massBefore = p.massKg();
            double tBefore = p.temperatureC();
            p.step(1.0, s);
            in += p.coveredDecayW();
            out += p.evaporationLossW() + p.passiveLossW();
            // the evaporated water leaves at about the temperature it had
            evaporatedSensible += (massBefore - p.massKg()) * CP * tBefore;
        }
        double stored1 = p.massKg() * CP * p.temperatureC();
        Check.relative(in, (stored1 - stored0) + out + evaporatedSensible, 0.01, "energy in against energy stored and lost");
        Check.note("two hours at 320 kW: %.0f MJ in, %.0f MJ stored, %.0f MJ lost", in / 1e6, (stored1 - stored0) / 1e6, out / 1e6);
    }

    public static void test06_aCoolingLoopHoldsTheLevelAndTheTemperature() {
        SpentFuelPool p = pool(1.0, 30.0);
        SpentFuelPoolCooler cooler = new SpentFuelPoolCooler();
        SpentFuelPool.Slot[] s = slots(40, 25_000.0, 1.0);   // 1 MW
        double coldH = Saturation.subcooledLiquidEnthalpyKJPerKg(13.0);
        double removedMJ = 0.0;
        for (int i = 0; i < 4 * 3600; i++) {
            cooler.fillCold(SpentFuelPoolCooler.SECONDARY_FLOW_KG_PER_S, coldH, false);   // a supply that keeps up
            cooler.drainHot(SpentFuelPoolCooler.CAPACITY_KG, false);                       // and a return that takes the warm water away
            removedMJ += cooler.step(p, 1.0);
            p.step(1.0, s);
        }
        Check.isTrue(p.temperatureC() < 45.0, "a 6 MW loop must hold a 1 MW pool well below boiling: %s C", p.temperatureC());
        Check.isTrue(p.levelFraction() > 0.99, "the level holds with cooling: %s", p.levelFraction());
        Check.exactly(0.0, p.cumulativeBoiledKg(), "nothing boiled");
        // the same pool without it
        SpentFuelPool dry = pool(1.0, 30.0);
        for (int i = 0; i < 4 * 3600; i++) {
            dry.step(1.0, s);
        }
        Check.isTrue(dry.temperatureC() > p.temperatureC() + 10.0, "without cooling it is much hotter: %s vs %s", dry.temperatureC(), p.temperatureC());
        Check.note("4 h at 1 MW: with cooling %.1f C (%.0f MJ removed), without %.1f C", p.temperatureC(), removedMJ, dry.temperatureC());
    }

    public static void test07_aLoopWithoutColdWaterOrWaterInThePoolMovesNoHeat() {
        SpentFuelPool p = pool(1.0, 60.0);
        SpentFuelPoolCooler cooler = new SpentFuelPoolCooler();
        Check.exactly(0.0, cooler.step(p, 1.0), "no cold water, no heat transfer");
        double coldH = Saturation.subcooledLiquidEnthalpyKJPerKg(13.0);
        cooler.fillCold(5000.0, coldH, false);
        double mj = cooler.step(p, 1.0);
        Check.isTrue(mj > 0.0 && mj <= SpentFuelPoolCooler.RATING_MW + 1e-9, "a supplied loop transfers up to its rating: %s MJ", mj);
        Check.relative(mj * 1000.0, cooler.hotKg() * (cooler.hotEnthalpy() - coldH), 1.0e-6, "the heat the pool lost is the heat the hot water gained");
        SpentFuelPool low = pool(0.04, 60.0);
        Check.exactly(0.0, cooler.step(low, 1.0), "below the suction floor the pump moves nothing");
    }

    public static void test08_uncoveredFuelHeatsAndCoveredFuelDoesNot() {
        SpentFuelPool p = pool(1.0, 30.0);
        p.restore(p.capacityKg() * (0.9 / 6.0), 30.0);   // level 0.9 m
        SpentFuelPool.Slot[] s = slots(2, 20_000.0, 1.0);
        s[1] = new SpentFuelPool.Slot(0.5);
        s[1].occupied = true;
        s[1].decayW = 20_000.0;
        s[1].fuelC = 30.0;
        s[0].fuelC = 30.0;
        for (int i = 0; i < 10; i++) {
            p.step(1.0, s);
        }
        Check.isFalse(s[0].covered, "the 1 m rack top is above a 0.9 m level");
        Check.isTrue(s[1].covered, "the 0.5 m rack top is under it");
        // early heating is P / C: 20 kW / 180 kJ/K = 0.111 K/s, minus a few percent of losses
        double rise = s[0].fuelC - 30.0;
        Check.relative(10.0 * 20_000.0 / (SpentFuelPool.FUEL_HEAT_CAPACITY_KJ_PER_K * 1000.0), rise, 0.05, "initial heating of uncovered fuel");
        Check.exactly(p.temperatureC(), s[1].fuelC, "covered fuel is at the water temperature");
        Check.exactly(20_000.0, p.uncoveredDecayW(), "uncovered power");
        Check.exactly(20_000.0, p.coveredDecayW(), "covered power");
    }

    public static void test09_freshFuelIgnitesOldFuelSettles() {
        // {power W, outcome}: 2 ignites, 1 cladding bursts but the zirconium does not ignite, 0 settles intact
        for (double[] row : new double[][] {{300_000.0, 2}, {100_000.0, 2}, {20_000.0, 1}, {10_000.0, 0}, {5_000.0, 0}}) {
            SpentFuelPool p = new SpentFuelPool();
            p.configure(49, 6);   // dry
            p.restore(0.0, 30.0);
            SpentFuelPool.Slot[] s = slots(1, row[0], 1.0);
            s[0].fuelC = 30.0;
            double burst = -1;
            double ignite = -1;
            for (int t = 0; t < 6 * 3600; t++) {
                p.step(1.0, s);
                if (burst < 0 && s[0].damaged) {
                    burst = t;
                }
                if (ignite < 0 && s[0].ignited) {
                    ignite = t;
                }
            }
            Check.note("dry %.0f kW assembly: cladding bursts at %.0f s, zirconium ignites at %.0f s, %.0f C after 6 h",
                    row[0] / 1e3, burst, ignite, s[0].fuelC);
            if (row[1] == 2) {
                Check.isTrue(ignite > 0, "%s W in dry air must ignite the zirconium", row[0]);
            } else if (row[1] == 1) {
                Check.isTrue(burst > 0 && ignite < 0, "%s W in dry air must burst the cladding but not ignite: burst %s ignite %s", row[0], burst, ignite);
            } else {
                Check.isTrue(ignite < 0 && !s[0].damaged, "%s W in dry air must settle without burst or ignition: %s C", row[0], s[0].fuelC);
            }
        }
    }

    public static void test10_makeupMixesAndFillsOnlyToTheRim() {
        SpentFuelPool p = pool(0.5, 80.0);
        double accepted = p.receiveWaterKg(10_000.0, 20.0);
        Check.exactly(10_000.0, accepted, "accepted");
        Check.relative((0.5 * 294_000.0 * 80.0 + 10_000.0 * 20.0) / (0.5 * 294_000.0 + 10_000.0), p.temperatureC(), 1.0e-9, "mixed temperature");
        double room = p.spaceKg();
        Check.exactly(room, p.receiveWaterKg(room + 5000.0, 20.0), "fills only to the rim");
        Check.exactly(0.0, p.receiveWaterKg(1.0, 20.0), "a full pool refuses");
        Check.exactly(0.0, p.receiveWaterKg(-5.0, 20.0), "negative flow ignored");
        Check.exactly(0.0, p.receiveWaterKg(Double.NaN, 20.0), "NaN ignored");
    }

    public static void test11_theEstimatesAreTheClosedForm() {
        SpentFuelPool p = pool(1.0, 100.0);
        SpentFuelPool.Slot[] s = slots(40, 25_000.0, 1.0);
        p.step(1.0, s);
        double passive = (SpentFuelPool.SURFACE_W_PER_M2_K * 49.0 + SpentFuelPool.WALL_W_PER_M2_K * wetted(49, 6.0))
                * (100.0 - SpentFuelPool.AMBIENT_C);
        double boilW = 1.0e6 - passive;
        double toLose = p.massKg() - 1.0 * 49.0 * 1000.0;
        Check.relative(toLose * 2257.0e3 / boilW, p.secondsToUncover(1.0), 0.002, "time to uncover from boiling");
        Check.exactly(0.0, p.secondsToBoil(), "already boiling");
        // with cooling taking more than the heat, there is no uncover time
        SpentFuelPool cooled = pool(1.0, 50.0);
        SpentFuelPoolCooler cooler = new SpentFuelPoolCooler();
        cooler.fillCold(10_000.0, Saturation.subcooledLiquidEnthalpyKJPerKg(13.0), false);
        cooler.step(cooled, 1.0);
        cooled.step(1.0, slots(4, 20_000.0, 1.0));
        Check.isTrue(Double.isInfinite(cooled.secondsToBoil()), "a pool that is cooling never boils at this load");
        Check.isTrue(Double.isInfinite(cooled.secondsToUncover(1.0)), "and never uncovers");
    }

    public static void test12_aRefloodedAssemblyIsQuenched() {
        SpentFuelPool p = new SpentFuelPool();
        p.configure(49, 6);
        p.restore(0.0, 30.0);
        SpentFuelPool.Slot[] s = slots(1, 300_000.0, 1.0);
        s[0].fuelC = 30.0;
        for (int t = 0; t < 3000; t++) {
            p.step(1.0, s);
        }
        Check.isTrue(s[0].fuelC > 500.0, "an uncovered fresh assembly must be hot: %s", s[0].fuelC);
        p.receiveWaterKg(p.capacityKg(), 15.0);
        p.step(1.0, s);
        Check.isTrue(s[0].covered, "covered again");
        Check.exactly(p.temperatureC(), s[0].fuelC, "quenched to the water temperature");
        Check.isFalse(s[0].ignited, "no longer burning");
    }
}
