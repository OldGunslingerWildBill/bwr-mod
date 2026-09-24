package dev.bwr.core.flow;

import dev.bwr.core.thermal.VoidModel;

/** Game-scale hardware ratings, in normal placed jet-assembly equivalents.
 * Volume includes the whole vessel interior. This is a balance rule, not a pump curve. */
public final class RecirculationSizing {
    public static final long BASE_VOLUME = 5L * 8 * 5;
    public static final int BASE_JETS = 12;
    public static final int JETS_PER_EXTERNAL_PUMP = 10;
    public static final double INTERNAL_PUMP_UNITS = 1.2;
    public static final double UNASSISTED_EFFICIENCY = .1;
    public static final double JET_FLOW_KG_PER_S = VoidModel.RATED_CORE_FLOW_KG_PER_S / BASE_JETS;

    private RecirculationSizing() {}

    public record Sizing(long interiorVolume, int requiredJets, int requiredExternalPumps) {
        public double ratedFlowKgPerS() { return requiredJets * JET_FLOW_KG_PER_S; }
    }

    public static Sizing forDimensions(int width, int height, int depth) {
        if (width <= 0 || height <= 0 || depth <= 0) throw new IllegalArgumentException("Vessel dimensions must be positive");
        return forVolume(Math.multiplyExact(Math.multiplyExact((long) width, height), depth));
    }

    public static Sizing forVolume(long volume) {
        if (volume <= 0) throw new IllegalArgumentException("Vessel volume must be positive");
        // Sublinear scaling keeps large-vessel requirements placeable in the annulus.
        // Each increase includes height, and the final target is a whole opposing set.
        double pairs = (BASE_JETS / 2.0) * Math.cbrt(Math.max(1.0, (double) volume / BASE_VOLUME));
        int jets = 2 * (int) Math.ceil(pairs - 1e-10);
        return new Sizing(volume, jets, (jets + JETS_PER_EXTERNAL_PUMP - 1) / JETS_PER_EXTERNAL_PUMP);
    }

    public static double externalCapacityUnits(double jetUnits, int pumps) {
        if (!Double.isFinite(jetUnits) || jetUnits < 0 || pumps < 0) throw new IllegalArgumentException("Invalid recirculation hardware");
        double drive = pumps * (double) JETS_PER_EXTERNAL_PUMP;
        return Math.min(drive, Math.max(jetUnits, drive * UNASSISTED_EFFICIENCY));
    }

    public static double externalDeliveryUnits(double jetUnits, double... speeds) {
        double speedSum = 0;
        for (double speed : speeds) speedSum += Double.isFinite(speed) ? Math.clamp(speed, 0, 1) : 0;
        // Every running drive can serve the shared manifold. A stopped parallel
        // drive must never reduce the flow of the remaining running drives.
        return Math.min(externalCapacityUnits(jetUnits, speeds.length),
                speedSum * externalCapacityUnits(jetUnits, 1));
    }

    /** Invert the actual shared-manifold curve, including its plateau, for equal motor commands. */
    public static double speedForDemand(double fraction, double requiredJets, double jetUnits,
                                       int externalPumps, int internalPumps) {
        if (!Double.isFinite(fraction) || !Double.isFinite(requiredJets) || requiredJets <= 0
                || internalPumps < 0) throw new IllegalArgumentException("Invalid circulation demand");
        double externalCap = externalCapacityUnits(jetUnits, externalPumps);
        double externalGain = externalPumps * externalCapacityUnits(jetUnits, 1);
        double internalGain = internalPumps * INTERNAL_PUMP_UNITS;
        double wanted = Math.min(Math.clamp(fraction, 0, 1) * requiredJets, externalCap + internalGain);
        if (wanted <= 0 || externalGain + internalGain <= 0) return 0;
        double linear = wanted / (externalGain + internalGain);
        if (externalGain * linear <= externalCap) return Math.clamp(linear, 0, 1);
        return internalGain > 0 ? Math.clamp((wanted - externalCap) / internalGain, 0, 1) : 1;
    }
}
