package dev.bwr.mod.floodlight;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Shapes of a north-facing model box turned to each facing, with the same turns as the blockstate files (x 90 = down). */
final class FacingLampShapes {
    private FacingLampShapes() {
    }

    /** The box {@code lo}-{@code hi} (pixels, north-facing model) for every facing, indexed by {@link Direction#get3DDataValue}. */
    static VoxelShape[] of(double[] lo, double[] hi) {
        VoxelShape[] shapes = new VoxelShape[6];
        for (Direction facing : Direction.values()) {
            double[] a = rotate(lo, facing);
            double[] b = rotate(hi, facing);
            shapes[facing.get3DDataValue()] = Block.box(Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.min(a[2], b[2]),
                    Math.max(a[0], b[0]), Math.max(a[1], b[1]), Math.max(a[2], b[2]));
        }
        return shapes;
    }

    private static double[] rotate(double[] p, Direction facing) {
        double x = p[0], y = p[1], z = p[2];
        return switch (facing) {
            case NORTH -> new double[] {x, y, z};
            case SOUTH -> new double[] {16 - x, y, 16 - z};
            case EAST -> new double[] {16 - z, y, x};
            case WEST -> new double[] {z, y, 16 - x};
            case UP -> new double[] {x, 16 - z, y};
            case DOWN -> new double[] {x, z, 16 - y};
        };
    }
}
