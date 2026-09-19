package dev.bwr.core.kinetics;

import dev.bwr.core.CoreConfig;
import dev.bwr.core.PhysicalConstants;

/**
 * Control rod reactivity worth: a differential-worth-per-notch curve integrated
 * over notch positions to give the worth each rod currently holds inserted.
 *
 * <h2>Notch positions, not percentages</h2>
 * A BWR control rod indexes in 25 discrete notches, read out on the Full Core
 * Display as 00 to 48 in steps of two [TTC 2.3.2.12]. Rods are described as
 * "at 48", never "at 100%". This class works in the index space 0..24 that
 * {@code ReactorState.rodNotches} stores, where
 * <b>index 0 is fully inserted and index 24 is fully withdrawn</b>, and
 * {@link #notchLabel} converts to what the panel shows.
 *
 * <h2>Bottom entry and the S-curve</h2>
 * Rods enter from the bottom, so a rod at index {@code k} occupies the lower
 * {@code (24-k)/24} of the core height. Its worth is the integral of the
 * differential worth density over the span it occupies. That density is
 * peaked near mid-core where flux peaks and falls to zero at both ends where
 * there are few neutrons to absorb, so the integral is the classic S-curve:
 * shallow at the extremes, steep in the middle. This is exactly how the NRC
 * manual builds an integral worth curve — by summing per-notch differential
 * worths [TTC 1.7.2.2]. Commands and latched indications use these notches;
 * during travel the physical absorber position lies between them.
 *
 * <p>The density shape is the two-parameter family
 * <pre>   w(z) = z^(a-1) * (1-z)^(b-1),   a = 1 + s*p,  b = 1 + s*(1-p)</pre>
 * peaking at height fraction {@code p} with sharpness {@code s}. Defaults are
 * {@code p = 0.5} and {@code s = 2}, giving {@code w(z) = z(1-z)} — worth
 * peaking exactly mid-core. See {@link #setDifferentialWorthPeakHeightFraction}
 * for the realism knob: a real BWR's upper core is steam-voided and absorbs
 * poorly, so worth peaks somewhat below mid-height.
 *
 * <h2>Per-rod, never ganged</h2>
 * Every method takes a per-rod array. There are no banks. Individual positions
 * are what make rod shadowing, stuck rods and local hot spots visible in the
 * nodal solve ({@code SPEC.md} section 1.3), and {@link #setRodFluxWeights} is
 * the hook by which that solve feeds radial flux shape back in — a central rod
 * is worth more than a peripheral one because it sits in higher flux.
 *
 * <h2>Sign convention</h2>
 * Rod worth is negative reactivity. Inserted worth is negative or zero;
 * withdrawing a notch returns a positive reactivity change.
 *
 * <p>This class computes worth. It does not decide where rods should be, does
 * not move them, and has no concept of a rod pattern being acceptable.
 */
public final class RodWorth {

    /** Notch index of a fully inserted rod. */
    public static final int NOTCH_INDEX_FULLY_INSERTED = 0;

    /** Notch index of a fully withdrawn rod, {@code ROD_NOTCH_POSITIONS - 1} = 24. */
    public static final int NOTCH_INDEX_FULLY_WITHDRAWN = PhysicalConstants.ROD_NOTCH_POSITIONS - 1;

    /** Simpson sub-intervals used per notch when integrating the density. Must be even. */
    private static final int QUADRATURE_INTERVALS_PER_NOTCH = 32;

    private int controlRodCount;
    private double totalRodWorthDkOverK;

    private double differentialWorthPeakHeightFraction = 0.5;
    private double differentialWorthSharpness = 2.0;

    /** Per-rod relative flux weight, mean 1.0. Never null. */
    private double[] rodFluxWeights;

    /**
     * Cumulative normalised worth by insertion index, {@code [0..24]}, where
     * entry {@code j} is the fraction of a rod's total worth held when the rod
     * is inserted {@code j/24} of the way up the core. Entry 0 is 0.0, entry 24
     * is 1.0.
     */
    private final double[] cumulativeWorthShape =
            new double[PhysicalConstants.ROD_NOTCH_POSITIONS];

    /** Integral of the raw density over the core height, for normalising the density accessor. */
    private double rawDensityIntegral;

    /**
     * @param config supplies {@code controlRodCount} and {@code totalRodWorth}.
     *               Values are copied, not held by reference, so later edits to
     *               the config need an explicit setter call here.
     */
    public RodWorth(CoreConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        this.controlRodCount = Math.max(1, config.controlRodCount);
        this.totalRodWorthDkOverK = config.totalRodWorth;
        this.rodFluxWeights = uniformWeights(this.controlRodCount);
        rebuildWorthShape();
    }

