package dev.bwr.core.turbine;

/** Energy form of the shaft torque balance. Parameters are game calibration;
 * see GRID-TIE-SPEC section 4.3. No governor, speed clamp or protective action. */
public final class ShaftDynamics {
    public static final double RATED_RPM = 1500;
    public static final double H_SECONDS = 5;
    public static final double BASE_WATTS = 1.5e9;
    public static final double RATED_ENERGY_KJ = H_SECONDS * BASE_WATTS / 1000;
    public static final double REFERENCE_INERTIA = 2 * RATED_ENERGY_KJ * 1000 / Math.pow(RATED_RPM * Math.PI / 30, 2);
    public static final double OVERSPEED_RPM = 1650;

    public record Step(double rpm, double energyKJ, double generatorWorkKJ, double lossKJ) {}
    public record Coupling(double rpm, double lossKJ) {}
    private ShaftDynamics() {}

    private static double nonnegative(double value) {
        if (!Double.isFinite(value) || value < 0) throw new IllegalArgumentException("Nonfinite or negative shaft state");
        return value;
    }
    public static double energyKJ(double inertia, double rpm) {
        return .5 * nonnegative(inertia) * Math.pow(nonnegative(rpm) * Math.PI / 30, 2) / 1000;
    }
    public static double rpm(double inertia, double energyKJ) {
        if (!(inertia > 0) || !Double.isFinite(inertia)) throw new IllegalArgumentException("Invalid inertia");
        return Math.sqrt(2 * nonnegative(energyKJ) * 1000 / inertia) * 30 / Math.PI;
    }
    /** Mechanical coupling conserves angular momentum; speed mismatch becomes heat. */
    public static Coupling couple(double[] inertias, double[] speeds) {
        if (inertias.length != speeds.length || inertias.length == 0) throw new IllegalArgumentException("Empty or mismatched shaft");
        double inertia = 0, momentum = 0, energy = 0;
        for (int i = 0; i < inertias.length; i++) {
            inertia += nonnegative(inertias[i]);
            momentum += inertias[i] * nonnegative(speeds[i]);
            energy += energyKJ(inertias[i], speeds[i]);
        }
        if (!(inertia > 0)) throw new IllegalArgumentException("Zero inertia");
        double speed = momentum / inertia;
        return new Coupling(speed, Math.max(0, energy - energyKJ(inertia, speed)));
    }
    public static Step step(double inertia, double speed, double steamWorkKJ, double requestedGeneratorWorkKJ, double dt) {
        double energy = energyKJ(inertia, speed) + nonnegative(steamWorkKJ);
        nonnegative(dt);
        double loss = Math.min(energy, energyKJ(inertia, speed) / H_SECONDS * .005 * dt);
        double work = Math.min(nonnegative(requestedGeneratorWorkKJ), energy - loss);
        double remaining = Math.max(0, energy - loss - work);
        return new Step(rpm(inertia, remaining), remaining, work, loss);
    }
    public static double damage(double previous, double speed, double dt) {
        return Math.min(1, nonnegative(previous) + .1 * Math.max(0, nonnegative(speed) / OVERSPEED_RPM - 1) * nonnegative(dt));
    }
}
