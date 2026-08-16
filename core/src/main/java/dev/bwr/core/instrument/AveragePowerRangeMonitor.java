package dev.bwr.core.instrument;

import java.util.Arrays;

/**
 * Average power range monitor: the percent-of-rated channel, formed by averaging
 * a set of local power range monitor (LPRM) chambers distributed through the
 * core. The top of the instrument ladder, and the only channel that reads in the
 * units the rest of the plant thinks in.
 *
 * <h2>What it actually measures</h2>
 * An APRM does not measure reactor power. It measures the arithmetic mean of the
 * LPRM chambers assigned to it, multiplied by a gain the operator sets from a
 * heat balance. Those are the same number only when the flux shape is the one
 * the gain was set for, when every chamber is reading, and when the calibration
 * is current. Each of those assumptions is modelled here, and each can be false:
 *
 * <pre>
 *   indicated % = 100 * gain * mean over in-service inputs( local flux weight * n )
 *                 + gamma background
 * </pre>
 *
 * <ul>
 *   <li><b>Local flux weights</b> ({@link #setLprmLocalFluxWeight}) come from the
 *       nodal flux shape, normally through
 *       {@link #setLprmLocalFluxWeightsFromLattice} with a chamber assignment
 *       from {@link #chamberLatticePositions}. All 1.0 means a flat core and an
 *       APRM that reads exact percent of rated. A shaped core, a stuck rod or a
 *       bypassed chamber in a hot spot biases the average — the real reason
 *       APRMs and heat balances disagree. <b>A channel nobody feeds weights to
 *       stays flat forever</b>, and a set of channels fed the <i>same</i> weights
 *       stays bit-identical forever, which makes the six-channel redundancy this
 *       class models decorative. Feed them, and feed them differently.</li>
 *   <li><b>Gain</b> ({@link #setGainAdjustmentFactor}) is the calibration the
 *       operator applies after a heat balance. Nothing in this class adjusts it.
 *       An APRM left uncalibrated through a shape change reads wrong and says
 *       nothing about it.</li>
 *   <li><b>Gamma background</b> ({@link #setGammaBackgroundPercent}) is the
 *       fission-product gamma the chambers see with no fission at all. It is why
 *       an APRM on a shut-down core indicates a percent or so rather than zero,
 *       and it is why this channel is worthless at low flux.</li>
 * </ul>
 *
 * <h2>Failed inputs versus bypassed inputs</h2>
 * The averaging network divides by the number of inputs <i>in service</i>, not
 * by the number of inputs still working. So:
 *
 * <ul>
 *   <li>A <b>failed</b> chamber ({@link #setLprmFailed}) contributes zero to the
 *       sum but is still counted in the divisor, dragging the whole channel low.
 *       Failure is silent — like {@link #isChamberFailed()} on the base class, it
 *       is equipment state for the damage model and persistence, not something to
 *       publish to the peripheral.</li>
 *   <li>A <b>bypassed</b> chamber ({@link #setLprmBypassed}) is removed from both
 *       the sum and the divisor. Bypassing is a player action, so the bypass count
 *       is published.</li>
 * </ul>
 *
 * That asymmetry is the entire point of an LPRM bypass switch: an APRM reading
 * low against the heat balance is the symptom, individual LPRM indications
 * ({@link #getLprmIndicatedPercent}) are the diagnosis, and bypassing the dead
 * chamber is the fix. All three steps are the player's.
 *
 * <h2>Degraded inputs are a status, not a trip</h2>
 * {@link #getInServiceLprmInputCount()} is published so a control program can
 * apply whatever "fewer than N inputs" rule the player decides on. This class
 * declares no such N. The single exception is <i>zero</i> inputs in service,
 * which reports {@link DetectorStatus#INOPERATIVE} — an averaging network with
 * nothing to average has no output at all, which is hardware, not a setpoint.
 *
 * <h2>Coverage</h2>
 * On scale from {@link #SCALE_BOTTOM_PERCENT} (3% of rated) to
 * {@link #SCALE_TOP_PERCENT} (125%), overlapping the top half decade of the
 * {@link IntermediateRangeMonitor}, whose range 10 tops out at 10%. Below a few
 * percent the gamma background swamps the fission signal and this channel is
 * lying; that is what the IRM is for. Above full scale the movement runs on to
 * {@link #METER_PEG_PERCENT} and stops there — a pegged APRM says "past 125%"
 * and nothing more precise than that.
 */
