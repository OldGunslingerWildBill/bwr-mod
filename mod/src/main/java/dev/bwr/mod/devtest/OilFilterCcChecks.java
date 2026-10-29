package dev.bwr.mod.devtest;

import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.peripheral.PeripheralCapability;
import dev.bwr.mod.power.TurbineAuxBlockEntity;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;

import java.util.Map;

/** The computer faces of the oil filter (TURBINE-AUX.md). Optional CC API references stay behind the installed-mod guard of the test that calls this. */
final class OilFilterCcChecks {
    private OilFilterCcChecks() {}

    static void verify(GameTestHelper h, TurbineAuxBlockEntity tank, TurbineAuxBlockEntity housing) throws Exception {
        var l = h.getLevel();
        var face = l.getCapability(PeripheralCapability.get(), housing.getBlockPos(), Direction.UP);
        h.assertTrue(face != null && face.getType().equals("bwr_oil_filter"), "the housing has a peripheral");
        for (Direction d : Direction.values())
            h.assertTrue(l.getCapability(PeripheralCapability.get(), housing.getBlockPos(), d) != null, "a computer face on " + d);
        var st = (Map<?, ?>) PeripheralTestCalls.call(face, "getStatus")[0];
        for (String k : new String[] {"attached", "filterCartridge", "filterLife", "filterDpPsi", "filterBypass", "filterChangeDue"})
            h.assertTrue(st.containsKey(k), "the housing status lacks " + k + ": " + st.keySet());
        h.assertTrue(Boolean.TRUE.equals(st.get("attached")) && Boolean.TRUE.equals(st.get("filterCartridge")) && Boolean.FALSE.equals(st.get("filterBypass"))
                && Math.abs(((Number) st.get("filterLife")).doubleValue() - 0.2) < 0.001, "and it reads true: " + st);
        boolean refused = false;
        try { PeripheralTestCalls.call(face, "setAcPump", true); } catch (LuaException e) { refused = true; }
        h.assertTrue(refused, "the face is read-only: it has no pump to switch");
        var oil = l.getCapability(PeripheralCapability.get(), tank.getBlockPos(), Direction.UP);
        var ts2 = (Map<?, ?>) PeripheralTestCalls.call(oil, "getStatus")[0];
        h.assertTrue(Boolean.TRUE.equals(ts2.get("filterHousing")) && ts2.containsKey("filterLife") && ts2.containsKey("filterDpPsi") && ts2.containsKey("filterBypass"),
                "the tank's status carries the filter: " + ts2.keySet());
        l.removeBlock(housing.getBlockPos(), false);
        var ts3 = (Map<?, ?>) PeripheralTestCalls.call(oil, "getStatus")[0];
        h.assertTrue(Boolean.FALSE.equals(ts3.get("filterHousing")) && !ts3.containsKey("filterLife"), "without a housing the tank reports none: " + ts3.keySet());
    }
}
