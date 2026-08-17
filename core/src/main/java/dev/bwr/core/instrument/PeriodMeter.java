package dev.bwr.core.instrument;

/**
 * Reactor period meter: a log-derivative amplifier watching one
 * {@link NeutronDetector} channel.
 *
 * <p>Period is the time for flux to change by a factor of e. Positive while the
 * indicated signal is rising, negative while it is falling, infinite when it is
 * steady:
 * <pre>
 *   1 / T  =  d(ln S)/dt        T = period, seconds; S = detector signal
 * </pre>
 *
 * <h2>It reads the detector, never the core</h2>
 * The input is {@link NeutronDetector#getDetectorSignal()} — the conditioned
 * indication of whichever channel is selected — and nothing else. It is
 * deliberately impossible to build one of these on true fission power, and
 * {@code PointKinetics.getPeriodSeconds()} must never be wired to a panel. The
 * consequence is the point:
 *
 * <ul>
 *   <li>A {@link SourceRangeMonitor} past its dead-time peak has a <i>falling</i>
 *       indication while power climbs, so the period meter reads strongly
 *       <b>negative on a core that is running away</b>. That is the real failure
 *       and it has killed reactors.</li>
 *   <li>An {@link IntermediateRangeMonitor} pegged at the top of its range has a
 *       constant indication, so the period reads <b>infinity</b> while power
 *       doubles every few seconds.</li>
 *   <li>A dead channel reads zero forever, so the period reads infinity and looks
 *       perfectly calm.</li>
 * </ul>
 *
 * In every case the meter is faithfully reporting its input. Deciding whether to
 * believe it means cross-checking channels, which is the player's job.
 *
 * <h2>Scale</h2>
 * The movement deflects with {@code 1/T}, so the centre of the scale is zero
 * deflection — an <b>infinite period</b>, i.e. steady flux — and the two ends are
 * the shortest periods the meter can indicate. Marked from
 * {@link #SCALE_LONGEST_MARKED_PERIOD_SECONDS} (100 s, close to centre) out to
 * {@link #SCALE_SHORTEST_PERIOD_SECONDS} (10 s) at each end, positive on one side
 * and negative on the other. Beyond that the pointer is against its stop and
 * {@link #getStatus()} reports {@link NeutronDetector.DetectorStatus#UPSCALE}.
 *
 * <p>There is no downscale condition on this instrument: zero deflection is the
 * middle of the scale, not the bottom of it.
 *
 * <p>No setpoints, no short-period alarm, no rod block. Those are Lua.
 */
public final class PeriodMeter {

    /** Shortest period the scale is marked to, seconds. Both ends of the movement. */
    public static final double SCALE_SHORTEST_PERIOD_SECONDS = 10.0;

    /** Longest marked period, seconds — the division nearest the infinity centre. */
    public static final double SCALE_LONGEST_MARKED_PERIOD_SECONDS = 100.0;

    /**
     * Rail of the log-derivative amplifier, reciprocal seconds. Ten per second is
     * a 0.1 s period: far beyond the printed scale, so the pointer is simply
     * against its stop long before this bites. It exists so a step change in the
     * input cannot produce an unbounded number, in the same way a real amplifier
     * cannot swing past its supply.
     */
    public static final double AMPLIFIER_RAIL_INVERSE_PERIOD_PER_SECOND = 10.0;

    /**
     * Signal below which no logarithm can be formed and the amplifier sits on its
     * bottom rail with zero derivative — an infinite indicated period. This is
     * what a rolled-over SRM or a dead chamber eventually settles into.
     */
    public static final double LOG_AMPLIFIER_FLOOR_SIGNAL = 1.0e-12;

    /**
     * Deflection the movement cannot resolve from dead centre, reciprocal
     * seconds. One ten-thousandth of full-scale deflection, i.e. a period of
     * 1E+05 s — about twenty-eight hours. An exponential filter approaches zero
     * asymptotically and never arrives, so without a resolution limit a steady
     * core would indicate a finite but absurd period like 1E+26 s instead of the
     * infinity a real pointer sitting on the centre line shows.
     */
    public static final double MOVEMENT_RESOLUTION_INVERSE_PERIOD_PER_SECOND = 1.0e-5;

    /** Differentiator time constant, seconds. */
    public static final double DEFAULT_FILTER_TIME_CONSTANT_SECONDS = 1.0;

    private final String designation;
    private NeutronDetector source;
    private double filterTimeConstantSeconds;

    private double inversePeriodPerSecond = 0.0;
    private double previousSignal = 0.0;
    private boolean hasPreviousSample = false;

