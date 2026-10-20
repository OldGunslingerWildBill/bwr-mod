package dev.bwr.mod.panel;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.EnumMap;
import java.util.Map;

/**
 * Where the 16 x 16 mounting grid of each panel style sits in a block, for each
 * facing. Pure math, no Minecraft world access, so the server (hit tests), the
 * client (ghost preview) and the renderer all agree.
 *
 * <h3>Coordinates</h3>
 * <ul>
 *   <li><b>Cell space</b>: {@code (cu, cv)} measured in grid cells from the
 *       top-left of the face of one block, {@code cu} to the operator's right,
 *       {@code cv} down the face. Cell space is continuous across a group, so
 *       {@code cu} may run past 16 into the next column and {@code cv} into the
 *       next row; the mounting plane really is one plane.</li>
 *   <li><b>Component space</b> adds {@code cz}: elevation above the face, in
 *       1/16 block units.</li>
 *   <li><b>Block space</b>: 0..1 inside the block, as the renderer draws.</li>
 * </ul>
 *
 * <p>A grid cell is 1/16 block wide. On sloped faces it is {@code vLength/16}
 * block long down the slope ({@link Frame#vLength}); models that need square
 * drawing (dials) divide by it.
 */
public final class PanelGeometry {
    private PanelGeometry() {
    }

    public static final int GRID = 16;
    /** Largest span of one component, in blocks, either way (spec section 2). */
    public static final int MAX_SPAN_BLOCKS = 4;
    /** The most any component type may span: types that need more than {@link #MAX_SPAN_BLOCKS} say so themselves. */
    public static final int ABSOLUTE_MAX_SPAN_BLOCKS = 8;

    private static final double TAN15 = Math.tan(Math.toRadians(15.0));
    /** Height of the back edge of the console desk. */
    private static final double CONSOLE_TOP = 0.375;
    private static final double VERTICAL_DEPTH = 0.25;
    /**
     * The duplex board (the owner's profile, one block = 1 m): the slab behind the board face is 0.8 deep, so the rear
     * 0.2 of the back blocks is empty; the bench falls from 1.0 at the board to 0.75 at its front edge; the tilted
     * header face rises from the board's top edge to half a block forward at the top of its block.
     */
    public static final double DUPLEX_SLAB_REAR = 0.2;
    public static final double DUPLEX_BENCH_FRONT = 0.75;
    public static final double DUPLEX_HEADER_REACH = 0.5;

    /**
     * The angled trim blocks (see {@link PanelType#HEADER_TILT} and the chamfers). The header panel is the vertical
     * board's face at its foot, leaning back 5 degrees over one block of height; the vertical chamfer facet runs from
     * (0.75, 0.25) to (0.25, 0.75) and the bench chamfer facet is the plane lx + ly + f = 2 (local frame, see
     * {@link #wx}), exactly as the models draw them.
     */
    public static final double HEADER_TILT_TAN = Math.tan(Math.toRadians(5.0));
    /** Cells along the chamfer facet that lie wholly on it: its length is 0.5 * sqrt(2) block = 11.31 cells. */
    public static final int CHAMFER_VERTICAL_COLUMNS = 11;
    private static final double CHAMFER_VERTICAL_FACET = 0.5 * Math.sqrt(2.0);
    /** Half the bench chamfer's top edge in cells (1.414 blocks / 2 * 16) and the cell rows it narrows over. */
    private static final double CHAMFER_BENCH_HALF_TOP_CELLS = Math.sqrt(2.0) / 2.0 * GRID;

    /** The face of one panel style for one facing. All vectors are in block space. */
    public static final class Frame {
        /** Block-space position of the top-left corner of cell (0,0). */
        public final double ox, oy, oz;
        /** Block-space step for one cell to the right. */
        public final double ux, uy, uz;
        /** Block-space step for one cell down the face. */
        public final double vx, vy, vz;
        /** Unit normal out of the face. */
        public final double nx, ny, nz;
        /** Real length of one cell down the face relative to one cell across (1.0 on a vertical board). */
        public final double vLength;
        /** Unit vector along the columns, and unit vector down the rows (with {@link #nx} the face normal: an orthonormal basis). */
        public final double rightX, rightY, rightZ, downX, downY, downZ;

        Frame(double ox, double oy, double oz, double ux, double uy, double uz,
              double vx, double vy, double vz, double nx, double ny, double nz) {
            this.ox = ox;
            this.oy = oy;
            this.oz = oz;
            this.ux = ux;
            this.uy = uy;
            this.uz = uz;
            this.vx = vx;
            this.vy = vy;
            this.vz = vz;
            this.nx = nx;
            this.ny = ny;
            this.nz = nz;
            double uLen = Math.sqrt(ux * ux + uy * uy + uz * uz);
            double vLen = Math.sqrt(vx * vx + vy * vy + vz * vz);
            this.vLength = vLen * GRID;
            this.rightX = ux / uLen;
            this.rightY = uy / uLen;
            this.rightZ = uz / uLen;
            this.downX = vx / vLen;
            this.downY = vy / vLen;
            this.downZ = vz / vLen;
        }

