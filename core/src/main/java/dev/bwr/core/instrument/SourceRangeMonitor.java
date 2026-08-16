package dev.bwr.core.instrument;

import dev.bwr.core.CoreConfig;

/**
 * Source range monitor: a pulse-counting fission chamber reading counts per
 * second. The bottom of the instrument stack, and the only thing that reads
 * anything at all on a shutdown core.
 *
 * <h2>Paralyzable dead time</h2>
 * A pulse counter cannot resolve two events closer together than its dead time
 * {@code tau}. In a <i>paralyzable</i> counter each arriving event restarts the
 * dead period, so pile-up does not merely lose counts, it actively suppresses
 * them:
 * <pre>
 *   indicated_cps = true_cps * exp(-true_cps * tau)
 * </pre>
 * That curve rises, peaks at {@code 1/(e*tau)} when {@code true_cps = 1/tau},
 * and then <b>rolls back down to zero</b>. There is no saturation branch in
 * this class and there must never be one: the rollover is the equation.
 *
 * <p>With the {@link CoreConfig} defaults ({@code tau = 3.68e-12 s},
 * {@code 4.1e13 cps per unit of fractional power}):
 * <table border="1">
 *   <caption>Calibration points</caption>
 *   <tr><th>Core condition</th><th>true cps</th><th>indicated cps</th></tr>
 *   <tr><td>shutdown on source, n = 1.0e-12</td><td>4.10E+01</td><td>4.10E+01</td></tr>
 *   <tr><td>n = 6.98e-04</td><td>2.86E+10</td><td>2.58E+10 (10% low)</td></tr>
 *   <tr><td>n = 6.63e-03, the peak</td><td>2.72E+11</td><td>9.9967E+10</td></tr>
 *   <tr><td>n = 1.2e-02 and above</td><td>&gt; 4.9E+11</td><td>collapsing toward 00.00</td></tr>
 * </table>
 *
 * <h2>The trap this class exists to create</h2>
 * The peak indication, {@code 1/(e*tau) = 9.9967E+10 cps}, sits just below the
 * top of the scale at {@code 1E+11}. So this channel <b>can never indicate
 * upscale</b>. Push the core past 0.66% rated and the SRM does not peg and does
 * not alarm — it quietly walks back down through mid-scale, through downscale,
 * to 00.00, which is precisely what it reads when it is dead. That ambiguity is
 * real dead-time behaviour and it is the reason the IRM exists.
 *
 * <p>The two cross-checks the player gets are {@link #isEnergised()} and
 * {@link #isStartupSourcePresent()}. Both true, reading 00.00, and the IRM
 * showing flux means the SRM has rolled over. Nothing in this class will say so.
 *
 * <p>The true count rate is deliberately not exposed. Neither is a "saturated"
 * flag. The {@link PeriodMeter} differentiates the <i>indicated</i> rate, so a
 * rolled-over SRM also produces a garbage period — first infinite at the peak,
 * then strongly negative while power is climbing. That coupling is intended.
 */
public final class SourceRangeMonitor extends NeutronDetector {

    /**
     * Bottom of the count-rate display, cps. Below this the readout is
     * downscale; a live startup source on a shutdown core sits an order of
     * magnitude above it.
     */
    public static final double SCALE_BOTTOM_CPS = 3.0;

    /**
     * Top of the count-rate display, cps. Unreachable by construction: the
     * paralyzable peak of {@code 1/(e*tau)} lands 0.03% below it with the
     * default dead time. See the class notes.
     */
    public static final double SCALE_TOP_CPS = 1.0e11;

    /** Count-rate meter integrating time constant, seconds. */
    public static final double DEFAULT_RESPONSE_TIME_CONSTANT_S = 0.5;

    private final double deadTimeSeconds;
    private final double countsPerSecondPerUnitPower;

    private boolean startupSourcePresent = true;

    /**
     * Build a channel from the core's instrument calibration.
     *
     * @param designation panel identifier, e.g. "SRM A"
     * @param config      supplies dead time and counts-per-unit-power
     */
    public SourceRangeMonitor(String designation, CoreConfig config) {
        this(designation, config.srmDeadTimeSeconds, config.srmCountsPerUnitPower);
    }

