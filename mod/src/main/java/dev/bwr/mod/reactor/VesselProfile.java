package dev.bwr.mod.reactor;

/**
 * The shape of the drawn pressure vessel at one envelope size: the single place the dished heads, the flange, the internals'
 * elevations and the water's inner wall are worked out. The renderer, the CRD necks, the water surface, the port spools, the
 * steam from a failed head and the thrown head all read it (see RPV-REFERENCE.md for the real dimensions behind it).
 *
 * <p>Units are blocks from the bottom of the construction envelope (y) and from its centre (x, z, along the width; the depth is
 * scaled by {@link #ey}). Fractions of the width are written as {@code .448}-style numbers, as the renderer always has.
 *
 * <p>What it keeps from before, on purpose, because the game's rules hang on them: the shell radii (0.46 / 0.448 of the
 * footprint), the bottom and top of the active fuel ({@link VesselInternalsGeometry}), the jet-pump seat at y 1.72, the dome
 * height of the head ({@link #headRise}, the thrown head's height) and so the flange lip {@link #rim} that the cavity seal and
 * the well use. Everything else follows Columbia's proportions: above the core one vertical scale {@link #sUp} (blocks per inch)
 * fits the real stack from the top of the fuel (363 in) to the closure flange (755 in) between the drawn fuel top and the drawn
 * flange; a taller envelope than real proportions need lengthens the separator standpipes rather than stretching anything.
 */
public final class VesselProfile {
    // real elevations, inches above vessel zero (RPV-REFERENCE.md); BAF/TAF are the mod's own
    public static final double IN_BAF = 213, IN_TAF = 363, IN_TOP_GUIDE = 380, IN_LPCI = 420, IN_SHROUD_FLANGE = 440,
            IN_SHROUD_DOME = 465, IN_CORE_SPRAY = 470, IN_FEEDWATER = 493.25, IN_SKIRT_BOTTOM = 527.5, IN_SEP_BODY = 540,
            IN_SEP_TOP_SECTION = 613, IN_SEP_TOP = 632, IN_WATER = 563.55, IN_STEAM = 648, IN_DRYER_RING = 662,
            IN_DRYER_TOP = 745, IN_FLANGE = 755, IN_RECIRC_OUT = 172.5, IN_RECIRC_IN = 181, IN_JP_INSTR = 152;
    public static final double[] IN_SEAMS = {230, 405, 585};
    /** Real inside diameter, in. */
    public static final double IN_DIAMETER = 251;
    /** The lower group (anchored at the top of the fuel) ends here and the upper group (anchored at the flange) begins here, in. */
    static final double LOWER_GROUP_TOP = 470, UPPER_GROUP_BOTTOM = 540;

    public final int width, depth, height;
    public final boolean internalPumps;
    public final double ey;
    /** Shell outer and inner radius along x, and the wall. */
    public final double ro, ri, wall;
    public final double coreTop;
    /** Horizontal and vertical scales, blocks per inch. */
    public final double sH, sUp;
    /** Bottom head: outer and inner apex, outer and inner rise, the knuckle (the head meets the cylinder). */
    public final double y0o, y0i, hbO, hbI, knuckle;
    /** The closure flange's mating face, the open vessel's lip (the old renderer's number), the dome rise and each flange forging's height. */
    public final double flangeFace, rim, headRise, flangeHeight;
    /** Core shroud, upper shroud and core-spray ring radii, fractions of the width. */
    public final double coreShroud, upperShroud, sprayRing;

