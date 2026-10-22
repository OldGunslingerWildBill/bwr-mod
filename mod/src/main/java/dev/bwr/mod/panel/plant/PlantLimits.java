package dev.bwr.mod.panel.plant;

/**
 * Every bound of the plant computer in one place, so a program, a save file and a packet can never make one display
 * hold or send more than this. The worst case of all of them together is proved to fit one content patch
 * ({@code PlantComputerTests.theLimitsKeepTheWholeStateInOnePatch}).
 */
public final class PlantLimits {
    private PlantLimits() {
    }

    /** Pages a program may define (shown by number, 1 to this). */
    public static final int MAX_PAGES = 8;
    /** Elements on one page. */
    public static final int MAX_ELEMENTS_PER_PAGE = 48;
    /** Elements on all pages together, trends included. */
    public static final int MAX_ELEMENTS = 160;
    /** Trend plots on all pages together. */
    public static final int MAX_TRENDS = 12;
    /** Points one trend keeps. A trend asks for a history of {@link #MIN_POINTS} to this many. */
    public static final int MAX_POINTS = 120;
    public static final int MIN_POINTS = 8;
    public static final int DEFAULT_POINTS = 60;
    /** Soft keys of a page (touch buttons along the bottom row). */
    public static final int MAX_KEYS = 6;

    /** Longest text line, and the longest caption / tile label / key label, unit, element id and page title (characters). */
    public static final int MAX_TEXT = 32;
    public static final int MAX_LABEL = 16;
    public static final int MAX_UNIT = 6;
    public static final int MAX_ID = 10;
    public static final int MAX_TITLE = 24;

    /** The text grid: a display is never more than this many columns and rows of characters. */
    public static final int MAX_COLS = 80;
    public static final int MAX_ROWS = 48;

    /**
     * Rate limit of the program's calls that change the display: a bucket of {@link #CALL_BURST} calls that refills at
     * {@link #CALL_REFILL_PER_TICK} a tick (640 a second). A call with no token left fails with an error instead of queueing.
     */
    public static final int CALL_BURST = 512;
    public static final double CALL_REFILL_PER_TICK = 32.0;

    /**
     * Network: what changed is sent at most every this many ticks per display (5 a second), however often the program
     * draws, as one patch of at most {@code ComponentData.MAX_PATCH} bytes. {@code flush()} may send sooner, but never
     * twice within {@link #FLUSH_MIN_TICKS}.
     */
    public static final int PUBLISH_TICKS = 4;
    public static final int FLUSH_MIN_TICKS = 2;

    /** A soft key touch fires at most one CC event per this many ticks per display. */
    public static final int TOUCH_TICKS = 4;
}
