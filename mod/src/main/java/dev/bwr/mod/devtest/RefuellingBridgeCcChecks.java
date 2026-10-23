package dev.bwr.mod.devtest;

import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.peripheral.PeripheralCapability;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;

import java.util.List;
import java.util.Map;

/**
 * The computer face of the refuelling bridge through the same dynamic-peripheral entry point a computer calls. These touch
 * CC:Tweaked types, so they live here and not in a {@code @GameTestHolder} class: {@link RefuellingBridgeTests} checks that
 * CC:Tweaked is loaded first.
 */
final class RefuellingBridgeCcChecks {

    private RefuellingBridgeCcChecks() {
    }

    static void run(GameTestHelper h, BlockPos at) throws Exception {
        RefuellingBridgeFixture f = RefuellingBridgeFixture.build(h.getLevel(), at, 4);
        try {
            f.fillPool(7.0);
            f.reactor.loadAssembly(f.corePosition(0), RefuellingBridgeFixture.fresh(30000.0));
            var face = f.level.getCapability(PeripheralCapability.get(), f.console, Direction.NORTH);
            h.assertTrue(face != null && face.getType().equals("bwr_refuelling_bridge"), "no bwr_refuelling_bridge face: " + face);

            Map<?, ?> status = (Map<?, ?>) PeripheralTestCalls.call(face, "getStatus")[0];
            h.assertTrue(Boolean.TRUE.equals(status.get("ready")) && "idle".equals(status.get("phase")) && Boolean.FALSE.equals(status.get("busy"))
                    && "open".equals(status.get("chute")) && Boolean.TRUE.equals(status.get("reactorFound")) && Boolean.TRUE.equals(status.get("poolFound"))
                    && num(status, "queued") == 0 && num(status, "cavityWaterM") > 2.6 && num(status, "coreSlots") > 10 && num(status, "rackCells") == 16
                    && Math.abs(num(status, "poolWaterOverRacksM") - 3.0) < 1e-6, "status: " + status);
            h.assertTrue(Boolean.TRUE.equals(PeripheralTestCalls.call(face, "isCoreOccupied", 0)[0])
                    && Boolean.FALSE.equals(PeripheralTestCalls.call(face, "isCoreOccupied", 1)[0]), "core occupancy");
            List<?> racks = (List<?>) PeripheralTestCalls.call(face, "getRacks")[0];
            h.assertTrue(racks.size() == 4 && ((List<?>) ((Map<?, ?>) racks.get(1)).get("cells")).size() == 4, "racks " + racks.size());

            // a program queues the move; nothing else would
            Map<?, ?> queued = (Map<?, ?>) PeripheralTestCalls.call(face, "queueCoreToPool", 0, 5)[0];
            h.assertTrue(Boolean.TRUE.equals(queued.get("ok")), "queued: " + queued);
            status = (Map<?, ?>) PeripheralTestCalls.call(face, "getStatus")[0];
            h.assertTrue(Boolean.TRUE.equals(status.get("busy")) && "travel".equals(status.get("phase")) && status.get("move") != null, "busy: " + status);
            f.runUntilStill(40_000);
            racks = (List<?>) PeripheralTestCalls.call(face, "getRacks")[0];
            Map<?, ?> cell = (Map<?, ?>) ((List<?>) ((Map<?, ?>) racks.get(1)).get("cells")).get(1);
            h.assertTrue(Boolean.TRUE.equals(cell.get("occupied")) && num(cell, "cell") == 5 && num(cell, "decayHeatKW") > 10.0, "rack cell 5 holds the hot assembly: " + cell);
            h.assertTrue(Boolean.FALSE.equals(PeripheralTestCalls.call(face, "isCoreOccupied", 0)[0]), "the core position is empty");

            // gates and the chute
            int gateBlocks = ((Number) PeripheralTestCalls.call(face, "setGate", false)[0]).intValue();
            h.assertTrue(gateBlocks == RefuellingBridgeFixture.CANAL_DEPTH, "closed every block of the gate: " + gateBlocks);
            h.assertTrue("closed".equals(PeripheralTestCalls.call(face, "getChute")[0]), "chute closed");
            Map<?, ?> refused = (Map<?, ?>) PeripheralTestCalls.call(face, "queuePoolToCore", 5, 0)[0];
            h.assertTrue(Boolean.FALSE.equals(refused.get("ok")) && String.valueOf(refused.get("message")).contains("closed"), "refused for the shut gate: " + refused);
            PeripheralTestCalls.call(face, "setGate", true);
            h.assertTrue("open".equals(PeripheralTestCalls.call(face, "getChute")[0]), "chute open");
            // the override and bad arguments
            PeripheralTestCalls.call(face, "setOverride", true);
            h.assertTrue(Boolean.TRUE.equals(PeripheralTestCalls.call(face, "getOverride")[0]) && f.bridge.override(), "override set");
            PeripheralTestCalls.call(face, "setOverride", false);
            try {
                PeripheralTestCalls.call(face, "queueCoreToPool", 9999, 0);
                throw new AssertionError("an out of range core index was accepted");
            } catch (LuaException expected) {
                h.assertTrue(expected.getMessage().contains("out of range"), "message " + expected.getMessage());
            }
        } finally {
            f.dispose();
        }
    }

    private static double num(Map<?, ?> map, String key) {
        return ((Number) map.get(key)).doubleValue();
    }
}
