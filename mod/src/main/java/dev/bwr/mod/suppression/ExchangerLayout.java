package dev.bwr.mod.suppression;

import com.google.gson.JsonParser;
import dev.bwr.mod.eccs.TurbineAssemblyBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Footprint of the full-size RHR heat exchanger, read from {@code data/bwr/exchanger_models/rhr_heat_exchanger.json} (written by
 * tools-export-hx-multiblock.py from the Blender mesh): size, controller cell, the four flanges and every cell's collision box.
 * Offsets are for a machine facing north and are turned with {@link TurbineAssemblyBlock#turn}, as the pump assemblies do.
 */
public final class ExchangerLayout {
    public record Port(RhrHeatExchangerBlock.HxPort role, BlockPos cell, Direction face) {}

    public final int width, height, depth, controller;
    public final List<Port> ports = new ArrayList<>();
    private final VoxelShape[][] shapes;
    private final int[][] turned = new int[4][];

    ExchangerLayout(String id) {
        try (var stream = ExchangerLayout.class.getResourceAsStream("/data/bwr/exchanger_models/" + id + ".json")) {
            if (stream == null) throw new IllegalStateException("Missing exchanger manifest " + id);
            var json = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            width = json.get("width").getAsInt(); height = json.get("height").getAsInt(); depth = json.get("depth").getAsInt();
            var c = json.getAsJsonArray("controller_cell");
            controller = c.get(0).getAsInt() + width * (c.get(2).getAsInt() + depth * c.get(1).getAsInt());
            shapes = new VoxelShape[4][count()];
            for (var row : shapes) Arrays.fill(row, Shapes.empty());
            for (var e : json.getAsJsonArray("game_ports")) {
                var p = e.getAsJsonObject(); var a = p.getAsJsonArray("cell");
                ports.add(new Port(RhrHeatExchangerBlock.HxPort.valueOf(p.get("role").getAsString()),
                        new BlockPos(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt()).subtract(grid(controller)),
                        Direction.byName(p.get("face").getAsString())));
            }
            for (var e : json.getAsJsonArray("cells")) {
                var cell = e.getAsJsonObject(); int n = cell.get("index").getAsInt(); var a = cell.getAsJsonArray("bounds");
                if (a.isEmpty()) continue;
                double x0 = a.get(0).getAsDouble(), y0 = a.get(1).getAsDouble(), z0 = a.get(2).getAsDouble();
                double x1 = a.get(3).getAsDouble(), y1 = a.get(4).getAsDouble(), z1 = a.get(5).getAsDouble();
                shapes[Direction.NORTH.get2DDataValue()][n] = Shapes.box(x0, y0, z0, x1, y1, z1);
                shapes[Direction.EAST.get2DDataValue()][n] = Shapes.box(1 - z1, y0, x0, 1 - z0, y1, x1);
                shapes[Direction.SOUTH.get2DDataValue()][n] = Shapes.box(1 - x1, y0, 1 - z1, 1 - x0, y1, 1 - z0);
                shapes[Direction.WEST.get2DDataValue()][n] = Shapes.box(z0, y0, 1 - x1, z1, y1, 1 - x0);
            }
        } catch (java.io.IOException ex) { throw new IllegalStateException("Cannot load " + id, ex); }
    }

    public int count() { return width * height * depth; }
    BlockPos grid(int cell) { return new BlockPos(cell % width, cell / (width * depth), (cell / width) % depth); }
    /** A cell's offset from the controller for a machine facing north. */
    public BlockPos offset(int cell) { return grid(cell).subtract(grid(controller)); }
    /** A cell's world position for a controller at {@code root} facing {@code facing}. */
    public BlockPos world(BlockPos root, Direction facing, int cell) { return root.offset(TurbineAssemblyBlock.turn(offset(cell), facing)); }
    public VoxelShape shape(Direction facing, int cell) { return cell >= 0 && cell < count() ? shapes[facing.get2DDataValue()][cell] : Shapes.empty(); }
    public Port port(RhrHeatExchangerBlock.HxPort role) {
        for (var p : ports) if (p.role() == role) return p;
        throw new IllegalArgumentException("No " + role);
    }

    /** Every cell's offset from the controller, already turned to {@code facing}, as x, y, z triples. */
    int[] turned(Direction facing) {
        int k = facing.get2DDataValue();
        int[] t = turned[k];
        if (t == null) {
            t = new int[count() * 3];
            for (int i = 0; i < count(); i++) {
                BlockPos o = TurbineAssemblyBlock.turn(offset(i), facing);
                t[3 * i] = o.getX(); t[3 * i + 1] = o.getY(); t[3 * i + 2] = o.getZ();
            }
            turned[k] = t;
        }
        return t;
    }
}
