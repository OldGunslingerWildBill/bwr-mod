package dev.bwr.core.instrument;

import dev.bwr.core.Check;
import dev.bwr.core.CoreConfig;

/**
 * Acceptance test 7: the source range monitor is a paralyzable pulse counter,
 * so it rolls over to zero instead of pegging.
 *
 * <pre>
 *   indicated_cps = true_cps * exp(-true_cps * deadTime)
 * </pre>
 *
 * <p>That curve rises, peaks at {@code 1/(e*tau)}, and falls back to zero. There
 * must be no saturation branch anywhere: the rollover has to fall out of the
 * equation. A model that clamps the indication at a maximum deletes the central
 * hazard of a BWR startup, which is that a source range channel reading 00.00
 * may mean the core is shut down or may mean it is well past the point where
 * anybody should be pulling rods.
 */
public final class SourceRangeMonitorTest {

    private SourceRangeMonitorTest() {
    }

    /** Long enough that the 0.5 s count-rate meter lag is fully settled. */
    private static final double SETTLE_SECONDS = 60.0;

    private static SourceRangeMonitor settledAt(double neutronPowerFraction) {
        SourceRangeMonitor srm = new SourceRangeMonitor("SRM TEST", new CoreConfig());
        srm.update(neutronPowerFraction, SETTLE_SECONDS);
        return srm;
    }

    /**
     * <b>Acceptance 7.</b> Sweep the true count rate up through the paralyzable
     * peak and out the other side. Indication must rise, turn over at
     * {@code 1/(e*tau)}, and collapse toward zero — never exceeding the peak and
     * never reporting upscale.
     */
    public static void test01_indicationRollsOverInsteadOfPegging() {
        CoreConfig config = new CoreConfig();
        SourceRangeMonitor reference = new SourceRangeMonitor("SRM REF", config);
        double peakIndicated = reference.getPeakIndicatedCountsPerSecond();
        double powerAtPeak = reference.getRangeMaximumNeutronPowerFraction();

        Check.note("dead time %.3g s, %.3g cps per unit power", reference.getDeadTimeSeconds(),
                reference.getCountsPerSecondPerUnitPower());
        Check.note("paralyzable peak %.4E cps at n = %.4E; scale top %.4E cps",
                peakIndicated, powerAtPeak, SourceRangeMonitor.SCALE_TOP_CPS);

        double previous = -1.0;
        double highestSeen = 0.0;
        boolean turnedOver = false;
        for (int decade = -13; decade <= 0; decade++) {
            for (int step = 0; step < 4; step++) {
                double n = Math.pow(10.0, decade + step * 0.25);
                double indicated = settledAt(n).getCountsPerSecond();
                Check.finiteAndNonNegative(indicated, "indicated cps at n = " + n);
                Check.lessThan(peakIndicated * (1.0 + 1.0e-9), indicated,
                        "indication must never exceed the paralyzable peak, at n = " + n);
                if (previous >= 0.0 && indicated < previous) {
                    turnedOver = true;
                } else if (turnedOver && indicated > previous) {
                    Check.fail("indication climbed again after rolling over, at n = %.3g", n);
                }
                highestSeen = Math.max(highestSeen, indicated);
                previous = indicated;
            }
        }
        Check.isTrue(turnedOver, "the indication must roll over somewhere in the sweep");
        Check.relative(peakIndicated, highestSeen, 0.02, "highest indication seen across the sweep");
        Check.note("highest indication seen in the sweep %.4E cps, 1/(e*tau) = %.4E cps",
                highestSeen, peakIndicated);

        // Exactly at the peak flux the indication is 1/(e*tau) by construction.
        Check.relative(peakIndicated, settledAt(powerAtPeak).getCountsPerSecond(), 1.0e-9,
                "indication at the peak flux");

        // A hundred times past it, the channel is reading essentially nothing.
        double farPast = settledAt(100.0 * powerAtPeak).getCountsPerSecond();
        Check.note("at 100x the peak flux (n = %.3E) the channel indicates %.4E cps",
                100.0 * powerAtPeak, farPast);
        Check.lessThan(1.0, farPast, "indication at a hundred times the rollover flux");
        Check.lessThan(SourceRangeMonitor.SCALE_BOTTOM_CPS, farPast,
                "a rolled-over channel must read downscale, not upscale");
    }

    /**
     * The indication must be the paralyzable equation itself, not a lookup or a
     * clamp. Checked against the closed form at points either side of the peak.
     */
    public static void test02_indicationIsTheParalyzableEquation() {
        CoreConfig config = new CoreConfig();
        double tau = config.srmDeadTimeSeconds;
        double perUnitPower = config.srmCountsPerUnitPower;
        for (double n : new double[]{1.0e-12, 1.0e-9, 1.0e-6, 1.0e-4, 6.63e-3, 2.0e-2, 1.0e-1}) {
            double trueCps = perUnitPower * n;
            double expected = trueCps * Math.exp(-trueCps * tau);
            Check.relative(expected, settledAt(n).getCountsPerSecond(), 1.0e-9,
                    "indicated cps at n = " + n);
        }
    }