        /**
         * Block-space x of a direction given in true-length component space: {@code cx} along the columns,
         * {@code cy} down the rows, {@code cz} out of the face (a baked model's normals; its geometry is in that
         * space once it has been fitted with the row aspect, see {@code Placement}).
         */
        public double directionX(double cx, double cy, double cz) {
            return cx * rightX + cy * downX + cz * nx;
        }

        public double directionY(double cx, double cy, double cz) {
            return cx * rightY + cy * downY + cz * ny;
        }

        public double directionZ(double cx, double cy, double cz) {
            return cx * rightZ + cy * downZ + cz * nz;
        }

        public double x(double cu, double cv, double cz) {
            return ox + ux * cu + vx * cv + nx * cz / GRID;
        }

        public double y(double cu, double cv, double cz) {
            return oy + uy * cu + vy * cv + ny * cz / GRID;
        }

        public double z(double cu, double cv, double cz) {
            return oz + uz * cu + vz * cv + nz * cz / GRID;
        }
    }

    private static final Map<PanelType, Map<Direction, Frame>> FRAMES = new EnumMap<>(PanelType.class);
    private static final Map<PanelType, Map<Direction, VoxelShape>> SHAPES = new EnumMap<>(PanelType.class);

    static {
        for (PanelType type : PanelType.values()) {
            Map<Direction, Frame> frames = new EnumMap<>(Direction.class);
            Map<Direction, VoxelShape> shapes = new EnumMap<>(Direction.class);
            for (Direction facing : Direction.Plane.HORIZONTAL) {
                frames.put(facing, buildFrame(type, facing));
                shapes.put(facing, buildShape(type, facing));
            }
            FRAMES.put(type, frames);
            SHAPES.put(type, shapes);
        }
    }

    public static Frame frame(PanelType type, Direction facing) {
        return FRAMES.get(type).get(facing);
    }

    public static VoxelShape shape(PanelType type, Direction facing) {
        return SHAPES.get(type).get(facing);
    }

    // ---- local frame: lx to the operator's right, ly up, f from the back toward the operator.

    private static double wx(Direction facing, double lx, double f) {
        Direction right = facing.getCounterClockWise();
        return 0.5 + (lx - 0.5) * right.getStepX() + (f - 0.5) * facing.getStepX();
    }

    private static double wz(Direction facing, double lx, double f) {
        Direction right = facing.getCounterClockWise();
        return 0.5 + (lx - 0.5) * right.getStepZ() + (f - 0.5) * facing.getStepZ();
    }

    /** World-space step of a local displacement (lx to the operator's right, ly up, f toward the operator). */
    private static double dx(Direction facing, double dlx, double df) {
        return dlx * facing.getCounterClockWise().getStepX() + df * facing.getStepX();
    }

    private static double dz(Direction facing, double dlx, double df) {
        return dlx * facing.getCounterClockWise().getStepZ() + df * facing.getStepZ();
    }

    /**
     * A frame given in the local frame: origin of cell (0, 0) as (lx, ly, f), the step for one cell across and one
     * cell down, and the outward unit normal. A cell is 1/16 block wide along the face (the elevation unit), whatever
     * the slant.
     */
    private static Frame localFrame(Direction facing, double[] origin, double[] across, double[] down, double[] normal) {
        return new Frame(wx(facing, origin[0], origin[2]), origin[1], wz(facing, origin[0], origin[2]),
                dx(facing, across[0], across[2]), across[1], dz(facing, across[0], across[2]),
                dx(facing, down[0], down[2]), down[1], dz(facing, down[0], down[2]),
                dx(facing, normal[0], normal[2]), normal[1], dz(facing, normal[0], normal[2]));
    }

