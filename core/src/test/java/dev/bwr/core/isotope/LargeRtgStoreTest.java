package dev.bwr.core.isotope;

import dev.bwr.core.Check;
import dev.bwr.core.grid.EnergyUnits;
import dev.bwr.core.isotope.LargeRtgStore.Fuel;

/** The Large RTG's arithmetic: 1,000 kg of mixed isotopes, the capacity cap, decay, heat-only mode and the housing. */
public final class LargeRtgStoreTest {

    private LargeRtgStoreTest() {
    }

    private static final Fuel SR_INGOT = new Fuel("Sr-90", 0.92, 28.9, 250.0, 0.0057, 1.0, 0.6);
    private static final Fuel SR_BLOCK = new Fuel("Sr-90", 0.92, 28.9, 2250.0, 0.0057, 1.0, 0.6);
    private static final Fuel CS_INGOT = new Fuel("Cs-137", 0.42, 30.04, 250.0, 0.29, 0.85, 0.65);
    private static final Fuel AM_INGOT = new Fuel("Am-241", 0.1147, 432.6, 250.0, 0.00063, 0.05, 0.02);
    private static final Fuel CO_INGOT = new Fuel("Co-60", 18.9, 5.2714, 474.0, 16.8, 0.9, 1.2);
    private static final double YEAR = RadioisotopePower.SECONDS_PER_YEAR;

    /** 1,000 kg of strontium-90: 920 kW thermal, 46 kW electric, 3,432.8 FE/t, the figures the docs give. */
    public static void test01_aThousandKilogramsOfStrontium() {
        var s = new LargeRtgStore();
        long in = s.insert("sr", SR_INGOT, 4000, 0.0);
        Check.exactly(4000L, in, "4,000 ingots of 250 g fit");
        Check.exactly(1_000_000.0, s.massGrams(), "the mass held");
        var o = s.output(0.0, false, 1.0);
        Check.relative(920_000.0, o.thermalWatts(), 1e-12, "thermal W");
        Check.relative(46_000.0, o.electricWatts(), 1e-12, "electric W");
        Check.relative(46_000.0 / EnergyUnits.WATTS_PER_FE_PER_TICK, o.fePerTick(), 1e-12, "FE/t");
        Check.note("1,000 kg Sr-90: %.0f W thermal, %.0f W electric, %.1f FE/t", o.thermalWatts(), o.electricWatts(), o.fePerTick());
    }

    /** Mixed isotopes add: the power is the sum of the kinds, the mass the sum of the masses (the cobalt ingot is the game's 474 g). */
    public static void test02_mixedIsotopesAdd() {
        var s = new LargeRtgStore();
        s.insert("sr", SR_INGOT, 1600, 0.0);    // 400 kg
        s.insert("cs", CS_INGOT, 1200, 0.0);    // 300 kg
        s.insert("am", AM_INGOT, 800, 0.0);     // 200 kg
        s.insert("co", CO_INGOT, 210, 0.0);     // 99.54 kg
        Check.exactly(999_540.0, s.massGrams(), "the mixed mass");
        double expected = 400_000 * 0.92 + 300_000 * 0.42 + 200_000 * 0.1147 + 99_540 * 18.9;
        var o = s.output(0.0, false, 1.0);
        Check.relative(expected, o.thermalWatts(), 1e-12, "thermal of the mix");
        Check.relative(expected * 0.05, o.electricWatts(), 1e-12, "electric of the mix");
        Check.relative(expected * 0.05 / 13.4 * 2.0, s.output(0.0, false, 2.0).fePerTick(), 1e-12, "the output multiplier applies to the electricity");
        Check.exactly(expected, s.output(0.0, false, 2.0).thermalWatts(), "the output multiplier leaves the heat alone");
        Check.relative(5.2714, o.shortestHalfLifeYears(), 1e-12, "the shortest half-life");
        Check.note("mixed 1,000 kg: %.0f W thermal, %.1f FE/t", o.thermalWatts(), o.fePerTick());
    }

    /** The cap: whole items only, up to 1,000 kg, in any mix of ingots and blocks; the 4,001st ingot is refused. */
    public static void test03_theCapacityCap() {
        var s = new LargeRtgStore();
        Check.exactly(4000L, s.insert("sr", SR_INGOT, 5000, 0.0), "5,000 offered, 4,000 taken");
        Check.exactly(0L, s.insert("sr", SR_INGOT, 1, 0.0), "the 4,001st ingot is refused");
        Check.exactly(0L, s.insert("sr_block", SR_BLOCK, 1, 0.0), "a block is refused when full");
        Check.exactly(1_000_000.0, s.massGrams(), "full means exactly 1,000 kg");
        var t = new LargeRtgStore();
        Check.exactly(444L, t.insert("blk", SR_BLOCK, 10_000, 0.0), "444 blocks (999 kg) fit");
        Check.exactly(4L, t.insert("ing", SR_INGOT, 10, 0.0), "then four more ingots: 1,000 kg to the gram");
        Check.exactly(1_000_000.0, t.massGrams(), "blocks and ingots together");
        Check.exactly(0L, t.room(0.01), "not even a radium item fits at the cap");
        var u = new LargeRtgStore();
        Check.exactly(0L, u.insert("none", SR_INGOT, 0, 0.0), "nothing offered, nothing taken");
        Check.exactly(0L, u.insert("neg", SR_INGOT, -5, 0.0), "a negative offer takes nothing");
    }

