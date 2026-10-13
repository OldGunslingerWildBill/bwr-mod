package dev.bwr.mod.panel.model;

import java.util.Locale;

/** The colours behind the exporter's {@code bwr_tint} names (the control-room palette of PANEL-MODELS-SPEC.md). */
public final class Tints {
    private Tints() {
    }

    /** sRGB of a tint name, or {@code fallback} for none or an unknown one. */
    public static int rgb(String tint, int fallback) {
        if (tint == null) {
            return fallback;
        }
        return switch (tint.toLowerCase(Locale.ROOT)) {
            case "red" -> 0xE02020;
            case "green" -> 0x20B040;
            case "white" -> 0xF0F0E0;
            case "amber" -> 0xFFB000;
            case "blue" -> 0x3070E0;
            default -> fallback;
        };
    }
}