    private static double[] uniformWeights(int count) {
        double[] w = new double[count];
        java.util.Arrays.fill(w, 1.0);
        return w;
    }

    // ---------------------------------------------------------------
    // Shape
    // ---------------------------------------------------------------

    /**
     * Differential worth density at a height fraction above the bottom of the
     * active core, before normalisation by total worth.
     *
     * @param heightFraction 0.0 at the bottom of active fuel, 1.0 at the top
     * @return relative absorption worth per unit height, non-negative
     */
    private double rawWorthDensity(double heightFraction) {
        if (heightFraction <= 0.0 || heightFraction >= 1.0) {
            return 0.0;
        }
        double a = 1.0 + differentialWorthSharpness * differentialWorthPeakHeightFraction;
        double b = 1.0 + differentialWorthSharpness * (1.0 - differentialWorthPeakHeightFraction);
        return Math.pow(heightFraction, a - 1.0) * Math.pow(1.0 - heightFraction, b - 1.0);
    }

    /**
     * Differential worth density normalised to unit integral over the core
     * height. Supplied for an axial nodal solve that needs to distribute a
     * rod's worth over axial nodes rather than lump it.
     *
     * @param heightFraction 0.0 at the bottom of active fuel, 1.0 at the top
     */
    public double worthDensityAtHeightFraction(double heightFraction) {
        return rawDensityIntegral > 0.0 ? rawWorthDensity(heightFraction) / rawDensityIntegral : 0.0;
    }

    /**
     * Integrate the density over each notch span by Simpson's rule and
     * accumulate, then normalise so a fully inserted rod holds exactly 1.0.
     */
    private void rebuildWorthShape() {
        final int notches = NOTCH_INDEX_FULLY_WITHDRAWN; // 24 spans between 25 positions
        final double spanHeight = 1.0 / notches;
        final int quadrature = QUADRATURE_INTERVALS_PER_NOTCH;
        final double h = spanHeight / quadrature;

        cumulativeWorthShape[0] = 0.0;
        double running = 0.0;
        for (int span = 0; span < notches; span++) {
            double zStart = span * spanHeight;
            double simpson = rawWorthDensity(zStart) + rawWorthDensity(zStart + spanHeight);
            for (int q = 1; q < quadrature; q++) {
                double z = zStart + q * h;
                simpson += (q % 2 == 1 ? 4.0 : 2.0) * rawWorthDensity(z);
            }
            running += simpson * h / 3.0;
            cumulativeWorthShape[span + 1] = running;
        }
        this.rawDensityIntegral = running;

        if (running > 0.0) {
            for (int j = 0; j <= notches; j++) {
                cumulativeWorthShape[j] /= running;
            }
        }
        // Pin the endpoints against quadrature round-off.
        cumulativeWorthShape[0] = 0.0;
        cumulativeWorthShape[notches] = 1.0;
    }

    /**
     * Fraction of a rod's total worth held at a notch index, 0.0 at fully
     * withdrawn to 1.0 at fully inserted. This is the integral S-curve.
     */
    public double integralWorthShape(int notchIndex) {
        checkNotchIndex(notchIndex);
        return cumulativeWorthShape[NOTCH_INDEX_FULLY_WITHDRAWN - notchIndex];
    }

    /** Continuous worth during travel, preserving every tabulated notch exactly. */
    public double integralWorthShape(double positionNotches) {
        if (!Double.isFinite(positionNotches) || positionNotches < 0.0
                || positionNotches > NOTCH_INDEX_FULLY_WITHDRAWN) {
            throw new IllegalArgumentException("rod position out of range: " + positionNotches);
        }
        int lower = (int) Math.floor(positionNotches);
        double fraction = positionNotches - lower;
        double a = integralWorthShape(lower);
        return fraction == 0.0 ? a : a + (integralWorthShape(lower + 1) - a) * fraction;
    }

    /** The same integral-worth curve evaluated at physical, fractional positions. */
    public double totalInsertedWorthDkOverK(double[] positions) {
        if (positions == null || positions.length != controlRodCount) {
            throw new IllegalArgumentException("one physical position is required per rod");
        }
        double sum = 0.0;
        for (int r = 0; r < controlRodCount; r++) {
            sum += rodFullWorthDkOverK(r) * integralWorthShape(positions[r]);
        }
        return sum;
    }

    // ---------------------------------------------------------------
    // Worth
    // ---------------------------------------------------------------