    /** Decay is the data's half-life: after one half-life the power is half; batches added at different times stay exact. */
    public static void test04_decayAndTheMergedClock() {
        var s = new LargeRtgStore();
        s.insert("sr", SR_INGOT, 100, 0.0);
        double half = 28.9 * YEAR;
        Check.relative(0.5 * 100 * 230.0, s.output(half, false, 1.0).thermalWatts(), 1e-12, "one half-life");
        // add a second batch one half-life in: the sum of the two batches' own decay must equal the merged clock's
        s.insert("sr", SR_INGOT, 300, half);
        double later = 1.7 * half;
        double exact = 100 * 230.0 * Math.pow(0.5, later / half) + 300 * 230.0 * Math.pow(0.5, (later - half) / half);
        Check.relative(exact, s.output(later, false, 1.0).thermalWatts(), 1e-12, "two batches, one clock");
        Check.relative(exact, s.output(later, false, 1.0).thermalWatts(), 1e-12, "and again");
        // a session of 100 hours costs under 0.03 %
        var t = new LargeRtgStore();
        t.insert("sr", SR_INGOT, 4000, 0.0);
        Check.isTrue(t.output(100 * 3600.0, false, 1.0).thermalWatts() > 0.9997 * 920_000.0, "100 hours cost more than 0.03 %%");
        // cobalt-60 really does decay in a session of years: five years leave 51.8 %
        var c = new LargeRtgStore();
        c.insert("co", CO_INGOT, 10, 0.0);
        Check.relative(Math.pow(0.5, 5.0 / 5.2714), c.output(5.0 * YEAR, false, 1.0).thermalWatts() / (10 * 474.0 * 18.9), 1e-12, "cobalt after five years");
    }

    /** Heat-only mode: no FE and no electric watts, the full thermal power. */
    public static void test05_heatOnlyStopsTheElectricityOnly() {
        var s = new LargeRtgStore();
        s.insert("sr", SR_INGOT, 4000, 0.0);
        s.insert("x", SR_INGOT, 1, 0.0); // refused: full
        var on = s.output(0.0, false, 1.0);
        var off = s.output(0.0, true, 1.0);
        Check.exactly(0.0, off.fePerTick(), "no FE in heat-only mode");
        Check.exactly(0.0, off.electricWatts(), "no electric watts in heat-only mode");
        Check.exactly(on.thermalWatts(), off.thermalWatts(), "the thermal power is the same");
        Check.exactly(on.doseAtOneMetreMsvPerH(), off.doseAtOneMetreMsvPerH(), "so is the dose");
        Check.isTrue(on.fePerTick() > 3000.0, "the switch off makes FE: %.1f", on.fePerTick());
    }

    /** The housing: caesium and strontium are stopped, cobalt-60 is still a source; the bare core is shown too. */
    public static void test06_theHousingAndTheDose() {
        var cs = new LargeRtgStore();
        cs.insert("cs", CS_INGOT, 4000, 0.0);
        var c = cs.output(0.0, false, 1.0);
        // 1,000 kg Cs-137: 0.29 W/g x 0.85 = 246.5 kW of photons, about 217 kSv/h bare at 1 m
        Check.relative(246_500.0, c.bareGammaWatts(), 1e-12, "bare gamma power of 1,000 kg of caesium");
        Check.greaterThan(1.0e8, c.bareDoseAtOneMetreMsvPerH(), "bare caesium reads over 100 kSv/h at a metre");
        Check.lessThan(1.0e-6, c.doseAtOneMetreMsvPerH(), "40 cm of lead stops caesium-137");
        var co = new LargeRtgStore();
        co.insert("co", CO_INGOT, 2_109, 0.0);   // 999,666 g: the 2,110th would pass the cap
        var k = co.output(0.0, false, 1.0);
        Check.greaterThan(1.0e9, k.bareDoseAtOneMetreMsvPerH(), "bare cobalt-60 reads over a MSv/h");
        Check.exactly(0L, co.insert("co", CO_INGOT, 1, 0.0), "the 2,110th cobalt ingot is refused");
        double t = LargeRtgStore.shieldTransmission(1.2);
        Check.relative(Math.pow(0.5, 40.0 / 1.2), t, 1e-12, "transmission is 2^(-thickness / half-value layer)");
        Check.relative(k.bareGammaWatts() * t * dev.bwr.core.radiation.DoseModel.pointSourceMsvPerH(1.0, 1.0, 1.0), k.doseAtOneMetreMsvPerH(), 1e-9, "the shielded cobalt dose");
        Check.inRange(0.1, 10.0, k.doseAtOneMetreMsvPerH(), "1,000 kg of Co-60 still reads about a mSv/h at 1 m");
        Check.exactly(1.0, LargeRtgStore.shieldTransmission(0.0), "no half-value layer, no shielding claimed");
        Check.note("1,000 kg Co-60: bare %.3g mSv/h, housed %.3f mSv/h at 1 m; Cs-137 housed %.3g mSv/h",
                k.bareDoseAtOneMetreMsvPerH(), k.doseAtOneMetreMsvPerH(), c.doseAtOneMetreMsvPerH());
    }

    /** Draining returns every kind with its exact count (mass conserved to the item). */
    public static void test07_drainConservesTheItems() {
        var s = new LargeRtgStore();
        s.insert("sr", SR_INGOT, 123, 0.0);
        s.insert("blk", SR_BLOCK, 45, 10.0);
        double before = s.massGrams();
        long items = s.itemCount();
        var out = s.drain();
        double after = 0;
        long n = 0;
        for (var b : out) {
            after += b.grams();
            n += b.count();
        }
        Check.exactly(before, after, "mass out equals mass in");
        Check.exactly(items, n, "item count out equals in");
        Check.isTrue(s.isEmpty() && s.massGrams() == 0.0, "the store is empty after a drain");
        Check.isTrue(LargeRtgStore.Output.NONE.equals(s.output(0.0, false, 1.0)), "an empty store makes nothing");
    }
}
