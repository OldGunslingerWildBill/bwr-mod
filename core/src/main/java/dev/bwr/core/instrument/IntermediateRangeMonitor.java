package dev.bwr.core.instrument;

/**
 * Intermediate range monitor: a fission chamber read as a current, displayed on
 * a 0..125 division scale, with a <b>manually selected</b> range switch of ten
 * detents. The middle rung of the instrument ladder — it takes over from the
 * source range monitor before that channel's dead time destroys it, and hands
 * over to the average power range monitor once there is enough flux to average.
 *
 * <h2>Ranging is manual and always will be</h2>
 * {@link #setRange(int)} is an actuator, nothing more. There is no auto-ranging
 * here and there must never be: leaving the IRM on a low range while withdrawing
 * rods is one of the classic startup errors, and auto-ranging deletes the skill
 * of avoiding it along with the hazard. A control program may of course drive
 * {@code setRange} itself — that is the player writing their own auto-ranger,
 * which is exactly the intended division of labour.
 *
 * <p>That is also why the switch position is persisted, as
 * {@code ReactorState.intermediateRangeMonitorRanges}: the channel's indication
 * is a filtered signal and is deliberately allowed to re-settle on a reload, but
 * where the operator left the switch is hardware, and nothing in the plant will
 * put it back. A reload that rebuilt every channel on detent 1 was silent
 * auto-ranging by omission — the same defect as an auto-ranger, arrived at by
 * forgetting rather than by deciding.
 *
 * <h2>Where the ranges sit, and why</h2>
 * Ten ranges, each a half decade above the last, so the instrument spans 4.5
 * decades between range 1 full scale and range 10 full scale, and about 5.9
 * decades in total once the bottom of range 1 is counted.
 *
 * <table border="1">
 *   <caption>Range card, neutron power fraction of rated at 125 divisions</caption>
 *   <tr><th>Range</th><th>Full scale</th><th>On scale from (5 div)</th></tr>
 *   <tr><td>1</td><td>3.16E-06</td><td>1.26E-07</td></tr>
 *   <tr><td>2</td><td>1.00E-05</td><td>4.00E-07</td></tr>
 *   <tr><td>3</td><td>3.16E-05</td><td>1.26E-06</td></tr>
 *   <tr><td>4</td><td>1.00E-04</td><td>4.00E-06</td></tr>
 *   <tr><td>5</td><td>3.16E-04</td><td>1.26E-05</td></tr>
 *   <tr><td>6</td><td>1.00E-03</td><td>4.00E-05</td></tr>
 *   <tr><td>7</td><td>3.16E-03</td><td>1.26E-04</td></tr>
 *   <tr><td>8</td><td>1.00E-02</td><td>4.00E-04</td></tr>
 *   <tr><td>9</td><td>3.16E-02</td><td>1.26E-03</td></tr>
 *   <tr><td>10</td><td>1.00E-01</td><td>4.00E-03</td></tr>
 * </table>
 *
 * <p><b>The IRM bottom is placed in the 1E-07 decade of rated power</b> — range 1
 * reaches its 5-division downscale point at 1.26E-07. That is deliberate, and it
 * is set against the two channels either side of it:
 *
 * <ul>
 *   <li><b>Against the SRM below.</b> With the {@code CoreConfig} defaults the
 *       source range monitor is still honest to within 10% up to about 7E-04 of
 *       rated and does not peak and roll over until 6.6E-03. The IRM is on scale
 *       from 1.26E-07, so the two channels overlap for roughly <b>3.7 decades</b>
 *       of trustworthy SRM indication. A player who transfers anywhere in the
 *       1E-06 to 1E-04 band — which is where the SRM count rate is comfortably
 *       mid-scale and the IRM has come up off its own downscale peg — never has
 *       a flux at which nothing reads true. At the moment the SRM does roll over
 *       at 6.6E-03, the IRM on range 8 is sitting at 83 divisions, dead centre of
 *       its scale.</li>
 *   <li><b>Against the APRM above.</b> Range 10 tops out at 10% of rated, and the
 *       APRM comes on scale at 3%, so those two overlap for the last half decade.
 *       An IRM left on range 10 at rated power is pegged at
 *       {@link #METER_PEG_DIVISIONS} and tells the operator nothing, which is why
 *       real IRMs are bypassed once the APRMs are reading.</li>
 * </ul>
 *
 * <h2>The peg is not a reading</h2>
 * Above full scale the current amplifier flattens and the movement stops at
 * {@link #METER_PEG_DIVISIONS}. A pegged IRM cannot say whether it is 5% or 500%
 * past its range, and because a {@link PeriodMeter} differentiates this same
 * pegged signal, a pegged IRM also reports an <i>infinite period</i> while power
 * is doubling every few seconds. That coupling is intended: it is precisely the
 * failure that makes leaving the range switch alone dangerous.
 *
 * <p>Nothing in this class alarms, trips or ranges itself. {@link #getStatus()}
 * reports where the pointer is; deciding what that means is the player's job.
 */
