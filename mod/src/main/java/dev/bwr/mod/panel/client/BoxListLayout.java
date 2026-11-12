package dev.bwr.mod.panel.client;

/**
 * Where the annunciator box list puts things, as pure arithmetic (like {@link BindLayout}): a title, a line of counts,
 * the filter selector, the column heads, the scrolling list, two lines of notes and the Done button. A light takes two
 * lines of text in the list so that its legend and its source each get most of the width: the list then reads at the
 * smallest screen the game scales to (320 x 240) as it does on a large one.
 *
 * <pre>
 *   3-2   []  RPV LEVEL LO-LO                 ALARM
 *   00102     Reactor - level                 below -129
 * </pre>
 */
public final class BoxListLayout {
    /** Height of one light in the list: two lines of text. */
    public static final int ROW = 22;
    /** Width of the left column of a row: the light's number over its address. */
    public static final int NUMBER_WIDTH = 34;
    /** Side of the colour swatch after the number. */
    public static final int SWATCH = 8;
    /** Width of the right column of a row: the state over the trigger. */
    public static final int SIDE_WIDTH = 76;
    public static final int GAP = 4;
    /** What the list keeps free of a row on each side (its own margin and its scrollbar). */
    public static final int LIST_MARGIN = 12;

    public final int left;
    public final int width;
    public final int top;
    public final int titleY;
    public final int countsY;
    public final int filterY;
    public final int headY;
    public final int listY;
    public final int listHeight;
    public final int hintY;
    public final int noteY;
    public final int buttonY;
    public final int bottom;

    public BoxListLayout(int screenWidth, int screenHeight) {
        this.width = Math.max(120, Math.min(460, screenWidth - 20));
        this.left = (screenWidth - width) / 2;
        this.top = 6;
        this.titleY = top + 4;
        this.countsY = titleY + 12;
        this.filterY = countsY + 12;
        this.headY = filterY + 24;
        this.listY = headY + 11;
        this.buttonY = Math.max(listY + ROW + 26, screenHeight - 26);
        this.noteY = buttonY - 11;
        this.hintY = noteY - 10;
        this.listHeight = Math.max(ROW, hintY - 3 - listY);
        this.bottom = buttonY + 20;
    }

    /** The width of a row of a list this wide. */
    public static int rowWidth(int listWidth) {
        return Math.max(0, listWidth - LIST_MARGIN);
    }

    /** Where the swatch starts, from a row's left edge. */
    public static int swatchX() {
        return NUMBER_WIDTH + GAP;
    }

    /** Where the middle column (the legend over the source) starts, from a row's left edge. */
    public static int middleX() {
        return swatchX() + SWATCH + GAP;
    }

    /** The width of the middle column of a row this wide; never negative. */
    public static int middleWidth(int rowWidth) {
        return Math.max(0, sideX(rowWidth) - GAP - middleX());
    }

    /** Where the right column (the state over the trigger) starts, from a row's left edge. */
    public static int sideX(int rowWidth) {
        return Math.max(middleX(), rowWidth - SIDE_WIDTH);
    }
}
