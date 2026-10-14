package dev.bwr.mod.reactor;

import dev.bwr.core.ReactorCore;
import dev.bwr.core.instrument.AveragePowerRangeMonitor;

/**
 * Real lattice/axial LPRM readings. The core owns each string and its unique APRM rack input;
 * no modulo wrapping or repeated chambers are introduced by the display.
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

    private static AveragePowerRangeMonitor monitor(ReactorCore core, int string, int level) {
        return core.getAveragePowerRangeMonitor(core.getLprmLayout().channel(string,level));
    }
    private static int input(ReactorCore core, int string, int level) {
        return core.getLprmLayout().input(string,level);
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