public final class IntermediateRangeMonitor extends NeutronDetector {

    /** Detents on the range switch. */
    public static final int RANGE_COUNT = 10;

    /** Lowest range detent, the most sensitive. */
    public static final int LOWEST_RANGE = 1;

    /** Highest range detent, the least sensitive. */
    public static final int HIGHEST_RANGE = RANGE_COUNT;

    /** Decades of flux between one detent and the next. Half a decade, factor 3.162. */
    public static final double RANGE_STEP_DECADES = 0.5;

    /**
     * Neutron power fraction that drives range 1 to full scale: 10^-5.5, i.e.
     * 3.16E-06 of rated. See the class notes for why the ladder is pinned here.
     */
    public static final double RANGE_1_FULL_SCALE_NEUTRON_POWER_FRACTION = Math.pow(10.0, -5.5);

    /** Full scale of the indicating meter, divisions. */
    public static final double SCALE_TOP_DIVISIONS = 125.0;

    /** Bottom of the indicating meter, divisions. Below this the pointer is downscale. */
    public static final double SCALE_BOTTOM_DIVISIONS = 5.0;

    /**
     * Mechanical stop of the meter movement, divisions. The amplifier flattens
     * and the pointer parks here however far past full scale the flux goes.
     */
    public static final double METER_PEG_DIVISIONS = 130.0;

    /** Current amplifier time constant, seconds. */
    public static final double DEFAULT_RESPONSE_TIME_CONSTANT_S = 0.2;

    private int range;

    /** Build a channel parked on the most sensitive range, as it would be for a startup. */
    public IntermediateRangeMonitor(String designation) {
        this(designation, LOWEST_RANGE);
    }

    /**
     * @param designation  panel identifier, e.g. "IRM C"
     * @param initialRange range detent to start on, 1..10; values outside the
     *                     switch's travel clamp to the nearest detent
     */
    public IntermediateRangeMonitor(String designation, int initialRange) {
        super(designation, DEFAULT_RESPONSE_TIME_CONSTANT_S);
        this.range = clampRange(initialRange);
    }

    // ------------------------------------------------------------------
    // Response
    // ------------------------------------------------------------------

    @Override
    protected double computeRawSignal(double neutronPowerFraction) {
        double divisions = SCALE_TOP_DIVISIONS * neutronPowerFraction / fullScaleForRange(range);
        // The movement stops at the peg. This is a mechanical stop, not a
        // saturation special case: the channel keeps indicating, it just stops
        // indicating anything useful.
        return Math.min(divisions, METER_PEG_DIVISIONS);
    }

    /** Meter indication, 0..125 divisions within the selected range. */
    public double getScaleDivisions() {
        return getDetectorSignal();
    }

    /**
     * Meter indication as a fraction of full scale, 0..1 on scale.
     * Convenience for a bar widget; the same number as
     * {@code getScaleDivisions() / 125}.
     */
    public double getFractionOfFullScale() {
        return getDetectorSignal() / SCALE_TOP_DIVISIONS;
    }

