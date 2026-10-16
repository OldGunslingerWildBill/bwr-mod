package dev.bwr.mod.panel.component;

/**
 * What a key press asks the server to do, as the number the operate packet carries ({@code Operate.value}). The
 * display selection menu, the FUNC soft keys and the rest of the key logic run on the client
 * ({@link RecorderUi}); only their result reaches the server, and the server validates every code.
 *
 * <p>These change what the recorder shows or records and acknowledge its own alarms. None drives a plant
 * command or a redstone output.
 */
public final class RecorderActions {
    private RecorderActions() {
    }

    public static final int NONE = 0;
    public static final int ACK = 1;
    public static final int START = 2;
    public static final int STOP = 3;
    public static final int GROUP_NEXT = 4;
    public static final int GROUP_PREV = 5;
    /** Up: toward the trend screen. */
    public static final int STEP_UP = 6;
    /** Down: toward the bar screen. */
    public static final int STEP_DOWN = 7;
    public static final int COVER_TOGGLE = 8;
    /** {@code SCREEN_BASE + RecorderScreen.ordinal()}: show that screen. */
    public static final int SCREEN_BASE = 10;
    /** {@code QUAD_BASE + layout}: the four-panel screen with that layout (0 MIX, 1 trend, 2 digital, 3 bar). */
    public static final int QUAD_BASE = 30;
    /** {@code MESSAGE_BASE + n} (n 1 to 10): write preset message n on the trend. */
    public static final int MESSAGE_BASE = 100;

    public static int screen(RecorderScreen screen) {
        return SCREEN_BASE + screen.ordinal();
    }

    /** Whether {@code code} is something the server acts on. */
    public static boolean valid(int code) {
        return code >= ACK && code <= COVER_TOGGLE
                || code >= SCREEN_BASE && code < SCREEN_BASE + RecorderScreen.values().length
                || code >= QUAD_BASE && code < QUAD_BASE + 4
                || code > MESSAGE_BASE && code <= MESSAGE_BASE + PaperlessRecorderData.PRESETS;
    }

    /** Server: carries out {@code code} on {@code data}. Returns whether it was understood (not whether it changed anything). */
    public static boolean apply(PaperlessRecorderData data, int code) {
        if (!valid(code)) {
            return false;
        }
        switch (code) {
            case ACK -> data.acknowledgeAll();
            case START -> data.setRunning(true);
            case STOP -> data.setRunning(false);
            case GROUP_NEXT -> data.stepGroup(1);
            case GROUP_PREV -> data.stepGroup(-1);
            case STEP_UP -> data.stepScreen(-1);
            case STEP_DOWN -> data.stepScreen(1);
            case COVER_TOGGLE -> data.toggleCover();
            default -> {
                if (code >= MESSAGE_BASE) {
                    data.addPresetMessage(code - MESSAGE_BASE);
                } else if (code >= QUAD_BASE) {
                    data.setQuad(code - QUAD_BASE);
                } else {
                    data.setScreen(RecorderScreen.values()[code - SCREEN_BASE]);
                }
            }
        }
        return true;
    }
}
