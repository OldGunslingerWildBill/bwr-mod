package dev.bwr.core.pool;

import dev.bwr.core.Check;
import dev.bwr.core.PhysicalConstants;
import dev.bwr.core.thermal.Saturation;

import java.lang.reflect.Method;

/**
 * Acceptance tests for the suppression pool — {@code SPEC.md} section 12.
 *
 * <p>The pool is a heat sink with a finite capacity, and the interesting
 * failure is not that it breaks but that it fills up. These tests check that
 * the energy bookkeeping is honest, that suppression degrades continuously as
 * subcooling is consumed rather than falling off a cliff, and that the closed
 * pool-suction loop really does heat itself.
 */
public final class SuppressionPoolTest {

    private SuppressionPoolTest() {
    }

    public static void testSteamBufferPersistsAndOldSavesRemainReadable() {
        var pool=SuppressionPool.empty(100_000);
        pool.receiveWaterKg(50_000,25);
        pool.inletSteam.offer(new dev.bwr.core.turbine.SteamInventory.Packet(75,2770,1015));
        var restored=SuppressionPool.empty(100_000);restored.fromArray(pool.toArray());
        if(restored.inletSteam.mass()!=75||restored.inletSteam.enthalpy()!=2770)throw new AssertionError("steam inventory/enthalpy lost");
        var packet=restored.inletSteam.take(75);double before=restored.getTemperatureC();
        restored.condenseSteamAtEnthalpy(packet.mass(),packet.enthalpy(),1);
        if(restored.getTemperatureC()<=before||restored.getMassKg()!=50_075)throw new AssertionError("steam did not become heat and water");
        restored.fromArray(java.util.Arrays.copyOf(pool.toArray(),9));
        if(restored.inletSteam.mass()!=0||restored.getMassKg()!=50_000)throw new AssertionError("legacy pool save incompatible");
    }

    /**
     * Condensing steam must deposit exactly the enthalpy it carried, and the
     * pool must warm by that energy over its own heat capacity.
     *
     * <p>The temperature-rise check used to be
     * {@code expectedRise = heatMJ * 1000 / (startMass * cp)} against the pool's
     * measured rise — which is character for character how {@code addHeatMJ} is
     * written, evaluated on the same inputs. It could not fail: the expected
     * value was the production formula fed the production answer. Both halves
     * are derived independently here instead. The energy is the steam's enthalpy
     * above the pool's liquid enthalpy, from the steam tables; the rise is that
     * energy over the pool's mass and specific heat, computed from the
     * <i>steam flow</i> rather than from what {@code condenseSteam} returned.
     */
    public static void test01_condensationEnergyBalances() {
        SuppressionPool pool = new SuppressionPool();
        double startT = pool.getTemperatureC();
        double startMass = pool.getMassKg();

        double steamKgPerS = 100.0;
        double dt = 10.0;
        double domePsia = Saturation.psiaFromPsig(PhysicalConstants.RATED_DOME_PRESSURE_PSIG);

        // What a kilogram of saturated steam at dome pressure gives up on
        // becoming pool water: its vapour enthalpy less the liquid enthalpy of
        // water at the pool temperature. Steam tables, not SuppressionPool.
        double perKgKJ = Saturation.vapourEnthalpyKJPerKg(domePsia)
                - Saturation.subcooledLiquidEnthalpyKJPerKg(startT);
        double expectedHeatMJ = steamKgPerS * dt * perKgKJ / 1000.0;
        double cp = Saturation.liquidSpecificHeatKJPerKgC(pool.getContainmentPressurePsia());
        double expectedRise = expectedHeatMJ * 1000.0 / (startMass * cp);

        double heatMJ = pool.condenseSteam(steamKgPerS, PhysicalConstants.RATED_DOME_PRESSURE_PSIG, dt);
        double actualRise = pool.getTemperatureC() - startT;

        Check.note("condensed %.0f kg of steam carrying %.1f kJ/kg: expected %.1f MJ, "
                        + "pool returned %.1f MJ", steamKgPerS * dt, perKgKJ, expectedHeatMJ, heatMJ);
        Check.note("pool %.3f -> %.3f degC, expected rise %.3f degC at cp = %.4f kJ/kg/C",
                startT, pool.getTemperatureC(), expectedRise, cp);
        Check.finiteAndPositive(heatMJ, "heat deposited");
        // The expected energy above assumes every kilogram condensed, which is
        // what a cold pool does. Stated here so a partially effective pool fails
        // with the right reason rather than as an energy discrepancy.
        Check.exactly(1.0, pool.getLastCondensationEffectiveness(),
                "a pool at its initial temperature must condense everything");
        Check.relative(expectedHeatMJ, heatMJ, 1e-9,
                "heat deposited against the steam's enthalpy above pool water");
        Check.relative(expectedRise, actualRise, 1e-9,
                "temperature rise against that energy over the pool's heat capacity");

        Check.note("pool inventory grew %.0f kg as the steam became liquid",
                pool.getMassKg() - startMass);
        Check.relative(steamKgPerS * dt, pool.getMassKg() - startMass, 1e-9,
                "condensed steam joins pool inventory");
    }