    /**
     * What this channel <i>claims</i> the neutron power fraction is: the meter
     * indication converted back through the selected range.
     *
     * <p>This is an indication, not the truth. On a pegged channel it reads
     * {@code 1.04 x} full scale of the range and stays there while real power
     * climbs; on a channel left on too high a range it reads a rounded-down
     * fraction of a division. Useful for stitching a continuous power trace
     * across range changes and for 1/M startup plots — with the same caveats a
     * real operator carries.
     */
    public double getIndicatedNeutronPowerFraction() {
        return (getDetectorSignal() / SCALE_TOP_DIVISIONS) * fullScaleForRange(range);
    }

    @Override
    public String getSignalUnits() {
        return "divisions (0-125)";
    }

    @Override
    public double getScaleBottomSignal() {
        return SCALE_BOTTOM_DIVISIONS;
    }

    @Override
    public double getScaleTopSignal() {
        return SCALE_TOP_DIVISIONS;
    }

    // ------------------------------------------------------------------
    // Range switch — a manual actuator
    // ------------------------------------------------------------------

    /** Selected range detent, 1..10. */
    public int getRange() {
        return range;
    }

    /**
     * Move the range switch. Ten detents; a value outside the switch's travel
     * clamps to the nearest one, because a physical switch has nowhere else to
     * go.
     *
     * <p>The indication does not jump — the amplifier's time constant still
     * applies — so a range change produces the short swing a real IRM shows.
     * Nothing here decides <i>when</i> to change range.
     */
    public void setRange(int range) {
        this.range = clampRange(range);
    }

    /** Move one detent less sensitive, if there is one. Returns the new range. */
    public int rangeUp() {
        return range = clampRange(range + 1);
    }

    /** Move one detent more sensitive, if there is one. Returns the new range. */
    public int rangeDown() {
        return range = clampRange(range - 1);
    }

    private static int clampRange(int range) {
        if (range < LOWEST_RANGE) {
            return LOWEST_RANGE;
        }
        return Math.min(range, HIGHEST_RANGE);
    }

    // ------------------------------------------------------------------
    // Nameplate
    // ------------------------------------------------------------------

    /**
     * Neutron power fraction that drives the selected range to 125 divisions.
     * Nameplate data for the selected detent.
     */
    public double getSelectedRangeFullScaleNeutronPowerFraction() {
        return fullScaleForRange(range);
    }

    /**
     * Neutron power fraction at which the selected range reaches its 5-division
     * downscale point. Nameplate data for the selected detent.
     */
    public double getSelectedRangeOnScaleNeutronPowerFraction() {
        return fullScaleForRange(range) * (SCALE_BOTTOM_DIVISIONS / SCALE_TOP_DIVISIONS);
    }

    /**
     * Full-scale flux of any detent, whether or not it is selected. Publishing
     * the whole range card is what lets a control program plan a range change
     * before making it — or write its own auto-ranger, which is the player's
     * prerogative and not the mod's business.
     *
     * @param range detent number, clamped to 1..10
     */
    public static double fullScaleForRange(int range) {
        return RANGE_1_FULL_SCALE_NEUTRON_POWER_FRACTION
                * Math.pow(10.0, RANGE_STEP_DECADES * (clampRange(range) - LOWEST_RANGE));
    }

    /**
     * Lowest flux the instrument can indicate on scale, i.e. the 5-division
     * point of range 1: 1.26E-07 of rated.
     */
    @Override
    public double getRangeMinimumNeutronPowerFraction() {
        return fullScaleForRange(LOWEST_RANGE) * (SCALE_BOTTOM_DIVISIONS / SCALE_TOP_DIVISIONS);
    }

    /**
     * Highest flux the instrument can indicate truthfully, i.e. full scale on
     * range 10: 0.1 of rated. Above this every detent is pegged.
     */
    @Override
    public double getRangeMaximumNeutronPowerFraction() {
        return fullScaleForRange(HIGHEST_RANGE);
    }

    /** Decades of flux the whole instrument covers, bottom of range 1 to top of range 10. */
    public static double getSpanDecades() {
        return RANGE_STEP_DECADES * (RANGE_COUNT - 1)
                + Math.log10(SCALE_TOP_DIVISIONS / SCALE_BOTTOM_DIVISIONS);
    }
}
