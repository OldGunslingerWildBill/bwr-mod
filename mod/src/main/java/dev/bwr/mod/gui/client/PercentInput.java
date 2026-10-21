package dev.bwr.mod.gui.client;

import java.util.function.Predicate;

/**
 * The percent entry shared by every panel that has a speed or opening field: typed as 0 to 100 with at most one decimal,
 * sent as per mille. The parsing is exactly what each screen did before this class existed: anything that is not a
 * finite number from 0 to 100 is not sent.
 */
public final class PercentInput {
    private PercentInput() {
    }

    /** Accepts what can still become a valid entry while typing: up to three digits, optionally one decimal. */
    public static final Predicate<String> FILTER = s -> s.matches("[0-9]{0,3}(\\.[0-9]{0,1})?");

    /** The per-mille value of {@code text}, or null if it is not a number from 0 to 100. */
    public static Integer perMille(String text) {
        try {
            double value = Double.parseDouble(text);
            return Double.isFinite(value) && value >= 0 && value <= 100 ? (Integer) (int) Math.round(value * 10) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
