package dev.bwr.core.boundary;

import dev.bwr.core.PhysicalConstants;

/** A vessel-side opening. Elevation is on the same inch scale as collapsed level.
 * Flow ratings are game calibration, not plant-specific LOCA predictions. */
public record BoundaryOpening(BoundaryComponent component, double elevationIn,
                              double ratedLiquidKgPerS, double ratedSteamKgPerS) {
    public BoundaryOpening {
        if (component == null || !Double.isFinite(elevationIn)
                || !Double.isFinite(ratedLiquidKgPerS) || ratedLiquidKgPerS < 0
                || !Double.isFinite(ratedSteamKgPerS) || ratedSteamKgPerS < 0)
            throw new IllegalArgumentException("Invalid boundary opening");
    }
    public double liquidFlow(double pressurePsig, double levelIn, double density) {
        double headM = Math.max(0, levelIn - elevationIn) * .0254;
        if (headM == 0) return 0;
        // Gauge pressure + hydrostatic head, against an atmospheric exterior.
        double drivingPsi = Math.max(0, pressurePsig + density * 9.80665 * headM / 6894.757);
        return ratedLiquidKgPerS * Math.sqrt(drivingPsi / PhysicalConstants.RATED_DOME_PRESSURE_PSIG);
    }
    public double steamFlow(double pressurePsig, double levelIn) {
        if (pressurePsig <= 0) return 0;
        // A submerged opening passes liquid. As it uncovers, it vents the steam space.
        double exposed = Math.max(0, Math.min(1, (elevationIn - levelIn) / 2.0 + 1));
        double absolute = pressurePsig + PhysicalConstants.ATMOSPHERIC_PSI;
        double reference = PhysicalConstants.RATED_DOME_PRESSURE_PSIG + PhysicalConstants.ATMOSPHERIC_PSI;
        // Choked at high pressure; smooth tail to zero as outside/inside pressures equalize.
        return ratedSteamKgPerS * absolute / reference
                * Math.sqrt(Math.min(1, pressurePsig / PhysicalConstants.ATMOSPHERIC_PSI)) * exposed;
    }
}