    /**
     * @param designation panel identifier, e.g. "PERIOD A"
     * @param source      channel this meter is selected to; may be null until one
     *                    is selected, in which case the meter indicates infinite
     *                    period and {@link NeutronDetector.DetectorStatus#INOPERATIVE}
     */
    public PeriodMeter(String designation, NeutronDetector source) {
        this(designation, source, DEFAULT_FILTER_TIME_CONSTANT_SECONDS);
    }

    /**
     * @param designation               panel identifier
     * @param source                    channel this meter is selected to
     * @param filterTimeConstantSeconds differentiator time constant; zero gives an
     *                                  unfiltered, twitchy meter
     */
    public PeriodMeter(String designation, NeutronDetector source, double filterTimeConstantSeconds) {
        this.designation = designation == null ? "" : designation;
        this.source = source;
        this.filterTimeConstantSeconds = Math.max(0.0, filterTimeConstantSeconds);
    }

    // ------------------------------------------------------------------
    // Tick
    // ------------------------------------------------------------------

    /**
     * Advance the meter one step. Call this <i>after</i> the source detector has
     * been updated for the same step, since the meter differentiates the signal
     * as it currently stands.
     *
     * @param dtSeconds step length, seconds; non-positive is a no-op
     */
    public void update(double dtSeconds) {
        if (!(dtSeconds > 0.0) || !Double.isFinite(dtSeconds)) {
            return;
        }
        if (source == null) {
            hasPreviousSample = false;
            inversePeriodPerSecond = 0.0;
            return;
        }

        double signal = source.getDetectorSignal();
        if (!(signal >= 0.0)) {
            signal = 0.0; // also catches NaN
        }

        if (!hasPreviousSample) {
            // First sample after construction, a channel select or a restore.
            // There is no interval to differentiate over yet, so the indication
            // holds where it is for this one step.
            previousSignal = signal;
            hasPreviousSample = true;
            return;
        }

        double raw;
        if (signal <= LOG_AMPLIFIER_FLOOR_SIGNAL || previousSignal <= LOG_AMPLIFIER_FLOOR_SIGNAL) {
            // No logarithm to take. The amplifier sits on its bottom rail with a
            // flat output, which the meter reads as an infinite period.
            raw = 0.0;
        } else {
            raw = Math.log(signal / previousSignal) / dtSeconds;
            if (raw > AMPLIFIER_RAIL_INVERSE_PERIOD_PER_SECOND) {
                raw = AMPLIFIER_RAIL_INVERSE_PERIOD_PER_SECOND;
            } else if (raw < -AMPLIFIER_RAIL_INVERSE_PERIOD_PER_SECOND) {
                raw = -AMPLIFIER_RAIL_INVERSE_PERIOD_PER_SECOND;
            } else if (!Double.isFinite(raw)) {
                raw = 0.0;
            }
        }

        inversePeriodPerSecond = NeutronDetector.firstOrderLag(
                inversePeriodPerSecond, raw, dtSeconds, filterTimeConstantSeconds);
        previousSignal = signal;
    }

    // ------------------------------------------------------------------
    // Indication
    // ------------------------------------------------------------------

    /**
     * Indicated reactor period, seconds. Positive while the selected channel's
     * indication is rising, negative while it is falling, and
     * {@link Double#POSITIVE_INFINITY} when it is steady.
     *
     * <p>Steady state returns positive infinity rather than a signed zero-crossing
     * artefact: both ends of the scale meet at the same physical condition, so the
     * sign there carries no information. Anything the movement cannot resolve from
     * dead centre — see {@link #MOVEMENT_RESOLUTION_INVERSE_PERIOD_PER_SECOND} —
     * counts as steady. Use {@link #getInversePeriodPerSecond()} if you want a
     * quantity that stays finite and continuous through the centre.
     */
    public double getPeriodSeconds() {
        if (!Double.isFinite(inversePeriodPerSecond)
                || Math.abs(inversePeriodPerSecond) < MOVEMENT_RESOLUTION_INVERSE_PERIOD_PER_SECOND) {
            return Double.POSITIVE_INFINITY;
        }
        return 1.0 / inversePeriodPerSecond;
    }

    /**
     * Meter deflection in its natural units, {@code 1/T} per second. Zero is the
     * centre of the scale, positive is rising flux. Finite and continuous
     * everywhere, unlike the period itself.
     */
    public double getInversePeriodPerSecond() {
        return inversePeriodPerSecond;
    }

    /**
     * Startup rate, decades per minute: {@code 60/(T*ln10)}, equivalently
     * {@code 26.06/T}. The unit startup procedures are actually written in.
     */
    public double getStartupRateDecadesPerMinute() {
        return inversePeriodPerSecond * 60.0 / Math.log(10.0);
    }

