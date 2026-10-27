package dev.bwr.mod.devtest;

import dan200.computercraft.api.peripheral.PeripheralCapability;
import dev.bwr.mod.reactor.ReactorControllerBlockEntity;
import dev.bwr.mod.refuelling.BuildingCraneBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;

import java.util.List;
import java.util.Map;

/**
 * The computer faces of the flood checklist ({@code getFloodReadiness}, the fill ports in {@code getCavity}) and of the canal gate lift
 * ({@code getGates}, {@code queueGateOut}, {@code getStatus().gateNext}). These touch CC:Tweaked types, so they live here and not in a
 * {@code @GameTestHolder} class; the callers check that CC:Tweaked is loaded first.
 */
final class CavityFloodCcChecks {
    private CavityFloodCcChecks() {
    }

    static void verifyFlood(GameTestHelper h, ReactorControllerBlockEntity reactor) throws Exception {
        var p = h.getLevel().getCapability(PeripheralCapability.get(), reactor.getBlockPos(), Direction.NORTH);
        h.assertTrue(p != null && p.getType().equals("bwr_reactor"), "the reactor face");
        var flood = (Map<?, ?>) PeripheralTestCalls.call(p, "getFloodReadiness")[0];
        h.assertTrue(flood.containsKey("canFlood") && flood.containsKey("inKgPerS") && flood.containsKey("outKgPerS") && flood.containsKey("spillTo"),
                "getFloodReadiness has the verdict and the flows: " + flood.keySet());
        var items = (List<?>) flood.get("items");
        h.assertTrue(items != null && !items.isEmpty(), "and the lines: " + flood);
        boolean head = false;
        for (Object o : items) {
            var item = (Map<?, ?>) o;
            h.assertTrue(item.containsKey("id") && item.containsKey("mark") && item.containsKey("pass") && item.get("text") instanceof String,
                    "each line has id, mark, pass and text: " + item);
            head |= "head".equals(item.get("id")) && "PASS".equals(item.get("mark"));
        }
        h.assertTrue(head, "the head line passes with the head off: " + items);
        var cavity = (Map<?, ?>) PeripheralTestCalls.call(p, "getCavity")[0];
        h.assertTrue(cavity.containsKey("fillCount") && cavity.containsKey("fills"), "getCavity lists the fill ports: " + cavity.keySet());
    }

    static void verifyGate(GameTestHelper h, BuildingCraneBlockEntity crane, BlockPos gate) throws Exception {
        var face = h.getLevel().getCapability(PeripheralCapability.get(), crane.getBlockPos(), Direction.NORTH);
        h.assertTrue(face != null && face.getType().equals("bwr_building_crane"), "the crane face: " + face);
        var gates = (List<?>) PeripheralTestCalls.call(face, "getGates")[0];
        h.assertTrue(gates.size() == 1, "one gate in reach: " + gates);
        var g = (Map<?, ?>) gates.get(0);
        h.assertTrue(((Number) g.get("x")).intValue() == gate.getX() && ((Number) g.get("blocks")).intValue() == 2 && Boolean.TRUE.equals(g.get("rigged"))
                && ((Number) g.get("massT")).doubleValue() > 1.0, "the gate: " + g);
        var status = (Map<?, ?>) PeripheralTestCalls.call(face, "getStatus")[0];
        h.assertTrue(status.containsKey("gateNext") && ((Number) status.get("gatesLaid")).intValue() == 0, "status: " + status);
        var r = (Map<?, ?>) PeripheralTestCalls.call(face, "queueGateOut", 1.0e6, 0.0, 1.0e6)[0];
        h.assertTrue(Boolean.FALSE.equals(r.get("ok")) && r.get("message") instanceof String, "a gate where there is none is refused: " + r);
        var in = (Map<?, ?>) PeripheralTestCalls.call(face, "queueGateIn")[0];
        h.assertTrue(Boolean.FALSE.equals(in.get("ok")), "nothing to put back: " + in);
        h.assertTrue(!crane.busy(), "nothing moved");
    }
}
