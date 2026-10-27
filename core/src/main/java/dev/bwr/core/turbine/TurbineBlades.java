package dev.bwr.core.turbine;

import dev.bwr.core.accident.HeadMissile;
import dev.bwr.core.grid.Synchronizer;

/**
 * The low-pressure (LP) turbine's blades: what wears and breaks them, and what a broken one does (TURBINE-MAINTENANCE.md). Pure
 * functions, game calibration informed by published turbine numbers, not a manufacturer's curves. Nothing here acts: the mod
 * applies the damage and throws the missile, and nothing ever closes a valve or opens a breaker because of a number here.
 *
 * <h2>The LP rotor</h2>
 * A double-flow LP turbine takes its steam in the middle and expands it to both ends. Its rotor is modelled as two bladed sections,
 * one per flow ({@link #FLOWS}), each the stages of one flow on their discs, each with its own condition from 0 (new) to 1 (failed).
 * A section is the unit the turbine hall crane lifts out and in.
 *
 * <h2>Causes</h2>
 * <ul>
 *   <li><b>Wet steam</b>: the existing erosion law ({@link PowerTurbine#erosionIncrement}), unchanged, now on the LP's blade
 *       sections (both flows see the same steam).</li>
 *   <li><b>Out-of-phase synchronising</b>: closing the generator breaker at a phase angle puts a torque transient through the shaft
 *       train, {@code 2 sin(delta / 2) / X} per unit of rated torque with {@code X = 0.35} (the generator's subtransient, the step-up
 *       transformer and the system in series). That is 0.5 pu inside the synchronising window, 4.0 pu at 90 degrees and 5.0 pu at
 *       120 degrees: the several-times-rated transients the literature gives for a bad close. Damage starts above 2 pu.</li>
 *   <li><b>Reverse power (motoring)</b>: a generator kept on the grid with too little steam is driven by the grid as a motor. The
 *       LP's last stages then churn trapped exhaust steam instead of expanding it, and that windage heats them: the exhaust hood
 *       climbs towards {@link #WINDAGE_RISE_K} over its base at rated speed. Above {@link #HOOD_DAMAGE_ONSET_C} the blades lose
 *       condition.</li>
 *   <li><b>Overspeed</b>: the existing shaft law (0.1 per second per unit over 110 % speed) also loads the long last-stage blades,
 *       and at {@link #BURST_RPM} (150 % of rated) a last-stage disc bursts.</li>
 * </ul>
 *
 * <h2>Missiles</h2>
 * A failed section with the shaft above {@link #MISSILE_MIN_RPM} throws a last-stage blade ({@link #BLADE_MASS_KG} at
 * {@link #BLADE_CENTROID_M} from the axis); a disc burst throws a disc fragment ({@link #DISC_FRAGMENT_KG} at
 * {@link #DISC_CENTROID_M}). The energy is the translational energy of the piece released at its centroid's speed.
 */
public final class TurbineBlades {
    private TurbineBlades() {}

    /** Bladed rotor sections in one double-flow LP turbine, one per flow. */
    public static final int FLOWS = 2;

    // ---- reverse power and windage
    /** Exhaust hood rise over its base at rated speed with the LP fully motored (no steam doing the work), K. */
    public static final double WINDAGE_RISE_K = 150;
    /** Time constant of the exhaust hood and last-stage blade temperature, s. */
    public static final double HOOD_TAU_S = 90;
    /** Exhaust hood temperature above which motoring windage costs the blades condition, C. */
    public static final double HOOD_DAMAGE_ONSET_C = 120;
    /** Condition lost per second for each kelvin the hood is above the onset. */
    public static final double HOOD_DAMAGE_PER_K_S = 5e-5;
    /** How far from rated speed a closed generator is held in step by the grid, rpm (1 Hz of slip at four poles). */
    public static final double SYNC_HOLD_RPM = 30;

