package dev.bwr.core.instrument;

/**
 * Base class for one nuclear instrument channel.
 *
 * <p>Neutron flux in this plant spans roughly twelve decades between a shutdown
 * core sitting on its startup source and rated power. No single detector can
 * read that, which is why three types exist: {@link SourceRangeMonitor},
 * {@link IntermediateRangeMonitor} and {@link AveragePowerRangeMonitor}. Each
 * covers a band, each saturates outside it, and the bands overlap so there is
 * never a flux at which no instrument reads true.
 *
 * <p><b>These classes are the player's only window into the neutronics.</b>
 * Everything here is a measurement or an actuator. Nothing here is a judgement:
 * there are no setpoints, no trips, no permissives and no auto-ranging anywhere
 * in this package. A detector reports what a real instrument would report,
 * <i>including when that is misleading</i>. Distinguishing a saturated channel
 * from a dead one is the player's job, done by cross-checking channels, not by
 * asking the mod.
 *
 * <h2>Signal chain</h2>
 * <pre>
 *   neutron power fraction  --&gt;  computeRawSignal()  --&gt;  first-order lag  --&gt;  detector signal
 * </pre>
 * {@code computeRawSignal} is where the physics of each detector type lives
 * (pulse pile-up for the SRM, range attenuation for the IRM, LPRM averaging for
 * the APRM). The lag afterwards is the signal-conditioning time constant every
 * real count-rate meter and current amplifier has; it is what stops the
 * indication and the derived period from being instantaneous.
 *
 * <h2>Two different kinds of "dead"</h2>
 * <ul>
 *   <li>{@link #setEnergised(boolean) De-energised} — chamber high voltage lost.
 *       Real instruments monitor their own HV, so this is self-declared and
 *       shows up as {@link DetectorStatus#INOPERATIVE}.</li>
 *   <li>{@link #setChamberFailed(boolean) Chamber failed} — the chamber, cable
 *       or preamp is broken. This is <i>silent</i>. The channel reads zero and
 *       claims to be perfectly healthy, exactly like the real failure. Do not
 *       publish {@link #isChamberFailed()} to the CC:Tweaked peripheral; it is
 *       equipment state for persistence and for the block model, not an
 *       instrument reading.</li>
 * </ul>
 *
 * <p>Pure Java. No Minecraft imports, no ambiguous doubles: every value carries
 * its unit in its name.
 */
public abstract class NeutronDetector {

    /**
     * What the meter movement is doing. This is an indication state, not a
     * protective action — "upscale" means the pointer is past the end of the
     * scale, and nothing in this mod acts on it.
     */
    public enum DetectorStatus {
        /** Channel cannot produce a valid indication: high voltage absent. */
        INOPERATIVE,
        /** Signal is below the bottom of the scale. */
        DOWNSCALE,
        /** Signal is on the scale and readable. */
        ONSCALE,
        /** Signal is past the top of the scale. */
        UPSCALE
    }

    private final String designation;
    private double responseTimeConstantSeconds;

    private boolean energised = true;
    private boolean chamberFailed = false;

    /**
     * Last neutron power fraction handed to {@link #update}, 1.0 = rated.
     *
     * <p>Protected on purpose. This is the true core condition; a detector may
     * use it to compute what it sees, but no public getter exposes it, because
     * handing the player true power would defeat every instrument in this
     * package.
     */
    protected double neutronPowerFraction = 0.0;

    private double detectorSignal = 0.0;
    private double elapsedSeconds = 0.0;

    /**
     * @param designation      panel identifier, e.g. "SRM A" or "IRM C"
     * @param responseTimeConstantSeconds signal-conditioning time constant of
     *                         the count-rate meter or current amplifier. Zero
     *                         gives an instantaneous, unfiltered channel.
     */
    protected NeutronDetector(String designation, double responseTimeConstantSeconds) {
        this.designation = designation == null ? "" : designation;
        this.responseTimeConstantSeconds = Math.max(0.0, responseTimeConstantSeconds);
    }

    // ------------------------------------------------------------------
    // Tick
    // ------------------------------------------------------------------

    /**
     * Advance the channel one step.
     *
     * <p>Call this once per tick for every detector, then update any
     * {@link PeriodMeter} that reads from one — period meters sample the
     * detector signal as it stands, so ordering matters.
     *
     * @param neutronPowerFraction true fission power, fraction of rated
     * @param dtSeconds            step length, seconds
     */
    public final void update(double neutronPowerFraction, double dtSeconds) {
        double n = neutronPowerFraction;
        if (!(n >= 0.0)) {
            n = 0.0; // also catches NaN
        }
        this.neutronPowerFraction = n;

        if (!(dtSeconds > 0.0)) {
            return;
        }
        this.elapsedSeconds += dtSeconds;

        double target = (energised && !chamberFailed) ? computeRawSignal(n) : 0.0;
        if (!(target >= 0.0)) {
            target = 0.0;
        }
        this.detectorSignal =
                firstOrderLag(this.detectorSignal, target, dtSeconds, responseTimeConstantSeconds);
    }

    /**
     * Unfiltered detector response to the given flux, in this channel's own
     * signal units. Implementations put their physics here.
     *
     * @param neutronPowerFraction true fission power, fraction of rated
     * @return raw signal, non-negative, in {@link #getSignalUnits()}
     */
    protected abstract double computeRawSignal(double neutronPowerFraction);

