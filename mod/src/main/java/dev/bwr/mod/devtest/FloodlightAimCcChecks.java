package dev.bwr.mod.devtest;

import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.peripheral.IDynamicPeripheral;
import dan200.computercraft.api.peripheral.PeripheralCapability;
import dev.bwr.mod.floodlight.AimReplans;
import dev.bwr.mod.floodlight.BeamLampBlockEntity;
import dev.bwr.mod.floodlight.FacingBeamLampBlock;
import dev.bwr.mod.floodlight.XenonFloodlightBlock;
import dev.bwr.mod.registry.BwrBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;

import java.util.Arrays;
import java.util.Map;

/** The computer face of the beam lamps (LIGHTING.md). Optional CC API references stay behind the installed-mod guard of the test that calls this. */
final class FloodlightAimCcChecks {
    private FloodlightAimCcChecks() {}

    private static void refused(GameTestHelper h, String why, IDynamicPeripheral face, String method, Object... args) throws Exception {
        boolean refused = false;
        try {
            PeripheralTestCalls.call(face, method, args);
        } catch (LuaException e) {
            refused = e.getMessage() != null && !e.getMessage().isEmpty();
        }
        h.assertTrue(refused, why + " is refused with a reason");
    }

    static void verify(GameTestHelper h) throws Exception {
        var l = h.getLevel();
        BlockPos pos = h.absolutePos(new BlockPos(2, 2, 2));
        l.setBlock(pos, BwrBlocks.XENON_FLOODLIGHT.get().defaultBlockState().setValue(XenonFloodlightBlock.FACING, Direction.NORTH), 3);
        var be = (BeamLampBlockEntity) l.getBlockEntity(pos);
        var face = l.getCapability(PeripheralCapability.get(), pos, Direction.UP);
        h.assertTrue(face instanceof IDynamicPeripheral && face.getType().equals("bwr_beam_lamp"), "the floodlight has the beam lamp face");
        var dyn = (IDynamicPeripheral) face;
        var names = Arrays.asList(dyn.getMethodNames());
        h.assertTrue(names.containsAll(java.util.List.of("getStatus", "setAim", "setReach")), "the face has getStatus, setAim and setReach: " + names);

        var st = (Map<?, ?>) PeripheralTestCalls.call(face, "getStatus")[0];
        for (String k : new String[] {"lit", "lightBlocks", "yawDeg", "pitchDeg", "reach", "yawDegrees", "pitchDegrees"}) {
            h.assertTrue(st.containsKey(k), "the status lacks " + k + ": " + st.keySet());
        }
        h.assertTrue(num(st, "yawDeg") == 0 && num(st, "pitchDeg") == 0 && num(st, "reach") == 32 && Boolean.FALSE.equals(st.get("lit")),
                "a new floodlight reads 0, 0, reach 32, unlit: " + st);

        PeripheralTestCalls.call(face, "setAim", 135, -30);
        h.assertTrue(be.yawDegrees() == 135 && be.pitchDegrees() == -30, "setAim(135, -30) aims the head: " + be.yawDegrees() + ", " + be.pitchDegrees());
        PeripheralTestCalls.call(face, "setReach", 100);
        h.assertTrue(be.reach() == 100 && be.yawDegrees() == 135, "setReach(100): " + be.reach());
        st = (Map<?, ?>) PeripheralTestCalls.call(face, "getStatus")[0];
        h.assertTrue(num(st, "yawDeg") == 135 && num(st, "pitchDeg") == -30 && num(st, "reach") == 100
                && num(st, "yawDegrees") == 135 && num(st, "pitchDegrees") == -30, "and the status reads them: " + st);
        h.assertTrue(AimReplans.waiting() >= 1, "the light follows through one waiting re-plan");
        PeripheralTestCalls.call(face, "setAim", 1, 1);
        h.assertTrue(be.yawDegrees() == 1 && be.pitchDegrees() == 1, "single degrees: setAim(1, 1)");
        PeripheralTestCalls.call(face, "setAim", 359, 30);
        PeripheralTestCalls.call(face, "setAim", 0, -75);
        PeripheralTestCalls.call(face, "setReach", 8);
        PeripheralTestCalls.call(face, "setReach", 128);
        h.assertTrue(be.yawDegrees() == 0 && be.pitchDegrees() == -75 && be.reach() == 128, "the limits are accepted");

        be.setAim(10, 20, 50);
        refused(h, "a yaw of 360", dyn, "setAim", 360, 0);
        refused(h, "a yaw of -1", dyn, "setAim", -1, 0);
        refused(h, "a pitch of 31", dyn, "setAim", 0, 31);
        refused(h, "a pitch of -76", dyn, "setAim", 0, -76);
        refused(h, "half a degree", dyn, "setAim", 0.5, 0);
        refused(h, "a reach of 7", dyn, "setReach", 7);
        refused(h, "a reach of 129", dyn, "setReach", 129);
        refused(h, "a reach of 20.5 blocks", dyn, "setReach", 20.5);
        refused(h, "no arguments", dyn, "setAim");
        h.assertTrue(be.yawDegrees() == 10 && be.pitchDegrees() == 20 && be.reach() == 50, "and nothing changed: " + be.yawDegrees() + ", " + be.pitchDegrees() + ", " + be.reach());

        // The high-bay reads, and cannot be aimed.
        BlockPos bay = h.absolutePos(new BlockPos(6, 2, 2));
        l.setBlock(bay, BwrBlocks.XENON_HIGH_BAY.get().defaultBlockState().setValue(FacingBeamLampBlock.FACING, Direction.DOWN), 3);
        var bayFace = l.getCapability(PeripheralCapability.get(), bay, Direction.UP);
        h.assertTrue(bayFace != null && bayFace.getType().equals("bwr_beam_lamp"), "the high-bay has the same face");
        var bst = (Map<?, ?>) PeripheralTestCalls.call(bayFace, "getStatus")[0];
        h.assertTrue(num(bst, "reach") == 48 && num(bst, "yawDeg") == 0 && num(bst, "pitchDeg") == 0, "it reports its fixed range as the reach: " + bst);
        refused(h, "aiming a high-bay", (IDynamicPeripheral) bayFace, "setAim", 10, 10);
        refused(h, "setting a high-bay's reach", (IDynamicPeripheral) bayFace, "setReach", 20);
        l.removeBlock(bay, false);
        l.removeBlock(pos, false);
    }

    private static double num(Map<?, ?> m, String key) {
        return ((Number) m.get(key)).doubleValue();
    }
}