    // ---- out-of-phase closing
    /** Generator, transformer and system reactance in series, per unit, for the closing transient. */
    public static final double CLOSE_REACTANCE_PU = 0.35;
    /** Transient torque above which a close damages the turbine train, per unit of rated torque. */
    public static final double SHOCK_ONSET_PU = 2.0;
    /** Transient torque at which an LP coupling and its last-stage disc let go, per unit (a close past about 150 degrees). */
    public static final double SHOCK_BURST_PU = 5.5;

    // ---- overspeed
    /** Speed at which an LP last-stage disc bursts, rpm: 150 % of rated (the design overspeed test is 120 %). */
    public static final double BURST_RPM = 1.5 * ShaftDynamics.RATED_RPM;

    // ---- missiles
    /** Lowest speed at which a failed blade section throws a blade, rpm: half of rated. */
    public static final double MISSILE_MIN_RPM = 0.5 * ShaftDynamics.RATED_RPM;
    /** One last-stage blade of a half-speed LP: mass, kg, its centroid's and its tip's radius, m. */
    public static final double BLADE_MASS_KG = 45, BLADE_CENTROID_M = 2.6, BLADE_TIP_M = 3.5;
    /** A third of a last-stage disc with its blades: mass, kg, and its centroid's radius, m. */
    public static final double DISC_FRAGMENT_KG = 2500, DISC_CENTROID_M = 1.1;
    /** Energy the LP's own casing takes out of a blade and a disc fragment on the way out, J. */
    public static final double BLADE_CASING_J = 1.0e6, DISC_CASING_J = 1.0e7;
    /** A missile's cost to go through one block is this many times the energy a vessel head spends crushing it (a blade is narrow, but it has to cut a hole through the whole block). */
    public static final double PERFORATION_FACTOR = 6;
    /** The least a block costs a missile, J. */
    public static final double MIN_BLOCK_J = 20_000;
    /** Imbalance from one thrown blade, mm/s at rated speed (it grows with the square of speed). */
    public static final double IMBALANCE_MM_S = 45;

    // ---- maintenance
    /** Fastest a shaft may turn for its casing to be opened or a blade section lifted, rpm: stopped, turning gear off. */
    public static final double OPEN_MAX_RPM = 1.0;
    /** Hottest a casing (its rotor metal and its exhaust hood) may be for the hood to come off, C. */
    public static final double OPEN_MAX_C = 100;
    /** Default masses: the LP's upper casing half with its inner casing half (the "hood"), and one bladed rotor section, t. */
    public static final double HOOD_MASS_T = 110, BLADE_SECTION_MASS_T = 62;
    /** A section's condition from which it comes out of a casing as a damaged section, not a spare. */
    public static final double DAMAGED_SECTION = 0.05;

    public enum Missile { BLADE, DISC }

    private static double finite(double v) { return Double.isFinite(v) ? v : 0; }

    // ------------------------------------------------------------------------------------------------ reverse power

    /** The exhaust hood temperature the LP heads for, C: its base (the exhaust steam, or the hall) plus windage while motored. */
    public static double hoodTargetC(double baseC, double rpm, double motoredFraction) {
        double s = Math.max(0, finite(rpm)) / ShaftDynamics.RATED_RPM;
        double m = Math.max(0, Math.min(1, finite(motoredFraction)));
        return Math.max(TurbineAux.AMBIENT_C, finite(baseC)) + WINDAGE_RISE_K * s * s * s * m;
    }

    public static double hoodStepC(double hoodC, double targetC, double dt) {
        return targetC + (finite(hoodC) - targetC) * Math.exp(-Math.max(0, dt) / HOOD_TAU_S);
    }

    /** Condition the blades lose in dt seconds with the hood at this temperature. */
    public static double windageDamage(double hoodC, double dt) {
        return HOOD_DAMAGE_PER_K_S * Math.max(0, finite(hoodC) - HOOD_DAMAGE_ONSET_C) * Math.max(0, dt);
    }

