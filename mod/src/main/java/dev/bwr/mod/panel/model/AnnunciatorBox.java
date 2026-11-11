package dev.bwr.mod.panel.model;

import java.util.HashSet;
import java.util.Set;

/**
 * Which annunciator windows form one box. Windows that touch along a whole side are drawn as one grouped box: a thin
 * dark gap between them, one shared dark recessed frame round the outside. Each window draws only its own part of that
 * frame, so what a window needs to know is which of its sides are joined to a neighbour and, at a joined corner pair,
 * whether the diagonal neighbour is missing (an inside corner of the box). Pure: no world, no Minecraft types.
 *
 * <p>Cells are panel cells on one panel group's grid (column and row of the block times 16, plus the cell in the
 * block), so windows in different blocks of one group are neighbours too.
 */
public final class AnnunciatorBox {
    private AnnunciatorBox() {
    }

    // Elevations above the face in 1/16 block (before the component's elevation factor, which scales them all alike): the
    // dark base, the recessed tile, the lit tile, the grey no-signal flag, the legend, the corner pieces, the bezel lip.
    // The legend is a hair above the face it is printed on (TEXT_EPSILON), whatever the window's size or the panel's slope.
    public static final float BASE_Z = 0.8f;
    public static final float TILE_Z = 1.0f;
    public static final float LIT_Z = 1.12f;
    public static final float FLAG_Z = 1.16f;
    public static final float TEXT_Z = 1.17f;
    public static final float NOTCH_Z = 1.2f;
    public static final float LIP_Z = 1.3f;
    /** The most the legend may stand above the lit face, in 1/16 block (before the elevation factor). */
    public static final float TEXT_EPSILON = 0.06f;

    /** Joined sides, bits 0..3 of an edge word. */
    public static final int LEFT = 1;
    public static final int TOP = 2;
    public static final int RIGHT = 4;
    public static final int BOTTOM = 8;
    /** Inside corners (both sides along it joined, the diagonal cell empty), bits 4..7 of an edge word. */
    public static final int NOTCH_TOP_LEFT = 16;
    public static final int NOTCH_TOP_RIGHT = 32;
    public static final int NOTCH_BOTTOM_RIGHT = 64;
    public static final int NOTCH_BOTTOM_LEFT = 128;
    public static final int SIDES = LEFT | TOP | RIGHT | BOTTOM;

    /** One window: top-left cell and size in cells. */
    public record Rect(int x, int y, int w, int h) {
    }

    // ---- the Columbia tile box: what is drawn round the tiles, as shares of the tile width. The same numbers, under
    // the same names, build the preview box in art/models/panel/annunciator/build.py (read off the owner's photograph
    // of box P800-C5): keep the two in step. The tile itself (gap, rim, elevations) comes from the model file.
    /** Plate margin left of the tiles (it holds the row numerals), right (the latch), above (the column numerals) and below (the nameplate). */
    public static final float MARGIN_LEFT = 0.30f;
    public static final float MARGIN_RIGHT = 0.31f;
    public static final float MARGIN_TOP = 0.19f;
    public static final float MARGIN_BOTTOM = 0.375f;
    /** The dark line that splits a tile into its two alarms. */
    public static final float DIVIDER = 0.02f;
    /** Cap height of the white margin numerals. */
    public static final float NUMERAL_CAP = 0.085f;
    /** The white nameplate under the box: width, height, its distance below the tiles, the cap height of its lines, its thickness. */
    public static final float NAMEPLATE_W = 0.95f;
    public static final float NAMEPLATE_H = 0.33f;
    public static final float NAMEPLATE_GAP = 0.012f;
    public static final float NAMEPLATE_CAP = 0.095f;
    public static final float NAMEPLATE_T = 0.006f;
    /** The round latch in the right margin: radius, and how far it stands off the plate. */
    public static final float LATCH_R = 0.10f;
    public static final float LATCH_H = 0.09f;
    /**
     * The most a window of the Columbia style draws outside its own cells, in cells: the bottom margin of a box of the
     * widest windows there are (16 cells). {@code PanelBlockEntity.renderBounds} is widened by this for such a window.
     */
    public static final float MAX_OVERHANG = MARGIN_BOTTOM * 16.0f;

    /** What a window is in a split tile: a whole tile, or the upper or the lower of the two alarms that share one. */
    public static final int WHOLE = 0;
    public static final int UPPER = 1;
    public static final int LOWER = 2;

