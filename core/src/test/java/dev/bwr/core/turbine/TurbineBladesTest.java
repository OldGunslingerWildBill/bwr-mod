package dev.bwr.core.turbine;

import dev.bwr.core.Check;
import dev.bwr.core.accident.BlastPhysics;
import dev.bwr.core.grid.Synchronizer;

/** The LP blade sections' damage laws and the turbine missile's energy (TURBINE-MAINTENANCE.md). */
public final class TurbineBladesTest {
    private TurbineBladesTest() {}

    public static void test01_outOfPhaseTorqueScalesWithTheAngle() {
        double inWindow = TurbineBlades.closingTorquePu(Synchronizer.ANGLE_LIMIT, 0);
        Check.isTrue(inWindow < 0.6, "inside the synchronising window the transient is half of rated: %.2f pu", inWindow);
        Check.exactly(0.0, TurbineBlades.closingBladeDamage(inWindow), "an in-window close costs the blades nothing");
        double last = 0;
        for (double angle : new double[] {30, 60, 90, 120, 150, 180}) {
            double pu = TurbineBlades.closingTorquePu(angle, 0);
            Check.isTrue(pu > last, "more angle, more torque at %.0f degrees: %.2f pu", angle, pu);
            last = pu;
        }
        double at120 = TurbineBlades.closingTorquePu(120, 0);
        Check.isTrue(at120 > 4.5 && at120 < 5.5, "about five times rated near 120 degrees: %.2f pu", at120);
        Check.isTrue(TurbineBlades.closingBladeDamage(TurbineBlades.closingTorquePu(30, 0)) == 0, "30 degrees is under the onset");
        double d90 = TurbineBlades.closingBladeDamage(TurbineBlades.closingTorquePu(90, 0));
        double d120 = TurbineBlades.closingBladeDamage(at120);
        Check.isTrue(d90 > 0.2 && d120 > d90 && d120 < 1, "90 degrees hurts (%.2f), 120 hurts more (%.2f) without failing at once", d90, d120);
        Check.isTrue(TurbineBlades.closingTorquePu(-120, 0) == at120, "the sign of the angle does not matter");
        Check.isTrue(TurbineBlades.closingTorquePu(0, 50) > TurbineBlades.SHOCK_BURST_PU, "closing a stopped machine is the worst case");
        Check.isTrue(TurbineBlades.closingTorquePu(170, 0) > TurbineBlades.SHOCK_BURST_PU && TurbineBlades.closingTorquePu(140, 0) < TurbineBlades.SHOCK_BURST_PU,
                "a coupling and disc let go only past about 150 degrees");
        Check.note("closing transient: 10 deg %.2f pu, 90 deg %.2f pu (blades -%.2f), 120 deg %.2f pu (blades -%.2f, shaft -%.2f)", inWindow,
                TurbineBlades.closingTorquePu(90, 0), d90, at120, d120, TurbineBlades.closingShaftDamage(at120));
    }

    public static void test02_motoringHeatsTheHoodAndThenTheBladesFail() {
        double hood = TurbineAux.AMBIENT_C, damage = 0, dt = 0.05;
        double crossed = -1;
        int seconds = 0;
        for (int i = 0; i < 20 * 3600 && damage < 1; i++) {
            hood = TurbineBlades.hoodStepC(hood, TurbineBlades.hoodTargetC(TurbineAux.AMBIENT_C, ShaftDynamics.RATED_RPM, 1), dt);
            damage += TurbineBlades.windageDamage(hood, dt);
            if (crossed < 0 && hood > TurbineBlades.HOOD_DAMAGE_ONSET_C) crossed = i * dt;
            seconds = (int) (i * dt);
        }
        Check.isTrue(crossed > 30 && crossed < 180, "the hood passes the onset in a minute or two: %.0f s", crossed);
        Check.isTrue(seconds > 240 && seconds < 900, "sustained motoring at rated speed fails the blades in minutes: %d s", seconds);
        double steady = TurbineBlades.hoodTargetC(TurbineAux.AMBIENT_C, ShaftDynamics.RATED_RPM, 1);
        Check.isTrue(steady > 160, "a fully motored LP heads for %.0f C", steady);
        Check.exactly(TurbineAux.AMBIENT_C, TurbineBlades.hoodTargetC(TurbineAux.AMBIENT_C, ShaftDynamics.RATED_RPM, 0), "steam doing the work: no windage heat");
        Check.isTrue(TurbineBlades.hoodTargetC(TurbineAux.AMBIENT_C, 750, 1) < TurbineBlades.HOOD_DAMAGE_ONSET_C, "at half speed motoring stays under the onset");
        Check.exactly(0.0, TurbineBlades.windageDamage(TurbineBlades.HOOD_DAMAGE_ONSET_C, 1), "no damage at the onset");
        Check.note("full motoring: hood past %.0f C after %.0f s, blades failed after %d s", TurbineBlades.HOOD_DAMAGE_ONSET_C, crossed, seconds);
    }