    /**
     * Pointer position as a fraction of full deflection, -1 to +1, with 0 at the
     * infinity centre and the ends at {@link #SCALE_SHORTEST_PERIOD_SECONDS}.
     * For drawing the movement.
     */
    public double getScaleDeflection() {
        double full = 1.0 / SCALE_SHORTEST_PERIOD_SECONDS;
        double deflection = inversePeriodPerSecond / full;
        if (deflection > 1.0) {
            return 1.0;
        }
        return Math.max(deflection, -1.0);
    }

    /**
     * Where the pointer is.
     *
     * <p>{@link NeutronDetector.DetectorStatus#INOPERATIVE} when no channel is
     * selected or the selected channel declares <i>itself</i> inoperative;
     * {@link NeutronDetector.DetectorStatus#UPSCALE} when the period is shorter
     * than the scale can show, in either direction, because both ends of this
     * movement are its extremes; otherwise
     * {@link NeutronDetector.DetectorStatus#ONSCALE} — including at infinite
     * period, which is the centre of the scale and a perfectly good reading.
     *
     * <p>{@link NeutronDetector.DetectorStatus#DOWNSCALE} never occurs here and
     * that is not an omission: there is no bottom of this scale to fall off.
     */
    public NeutronDetector.DetectorStatus getStatus() {
        // Asks the channel what it thinks of itself rather than testing its high
        // voltage directly. For a SourceRangeMonitor or an IntermediateRangeMonitor
        // those are the same question — neither overrides getStatus(), so its only
        // INOPERATIVE case is exactly the de-energised one this used to test — but
        // an AveragePowerRangeMonitor widens INOPERATIVE to cover an averaging
        // network with every LPRM bypassed. Such a channel is energised and
        // produces no signal, so the amplifier settles on its bottom rail and this
        // meter would otherwise report a serenely infinite period ON SCALE for a
        // channel that has nothing to average. A period meter reading calm is not
        // the same statement as a period meter reading nothing, and on the one
        // instrument that can tell them apart it must not conflate them.
        if (source == null
                || source.getStatus() == NeutronDetector.DetectorStatus.INOPERATIVE) {
            return NeutronDetector.DetectorStatus.INOPERATIVE;
        }
        if (Math.abs(inversePeriodPerSecond) > 1.0 / SCALE_SHORTEST_PERIOD_SECONDS) {
            return NeutronDetector.DetectorStatus.UPSCALE;
        }
        return NeutronDetector.DetectorStatus.ONSCALE;
    }

    /** True when {@link #getStatus()} is on scale. */
    public boolean isOnScale() {
        return getStatus() == NeutronDetector.DetectorStatus.ONSCALE;
    }

    // ------------------------------------------------------------------
    // Channel selection
    // ------------------------------------------------------------------

    /** Panel identifier for this meter. */
    public String getDesignation() {
        return designation;
    }

    /** Channel this meter is currently differentiating, or null if none is selected. */
    public NeutronDetector getSource() {
        return source;
    }

    /** Designation of the selected channel, empty when none is selected. */
    public String getSourceDesignation() {
        return source == null ? "" : source.getDesignation();
    }

    /**
     * Select which channel feeds the meter — an operator action, and one worth
     * getting right: a period meter selected to a rolled-over SRM lies about a
     * rising core.
     *
     * <p>The stored sample is discarded so the first step after a select does not
     * differentiate one channel's signal against another's. The indication itself
     * is kept and relaxes toward the new channel's rate through the normal filter.
     */
    public void setSource(NeutronDetector source) {
        if (this.source != source) {
            this.source = source;
            this.hasPreviousSample = false;
        }
    }

    /** Differentiator time constant, seconds. */
    public double getFilterTimeConstantSeconds() {
        return filterTimeConstantSeconds;
    }

    /** @see #getFilterTimeConstantSeconds() */
    public void setFilterTimeConstantSeconds(double filterTimeConstantSeconds) {
        this.filterTimeConstantSeconds = Math.max(0.0, filterTimeConstantSeconds);
    }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    /**
     * Restore the indication after a chunk reload so the meter resumes
     * mid-transient instead of swinging up from centre. Persistence only.
     */
    public void restoreInversePeriodPerSecond(double inversePeriodPerSecond) {
        this.inversePeriodPerSecond =
                Double.isFinite(inversePeriodPerSecond) ? inversePeriodPerSecond : 0.0;
        this.hasPreviousSample = false;
    }

    /** Park the pointer at the infinity centre and drop the stored sample. */
    public void reset() {
        this.inversePeriodPerSecond = 0.0;
        this.previousSignal = 0.0;
        this.hasPreviousSample = false;
    }
}