    /**
     * Reactivity worth one rod holds inserted at its current notch, dk/k.
     * Negative or zero.
     *
     * @param rodIndex   which rod, 0-based
     * @param notchIndex 0 fully inserted to 24 fully withdrawn
     */
    public double insertedWorthDkOverK(int rodIndex, int notchIndex) {
        checkRodIndex(rodIndex);
        return rodFullWorthDkOverK(rodIndex) * integralWorthShape(notchIndex);
    }

    /**
     * Reactivity worth held by every rod summed, dk/k. Negative or zero. This
     * is {@code rho_rods} for {@link ReactivityBalance}.
     *
     * @param notchIndices per-rod notch index, length must equal the rod count
     */
    public double totalInsertedWorthDkOverK(int[] notchIndices) {
        checkNotchArray(notchIndices);
        double sum = 0.0;
        for (int r = 0; r < controlRodCount; r++) {
            sum += rodFullWorthDkOverK(r) * integralWorthShape(notchIndices[r]);
        }
        return sum;
    }

    /**
     * Per-rod inserted worth, dk/k, for the core map and the peripheral
     * readout. Each entry negative or zero.
     */
    public double[] perRodInsertedWorthDkOverK(int[] notchIndices) {
        checkNotchArray(notchIndices);
        double[] worth = new double[controlRodCount];
        for (int r = 0; r < controlRodCount; r++) {
            worth[r] = rodFullWorthDkOverK(r) * integralWorthShape(notchIndices[r]);
        }
        return worth;
    }

    /**
     * Reactivity added by withdrawing one rod a single notch, from
     * {@code notchIndex} to {@code notchIndex + 1}, dk/k. Positive, and zero at
     * the fully withdrawn position where there is nowhere further to go.
     *
     * <p>This is the differential worth per notch the manual tabulates, and the
     * quantity that makes the difference between a safe and an unsafe rod pull
     * legible: the same one-notch pull is worth several times more mid-core
     * than near either end.
     */
    public double differentialWorthPerNotchDkOverK(int rodIndex, int notchIndex) {
        checkRodIndex(rodIndex);
        checkNotchIndex(notchIndex);
        if (notchIndex >= NOTCH_INDEX_FULLY_WITHDRAWN) {
            return 0.0;
        }
        double shapeChange = integralWorthShape(notchIndex) - integralWorthShape(notchIndex + 1);
        return -rodFullWorthDkOverK(rodIndex) * shapeChange;
    }

    /** Worth of one rod at full insertion, dk/k. Negative, scaled by that rod's flux weight. */
    public double rodFullWorthDkOverK(int rodIndex) {
        checkRodIndex(rodIndex);
        return totalRodWorthDkOverK * rodFluxWeights[rodIndex] / controlRodCount;
    }

    // ---------------------------------------------------------------
    // Notch label conversion — what the Full Core Display shows
    // ---------------------------------------------------------------

    /** Convert a 0..24 storage index to the 00..48 even label an operator reads. */
    public static int notchLabel(int notchIndex) {
        if (notchIndex < NOTCH_INDEX_FULLY_INSERTED || notchIndex > NOTCH_INDEX_FULLY_WITHDRAWN) {
            throw new IllegalArgumentException("notch index out of range 0.."
                    + NOTCH_INDEX_FULLY_WITHDRAWN + ": " + notchIndex);
        }
        return notchIndex * PhysicalConstants.ROD_NOTCH_STEP;
    }

    /** Convert a 00..48 even Full Core Display label to the 0..24 storage index. */
    public static int notchIndexFromLabel(int notchLabel) {
        if (notchLabel < 0 || notchLabel > PhysicalConstants.ROD_NOTCH_MAX_LABEL
                || notchLabel % PhysicalConstants.ROD_NOTCH_STEP != 0) {
            throw new IllegalArgumentException("notch label must be even and in 00.."
                    + PhysicalConstants.ROD_NOTCH_MAX_LABEL + ": " + notchLabel);
        }
        return notchLabel / PhysicalConstants.ROD_NOTCH_STEP;
    }

    /** Fraction of the core height a rod at this notch index occupies, 1.0 fully inserted. */
    public static double insertionFraction(int notchIndex) {
        if (notchIndex < NOTCH_INDEX_FULLY_INSERTED || notchIndex > NOTCH_INDEX_FULLY_WITHDRAWN) {
            throw new IllegalArgumentException("notch index out of range 0.."
                    + NOTCH_INDEX_FULLY_WITHDRAWN + ": " + notchIndex);
        }
        return (double) (NOTCH_INDEX_FULLY_WITHDRAWN - notchIndex) / NOTCH_INDEX_FULLY_WITHDRAWN;
    }