    /**
     * Exact first-order lag. Uses {@code 1 - exp(-dt/tau)} rather than
     * {@code dt/tau} so it stays stable and correct for any step length,
     * including steps longer than the time constant.
     */
    protected static double firstOrderLag(double current, double target,
                                          double dtSeconds, double tauSeconds) {
        if (tauSeconds <= 0.0) {
            return target;
        }
        double alpha = 1.0 - Math.exp(-dtSeconds / tauSeconds);
        return current + (target - current) * alpha;
    }

    // ------------------------------------------------------------------
    // Indication
    // ------------------------------------------------------------------

    /**
     * Conditioned output of this channel, in {@link #getSignalUnits()}. This is
     * what the meter movement shows and what a {@link PeriodMeter} differentiates.
     */
    public double getDetectorSignal() {
        return detectorSignal;
    }

    /** Units of {@link #getDetectorSignal()}, for labelling only. */
    public abstract String getSignalUnits();

    /** Bottom of the indicating scale, in {@link #getSignalUnits()}. */
    public abstract double getScaleBottomSignal();

    /** Top of the indicating scale, in {@link #getSignalUnits()}. */
    public abstract double getScaleTopSignal();

    /**
     * Where the pointer is sitting. Overridable so a channel with additional
     * self-declared conditions (see {@link AveragePowerRangeMonitor}) can widen
     * the {@link DetectorStatus#INOPERATIVE} case.
     *
     * <p>Note what is deliberately absent: nothing here reports "saturated". A
     * detector that has rolled over reads the same as one that sees no flux,
     * because that is what the hardware does.
     */
    public DetectorStatus getStatus() {
        if (!energised) {
            return DetectorStatus.INOPERATIVE;
        }
        if (detectorSignal > getScaleTopSignal()) {
            return DetectorStatus.UPSCALE;
        }
        if (detectorSignal < getScaleBottomSignal()) {
            return DetectorStatus.DOWNSCALE;
        }
        return DetectorStatus.ONSCALE;
    }

    /** True when {@link #getStatus()} is {@link DetectorStatus#ONSCALE}. */
    public boolean isOnScale() {
        return getStatus() == DetectorStatus.ONSCALE;
    }

    // ------------------------------------------------------------------
    // Nameplate range
    // ------------------------------------------------------------------

    /**
     * Lowest neutron power fraction this channel can indicate on scale.
     * Nameplate data — it says what the hardware is, not what the core is doing.
     */
    public abstract double getRangeMinimumNeutronPowerFraction();

    /**
     * Highest neutron power fraction this channel indicates truthfully.
     *
     * <p>Above this the channel does not stop at a maximum, it stops being
     * honest: the SRM rolls back toward zero, the IRM amplifier flattens, the
     * APRM pegs. Nameplate data.
     */
    public abstract double getRangeMaximumNeutronPowerFraction();

    /**
     * Whether the given flux falls inside this channel's nameplate band. Takes
     * true core power, so this is for the mod's own instrument-coverage checks
     * and tests, not something a control program can evaluate.
     */
    public boolean coversNeutronPowerFraction(double n) {
        return n >= getRangeMinimumNeutronPowerFraction()
                && n <= getRangeMaximumNeutronPowerFraction();
    }

    // ------------------------------------------------------------------
    // Channel condition
    // ------------------------------------------------------------------

    /** Panel identifier for this channel. */
    public String getDesignation() {
        return designation;
    }

    /** True when chamber high voltage is applied. */
    public boolean isEnergised() {
        return energised;
    }

    /**
     * Apply or remove chamber high voltage. A de-energised channel decays to
     * zero signal and declares itself {@link DetectorStatus#INOPERATIVE}.
     */
    public void setEnergised(boolean energised) {
        this.energised = energised;
    }

    /**
     * Whether the chamber, cable or preamplifier has failed.
     *
     * <p><b>Equipment state, not an instrument reading.</b> Persist it, render
     * it, drive it from a damage model — but do not expose it through the
     * CC:Tweaked peripheral. A real chamber failure is silent: the channel
     * reads zero and reports itself energised and healthy. Finding that by
     * comparing channels is the intended experience.
     */
    public boolean isChamberFailed() {
        return chamberFailed;
    }

    /** @see #isChamberFailed() */
    public void setChamberFailed(boolean chamberFailed) {
        this.chamberFailed = chamberFailed;
    }

    /** Signal-conditioning time constant, seconds. */
    public double getResponseTimeConstantSeconds() {
        return responseTimeConstantSeconds;
    }

    /** @see #getResponseTimeConstantSeconds() */
    public void setResponseTimeConstantSeconds(double responseTimeConstantSeconds) {
        this.responseTimeConstantSeconds = Math.max(0.0, responseTimeConstantSeconds);
    }

    /** Simulated seconds this channel has been ticked. */
    public double getElapsedSeconds() {
        return elapsedSeconds;
    }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    /**
     * Restore the conditioned signal after a chunk reload, so a channel resumes
     * mid-transient rather than ramping up from zero. Persistence only.
     */
    public void restoreDetectorSignal(double signal) {
        this.detectorSignal = (signal >= 0.0) ? signal : 0.0;
    }

    /** Restore accumulated run time after a chunk reload. Persistence only. */
    public void restoreElapsedSeconds(double seconds) {
        this.elapsedSeconds = (seconds >= 0.0) ? seconds : 0.0;
    }

    /** Zero the conditioned signal and run time. Leaves energisation and failure alone. */
    public void reset() {
        this.detectorSignal = 0.0;
        this.elapsedSeconds = 0.0;
        this.neutronPowerFraction = 0.0;
    }
}