    /** A hot pool cools faster than a warm one, and RHR never drives it past the sink. */
    public static void test02_rhrCoolingAsymptotesToTheHeatSink() {
        SuppressionPool hot = new SuppressionPool(SuppressionPool.DEFAULT_MASS_KG, 90.0);
        SuppressionPool warm = new SuppressionPool(SuppressionPool.DEFAULT_MASS_KG, 45.0);

        double hotRemoved = hot.coolWithRhr(1.0, 30.0, 30.0, 60.0);
        double warmRemoved = warm.coolWithRhr(1.0, 30.0, 30.0, 60.0);

        Check.note("RHR at full duty: hot pool (90 degC) rejected %.1f MJ, warm pool (45 degC) %.1f MJ",
                hotRemoved, warmRemoved);
        Check.greaterThan(warmRemoved, hotRemoved, "hot pool rejects more heat than warm pool");

        // Drive it hard for a simulated hour and confirm it approaches but never crosses the sink.
        SuppressionPool p = new SuppressionPool(SuppressionPool.DEFAULT_MASS_KG, 95.0);
        for (int i = 0; i < 3600; i++) {
            p.coolWithRhr(1.0, 30.0, 30.0, 1.0);
        }
        Check.note("after an hour of full RHR the pool sits at %.2f degC against a 30.0 degC sink",
                p.getTemperatureC());
        Check.greaterThan(29.999, p.getTemperatureC(), "pool never cooled below the heat sink");
    }

    /** Suppression must fade out with subcooling, not fail at a threshold. */
    public static void test03_suppressionDegradesContinuouslyWithSubcooling() {
        double satC = new SuppressionPool().getSaturationTemperatureC();
        Check.note("pool saturation temperature at containment pressure: %.2f degC", satC);

        double previous = -1.0;
        for (double t : new double[]{40.0, 70.0, 90.0, satC - 6.0, satC - 1.0, satC, satC + 5.0}) {
            SuppressionPool p = new SuppressionPool(SuppressionPool.DEFAULT_MASS_KG, t);
            double eff = p.condensationEffectiveness();
            Check.note("  %6.2f degC -> subcooling %6.2f degC, condensation effectiveness %.3f",
                    t, p.getSubcoolingC(), eff);
            Check.inRange(0.0, 1.0, eff, "effectiveness stays within 0..1");
            if (previous >= 0.0) {
                Check.isTrue(eff <= previous + 1e-12,
                        "effectiveness must fall monotonically as the pool heats");
            }
            previous = eff;
        }

        SuppressionPool boiling = new SuppressionPool(SuppressionPool.DEFAULT_MASS_KG, satC + 5.0);
        Check.isTrue(boiling.isBoiling(), "a pool above saturation reports boiling");
        Check.exactly(0.0, boiling.condensationEffectiveness(), "boiling pool condenses nothing");
    }

