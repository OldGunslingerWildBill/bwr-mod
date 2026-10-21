package dev.bwr.mod.gui.client.style;

import java.util.ArrayList;
import java.util.List;

/**
 * The design tokens of every machine screen, in one place: a dark industrial control-room palette, a 4 px spacing
 * scale, a type hierarchy and the status vocabulary.
 *
 * <p>This class is deliberately pure (integers and strings, no Minecraft type) so a test can load it on a dedicated
 * server and check the numbers: {@link #textPairs()} lists every text-on-background combination the screens draw and
 * the test requires each to reach {@value #MIN_CONTRAST}:1 (WCAG AA for normal text). A screen never writes a colour
 * literal; it asks for a token, and a token that is only ever used with the surfaces it was checked against stays
 * readable.
 *
 * <h2>Colour is typography, not logic</h2>
 * As before, colour here never decides anything in the plant. A value drawn amber is not a trip, it is a number that
 * has passed a published design figure, and it is always accompanied by a shape and a word ({@link Status}) so it is
 * readable without telling red from green.
 *
 * <h2>Type</h2>
 * Vanilla's bitmap font is only crisp at whole-number scales, so the hierarchy is built from weight, case, colour and
 * one 2x step ({@link Type}) rather than from fractional sizes, which blur at GUI scales 1 to 3.
 */
public final class BwrStyle {
    private BwrStyle() {
    }

    /** WCAG AA contrast for normal-size text. */
    public static final double MIN_CONTRAST = 4.5;

    // ---------------------------------------------------------------
    // Spacing: a 4 px scale. Every gap, padding and margin is one of these.
    // ---------------------------------------------------------------
    public static final int SP1 = 4;
    public static final int SP2 = 8;
    public static final int SP3 = 12;
    public static final int SP4 = 16;
    public static final int SP5 = 24;

    /** Height of one label-value row (a 9 px line plus breathing room). */
    public static final int ROW = 12;
    /** Height of a wrapped text line. */
    public static final int LINE = 10;
    /** Height of buttons, fields and sliders (vanilla's, so keyboard and gamepad users get a familiar target). */
    public static final int CONTROL = 20;
    /** Height of a tab. */
    public static final int TAB = 16;
    /** Height of the title bar. */
    public static final int TITLE_BAR = 20;
    /** Height of a bar meter. */
    public static final int METER = 8;
    /** Side of a status icon. */
    public static final int ICON = 7;
    /** Space kept between the panel and the window edge when the window is small. */
    public static final int WINDOW_MARGIN = SP2;

    // ---------------------------------------------------------------
    // Surfaces (ARGB)
    // ---------------------------------------------------------------
    /** The panel itself. */
    public static final int PANEL = 0xFF12161C;
    /** A card: the first step up from the panel. */
    public static final int SURFACE = 0xFF1A2028;
    /** Buttons and other raised controls. */
    public static final int RAISED = 0xFF252D38;
    public static final int RAISED_HOVER = 0xFF2E3844;
    public static final int RAISED_PRESSED = 0xFF161B22;
    /** Wells: bar troughs, text fields, slot backgrounds, the core map. */
    public static final int INSET = 0xFF0C0F13;
    public static final int TITLEBAR = 0xFF1B2D42;

    public static final int BORDER = 0xFF3A4552;
    public static final int BORDER_STRONG = 0xFF66768A;
    /** The keyboard focus ring. */
    public static final int FOCUS = 0xFFFFFFFF;
    /** The grid and rules inside a card. */
    public static final int RULE = 0xFF2C3540;

    // ---------------------------------------------------------------
    // Text
    // ---------------------------------------------------------------
    public static final int TEXT_PRIMARY = 0xFFEDF1F5;
    public static final int TEXT_SECONDARY = 0xFFAAB5C2;
    public static final int TEXT_DISABLED = 0xFF939EAB;
    public static final int ACCENT = 0xFF80C4FF;

