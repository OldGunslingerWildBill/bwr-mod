package dev.bwr.mod.devtest;

import dan200.computercraft.api.peripheral.PeripheralCapability;
import dev.bwr.mod.peripheral.PowerModulePeripheral;
import dev.bwr.mod.power.PowerModuleBlockEntity;
import dev.bwr.mod.refuelling.BuildingCraneBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;

import java.util.Map;

/**
 * Computer-side checks of the crane and the LP turbine that need CC:Tweaked types, so they live here and not in a {@code @GameTestHolder}
 * class; the tests that call them check that CC:Tweaked is loaded first.
 */
final class CcFixChecks {

    private CcFixChecks() {
    }

    /** The coordinates a program hands the crane are far from every loaded chunk: it answers "not loaded" and generates nothing. */
    static void craneFarCoordinatesRefuse(GameTestHelper h, ServerLevel level, BlockPos console, ChunkLab lab) throws Exception {
        var crane = level.getCapability(PeripheralCapability.get(), console, Direction.NORTH);
        h.assertTrue(crane != null && crane.getType().equals("bwr_building_crane"), "no bwr_building_crane face: " + crane);
        BlockPos far = new BlockPos(25_000_000, 64, 25_000_000);
        for (String fn : new String[] {"queuePlugOff", "queueCoverOff", "queueCoverOn", "queueBladesOut", "queueBladesIn", "queueGateOut"}) {
            Map<?, ?> r = (Map<?, ?>) PeripheralTestCalls.call(crane, fn, (double) far.getX(), (double) far.getY(), (double) far.getZ())[0];
            h.assertTrue(Boolean.FALSE.equals(r.get("ok")) && String.valueOf(r.get("message")).contains("not loaded"),
                    fn + " at a position in an unloaded chunk is refused as not loaded: " + r);
            h.assertTrue(!lab.loaded(far), fn + " generated or loaded the chunk it was pointed at");
        }
        h.assertTrue(lab.syncLoads.isEmpty(), "chunks were loaded by the mod on the server thread: " + lab.syncLoads);
    }

    /** The status a program reads says the section failed when a blade section has, not only when the shaft has. */
    static void lpStatusReportsBladeFailure(GameTestHelper h, PowerModuleBlockEntity lp, boolean expected) {
        Map<String, Object> status = new PowerModulePeripheral(lp).getStatus();
        h.assertTrue(Boolean.valueOf(expected).equals(status.get("failed")), "getStatus().failed should be " + expected + ": " + status.get("failed")
                + " (damage " + status.get("damage") + ", shaftDamage " + status.get("shaftDamage") + ")");
    }
}