    /**
     * Where one window sits in its box ({@link #layout}).
     *
     * @param box       number of the box among the boxes of the panel group, from 0
     * @param row       the window's row in its box, from 0 at the top: one row per alarm, so a split tile is two rows
     * @param column    its column in the box, from 0 at the left
     * @param role      {@link #WHOLE}, {@link #UPPER} or {@link #LOWER}
     * @param unit      the tile width of the box in cells (the width of its first window in reading order): what the
     *                  margins, the numerals, the nameplate and the latch are sized by
     * @param nameplate whether this is the one window of its box that draws the nameplate
     * @param nameX2    twice the distance in cells from the window's left edge to the centre of the nameplate
     * @param latch     whether this is the one window of its box that draws the latch
     * @param latchY2   twice the distance in cells from the window's top edge to the centre of the latch
     */
    public record Tile(int box, int row, int column, int role, int unit, boolean nameplate, int nameX2, boolean latch, int latchY2) {
        /** A window that is a box by itself (also what a window is taken for until its panel group has been scanned). */
        public static Tile lone(int w, int h) {
            return new Tile(0, 0, 0, WHOLE, Math.max(1, w), true, w, true, h);
        }

        /** Everything a window's mesh depends on besides its edge word, folded into one number (never negative). */
        public long key() {
            long k = role;
            k = k * 131 + unit;
            k = k * 131 + (nameplate ? nameX2 + 1024 : 0);
            k = k * 131 + (latch ? latchY2 + 1024 : 0);
            return k & 0x7FFFFFFFFFFFFFL;
        }
    }

    /** Whether a window of this size is one alarm of a split tile rather than a whole tile: at least twice as wide as tall. */
    public static boolean halfShaped(int w, int h) {
        return w >= 2 * h;
    }