    // ---------------------------------------------------------------
    // Buttons
    // ---------------------------------------------------------------
    public static final int PRIMARY = 0xFF1F63A8;
    public static final int PRIMARY_HOVER = 0xFF2870B8;
    public static final int PRIMARY_PRESSED = 0xFF174F88;
    public static final int DANGER = 0xFFA33030;
    public static final int DANGER_HOVER = 0xFFB63A3A;
    public static final int DANGER_PRESSED = 0xFF802424;
    public static final int ON_FILL = 0xFFFFFFFF;
    /** Outlines of the filled button variants (graphic parts, not text). */
    public static final int PRIMARY_BORDER = 0xFF5AA5EE;
    public static final int DANGER_BORDER = 0xFFE06A6A;

    // ---------------------------------------------------------------
    // Status
    // ---------------------------------------------------------------

    /** The silhouette of a status icon: a second channel besides colour. */
    public enum Shape {
        NONE, CIRCLE, TRIANGLE, DIAMOND, SQUARE, RING
    }

    /**
     * What a value is telling the reader. {@link #NONE} is "just a number". Every other status has a colour, a
     * silhouette and a word, so none of them depends on colour perception.
     */
    public enum Status {
        NONE(TEXT_PRIMARY, Shape.NONE, ""),
        NORMAL(0xFF62D690, Shape.CIRCLE, "NORMAL"),
        CAUTION(0xFFF2B93B, Shape.TRIANGLE, "CAUTION"),
        ALARM(0xFFFF7B7B, Shape.DIAMOND, "ALARM"),
        INFO(0xFF6FBAFF, Shape.SQUARE, "INFO"),
        OFF(TEXT_SECONDARY, Shape.RING, "OFF");

        public final int colour;
        public final Shape shape;
        public final String label;

        Status(int colour, Shape shape, String label) {
            this.colour = colour;
            this.shape = shape;
            this.label = label;
        }

        /** The more severe of two statuses, for a card that summarises its rows. */
        public Status worst(Status other) {
            return rank() >= other.rank() ? this : other;
        }

        private int rank() {
            return switch (this) {
                case ALARM -> 4;
                case CAUTION -> 3;
                case INFO -> 2;
                case NORMAL -> 1;
                default -> 0;
            };
        }
    }

    // ---------------------------------------------------------------
    // Type
    // ---------------------------------------------------------------

    /** The type hierarchy. {@code scale} is a whole number so the bitmap font stays crisp. */
    public enum Type {
        /** The window title: bold, primary. */
        TITLE(1, true, false, TEXT_PRIMARY),
        /** A section header: upper case, accent. */
        HEADER(1, false, true, ACCENT),
        /** A section heading in plain sentence case ("Pump", "Water", "Controls"), in the same accent. */
        SECTION(1, false, false, ACCENT),
        /** Everything you read. */
        BODY(1, false, false, TEXT_PRIMARY),
        /** Units, help and secondary detail. */
        CAPTION(1, false, false, TEXT_SECONDARY),
        /** A headline number. */
        DISPLAY(2, false, false, TEXT_PRIMARY);

        public final int scale;
        public final boolean bold;
        public final boolean upper;
        public final int colour;

        Type(int scale, boolean bold, boolean upper, int colour) {
            this.scale = scale;
            this.bold = bold;
            this.upper = upper;
            this.colour = colour;
        }
    }

    // ---------------------------------------------------------------
    // Contrast
    // ---------------------------------------------------------------

    /** A foreground drawn on a background, named for the failure message. */
    public record Pair(String name, int foreground, int background) {
        public double contrast() {
            return BwrStyle.contrast(foreground, background);
        }
    }

    private static double channel(int c) {
        double v = c / 255.0;
        return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    }

    /** WCAG relative luminance of an RGB colour (alpha ignored). */
    public static double luminance(int argb) {
        return 0.2126 * channel(argb >> 16 & 0xFF) + 0.7152 * channel(argb >> 8 & 0xFF) + 0.0722 * channel(argb & 0xFF);
    }