    // ---------------------------------------------------------------
    // Configuration
    // ---------------------------------------------------------------

    /** Number of control rods this core has. */
    public int getControlRodCount() {
        return controlRodCount;
    }

    /**
     * Resize the rod array. Flux weights reset to uniform, since a resized core
     * has no meaningful mapping from the old shape.
     */
    public void setControlRodCount(int controlRodCount) {
        if (controlRodCount < 1) {
            throw new IllegalArgumentException("controlRodCount must be at least 1, got " + controlRodCount);
        }
        this.controlRodCount = controlRodCount;
        this.rodFluxWeights = uniformWeights(controlRodCount);
    }

    /** Combined worth of all rods at full insertion, dk/k. Negative. */
    public double getTotalRodWorthDkOverK() {
        return totalRodWorthDkOverK;
    }

    /**
     * Set the combined worth of all rods at full insertion, dk/k. Negative.
     * Falls with burnup and rises with fresh fuel, so a fuel model owns it.
     */
    public void setTotalRodWorthDkOverK(double totalRodWorthDkOverK) {
        if (!Double.isFinite(totalRodWorthDkOverK) || totalRodWorthDkOverK > 0.0) {
            throw new IllegalArgumentException(
                    "total rod worth must be finite and non-positive, got " + totalRodWorthDkOverK);
        }
        this.totalRodWorthDkOverK = totalRodWorthDkOverK;
    }

    /** Copy of the per-rod relative flux weights, mean 1.0. */
    public double[] getRodFluxWeights() {
        return rodFluxWeights.clone();
    }

    /**
     * Set per-rod relative flux weights from the nodal solve. Values are
     * normalised internally to mean 1.0, so the total worth of a fully inserted
     * core is unchanged by the shape — only its distribution between rods.
     *
     * <p>Pass {@code null} to return to uniform weighting. A central rod sitting
     * in peak flux ends up worth several times a peripheral one, which is what
     * makes the loading pattern and the identity of a stuck rod matter.
     *
     * <p>This is the path for weights coming <b>out of a solve</b>. Weights coming
     * back out of a saved game go through {@link #restoreRodFluxWeights} instead,
     * because the renormalisation below is not the identity and a restore has no
     * precision to spend.
     */
    public void setRodFluxWeights(double[] weights) {
        if (weights == null) {
            this.rodFluxWeights = uniformWeights(controlRodCount);
            return;
        }
        if (weights.length != controlRodCount) {
            throw new IllegalArgumentException("flux weight array length " + weights.length
                    + " does not match rod count " + controlRodCount);
        }
        double sum = 0.0;
        for (int r = 0; r < weights.length; r++) {
            if (!(weights[r] >= 0.0) || !Double.isFinite(weights[r])) {
                throw new IllegalArgumentException(
                        "flux weight[" + r + "] must be finite and non-negative, got " + weights[r]);
            }
            sum += weights[r];
        }
        if (!(sum > 0.0)) {
            throw new IllegalArgumentException("flux weights sum to zero");
        }
        double scale = controlRodCount / sum;
        double[] normalised = new double[controlRodCount];
        for (int r = 0; r < controlRodCount; r++) {
            normalised[r] = weights[r] * scale;
        }
        this.rodFluxWeights = normalised;
    }

    /**
     * Install per-rod flux weights <b>verbatim</b>, skipping the renormalisation
     * {@link #setRodFluxWeights} applies. For putting persisted weights back, and
     * for nothing else.
     *
     * <p>{@link #setRodFluxWeights} rescales what it is handed by
     * {@code controlRodCount / sum}. That is right for the solver: a nodal solve
     * publishes a <i>shape</i>, not a magnitude, and pinning the mean at 1.0 is
     * what keeps a fully inserted core worth exactly {@code totalRodWorth} however
     * the shape moves worth between rods. It is wrong for a restore, because the
     * rescale is not the identity in binary floating point — {@code set(get(x))}
     * moves every one of 177 weights in its last bits, so an array put back
     * through it lands <em>near</em> where it was rather than on it, and
     * {@link #totalInsertedWorthDkOverK} then sums 177 near-misses. Persistence is
     * required to be bit-exact ({@code SPEC.md} section 11), so the arithmetic has
     * to not happen rather than merely be small.
     *
     * <p>What is skipped is the arithmetic, not the checking: an array that could
     * not have come from a solve is refused here exactly as loudly as it would be
     * there. Weights this model writes are already mean 1.0, having been
     * normalised on the way in; this trusts that rather than redoing it, and
     * redoing it is precisely the bug.
     *
     * @param weights one weight per rod, each finite and non-negative, summing
     *                above zero. Not defensively aliased — a copy is taken.
     */
    public void restoreRodFluxWeights(double[] weights) {
        if (weights == null) {
            throw new IllegalArgumentException("flux weights to restore must not be null");
        }
        if (weights.length != controlRodCount) {
            throw new IllegalArgumentException("flux weight array length " + weights.length
                    + " does not match rod count " + controlRodCount);
        }
        double sum = 0.0;
        for (int r = 0; r < weights.length; r++) {
            if (!(weights[r] >= 0.0) || !Double.isFinite(weights[r])) {
                throw new IllegalArgumentException(
                        "flux weight[" + r + "] must be finite and non-negative, got " + weights[r]);
            }
            sum += weights[r];
        }
        if (!(sum > 0.0)) {
            throw new IllegalArgumentException("flux weights sum to zero");
        }
        this.rodFluxWeights = weights.clone();
    }