    // ------------------------------------------------------------------------------------------------ out-of-phase

    /**
     * The phase difference the close effectively makes, degrees: the angle itself, or for a close with slip (a machine not near
     * synchronous speed) the angle the slip ratio stands for on the same scale as {@link Synchronizer#closingDamage}, at most 180.
     */
    public static double effectiveAngle(double angleDeg, double slipHz) {
        double a = Math.abs(Synchronizer.wrap(finite(angleDeg)));
        double s = Synchronizer.ANGLE_LIMIT * Math.abs(finite(slipHz)) / Synchronizer.SLIP_LIMIT;
        return Math.min(180, Math.max(a, s));
    }

    /** The shaft train's transient torque from closing at this angle and slip, per unit of rated torque. */
    public static double closingTorquePu(double angleDeg, double slipHz) {
        return 2 * Math.sin(Math.toRadians(effectiveAngle(angleDeg, slipHz)) / 2) / CLOSE_REACTANCE_PU;
    }

    /** Condition an LP blade section loses to a closing transient of this many per unit. */
    public static double closingBladeDamage(double torquePu) {
        double over = finite(torquePu) - SHOCK_ONSET_PU;
        return over <= 0 ? 0 : Math.min(1, 0.12 * Math.pow(over, 1.5));
    }

    /** Condition a turbine section's shaft and couplings lose to a closing transient of this many per unit. */
    public static double closingShaftDamage(double torquePu) {
        double over = finite(torquePu) - SHOCK_ONSET_PU;
        return over <= 0 ? 0 : Math.min(1, 0.06 * over);
    }

    // ------------------------------------------------------------------------------------------------ overspeed

    /** Condition the last-stage blades lose in dt seconds at this speed: the shaft's own overspeed law. */
    public static double overspeedDamage(double rpm, double dt) {
        return 0.1 * Math.max(0, finite(rpm) / ShaftDynamics.OVERSPEED_RPM - 1) * Math.max(0, dt);
    }

    // ------------------------------------------------------------------------------------------------ missiles

    /** The speed of a point this far from the axis, m/s. */
    public static double peripheralSpeed(double rpm, double radiusM) {
        return Math.max(0, finite(rpm)) * Math.PI / 30 * Math.max(0, finite(radiusM));
    }

    public static double missileMassKg(Missile kind) {
        return kind == Missile.DISC ? DISC_FRAGMENT_KG : BLADE_MASS_KG;
    }

    /** The missile's speed when it leaves the rotor, m/s. */
    public static double missileSpeed(Missile kind, double rpm) {
        return peripheralSpeed(rpm, kind == Missile.DISC ? DISC_CENTROID_M : BLADE_CENTROID_M);
    }

    /** The missile's energy when it leaves the rotor, J. */
    public static double missileEnergyJ(Missile kind, double rpm) {
        double v = missileSpeed(kind, rpm);
        return 0.5 * missileMassKg(kind) * v * v;
    }

    /** The missile's energy once it is through the LP's own casing, J. */
    public static double energyOutsideCasingJ(Missile kind, double rpm) {
        return Math.max(0, missileEnergyJ(kind, rpm) - (kind == Missile.DISC ? DISC_CASING_J : BLADE_CASING_J));
    }

    /** What one block of this rating costs a missile to go through, J. */
    public static double perforationJ(double ratingPsi) {
        return Math.max(MIN_BLOCK_J, PERFORATION_FACTOR * HeadMissile.crushJ(ratingPsi));
    }

    /** Shaft vibration from blades thrown, mm/s: {@link #IMBALANCE_MM_S} per thrown blade at rated speed, with the square of speed. */
    public static double imbalanceMmPerS(int bladesThrown, double rpm) {
        double s = Math.max(0, finite(rpm)) / ShaftDynamics.RATED_RPM;
        return IMBALANCE_MM_S * Math.max(0, bladesThrown) * s * s;
    }
}
