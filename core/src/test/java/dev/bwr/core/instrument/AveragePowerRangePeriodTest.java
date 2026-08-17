package dev.bwr.core.instrument;

import dev.bwr.core.Check;
import dev.bwr.core.CoreConfig;
import dev.bwr.core.ReactorCore;

/**
 * Period meters on the average power range monitors.
 *
 * <h2>The gap these close</h2>
 * {@code ReactorCore} built {@link PeriodMeter}s for the four source range and
 * eight intermediate range channels and stopped there, so above IRM range — which
 * is to say at every power a plant actually operates at — there was no
 * rate-of-change indication anywhere in the model. A period meter on the APRMs is
 * standard control-room equipment, and without one a control program flying the
 * plant at power could see where power <i>was</i> and never how fast it was
 * moving.
 *
 * <h2>The same instrument, and why that is not laziness</h2>
 * A period meter is a log-derivative amplifier. {@code d(ln S)/dt} is blind to
 * units and to any constant multiplier, so the APRM's percent-of-rated scaling
 * and its operator-set gain both cancel in the ratio and the existing
 * {@link PeriodMeter} reads a correct period off a percent-of-rated channel with
 * nothing changed. What does <i>not</i> cancel is the additive gamma background,
 * and the tests below pin down that it biases the reading long, that the bias
 * shrinks with power, and that it is negligible where the channel is trustworthy
 * and large where {@link AveragePowerRangeMonitor} already says the channel is
 * not — which is the physical behaviour, not a modelling compromise.
 *
 * <h2>No judgements</h2>
 * Nothing here is a threshold. A short period is not an alarm, a pegged channel
 * is not a fault, and the meter reports its input faithfully including when its
 * input is a lie.
 */
public final class AveragePowerRangePeriodTest {

    private AveragePowerRangePeriodTest() {
    }

    private static final double TICK_SECONDS = 0.05;

    /**
     * Steady power reads the infinity centre of the scale, not a large finite
     * number. An exponential filter approaches zero asymptotically and never
     * arrives, so this is a statement about
     * {@link PeriodMeter#MOVEMENT_RESOLUTION_INVERSE_PERIOD_PER_SECOND} doing its
     * job on a channel whose signal is a hundred times larger than an SRM's.
     */
    public static void test01_steadyPowerReadsInfinitePeriod() {
        AveragePowerRangeMonitor aprm = new AveragePowerRangeMonitor("APRM TEST");
        PeriodMeter meter = new PeriodMeter("PERIOD APRM TEST", aprm);
        aprm.update(0.85, 60.0);
        meter.reset();

        for (int i = 0; i < 20 * 60; i++) {
            aprm.update(0.85, TICK_SECONDS);
            meter.update(TICK_SECONDS);
        }

        Check.note("steady at %.2f%% of rated: 1/T = %.3e per second, period %.4g s, %s",
                aprm.getPercentOfRated(), meter.getInversePeriodPerSecond(),
                meter.getPeriodSeconds(), meter.getStatus());
        Check.isTrue(Double.isInfinite(meter.getPeriodSeconds()) && meter.getPeriodSeconds() > 0.0,
                "a steady power range channel indicates an infinite period, got %.6g",
                meter.getPeriodSeconds());
        Check.exactly(NeutronDetector.DetectorStatus.ONSCALE.ordinal(),
                meter.getStatus().ordinal(),
                "the infinity centre is the middle of the scale, not off the end of it");
    }

    /**
     * A ramp of known period reads back as that period, biased long by the gamma
     * background, with the bias shrinking as the fission signal grows past it.
     *
     * <p>The indicated inverse period is {@code (dS/dt)/S} and the APRM's signal
     * is {@code 100 n + gamma}, so what the meter reports is the true
     * {@code 1/T} multiplied by {@code 100 n / (100 n + gamma)}. At 5% of rated
     * that factor is about 0.91 and the period reads roughly a tenth too long; at
     * full power it is 0.995 and the reading is as good as the calibration. Both
     * are asserted here in the same test, because it is the <i>difference</i>
     * between them that says the background is being modelled rather than merely
     * added.
     */
    public static void test02_aKnownRampReadsBackBiasedLongByTheGammaBackground() {
        double truePeriodSeconds = 100.0;
        double lowPower = indicatedPeriodAfterRamp(0.03, truePeriodSeconds, 40.0);
        double highPower = indicatedPeriodAfterRamp(0.80, truePeriodSeconds, 40.0);

        Check.note("true period %.0f s: indicated %.2f s climbing through a few per cent of "
                        + "rated, %.2f s climbing through full power",
                truePeriodSeconds, lowPower, highPower);

        Check.greaterThan(0.0, lowPower, "indicated period on a rising channel is positive");
        Check.greaterThan(0.0, highPower, "indicated period on a rising channel is positive");
        Check.greaterThan(truePeriodSeconds, lowPower,
                "the gamma background can only make the indicated period longer");
        Check.greaterThan(truePeriodSeconds, highPower,
                "the gamma background can only make the indicated period longer");
        Check.relative(truePeriodSeconds, highPower, 0.02,
                "at power the background is negligible and the channel reads true");
        Check.greaterThan(highPower, lowPower,
                "the background bias must be larger at low power, which is where the "
                        + "intermediate range exists to take over");
        Check.lessThan(1.25 * truePeriodSeconds, lowPower,
                "the low-power bias is a tenth or so, not an order of magnitude");
    }