    private VesselProfile(int width, int depth, int height, boolean internalPumps) {
        this.width = width;
        this.depth = depth;
        this.height = height;
        this.internalPumps = internalPumps;
        ey = depth / (double) width;
        ro = .46 * width;
        ri = .448 * width;
        wall = ro - ri;
        coreTop = VesselInternalsGeometry.CORE_BOTTOM + VesselInternalsGeometry.coreHeight(width, depth, height);
        sH = 2 * ri / IN_DIAMETER;
        int m = Math.min(width, depth);
        headRise = headRise(width, depth, height);
        flangeFace = height - headRise;
        rim = flangeFace - .08;
        flangeHeight = flangeHeight(headRise);
        sUp = Math.min((flangeFace - coreTop) / (IN_FLANGE - IN_TAF), sH);
        y0o = .15;
        y0i = y0o + wall;
        // As deep as the head can be while the annulus floor at the jet-pump seat (y 1.72) is still 0.43 of the width wide.
        double k = .43 / .448;
        hbI = Math.min((1.72 - y0i) / (1 - Math.sqrt(1 - k * k)), .5 * ri);
        hbO = hbI + wall;
        knuckle = y0o + hbO;
        double abwr = dev.bwr.core.fuel.AbwrShroudGeometry.OUTER_RADIUS;
        coreShroud = internalPumps ? abwr : .375;
        upperShroud = internalPumps ? abwr : .418;
        sprayRing = upperShroud - .018;
    }

    public static VesselProfile of(VesselAppearance.Envelope e, boolean internalPumps) {
        return new VesselProfile(e.width(), e.depth(), e.height(), internalPumps);
    }

    public static VesselProfile of(int width, int depth, int height, boolean internalPumps) {
        return new VesselProfile(width, depth, height, internalPumps);
    }

    /** The head's dome height, unchanged since alpha.21: the thrown head, the head access cells and the lip all hang on it. */
    public static double headRise(int width, int depth, int height) {
        return Math.min(height * .22, Math.min(width, depth) * .18);
    }

    /** Each flange forging (the vessel's and the head's): about 26 in of a 125 in head, at least 0.28 block. */
    public static double flangeHeight(double headRise) {
        return Math.min(1.2, Math.max(.28, .21 * headRise));
    }

    /** A real elevation (in above vessel zero) on the drawn vessel. */
    public double up(double inch) {
        if (inch <= IN_BAF) return y0i + inch / IN_BAF * (VesselInternalsGeometry.CORE_BOTTOM - y0i);
        if (inch <= IN_TAF) return VesselInternalsGeometry.CORE_BOTTOM + (inch - IN_BAF) / (IN_TAF - IN_BAF) * (coreTop - VesselInternalsGeometry.CORE_BOTTOM);
        double lower = coreTop + (Math.min(inch, LOWER_GROUP_TOP) - IN_TAF) * sUp;
        if (inch <= LOWER_GROUP_TOP) return lower;
        double upperBottom = flangeFace - (IN_FLANGE - UPPER_GROUP_BOTTOM) * sUp;
        if (inch < UPPER_GROUP_BOTTOM) return lower + (inch - LOWER_GROUP_TOP) / (UPPER_GROUP_BOTTOM - LOWER_GROUP_TOP) * (upperBottom - lower);
        return flangeFace - (IN_FLANGE - inch) * sUp;
    }

    /** The inverse of {@link #up}. */
    public double inchAt(double y) {
        double base = VesselInternalsGeometry.CORE_BOTTOM;
        if (y <= base) return (y - y0i) / (base - y0i) * IN_BAF;
        if (y <= coreTop) return IN_BAF + (y - base) / (coreTop - base) * (IN_TAF - IN_BAF);
        double lowerTop = up(LOWER_GROUP_TOP);
        if (y <= lowerTop) return IN_TAF + (y - coreTop) / sUp;
        double upperBottom = up(UPPER_GROUP_BOTTOM);
        if (y < upperBottom) return LOWER_GROUP_TOP + (y - lowerTop) / (upperBottom - lowerTop) * (UPPER_GROUP_BOTTOM - LOWER_GROUP_TOP);
        return IN_FLANGE - (flangeFace - y) / sUp;
    }

    /** Height of the outer bottom-head surface at a radius that is {@code rFraction} of the outer radius. */
    public double bottomOuterY(double rFraction) {
        return knuckle - hbO * Math.sqrt(Math.max(0, 1 - rFraction * rFraction));
    }

    /** Height of the outer top-head surface at {@code rFraction} of the outer radius. */
    public double headOuterY(double rFraction) {
        return flangeFace + headRise * Math.sqrt(Math.max(0, 1 - rFraction * rFraction));
    }

