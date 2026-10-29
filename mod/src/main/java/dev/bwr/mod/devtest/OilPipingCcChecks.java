package dev.bwr.mod.devtest;

import dan200.computercraft.api.peripheral.PeripheralCapability;
import dev.bwr.mod.power.LubeOilValveBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;

import java.util.Map;

/** The computer faces of the Lube Oil Valve (TURBINE-AUX.md). Optional CC API references stay behind the installed-mod guard of the test that calls this. */
final class OilPipingCcChecks {
    private OilPipingCcChecks() {}

    static void valve(GameTestHelper h, ServerLevel l, BlockPos pos) throws Exception {
        for (Direction d : Direction.values())
            h.assertTrue(l.getCapability(PeripheralCapability.get(), pos, d) != null, "a computer face on " + d);
        var face = l.getCapability(PeripheralCapability.get(), pos, Direction.UP);
        h.assertTrue(face.getType().equals("bwr_lube_oil_valve"), "the valve's peripheral type: " + face.getType());
        PeripheralTestCalls.call(face, "close");
        h.assertTrue(!l.getBlockState(pos).getValue(LubeOilValveBlock.OPEN), "close() shuts the valve");
        h.assertTrue(Boolean.TRUE.equals(PeripheralTestCalls.call(face, "isClosed")[0]) && Boolean.FALSE.equals(PeripheralTestCalls.call(face, "isOpen")[0]), "and reads shut");
        var st = (Map<?, ?>) PeripheralTestCalls.call(face, "getStatus")[0];
        h.assertTrue(Boolean.TRUE.equals(st.get("limitClosed")) && Boolean.FALSE.equals(st.get("limitOpen")) && ((Number) st.get("position")).doubleValue() == 0, "the status reads shut: " + st);
        PeripheralTestCalls.call(face, "open");
        h.assertTrue(l.getBlockState(pos).getValue(LubeOilValveBlock.OPEN), "open() opens it");
        st = (Map<?, ?>) PeripheralTestCalls.call(face, "getStatus")[0];
        h.assertTrue(Boolean.TRUE.equals(st.get("limitOpen")) && Boolean.FALSE.equals(st.get("limitClosed")) && ((Number) st.get("position")).doubleValue() == 1
                && Boolean.TRUE.equals(st.get("open")), "the status reads open: " + st);
        PeripheralTestCalls.call(face, "setOpen", false);
        h.assertTrue(!l.getBlockState(pos).getValue(LubeOilValveBlock.OPEN), "setOpen(false) shuts it");
        PeripheralTestCalls.call(face, "setOpen", true);
    }
}
