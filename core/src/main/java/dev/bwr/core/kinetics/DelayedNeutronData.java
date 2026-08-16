package dev.bwr.core.kinetics;

/**
 * Keepin six-group delayed neutron data for one fissioning isotope, or for a
 * flux-weighted mixture of several.
 *
 * <p>Immutable value type. Instances are safe to share between reactors and to
 * hold in static fields; every accessor that would expose internal array state
 * returns a copy.
 *
 * <p><b>Why six groups and why per-group.</b> Point kinetics needs both the
 * total delayed fraction and its decay spectrum. The total sets control margin
 * (how much reactivity you can insert before going prompt critical); the
 * spectrum sets how quickly the delayed neutron source responds after a
 * reactivity change. Two fuels with the same total beta but different group
 * spectra behave differently on a scram and on a rod pull, so mixed cores must
 * weight the per-group beta_i by fission rate rather than blending totals.
 * See {@code REFERENCE-DATA.md} section 4 and {@code SPEC.md} section 1.3.
 *
 * <p><b>The plutonium hazard is emergent from this class.</b> Pu-239 has a
 * total delayed fraction of 0.002099 against U-235's 0.006502, a factor of
 * 3.1. Nothing anywhere in this codebase penalises plutonium; the smaller beta
 * simply means a given reactivity insertion buys more dollars, and the
 * integrator does the rest.
 *
 * <p>Units: {@code lambda} in reciprocal seconds, {@code beta} as a bare
 * fraction of total fission neutrons (dimensionless).
 */
public final class DelayedNeutronData {

    /** Number of delayed neutron precursor groups. Keepin's standard six. */
    public static final int GROUP_COUNT = 6;

    private final String isotopeName;
    private final double[] lambdaPerSecond;
    private final double[] betaFraction;
    private final double betaTotalFraction;
    private final double precursorInventoryPerUnitPowerSeconds;

    /**
     * @param isotopeName     label for readouts and debugging; not used by the physics
     * @param lambdaPerSecond per-group precursor decay constants, 1/s, all strictly positive
     * @param betaFraction    per-group delayed neutron fractions, all non-negative
     */
    public DelayedNeutronData(String isotopeName, double[] lambdaPerSecond, double[] betaFraction) {
        if (lambdaPerSecond == null || betaFraction == null) {
            throw new IllegalArgumentException("lambda and beta arrays must not be null");
        }
        if (lambdaPerSecond.length != GROUP_COUNT || betaFraction.length != GROUP_COUNT) {
            throw new IllegalArgumentException(
                    "expected " + GROUP_COUNT + " groups, got lambda[" + lambdaPerSecond.length
                            + "] beta[" + betaFraction.length + "]");
        }
        double total = 0.0;
        double inventory = 0.0;
        for (int i = 0; i < GROUP_COUNT; i++) {
            if (!(lambdaPerSecond[i] > 0.0) || !Double.isFinite(lambdaPerSecond[i])) {
                throw new IllegalArgumentException(
                        "lambda[" + i + "] must be finite and positive, got " + lambdaPerSecond[i]);
            }
            if (!(betaFraction[i] >= 0.0) || !Double.isFinite(betaFraction[i])) {
                throw new IllegalArgumentException(
                        "beta[" + i + "] must be finite and non-negative, got " + betaFraction[i]);
            }
            total += betaFraction[i];
            inventory += betaFraction[i] / lambdaPerSecond[i];
        }
        this.isotopeName = isotopeName == null ? "unnamed" : isotopeName;
        this.lambdaPerSecond = lambdaPerSecond.clone();
        this.betaFraction = betaFraction.clone();
        this.betaTotalFraction = total;
        this.precursorInventoryPerUnitPowerSeconds = inventory;
    }

    // ---------------------------------------------------------------
    // Presets — Keepin thermal fission data [REFERENCE-DATA.md section 4]
    // ---------------------------------------------------------------

