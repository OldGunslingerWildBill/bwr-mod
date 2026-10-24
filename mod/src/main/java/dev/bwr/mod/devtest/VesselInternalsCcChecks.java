package dev.bwr.mod.devtest;

import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.peripheral.PeripheralCapability;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;

import java.util.Map;

/**
 * The computer face of the removable dryer and separators: {@code reactor.getInternals()} and the bridge's
 * {@code queueInternalOut} / {@code queueInternalIn}. CC:Tweaked types, so not in a {@code @GameTestHolder} class.
 */
final class VesselInternalsCcChecks {
    private VesselInternalsCcChecks() {
    }

    static void run(GameTestHelper h, RefuellingBridgeFixture f) throws Exception {
        var bridge = f.level.getCapability(PeripheralCapability.get(), f.bridge.getBlockPos(), Direction.NORTH);
        var reactor = f.level.getCapability(PeripheralCapability.get(), f.reactor.getBlockPos(), Direction.NORTH);
        h.assertTrue(bridge != null && reactor != null, "faces: " + bridge + " " + reactor);
        Map<?, ?> internals = (Map<?, ?>) PeripheralTestCalls.call(reactor, "getInternals")[0];
        h.assertTrue(Boolean.TRUE.equals(internals.get("dryerInstalled")) && Boolean.TRUE.equals(internals.get("separatorInstalled"))
                && Boolean.TRUE.equals(internals.get("operable")), "both installed: " + internals);
        Map<?, ?> refused = (Map<?, ?>) PeripheralTestCalls.call(bridge, "queueInternalOut", "separator")[0];
        h.assertTrue(Boolean.FALSE.equals(refused.get("ok")) && String.valueOf(refused.get("message")).contains("dryer"), "dryer first: " + refused);
        Map<?, ?> queued = (Map<?, ?>) PeripheralTestCalls.call(bridge, "queueInternalOut", "dryer")[0];
        h.assertTrue(Boolean.TRUE.equals(queued.get("ok")), "queued: " + queued);
        f.runUntilStill(40_000);
        internals = (Map<?, ?>) PeripheralTestCalls.call(reactor, "getInternals")[0];
        h.assertTrue(Boolean.FALSE.equals(internals.get("dryerInstalled")) && internals.get("dryerAt") instanceof Map<?, ?>
                && Boolean.FALSE.equals(internals.get("operable")) && String.valueOf(internals.get("summary")).contains("Not operable"), "dryer out: " + internals);
        Map<?, ?> status = (Map<?, ?>) PeripheralTestCalls.call(bridge, "getStatus")[0];
        h.assertTrue(Boolean.FALSE.equals(status.get("dryerInstalled")) && Boolean.TRUE.equals(status.get("separatorInstalled")), "bridge status: " + status);
        h.assertTrue(Boolean.TRUE.equals(((Map<?, ?>) PeripheralTestCalls.call(bridge, "queueInternalIn", "dryer")[0]).get("ok")), "back in");
        f.runUntilStill(40_000);
        h.assertTrue(f.reactor.internalsOutMask() == 0, "both in again");
        try {
            PeripheralTestCalls.call(bridge, "queueInternalOut", "head");
            throw new AssertionError("a bad name was accepted");
        } catch (LuaException expected) {
            h.assertTrue(expected.getMessage().contains("dryer"), "message " + expected.getMessage());
        }
    }
}
