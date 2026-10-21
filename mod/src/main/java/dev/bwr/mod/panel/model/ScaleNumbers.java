package dev.bwr.mod.panel.model;

import dev.bwr.mod.panel.display.PanelFormat;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * The one number formatter of every printed gauge scale (the baked GE and classic meters' numerals through
 * {@link Legends}, the bar gauge's numbers, the classic dial's end labels): so a range in the thousands cannot print
 * "40005000" on one kind of gauge and be right on another.
 *
 * <p>A scale is first tried in full numbers; when the caller says they do not fit (they would touch at their spacing, or
 * are wider than their room) it is printed compactly: thousands as {@code k} and millions as {@code M}, with the fewest
 * digits that say the same number ("1k", "2.5k", "60k", "1.2M"; 500 stays 500). A value that cannot be said exactly in two
 * decimals of its unit (1234) keeps its full digits.
 */
public final class ScaleNumbers {
    private ScaleNumbers() {
    }

    /** How a scale's numbers are written. */
    public enum Notation {
        FULL, COMPACT
    }

    /** {@code value} written in {@code notation}. */
    public static String format(double value, Notation notation) {
        if (notation == Notation.COMPACT) {
            double magnitude = Math.abs(value);
            double unit = magnitude >= 1.0e6 ? 1.0e6 : magnitude >= 1.0e3 ? 1.0e3 : 0.0;
            if (unit > 0.0) {
                double scaled = value / unit;
                double hundredths = Math.round(scaled * 100.0);
                if (Math.abs(scaled * 100.0 - hundredths) < 1.0e-6) {
                    String digits = PanelFormat.formatNumber(hundredths / 100.0, "");
                    return digits + (unit == 1.0e6 ? "M" : "k");
                }
            }
        }
        return PanelFormat.formatNumber(value, "");
    }

    /** All of {@code values} written in {@code notation}. */
    public static List<String> format(double[] values, Notation notation) {
        List<String> out = new ArrayList<>(values.length);
        for (double value : values) {
            out.add(format(value, notation));
        }
        return out;
    }

    /**
     * The labels of a scale of {@code values}: in full numbers if {@code fits} accepts them, else compactly (whether or not
     * that fits: it is the narrowest there is, and the caller then shrinks the text).
     */
    public static List<String> labels(double[] values, Predicate<List<String>> fits) {
        List<String> full = format(values, Notation.FULL);
        if (fits.test(full)) {
            return full;
        }
        return format(values, Notation.COMPACT);
    }
}
