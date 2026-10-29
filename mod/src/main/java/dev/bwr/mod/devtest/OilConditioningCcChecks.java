package dev.bwr.mod.devtest;

import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.peripheral.PeripheralCapability;
import dev.bwr.mod.power.TurbineAuxBlockEntity;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;

import java.util.Map;

/** The computer faces of the duplex oil filter and the Lube Oil Conditioner (TURBINE-AUX.md). Optional CC API references stay behind the installed-mod guard of the test that calls this. */
final class OilConditioningCcChecks {
    private OilConditioningCcChecks() {}

    static void duplex(GameTestHelper h, ServerLevel l, TurbineAuxBlockEntity tank, TurbineAuxBlockEntity housing) throws Exception {
        var face = l.getCapability(PeripheralCapability.get(), housing.getBlockPos(), Direction.UP);
        h.assertTrue(face != null && face.getType().equals("bwr_oil_filter"), "the housing's peripheral");
        var st = (Map<?, ?>) PeripheralTestCalls.call(face, "getStatus")[0];
        h.assertTrue("A".equals(st.get("filterSelectedSide")) && st.get("sideA") instanceof Map<?, ?> && st.get("sideB") instanceof Map<?, ?>, "status carries the selected side and each side: " + st.keySet());
        var a = (Map<?, ?>) st.get("sideA");
        var b = (Map<?, ?>) st.get("sideB");
        h.assertTrue(Boolean.TRUE.equals(a.get("inService")) && Boolean.FALSE.equals(b.get("inService")) && Boolean.TRUE.equals(a.get("cartridge")) && Boolean.TRUE.equals(b.get("cartridge")),
                "A in service, both held: " + a + " " + b);
        for (String k : new String[] {"life", "dpPsi", "bypass", "changeDue"}) h.assertTrue(a.containsKey(k) && b.containsKey(k), "each side reads " + k);
        PeripheralTestCalls.call(face, "setSide", "B");
        h.assertTrue(housing.sideBInService(), "setSide(\"B\") throws the transfer valve");
        st = (Map<?, ?>) PeripheralTestCalls.call(face, "getStatus")[0];
        h.assertTrue("B".equals(st.get("filterSelectedSide")), "and the status follows: " + st.get("filterSelectedSide"));
        PeripheralTestCalls.call(face, "transfer");
        h.assertTrue(!housing.sideBInService(), "transfer() throws it back");
        boolean refused = false;
        try { PeripheralTestCalls.call(face, "setSide", "C"); } catch (LuaException e) { refused = true; }
        h.assertTrue(refused, "a side other than A or B is refused");
        refused = false;
        try { PeripheralTestCalls.call(face, "setRunning", true); } catch (LuaException e) { refused = true; }
        h.assertTrue(refused, "a housing has no conditioner switch");
    }

    static void conditioner(GameTestHelper h, ServerLevel l, TurbineAuxBlockEntity tank, TurbineAuxBlockEntity cond) throws Exception {
        for (Direction d : Direction.values())
            h.assertTrue(l.getCapability(PeripheralCapability.get(), cond.getBlockPos(), d) != null, "a computer face on " + d);
        var face = l.getCapability(PeripheralCapability.get(), cond.getBlockPos(), Direction.UP);
        h.assertTrue(face.getType().equals("bwr_lube_oil_conditioner"), "the conditioner's peripheral: " + face.getType());
        PeripheralTestCalls.call(face, "stop");
        var st = (Map<?, ?>) PeripheralTestCalls.call(face, "getStatus")[0];
        for (String k : new String[] {"attached", "on", "running", "supplyFE", "degradationFactor"}) h.assertTrue(st.containsKey(k), "the status lacks " + k + ": " + st.keySet());
        h.assertTrue(Boolean.FALSE.equals(st.get("on")) && ((Number) st.get("degradationFactor")).doubleValue() == 1, "stopped reads all of the degradation: " + st);
        PeripheralTestCalls.call(face, "start");
        h.assertTrue(cond.conditionerOn(), "start() switches it on");
        PeripheralTestCalls.call(face, "setRunning", false);
        h.assertTrue(!cond.conditionerOn(), "setRunning(false) switches it off");
        boolean refused = false;
        try { PeripheralTestCalls.call(face, "setSide", "A"); } catch (LuaException e) { refused = true; }
        h.assertTrue(refused, "the conditioner has no transfer valve");
        var tf = l.getCapability(PeripheralCapability.get(), tank.getBlockPos(), Direction.UP);
        var ts = (Map<?, ?>) PeripheralTestCalls.call(tf, "getStatus")[0];
        h.assertTrue(Boolean.TRUE.equals(ts.get("conditioner")) && ts.containsKey("conditionerRunning"), "the tank's status carries its conditioner: " + ts.keySet());
    }
}
