package dev.bwr.core.chemistry;

import dev.bwr.core.eccs.PumpCurve;
import dev.bwr.core.eccs.PumpDesign;

/**
 * The RWCU pump nameplate: an electric centrifugal pump, 950 gpm (about 60 kg/s) at 130 psid, shutoff 200 psi. The
 * differential is the loop's: suction and discharge are both at reactor pressure, so the pump only works against the
 * exchangers, the demineraliser and the pipe. Motor 120 kW. Pure Java.
 */
public final class RwcuPumpDesign implements PumpDesign {
    public static final RwcuPumpDesign INSTANCE = new RwcuPumpDesign();
    /** Differential the loop presents at rated flow, psi (the pump's duty point). */
    public static final double LOOP_DIFFERENTIAL_PSI = 130.0;
    private final PumpCurve curve = new PumpCurve(200.0, PumpDesign.kgPerSecondFromGpm(950.0), LOOP_DIFFERENTIAL_PSI);

    private RwcuPumpDesign() {}

    /**
     * The loop's system-curve coefficient, psid per (kg/s)^2, with {@code extraPsiAtRated} psid more at rated flow than the
     * skid's own (a loaded high-temperature filter, say).
     */
    public static double systemCoefficient(double extraPsiAtRated) {
        double rated = INSTANCE.ratedFlowKgPerS();
        return (LOOP_DIFFERENTIAL_PSI + Math.max(0, extraPsiAtRated)) / (rated * rated);
    }

    /** The flow the pump settles at, kg/s, at speed fraction {@code speed} against the skid plus {@code extraPsiAtRated}. */
    public static double loopFlowKgPerS(double speed, double extraPsiAtRated) {
        double rated = INSTANCE.ratedFlowKgPerS();
        double shutoff = INSTANCE.curve().shutoffHeadPsi();
        double k = (shutoff - LOOP_DIFFERENTIAL_PSI) / (rated * rated);
        double s = Math.min(1.0, Math.max(0.0, speed));
        return s * Math.sqrt(shutoff / (k + systemCoefficient(extraPsiAtRated)));
    }

    @Override public String id() { return "rwcu"; }
    @Override public String displayName() { return "RWCU pump"; }
    @Override public Drive drive() { return Drive.ELECTRIC_MOTOR; }
    @Override public PumpCurve curve() { return curve; }
    @Override public boolean isConstantDisplacement() { return false; }
    @Override public double ratedFlowKgPerS() { return curve.ratedFlowKgPerS(); }
    @Override public double maximumDischargePsi() { return curve.shutoffHeadPsi(); }
    @Override public double pumpEfficiency() { return 0.70; }
    @Override public double motorRatingWatts() { return 120_000.0; }
    @Override public double maximumSteamKgPerS() { return 0; }
    @Override public double turbineEfficiency() { return 0; }
    @Override public double windageFractionOfRatedShaft() { return 0.15; }
    @Override public double startupSeconds() { return 8.0; }
    @Override public double coastdownSeconds() { return 6.0; }
    @Override public double boronPpmPerMinuteAtRatedFlow() { return 0; }
    @Override public double ratedShaftPowerWatts() {
        return PumpDesign.hydraulicPowerWatts(ratedFlowKgPerS(), LOOP_DIFFERENTIAL_PSI) / pumpEfficiency();
    }
}