    /** Once boiling, arriving steam passes straight through to containment. */
    public static void test04_exhaustedPoolPassesSteamThrough() {
        SuppressionPool p = new SuppressionPool();
        double satC = p.getSaturationTemperatureC();

        p.condenseSteam(50.0, PhysicalConstants.RATED_DOME_PRESSURE_PSIG, 1.0);
        Check.note("cold pool: %.3f of arriving steam condensed, %.2f kg/s passed through",
                p.getLastCondensationEffectiveness(), p.getUncondensedSteamKgPerS());
        Check.exactly(0.0, p.getUncondensedSteamKgPerS(), "a cold pool condenses everything");

        SuppressionPool spent = new SuppressionPool(SuppressionPool.DEFAULT_MASS_KG, satC + 2.0);
        spent.condenseSteam(50.0, PhysicalConstants.RATED_DOME_PRESSURE_PSIG, 1.0);
        Check.note("spent pool: %.3f condensed, %.2f kg/s uncondensed and pressurising containment",
                spent.getLastCondensationEffectiveness(), spent.getUncondensedSteamKgPerS());
        Check.relative(50.0, spent.getUncondensedSteamKgPerS(), 1e-9,
                "an exhausted pool passes all steam through");
    }

    /**
     * The closed loop from SPEC 9.2: pool suction is unlimited but self-heating,
     * so an extended transient on pool suction walks the pool toward its limit.
     */
    public static void test05_poolSuctionLoopHeatsItself() {
        SuppressionPool p = new SuppressionPool();
        double startT = p.getTemperatureC();
        double startCapacityMJ = p.getRemainingHeatCapacityMJ();

        // Two hours of decay-heat boiloff relieved back into the pool, no RHR.
        double steamKgPerS = 45.0;
        for (int s = 0; s < 7200; s++) {
            double drawn = p.drawSuctionKg(steamKgPerS, 1.0);
            p.condenseSteam(drawn, PhysicalConstants.RATED_DOME_PRESSURE_PSIG, 1.0);
        }

        Check.note("two hours on pool suction with no RHR: %.2f -> %.2f degC, subcooling now %.2f degC",
                startT, p.getTemperatureC(), p.getSubcoolingC());
        Check.note("remaining heat capacity fell from %.0f to %.0f MJ",
                startCapacityMJ, p.getRemainingHeatCapacityMJ());
        Check.greaterThan(startT, p.getTemperatureC(), "closed loop heats the pool");
        Check.lessThan(startCapacityMJ, p.getRemainingHeatCapacityMJ(),
                "remaining heat capacity is consumed");

        // With RHR running, the same duty is survivable — that is the point of RHR.
        SuppressionPool cooled = new SuppressionPool();
        for (int s = 0; s < 7200; s++) {
            double drawn = cooled.drawSuctionKg(steamKgPerS, 1.0);
            cooled.condenseSteam(drawn, PhysicalConstants.RATED_DOME_PRESSURE_PSIG, 1.0);
            cooled.coolWithRhr(1.0, 30.0, 30.0, 1.0);
        }
        Check.note("same two hours with RHR in pool cooling: %.2f degC", cooled.getTemperatureC());
        Check.greaterThan(cooled.getTemperatureC(), p.getTemperatureC(),
                "RHR keeps the pool cooler than the uncooled case");
    }

    /** Persistence must survive a chunk unload mid-transient. */
    public static void test06_roundTripPreservesPoolState() {
        SuppressionPool p = new SuppressionPool();
        p.condenseSteam(120.0, PhysicalConstants.RATED_DOME_PRESSURE_PSIG, 45.0);
        p.coolWithRhr(0.5, 30.0, 30.0, 30.0);
        p.drawSuctionKg(200.0, 10.0);

        double[] snapshot = p.toArray();
        SuppressionPool restored = new SuppressionPool();
        restored.fromArray(snapshot);

        Check.note("round trip at %.4f degC, %.1f kg, %.2f MJ in, %.2f MJ removed",
                p.getTemperatureC(), p.getMassKg(),
                p.getCumulativeHeatInputMJ(), p.getCumulativeRhrRemovedMJ());
        Check.exactly(p.getTemperatureC(), restored.getTemperatureC(), "temperature round trip");
        Check.exactly(p.getMassKg(), restored.getMassKg(), "mass round trip");
        Check.exactly(p.getCumulativeHeatInputMJ(), restored.getCumulativeHeatInputMJ(),
                "cumulative heat input round trip");
        Check.exactly(p.getSubcoolingC(), restored.getSubcoolingC(), "subcooling round trip");
    }