    /** WCAG contrast ratio, 1 to 21. */
    public static double contrast(int a, int b) {
        double la = luminance(a);
        double lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    /**
     * Every text-on-background pair the screens draw. The surfaces a text colour may sit on are the ones listed
     * here; {@link #TEXT_DISABLED} is never drawn on a hovered control (a disabled control has no hover state).
     */
    public static List<Pair> textPairs() {
        List<Pair> pairs = new ArrayList<>();
        int[] surfaces = {PANEL, SURFACE, RAISED, INSET, TITLEBAR};
        String[] surfaceNames = {"PANEL", "SURFACE", "RAISED", "INSET", "TITLEBAR"};
        int[] texts = {TEXT_PRIMARY, TEXT_SECONDARY, TEXT_DISABLED, ACCENT, Status.NORMAL.colour, Status.CAUTION.colour,
                Status.ALARM.colour, Status.INFO.colour};
        String[] textNames = {"TEXT_PRIMARY", "TEXT_SECONDARY", "TEXT_DISABLED", "ACCENT", "NORMAL", "CAUTION", "ALARM", "INFO"};
        for (int t = 0; t < texts.length; t++) {
            for (int s = 0; s < surfaces.length; s++) {
                pairs.add(new Pair(textNames[t] + " on " + surfaceNames[s], texts[t], surfaces[s]));
            }
        }
        // Hovered and pressed secondary controls carry primary and secondary text, and the status colours (a toggle that
        // shows its state in colour) but never disabled text.
        int[] liveTexts = {TEXT_PRIMARY, TEXT_SECONDARY, ACCENT, Status.NORMAL.colour, Status.CAUTION.colour,
                Status.ALARM.colour, Status.INFO.colour};
        String[] liveNames = {"TEXT_PRIMARY", "TEXT_SECONDARY", "ACCENT", "NORMAL", "CAUTION", "ALARM", "INFO"};
        for (int t = 0; t < liveTexts.length; t++) {
            pairs.add(new Pair(liveNames[t] + " on RAISED_HOVER", liveTexts[t], RAISED_HOVER));
            pairs.add(new Pair(liveNames[t] + " on RAISED_PRESSED", liveTexts[t], RAISED_PRESSED));
        }
        pairs.add(new Pair("ON_FILL on PRIMARY", ON_FILL, PRIMARY));
        pairs.add(new Pair("ON_FILL on PRIMARY_HOVER", ON_FILL, PRIMARY_HOVER));
        pairs.add(new Pair("ON_FILL on PRIMARY_PRESSED", ON_FILL, PRIMARY_PRESSED));
        pairs.add(new Pair("ON_FILL on DANGER", ON_FILL, DANGER));
        pairs.add(new Pair("ON_FILL on DANGER_HOVER", ON_FILL, DANGER_HOVER));
        pairs.add(new Pair("ON_FILL on DANGER_PRESSED", ON_FILL, DANGER_PRESSED));
        return pairs;
    }

    /** Non-text parts that must stay distinguishable from what they sit on (WCAG 1.4.11 asks 3:1). */
    public static List<Pair> graphicPairs() {
        List<Pair> pairs = new ArrayList<>();
        pairs.add(new Pair("FOCUS ring on PANEL", FOCUS, PANEL));
        pairs.add(new Pair("FOCUS ring on SURFACE", FOCUS, SURFACE));
        pairs.add(new Pair("BORDER_STRONG on PANEL", BORDER_STRONG, PANEL));
        pairs.add(new Pair("BORDER_STRONG on SURFACE", BORDER_STRONG, SURFACE));
        for (Status s : Status.values()) {
            if (s != Status.NONE) {
                pairs.add(new Pair("status icon " + s + " on SURFACE", s.colour, SURFACE));
                pairs.add(new Pair("status icon " + s + " on INSET", s.colour, INSET));
            }
        }
        pairs.add(new Pair("ACCENT meter fill on INSET", ACCENT, INSET));
        return pairs;
    }

    /** Minimum for the {@link #graphicPairs()}. */
    public static final double MIN_GRAPHIC_CONTRAST = 3.0;
}