public final class AveragePowerRangeMonitor extends NeutronDetector {

    /** Full scale of the percent-of-rated meter. */
    public static final double SCALE_TOP_PERCENT = 125.0;

    /** Bottom of the percent-of-rated meter. Below this the pointer is downscale. */
    public static final double SCALE_BOTTOM_PERCENT = 3.0;

    /**
     * Mechanical stop of the percent-of-rated movement, percent.
     *
     * <h2>The peg is not a reading</h2>
     * The movement runs a little past {@link #SCALE_TOP_PERCENT} and then stops,
     * exactly as {@link IntermediateRangeMonitor#METER_PEG_DIVISIONS} does. This
     * is a stop, not a saturation special case: the channel keeps indicating, it
     * just stops indicating anything useful, and a pegged APRM cannot tell you
     * whether the core is at 130% or at 400%.
     *
     * <p>Without it this channel was silently more honest than its own hardware,
     * which is the inverse of what this package exists to preserve. An MSIV
     * closure with no scram — shipped content, in the harness's pressurisation
     * scenario — runs to several hundred percent of rated, and a player watching
     * the panel would have read a precise four-hundred-percent number off a
     * movement that should be parked against its stop. Deciding what a pegged
     * channel means, and cross-checking it against something else, is the
     * player's job; this class only refuses to invent precision it does not have.
     */
    public static final double METER_PEG_PERCENT = 130.0;

    /**
     * LPRM chambers assigned to one APRM channel. A BWR/6-class core carries 43
     * LPRM strings of four detectors each, apportioned across six APRM channels;
     * two dozen inputs per channel is representative. This is an assignment, not
     * a minimum — there is no minimum in this class.
     */
    public static final int DEFAULT_ASSIGNED_LPRM_INPUTS = 24;

    /**
     * Fission-product gamma seen by the chambers with no fission present,
     * percent of rated equivalent. Default is a static stand-in; drive it from
     * the decay heat model if you want it to decay away after a shutdown.
     */
    public static final double DEFAULT_GAMMA_BACKGROUND_PERCENT = 0.5;

    /** Averaging amplifier time constant, seconds. Short — this channel is the fast one. */
    public static final double DEFAULT_RESPONSE_TIME_CONSTANT_S = 0.1;

    private final boolean[] lprmBypassed;
    private final boolean[] lprmFailed;
    private final double[] lprmLocalFluxWeight;

    private double gainAdjustmentFactor = 1.0;
    private double gammaBackgroundPercent = DEFAULT_GAMMA_BACKGROUND_PERCENT;

    /** Build a channel with the default input assignment, all chambers healthy and in service. */
    public AveragePowerRangeMonitor(String designation) {
        this(designation, DEFAULT_ASSIGNED_LPRM_INPUTS);
    }

    /**
     * @param designation             panel identifier, e.g. "APRM 3"
     * @param assignedLprmInputCount  LPRM chambers wired to this channel, at least one
     */
    public AveragePowerRangeMonitor(String designation, int assignedLprmInputCount) {
        super(designation, DEFAULT_RESPONSE_TIME_CONSTANT_S);
        if (assignedLprmInputCount < 1) {
            throw new IllegalArgumentException(
                    "an APRM channel needs at least one LPRM input, got " + assignedLprmInputCount);
        }
        this.lprmBypassed = new boolean[assignedLprmInputCount];
        this.lprmFailed = new boolean[assignedLprmInputCount];
        this.lprmLocalFluxWeight = new double[assignedLprmInputCount];
        Arrays.fill(this.lprmLocalFluxWeight, 1.0);
    }

    // ------------------------------------------------------------------
    // Response
    // ------------------------------------------------------------------