    /**
     * The pool publishes measurements. It must not contain a heat capacity
     * temperature limit, an automatic RHR start, or any other decision — those
     * belong to the player's Lua.
     */
    public static void test07_poolContainsNoProtectionLogic() {
        String[] banned = {
                "shouldscram", "autoscram", "checktrip", "issafe", "permissive",
                "setpoint", "alarm", "trip", "autostart", "islimitexceeded",
        };
        int scanned = 0;
        for (Method m : SuppressionPool.class.getDeclaredMethods()) {
            String name = m.getName().toLowerCase(java.util.Locale.ROOT);
            scanned++;
            for (String bad : banned) {
                Check.isFalse(name.contains(bad),
                        "SuppressionPool." + m.getName() + " encodes a decision the player should own");
            }
        }
        Check.note(". scanned %d methods on SuppressionPool, no protection logic present", scanned);
        Check.note(". the heat capacity temperature limit is deliberately absent: "
                + "subcooling and effectiveness are published, the limit is the operator's to choose");
    }
    public static void test20_emptyBasinAndFiniteSpray() {
        var p=SuppressionPool.empty(100_000);
        Check.isTrue(p.getMassKg()==0 && p.drawSuctionKg(100,1)==0,"new shell must contain no water");
        p.condenseSteam(100,1000,1);
        Check.isTrue(p.getMassKg()==0 && p.getUncondensedSteamKgPerS()==100,"dry pool condensed steam");
        p.addHeatMJ(100);Check.isTrue(Double.isFinite(p.getTemperatureC()),"dry heat poisoned temperature");
        Check.isTrue(p.receiveWaterKg(40_000,32)==40_000,"metered filling");
        Check.isTrue(p.getMassKg()==40_000,"fill mass");
        p.fromArray(new SuppressionPool(40_000,105).toArray());p.resizeCapacityKeepingInventory(100_000);
        p.setSprayMode(true);Check.isTrue(p.receiveWaterKg(100_000,20)==SuppressionPool.SPRAY_HEADER_KG,"finite header");
        double start=p.getMassKg()+p.getSprayWaterKg();
        p.condenseSteam(200,1000,1);double escaped=p.getUncondensedSteamKgPerS();
        double captured=p.spraySteam(escaped,1000,1);
        Check.isTrue(captured>0 && captured<200,"hot pool spray must capture finite steam");
        Check.isTrue(Math.abs(p.getMassKg()+p.getSprayWaterKg()-start-captured)<1e-6,"spray mass balance");
        Check.isTrue(Math.abs(p.getUncondensedSteamKgPerS()+captured-200)<1e-6,"escaping steam balance");
        Check.isTrue(p.getSprayKgPerS()==600,"spray nozzle flow limit");
        var saved=new SuppressionPool();saved.fromArray(p.toArray());
        Check.isTrue(saved.isSprayMode() && saved.getSprayWaterKg()==p.getSprayWaterKg(),"spray persistence");
        double total=saved.getMassKg()+saved.getSprayWaterKg();saved.setSprayMode(false);saved.spraySteam(200,1000,1);
        Check.isTrue(Math.abs(saved.getMassKg()-total)<1e-6 && saved.getSprayCondensedKgPerS()==0,"mode switch lost water or condensed without spray");
        for(int n=0;n<20;n++)p.spraySteam(200,1000,1);
        Check.isTrue(p.getSprayKgPerS()==0 && p.getSprayCondensedKgPerS()==0,"empty header produced free spray");
        p.resizeCapacityKeepingInventory(p.getMassKg());Check.isTrue(p.receiveWaterKg(100,20)==0,"full basin accepted more supply");
    }

