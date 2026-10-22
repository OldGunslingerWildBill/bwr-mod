package dev.bwr.mod.panel.plant;

import java.util.List;
import java.util.Locale;

/**
 * The few colours of the plant computer. A program names a colour (or a tile state); the display draws it. The names are
 * what the CC program passes, so they are part of the API.
 */
public final class PlantColors {
    private PlantColors() {
    }

    public static final int WHITE = 0;
    public static final int GREY = 1;
    public static final int GREEN = 2;
    public static final int AMBER = 3;
    public static final int RED = 4;
    public static final int CYAN = 5;
    public static final int BLUE = 6;
    public static final int MAGENTA = 7;

    public static final List<String> NAMES = List.of("white", "grey", "green", "amber", "red", "cyan", "blue", "magenta");
    /** 0xRRGGBB of each colour, as lit on the screen. */
    public static final int[] RGB = {0xF2F2EA, 0x8E959B, 0x35E060, 0xFFB020, 0xFF4636, 0x30D8E8, 0x5C8CFF, 0xE070F0};

    /** The colour named {@code name} (any case; {@code gray} too), or -1. */
    public static int byName(String name) {
        if (name == null) {
            return -1;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.equals("gray")) {
            lower = "grey";
        }
        return NAMES.indexOf(lower);
    }

    public static int rgb(int colour) {
        return RGB[Math.max(0, Math.min(RGB.length - 1, colour))];
    }

    /** Tile states: {@code off} is a dark tile, the others light it green, amber or red. */
    public static final int STATE_OFF = 0;
    public static final int STATE_OK = 1;
    public static final int STATE_WARN = 2;
    public static final int STATE_ALARM = 3;
    public static final List<String> STATES = List.of("off", "ok", "warn", "alarm");

    /** The state named {@code name} ({@code green}, {@code amber}, {@code red}, {@code grey} also), or -1. */
    public static int stateByName(String name) {
        if (name == null) {
            return -1;
        }
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "off", "grey", "gray" -> STATE_OFF;
            case "ok", "green" -> STATE_OK;
            case "warn", "amber" -> STATE_WARN;
            case "alarm", "red" -> STATE_ALARM;
            default -> -1;
        };
    }

    /** The colour a tile state, or a field's status, is drawn in. */
    public static int ofState(int state) {
        return switch (state) {
            case STATE_OK -> GREEN;
            case STATE_WARN -> AMBER;
            case STATE_ALARM -> RED;
            default -> GREY;
        };
    }
}
