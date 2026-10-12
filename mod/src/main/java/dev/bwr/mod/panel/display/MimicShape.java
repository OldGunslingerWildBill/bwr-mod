package dev.bwr.mod.panel.display;

/**
 * Which sides of its footprint a mimic line piece connects to (pure). The
 * piece is a bus of some thickness running from the centre to each connected
 * side:
 * <pre>
 *   straight   two opposite sides (the long axis; a square piece uses its rotation)
 *   corner     two adjacent sides:  rotation 0 right+down, 1 down+left, 2 left+up, 3 up+right
 *   tee        three sides, the one not connected is: rotation 0 up, 1 right, 2 down, 3 left
 * </pre>
 */
public final class MimicShape {
    private MimicShape() {
    }

    public static final int RIGHT = 1;
    public static final int DOWN = 2;
    public static final int LEFT = 4;
    public static final int UP = 8;

    public static final String STRAIGHT = "straight";
    public static final String CORNER = "corner";
    public static final String TEE = "tee";
    public static final java.util.List<String> SHAPES = java.util.List.of(STRAIGHT, CORNER, TEE);

    /** Bit set of the connected sides for a piece of {@code w x h} cells. */
    public static int arms(String shape, int rotation, int w, int h) {
        int r = ((rotation % 4) + 4) % 4;
        if (CORNER.equals(shape)) {
            return switch (r) {
                case 0 -> RIGHT | DOWN;
                case 1 -> DOWN | LEFT;
                case 2 -> LEFT | UP;
                default -> UP | RIGHT;
            };
        }
        if (TEE.equals(shape)) {
            int all = RIGHT | DOWN | LEFT | UP;
            return all & ~switch (r) {
                case 0 -> UP;
                case 1 -> RIGHT;
                case 2 -> DOWN;
                default -> LEFT;
            };
        }
        boolean horizontal = w > h || (w == h && r % 2 == 0);
        return horizontal ? RIGHT | LEFT : UP | DOWN;
    }

    /** The bus thickness in cells actually drawn: at least 1, at most the short side of the footprint. */
    public static int thickness(int requested, int w, int h) {
        return Math.max(1, Math.min(requested, Math.min(w, h)));
    }
}