    /**
     * <b>A pegged channel reads a calm infinite period while power doubles.</b>
     *
     * <p>The movement runs to {@link AveragePowerRangeMonitor#METER_PEG_PERCENT}
     * and stops, so its signal stops changing, so the amplifier differentiating it
     * has nothing to differentiate. The period meter faithfully reports a steady
     * core. This is the same failure a pegged IRM produces, arriving in the band a
     * player is most likely to be flying on one channel, and it is preserved
     * rather than papered over: nothing here declares the channel faulty, and
     * cross-checking against another indication is the player's job.
     */
    public static void test03_aPeggedChannelIndicatesACalmCoreWhilePowerRunsAway() {
        AveragePowerRangeMonitor aprm = new AveragePowerRangeMonitor("APRM PEG");
        PeriodMeter meter = new PeriodMeter("PERIOD APRM PEG", aprm);

        double n = 2.0; // 200% of rated: well past the peg already
        aprm.update(n, 60.0);
        meter.reset();
        Check.exactly(AveragePowerRangeMonitor.METER_PEG_PERCENT, aprm.getPercentOfRated(),
                "the movement is against its stop before the excursion starts");

        double doublingSeconds = 10.0;
        double growth = Math.exp(TICK_SECONDS * Math.log(2.0) / doublingSeconds);
        for (int i = 0; i < 20 * 60; i++) { // 60 s, six doublings
            n *= growth;
            aprm.update(n, TICK_SECONDS);
            meter.update(TICK_SECONDS);
        }

        Check.note("true power went from 200%% to %.0f%% of rated in 60 s; the pegged channel "
                        + "still indicates %.1f%% and a period of %.4g s, status %s / %s",
                100.0 * n, aprm.getPercentOfRated(), meter.getPeriodSeconds(),
                aprm.getStatus(), meter.getStatus());

        Check.greaterThan(50.0, n, "the core really did run away during the test");
        Check.exactly(AveragePowerRangeMonitor.METER_PEG_PERCENT, aprm.getPercentOfRated(),
                "a pegged movement stops indicating anything more precise than 'past full scale'");
        Check.isTrue(Double.isInfinite(meter.getPeriodSeconds()),
                "a pegged channel's period meter has nothing to differentiate and reads "
                        + "infinity, got %.6g", meter.getPeriodSeconds());
    }

    /**
     * An averaging network with every input bypassed has no output, and its period
     * meter must say so rather than reporting a serene infinite period on scale.
     *
     * <p>{@link AveragePowerRangeMonitor} widens
     * {@link NeutronDetector.DetectorStatus#INOPERATIVE} to cover this case
     * because it is hardware — nothing to average means no signal — and
     * {@link PeriodMeter#getStatus()} asks the channel what it thinks of itself
     * rather than testing its high voltage, so the two agree. A meter reading
     * "calm" and a meter reading "nothing" are different statements and the panel
     * has to be able to tell them apart.
     */
    public static void test04_aBypassedOutAveragingNetworkIsInoperativeNotCalm() {
        AveragePowerRangeMonitor aprm = new AveragePowerRangeMonitor("APRM BYPASS");
        PeriodMeter meter = new PeriodMeter("PERIOD APRM BYPASS", aprm);
        aprm.update(0.85, 60.0);
        meter.update(TICK_SECONDS);

        Check.exactly(NeutronDetector.DetectorStatus.ONSCALE.ordinal(),
                meter.getStatus().ordinal(), "a healthy channel's meter is on scale");

        for (int i = 0; i < aprm.getAssignedLprmInputCount(); i++) {
            aprm.setLprmBypassed(i, true);
        }
        aprm.update(0.85, TICK_SECONDS);
        meter.update(TICK_SECONDS);

        Check.exactly(0, aprm.getInServiceLprmInputCount(), "every input switched out");
        Check.exactly(NeutronDetector.DetectorStatus.INOPERATIVE.ordinal(),
                aprm.getStatus().ordinal(), "an averaging network with no inputs is inoperative");
        Check.exactly(NeutronDetector.DetectorStatus.INOPERATIVE.ordinal(),
                meter.getStatus().ordinal(),
                "and so is the period meter watching it — it must not read calm");
        Check.note("every LPRM bypassed: channel %s, period meter %s",
                aprm.getStatus(), meter.getStatus());
    }