    public static void test03_erosionIsTheExistingLaw() {
        Check.exactly(0.0, PowerTurbine.erosionIncrement(1e6, PowerTurbine.DESIGN_MOISTURE), "design moisture does not erode");
        Check.isTrue(Math.abs(PowerTurbine.erosionIncrement(1e6, 0.021) - 1e6 * 0.02 / PowerTurbine.EROSION_LIQUID_KG_TO_FAIL) < 1e-15,
                "the liquid over design over the erosion mass");
    }

    public static void test04_overspeedLoadsTheBladesAndBurstsADisc() {
        Check.exactly(0.0, TurbineBlades.overspeedDamage(1600, 1), "no damage under 110 %");
        Check.isTrue(TurbineBlades.overspeedDamage(1800, 1) > 0, "damage above it");
        Check.isTrue(Math.abs(TurbineBlades.overspeedDamage(1800, 1) - (ShaftDynamics.damage(0, 1800, 1))) < 1e-12, "the same law as the shaft");
        Check.exactly(2250.0, TurbineBlades.BURST_RPM, "a disc bursts at 150 % of rated");
    }

    public static void test05_missileEnergyIsReal() {
        double tip = TurbineBlades.peripheralSpeed(ShaftDynamics.RATED_RPM, TurbineBlades.BLADE_TIP_M);
        Check.isTrue(tip > 500 && tip < 600, "last-stage tip speed %.0f m/s at rated", tip);
        double e = TurbineBlades.missileEnergyJ(TurbineBlades.Missile.BLADE, ShaftDynamics.RATED_RPM);
        Check.isTrue(e > 2e6 && e < 8e6, "a blade thrown at rated speed carries a few MJ: %.2f MJ", e / 1e6);
        double slow = TurbineBlades.missileEnergyJ(TurbineBlades.Missile.BLADE, 750);
        Check.isTrue(Math.abs(slow - e / 4) < 1, "energy goes with the square of speed");
        double disc = TurbineBlades.missileEnergyJ(TurbineBlades.Missile.DISC, TurbineBlades.BURST_RPM);
        Check.isTrue(disc > 20 * e, "a disc fragment at burst speed is much worse: %.0f MJ", disc / 1e6);
        Check.exactly(0.0, TurbineBlades.missileEnergyJ(TurbineBlades.Missile.BLADE, 0), "nothing at standstill");
        double stone = TurbineBlades.perforationJ(BlastPhysics.effectiveRating(5.8 * Math.sqrt(6)));
        Check.isTrue(stone > 1e5 && stone < 2e6, "a stone block costs %.0f kJ", stone / 1e3);
        Check.note("blade at rated: %.0f m/s at its centroid, tip %.0f m/s, %.2f MJ (%.2f MJ out of the casing), about %.0f stone blocks; disc fragment at 150 %%: %.0f MJ",
                TurbineBlades.missileSpeed(TurbineBlades.Missile.BLADE, 1500), tip, e / 1e6,
                TurbineBlades.energyOutsideCasingJ(TurbineBlades.Missile.BLADE, 1500) / 1e6,
                TurbineBlades.energyOutsideCasingJ(TurbineBlades.Missile.BLADE, 1500) / stone, disc / 1e6);
    }

