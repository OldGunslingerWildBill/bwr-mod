package dev.bwr.mod.panel;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * The 45 degree desk corners (CONTROL-PANELS-SPEC section 14): where a straight desk run ({@link PanelType#DESK}) turns
 * through two 45 degree bends, joined by an angled section of {@code length} units (1 to {@link #MAX_LENGTH}), each unit
 * 1.414 m of console with a mounting face of its own. Pure maths, no world access (like {@link PanelGeometry});
 * art/models/control_desk/build_desk_corner.py draws the same numbers.
 *
 * <h3>The plan</h3>
 * A corner of length {@code n} is a {@code S x S} block footprint, {@code S = n + 1}. Coordinates are {@code x} to the
 * operator's right and {@code f} from the back toward the operator, in blocks, as {@link PanelGeometry} puts it for a
 * block facing {@code facing}. The straight run the corner continues arrives at {@code x = S} exactly where a straight
 * desk stands (back {@code f = 0}, {@link PanelGeometry#DESK_FRONT} deep). The console is a band of that depth {@code W}
 * bent twice by 45 degrees (mitred joints, constant depth). The bend vertices are at {@code n + W / sqrt 2} so the angled
 * section is exactly {@code n} times 1.414 m between its two mitres, and the unit faces, 1.414 m apart, have their
 * centres exactly one block apart along the diagonal, so no two faces share a block.
 * <ul>
 *   <li><b>Inside</b> (the console wraps around the operator, the U): the second run leaves at {@code f = S}, facing
 *       right, in {@code x = 0..W}.</li>
 *   <li><b>Outside</b> (wraps away): the second run leaves at {@code f = 1 - S} backwards, facing left, in
 *       {@code x = 0.25..1}.</li>
 * </ul>
 * Each unit's level top is a mounting face, 16 columns by 10 rows (1.0 m by 0.625 m) like a straight desk, on a frame
 * whose columns run along the diagonal; the 0.41 m between two faces carries nothing (the pitch of a diagonal run is not
 * a multiple of the 16 cells of a metre, so the faces cannot be one cell space: each is a panel group of its own).
 *
 * <h3>Blocks</h3>
 * Only the {@link #cores core} block of each face (the block its centre lies in) has a block entity; the other blocks
 * of the footprint that hold console are body only and forward clicks to the face the ray lands on
 * ({@link DeskCornerBlock}).
 */
public final class DeskCornerGeometry {
    private DeskCornerGeometry() {
    }

    private static final double SQRT2 = Math.sqrt(2.0);
    public static final int MAX_LENGTH = 1;
    /** Depth of the console band: that of the straight desk. */
    public static final double W = PanelGeometry.DESK_FRONT;
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

    /** Footprint side in blocks. */
    public static int size(int length) {
        return length + 1;
    }

    /** Where the operator-side vertices of the bends lie along the runs. */
    public static double bend(int length) {
        return length + W / SQRT2;
    }

    /** The back diagonal of an inside corner is the line {@code x + f = insideBack}. */
    public static double insideBack(int length) {
        return bend(length) + W - W * SQRT2;
    }

    /** The back diagonal of an outside corner is the line {@code x - f = outsideBack}; the second run's back is at x = 1. */
    public static double outsideBack(int length) {
        return bend(length) - W + W * SQRT2;
    }

    /** Footprint x of the near (low) edge of the block {@code left} blocks to the operator's left of the origin. */
    public static int blockX(int length, int left) {
        return size(length) - 1 - left;
    }

    /** Footprint f of the near (low) edge of the block {@code deep} blocks deep. */
    public static int blockF(boolean inside, int deep) {
        return inside ? deep : -deep;
    }

    /**
     * How far {@code (x, f)} is from the back of the console (the depth {@code t} the profile is a function of), or a
     * value outside {@code [0, W]} when the point is not on the console.
     */
    public static double depth(boolean inside, int length, double x, double f) {
        if (inside) {
            return Math.min(Math.min(f, x), (x + f - insideBack(length)) / SQRT2);
        }
        return Math.max(Math.max(f, OUTSIDE_B_BACK - x), (outsideBack(length) - (x - f)) / SQRT2);
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

    // ---- the blocks of a corner

    /** A block of a corner: blocks to the operator's left of the origin, and blocks deep (toward the operator for an inside corner). */
    public record Part(int left, int deep) {
    }

    private static final Map<String, List<Part>> PARTS = new ConcurrentHashMap<>();

    /** The blocks of the footprint that hold console, origin first (the origin is {@code (0, 0)}, where the straight run arrives). */
    public static List<Part> parts(boolean inside, int length) {
        return PARTS.computeIfAbsent(inside + ":" + length, key -> {
            List<Part> out = new ArrayList<>();
            int size = size(length);
            for (int left = 0; left < size; left++) {
                for (int deep = 0; deep < size; deep++) {
                    if (holdsConsole(inside, length, blockX(length, left), blockF(inside, deep))) {
                        out.add(new Part(left, deep));
                    }
                }
            }
            return List.copyOf(out);
        });
    }

    private static boolean holdsConsole(boolean inside, int length, int bx, int bf) {
        for (int i = 0; i <= 8; i++) {
            for (int j = 0; j <= 8; j++) {
                double t = depth(inside, length, bx + i / 8.0, bf + j / 8.0);
                if (t >= -1.0e-9 && t <= W + 1.0e-9) {
                    return true;
                }
            }
        }
        return false;
    }

    // ---- the faces

    /** Centre of face {@code face} (0 is the leftmost as the operator sees it) in footprint coordinates, at mid-depth of the 10 rows. */
    public static double[] faceCentre(boolean inside, int length, int face) {
        double mid = FACE_BACK + (PanelGeometry.DESK_FRONT - FACE_BACK) / 2.0;
        double shift = face - (length - 1) / 2.0; // one block per face along the diagonal
        if (inside) {
            double xf = (insideBack(length) + mid * SQRT2) / 2.0;
            return new double[] {xf + shift, xf - shift};
        }
        double diff = outsideBack(length) - mid * SQRT2; // x - f
        return new double[] {(1.0 + diff) / 2.0 + shift, (1.0 - diff) / 2.0 + shift};
    }

    /** The part (block) that holds the middle of face {@code face}: its block entity and block of that face's cells. */
    public static Part core(boolean inside, int length, int face) {
        double[] c = faceCentre(inside, length, face);
        int bx = (int) Math.floor(c[0]);
        int bf = (int) Math.floor(c[1]);
        return new Part(size(length) - 1 - bx, inside ? bf : -bf);
    }

    /** The face a part holds the middle of, or -1 for a body block. */
    public static int faceOf(boolean inside, int length, int left, int deep) {
        for (int face = 0; face < length; face++) {
            Part core = core(inside, length, face);
            if (core.left() == left && core.deep() == deep) {
                return face;
            }
        }
        return -1;
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

    /** Corner (0, 0) of face {@code face} as {@code (lx, f)} in the local frame of its core block (it may lie outside [0, 1]). */
    public static double[] faceOrigin(boolean inside, int length, int face) {
        double[] centre = faceCentre(inside, length, face);
        double[] u = columnDirection(inside);
        double[] v = rowDirection(inside);
        double halfWidth = 0.5;
        double halfDepth = PanelGeometry.DESK_ROWS / 2.0 / PanelGeometry.GRID;
        int bx = (int) Math.floor(centre[0]);
        int bf = (int) Math.floor(centre[1]);
        return new double[] {centre[0] - bx - u[0] * halfWidth - v[0] * halfDepth,
                centre[1] - bf - u[1] * halfWidth - v[1] * halfDepth};
    }

    // ---- shapes

    private static final Map<String, VoxelShape> SHAPES = new ConcurrentHashMap<>();

    /** The collision and outline shape of one block of a corner, for a facing (built on first use, then kept). */
    public static VoxelShape shape(boolean inside, int length, int left, int deep, Direction facing) {
        return SHAPES.computeIfAbsent(inside + ":" + length + ":" + left + ":" + deep + ":" + facing,
                key -> buildShape(inside, length, left, deep, facing));
    }

    private static VoxelShape buildShape(boolean inside, int length, int left, int deep, Direction facing) {
        int n = 16;
        double[][] lo = new double[n][n];
        double[][] hi = new double[n][n];
        boolean[][] on = new boolean[n][n];
        int bx = blockX(length, left);
        int bf = blockF(inside, deep);
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                double ylo = Double.MAX_VALUE;
                boolean any = false;
                // the top follows the depth at the middle of the cell, so a cell of the face is never covered by the riser's height
                double tMid = depth(inside, length, bx + (i + 0.5) / n, bf + (j + 0.5) / n);
                for (int a = 0; a < 3; a++) {
                    for (int b = 0; b < 3; b++) {
                        double t = depth(inside, length, bx + (i + a / 2.0) / n, bf + (j + b / 2.0) / n);
                        if (t >= -1.0e-9 && t <= W + 1.0e-9) {
                            ylo = Math.min(ylo, yLow(Math.max(0, t)));
                            any = true;
                        }
                    }
                }
                if (any) {
                    on[i][j] = true;
                    lo[i][j] = Math.floor(ylo * 16.0 - 1.0e-9) / 16.0;
                    hi[i][j] = Math.ceil(yHigh(Math.min(W, Math.max(0, tMid))) * 16.0 - 1.0e-9) / 16.0;
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
}