    /**
     * {@code ReactorCore} really builds one per channel, really updates it every
     * tick, and really parks it at centre on an initialisation.
     *
     * <p>The check that a period meter exists is not enough on its own — the
     * defect class this project keeps finding is a component that is constructed
     * and then never consumed — so this drives the core through a genuine power
     * change and requires the indication to move with it and to agree in sign with
     * the channel it is watching.
     */
    public static void test05_theCoreWiresAPeriodMeterToEveryPowerRangeChannel() {
        ReactorCore core = new ReactorCore(new CoreConfig());
        core.setBurnupEnabled(false);
        core.initialiseAtTotalPowerFraction(0.5);

        for (int channel = 0; channel < ReactorCore.AVERAGE_POWER_RANGE_CHANNELS; channel++) {
            PeriodMeter meter = core.getAveragePowerRangePeriodMeter(channel);
            Check.isTrue(meter != null, "APRM channel %d has no period meter", channel);
            Check.isTrue(meter.getSource() == core.getAveragePowerRangeMonitor(channel),
                    "APRM period meter %d is watching the wrong channel", channel);
            Check.isTrue(Double.isInfinite(core.getAveragePowerRangePeriodSeconds(channel)),
                    "a freshly initialised channel's pointer sits at the infinity centre, "
                            + "got %.6g", core.getAveragePowerRangePeriodSeconds(channel));
        }

        // Add a little reactivity by hand and let the core climb. Positive
        // reactivity, rising power, rising indication, positive period.
        double before = core.getAveragePowerRangePercent(0);
        core.positionRodsForReactivity(0.0008);
        for (int i = 0; i < 20 * 20; i++) {
            core.step();
        }
        double rising = core.getAveragePowerRangePeriodSeconds(0);
        double after = core.getAveragePowerRangePercent(0);

        Check.note("power %.2f%% -> %.2f%% of rated: indicated period %.2f s, startup rate "
                        + "%+.3f decades/min",
                before, after, rising,
                core.getAveragePowerRangeStartupRateDecadesPerMinute(0));

        Check.greaterThan(before, after, "the core climbed, so the channel indicated more power");
        Check.finite(rising, "a climbing power range channel indicates a finite period");
        Check.greaterThan(0.0, rising, "a rising indication is a positive period");
        Check.greaterThan(0.0, core.getAveragePowerRangeStartupRateDecadesPerMinute(0),
                "startup rate carries the same sign as the period");

        // Now the other way. A scram is the bluntest available power reduction and
        // the sign of the indication must follow it.
        core.scram();
        for (int i = 0; i < 20 * 20; i++) {
            core.step();
        }
        double falling = core.getAveragePowerRangePeriodSeconds(0);
        Check.note("after a scram: %.2f%% of rated, indicated period %.2f s",
                core.getAveragePowerRangePercent(0), falling);
        Check.lessThan(0.0, falling, "a falling indication is a negative period");
    }

    /**
     * The meters re-settle at centre across a save and a reload, exactly as the
     * source and intermediate range meters do.
     *
     * <p>A signal-conditioning lag is a filter, not plant state, so it is
     * deliberately absent from {@link dev.bwr.core.ReactorState}; the channels
     * re-settle in well under a second of indication, which is what a real
     * instrument rack does when its power comes back. What must not happen is a
     * reloaded plant coming up with a stale period on the panel.
     */
    public static void test06_periodMetersReSettleAcrossARestore() {
        ReactorCore original = new ReactorCore(new CoreConfig());
        original.setBurnupEnabled(false);
        original.initialiseAtTotalPowerFraction(0.5);
        original.positionRodsForReactivity(0.0008);
        for (int i = 0; i < 20 * 20; i++) {
            original.step();
        }
        Check.finite(original.getAveragePowerRangePeriodSeconds(0),
                "the original is mid-ramp with a finite indicated period");

        ReactorCore restored = new ReactorCore(new CoreConfig());
        restored.setBurnupEnabled(false);
        restored.fromState(original.toState());

        for (int channel = 0; channel < ReactorCore.AVERAGE_POWER_RANGE_CHANNELS; channel++) {
            Check.isTrue(Double.isInfinite(restored.getAveragePowerRangePeriodSeconds(channel)),
                    "APRM period meter %d must come back at centre scale, got %.6g",
                    channel, restored.getAveragePowerRangePeriodSeconds(channel));
        }
        Check.note("restore parks all %d power range period meters at the infinity centre",
                ReactorCore.AVERAGE_POWER_RANGE_CHANNELS);
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    /**
     * Run one APRM channel up an exponential of known period and return what its
     * period meter indicates at the end of the ramp.
     */
    private static double indicatedPeriodAfterRamp(double startingPowerFraction,
                                                   double truePeriodSeconds,
                                                   double rampSeconds) {
        AveragePowerRangeMonitor aprm = new AveragePowerRangeMonitor("APRM RAMP");
        PeriodMeter meter = new PeriodMeter("PERIOD APRM RAMP", aprm);
        double n = startingPowerFraction;
        aprm.update(n, 60.0);
        meter.reset();

        double growth = Math.exp(TICK_SECONDS / truePeriodSeconds);
        int ticks = (int) Math.round(rampSeconds / TICK_SECONDS);
        for (int i = 0; i < ticks; i++) {
            n *= growth;
            aprm.update(n, TICK_SECONDS);
            meter.update(TICK_SECONDS);
        }
        return meter.getPeriodSeconds();
    }
}