    @Override
    protected double computeRawSignal(double neutronPowerFraction) {
        int inService = getInServiceLprmInputCount();
        if (inService == 0) {
            return 0.0;
        }
        double sum = 0.0;
        for (int i = 0; i < lprmBypassed.length; i++) {
            if (lprmBypassed[i]) {
                continue; // out of the sum and out of the divisor
            }
            if (lprmFailed[i]) {
                continue; // out of the sum, still in the divisor — reads low
            }
            sum += lprmLocalFluxWeight[i] * neutronPowerFraction;
        }
        double indicated = 100.0 * gainAdjustmentFactor * (sum / inService) + gammaBackgroundPercent;
        // The movement stops at the peg. See METER_PEG_PERCENT: a meter that keeps
        // climbing past its own declared full scale is reporting a precision the
        // hardware does not have.
        return Math.min(indicated, METER_PEG_PERCENT);
    }

    /** Indicated reactor power, percent of rated. What the meter shows. */
    public double getPercentOfRated() {
        return getDetectorSignal();
    }

    @Override
    public String getSignalUnits() {
        return "% rated";
    }

    @Override
    public double getScaleBottomSignal() {
        return SCALE_BOTTOM_PERCENT;
    }

    @Override
    public double getScaleTopSignal() {
        return SCALE_TOP_PERCENT;
    }

    /**
     * Widens {@link DetectorStatus#INOPERATIVE} to cover an averaging network
     * with no inputs in service. Every LPRM bypassed means there is nothing to
     * average and no output to indicate — a property of the hardware, not a
     * threshold anybody chose. Any non-zero number of inputs produces a reading,
     * however degraded, and what to make of that is the player's call.
     */
    @Override
    public DetectorStatus getStatus() {
        if (getInServiceLprmInputCount() == 0) {
            return DetectorStatus.INOPERATIVE;
        }
        return super.getStatus();
    }

    // ------------------------------------------------------------------
    // LPRM inputs
    // ------------------------------------------------------------------

    /** LPRM chambers wired to this channel. */
    public int getAssignedLprmInputCount() {
        return lprmBypassed.length;
    }

    /**
     * LPRM chambers currently feeding the averaging network — assigned minus
     * bypassed. This is the divisor the average is formed over, and the number a
     * control program should watch if the player wants a "fewer than N inputs"
     * rule. A failed chamber that has not been bypassed is still counted here,
     * because the hardware still counts it.
     */
    public int getInServiceLprmInputCount() {
        int count = 0;
        for (boolean bypassed : lprmBypassed) {
            if (!bypassed) {
                count++;
            }
        }
        return count;
    }

    /** LPRM chambers the operator has switched out of the average. */
    public int getBypassedLprmInputCount() {
        return getAssignedLprmInputCount() - getInServiceLprmInputCount();
    }

    /** Whether the operator has switched this input out of the average. */
    public boolean isLprmBypassed(int index) {
        return lprmBypassed[checkIndex(index)];
    }

    /**
     * Switch one LPRM input into or out of the average. A player action, and the
     * only cure for a failed chamber dragging the channel low.
     */
    public void setLprmBypassed(int index, boolean bypassed) {
        lprmBypassed[checkIndex(index)] = bypassed;
    }

    /**
     * Whether this chamber has failed.
     *
     * <p><b>Equipment state, not an instrument reading</b> — the same rule as
     * {@link #isChamberFailed()}. A failed LPRM reads zero and says nothing; the
     * player finds it by comparing {@link #getLprmIndicatedPercent} across the
     * channel. Persist it and drive it from a damage or burnup model, but do not
     * publish it to the CC:Tweaked peripheral.
     */
    public boolean isLprmFailed(int index) {
        return lprmFailed[checkIndex(index)];
    }

    /** @see #isLprmFailed(int) */
    public void setLprmFailed(int index, boolean failed) {
        lprmFailed[checkIndex(index)] = failed;
    }

