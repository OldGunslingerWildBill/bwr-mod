package dev.bwr.mod.panel.plant;

import java.util.Locale;

/** How a number is written on the display. One place, so the server's change test and the client's text agree. */
public final class PlantFormat {
    private PlantFormat() {
    }

    /** The shown text of a value with no data. */
    public static final String NO_DATA = "---";

    /** {@code value} with {@code decimals} places; never "-0.0"; {@link #NO_DATA} for NaN or infinity. */
    public static String number(float value, int decimals) {
        if (Float.isNaN(value) || Float.isInfinite(value)) {
            return NO_DATA;
        }
        String text = String.format(Locale.ROOT, "%." + Math.max(0, Math.min(4, decimals)) + "f", value);
        if (text.startsWith("-") && text.chars().noneMatch(c -> c >= '1' && c <= '9')) {
            text = text.substring(1);
        }
        return text;
    }
}