    /**
     * The boxes the windows form and each window's place in its box. Windows that touch along any length of a side
     * are one box. Rows and columns are counted over the distinct tops and lefts of the box's windows, so a regular
     * grid numbers as a grid and an irregular box still gets numbers. A window that is {@link #halfShaped} pairs with
     * the same-sized window directly under it into one split tile (the first of a column is the upper alarm, the next
     * the lower, and so on down); one left without a partner, and every other window, is a whole tile. One window of
     * the bottom row draws the nameplate, centred under the box, and one of the right column the latch, half way up
     * it; where the box's bottom row (right column) does not reach all the way under (beside) it there, as in an L,
     * the nearest window draws it, centred on itself, so it always has plate behind it.
     *
     * @param windows the windows of one panel group; they must not overlap. Never throws for any input.
     */
    public static Tile[] layout(Rect[] windows) {
        int n = windows.length;
        Tile[] out = new Tile[n];
        if (n == 0) {
            return out;
        }
        // Which window holds each cell.
        java.util.Map<Long, Integer> owner = new java.util.HashMap<>();
        for (int i = 0; i < n; i++) {
            Rect r = windows[i];
            for (int x = r.x; x < r.x + r.w; x++) {
                for (int y = r.y; y < r.y + r.h; y++) {
                    owner.put(key(x, y), i);
                }
            }
        }
        // Boxes: windows that touch along a side are merged.
        int[] group = new int[n];
        for (int i = 0; i < n; i++) {
            group[i] = i;
        }
        for (int i = 0; i < n; i++) {
            Rect r = windows[i];
            for (int y = r.y; y < r.y + r.h; y++) {
                merge(group, i, owner.get(key(r.x + r.w, y)));
            }
            for (int x = r.x; x < r.x + r.w; x++) {
                merge(group, i, owner.get(key(x, r.y + r.h)));
            }
        }
        // The windows in reading order (top to bottom, then left to right), so boxes and their first windows are stable.
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        java.util.Arrays.sort(order, (a, b) -> windows[a].y != windows[b].y ? Integer.compare(windows[a].y, windows[b].y)
                : windows[a].x != windows[b].x ? Integer.compare(windows[a].x, windows[b].x) : Integer.compare(a, b));
        int[] boxOf = new int[n];
        java.util.Map<Integer, Integer> numbers = new java.util.HashMap<>();
        for (int i : order) {
            int root = rootOf(group, i);
            Integer number = numbers.get(root);
            if (number == null) {
                number = numbers.size();
                numbers.put(root, number);
            }
            boxOf[i] = number;
        }
        int boxes = numbers.size();
        int[] x0 = new int[boxes];
        int[] y0 = new int[boxes];
        int[] x1 = new int[boxes];
        int[] y1 = new int[boxes];
        int[] unit = new int[boxes];
        java.util.Arrays.fill(x0, Integer.MAX_VALUE);
        java.util.Arrays.fill(y0, Integer.MAX_VALUE);
        java.util.Arrays.fill(x1, Integer.MIN_VALUE);
        java.util.Arrays.fill(y1, Integer.MIN_VALUE);
        java.util.List<java.util.TreeSet<Integer>> tops = new java.util.ArrayList<>();
        java.util.List<java.util.TreeSet<Integer>> lefts = new java.util.ArrayList<>();
        for (int b = 0; b < boxes; b++) {
            tops.add(new java.util.TreeSet<>());
            lefts.add(new java.util.TreeSet<>());
        }
        for (int i : order) {
            Rect r = windows[i];
            int b = boxOf[i];
            if (unit[b] == 0) {
                unit[b] = Math.max(1, r.w);
            }
            x0[b] = Math.min(x0[b], r.x);
            y0[b] = Math.min(y0[b], r.y);
            x1[b] = Math.max(x1[b], r.x + r.w);
            y1[b] = Math.max(y1[b], r.y + r.h);
            tops.get(b).add(r.y);
            lefts.get(b).add(r.x);
        }
        // Split tiles: down each column the half-shaped windows pair off, upper then lower.
        int[] role = new int[n];
        boolean[] open = new boolean[n]; // an upper alarm still waiting for the window under it
        for (int i : order) {
            Rect r = windows[i];
            if (!halfShaped(r.w, r.h)) {
                continue;
            }
            Integer above = owner.get(key(r.x, r.y - 1));
            if (above != null && open[above] && windows[above].x == r.x && windows[above].w == r.w && windows[above].h == r.h
                    && windows[above].y + windows[above].h == r.y) {
                role[above] = UPPER;
                role[i] = LOWER;
                open[above] = false;
            } else {
                open[i] = true;
            }
        }
        // The nameplate and the latch: the window on the box's centre line, else the nearest to it.
        int[] nameAt = new int[boxes];
        int[] latchAt = new int[boxes];
        long[] nameBest = new long[boxes];
        long[] latchBest = new long[boxes];
        java.util.Arrays.fill(nameAt, -1);
        java.util.Arrays.fill(latchAt, -1);
        for (int i : order) {
            Rect r = windows[i];
            int b = boxOf[i];
            if (r.y + r.h == y1[b]) {
                long off = distance2(r.x, r.x + r.w, (long) x0[b] + x1[b]);
                if (nameAt[b] < 0 || off < nameBest[b]) {
                    nameAt[b] = i;
                    nameBest[b] = off;
                }
            }
            if (r.x + r.w == x1[b]) {
                long off = distance2(r.y, r.y + r.h, (long) y0[b] + y1[b]);
                if (latchAt[b] < 0 || off < latchBest[b]) {
                    latchAt[b] = i;
                    latchBest[b] = off;
                }
            }
        }
        for (int i = 0; i < n; i++) {
            Rect r = windows[i];
            int b = boxOf[i];
            boolean name = nameAt[b] == i;
            boolean latch = latchAt[b] == i;
            // Centred on the box where the box's bottom row (right column) runs under the whole nameplate (beside the
            // whole latch) there; else, as for the nearest window of an L, centred on the window that draws it.
            int nameX2 = r.w;
            if (name && nameBest[b] == 0) {
                double centre = (x0[b] + x1[b]) / 2.0;
                double half = NAMEPLATE_W * unit[b] / 2.0;
                if (boxCovers(owner, boxOf, b, (int) Math.floor(centre - half), (int) Math.ceil(centre + half) - 1, y1[b] - 1, true)) {
                    nameX2 = x0[b] + x1[b] - 2 * r.x;
                }
            }
            int latchY2 = r.h;
            if (latch && latchBest[b] == 0) {
                double centre = (y0[b] + y1[b]) / 2.0;
                double half = LATCH_R * unit[b];
                if (boxCovers(owner, boxOf, b, (int) Math.floor(centre - half), (int) Math.ceil(centre + half) - 1, x1[b] - 1, false)) {
                    latchY2 = y0[b] + y1[b] - 2 * r.y;
                }
            }
            out[i] = new Tile(b, tops.get(b).headSet(r.y).size(), lefts.get(b).headSet(r.x).size(), role[i], unit[b],
                    name, nameX2, latch, latchY2);
        }
        return out;
    }

    /**
     * Whether windows of box {@code box} hold every cell from {@code from} to {@code to} along the row {@code line}
     * ({@code alongRow}) or along the column {@code line}.
     */
    private static boolean boxCovers(java.util.Map<Long, Integer> owner, int[] boxOf, int box, int from, int to, int line,
                                     boolean alongRow) {
        for (int at = from; at <= to; at++) {
            Integer window = owner.get(alongRow ? key(at, line) : key(line, at));
            if (window == null || boxOf[window] != box) {
                return false;
            }
        }
        return true;
    }