    /**
     * Local flux at this chamber relative to the core average, 1.0 for a flat
     * core. Fed from the nodal flux shape solve.
     */
    public double getLprmLocalFluxWeight(int index) {
        return lprmLocalFluxWeight[checkIndex(index)];
    }

    /** @see #getLprmLocalFluxWeight(int) */
    public void setLprmLocalFluxWeight(int index, double weight) {
        lprmLocalFluxWeight[checkIndex(index)] = (weight >= 0.0) ? weight : 0.0;
    }

    /** Set every input's local flux weight at once, from a nodal solve sweep. */
    public void setLprmLocalFluxWeights(double[] weights) {
        if (weights == null || weights.length != lprmLocalFluxWeight.length) {
            throw new IllegalArgumentException(
                    "expected " + lprmLocalFluxWeight.length + " local flux weights");
        }
        for (int i = 0; i < weights.length; i++) {
            setLprmLocalFluxWeight(i, weights[i]);
        }
    }

    /**
     * Install this channel's local flux weights by sampling a core-wide power
     * weight map at the lattice positions its chambers sit in.
     *
     * <p>This is the bridge the class comment describes and the one piece of it
     * that was missing: {@code CoreLoading.powerWeights()} — and behind it the
     * nodal diffusion solve — produces a share of core fission power per lattice
     * position, and an LPRM chamber reads the flux at the position it is in. The
     * conversion is a normalisation: each sampled weight divided by the mean
     * weight over the occupied positions, so 1.0 means "this chamber sits in
     * core-average flux" exactly as {@link #getLprmLocalFluxWeight} promises, and
     * a shaped core, a stuck rod or a hot spot moves the channels apart.
     *
     * <p>Call it wherever the nodal shape is re-solved, once per solve interval,
     * with a chamber assignment from {@link #chamberLatticePositions} — different
     * for each channel, or the six channels stay copies of one another and the
     * redundancy the panel implies is decorative.
     *
     * <p>Positions outside the array, and positions holding no fuel (weight zero),
     * are read as core-average rather than as zero: a chamber in an empty lattice
     * cell is not a failed chamber, and {@link #setLprmFailed} is how a chamber
     * that reads nothing is expressed.
     *
     * @param perPositionPowerWeights share of core fission power per lattice
     *                                position, zero at empty positions
     * @param chamberPositions        lattice position of each of this channel's
     *                                chambers, one per assigned input
     */
    public void setLprmLocalFluxWeightsFromLattice(double[] perPositionPowerWeights,
                                                   int[] chamberPositions) {
        if (perPositionPowerWeights == null || chamberPositions == null) {
            throw new IllegalArgumentException("power weights and chamber positions are both required");
        }
        if (chamberPositions.length != lprmLocalFluxWeight.length) {
            throw new IllegalArgumentException("expected " + lprmLocalFluxWeight.length
                    + " chamber positions, got " + chamberPositions.length);
        }

        // Mean over the positions that actually hold fuel: the weights sum to 1
        // over those, so the mean is 1/occupied and a chamber in average flux
        // comes out at exactly 1.0.
        double total = 0.0;
        int occupied = 0;
        for (double weight : perPositionPowerWeights) {
            if (weight > 0.0) {
                total += weight;
                occupied++;
            }
        }
        if (occupied == 0 || !(total > 0.0)) {
            Arrays.fill(lprmLocalFluxWeight, 1.0);
            return;
        }
        double mean = total / occupied;

        for (int i = 0; i < chamberPositions.length; i++) {
            int position = chamberPositions[i];
            double weight = (position >= 0 && position < perPositionPowerWeights.length)
                    ? perPositionPowerWeights[position] : 0.0;
            lprmLocalFluxWeight[i] = (weight > 0.0) ? weight / mean : 1.0;
        }
    }

