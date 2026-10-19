package dev.bwr.mod.furniture;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Selection and collision shapes of the furniture, written for facing=north in sixteenths of a block as
 * {x0, y0, z0, x1, y1, z1} boxes (the models are authored the same way) and turned to the other facings here.
 */
final class FurnitureShapes {
    private FurnitureShapes() {
    }

    /** Index of a horizontal facing: 0 south, 1 west, 2 north, 3 east (Direction.get2DDataValue). */
    static int index(Direction facing) {
        return facing.get2DDataValue();
    }

    /** The union of boxes, mirrored left to right first when asked, then turned from north to facing. */
    static VoxelShape build(double[][] boxes, Direction facing, boolean mirrored) {
        VoxelShape shape = Shapes.empty();
        for (double[] b : boxes) {
            double x0 = mirrored ? 16 - b[3] : b[0];
            double x1 = mirrored ? 16 - b[0] : b[3];
            double z0 = b[2];
            double z1 = b[5];
            double rx0;
            double rx1;
            double rz0;
            double rz1;
            switch (facing) {
                case EAST -> {
                    rx0 = 16 - z1;
                    rx1 = 16 - z0;
                    rz0 = x0;
                    rz1 = x1;
                }
                case SOUTH -> {
                    rx0 = 16 - x1;
                    rx1 = 16 - x0;
                    rz0 = 16 - z1;
                    rz1 = 16 - z0;
                }
                case WEST -> {
                    rx0 = z0;
                    rx1 = z1;
                    rz0 = 16 - x1;
                    rz1 = 16 - x0;
                }
                default -> {
                    rx0 = x0;
                    rx1 = x1;
                    rz0 = z0;
                    rz1 = z1;
                }
            }
            shape = Shapes.or(shape, Block.box(rx0, b[1], rz0, rx1, b[4], rz1));
        }
        return shape;
    }

    /** One shape per facing, indexed by {@link #index}. */
    static VoxelShape[] perFacing(double[][] boxes, boolean mirrored) {
        VoxelShape[] out = new VoxelShape[4];
        for (Direction d : Direction.Plane.HORIZONTAL) {
            out[index(d)] = build(boxes, d, mirrored);
        }
        return out;
    }

    static double[][] concat(double[][] a, double[][] b) {
        double[][] out = new double[a.length + b.length][];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
