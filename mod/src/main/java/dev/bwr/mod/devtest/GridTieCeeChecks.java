package dev.bwr.mod.devtest;

import com.george_vi.electroenergetics.CEEBlocks;
import com.george_vi.electroenergetics.CEEWireTypes;
import com.george_vi.electroenergetics.content.electronic_components.resistor.ResistorBlockEntity;
import com.george_vi.electroenergetics.devices.device.DevicesSavedData;
import com.george_vi.electroenergetics.foundation.device.ElectricalDeviceBlock;
import com.george_vi.electroenergetics.foundation.nodes.InWorldNode;
import com.george_vi.electroenergetics.simulation.infrastructure.InfrastructureSavedData;
import dev.bwr.mod.compat.cee.CeeGridTieBlock;
import dev.bwr.mod.compat.cee.GridTieDevice;
import dev.bwr.mod.grid.GcbConnections;
import dev.bwr.mod.grid.GridTieBlock;
import dev.bwr.mod.grid.GridTieBlockEntity;
import dev.bwr.mod.registry.BwrBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The Grid Tie against the real Create: Electro Energetics (only with {@code -PwithCee}; {@link GridTieCeeTests} checks first, because
 * this names CEE types): its six nodes, the wires that go with the block, and the poles switching a circuit made of CEE's creative
 * battery and creative resistor joined by creative wire.
 */
final class GridTieCeeChecks {
    private GridTieCeeChecks() {
    }

    private static final Direction FACING = Direction.EAST;
    private static final double VOLTS = 300, OHMS = 100;

    static AABB box(BlockPos min, BlockPos max) {
        return new AABB(min.getX(), min.getY(), min.getZ(), max.getX() + 1, max.getY() + 1, max.getZ() + 1);
    }