    /**
     * A channel with no flux, a de-energised channel, and a rolled-over channel
     * all read 00.00. That ambiguity is the point: no method here will tell the
     * player which of the three they are looking at.
     */
    public static void test03_deadRolledOverAndDarkAllReadZero() {
        SourceRangeMonitor dark = settledAt(0.0);
        Check.absolute(0.0, dark.getCountsPerSecond(), 1.0e-30, "indication with no flux");

        SourceRangeMonitor unpowered = new SourceRangeMonitor("SRM HV OFF", new CoreConfig());
        unpowered.setEnergised(false);
        unpowered.update(1.0e-12, SETTLE_SECONDS);
        Check.absolute(0.0, unpowered.getCountsPerSecond(), 1.0e-30, "indication with no high voltage");
        Check.isTrue(unpowered.getStatus() == NeutronDetector.DetectorStatus.INOPERATIVE,
                "a de-energised channel declares itself inoperative");

        SourceRangeMonitor failed = new SourceRangeMonitor("SRM FAILED", new CoreConfig());
        failed.setChamberFailed(true);
        failed.update(1.0e-12, SETTLE_SECONDS);
        Check.absolute(0.0, failed.getCountsPerSecond(), 1.0e-30, "indication with a failed chamber");
        Check.isTrue(failed.getStatus() != NeutronDetector.DetectorStatus.INOPERATIVE,
                "a failed chamber must be silent, not self-declaring");

        SourceRangeMonitor rolledOver = settledAt(1.0);
        Check.note("at rated flux the SRM indicates %.4E cps with status %s",
                rolledOver.getCountsPerSecond(), rolledOver.getStatus());
        Check.isTrue(rolledOver.getStatus() != NeutronDetector.DetectorStatus.UPSCALE,
                "a rolled-over SRM must not read upscale");
    }

    /**
     * The count-rate floor a shutdown core sits on. With the calibrated source
     * the subcritical equilibrium puts about 1e-12 of rated on the chamber, and
     * the channel reads 41 cps — an order of magnitude above the bottom of the
     * scale, which is what makes an approach to critical observable at all.
     */
    public static void test04_shutdownFloorIsAboutFortyOneCountsPerSecond() {
        double indicated = settledAt(1.0e-12).getCountsPerSecond();
        Check.note("n = 1.0e-12 of rated indicates %.4E cps", indicated);
        Check.relative(41.0, indicated, 0.01, "shutdown count rate floor");
        Check.greaterThan(SourceRangeMonitor.SCALE_BOTTOM_CPS, indicated,
                "the shutdown floor must be on scale");
    }

    /**
     * Period is derived from the detector's own signal, so a rolled-over channel
     * produces a garbage period. Here true power climbs steadily through the
     * rollover and the period meter, faithfully differentiating a falling
     * indication, reports the core <i>shrinking</i>. Nothing in the model corrects
     * this, and nothing should.
     */
    public static void test05_rolledOverChannelReportsAGarbagePeriod() {
        SourceRangeMonitor srm = new SourceRangeMonitor("SRM PERIOD", new CoreConfig());
        PeriodMeter meter = new PeriodMeter("PERIOD SRM", srm);

        double dt = 0.05;
        double truePeriodSeconds = 20.0;
        double growthPerTick = Math.exp(dt / truePeriodSeconds);
        double powerAtRollover = srm.getRangeMaximumNeutronPowerFraction();

        // Climb on a genuine +20 second period, two decades below the rollover
        // flux. Here the channel tells the truth.
        double power = 1.0e-7;
        while (power < 0.01 * powerAtRollover) {
            power *= growthPerTick;
            srm.update(power, dt);
            meter.update(dt);
        }
        double truthfulPeriod = meter.getPeriodSeconds();
        double truthfulCps = srm.getCountsPerSecond();

        // Keep climbing, on exactly the same period, out past the rollover.
        while (power < 3.0 * powerAtRollover) {
            power *= growthPerTick;
            srm.update(power, dt);
            meter.update(dt);
        }
        double garbagePeriod = meter.getPeriodSeconds();

        Check.note("below the rollover the meter reads %+.1f s on a true %+.0f s period, at %.3E cps",
                truthfulPeriod, truePeriodSeconds, truthfulCps);
        Check.note("past the rollover, true power still climbing on the same period, "
                        + "it reads %+.1f s at %.3E cps", garbagePeriod, srm.getCountsPerSecond());
        Check.greaterThan(0.0, truthfulPeriod, "period below the rollover, on a climbing core");
        Check.relative(truePeriodSeconds, truthfulPeriod, 0.05, "indicated period below the rollover");
        Check.lessThan(0.0, garbagePeriod, "period past the rollover, on a still-climbing core");

        // Dead time does not wait for the rollover to start lying. Differentiating
        // indicated = true*exp(-true*tau) stretches the indicated period by
        // 1/(1 - true*tau) all the way up, so the channel reads long before it
        // reads backwards. Ten percent below the peak that is already 11% slow.
        SourceRangeMonitor nearPeak = new SourceRangeMonitor("SRM NEAR PEAK", new CoreConfig());
        PeriodMeter nearPeakMeter = new PeriodMeter("PERIOD NEAR PEAK", nearPeak);
        double p = 0.05 * powerAtRollover;
        while (p < 0.10 * powerAtRollover) {
            p *= growthPerTick;
            nearPeak.update(p, dt);
            nearPeakMeter.update(dt);
        }
        double stretched = nearPeakMeter.getPeriodSeconds();
        Check.note("at a tenth of the rollover flux the same +%.0f s core indicates %+.1f s "
                        + "(dead-time stretch 1/(1 - true*tau) = %.3f)",
                truePeriodSeconds, stretched, 1.0 / (1.0 - 0.10));
        Check.relative(truePeriodSeconds / 0.90, stretched, 0.05,
                "dead-time stretched period at a tenth of the rollover flux");
    }
}
