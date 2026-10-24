package dev.bwr.core.thermal;

/**
 * What the height of a built vessel means to the physics, as four plain numbers that the mod measures off its drawn vessel
 * ({@code VesselProfile}). See {@code VESSEL-HEIGHT.md}.
 *
 * <p>Every length is an <i>equivalent real length</i>, in inches: the drawn length in blocks divided by the horizontal scale
 * of the same vessel (blocks per inch, {@code 2 x 0.448 W / 251 in}). That makes a vessel of any width comparable: a 23 wide
 * vessel drawn with the same proportions as a 17 wide one is the same physical vessel. A vessel drawn at true scale has a
 * {@link #separatorRatio} of 1 and an equivalent length equal to the real one.
 *
 * @param separatorRatio  vertical scale above the core over the horizontal scale, 0..1. 1 is true scale (separators,
 *                        standpipes and dryer as tall as Columbia's, in proportion to its 251 in diameter); a shorter
 *                        vessel squashes everything above the core by this factor. Above true scale the vessel does not
 *                        get a better separator, it gets longer standpipes (see {@link #chimneyColumnIn}).
 * @param chimneyColumnIn the two-phase column above the core, equivalent inches from the top of active fuel to the
 *                        separators' inlet. Real: 177 in (363 to 540). This is what drives natural circulation.
 * @param waterColumnIn   equivalent inches from the top of active fuel to the normal water level. Real: 200.55 in.
 *                        The water in the stretched or squashed part of this column is the build's extra or missing
 *                        inventory.
 * @param domeEquivalentIn the steam space above the normal water level, as inches of full-width cylinder: the
 *                        cylinder from the water to the closure flange plus two thirds of the head's rise (an ellipsoidal
 *                        cap holds two thirds of the cylinder that encloses it).
 */
public record VesselBuild(double separatorRatio, double chimneyColumnIn, double waterColumnIn, double domeEquivalentIn) {

    /**
     * The build the mod's reference vessel (17 x 17 x 28 as the player counts it, a 17 x 17 x 27 envelope) measures to,
     * as {@code VesselProfile} computes it. Natural circulation, the steam dome and the water inventory are
     * <b>exactly</b> their calibrated values at this build; every other height is a ratio to it. A mod GameTest compares
     * this constant with {@code VesselProfile}, so the two cannot drift apart.
     */
    public static final VesselBuild REFERENCE = new VesselBuild(
            0.543957538, 96.280484292, 109.090684321, 137.756742150);

    /**
     * Inches of vessel the rated water inventory stands in: instrument zero (530 in) plus the normal collapsed level
     * (+8 in). Extra equivalent column of water adds this fraction of the calibrated inventory per inch.
     * {@code PressureVessel} derives its level area from the same two numbers.
     */
    public static final double INVENTORY_HEIGHT_IN = 538.0;

    /** Bounds on the inventory factor: a build can neither empty the vessel nor hold three times the rated water. */
    public static final double MIN_WATER_FACTOR = 0.5, MAX_WATER_FACTOR = 3.0;

    public VesselBuild {
        if (!(separatorRatio > 0.0) || !(chimneyColumnIn > 0.0) || !(waterColumnIn > 0.0) || !(domeEquivalentIn > 0.0)
                || !Double.isFinite(separatorRatio) || !Double.isFinite(chimneyColumnIn)
                || !Double.isFinite(waterColumnIn) || !Double.isFinite(domeEquivalentIn)) {
            throw new IllegalArgumentException("a vessel build needs finite positive lengths");
        }
        separatorRatio = Math.min(1.0, separatorRatio);
    }

    /**
     * Equivalent inches of chimney the core's own boiling column is worth to natural circulation. The driving head is the
     * density difference over the whole two-phase column, the core's 150 in of fuel (a mean void of about 0.4, against the
     * chimney's 0.7 or so, so about half as buoyant per inch) as well as the chimney above it. A vessel with no chimney still
     * has its core.
     */
    public static final double CORE_HEAD_EQUIVALENT_IN = 75.0;

    /** The two-phase column (core plus chimney) over the reference build's: 1 at the reference. */
    public double chimneyRatio() {
        return snap((chimneyColumnIn + CORE_HEAD_EQUIVALENT_IN) / (REFERENCE.chimneyColumnIn + CORE_HEAD_EQUIVALENT_IN));
    }

    /** The steam dome over the reference build's, for scaling the calibrated dome volume. 1 at the reference. */
    public double domeRatio() {
        return snap(domeEquivalentIn / REFERENCE.domeEquivalentIn);
    }

    /**
     * The water inventory over the calibrated one: the part of the column above the core that this build stretches or
     * squashes, as a share of the whole 538 in the inventory is calibrated to. 1 at the reference.
     */
    public double waterMassFactor() {
        double f = snap(1.0 + (waterColumnIn - REFERENCE.waterColumnIn) / INVENTORY_HEIGHT_IN);
        return Math.max(MIN_WATER_FACTOR, Math.min(MAX_WATER_FACTOR, f));
    }

    /** A ratio within a millionth of 1 is 1: the mod measures the reference to rounding, and the reference must be exact. */
    private static double snap(double ratio) {
        return Math.abs(ratio - 1.0) < 1e-6 ? 1.0 : ratio;
    }

    /** Whether this is the reference build, within rounding of the constant. */
    public boolean isReference() {
        return chimneyRatio() == 1.0 && domeRatio() == 1.0 && waterMassFactor() == 1.0;
    }
}
