package dev.bwr.mod.panel.component;

/**
 * The controller station's built-in PID, in the velocity (incremental) form, on percentages: the error and the process
 * variable are shares of the scale's span, the output is the station's demand in percent. Pure arithmetic: nothing here
 * touches Minecraft, and it runs only for a station the player has put in AUTO (see {@link ControllerStationData#sampleContent}).
 *
 * <p>Each step changes the demand by
 * <pre>
 *   du = Kp * ( -dPV  +  dt / Ti * e  -  Td / dt * d2PVf )          (the PV and derivative terms and e flip for reverse action)
 * </pre>
 * where {@code e} is the error (SP - PV for direct action, PV - SP for reverse), {@code dPV} the change of the process variable
 * since the last step, and {@code d2PVf} the second difference of the process variable after a first order filter of time constant
 * {@code Td / 8}. The proportional and derivative terms act on the measurement, not on the error, so a setpoint change reaches
 * the output through the integral alone and never kicks it.
 *
 * <p>Because the state is the demand itself, the form is bumpless by construction: entering AUTO starts from the demand MAN left,
 * and leaving it keeps the demand where the PID had it. Clamping the new demand to the output limits is the anti-windup: there is no
 * separate integral to run away, and the output leaves a limit the moment the error changes sign. A process variable without a signal
 * (NaN), a missing step time or a gap longer than {@link #MAX_GAP} freezes the demand and forgets the memory of the last step, so
 * nothing winds up and nothing kicks when the signal returns; the first step after any of those only learns the measurement.
 */
public final class StationPid {
    private StationPid() {
    }

    /** A gap between steps longer than this (seconds: the panel was unloaded, a long lag) restarts the memory instead of integrating it. */
    public static final double MAX_GAP = 5.0;

    /**
     * The next demand, percent, or NaN when the station keeps its demand (no signal, no step time, or the first step after a
     * restart, which only records the process variable).
     *
     * @param d  the station's configuration and transient PID memory
     * @param sp the setpoint in the scale's units
     * @param pv the process variable in the scale's units (NaN: no signal)
     * @param dt seconds since the last step
     */
    public static double step(ControllerStationData d, double sp, double pv, double dt) {
        if (!Double.isFinite(sp) || !Double.isFinite(pv) || !(dt > 0.0) || dt > MAX_GAP) {
            d.pidReady = false;
            return Double.NaN;
        }
        double span = d.span();
        double pvPct = (pv - d.min) / span * 100.0;
        double spPct = (sp - d.min) / span * 100.0;
        double dir = d.reverse ? -1.0 : 1.0;
        if (!d.pidReady) {
            d.pidPv = pvPct;
            d.pidFiltered = pvPct;
            d.pidFiltered1 = pvPct;
            d.pidReady = true;
            return Double.NaN;
        }
        double filtered = d.td > 0.0 ? d.pidFiltered + dt / (d.td / 8.0 + dt) * (pvPct - d.pidFiltered) : pvPct;
        double error = dir * (spPct - pvPct);
        double proportional = -dir * (pvPct - d.pidPv);
        double integral = dt / d.ti * error;
        double derivative = d.td > 0.0 ? -dir * d.td / dt * (filtered - 2.0 * d.pidFiltered + d.pidFiltered1) : 0.0;
        d.pidFiltered1 = d.pidFiltered;
        d.pidFiltered = filtered;
        d.pidPv = pvPct;
        double next = d.demand + d.kp * (proportional + integral + derivative);
        return Math.max(d.outMin, Math.min(d.outMax, next));
    }
}
