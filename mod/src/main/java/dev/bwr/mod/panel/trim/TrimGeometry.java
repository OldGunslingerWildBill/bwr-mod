package dev.bwr.mod.panel.trim;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleBinaryOperator;

/**
 * Collision and outline shapes of the trim blocks, in the same local frame as
 * {@link dev.bwr.mod.panel.PanelGeometry}: {@code lx} to the operator's right, {@code ly} up and {@code f} from the
 * back toward the operator. The models in {@code assets/bwr/models/block/control_panel_trim} follow the same
 * numbers (art/models/control_panel_trim/build_trim.py); a shape is a stepped version of the model, never smaller
 * than it by more than a step.
 *
 * <p>Corners put the second wall on the operator's left ({@code lx = 0}) and the first wall behind ({@code f = 0}),
 * so a corner sits at the far left of its cell as the placer looks at it.
 */
public final class TrimGeometry {
    private TrimGeometry() {
    }

    static final double VERTICAL_DEPTH = 0.25;
    static final double CAP = 0.125;
    static final double TAN5 = Math.tan(Math.toRadians(5.0));
    static final double TAN15 = Math.tan(Math.toRadians(15.0));
    static final double CONSOLE_TOP = 0.375;
    static final double HEADER_HEIGHT = 1.0;
    static final double HOOD_BOTTOM = 0.9375;
    static final double HOOD_REACH = 0.42;
    static final double KICK = 0.125;

    /** A box in the local frame: {lx0, ly0, f0, lx1, ly1, f1}. */
    private static double[] b(double lx0, double ly0, double f0, double lx1, double ly1, double f1) {
        return new double[] {lx0, ly0, f0, lx1, ly1, f1};
    }

    /** The shape of a piece for a facing; {@code rightSide} matters only to end caps. */
    public static VoxelShape shape(TrimPiece piece, Direction facing, boolean rightSide) {
        List<double[]> boxes = new ArrayList<>();
        switch (piece) {
            case END_CAP_FLAT -> boxes.add(b(0, 0, 0, CAP, 1, VERTICAL_DEPTH));
            case END_CAP_BENCH -> {
                for (int k = 0; k < 8; k++) {
                    boxes.add(b(0, 0, k / 8.0, CAP, 1.0 - k / 8.0, (k + 1) / 8.0));
                }
            }
            case END_CAP_CONSOLE -> {
                for (int k = 0; k < 8; k++) {
                    boxes.add(b(0, 0, k / 8.0, CAP, CONSOLE_TOP - TAN15 * k / 8.0, (k + 1) / 8.0));
                }
            }
            case END_CAP_ROUND -> {
                // a bullnose 2 px out: the middle of its half-disc is deepest
                boxes.add(b(0, 0, 0.0, CAP * 0.5, 1, VERTICAL_DEPTH));
                boxes.add(b(CAP * 0.5, 0, 0.0625, CAP, 1, VERTICAL_DEPTH - 0.0625));
            }
            case HEADER_STRIP, HEADER_VENT -> header(boxes, false);
            case HEADER_CANOPY -> header(boxes, true);
            case PLINTH_KICK -> boxes.add(b(0, 0, 0, 1, KICK, KICK));
            case PLINTH_APRON -> boxes.add(b(0, 0, 0, 1, KICK, 1));
            case PLINTH_BASE -> {
                boxes.add(b(0, KICK, 0, 1, 1, 1));
                boxes.add(b(0, 0, 0, 1, KICK, 1.0 - KICK));
            }
            case CORNER_VERTICAL_INSIDE -> {
                boxes.add(b(0, 0, 0, 1, 1, VERTICAL_DEPTH));
                boxes.add(b(0, 0, VERTICAL_DEPTH, VERTICAL_DEPTH, 1, 1));
            }
            case CORNER_VERTICAL_CHAMFER -> {
                boxes.add(b(0, 0, 0, 1, 1, VERTICAL_DEPTH));
                boxes.add(b(0, 0, VERTICAL_DEPTH, VERTICAL_DEPTH, 1, 1));
                // the triangle that fills the inside corner, from (0.25, 0.25) to (0.75, 0.25) and (0.25, 0.75)
                for (int k = 0; k < 8; k++) {
                    double f0 = VERTICAL_DEPTH + k * 0.0625;
                    double reach = 0.5 - (k + 0.5) * 0.0625;     // how far along lx the triangle reaches at mid-step
                    if (reach > 0) {
                        boxes.add(b(VERTICAL_DEPTH, 0, f0, VERTICAL_DEPTH + reach, 1, f0 + 0.0625));
                    }
                }
            }
            case CORNER_VERTICAL_OUTSIDE -> boxes.add(b(0, 0, 0, VERTICAL_DEPTH, 1, VERTICAL_DEPTH));
            case CORNER_BENCH_INSIDE -> heightField(boxes, (lx, f) -> 1.0 - Math.min(f, lx));
            case CORNER_BENCH_OUTSIDE -> heightField(boxes, (lx, f) -> Math.min(1.0 - f, 1.0 - lx));
            case CORNER_BENCH_CHAMFER -> heightField(boxes, (lx, f) -> Math.min(1.0, 2.0 - lx - f));
        }
        if (piece.isEndCap() && !rightSide) {
            // 'left' sits at the right edge of the block
            List<double[]> mirrored = new ArrayList<>();
            for (double[] box : boxes) {
                mirrored.add(b(1.0 - box[3], box[1], box[2], 1.0 - box[0], box[4], box[5]));
            }
            boxes = mirrored;
        }
        return union(facing, boxes);
    }