    private static Frame buildFrame(PanelType type, Direction facing) {
        switch (type) {
            case CHAMFER_VERTICAL -> {
                // The facet from its left end B = (0.25, 0.75) to A = (0.75, 0.25) at the operator's right, normal
                // toward (+lx, +f). 11 whole cells sit on it (the margin 0.157 cell at each end is left unused).
                double r = Math.sqrt(0.5);
                double margin = (CHAMFER_VERTICAL_FACET * GRID - CHAMFER_VERTICAL_COLUMNS) / 2.0 / GRID;
                return localFrame(facing, new double[] {0.25 + r * margin, 1.0, 0.75 - r * margin},
                        new double[] {r / GRID, 0.0, -r / GRID}, new double[] {0.0, -1.0 / GRID, 0.0},
                        new double[] {r, 0.0, r});
            }
            case CHAMFER_BENCH -> {
                // The triangle (lx, ly, f) = (0, 1, 1), (1, 1, 0), (1, 0, 1): its top edge is 1.414 long, cell 0 starts
                // 0.207 along it so that the 16 cells are centred, and the rows run down the line of steepest slope
                // to the apex one block below (0.5, -1, 0.5 per block of rows).
                double r = Math.sqrt(0.5);
                double start = (Math.sqrt(2.0) - 1.0) / 2.0;
                double s3 = 1.0 / Math.sqrt(3.0);
                return localFrame(facing, new double[] {r * start, 1.0, 1.0 - r * start},
                        new double[] {r / GRID, 0.0, -r / GRID}, new double[] {0.5 / GRID, -1.0 / GRID, 0.5 / GRID},
                        new double[] {s3, s3, s3});
            }
            default -> {
                return buildAxisFrame(type, facing);
            }
        }
    }

    private static Frame buildAxisFrame(PanelType type, Direction facing) {
        double ly0;
        double f0;
        double drop; // y lost going one block down the face
        double forward; // f gained going one block down the face
        switch (type) {
            case VERTICAL -> {
                ly0 = 1.0;
                f0 = VERTICAL_DEPTH;
                drop = 1.0;
                forward = 0.0;
            }
            case BACK_PANEL, DUPLEX_BOARD -> {
                ly0 = 1.0;
                f0 = 1.0;
                drop = 1.0;
                forward = 0.0;
            }
            case HEADER_TILT -> {
                // Flush with the vertical board's face at the foot of the block (f = 0.25) and leaning back as it
                // rises: row 0 is the top edge, 0.0875 further back, and one block down the face comes 0.0875 forward.
                ly0 = 1.0;
                f0 = VERTICAL_DEPTH - HEADER_TILT_TAN;
                drop = 1.0;
                forward = HEADER_TILT_TAN;
            }
            case DUPLEX_BENCH -> {
                ly0 = 1.0;
                f0 = 0.0;
                drop = 1.0 - DUPLEX_BENCH_FRONT;
                forward = 1.0;
            }
            case DUPLEX_HEADER -> {
                // Row 0 is the top edge, half a block out; down the face goes back to the board's top edge, so the
                // normal points forward and down.
                ly0 = 1.0;
                f0 = DUPLEX_HEADER_REACH;
                drop = 1.0;
                forward = -DUPLEX_HEADER_REACH;
            }
            case BENCHBOARD -> {
                ly0 = 1.0;
                f0 = 0.0;
                drop = 1.0;
                forward = 1.0;
            }
            default -> {
                ly0 = CONSOLE_TOP;
                f0 = 0.0;
                drop = TAN15;
                forward = 1.0;
            }
        }
        Direction right = facing.getCounterClockWise();
        // Local normal is perpendicular to U=(1,0,0) and V=(0,-drop,forward), toward the operator.
        double length = Math.sqrt(drop * drop + forward * forward);
        double normalUp = forward / length;
        double normalForward = drop / length;
        return new Frame(wx(facing, 0.0, f0), ly0, wz(facing, 0.0, f0),
                right.getStepX() / (double) GRID, 0.0, right.getStepZ() / (double) GRID,
                facing.getStepX() * forward / GRID, -drop / GRID, facing.getStepZ() * forward / GRID,
                facing.getStepX() * normalForward, normalUp, facing.getStepZ() * normalForward);
    }

