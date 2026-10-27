package dev.bwr.core.turbine;

/**
 * Turbine fires (TURBINE-MAINTENANCE.md): lube oil on hot metal, and a generator's hydrogen let out. Pure functions, game calibration,
 * not a fire model. Nothing here puts a fire out or stops a pump; the player does.
 *
 * <h2>Lube oil</h2>
 * Turbine oil flashes near 200 C and its mist lights on metal at {@link #BEARING_IGNITION_C}. Two ways bring it there:
 * <ul>
 *   <li>a bearing run with no oil film wipes and heats past the ignition point (TurbineAux's bearing law takes about a minute at
 *       rated speed) while the oil left in its housing and drains is still there: {@link #BEARING_SPILL_L} catches;</li>
 *   <li>a piece thrown out of the turbine (a disc fragment, a blade going down through the oil lines under the casing, or the rotor
 *       half of a shaft excursion) cuts an oil line: {@link #LINE_SPILL_L} pours onto the hot casing at once.</li>
 * </ul>
 * The spilled oil burns as a pool at {@link #POOL_BURN_L_PER_S}. While the oil pumps hold pressure the cut line or the wiped bearing
 * keeps spraying more oil into it, {@link #SPRAY_L_PER_S} at the nominal header pressure: a fire fed by the pumps burns as long as the
 * tank has oil. Stopping the pumps starves it (and wipes every other bearing on a turning shaft); water puts it out.
 *
 * <h2>Hydrogen</h2>
 * A shaft excursion breaks the generator's seals: the hydrogen charge escapes and burns at the seals, {@link #H2_BURN_KG_PER_S}, until
 * it is gone.
 *
 * <h2>Damage</h2>
 * A section on fire loses {@link #DAMAGE_PER_S} of its condition a second (bearings, seals, cabling, the lagging): about four minutes
 * of fire wrecks it.
 */
public final class TurbineFire {
    private TurbineFire() {}

    /** Bearing metal temperature at which the oil mist on it lights, C (turbine oils flash near 200 C and light on hot metal from about 300 C). */
    public static final double BEARING_IGNITION_C = 300;
    /** Oil that catches from a wiped bearing's housing and drains, and that a cut line pours out at once, L. */
    public static final double BEARING_SPILL_L = 200, LINE_SPILL_L = 1000;
    /** A burning oil pool's consumption, L/s. */
    public static final double POOL_BURN_L_PER_S = 2.0;
    /** Oil a cut line or a wiped bearing sprays into the fire at the nominal header pressure, L/s. */
    public static final double SPRAY_L_PER_S = 6.0;
    /** A breached generator's hydrogen burning at its seals, kg/s. */
    public static final double H2_BURN_KG_PER_S = 0.2;
    /** Condition a section on fire loses, per second. */
    public static final double DAMAGE_PER_S = 0.004;

    public enum Kind { NONE, OIL, HYDROGEN }

    private static double finite(double v) { return Double.isFinite(v) ? v : 0; }

    /** Whether a bearing this hot, on a shaft that turns, lights the oil on it. */
    public static boolean bearingIgnites(double bearingC, double rpm) {
        return finite(bearingC) >= BEARING_IGNITION_C && finite(rpm) >= TurbineAux.STANDSTILL_RPM;
    }

    /** Oil the running pumps spray into the fire, L in dt seconds, at this header pressure. */
    public static double sprayLitres(double headerPsig, double dt) {
        return SPRAY_L_PER_S * Math.max(0, Math.min(1.2, finite(headerPsig) / TurbineAux.OIL_PSIG_NOMINAL)) * Math.max(0, dt);
    }

    /** The fuel left in an oil pool after dt seconds of burning, L. */
    public static double poolAfter(double litres, double dt) {
        return Math.max(0, finite(litres) - POOL_BURN_L_PER_S * Math.max(0, dt));
    }

    /** The hydrogen left after dt seconds of burning at the seals, kg. */
    public static double hydrogenAfter(double kg, double dt) {
        return Math.max(0, finite(kg) - H2_BURN_KG_PER_S * Math.max(0, dt));
    }

    /** Condition a section on fire loses in dt seconds. */
    public static double damage(double dt) {
        return DAMAGE_PER_S * Math.max(0, dt);
    }
}
