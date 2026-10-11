package dev.bwr.core.accident;

/** Pure containment wall calibration. The world adapter supplies block blast resistance. */
public final class ContainmentWallStrength {
    public static final double DEFAULT_K = 5.8;
    public static final double DEFAULT_PER_BLOCK_CAP_PSIG = 250.0;

    private ContainmentWallStrength() {
    }

    public static double psi(double blastResistance) {
        return psi(blastResistance, DEFAULT_K, DEFAULT_PER_BLOCK_CAP_PSIG);
    }

    public static double psi(double blastResistance, double k, double cap) {
        if (Double.isNaN(blastResistance) || Double.isNaN(k) || Double.isNaN(cap)) return 0.0;
        if (blastResistance <= 0.0 || k <= 0.0 || cap <= 0.0) return 0.0;
        return Math.clamp(k * Math.sqrt(blastResistance), 0.0, cap);
    }
}