    /**
     * A deterministic, disjoint chamber assignment for one APRM channel.
     *
     * <p>Real LPRM strings are apportioned so that every APRM channel sees
     * chambers spread across the whole core rather than one quadrant of it —
     * otherwise a channel would be reporting a region, not the core. Dealing the
     * candidate positions round-robin does both jobs at once: each channel's
     * chambers are scattered through the lattice, and no two channels share a
     * chamber until the candidates run out, so the six channels stop being
     * bit-identical copies and start disagreeing about a shaped core the way real
     * ones do.
     *
     * @param channel          this channel's index, 0-based
     * @param channelCount     how many channels the chambers are shared between
     * @param chamberCount     chambers wanted, i.e. this channel's assigned input count
     * @param candidatePositions lattice positions a chamber may sit in — the
     *                           occupied positions of the core, in index order
     * @return {@code chamberCount} lattice positions, or an array of -1 when
     *         there are no candidates at all
     */
    public static int[] chamberLatticePositions(int channel, int channelCount, int chamberCount,
                                                int[] candidatePositions) {
        if (chamberCount < 0) {
            throw new IllegalArgumentException("chamberCount must not be negative, got " + chamberCount);
        }
        int channels = Math.max(1, channelCount);
        int index = Math.max(0, channel) % channels;
        int[] assigned = new int[chamberCount];
        if (candidatePositions == null || candidatePositions.length == 0) {
            Arrays.fill(assigned, -1);
            return assigned;
        }
        for (int i = 0; i < chamberCount; i++) {
            assigned[i] = candidatePositions[(index + i * channels) % candidatePositions.length];
        }
        return assigned;
    }

    /**
     * What one LPRM chamber indicates, percent of rated equivalent. A failed
     * chamber reads zero here, exactly as it does on a real panel — which is how
     * the player identifies it. Unlagged: the chamber is fast, the averaging
     * amplifier behind it is where the time constant lives.
     */
    public double getLprmIndicatedPercent(int index) {
        int i = checkIndex(index);
        if (!isEnergised() || lprmFailed[i]) {
            return 0.0;
        }
        return 100.0 * gainAdjustmentFactor * lprmLocalFluxWeight[i] * neutronPowerFraction
                + gammaBackgroundPercent;
    }

    private int checkIndex(int index) {
        if (index < 0 || index >= lprmBypassed.length) {
            throw new IllegalArgumentException("LPRM input index out of range: " + index
                    + ", channel has " + lprmBypassed.length);
        }
        return index;
    }

    // ------------------------------------------------------------------
    // Calibration
    // ------------------------------------------------------------------

    /**
     * Gain applied to the averaged flux signal, 1.0 as built. The operator sets
     * this from a heat balance; drift between calibrations is a real source of
     * indication error and this class never corrects it on its own.
     */
    public double getGainAdjustmentFactor() {
        return gainAdjustmentFactor;
    }

    /** @see #getGainAdjustmentFactor() */
    public void setGainAdjustmentFactor(double gainAdjustmentFactor) {
        this.gainAdjustmentFactor = (gainAdjustmentFactor >= 0.0) ? gainAdjustmentFactor : 0.0;
    }

    /**
     * Fission-product gamma contribution, percent of rated equivalent. Why an
     * APRM on a shut-down core does not read zero.
     */
    public double getGammaBackgroundPercent() {
        return gammaBackgroundPercent;
    }

    /** @see #getGammaBackgroundPercent() */
    public void setGammaBackgroundPercent(double gammaBackgroundPercent) {
        this.gammaBackgroundPercent = (gammaBackgroundPercent >= 0.0) ? gammaBackgroundPercent : 0.0;
    }

    // ------------------------------------------------------------------
    // Nameplate
    // ------------------------------------------------------------------

    /**
     * Bottom of the meter as a neutron power fraction, 0.03. Design nameplate:
     * the flux at which a correctly calibrated channel with no gamma background
     * would reach scale bottom. Gain adjustment and background shift the real
     * crossing, which is the point.
     */
    @Override
    public double getRangeMinimumNeutronPowerFraction() {
        return SCALE_BOTTOM_PERCENT / 100.0;
    }

    /** Full scale as a neutron power fraction, 1.25. Design nameplate. */
    @Override
    public double getRangeMaximumNeutronPowerFraction() {
        return SCALE_TOP_PERCENT / 100.0;
    }
}