    static void clear(ServerLevel l, BlockPos min, BlockPos max) {
        var sd = InfrastructureSavedData.load(l);
        for (var p : BlockPos.betweenClosed(min, max)) {
            if (!l.getBlockState(p).isAir()) sd.removeNodes(p);
            l.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
        }
        for (var e : l.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, box(min, max).inflate(4))) e.discard();
    }

    /** A full tie facing east at {@code at}, built the way a placer does. */
    static GridTieBlockEntity placeTie(ServerLevel l, BlockPos at) {
        var s = BwrBlocks.GRID_TIE.get().placementState().setValue(GridTieBlock.FACING, FACING);
        l.setBlock(at, s, 3);
        BwrBlocks.GRID_TIE.get().setPlacedBy(l, at, s, null, new ItemStack(BwrBlocks.GRID_TIE.get()));
        return (GridTieBlockEntity) l.getBlockEntity(at);
    }

    /** A CEE block with its nodes registered now (the block's own tick would do it a tick later). */
    static void placeCee(ServerLevel l, net.minecraft.world.level.block.Block block, BlockPos pos) {
        var state = block.defaultBlockState();
        l.setBlock(pos, state, 3);
        ((ElectricalDeviceBlock<?>) block).ensureNodesExist(l, pos, state);
    }

    static InWorldNode node(InfrastructureSavedData sd, BlockPos pos, int id) {
        var n = new InWorldNode(id, pos);
        return sd.hasNode(n) ? n : null;
    }

    static void wire(InfrastructureSavedData sd, InWorldNode a, InWorldNode b) {
        sd.connect(a, b, CEEWireTypes.CREATIVE.get());
    }

    static int wiresAt(InfrastructureSavedData sd, BlockPos pos) {
        int n = 0;
        for (var node : sd.getNodesAt(pos)) n += sd.getConnections(node).size();
        return n;
    }

    /** The nodes of a tie registered, with the flange labels, positions and size; a legacy cube and the other cells have none. */
    static void nodesAndLabels(GameTestHelper h, BlockPos at) {
        var l = h.getLevel();
        var sd = InfrastructureSavedData.load(l);
        placeTie(l, at);
        h.assertTrue(sd.getNodesAt(at).size() == 6, "the controller has " + sd.getNodesAt(at).size() + " nodes, not 6");
        var block = (CeeGridTieBlock) l.getBlockState(at).getBlock();
        String[] labels = {"X gen", "Y gen", "Z gen", "X xfmr", "Y xfmr", "Z xfmr"};
        var flanges = GcbConnections.flanges(at, FACING);
        for (int id = 0; id < 6; id++) {
            var n = node(sd, at, id);
            h.assertTrue(n != null, "node " + id + " is not registered");
            var world = n.getPosition(l);
            h.assertTrue(world.distanceTo(flanges.get(id).pos()) < 1e-3, "node " + id + " is at " + world + ", the flange is at " + flanges.get(id).pos());
            h.assertTrue(labels[id].equals(block.getNodeLabel(l, at, l.getBlockState(at), id).getString()), "label " + id);
            h.assertTrue(block.getNodeSize(l, at, l.getBlockState(at), id) == 1.3f, "size " + id);
        }
        // no other cell has a node, and neither does a tie saved as one block
        for (int i = 0; i < GridTieBlock.CELLS; i++) {
            if (i == GridTieBlock.CONTROLLER) continue;
            var p = GridTieBlock.cellPos(at, FACING, i);
            h.assertTrue(block.getNodePositions(l, p, l.getBlockState(p)).isEmpty() && sd.getNodesAt(p).isEmpty(), "cell " + i + " has nodes");
        }
    }

    static void legacyCubeHasNoNodes(GameTestHelper h, BlockPos at) {
        var l = h.getLevel();
        var sd = InfrastructureSavedData.load(l);
        l.setBlock(at, BwrBlocks.GRID_TIE.get().defaultBlockState(), 3);
        var s = l.getBlockState(at);
        h.assertTrue(!s.getValue(GridTieBlock.ASSEMBLED) && s.getBlock() instanceof CeeGridTieBlock, "setup: " + s);
        var block = (CeeGridTieBlock) s.getBlock();
        h.assertTrue(block.getNodePositions(l, at, s).isEmpty() && block.getNodePosition(l, at, s, 0) == null, "the legacy cube offers nodes");
        h.assertTrue(sd.getNodesAt(at).isEmpty(), "the legacy cube registered nodes");
    }

    /** closestNode, asked about a non-controller cell that contains a flange, finds that flange's node of the controller. */
    static void closestNodeThroughFlangeCells(GameTestHelper h, BlockPos at) {
        var l = h.getLevel();
        var sd = InfrastructureSavedData.load(l);
        placeTie(l, at);
        var flanges = GcbConnections.flanges(at, FACING);
        for (int id = 0; id < 6; id++) {
            var f = flanges.get(id).pos();
            BlockPos cell = null;
            for (int i = 0; i < GridTieBlock.CELLS && cell == null; i++) {
                var p = GridTieBlock.cellPos(at, FACING, i);
                if (i != GridTieBlock.CONTROLLER && new AABB(p).inflate(1e-6).contains(f)) cell = p;
            }
            h.assertTrue(cell != null, "no flange cell for node " + id);
            var found = InWorldNode.closestNode(l, cell, l.getBlockState(cell), 0.5f, f);
            h.assertTrue(found != null && found.id() == id && found.sourcePos().equals(at),
                    "from cell " + cell + " (flange " + id + ") closestNode gave " + found + " (the mixin did not redirect to the controller)");
        }
        // a hit point nowhere near a node finds none, and a plain stone block is untouched by the redirect
        var far = Vec3.atCenterOf(at).add(0, 40, 0);
        h.assertTrue(InWorldNode.closestNode(l, GridTieBlock.cellPos(at, FACING, 0), l.getBlockState(GridTieBlock.cellPos(at, FACING, 0)), 0.5f, far) == null, "a far click found a node");
    }

    /** The wires go with the block: a survival player breaking a flange cell, the controller removed by code, and a tie that loses its assembled flag. */
    static void wiresGoWithTheBlock(GameTestHelper h, BlockPos at, BlockPos min, BlockPos max, java.util.function.Consumer<BlockPos> breakIt, String how) {
        var l = h.getLevel();
        var sd = InfrastructureSavedData.load(l);
        clear(l, min, max);
        placeTie(l, at);
        var battery = at.offset(0, 6, -8);
        var resistor = at.offset(0, 6, 8);
        placeCee(l, CEEBlocks.CREATIVE_BATTERY.get(), battery);
        placeCee(l, CEEBlocks.CREATIVE_RESISTOR.get(), resistor);
        wire(sd, node(sd, at, 0), node(sd, battery, 0));
        wire(sd, node(sd, at, 3), node(sd, resistor, 0));
        h.assertTrue(wiresAt(sd, at) == 2 && wiresAt(sd, battery) == 1 && wiresAt(sd, resistor) == 1, how + ": setup wires " + wiresAt(sd, at));
        breakIt.accept(at);
        h.assertTrue(sd.getNodesAt(at).isEmpty(), how + ": " + sd.getNodesAt(at).size() + " nodes of the tie remain");
        h.assertTrue(wiresAt(sd, battery) == 0 && wiresAt(sd, resistor) == 0, how + ": the bus wires remain (" + wiresAt(sd, battery) + ", " + wiresAt(sd, resistor) + ")");
    }

    /** Battery + through pole X, load, back through pole Z: closed carries V/R, tripped none, closed again carries it again. */
    static void polesSwitchTheCircuit(GameTestHelper h, BlockPos root, Runnable done) {
        var l = h.getLevel();
        var sd = InfrastructureSavedData.load(l);
        // no BWR generator: the breaker is set closed or open directly (GridTieBlockEntity.setClosedForTest)
        var tie = placeTie(l, root.east(24));
        var at = tie.getBlockPos();
        var battery = at.offset(0, 6, -8);
        var resistor = at.offset(0, 6, 8);
        placeCee(l, CEEBlocks.CREATIVE_BATTERY.get(), battery);
        placeCee(l, CEEBlocks.CREATIVE_RESISTOR.get(), resistor);
        ((ResistorBlockEntity) l.getBlockEntity(resistor)).setResistance(OHMS);
        tie.setClosedForTest(true);
        h.assertTrue(tie.closed(), "tie setup");
        h.assertTrue(DevicesSavedData.load(l).getDevice(at, GridTieDevice.class) != null, "no Grid Tie device at the controller");
        // + through pole X (nodes 0 and 3), - back through pole Z (nodes 5 and 2)
        wire(sd, node(sd, battery, 0), node(sd, at, 0));
        wire(sd, node(sd, at, 3), node(sd, resistor, 0));
        wire(sd, node(sd, resistor, 1), node(sd, at, 5));
        wire(sd, node(sd, at, 2), node(sd, battery, 1));
        final double[] amps = {Double.NaN};
        GridTieDevice.testProbe = results -> amps[0] = Math.abs(results.getCurrentThrough(resistor, 0, 1));
        Runnable finish = () -> GridTieDevice.testProbe = null;
        h.runAfterDelay(60, () -> {
            try {
                double expected = VOLTS / OHMS;
                var gcb = DevicesSavedData.load(l).getDevice(at, GridTieDevice.class);
                h.assertTrue(Math.abs(amps[0] - expected) < 0.05 * expected, "closed: " + amps[0] + " A through the load, expected about " + expected);
                h.assertTrue(Math.abs(Math.abs(gcb.poleAmps[0]) - expected) < 0.05 * expected && Math.abs(Math.abs(gcb.poleAmps[2]) - expected) < 0.05 * expected,
                        "closed: pole X " + gcb.poleAmps[0] + " A, pole Z " + gcb.poleAmps[2] + " A, expected about " + expected);
                h.assertTrue(Math.abs(gcb.poleAmps[1]) < 1e-6, "pole Y carries " + gcb.poleAmps[1] + " A with nothing on it");
                tie.tripBreaker();
                h.assertTrue(!tie.closed(), "the breaker did not trip");
                amps[0] = Double.NaN;
            } catch (RuntimeException | Error e) {
                finish.run();
                throw e;
            }
            h.runAfterDelay(60, () -> {
                try {
                    var gcb = DevicesSavedData.load(l).getDevice(at, GridTieDevice.class);
                    h.assertTrue(!Double.isNaN(amps[0]) && amps[0] < 1e-3, "tripped: " + amps[0] + " A still flows through the load");
                    h.assertTrue(gcb.poleAmps[0] == 0 && gcb.poleAmps[2] == 0, "tripped: pole currents " + gcb.poleAmps[0] + ", " + gcb.poleAmps[2]);
                    tie.setClosedForTest(true);
                    h.assertTrue(tie.closed(), "the breaker did not close again");
                    amps[0] = Double.NaN;
                } catch (RuntimeException | Error e) {
                    finish.run();
                    throw e;
                }
                h.runAfterDelay(60, () -> {
                    try {
                        double expected = VOLTS / OHMS;
                        h.assertTrue(!Double.isNaN(amps[0]) && Math.abs(amps[0] - expected) < 0.05 * expected, "closed again: " + amps[0] + " A through the load, expected about " + expected);
                    } finally {
                        finish.run();
                    }
                    done.run();
                });
            });
        });
    }
}
