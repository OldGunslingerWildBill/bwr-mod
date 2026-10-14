package dev.bwr.mod.reactor;

import dev.bwr.core.ReactorCore;
import dev.bwr.core.instrument.AveragePowerRangeMonitor;

/**
 * What the LPRM strings of {@link CoreRodMap} read. The core models LPRM chambers as the inputs of its six APRM
 * channels (24 each, 144 in all), with no string or level structure, so the display's strings are dealt onto those
 * chambers in order: level {@code L} (0 to 3, A to D) of string {@code s} (0-based) is chamber
 * {@code (4 s + L) mod 144}, which is input {@code (chamber / 6) mod 24} of channel {@code chamber mod 6}. The
 * 185-rod core has 39 strings (156 levels), so the last 12 levels read the same chambers as the first 12. The
 * reading, bypass and failure of a level are exactly those of its chamber, so a chamber failed or bypassed in the
 * core shows on the display, and the display adds nothing of its own except the alarm thresholds. Pure Java.
 *
 * <p>Thresholds are percent of rated on the LPRM scale. {@link #DEFAULT_DOWNSCALE_PERCENT} and
 * {@link #DEFAULT_UPSCALE_PERCENT} are gameplay defaults of the order real GE LPRM downscale and upscale alarms
 * use (a few percent, and a little above the top of the operating range); no source for exact values was found.
 * A display component carries its own.
 */
public final class LprmReadout {
    private LprmReadout() {
    }

    public static final int LEVELS = 4;
    public static final double DEFAULT_DOWNSCALE_PERCENT = 5.0;
    public static final double DEFAULT_UPSCALE_PERCENT = 110.0;

    private static int chamber(int string, int level) {
        int channels = ReactorCore.AVERAGE_POWER_RANGE_CHANNELS;
        int perChannel = AveragePowerRangeMonitor.DEFAULT_ASSIGNED_LPRM_INPUTS;
        return Math.floorMod(string * LEVELS + level, channels * perChannel);
    }

    private static AveragePowerRangeMonitor monitor(ReactorCore core, int string, int level) {
        return core.getAveragePowerRangeMonitor(chamber(string, level) % ReactorCore.AVERAGE_POWER_RANGE_CHANNELS);
    }

    private static int input(ReactorCore core, int string, int level) {
        AveragePowerRangeMonitor monitor = monitor(core, string, level);
        return chamber(string, level) / ReactorCore.AVERAGE_POWER_RANGE_CHANNELS % monitor.getAssignedLprmInputCount();
    }

    /** What the level indicates, percent of rated equivalent; a failed chamber reads 0. */
    public static double percent(ReactorCore core, int string, int level) {
        return monitor(core, string, level).getLprmIndicatedPercent(input(core, string, level));
    }

    public static boolean bypassed(ReactorCore core, int string, int level) {
        return monitor(core, string, level).isLprmBypassed(input(core, string, level));
    }

    public static boolean failed(ReactorCore core, int string, int level) {
        return monitor(core, string, level).isLprmFailed(input(core, string, level));
    }

    /** Below the downscale threshold, for a chamber that is in service and working (a failed or bypassed one is neither). */
    public static boolean downscale(double percent, boolean bypassed, boolean failed, double threshold) {
        return !bypassed && !failed && percent < threshold;
    }

    public static boolean upscale(double percent, boolean bypassed, boolean failed, double threshold) {
        return !bypassed && !failed && percent > threshold;
    }
}