    /**
     * U-235 thermal fission. Total beta 0.006502. The reference case: LEU and
     * HEU both use this set, since enrichment changes how much fissile
     * material is present, not the delayed neutron physics of the nuclide.
     */
    public static final DelayedNeutronData U235 = new DelayedNeutronData(
            "U-235",
            new double[]{0.0124, 0.0305, 0.111, 0.301, 1.140, 3.010},
            new double[]{0.000215, 0.001424, 0.001274, 0.002568, 0.000748, 0.000273});

    /**
     * Pu-239 thermal fission. Total beta 0.002099 — 3.1 times smaller than
     * U-235, which is the entire reason a plutonium core is twitchy.
     */
    public static final DelayedNeutronData PU239 = new DelayedNeutronData(
            "Pu-239",
            new double[]{0.0128, 0.0301, 0.124, 0.325, 1.120, 2.690},
            new double[]{0.0000724, 0.000626, 0.000443, 0.000685, 0.000181, 0.000092});

    /**
     * U-233 thermal fission, the bred fissile nuclide of the thorium cycle.
     * Total beta 0.00266.
     *
     * <p>Only the total is quoted authoritatively in {@code REFERENCE-DATA.md};
     * the per-group split here is Keepin's U-233 relative group abundances
     * {0.086, 0.274, 0.227, 0.317, 0.073, 0.023} multiplied by that total, so
     * the group sum reproduces 0.00266 exactly.
     */
    public static final DelayedNeutronData U233 = new DelayedNeutronData(
            "U-233",
            new double[]{0.0126, 0.0337, 0.139, 0.325, 1.130, 2.500},
            new double[]{
                    0.086 * 0.00266, 0.274 * 0.00266, 0.227 * 0.00266,
                    0.317 * 0.00266, 0.073 * 0.00266, 0.023 * 0.00266});

    // ---------------------------------------------------------------
    // Accessors
    // ---------------------------------------------------------------

    /** Label for readouts. Not used by the physics. */
    public String isotopeName() {
        return isotopeName;
    }

    /** Number of precursor groups. Always {@link #GROUP_COUNT}. */
    public int groupCount() {
        return GROUP_COUNT;
    }

    /** Precursor decay constant for one group, 1/s. */
    public double lambdaPerSecond(int group) {
        return lambdaPerSecond[group];
    }

    /** Delayed neutron fraction for one group, dimensionless. */
    public double betaFraction(int group) {
        return betaFraction[group];
    }

    /** Copy of all six decay constants, 1/s. */
    public double[] lambdaPerSecond() {
        return lambdaPerSecond.clone();
    }

    /** Copy of all six group delayed fractions, dimensionless. */
    public double[] betaFraction() {
        return betaFraction.clone();
    }

    /** Sum of the six group fractions — the total delayed neutron fraction. */
    public double betaTotalFraction() {
        return betaTotalFraction;
    }

    /**
     * Sum of beta_i / lambda_i, seconds.
     *
     * <p>Multiplied by the prompt lifetime reciprocal and the power level this
     * gives the steady-state precursor inventory, and it is the quantity that
     * governs how long the delayed neutron source sustains power after a scram.
     * A fuel with the same total beta but a longer-lived spectrum has a larger
     * value here and a slower post-scram decay.
     */
    public double precursorInventoryPerUnitPowerSeconds() {
        return precursorInventoryPerUnitPowerSeconds;
    }

    // ---------------------------------------------------------------
    // Mixtures
    // ---------------------------------------------------------------

