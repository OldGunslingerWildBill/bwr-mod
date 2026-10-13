package dev.bwr.mod.panel.model;

/**
 * What a control switch's target flag shows. The two lamp inputs say what the plant is really doing and win when
 * exactly one of them is lit (red = open / running, green = closed / stopped, the US convention); with neither
 * (or both) lit the flag shows the last operation. Which operation leaves it red is a per-switch setting: CLOSE
 * (the default, as on a GE breaker control switch: GE SB-1 data sheet) or OPEN (valves and pumps). A switch nobody
 * has operated shows a neutral dark flag.
 */
public final class FlagState {
    private FlagState() {
    }

    public static final int RED = 0xE02020;
    public static final int GREEN = 0x30D060;
    public static final int NEUTRAL = 0x3A3F44;

    /**
     * @param redLit        the red lamp input is active
     * @param greenLit      the green lamp input is active
     * @param lastOperated  -1 CLOSE, 0 never operated, +1 OPEN
     * @param redAfterClose true: CLOSE leaves the flag red and OPEN green; false: the reverse
     */
    public static int colour(boolean redLit, boolean greenLit, int lastOperated, boolean redAfterClose) {
        if (redLit != greenLit) {
            return redLit ? RED : GREEN;
        }
        if (lastOperated == 0) {
            return NEUTRAL;
        }
        boolean closed = lastOperated < 0;
        return closed == redAfterClose ? RED : GREEN;
    }

    /** The default setting: red after CLOSE. */
    public static int colour(boolean redLit, boolean greenLit, int lastOperated) {
        return colour(redLit, greenLit, lastOperated, true);
    }
}
