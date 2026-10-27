package dev.bwr.core.turbine;

import dev.bwr.core.Check;

/** The worst case of a turbine (TURBINE-MAINTENANCE.md): a burst rotor breaking loose, and the fires a turbine can have. */
public final class TurbineWreckTest {
    private TurbineWreckTest() {}

    public static void test01_aBurstRotorBreaksLooseOnlyFarPastTheBurstSpeed() {
        Check.isTrue(Math.abs(TurbineBlades.EXCURSION_RPM / ShaftDynamics.RATED_RPM - 1.8) < 0.005,
                "a burst rotor breaks loose at 180 %% of rated: %.0f rpm", TurbineBlades.EXCURSION_RPM);
        Check.isTrue(TurbineBlades.EXCURSION_RPM > TurbineBlades.BURST_RPM * 1.15, "well past the 150 %% burst speed");
        Check.isFalse(TurbineBlades.excursion(true, TurbineBlades.BURST_RPM + 10), "a disc bursting at 150 %% does not break the rotor loose by itself");
        Check.isFalse(TurbineBlades.excursion(true, ShaftDynamics.RATED_RPM), "nor at rated speed after a bad close");
        Check.isTrue(TurbineBlades.excursion(true, TurbineBlades.EXCURSION_RPM + 1), "a burst rotor driven on to 180 %% does");
        Check.isFalse(TurbineBlades.excursion(false, 3 * ShaftDynamics.RATED_RPM), "an intact rotor is balanced: no excursion at any speed");
        double rated = TurbineBlades.unbalanceForceN(ShaftDynamics.RATED_RPM), atBurst = TurbineBlades.unbalanceForceN(TurbineBlades.BURST_RPM);
        Check.isTrue(rated > 6e7 && rated < 7.5e7 && atBurst > 1.4e8 && atBurst < TurbineBlades.COUPLING_SHEAR_N,
                "the lost third's unbalance: %.0f MN at rated, %.0f MN at 150 %%", rated / 1e6, atBurst / 1e6);
        Check.note("excursion at %.0f rpm; unbalance %.0f MN at rated, %.0f MN at the burst speed, %.0f MN shears the coupling",
                TurbineBlades.EXCURSION_RPM, rated / 1e6, atBurst / 1e6, TurbineBlades.COUPLING_SHEAR_N / 1e6);
    }

    public static void test02_theRotorHalfComesToRestOnTheFloorBesideTheTurbine() {
        double e = TurbineBlades.excursionEnergyJ(), perM = TurbineBlades.excursionFrictionJPerM();
        Check.isTrue(e > 5e6 && e < 7e6, "62 t at 15 m/s, less the end wall: %.1f MJ", e / 1e6);
        double open = e / perM;
        Check.isTrue(open > 10 && open < 25, "on open floor it ploughs to rest within a turbine hall: %.1f m", open);
        double stone = 0.6e6; // a stone block's cost to a missile (TURBINE-MAINTENANCE.md)
        Check.isTrue(e / stone > 5, "with enough left to break through a few blocks in its way: %.0f stone-rated blocks", e / stone);
        Check.note("rotor half %.1f MJ, %.0f kJ a metre of floor, %.1f m on open floor", e / 1e6, perM / 1e3, open);
    }

    public static void test03_aWipedBearingLightsItsOilAndAPumpedFireKeepsBurning() {
        Check.isFalse(TurbineFire.bearingIgnites(TurbineFire.BEARING_IGNITION_C - 1, 1500), "under the ignition point nothing lights");
        Check.isFalse(TurbineFire.bearingIgnites(500, 0), "a stopped shaft's hot bearing does not light the oil");
        Check.isTrue(TurbineFire.bearingIgnites(TurbineFire.BEARING_IGNITION_C, 1500), "at the ignition point on a turning shaft it does");
        // The bearing law with no film: how long until the oil lights.
        double c = TurbineAux.AMBIENT_C, t = 0, dt = 0.05;
        while (!TurbineFire.bearingIgnites(c, ShaftDynamics.RATED_RPM) && t < 600) {
            c = TurbineAux.bearingStepC(c, TurbineAux.bearingTargetC(0, TurbineAux.AMBIENT_C, ShaftDynamics.RATED_RPM), dt);
            t += dt;
        }
        Check.isTrue(t > 20 && t < 90, "a bearing run dry at rated speed lights its oil in about a minute: %.0f s", t);
        double pool = TurbineFire.BEARING_SPILL_L, s = 0;
        while (pool > 0 && s < 1e5) { pool = TurbineFire.poolAfter(pool, 1); s++; }
        Check.isTrue(s >= 60 && s < 300, "an unfed bearing fire burns out in minutes: %.0f s", s);
        Check.exactly(0.0, TurbineFire.sprayLitres(0, 1), "no pressure, no spray");
        double fed = TurbineFire.sprayLitres(TurbineAux.OIL_PSIG_NOMINAL, 1);
        Check.isTrue(fed > TurbineFire.POOL_BURN_L_PER_S, "the pumps spray more oil than the pool burns: %.1f L/s", fed);
        Check.isTrue(TurbineFire.LINE_SPILL_L / TurbineFire.POOL_BURN_L_PER_S > 300, "a cut line's spill burns for minutes on its own");
        double h2 = TurbineAux.H2_FULL_KG, hs = 0;
        while (h2 > 0 && hs < 1e5) { h2 = TurbineFire.hydrogenAfter(h2, 1); hs++; }
        Check.isTrue(hs > 30 && hs < 300, "a full hydrogen charge burns at the seals for %.0f s", hs);
        double life = 1 / TurbineFire.damage(1);
        Check.isTrue(life > 120 && life < 600, "a section burning wrecks in %.0f s", life);
        Check.note("dry bearing lights in %.0f s; bearing fire %.0f s unfed; hydrogen %.0f s; a section burns out in %.0f s", t, s, hs, life);
    }
}