    public static void test21_sprayHeatMarginAndNoSteam() {
        var p=SuppressionPool.empty(100_000);p.setSprayMode(true);p.receiveWaterKg(600,110);
        Check.isTrue(p.spraySteam(100,1000,1)==0,"hot spray condensed steam without a heat margin");
        Check.isTrue(p.getMassKg()==600 && p.getTemperatureC()==110,"uncooled spray lost water or heat");
        p.receiveWaterKg(600,20);p.spraySteam(0,1000,1);
        Check.isTrue(p.getMassKg()==1200 && Math.abs(p.getTemperatureC()-65)<1e-9,"spray with no steam must just mix");
        Check.isTrue(p.receiveWaterKg(Double.NaN,20)==0 && p.receiveWaterKg(10,Double.NaN)==0,"invalid supply accepted");
    }

    public static void test22_passiveCoolingConservesWaterAndScalesWithGeometry() {
        var p = new SuppressionPool(105_000, 80);
        double heat = p.coolPassively(25, 35, 107, 3600);
        Check.isTrue(p.getTemperatureC() < 80 && p.getTemperatureC() > 25,
                "unpowered hot pool must cool gradually toward ambient");
        double cp = Saturation.liquidSpecificHeatKJPerKgC(p.getContainmentPressurePsia());
        Check.isTrue(Math.abs(heat - (80-p.getTemperatureC())*105_000*cp/1000) < 1e-7,
                "passive removed heat must match the water's energy loss");
        Check.exactly(105_000, p.getMassKg(), "passive cooling consumes no water");
        Check.exactly(0, p.getCumulativeRhrRemovedMJ(), "passive cooling is not RHR duty");
        var shallow = new SuppressionPool(52_500, 80);
        shallow.coolPassively(25, 35, 71, 3600);
        Check.isTrue(shallow.getTemperatureC() < p.getTemperatureC(), "less water cools faster");
        var broader = new SuppressionPool(105_000, 80);
        broader.coolPassively(25, 70, 107, 3600);
        Check.isTrue(broader.getTemperatureC() < p.getTemperatureC(), "more surface increases cooling");
        var stepped = new SuppressionPool(105_000, 80);
        for (int n=0; n<3600; n++) stepped.coolPassively(25, 35, 107, 1);
        Check.isTrue(Math.abs(stepped.getTemperatureC()-p.getTemperatureC()) < 1e-8,
                "cooling must not depend on timestep subdivision");
        var restored = new SuppressionPool(); restored.fromArray(p.toArray());
        p.coolPassively(25,35,107,60); restored.coolPassively(25,35,107,60);
        Check.exactly(p.getTemperatureC(), restored.getTemperatureC(), "reload preserves passive cooldown");
        Check.note("105 tonne pool after one hour from 80 C: %.3f C", stepped.getTemperatureC());
    }

    public static void test23_passiveCoolingBoundsAndInvalidInputs() {
        var p = new SuppressionPool(105_000,80);
        Check.exactly(0,p.coolPassively(25,0,0,3600),"no area means no heat transfer");
        for (double bad : new double[]{Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY}) {
            Check.exactly(0,p.coolPassively(bad,35,107,1),"invalid ambient");
            Check.exactly(0,p.coolPassively(25,bad,107,1),"invalid surface");
            Check.exactly(0,p.coolPassively(25,35,bad,1),"invalid shell");
            Check.exactly(0,p.coolPassively(25,35,107,bad),"invalid time");
        }
        Check.exactly(0,p.coolPassively(25,-1,107,1),"negative area");
        Check.exactly(0,p.coolPassively(25,35,107,-1),"negative time");
        Check.exactly(80,p.getTemperatureC(),"invalid calls must not corrupt temperature");
        p.coolPassively(25,35,107,1e12);
        Check.exactly(25,p.getTemperatureC(),"long timestep must not overshoot ambient");
        Check.exactly(0,p.coolPassively(25,35,107,1),"ambient pool has no cooling load");
        var cold = new SuppressionPool(1000,10);
        Check.exactly(0,cold.coolPassively(25,35,107,3600),"cooling does not heat a cold pool");
        Check.exactly(10,cold.getTemperatureC(),"cold pool unchanged");
        var dry = SuppressionPool.empty(105_000);
        Check.exactly(0,dry.coolPassively(25,35,107,3600),"empty basin has no stored water heat");
        Check.isTrue(Double.isFinite(dry.getTemperatureC()),"empty basin stays finite");
    }
}
