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