    private static VoxelShape buildShape(PanelType type, Direction facing) {
        VoxelShape shape = Shapes.empty();
        switch (type) {
            case VERTICAL -> shape = box(facing, 0, 0, 0, 1, 1, VERTICAL_DEPTH);
            case BACK_PANEL -> shape = box(facing, 0, 0, 0, 1, 1, 1);
            // The angled trim pieces: the outline is the trim block's own (the canopy adds a hood to the strip's).
            case HEADER_TILT -> shape = dev.bwr.mod.panel.trim.TrimGeometry.shape(
                    dev.bwr.mod.panel.trim.TrimPiece.HEADER_STRIP, facing, true);
            case CHAMFER_VERTICAL -> shape = dev.bwr.mod.panel.trim.TrimGeometry.shape(
                    dev.bwr.mod.panel.trim.TrimPiece.CORNER_VERTICAL_CHAMFER, facing, true);
            case CHAMFER_BENCH -> shape = dev.bwr.mod.panel.trim.TrimGeometry.shape(
                    dev.bwr.mod.panel.trim.TrimPiece.CORNER_BENCH_CHAMFER, facing, true);
            case DUPLEX_BOARD -> shape = box(facing, 0, 0, DUPLEX_SLAB_REAR, 1, 1, 1);
            case DUPLEX_BENCH -> {
                for (int k = 0; k < 8; k++) {
                    shape = Shapes.join(shape,
                            box(facing, 0, 0, k / 8.0, 1, 1.0 - (1.0 - DUPLEX_BENCH_FRONT) * k / 8.0, (k + 1) / 8.0),
                            BooleanOp.OR);
                }
            }
            case DUPLEX_HEADER -> {
                // Slices of the wedge, each reaching the face at its own top, so a ray to the face always finds it.
                for (int k = 0; k < 8; k++) {
                    shape = Shapes.join(shape,
                            box(facing, 0, k / 8.0, 0, 1, (k + 1) / 8.0, DUPLEX_HEADER_REACH * (k + 1) / 8.0),
                            BooleanOp.OR);
                }
            }
            case BENCHBOARD -> {
                for (int k = 0; k < 8; k++) {
                    shape = Shapes.join(shape, box(facing, 0, 0, k / 8.0, 1, 1.0 - k / 8.0, (k + 1) / 8.0),
                            BooleanOp.OR);
                }
            }
            default -> {
                for (int k = 0; k < 8; k++) {
                    shape = Shapes.join(shape,
                            box(facing, 0, 0, k / 8.0, 1, CONSOLE_TOP - TAN15 * k / 8.0, (k + 1) / 8.0),
                            BooleanOp.OR);
                }
            }
        }
        return shape.optimize();
    }

    /**
     * Whether cell {@code (cx, cy)} of a block of this style lies on its mounting surface, so that a component can
     * stand there. Every cell of a board, bench or console does; the grid of a chamfer is a full 16 x 16 square laid
     * on a facet that is smaller than it:
     * <ul>
     *   <li>{@link PanelType#CHAMFER_VERTICAL}: the facet is 11.31 cells wide, so columns 0 to 10;</li>
     *   <li>{@link PanelType#CHAMFER_BENCH}: the facet is a triangle that narrows from 22.6 cells at the top to a point
     *       at the bottom, so a cell is usable when both its lower corners are on the triangle.</li>
     * </ul>
     */
    public static boolean cellUsable(PanelType type, int cx, int cy) {
        if (cx < 0 || cy < 0 || cx >= GRID || cy >= GRID) {
            return false;
        }
        return switch (type) {
            case CHAMFER_VERTICAL -> cx < CHAMFER_VERTICAL_COLUMNS;
            case CHAMFER_BENCH -> {
                double half = CHAMFER_BENCH_HALF_TOP_CELLS * (1.0 - (cy + 1) / (double) GRID);
                yield Math.max(Math.abs(cx - GRID / 2.0), Math.abs(cx + 1 - GRID / 2.0)) <= half + 1.0e-9;
            }
            default -> true;
        };
    }

    /** A box given in the local frame, rotated to world for {@code facing}. */
    private static VoxelShape box(Direction facing, double lx0, double ly0, double f0,
                                  double lx1, double ly1, double f1) {
        double xa = wx(facing, lx0, f0);
        double xb = wx(facing, lx1, f1);
        double za = wz(facing, lx0, f0);
        double zb = wz(facing, lx1, f1);
        return Shapes.box(Math.min(xa, xb), ly0, Math.min(za, zb), Math.max(xa, xb), ly1, Math.max(za, zb));
    }

    /**
     * Intersects a view ray with the mounting plane of one block and returns the
     * position in continuous cell space.
     *
     * @param out receives {@code cu, cv}; meaningful only when this returns true
     * @return false when the ray is parallel to, or moving away from, the face
     */
    public static boolean rayToCell(Frame frame, double blockX, double blockY, double blockZ,
                                    double eyeX, double eyeY, double eyeZ,
                                    double dirX, double dirY, double dirZ, double[] out) {
        double ex = eyeX - blockX;
        double ey = eyeY - blockY;
        double ez = eyeZ - blockZ;
        double denom = dirX * frame.nx + dirY * frame.ny + dirZ * frame.nz;
        if (Math.abs(denom) < 1.0e-6) {
            return false;
        }
        double t = ((frame.ox - ex) * frame.nx + (frame.oy - ey) * frame.ny + (frame.oz - ez) * frame.nz) / denom;
        if (t < 0.0) {
            return false;
        }
        double px = ex + dirX * t - frame.ox;
        double py = ey + dirY * t - frame.oy;
        double pz = ez + dirZ * t - frame.oz;
        double uu = frame.ux * frame.ux + frame.uy * frame.uy + frame.uz * frame.uz;
        double vv = frame.vx * frame.vx + frame.vy * frame.vy + frame.vz * frame.vz;
        out[0] = (px * frame.ux + py * frame.uy + pz * frame.uz) / uu;
        out[1] = (px * frame.vx + py * frame.vy + pz * frame.vz) / vv;
        return true;
    }
}
