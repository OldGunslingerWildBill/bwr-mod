package dev.bwr.mod.reactor;

import dev.bwr.core.thermal.VesselBuild;

/**
 * Measures what the height of a built vessel means to the physics, off the drawn vessel ({@link VesselProfile}), in the
 * terms {@link VesselBuild} wants: equivalent real inches, the drawn length over the same vessel's horizontal scale.
 * See VESSEL-HEIGHT.md. Pure arithmetic on the profile; no world access.
 */
public final class VesselHeightBuild {
    private VesselHeightBuild() {}

    public static VesselBuild of(VesselProfile p) {
        double sh = p.sH;
        // The vertical scale above the core, over the horizontal. 1 once the stack is drawn at Columbia's proportions: a
        // taller envelope then lengthens the standpipes (the chimney), it does not improve the separators.
        double ratio = Math.max(1e-6, Math.min(1.0, p.sUp / sh));
        // Top of the fuel to the separators' inlet (the two-phase column that drives natural circulation).
        double chimney = Math.max(1e-6, (p.up(VesselProfile.IN_SEP_BODY) - p.coreTop) / sh);
        // Top of the fuel to the normal water level (the water column this build stretches or squashes).
        double water = Math.max(1e-6, (p.up(VesselProfile.IN_WATER) - p.coreTop) / sh);
        // Steam space: the cylinder from the water to the flange, plus two thirds of the head's rise (an ellipsoidal cap).
        double dome = Math.max(1e-6, (p.flangeFace - p.up(VesselProfile.IN_WATER)) / sh + (2.0 / 3.0) * p.headRise / sh);
        return new VesselBuild(ratio, chimney, water, dome);
    }

    /** The build of an envelope as the world counts it: outer width and depth, and the height from floor to roof. */
    public static VesselBuild ofEnvelope(int width, int depth, int height) {
        return of(VesselProfile.of(width, depth, height, false));
    }

    public static VesselBuild of(VesselAppearance.Envelope e) {
        return ofEnvelope(e.width(), e.depth(), e.height());
    }

    /** The player-counted height of an envelope: the envelope plus the control rod drive layer under it. */
    public static int playerHeight(int envelopeHeight) {
        return envelopeHeight + 1;
    }
}
