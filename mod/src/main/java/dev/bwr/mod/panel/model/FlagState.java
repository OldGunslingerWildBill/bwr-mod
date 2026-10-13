package dev.bwr.mod.panel.model;

/**
 * What a control switch's target flag shows. The two lamp inputs say what the plant is really doing and win when
 * exactly one of them is lit (red = open / running, green = closed / stopped, the US convention); with neither
 * (or both) lit the flag shows the last operation, the way a real breaker control switch's target does (OPEN
 * left it red, CLOSE left it green); a switch nobody has operated shows a neutral dark flag.
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
     */
    public static int colour(boolean redLit, boolean greenLit, int lastOperated) {
        if (redLit != greenLit) {
            return redLit ? RED : GREEN;
        }
        if (lastOperated > 0) {
            return RED;
        }
        if (lastOperated < 0) {
            return GREEN;
        }
        return NEUTRAL;
    }
}
