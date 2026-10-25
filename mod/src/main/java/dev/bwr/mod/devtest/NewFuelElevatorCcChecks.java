package dev.bwr.mod.devtest;

import dan200.computercraft.api.peripheral.PeripheralCapability;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;

import java.util.Map;

/**
 * The computer face of the new fuel elevator through the same dynamic-peripheral entry point a computer calls. These touch CC:Tweaked
 * types, so they live here and not in a {@code @GameTestHolder} class:
 * {@link NewFuelElevatorTests#theComputerFaceOfTheElevatorReadsAndRaises} checks that CC:Tweaked is loaded first.
 */
final class NewFuelElevatorCcChecks {

    private NewFuelElevatorCcChecks() {
    }

    private static double num(Map<?, ?> m, String key) {
        return ((Number) m.get(key)).doubleValue();
    }

    static void run(GameTestHelper h, NewFuelElevatorTests.Site s) throws Exception {
        try {
            var face = s.level.getCapability(PeripheralCapability.get(), s.rim, Direction.UP);
            h.assertTrue(face != null && face.getType().equals("bwr_new_fuel_elevator"), "no bwr_new_fuel_elevator face: " + face);
            h.assertTrue(Boolean.TRUE.equals(PeripheralTestCalls.call(face, "isLinked")[0]), "isLinked");
            Map<?, ?> idle = (Map<?, ?>) PeripheralTestCalls.call(face, "getStatus")[0];
            var st = s.be.status();
            h.assertTrue(Boolean.TRUE.equals(idle.get("linked")) && "idle".equals(idle.get("state")) && num(idle, "freeCells") == st.freeCells()
                            && num(idle, "cells") == 8 && Math.abs(num(idle, "waterOverTransferM") - st.waterOverTransferM()) < 1e-9
                            && num(idle, "minCoverM") == 1.5 && num(idle, "coolestDecayHeatKW") == -1.0, "idle status: " + idle);

            // nothing to raise: a refusal with the reason, not an error
            Map<?, ?> none = (Map<?, ?>) PeripheralTestCalls.call(face, "raise")[0];
            h.assertTrue(Boolean.FALSE.equals(none.get("ok")) && "nothing_to_raise".equals(none.get("reason")), "raise on an empty pool: " + none);

            // a cooled assembly in the pool, raised by the program
            long now = s.level.getGameTime();
            s.pool.be.insertAt(s.pool.racks.get(1), 2, SpentFuelPoolFixture.assembly(45000.0, 86400.0 * 60, now));
            Map<?, ?> go = (Map<?, ?>) PeripheralTestCalls.call(face, "raiseCell", 4 + 2)[0];
            h.assertTrue(Boolean.TRUE.equals(go.get("ok")), "raiseCell(6): " + go);
            Map<?, ?> moving = (Map<?, ?>) PeripheralTestCalls.call(face, "getStatus")[0];
            h.assertTrue("raising".equals(moving.get("state")) && num(moving, "secondsLeft") > 5.0 && !String.valueOf(moving.get("leg")).isEmpty(), "raising: " + moving);
            s.advance(s.be.currentTrip().totalTicks() + 5);
            Map<?, ?> landed = (Map<?, ?>) PeripheralTestCalls.call(face, "getStatus")[0];
            h.assertTrue(Boolean.TRUE.equals(landed.get("landing")) && "idle".equals(landed.get("state")), "landed: " + landed);
            Map<?, ?> again = (Map<?, ?>) PeripheralTestCalls.call(face, "raise")[0];
            h.assertTrue(Boolean.FALSE.equals(again.get("ok")), "a second raise is refused: " + again);
            Map<?, ?> retry = (Map<?, ?>) PeripheralTestCalls.call(face, "retry")[0];
            h.assertTrue(Boolean.TRUE.equals(retry.get("ok")), "retry with nothing waiting is fine: " + retry);
        } finally {
            s.dispose();
        }
    }
}