    /**
     * Flux-weighted mixture of several isotopes, weighted by fission rate.
     *
     * <p>Per {@code SPEC.md} section 1.3 and {@code REFERENCE-DATA.md} section
     * 4, a mixed core must weight the per-group beta_i by fission rate, not
     * blend the totals, because the group spectra differ between isotopes and
     * not only their sums. A MOX core is built here as a mixture of
     * {@link #U235} and {@link #PU239} with weights equal to the fission rate
     * each nuclide contributes, which lands naturally in the 0.003–0.004 band
     * {@code SPEC.md} section 2.2 expects without anyone choosing that number.
     *
     * <p>Group fractions combine as a plain fission-rate-weighted mean:
     * <pre>beta_i = sum_k(w_k * beta_i,k) / sum_k(w_k)</pre>
     *
     * <p>Group decay constants combine so that both the delayed yield and the
     * steady-state precursor inventory of the mixture equal the sums of the
     * components':
     * <pre>lambda_i = sum_k(w_k * beta_i,k) / sum_k(w_k * beta_i,k / lambda_i,k)</pre>
     * A plain arithmetic mean of lambda would conserve neither, and would get
     * the post-scram decay tail of a mixed core wrong. Where a group's blended
     * fraction is zero the inventory-preserving form is undefined, and a plain
     * weighted mean of lambda is used instead — the group carries no neutrons
     * either way.
     *
     * @param isotopeName        label for the resulting mixture
     * @param components         the isotope data sets, at least one
     * @param fissionRateWeights relative fission rate per component, non-negative,
     *                           not all zero. Absolute scale is irrelevant; only
     *                           ratios matter.
     */
    public static DelayedNeutronData fluxWeightedMixture(
            String isotopeName, DelayedNeutronData[] components, double[] fissionRateWeights) {

        if (components == null || fissionRateWeights == null) {
            throw new IllegalArgumentException("components and weights must not be null");
        }
        if (components.length == 0) {
            throw new IllegalArgumentException("a mixture needs at least one component");
        }
        if (components.length != fissionRateWeights.length) {
            throw new IllegalArgumentException("components[" + components.length
                    + "] and weights[" + fissionRateWeights.length + "] must be the same length");
        }

        double weightSum = 0.0;
        for (int k = 0; k < fissionRateWeights.length; k++) {
            double w = fissionRateWeights[k];
            if (!(w >= 0.0) || !Double.isFinite(w)) {
                throw new IllegalArgumentException(
                        "fissionRateWeights[" + k + "] must be finite and non-negative, got " + w);
            }
            if (components[k] == null) {
                throw new IllegalArgumentException("components[" + k + "] is null");
            }
            weightSum += w;
        }
        if (!(weightSum > 0.0)) {
            throw new IllegalArgumentException("fission rate weights sum to zero; no fissioning nuclide");
        }

        double[] mixedLambda = new double[GROUP_COUNT];
        double[] mixedBeta = new double[GROUP_COUNT];

        for (int i = 0; i < GROUP_COUNT; i++) {
            double yieldSum = 0.0;      // sum_k w_k * beta_i,k
            double inventorySum = 0.0;  // sum_k w_k * beta_i,k / lambda_i,k
            double lambdaWeighted = 0.0;
            for (int k = 0; k < components.length; k++) {
                double w = fissionRateWeights[k];
                double b = components[k].betaFraction[i];
                double l = components[k].lambdaPerSecond[i];
                yieldSum += w * b;
                inventorySum += w * b / l;
                lambdaWeighted += w * l;
            }
            mixedBeta[i] = yieldSum / weightSum;
            mixedLambda[i] = inventorySum > 0.0
                    ? yieldSum / inventorySum
                    : lambdaWeighted / weightSum;
        }
        return new DelayedNeutronData(isotopeName, mixedLambda, mixedBeta);
    }

    /** Flux-weighted mixture with an auto-generated label. */
    public static DelayedNeutronData fluxWeightedMixture(
            DelayedNeutronData[] components, double[] fissionRateWeights) {
        StringBuilder label = new StringBuilder("mix(");
        for (int k = 0; k < (components == null ? 0 : components.length); k++) {
            if (k > 0) {
                label.append('+');
            }
            label.append(components[k] == null ? "null" : components[k].isotopeName);
        }
        return fluxWeightedMixture(label.append(')').toString(), components, fissionRateWeights);
    }