    /**
     * @param designation                 panel identifier
     * @param deadTimeSeconds             paralyzable dead time of the chamber
     *                                    and pulse chain, seconds
     * @param countsPerSecondPerUnitPower true counts per second produced per
     *                                    unit of fractional neutron power
     */
    public SourceRangeMonitor(String designation,
                              double deadTimeSeconds,
                              double countsPerSecondPerUnitPower) {
        super(designation, DEFAULT_RESPONSE_TIME_CONSTANT_S);
        this.deadTimeSeconds = Math.max(0.0, deadTimeSeconds);
        this.countsPerSecondPerUnitPower = Math.max(0.0, countsPerSecondPerUnitPower);
    }

    // ------------------------------------------------------------------
    // Response
    // ------------------------------------------------------------------

    @Override
    protected double computeRawSignal(double neutronPowerFraction) {
        double trueCps = countsPerSecondPerUnitPower * neutronPowerFraction;
        // Paralyzable pulse counter. Rise, peak at 1/(e*tau), roll to zero.
        // No saturation branch, by design.
        return trueCps * Math.exp(-trueCps * deadTimeSeconds);
    }

    /** Indicated count rate, counts per second. What the readout shows. */
    public double getCountsPerSecond() {
        return getDetectorSignal();
    }

    @Override
    public String getSignalUnits() {
        return "cps";
    }

    @Override
    public double getScaleBottomSignal() {
        return SCALE_BOTTOM_CPS;
    }

    @Override
    public double getScaleTopSignal() {
        return SCALE_TOP_CPS;
    }

    // ------------------------------------------------------------------
    // Nameplate
    // ------------------------------------------------------------------

    @Override
    public double getRangeMinimumNeutronPowerFraction() {
        if (countsPerSecondPerUnitPower <= 0.0) {
            return 0.0;
        }
        return SCALE_BOTTOM_CPS / countsPerSecondPerUnitPower;
    }

    /**
     * Flux at which the indication peaks and begins rolling over,
     * {@code (1/tau) / countsPerUnitPower}. 6.63e-3 of rated with the defaults.
     *
     * <p>Nameplate: it tells the player what this hardware is capable of, not
     * where the core is. Evaluating it against true power would require true
     * power, which no instrument in this package hands out.
     */
    @Override
    public double getRangeMaximumNeutronPowerFraction() {
        if (deadTimeSeconds <= 0.0 || countsPerSecondPerUnitPower <= 0.0) {
            return Double.POSITIVE_INFINITY;
        }
        return (1.0 / deadTimeSeconds) / countsPerSecondPerUnitPower;
    }

    /** Paralyzable dead time of the pulse chain, seconds. Nameplate. */
    public double getDeadTimeSeconds() {
        return deadTimeSeconds;
    }

    /** Chamber sensitivity: true cps per unit of fractional power. Nameplate. */
    public double getCountsPerSecondPerUnitPower() {
        return countsPerSecondPerUnitPower;
    }

    /**
     * Highest count rate this channel can ever indicate, {@code 1/(e*tau)}.
     * Nameplate. Note that it is below {@link #SCALE_TOP_CPS}.
     */
    public double getPeakIndicatedCountsPerSecond() {
        if (deadTimeSeconds <= 0.0) {
            return Double.POSITIVE_INFINITY;
        }
        return 1.0 / (Math.E * deadTimeSeconds);
    }

    // ------------------------------------------------------------------
    // Cross-checks
    // ------------------------------------------------------------------

    /**
     * Whether an installed startup neutron source is irradiating this chamber.
     *
     * <p>Exposed because a dead channel, a rolled-over channel and a channel
     * with no source all read 00.00. This and {@link #isEnergised()} are the
     * two facts the player gets; everything else is inference from other
     * channels.
     */
    public boolean isStartupSourcePresent() {
        return startupSourcePresent;
    }

    /**
     * Declare whether a startup source is installed and irradiating this
     * chamber. Status only — the count rate follows the flux the chamber
     * actually sees, and removing the source lowers that flux through the
     * neutronics, not through this flag.
     */
    public void setStartupSourcePresent(boolean startupSourcePresent) {
        this.startupSourcePresent = startupSourcePresent;
    }
}