    /** The outer shell's radius at height y, as a fraction of the cylinder's (1 on the barrel, less on the heads). */
    public double shellSection(double y) {
        if (y < knuckle) return Math.sqrt(Math.max(.04, 1 - Math.pow((knuckle - y) / hbO, 2)));
        if (y > flangeFace) return Math.sqrt(Math.max(.04, 1 - Math.pow((y - flangeFace) / headRise, 2)));
        return 1;
    }

    /** The inner wall's radius at height y as a fraction of the width, drawn slightly inside it (0.442), for the water surface. */
    public double waterRadiusFraction(double y) {
        double inner = knuckle - hbI;
        if (y <= inner) return 0;
        if (y < knuckle) return .442 * Math.sqrt(Math.max(0, 1 - Math.pow((knuckle - y) / hbI, 2)));
        return .442;
    }

    /** Studs on the closure: the real pitch (about 11.2 in, so 74 at every size), 24..92. */
    public int studCount() {
        return (int) Math.max(24, Math.min(92, Math.round(Math.PI * 2 * .472 * width / (11.2 * sH))));
    }

    public double studRadius() {
        return Math.max(.05, 3.0 * sH);
    }

    public double topGuide() {
        return up(IN_TOP_GUIDE);
    }

    public double shroudFlange() {
        return up(IN_SHROUD_FLANGE);
    }

    /** The LPCS (lower) and HPCS (upper) rings inside the upper shroud, between the top guide and the shroud flange. */
    public double sprayRingHeight(boolean hpcs) {
        double top = topGuide(), flange = shroudFlange();
        return top + (hpcs ? .62 : .32) * (flange - top);
    }

    /**
     * The head's lifting point when the crane's strongback is rigged: the hook, above the head's mating face. The spreader sits
     * 0.55 block under it and clears the crown nozzles; four slings run down to the head's lifting lugs.
     */
    public double headLift() {
        return headRise + .9 + .04 * Math.max(width, depth);
    }

    /** Height of the spreader beam of the strongback above the head's mating face (the hook is {@link #STRONGBACK_DROP} higher). */
    public double headSpreaderY() {
        return headLift() - STRONGBACK_DROP;
    }

    public static final double STRONGBACK_DROP = .55;

    /** Radius of the ring of four lifting lugs on the dome, blocks (they stand at 45, 135, 225 and 315 degrees). */
    public double headLugRadius() {
        return .55 * .46 * width;
    }

    /** Height of the top of the studs above the vessel's mating face when the nuts are off, blocks. */
    public double studTop() {
        return flangeHeight + 2.6 * studRadius();
    }

    // the two removable assemblies: their lowest point and their lifting point (the top of the lifting eyes), vessel y

    public double separatorEyeRadius() {
        return Math.max(.12, .022 * width);
    }

    public double separatorBottom() {
        return shroudFlange();
    }

    public double separatorLift() {
        return up(IN_SEP_TOP) + .45 + 2 * separatorEyeRadius() + .05;
    }

    public double dryerEyeRadius() {
        return Math.max(.13, .02 * width);
    }

    /** The bottom of the dryer's lifting eyes: they reach up into the head, where the hold-down brackets meet them. */
    public double dryerEyeY() {
        return Math.min(flangeFace + .55 * (headRise - wall), up(IN_DRYER_TOP) + .9);
    }

    public double dryerBottom() {
        return up(IN_SKIRT_BOTTOM) - .02;
    }

    public double dryerLift() {
        return dryerEyeY() + 2 * dryerEyeRadius() + .04;
    }

    /** Radius of the ring of the dryer's four lifting eyes along x, blocks (they stand at 45, 135, 225 and 315 degrees; along z it is this times {@link #ey}). */
    public double dryerEyeRing() {
        return .427 * width - .15;
    }

    /** Radius of the ring of the separator assembly's four lifting eyes along x, blocks. */
    public double separatorEyeRing() {
        return .55 * upperShroud * width;
    }
}