    /**
     * An annunciator panel: a full block of the upper board, flush with the face of the vertical board below it and
     * leaning back 5 degrees as it rises; a canopy adds the hood across the top.
     */
    private static void header(List<double[]> boxes, boolean hood) {
        for (int k = 0; k < 4; k++) {
            double y0 = k * HEADER_HEIGHT / 4.0;
            boxes.add(b(0, y0, 0, 1, (k + 1) * HEADER_HEIGHT / 4.0, VERTICAL_DEPTH - y0 * TAN5));
        }
        if (hood) {
            boxes.add(b(0, HOOD_BOTTOM, 0, 1, HEADER_HEIGHT, HOOD_REACH));
        }
    }

    /** Columns of an 8 x 8 grid up to the highest the surface reaches in each. */
    private static void heightField(List<double[]> boxes, DoubleBinaryOperator height) {
        int n = 8;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                double lx0 = i / (double) n;
                double lx1 = (i + 1) / (double) n;
                double f0 = j / (double) n;
                double f1 = (j + 1) / (double) n;
                double top = Math.max(Math.max(height.applyAsDouble(lx0, f0), height.applyAsDouble(lx1, f0)),
                        Math.max(height.applyAsDouble(lx0, f1), height.applyAsDouble(lx1, f1)));
                if (top > 1.0e-6) {
                    boxes.add(b(lx0, 0, f0, lx1, Math.min(1.0, top), f1));
                }
            }
        }
    }

    // ---- local frame to world, the same maths as PanelGeometry

    private static double wx(Direction facing, double lx, double f) {
        Direction right = facing.getCounterClockWise();
        return 0.5 + (lx - 0.5) * right.getStepX() + (f - 0.5) * facing.getStepX();
    }

    private static double wz(Direction facing, double lx, double f) {
        Direction right = facing.getCounterClockWise();
        return 0.5 + (lx - 0.5) * right.getStepZ() + (f - 0.5) * facing.getStepZ();
    }

    private static VoxelShape union(Direction facing, List<double[]> boxes) {
        VoxelShape shape = Shapes.empty();
        for (double[] box : boxes) {
            double xa = wx(facing, box[0], box[2]);
            double xb = wx(facing, box[3], box[5]);
            double za = wz(facing, box[0], box[2]);
            double zb = wz(facing, box[3], box[5]);
            shape = Shapes.join(shape, Shapes.box(Math.min(xa, xb), box[1], Math.min(za, zb),
                    Math.max(xa, xb), box[4], Math.max(za, zb)), BooleanOp.OR);
        }
        return shape.optimize();
    }
}
