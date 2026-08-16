package dev.bwr.core.eccs;

/**
 * A centrifugal pump's head-flow characteristic.
 *
 * <h2>Shutoff head is physics, not a permissive</h2>
 * A pump develops a differential head that falls as flow rises. At zero flow it
 * develops its maximum — the <b>shutoff head</b> — and it physically cannot push
 * water into a vessel that is at a higher pressure than that. The flow simply
 * goes to zero. This is why the low pressure systems are useless until the
 * vessel has been blown down, and why the ADS handoff is a real decision rather
 * than a formality.
 *
 * <p><b>This is deliberately not an interlock.</b> A real BWR/6 additionally
 * inhibits LPCS and RHR injection below 500 psig [TTC 3.1.3], but that is a
 * chosen number in a chosen protection scheme and it belongs in the player's
 * Lua, not in this model. What lives here is the metal: a pump curve, and a flow
 * that falls smoothly to zero as the discharge pressure approaches the head the
 * impeller can actually produce. Nothing refuses; the water just stops arriving.
 *
 * <h2>The curve</h2>
 * The standard quadratic characteristic:
 *
 * <pre>H = H0 - k*Q^2</pre>
 *
 * where {@code H0} is the shutoff head and {@code k} is fixed by the rated duty
 * point. Two published duty points from a real pump determine both constants
 * exactly, which is where the numbers in {@link EccsDesign} come from rather
 * than from balance guesswork.
 *
 * <h2>Speed</h2>
 * The affinity laws: flow scales with speed, head with speed squared, power with
 * speed cubed. So the curve at part speed is {@code H = s^2*H0 - k*Q^2}, and a
 * half speed pump has a quarter of its shutoff head. That matters — a
 * brownout does not merely slow injection down, it can stop it entirely against
 * a pressurised vessel.
 *
 * <p>Pure Java, no Minecraft imports.
 */
public final class PumpCurve {

    private final double shutoffHeadPsi;
    private final double ratedFlowKgPerS;
    private final double ratedHeadPsi;

    /** {@code k} in {@code H = H0 - k*Q^2}, psi per (kg/s)^2. */
    private final double resistance;

    /**
     * @param shutoffHeadPsi  differential head developed at zero flow, psi
     * @param ratedFlowKgPerS flow at the rated duty point, kg/s
     * @param ratedHeadPsi    differential head at the rated duty point, psi
     */
    public PumpCurve(double shutoffHeadPsi, double ratedFlowKgPerS, double ratedHeadPsi) {
        if (!(shutoffHeadPsi > ratedHeadPsi)) {
            throw new IllegalArgumentException(
                    "shutoff head must exceed the rated head: " + shutoffHeadPsi + " vs " + ratedHeadPsi);
        }
        if (!(ratedFlowKgPerS > 0.0)) {
            throw new IllegalArgumentException("rated flow must be positive: " + ratedFlowKgPerS);
        }
        this.shutoffHeadPsi = shutoffHeadPsi;
        this.ratedFlowKgPerS = ratedFlowKgPerS;
        this.ratedHeadPsi = ratedHeadPsi;
        this.resistance = (shutoffHeadPsi - ratedHeadPsi) / (ratedFlowKgPerS * ratedFlowKgPerS);
    }

    /** Differential head at zero flow and full speed, psi. */
    public double shutoffHeadPsi() {
        return shutoffHeadPsi;
    }

    /** Differential head at zero flow at a given speed, psi. Affinity law, {@code s^2}. */
    public double shutoffHeadPsi(double speedFraction) {
        double s = clampSpeed(speedFraction);
        return shutoffHeadPsi * s * s;
    }

    /** Flow at the rated duty point, kg/s. */
    public double ratedFlowKgPerS() {
        return ratedFlowKgPerS;
    }

    /** Differential head at the rated duty point, psi. */
    public double ratedHeadPsi() {
        return ratedHeadPsi;
    }

    /**
     * Flow the pump delivers against a given differential pressure, kg/s.
     *
     * @param differentialHeadPsi discharge pressure minus suction pressure, psi
     * @param speedFraction       shaft speed, 0 to 1
     * @return kilograms per second, zero once the differential exceeds the head
     *         the impeller can develop at that speed
     */
    public double flowKgPerS(double differentialHeadPsi, double speedFraction) {
        double s = clampSpeed(speedFraction);
        if (s <= 0.0) {
            return 0.0;
        }
        double margin = shutoffHeadPsi * s * s - Math.max(0.0, differentialHeadPsi);
        if (margin <= 0.0) {
            return 0.0;
        }
        return Math.sqrt(margin / resistance);
    }

    /**
     * Head the impeller actually develops while passing a given flow, psi:
     * {@code H = s^2*H0 - k*Q^2}, floored at zero.
     *
     * <h2>This is not the system differential, and the difference is shaft power</h2>
     * A pump held at flow {@code Q} by a discharge flow control valve — which is
     * exactly what {@code EccsPump.setFlowDemandFraction} describes, and what the
     * real 700 gpm RCIC controller does — sits on <i>its own curve</i> at
     * {@code H(Q)}, not at the vessel-minus-suction differential. Whenever the
     * valve is throttling, {@code H(Q)} is strictly the larger of the two and the
     * excess is destroyed across the valve. The impeller still did that work, and
     * the drive still has to pay for it.
     *
     * <p>Charging the drive only the system differential understates shaft power
     * by the ratio {@code H(Q)/dp}, and that ratio is unbounded: at a fully blown
     * down vessel the differential goes to zero while the machine is still moving
     * rated flow against its own rated head. That is precisely the depressurised
     * regime the low pressure systems exist for, so the error is largest exactly
     * where it matters most. Use this, not the differential, as the head a
     * centrifugal machine is charged for.
     *
     * @param flowKgPerS    flow actually being passed, kg/s
     * @param speedFraction shaft speed, 0 to 1
     */
    public double headAtFlowPsi(double flowKgPerS, double speedFraction) {
        double s = clampSpeed(speedFraction);
        double q = (Double.isFinite(flowKgPerS) && flowKgPerS > 0.0) ? flowKgPerS : 0.0;
        return Math.max(0.0, shutoffHeadPsi * s * s - resistance * q * q);
    }

    /**
     * Whether the discharge the pump is being asked to push against exceeds the
     * head it can develop right now.
     *
     * <p>A measurement of two physical quantities — a pressure and a pump curve
     * — and their comparison. It is not a permissive: nothing consults it before
     * allowing the pump to run, and a pump above its shutoff head runs happily
     * and delivers nothing, exactly as the metal would. It exists so a control
     * program can tell "my pump is broken" from "my vessel is too hard", which
     * are the same reading of zero flow.
     */
    public boolean isAboveShutoffHead(double differentialHeadPsi, double speedFraction) {
        return Math.max(0.0, differentialHeadPsi) >= shutoffHeadPsi(speedFraction);
    }

    private static double clampSpeed(double s) {
        if (!Double.isFinite(s) || s <= 0.0) {
            return 0.0;
        }
        return Math.min(1.0, s);
    }

    @Override
    public String toString() {
        return String.format("PumpCurve[shutoff %.0f psi, %.1f kg/s at %.0f psi]",
                shutoffHeadPsi, ratedFlowKgPerS, ratedHeadPsi);
    }
}