    /** Height fraction at which differential worth per notch peaks. */
    public double getDifferentialWorthPeakHeightFraction() {
        return differentialWorthPeakHeightFraction;
    }

    /**
     * Move the peak of the differential worth curve, as a fraction of core
     * height above the bottom of active fuel. Default 0.5, mid-core.
     *
     * <p>Set below 0.5 for a more realistic bottom-entry BWR shape: the top of
     * a boiling core is heavily voided, there is less moderator and therefore
     * less thermal flux up there, and an absorber inserted into that region
     * earns less than the same absorber mid-core ({@code SPEC.md} section 3.1).
     * The shape also drifts through a cycle as the axial power profile does.
     */
    public void setDifferentialWorthPeakHeightFraction(double peakHeightFraction) {
        if (!(peakHeightFraction > 0.0) || !(peakHeightFraction < 1.0)) {
            throw new IllegalArgumentException(
                    "peak height fraction must lie strictly between 0 and 1, got " + peakHeightFraction);
        }
        this.differentialWorthPeakHeightFraction = peakHeightFraction;
        rebuildWorthShape();
    }

    /** Sharpness of the differential worth peak. */
    public double getDifferentialWorthSharpness() {
        return differentialWorthSharpness;
    }

    /**
     * Set how sharply the differential worth curve peaks. Default 2.0, which
     * gives {@code w(z) = z(1-z)} at a mid-core peak. Larger values concentrate
     * worth near the peak and flatten the ends, steepening the middle of the
     * S-curve; values approaching zero tend toward uniform worth per notch and
     * remove the S entirely.
     */
    public void setDifferentialWorthSharpness(double sharpness) {
        if (!(sharpness > 0.0) || !Double.isFinite(sharpness)) {
            throw new IllegalArgumentException("sharpness must be finite and positive, got " + sharpness);
        }
        this.differentialWorthSharpness = sharpness;
        rebuildWorthShape();
    }

    // ---------------------------------------------------------------

    private void checkRodIndex(int rodIndex) {
        if (rodIndex < 0 || rodIndex >= controlRodCount) {
            throw new IllegalArgumentException(
                    "rod index out of range 0.." + (controlRodCount - 1) + ": " + rodIndex);
        }
    }

    private void checkNotchIndex(int notchIndex) {
        if (notchIndex < NOTCH_INDEX_FULLY_INSERTED || notchIndex > NOTCH_INDEX_FULLY_WITHDRAWN) {
            throw new IllegalArgumentException("notch index out of range 0.."
                    + NOTCH_INDEX_FULLY_WITHDRAWN + ": " + notchIndex);
        }
    }

    private void checkNotchArray(int[] notchIndices) {
        if (notchIndices == null) {
            throw new IllegalArgumentException("notch index array must not be null");
        }
        if (notchIndices.length != controlRodCount) {
            throw new IllegalArgumentException("notch array length " + notchIndices.length
                    + " does not match rod count " + controlRodCount
                    + "; reconcile the core size before computing worth");
        }
        for (int r = 0; r < notchIndices.length; r++) {
            if (notchIndices[r] < NOTCH_INDEX_FULLY_INSERTED
                    || notchIndices[r] > NOTCH_INDEX_FULLY_WITHDRAWN) {
                throw new IllegalArgumentException("rod " + r + " notch index out of range 0.."
                        + NOTCH_INDEX_FULLY_WITHDRAWN + ": " + notchIndices[r]);
            }
        }
    }
}
