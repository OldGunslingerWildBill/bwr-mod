package dev.bwr.mod.devtest;

import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.peripheral.PeripheralCapability;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;

import java.util.Map;

/**
 * The computer faces of the reactor building crane, the stud tensioner, the head stand and the reactor's {@code getHead}, through the
 * same dynamic-peripheral entry point a computer calls. These touch CC:Tweaked types, so they live here and not in a
 * {@code @GameTestHolder} class: {@link BuildingCraneTests} checks that CC:Tweaked is loaded first.
 */
final class BuildingCraneCcChecks {

    private BuildingCraneCcChecks() {
    }

    private static double num(Map<?, ?> m, String key) {
        return ((Number) m.get(key)).doubleValue();
    }

    static void run(GameTestHelper h, CraneFixture f) throws Exception {
        var crane = f.level.getCapability(PeripheralCapability.get(), f.console, Direction.NORTH);
        var tensioner = f.level.getCapability(PeripheralCapability.get(), f.tensionerPos, Direction.NORTH);
        var reactor = f.level.getCapability(PeripheralCapability.get(), f.reactor().getBlockPos(), Direction.NORTH);
        var stand = f.level.getCapability(PeripheralCapability.get(), f.stand, Direction.NORTH);
        h.assertTrue(crane != null && crane.getType().equals("bwr_building_crane"), "no bwr_building_crane face: " + crane);
        h.assertTrue(tensioner != null && tensioner.getType().equals("bwr_stud_tensioner"), "no bwr_stud_tensioner face: " + tensioner);
        h.assertTrue(stand != null && stand.getType().equals("bwr_head_stand"), "no bwr_head_stand face: " + stand);
        h.assertTrue(reactor != null, "no reactor face");

        Map<?, ?> status = (Map<?, ?>) PeripheralTestCalls.call(crane, "getStatus")[0];
        h.assertTrue(Boolean.TRUE.equals(status.get("ready")) && "idle".equals(status.get("phase")) && Boolean.FALSE.equals(status.get("busy"))
                && Boolean.TRUE.equals(status.get("reactorFound")) && Boolean.FALSE.equals(status.get("headOff")) && num(status, "studTotal") == 74
                && num(status, "studsTensioned") == 74 && Boolean.TRUE.equals(status.get("dryerInstalled")), "crane status: " + status);
        Map<?, ?> head = (Map<?, ?>) PeripheralTestCalls.call(reactor, "getHead")[0];
        h.assertTrue(Boolean.TRUE.equals(head.get("headOn")) && Boolean.TRUE.equals(head.get("sealed")) && Boolean.FALSE.equals(head.get("liftable"))
                && num(head, "studsTensioned") == 74 && num(head, "studsDetensioned") == 0 && head.get("headAt") == null, "head on, sealed: " + head);

        // the head does not lift with the studs tensioned
        Map<?, ?> refused = (Map<?, ?>) PeripheralTestCalls.call(crane, "queueHeadOut")[0];
        h.assertTrue(Boolean.FALSE.equals(refused.get("ok")) && String.valueOf(refused.get("message")).contains("studs"), "refused: " + refused);

        // a program detensions the studs
        Map<?, ?> tstatus = (Map<?, ?>) PeripheralTestCalls.call(tensioner, "getStatus")[0];
        h.assertTrue(Boolean.TRUE.equals(tstatus.get("reactorFound")) && "idle".equals(tstatus.get("mode")) && num(tstatus, "studsTensioned") == 74
                && num(tstatus, "secondsPerStudDetension") == 4.0 && num(tstatus, "secondsPerStudRetension") == 6.0, "tensioner status: " + tstatus);
        Map<?, ?> started = (Map<?, ?>) PeripheralTestCalls.call(tensioner, "detension")[0];
        h.assertTrue(Boolean.TRUE.equals(started.get("ok")), "started: " + started);
        tstatus = (Map<?, ?>) PeripheralTestCalls.call(tensioner, "getStatus")[0];
        h.assertTrue(Boolean.TRUE.equals(tstatus.get("running")) && "loosen".equals(tstatus.get("mode")), "running: " + tstatus);
        Map<?, ?> twice = (Map<?, ?>) PeripheralTestCalls.call(tensioner, "retension")[0];
        h.assertTrue(Boolean.FALSE.equals(twice.get("ok")), "not while it works: " + twice);
        f.runTensioner(20_000, true);
        head = (Map<?, ?>) PeripheralTestCalls.call(reactor, "getHead")[0];
        h.assertTrue(num(head, "studsDetensioned") == 74 && Boolean.TRUE.equals(head.get("liftable")) && Boolean.FALSE.equals(head.get("sealed")), "liftable: " + head);

        // a program lifts the head to the stand
        Map<?, ?> queued = (Map<?, ?>) PeripheralTestCalls.call(crane, "queueHeadOut")[0];
        h.assertTrue(Boolean.TRUE.equals(queued.get("ok")), "queued: " + queued);
        status = (Map<?, ?>) PeripheralTestCalls.call(crane, "getStatus")[0];
        h.assertTrue(Boolean.TRUE.equals(status.get("busy")) && "travel".equals(status.get("phase")) && status.get("to") != null, "busy: " + status);
        f.runCrane(60_000);
        head = (Map<?, ?>) PeripheralTestCalls.call(reactor, "getHead")[0];
        h.assertTrue(Boolean.FALSE.equals(head.get("headOn")) && head.get("headAt") instanceof Map<?, ?> at && num(at, "x") == f.stand.getX()
                && num(at, "y") == f.stand.getY() && num(at, "z") == f.stand.getZ() && String.valueOf(head.get("summary")).contains("Head off"), "head off on the stand: " + head);
        Map<?, ?> standStatus = (Map<?, ?>) PeripheralTestCalls.call(stand, "getStatus")[0];
        h.assertTrue(Boolean.TRUE.equals(standStatus.get("holdingHead")), "the stand says it holds the head: " + standStatus);

        // and the dryer
        Map<?, ?> dryer = (Map<?, ?>) PeripheralTestCalls.call(crane, "queueInternalOut", "dryer")[0];
        h.assertTrue(Boolean.TRUE.equals(dryer.get("ok")), "dryer queued: " + dryer);
        f.runCrane(60_000);
        Map<?, ?> internals = (Map<?, ?>) PeripheralTestCalls.call(reactor, "getInternals")[0];
        h.assertTrue(Boolean.FALSE.equals(internals.get("dryerInstalled")) && internals.get("dryerAt") instanceof Map<?, ?>, "dryer out: " + internals);
        status = (Map<?, ?>) PeripheralTestCalls.call(crane, "getStatus")[0];
        h.assertTrue(Boolean.FALSE.equals(status.get("dryerInstalled")) && Boolean.TRUE.equals(status.get("headOff")), "crane status: " + status);
        Map<?, ?> back = (Map<?, ?>) PeripheralTestCalls.call(crane, "queueInternalIn", "dryer")[0];
        h.assertTrue(Boolean.TRUE.equals(back.get("ok")), "back in: " + back);
        f.runCrane(60_000);
        Map<?, ?> headIn = (Map<?, ?>) PeripheralTestCalls.call(crane, "queueHeadIn")[0];
        h.assertTrue(Boolean.TRUE.equals(headIn.get("ok")), "head back queued: " + headIn);
        PeripheralTestCalls.call(crane, "abort");
        h.assertTrue(!f.crane.busy(), "abort stops it");
        try {
            PeripheralTestCalls.call(crane, "queueInternalOut", "head");
            throw new AssertionError("a bad name was accepted");
        } catch (LuaException expected) {
            h.assertTrue(expected.getMessage().contains("dryer"), "message " + expected.getMessage());
        }
    }
}