    public static void test06_maintenanceLimitsFitTheCrane() {
        Check.isTrue(TurbineBlades.HOOD_MASS_T < dev.bwr.core.refuelling.PlugLift.RATED_CAPACITY_T, "the hood is within the 125 t crane");
        Check.isTrue(TurbineBlades.BLADE_SECTION_MASS_T < dev.bwr.core.refuelling.PlugLift.RATED_CAPACITY_T, "and so is a blade section");
        Check.isTrue(TurbineBlades.imbalanceMmPerS(1, 1500) > TurbineAux.VIBRATION_DAMAGE_MM_S * 3, "a thrown blade shakes the train hard");
        Check.exactly(0.0, TurbineBlades.imbalanceMmPerS(0, 1500), "no imbalance without one");
    }

    public static void test07_aBreakerClosedOnAStalledShaftMotorsItUpAndShakesItApart() {
        double top = ShaftDynamics.RATED_RPM - TurbineBlades.SYNC_HOLD_RPM;
        Check.exactly(0.0, TurbineBlades.destructRpm(top, 0.5), "a close at the bottom of the window has no destruct speed");
        Check.exactly(0.0, TurbineBlades.destructRpm(1500, 0.5), "nor one at synchronous speed");
        for (double close : new double[] {0, 300, 1000, 1469}) {
            double lo = TurbineBlades.destructRpm(close, 0), hi = TurbineBlades.destructRpm(close, 1), mid = TurbineBlades.destructRpm(close, 0.5);
            Check.isTrue(lo >= Math.max(1, close) - 1e-9 && lo < hi + 1e-9 && Math.abs(hi - top) < 1e-9 && mid > lo - 1e-9 && mid < hi + 1e-9,
                    "the destruct speed runs from the close speed to the window: %.1f %.1f %.1f from %.0f", lo, mid, hi, close);
        }
        // the same 60 s ramp for a small train and a big one: the speed gained per second does not depend on the inertia
        for (double inertia : new double[] {ShaftDynamics.REFERENCE_INERTIA * 0.1, ShaftDynamics.REFERENCE_INERTIA * 0.5, ShaftDynamics.REFERENCE_INERTIA * 3}) {
            double rpm = 0, kj = 0, peak = 0;
            int ticks = 0;
            while (rpm < ShaftDynamics.RATED_RPM - 1e-9 && ticks < 5000) {
                double drive = TurbineBlades.asyncDriveKJ(inertia, rpm, .05);
                peak = Math.max(peak, drive);
                kj += drive;
                rpm = ShaftDynamics.rpm(inertia, ShaftDynamics.energyKJ(inertia, rpm) + drive);
                ticks++;
            }
            Check.isTrue(ticks >= 1195 && ticks <= 1205, "0 to rated in about 60 s (%d ticks) for inertia %.2e", ticks, inertia);
            Check.isTrue(Math.abs(kj - ShaftDynamics.energyKJ(inertia, ShaftDynamics.RATED_RPM)) < 1e-6 * kj, "the grid puts in the whole kinetic energy of the rotor");
            Check.isTrue(peak * 20 / ShaftDynamics.energyKJ(inertia, ShaftDynamics.RATED_RPM) < 0.07, "the peak power is a few percent of the rotor energy a second");
        }
        Check.exactly(0.0, TurbineBlades.asyncDriveKJ(1e6, ShaftDynamics.RATED_RPM, .05), "no pull at rated speed");
        Check.exactly(0.0, TurbineBlades.asyncVibrationMmPerS(500, 0), "no shaking with no destruct speed armed");
        Check.isTrue(TurbineBlades.asyncVibrationMmPerS(300, 600) < TurbineBlades.asyncVibrationMmPerS(450, 600), "the shaking climbs with the speed");
        Check.isTrue(TurbineBlades.asyncVibrationMmPerS(600, 600) > TurbineAux.VIBRATION_DAMAGE_MM_S * 5, "far over the alarm as the shaft arrives at its destruct speed");
        Check.isTrue(TurbineBlades.asyncVibrationMmPerS(2000, 600) == TurbineBlades.asyncVibrationMmPerS(600, 600), "and no more past it");
    }
}