    /** 0 when twice the centre {@code c2} lies within {@code from .. to} (ends included), else how far outside it is (doubled). */
    private static long distance2(int from, int to, long c2) {
        long lo = 2L * from;
        long hi = 2L * to;
        return c2 < lo ? lo - c2 : c2 > hi ? c2 - hi : 0L;
    }

    private static int rootOf(int[] group, int i) {
        while (group[i] != i) {
            group[i] = group[group[i]];
            i = group[i];
        }
        return i;
    }

    private static void merge(int[] group, int a, Integer b) {
        if (b != null) {
            int ra = rootOf(group, a);
            int rb = rootOf(group, b);
            if (ra != rb) {
                group[Math.max(ra, rb)] = Math.min(ra, rb);
            }
        }
    }

    private static long key(int x, int y) {
        return ((long) x << 32) | (y & 0xFFFFFFFFL);
    }

    /**
     * The edge word of every window: a side is joined when every cell along it is touched by another window, so a
     * window beside a gap, a lamp or the end of the box keeps its own frame there. A window alone gets 0.
     *
     * @param windows the windows of one panel group; they must not overlap (a panel never places two over each other)
     */
    public static int[] edges(Rect[] windows) {
        Set<Long> occupied = new HashSet<>();
        for (Rect r : windows) {
            for (int x = r.x; x < r.x + r.w; x++) {
                for (int y = r.y; y < r.y + r.h; y++) {
                    occupied.add(key(x, y));
                }
            }
        }
        int[] out = new int[windows.length];
        for (int i = 0; i < windows.length; i++) {
            Rect r = windows[i];
            int word = 0;
            if (covered(occupied, r.x - 1, r.y, 0, r.h)) {
                word |= LEFT;
            }
            if (covered(occupied, r.x + r.w, r.y, 0, r.h)) {
                word |= RIGHT;
            }
            if (covered(occupied, r.x, r.y - 1, r.w, 0)) {
                word |= TOP;
            }
            if (covered(occupied, r.x, r.y + r.h, r.w, 0)) {
                word |= BOTTOM;
            }
            if ((word & LEFT) != 0 && (word & TOP) != 0 && !occupied.contains(key(r.x - 1, r.y - 1))) {
                word |= NOTCH_TOP_LEFT;
            }
            if ((word & RIGHT) != 0 && (word & TOP) != 0 && !occupied.contains(key(r.x + r.w, r.y - 1))) {
                word |= NOTCH_TOP_RIGHT;
            }
            if ((word & RIGHT) != 0 && (word & BOTTOM) != 0 && !occupied.contains(key(r.x + r.w, r.y + r.h))) {
                word |= NOTCH_BOTTOM_RIGHT;
            }
            if ((word & LEFT) != 0 && (word & BOTTOM) != 0 && !occupied.contains(key(r.x - 1, r.y + r.h))) {
                word |= NOTCH_BOTTOM_LEFT;
            }
            out[i] = word;
        }
        return out;
    }

    /** Whether every cell of the line starting at {@code (x, y)} and {@code w} (or, when 0, {@code h}) long is occupied. */
    private static boolean covered(Set<Long> occupied, int x, int y, int w, int h) {
        int n = Math.max(w, h);
        for (int i = 0; i < n; i++) {
            if (!occupied.contains(key(w > 0 ? x + i : x, w > 0 ? y : y + i))) {
                return false;
            }
        }
        return true;
    }

    /** The thin dark gap between two windows, in cells: a hair on a real size window, wider on a big one. */
    public static float gap(float w, float h) {
        return Math.max(0.06f, Math.min(0.5f, 0.06f * Math.min(w, h)));
    }

    /** The shared frame's width on a side of a window that is not joined: the bezel of a lone window, 0.75 cells at most. */
    public static float frame(float w, float h) {
        return Math.min(0.75f, 0.1f * Math.min(w, h));
    }

    /**
     * Where the window's lit tile starts from each side of its footprint: {@code {left, top, right, bottom}} in cells.
     * A joined side leaves half the gap (the neighbour leaves the other half), an open side the frame.
     */
    public static float[] insets(int edges, float w, float h) {
        float half = gap(w, h) / 2.0f;
        float frame = frame(w, h);
        return new float[] {(edges & LEFT) != 0 ? half : frame, (edges & TOP) != 0 ? half : frame,
                (edges & RIGHT) != 0 ? half : frame, (edges & BOTTOM) != 0 ? half : frame};
    }
}