    /**
     * The U-235 / Pu-239 fission rate blend whose total delayed fraction is
     * {@code betaTotalFraction}, with the correct blended group spectrum.
     *
     * <p>This is the bridge for a fuel registry that stores only a scalar beta.
     * Any total between Pu-239's 0.002099 and U-235's 0.006502 is produced by
     * exactly one blend of the two, since the total is linear in the
     * plutonium fission fraction {@code x}:
     * <pre>   beta(x) = (1-x)*beta_U235 + x*beta_Pu239</pre>
     * Inverting for {@code x} and mixing gives real group data instead of a
     * rescaled U-235 spectrum. MOX at 0.0035 resolves to roughly 68% of
     * fissions in plutonium, which is about right for a plutonium-bearing LWR
     * assembly, and its delayed spectrum is genuinely the blend of the two
     * nuclides rather than uranium's shape wearing plutonium's magnitude.
     *
     * <p>Prefer {@link #fluxWeightedMixture} where the actual isotopic fission
     * rates are known; this exists for the case where only the total survives.
     *
     * @param isotopeName       label for the resulting mixture
     * @param betaTotalFraction target total delayed fraction, within
     *                          [0.002099, 0.006502]
     * @throws IllegalArgumentException if no blend of the two nuclides produces
     *                                  that total — use
     *                                  {@link #withTotalBetaFraction} instead
     */
    public static DelayedNeutronData uraniumPlutoniumBlendWithTotalBeta(
            String isotopeName, double betaTotalFraction) {
        double betaU = U235.betaTotalFraction;
        double betaPu = PU239.betaTotalFraction;
        if (!Double.isFinite(betaTotalFraction)
                || betaTotalFraction < betaPu || betaTotalFraction > betaU) {
            throw new IllegalArgumentException("no U-235/Pu-239 blend has a total beta of "
                    + betaTotalFraction + "; the achievable range is [" + betaPu + ", " + betaU
                    + "]. Use withTotalBetaFraction to rescale a spectrum instead.");
        }
        double plutoniumFissionFraction = (betaU - betaTotalFraction) / (betaU - betaPu);
        return fluxWeightedMixture(
                isotopeName,
                new DelayedNeutronData[]{U235, PU239},
                new double[]{1.0 - plutoniumFissionFraction, plutoniumFissionFraction});
    }

    /**
     * Copy of this data set with every group fraction scaled so the total
     * delayed fraction equals {@code betaTotalFraction}, decay constants
     * unchanged.
     *
     * <p>For a datapack fuel entry that supplies only a total beta and no group
     * spectrum, where the fissioning nuclides are not a uranium/plutonium mix.
     * A {@link #fluxWeightedMixture} of the actual nuclides is physically
     * better and should be preferred where the isotopic content is known, and
     * {@link #uraniumPlutoniumBlendWithTotalBeta} is better still for a scalar
     * beta that falls in the U/Pu range.
     */
    public DelayedNeutronData withTotalBetaFraction(String isotopeName, double betaTotalFraction) {
        if (!(betaTotalFraction >= 0.0) || !Double.isFinite(betaTotalFraction)) {
            throw new IllegalArgumentException(
                    "betaTotalFraction must be finite and non-negative, got " + betaTotalFraction);
        }
        if (!(this.betaTotalFraction > 0.0)) {
            throw new IllegalArgumentException("cannot rescale a data set whose total beta is zero");
        }
        double scale = betaTotalFraction / this.betaTotalFraction;
        double[] scaled = new double[GROUP_COUNT];
        for (int i = 0; i < GROUP_COUNT; i++) {
            scaled[i] = this.betaFraction[i] * scale;
        }
        return new DelayedNeutronData(isotopeName, this.lambdaPerSecond, scaled);
    }

    @Override
    public String toString() {
        return "DelayedNeutronData[" + isotopeName + ", beta=" + betaTotalFraction
                + ", groups=" + GROUP_COUNT + "]";
    }
}
