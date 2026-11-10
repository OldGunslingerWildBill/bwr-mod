package dev.bwr.mod.grid;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/**
 * Where the six isolated-phase-bus flanges of a Grid Tie (the generator circuit breaker) are in the world, for the CEE add-on to attach
 * its wire nodes to. The constants are the points in art/models/generator_breaker/gcb_connections.json, in block units of the model's
 * 6 x 3 x 4 box (x across the phases, y up, z along the bus; generator end z = 0, transformer end z = 4; ABB HEC 7/8, brochure 1HC0070730 E02) for a north-facing tie. They
 * are copied here, not read at run time; build_gcb.py writes the JSON, so change both together.
 */
public final class GcbConnections {
    private GcbConnections() {}
    public enum Phase { A, B, C }
    public enum End { GENERATOR, TRANSFORMER }
    public record Flange(Phase phase, End end, Vec3 pos) {}

    private static final double[] PHASE_X = {1.0, 3.0, 5.0};
    private static final double Y = 1.7;
    private static final double Z_GENERATOR = 0.0, Z_TRANSFORMER = 4.0;

    /** The flange centre in the world, for a tie whose controller (control cubicle) cell is {@code controller} and which faces {@code facing}. */
    public static Vec3 flange(BlockPos controller, Direction facing, Phase phase, End end) {
        // Model coordinates of the controller cell's centre. The cells turn about their own centres (the blockstate's y rotation),
        // which is the same turn as GridTieBlock.cellPos applies to whole cells.
        int cell = GridTieBlock.CONTROLLER;
        double cx = cell % GridTieBlock.W + .5, cy = cell / (GridTieBlock.W * GridTieBlock.D) + .5, cz = (cell / GridTieBlock.W) % GridTieBlock.D + .5;
        double dx = PHASE_X[phase.ordinal()] - cx, dz = (end == End.GENERATOR ? Z_GENERATOR : Z_TRANSFORMER) - cz;
        double rx, rz;
        switch (facing) {
            case EAST -> { rx = -dz; rz = dx; }
            case SOUTH -> { rx = -dx; rz = -dz; }
            case WEST -> { rx = dz; rz = -dx; }
            default -> { rx = dx; rz = dz; }
        }
        return new Vec3(controller.getX() + .5 + rx, controller.getY() + .5 + (Y - cy), controller.getZ() + .5 + rz);
    }

    /** All six flanges: phases A, B, C at the generator end, then at the transformer end. */
    public static List<Flange> flanges(BlockPos controller, Direction facing) {
        var out = new ArrayList<Flange>();
        for (var end : End.values()) for (var phase : Phase.values()) out.add(new Flange(phase, end, flange(controller, facing, phase, end)));
        return out;
    }
}
