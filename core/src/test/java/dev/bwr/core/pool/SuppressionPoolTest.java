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
}
