package dev.bwr.mod.panel.model;

/**
 * How big the printed parts of a gauge-like component are: its scale numbers, units and labels, tick marks, pointers,
 * bands and margins. Every gauge renderer sizes them through this one policy, so a small (real size) component is the
 * big one scaled down, never the big one's text and ticks crammed onto a small face.
 *
 * <h3>The policy</h3>
 * <ol>
 *   <li><b>Proportional.</b> A printed length is a fixed share of the component's own drawn size: of its fitted model
 *       ({@link Placement#length}), of its face (the dial's radius, the edgewise strip's width), or of the art's size
 *       in cells for the size it was drawn for. Never a number of cells on its own.</li>
 *   <li><b>Least sizes shrink too.</b> A least size that keeps something from vanishing is written for the size the art
 *       was designed for and multiplied by {@link #sizeFactor}: {@code min(1, size / designSize)}. A floor fixed in cells
 *       is the bug this class exists to stop (a 0.15 cell floor made the numbers of a 1 x 3 bar gauge twice their
 *       size).</li>
 *   <li><b>Big sizes stay as they are.</b> {@link #sizeFactor} is never above 1, so at the design size and above every
 *       least size is what it always was: the big components look exactly as before.</li>
 *   <li><b>Player sizes.</b> The wrench's Text size and Tick size (percent, {@value #MIN_PERCENT} to
 *       {@value #MAX_PERCENT}, {@value #DEFAULT_PERCENT} by default, saved only when not the default) multiply the
 *       proportional sizes. Text then shrinks to fit ({@link #textCap}): it never leaves the face or runs into its
 *       neighbours, except that it never goes below {@link #MIN_TEXT_SHARE} of the size asked for.</li>
 * </ol>
 * GaugeScaleGuardTests renders every gauge-like component type at its small and big sizes and checks these rules.
 */
public final class GaugeScale {
    private GaugeScale() {
    }

    /** Config keys of the wrench's two sliders (percent). */
    public static final String TEXT_SIZE = "TextSize";
    public static final String TICK_SIZE = "TickSize";
    public static final int DEFAULT_PERCENT = 100;
    public static final int MIN_PERCENT = 50;
    public static final int MAX_PERCENT = 200;
    /** The sliders move in steps of this many percent. */
    public static final int STEP_PERCENT = 5;
    /**
     * The least share of the asked-for size that fitting may shrink text to, so a scale whose numbers are far too long
     * for the face (a range in the millions) still prints them, if past their room. Relative, like every other limit.
     */
    public static final float MIN_TEXT_SHARE = 0.4f;

    /** {@code percent} brought into the sliders' range. */
    public static int clampPercent(int percent) {
        return Math.max(MIN_PERCENT, Math.min(MAX_PERCENT, percent));
    }

    /** The factor a slider at {@code percent} multiplies its sizes by (1 at the default). */
    public static float factor(int percent) {
        return clampPercent(percent) / 100.0f;
    }

    /**
     * 1 for a component {@code designSize} or bigger (in whatever length both are given), less for a smaller one: what
     * a least size written for the design size is multiplied by.
     */
    public static float sizeFactor(float size, float designSize) {
        return designSize <= 0.0f ? 1.0f : Math.max(0.0f, Math.min(1.0f, size / designSize));
    }

    /** A least size, {@code leastAtDesign} at the design size, shrinking with the component ({@code sizeFactor}). */
    public static float least(float leastAtDesign, float sizeFactor) {
        return leastAtDesign * sizeFactor;
    }

    /**
     * The cap height of text whose proportional size is {@code natural}, with the player's {@code factor}, shrunk to
     * {@code fit} (the most the room allows: the face's width, a share of the spacing of its neighbours), but never
     * below {@link #MIN_TEXT_SHARE} of what was asked for.
     */
    public static float textCap(float natural, float factor, float fit) {
        float asked = natural * factor;
        return Math.max(asked * MIN_TEXT_SHARE, Math.min(asked, fit));
    }

    /**
     * How far a tick may be lengthened: {@code factor}, but at most what keeps it within {@code maxLength} (both in
     * the same length as {@code length}). Shortening is never limited.
     */
    public static float tickLengthFactor(float length, float factor, float maxLength) {
        if (factor <= 1.0f || length <= 0.0f) {
            return factor;
        }
        return Math.max(1.0f, Math.min(factor, maxLength / length));
    }

    /**
     * Width of {@code text} in pixels of the default font (the advance of each glyph, as {@code Font.width} adds them
     * up), without needing the font: layouts are worked out on either side (and in tests) with this, and the renderer
     * only aligns with the real font. Characters it does not list are taken at the common 6 pixels.
     */
    public static int textPixels(String text) {
        int width = 0;
        for (int i = 0; i < text.length(); i++) {
            width += advance(text.charAt(i));
        }
        return width;
    }

    private static int advance(char c) {
        return switch (c) {
            case 'i', '!', ',', '.', ':', ';', '|', '\'' -> 2;
            case 'l', '`' -> 3;
            case ' ', 'I', 't', '[', ']' -> 4;
            case 'f', 'k', '(', ')', '{', '}', '<', '>', '"', '*' -> 5;
            case '@', '~' -> 7;
            default -> 6;
        };
    }

    /**
     * Width of {@code text} in cap heights: its glyphs ({@link #textPixels} less the spacing after the last one) over the
     * {@link Legends#CAP_PIXELS} of a capital.
     */
    public static float textCaps(String text) {
        return text.isEmpty() ? 0.0f : (textPixels(text) - 1.0f) / Legends.CAP_PIXELS;
    }
}
