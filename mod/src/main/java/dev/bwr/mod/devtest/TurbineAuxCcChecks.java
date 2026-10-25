package dev.bwr.mod.devtest;

import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.peripheral.PeripheralCapability;
import dev.bwr.mod.power.TurbineAuxBlockEntity;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;

import java.util.Map;

/** The computer face of the turbine auxiliaries. Optional CC API references stay behind the installed-mod guard of the test that calls this. */
final class TurbineAuxCcChecks {
    private TurbineAuxCcChecks() {}

    static void verify(GameTestHelper h, TurbineAuxTests.Fx f, TurbineAuxBlockEntity tank, TurbineAuxBlockEntity skid) throws Exception {
        var gen = h.getLevel().getCapability(PeripheralCapability.get(), f.root, Direction.UP);
        h.assertTrue(gen != null && gen.getType().equals("bwr_generator"), "the generator has a peripheral");
        var status = (Map<?, ?>) PeripheralTestCalls.call(gen, "getStatus")[0];
        for (String key : new String[] {"auxGoverned", "oilPressurePsig", "oilTempC", "oilLevel", "oilFilm", "bearingTempC", "oilPressureAlarm", "oilLevelAlarm",
                "oilTempAlarm", "bearingAlarm", "vibrationMmPerS", "vibrationAlarm", "turningGear", "turningGearRolling", "turningGearPowered", "windingTempC",
                "statorFlow", "h2PressurePsig", "coolantTempC", "windingAlarm", "h2Alarm"}) {
            h.assertTrue(status.containsKey(key), "the generator status lacks " + key + ": " + status.keySet());
        }
        // The switch: refused above the gear's overrun speed, accepted below it.
        boolean refused = false;
        try {
            PeripheralTestCalls.call(gen, "setTurningGear", true);
        } catch (LuaException e) {
            refused = true;
        }
        h.assertTrue(refused && !f.gen.turningGear(), "setTurningGear(true) at 100 rpm is refused");
        f.gen.rpm = 4;
        PeripheralTestCalls.call(gen, "setTurningGear", true);
        h.assertTrue(f.gen.turningGear() && Boolean.TRUE.equals(PeripheralTestCalls.call(gen, "getTurningGear")[0]), "setTurningGear(true) at 4 rpm engages it");
        PeripheralTestCalls.call(gen, "setTurningGear", false);
        h.assertTrue(!f.gen.turningGear(), "and false releases it");

        var oil = h.getLevel().getCapability(PeripheralCapability.get(), tank.getBlockPos(), Direction.UP);
        h.assertTrue(oil != null && oil.getType().equals("bwr_main_oil_tank"), "the oil tank has a peripheral");
        PeripheralTestCalls.call(oil, "setAcPump", true);
        PeripheralTestCalls.call(oil, "setDcPump", true);
        h.assertTrue(tank.acPumpOn() && tank.dcPumpOn(), "the pump switches work");
        var tankStatus = (Map<?, ?>) PeripheralTestCalls.call(oil, "getStatus")[0];
        for (String key : new String[] {"attached", "acPump", "dcPump", "acRunning", "dcRunning", "oilLevel", "oilLitres", "oilTempC", "pressurePsig", "oilFilm",
                "coolerKW", "coldWaterKg", "hotWaterKg", "acFE", "dcFE"}) {
            h.assertTrue(tankStatus.containsKey(key), "the tank status lacks " + key + ": " + tankStatus.keySet());
        }
        boolean wrong = false;
        try {
            PeripheralTestCalls.call(oil, "setStatorPumps", true);
        } catch (LuaException e) {
            wrong = true;
        }
        h.assertTrue(wrong, "the tank has no stator pumps");

        var cool = h.getLevel().getCapability(PeripheralCapability.get(), skid.getBlockPos(), Direction.UP);
        h.assertTrue(cool != null && cool.getType().equals("bwr_generator_cooling_skid"), "the cooling skid has a peripheral");
        PeripheralTestCalls.call(cool, "setStatorPumps", true);
        h.assertTrue(skid.statorPumpsOn(), "the stator pump switch works");
        var coolStatus = (Map<?, ?>) PeripheralTestCalls.call(cool, "getStatus")[0];
        for (String key : new String[] {"attached", "statorPumps", "statorRunning", "statorFlow", "h2PressurePsig", "h2Kg", "coolantTempC", "windingTempC", "coolerKW",
                "coldWaterKg", "hotWaterKg", "supplyFE"}) {
            h.assertTrue(coolStatus.containsKey(key), "the cooling skid status lacks " + key + ": " + coolStatus.keySet());
        }
    }
}
