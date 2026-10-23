package dev.bwr.mod.devtest;

import dan200.computercraft.api.peripheral.PeripheralCapability;
import dev.bwr.mod.reactor.CavityDrainPortBlockEntity;
import dev.bwr.mod.reactor.ReactorControllerBlockEntity;
import dev.bwr.mod.water.MotorOperatedWaterValveBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;

import java.util.Map;

/** Optional CC API references stay behind the installed-mod guard (never loaded without CC:Tweaked). */
final class WaterValveCcChecks {
    private WaterValveCcChecks() {
    }

    /** The computer face of a motor-operated water valve: every side, every command, the rating, the fail position. */
    static void verifyValve(GameTestHelper h, BlockPos at, MotorOperatedWaterValveBlockEntity valve) throws Exception {
        for (Direction side : Direction.values()) {
            var p = h.getLevel().getCapability(PeripheralCapability.get(), at, side);
            h.assertTrue(p != null && p.getType().equals("bwr_motor_operated_water_valve"), "no valve peripheral on " + side);
        }
        var p = h.getLevel().getCapability(PeripheralCapability.get(), at, Direction.UP);
        PeripheralTestCalls.call(p, "setPosition", 0.3);
        h.assertTrue(Math.abs(valve.target() - 0.3) < 1.0e-9, "setPosition(0.3) demands 0.3: " + valve.target());
        PeripheralTestCalls.call(p, "open");
        h.assertTrue(valve.target() == 1.0, "open() demands fully open");
        PeripheralTestCalls.call(p, "close");
        h.assertTrue(valve.target() == 0.0, "close() demands closed");
        for (double bad : new double[] {-0.1, 1.1, Double.NaN}) {
            boolean refused = false;
            try {
                PeripheralTestCalls.call(p, "setPosition", bad);
            } catch (dan200.computercraft.api.lua.LuaException e) {
                refused = true;
            }
            h.assertTrue(refused, "setPosition(" + bad + ") is refused");
        }
        PeripheralTestCalls.call(p, "setFailPosition", "open");
        h.assertTrue(valve.failMode() == MotorOperatedWaterValveBlockEntity.FailMode.OPEN
                && PeripheralTestCalls.call(p, "getFailPosition")[0].equals("open"), "setFailPosition(\"open\")");
        PeripheralTestCalls.call(p, "setFailPosition", "closed");
        h.assertTrue(valve.failMode() == MotorOperatedWaterValveBlockEntity.FailMode.CLOSED, "setFailPosition(\"closed\")");
        PeripheralTestCalls.call(p, "setFailPosition", "as_is");
        h.assertTrue(valve.failMode() == MotorOperatedWaterValveBlockEntity.FailMode.AS_IS, "setFailPosition(\"as_is\")");
        boolean refused = false;
        try {
            PeripheralTestCalls.call(p, "setFailPosition", "sideways");
        } catch (dan200.computercraft.api.lua.LuaException e) {
            refused = true;
        }
        h.assertTrue(refused, "an unknown fail position is refused");
        var status = (Map<?, ?>) PeripheralTestCalls.call(p, "getStatus")[0];
        h.assertTrue(((Number) status.get("ratingPsi")).doubleValue() == 2500.0 && ((Number) status.get("wearAbovePsig")).doubleValue() == 1250.0,
                "the status carries the rating: " + status);
        for (String key : new String[] {"target", "position", "open", "closed", "flowKgPerS", "powered", "energyFE", "failPosition"}) {
            h.assertTrue(status.containsKey(key), "status lacks " + key + ": " + status);
        }
    }

    /** The reactor's getCavity() lists the drain ports with their flow and line state, and the port has its own face. */
    static void verifyCavity(GameTestHelper h, ReactorControllerBlockEntity reactor, CavityDrainPortBlockEntity port) throws Exception {
        var p = h.getLevel().getCapability(PeripheralCapability.get(), reactor.getBlockPos(), Direction.NORTH);
        h.assertTrue(p != null && p.getType().equals("bwr_reactor"), "the reactor face");
        var cavity = (Map<?, ?>) PeripheralTestCalls.call(p, "getCavity")[0];
        h.assertTrue(((Number) cavity.get("drainCount")).intValue() == 1, "getCavity lists one drain: " + cavity);
        h.assertTrue(((Number) cavity.get("drainKgPerS")).doubleValue() > 100, "and its flow: " + cavity.get("drainKgPerS"));
        var drains = (java.util.List<?>) cavity.get("drains");
        var drain = (Map<?, ?>) drains.get(0);
        h.assertTrue(drain != null && "draining".equals(drain.get("state")) && ((Number) drain.get("flowKgPerS")).doubleValue() > 100
                && ((Number) drain.get("valves")).intValue() == 1 && ((Number) drain.get("valveOpening")).doubleValue() == 1.0, "the drain's line state: " + drains);
        var face = h.getLevel().getCapability(PeripheralCapability.get(), port.getBlockPos(), Direction.EAST);
        h.assertTrue(face != null && face.getType().equals("bwr_cavity_drain_port"), "the port's own face");
        var own = (Map<?, ?>) PeripheralTestCalls.call(face, "getStatus")[0];
        h.assertTrue("draining".equals(own.get("state")) && ((Number) own.get("flowKgPerS")).doubleValue() > 100, "its status: " + own);
    }
}
