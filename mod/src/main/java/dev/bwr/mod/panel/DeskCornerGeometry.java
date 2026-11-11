package dev.bwr.mod.panel;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The 45 degree desk corners (CONTROL-PANELS-SPEC section 14): where a straight desk run ({@link PanelType#DESK}) turns
 * through two 45 degree bends, joined by an angled section whose level top carries components like any other desk.
 * Pure maths, no world access (like {@link PanelGeometry}); art/models/control_desk/build_desk_corner.py draws the same
 * numbers.
 *
 * <h3>The plan</h3>
 * A corner is a 2 x 2 footprint. Its coordinates here are {@code x} to the operator's right and {@code f} from the back
 * toward the operator, in blocks, as {@link PanelGeometry} puts it for a block facing {@code facing}; the straight run
 * the corner continues arrives at {@code x = 2}, in the same place a straight desk stands (its back {@code f = 0},
 * {@link PanelGeometry#DESK_FRONT} deep). The console is a band of that depth {@code W} bent twice by 45 degrees (mitred
 * joints, so its depth is the same all the way round); the second run leaves at the edge of the footprint, turned 90
 * degrees.
 * <ul>
 *   <li><b>Inside</b> (the console wraps around the operator, the U): runs along {@code f = 0..W} and {@code x = 0..W}
 *       (the second run leaves at {@code f = 2}, facing right). The operator side of the band is the concave one; its
 *       mitre vertices are {@code (X, W)} and {@code (W, X)}, {@code X =} {@link #INSIDE_BEND}.</li>
 *   <li><b>Outside</b> (the console wraps away from the operator): the first run is {@code f = 0..W}, the second leaves
 *       at {@code f = -1} backwards, facing left, in {@code x = 0.25..1}. The operator side is the convex one, with
 *       mitre vertices at {@link #OUTSIDE_BEND}.</li>
 * </ul>
 * The angled section is 1.06 m (inside) or 1.77 m (outside) long on the operator side. Its level top is the mounting
 * face, the same 16 columns by 10 rows as a straight desk (1.0 m wide, 0.625 m deep, rows counted from the foot of the
 * riser), on a frame whose columns run along the diagonal.
 *
 * <h3>Blocks</h3>
 * Only the {@link #core} block (the one holding the middle of the face) has a block entity and a face; the other three are
 * body: they hold the rest of the console's shape and forward clicks to the core ({@link DeskCornerBlock}). The face
 * reaches into the other blocks; cell space is the core's alone, so the face is its own panel group.
 */
public final class DeskCornerGeometry {
    private DeskCornerGeometry() {
    }

    private static final double SQRT2 = Math.sqrt(2.0);
    /** Depth of the console band: that of the straight desk. */
    public static final double W = PanelGeometry.DESK_FRONT;
    /** Where the inside bend's operator-side vertices lie along the runs, from the far edge of the footprint. */
    public static final double INSIDE_BEND = 1.5;
    /** The same for the outside bend's operator-side vertices. */
    public static final double OUTSIDE_BEND = 1.5;
    /** Length of the operator side of the angled section of an inside corner: sqrt(2) * (INSIDE_BEND - W). */
    public static final double INSIDE_FACE_EDGE = SQRT2 * (INSIDE_BEND - W);
    /** The back diagonal of an inside corner is the line {@code x + f = INSIDE_BACK}. */
    public static final double INSIDE_BACK = INSIDE_BEND + W - W * SQRT2;
    /** The back diagonal of an outside corner is the line {@code x - f = OUTSIDE_BACK}; the second run's back is at x = 1. */
    public static final double OUTSIDE_BACK = OUTSIDE_BEND - W + W * SQRT2;
    /** The second run of an outside corner: its front (operator side) is the line {@code x = OUTSIDE_B_FRONT}. */
    public static final double OUTSIDE_B_FRONT = 0.25;
    public static final double OUTSIDE_B_BACK = 1.0;

    private static final double TOP = PanelGeometry.DESK_TOP;
    private static final double FACE_BACK = PanelGeometry.DESK_FACE_BACK;
    private static final double SHELF = PanelGeometry.DESK_BACK_HEIGHT;
    private static final double RISER_HEAD = 0.075;
    private static final double FOOT = PanelGeometry.DESK_FOOT;
    private static final double LIP = PanelGeometry.DESK_LIP;
    private static final double UNDER = TOP - LIP;
    private static final double KNEE_TOP = FOOT + (PanelGeometry.DESK_KNEE_TOP - FOOT) * UNDER / TOP;

    public static boolean isCorner(PanelType type) {
        return type == PanelType.DESK_CORNER_INSIDE || type == PanelType.DESK_CORNER_OUTSIDE;
    }

    /** The block that holds the face and the block entity: the one the middle of the face lies in. */
    public static CornerPart core(boolean inside) {
        return inside ? CornerPart.LEFT : CornerPart.DEEP;
    }

    /** Footprint x of the near (low) edge of a part's block. */
    public static int blockX(CornerPart part) {
        return 1 - part.left();
    }

    /** Footprint f of the near (low) edge of a part's block. */
    public static int blockF(CornerPart part, boolean inside) {
        return inside ? part.deep() : -part.deep();
    }

    /**
     * How far {@code (x, f)} is from the back of the console (the depth {@code t} the profile is a function of), or a
     * value outside {@code [0, W]} when the point is not on the console.
     */
    public static double depth(boolean inside, double x, double f) {
        if (inside) {
            return Math.min(Math.min(f, x), (x + f - INSIDE_BACK) / SQRT2);
        }
        return Math.max(Math.max(f, OUTSIDE_B_BACK - x), (OUTSIDE_BACK - (x - f)) / SQRT2);
    }

    public static boolean onConsole(boolean inside, double x, double f) {
        double t = depth(inside, x, f);
        return t >= -1.0e-9 && t <= W + 1.0e-9;
    }

    /** Height of the solid above the floor at depth {@code t}: the shelf along the back, the riser, then the level top. */
    static double yHigh(double t) {
        if (t <= RISER_HEAD) {
            return SHELF;
        }
        if (t < FACE_BACK) {
            return SHELF + (TOP - SHELF) * (t - RISER_HEAD) / (FACE_BACK - RISER_HEAD);
        }
        return TOP;
    }

    /** Lowest solid at depth {@code t}: the floor behind the knee front, the knee slope, then the underside of the top. */
    static double yLow(double t) {
        if (t <= FOOT) {
            return 0.0;
        }
        if (t >= KNEE_TOP) {
            return UNDER;
        }
        return (t - FOOT) / (KNEE_TOP - FOOT) * UNDER;
    }

    // ---- the mounting frame, in the core block's own local frame

    /** Centre of the face in footprint coordinates: on the axis of the corner, mid-depth of the 10 rows. */
    public static double[] faceCentre(boolean inside) {
        double mid = FACE_BACK + (PanelGeometry.DESK_FRONT - FACE_BACK) / 2.0;
        if (inside) {
            double xf = (INSIDE_BACK + mid * SQRT2) / 2.0;
            return new double[] {xf, xf};
        }
        double diff = OUTSIDE_BACK - mid * SQRT2; // x - f
        return new double[] {(1.0 + diff) / 2.0, (1.0 - diff) / 2.0};
    }

    /** The unit step along the columns (to the operator's right) as {@code (dx, df)}. */
    public static double[] columnDirection(boolean inside) {
        double r = Math.sqrt(0.5);
        return inside ? new double[] {r, -r} : new double[] {r, r};
    }

    /** The unit step down the rows (toward the operator) as {@code (dx, df)}. */
    public static double[] rowDirection(boolean inside) {
        double r = Math.sqrt(0.5);
        return inside ? new double[] {r, r} : new double[] {-r, r};
    }

    /** Corner (0, 0) of the face as {@code (lx, f)} in the core block's local frame (it may lie outside [0, 1]). */
    public static double[] faceOrigin(boolean inside) {
        double[] centre = faceCentre(inside);
        double[] u = columnDirection(inside);
        double[] v = rowDirection(inside);
        CornerPart core = core(inside);
        double halfWidth = 0.5;
        double halfDepth = PanelGeometry.DESK_ROWS / 2.0 / PanelGeometry.GRID;
        return new double[] {centre[0] - blockX(core) - u[0] * halfWidth - v[0] * halfDepth,
                centre[1] - blockF(core, inside) - u[1] * halfWidth - v[1] * halfDepth};
    }

    // ---- shapes

    private static final Map<String, VoxelShape> SHAPES = new ConcurrentHashMap<>();

    /** The collision and outline shape of one block of a corner, for a facing (built on first use, then kept). */
    public static VoxelShape shape(boolean inside, CornerPart part, Direction facing) {
        return SHAPES.computeIfAbsent(inside + ":" + part + ":" + facing, key -> buildShape(inside, part, facing));
    }

    private static VoxelShape buildShape(boolean inside, CornerPart part, Direction facing) {
        int n = 16;
        double[][] lo = new double[n][n];
        double[][] hi = new double[n][n];
        boolean[][] on = new boolean[n][n];
        int bx = blockX(part);
        int bf = blockF(part, inside);
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                double ylo = Double.MAX_VALUE;
                double yhi = -1;
                for (int a = 0; a < 3; a++) {
                    for (int b = 0; b < 3; b++) {
                        double x = bx + (i + a / 2.0) / n;
                        double f = bf + (j + b / 2.0) / n;
                        double t = depth(inside, x, f);
                        if (t >= -1.0e-9 && t <= W + 1.0e-9) {
                            ylo = Math.min(ylo, yLow(Math.max(0, t)));
                            yhi = Math.max(yhi, yHigh(Math.max(0, t)));
                        }
                    }
                }
                if (yhi > 0) {
                    on[i][j] = true;
                    lo[i][j] = Math.floor(ylo * 16.0 - 1.0e-9) / 16.0;
                    hi[i][j] = Math.ceil(yhi * 16.0 - 1.0e-9) / 16.0;
                }
            }
        }
        // merge runs along x, then identical runs in the following rows of f
        List<double[]> boxes = new ArrayList<>();
        boolean[][] used = new boolean[n][n];
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                if (!on[i][j] || used[i][j]) {
                    continue;
                }
                int i1 = i + 1;
                while (i1 < n && on[i1][j] && !used[i1][j] && lo[i1][j] == lo[i][j] && hi[i1][j] == hi[i][j]) {
                    i1++;
                }
                int j1 = j + 1;
                outer:
                while (j1 < n) {
                    for (int k = i; k < i1; k++) {
                        if (!on[k][j1] || used[k][j1] || lo[k][j1] != lo[i][j] || hi[k][j1] != hi[i][j]) {
                            break outer;
                        }
                    }
                    j1++;
                }
                for (int k = i; k < i1; k++) {
                    for (int m = j; m < j1; m++) {
                        used[k][m] = true;
                    }
                }
                boxes.add(new double[] {i / (double) n, lo[i][j], j / (double) n, i1 / (double) n, hi[i][j], j1 / (double) n});
            }
        }
        VoxelShape shape = Shapes.empty();
        for (double[] box : boxes) {
            double xa = wx(facing, box[0], box[2]);
            double xb = wx(facing, box[3], box[5]);
            double za = wz(facing, box[0], box[2]);
            double zb = wz(facing, box[3], box[5]);
            shape = Shapes.joinUnoptimized(shape, Shapes.box(Math.min(xa, xb), box[1], Math.min(za, zb),
                    Math.max(xa, xb), box[4], Math.max(za, zb)), BooleanOp.OR);
        }
        return shape.optimize();
    }

    private static double wx(Direction facing, double lx, double f) {
        Direction right = facing.getCounterClockWise();
        return 0.5 + (lx - 0.5) * right.getStepX() + (f - 0.5) * facing.getStepX();
    }

    private static double wz(Direction facing, double lx, double f) {
        Direction right = facing.getCounterClockWise();
        return 0.5 + (lx - 0.5) * right.getStepZ() + (f - 0.5) * facing.getStepZ();
    }

    /** All four shapes of a corner, for the tests. */
    public static Map<CornerPart, VoxelShape> shapes(boolean inside, Direction facing) {
        Map<CornerPart, VoxelShape> out = new EnumMap<>(CornerPart.class);
        for (CornerPart part : CornerPart.values()) {
            out.put(part, shape(inside, part, facing));
        }
        return out;
    }
}
