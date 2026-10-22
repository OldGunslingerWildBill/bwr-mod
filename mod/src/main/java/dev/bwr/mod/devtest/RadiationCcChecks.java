package dev.bwr.mod.devtest;

import dan200.computercraft.api.peripheral.IPeripheral;
import dan200.computercraft.api.peripheral.PeripheralCapability;
import dev.bwr.core.radiation.DoseModel;
import dev.bwr.mod.peripheral.AreaRadiationMonitorPeripheral;
import dev.bwr.mod.radiation.AreaRadiationMonitorBlock;
import dev.bwr.mod.radiation.AreaRadiationMonitorBlockEntity;
import dev.bwr.mod.radiation.RadiationSources;
import dev.bwr.mod.registry.BwrBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.phys.Vec3;

import java.util.Map;

/**
 * Drives the monitor through the same {@code IDynamicPeripheral} entry a computer calls, on a real block in a real level
 * (a point source for the radiation), and checks the Lua fields against the monitor and against the signal layer.
 */
final class RadiationCcChecks {
    private RadiationCcChecks() {
    }

    static void run(GameTestHelper helper) {
        NetStage.run(helper, 94, s -> {
            BlockPos at = s.devices;
            s.level.setBlock(at, BwrBlocks.AREA_RADIATION_MONITOR.get().defaultBlockState()
                    .setValue(AreaRadiationMonitorBlock.FACING, Direction.NORTH), 3);
            var monitor = (AreaRadiationMonitorBlockEntity) s.level.getBlockEntity(at);
            Object owner = new Object();
            s.then(1, () -> {
                IPeripheral peripheral = s.level.getCapability(PeripheralCapability.get(), at, Direction.NORTH);
                s.check(peripheral != null && peripheral.getType().equals("bwr_radiation_monitor"),
                        "a computer sees a bwr_radiation_monitor: " + (peripheral == null ? null : peripheral.getType()));
                try {
                    var names = java.util.Arrays.asList(((dan200.computercraft.api.peripheral.IDynamicPeripheral) peripheral).getMethodNames());
                    for (String name : new String[] {"getDoseRate", "isHighRadiation", "isAlert", "getAlarmSetpoint", "getAlertSetpoint",
                            "setAlarmSetpoint", "getStatus", "getName", "setName", "getPort", "setPort"}) {
                        s.check(names.contains(name), "the peripheral has " + name + ": " + names);
                    }
                    // Background first.
                    s.check(call(peripheral, "getDoseRate")[0].equals(0.0), "background reads 0 over CC");
                    s.check(call(peripheral, "isHighRadiation")[0].equals(false) && call(peripheral, "isAlert")[0].equals(false),
                            "no alarm at background");
                    // A source worth about 3 mSv/h at the monitor.
                    double watts = 3.0 / DoseModel.pointSourceMsvPerH(1.0, 2.0, 1.0);
                    RadiationSources.emit(s.level, owner, Vec3.atCenterOf(at).add(2.0, 0.0, 0.0), watts, 1.0);
                    monitor.invalidate();
                    double rate = (Double) call(peripheral, "getDoseRate")[0];
                    s.check(Math.abs(rate - 3.0) < 0.01, "getDoseRate is the dose rate in mSv/h: " + rate);
                    s.check(call(peripheral, "isHighRadiation")[0].equals(true) && call(peripheral, "isAlert")[0].equals(true),
                            "3 mSv/h is above the 1 mSv/h alarm setpoint");
                    s.check(call(peripheral, "getAlarmSetpoint")[0].equals(1.0) && call(peripheral, "getAlertSetpoint")[0].equals(0.1),
                            "setpoints 1 and 0.1 mSv/h");
                    Map<?, ?> status = (Map<?, ?>) call(peripheral, "getStatus")[0];
                    s.check(status.get("unit").equals("mSv/h") && Math.abs((Double) status.get("doseRate") - rate) < 1e-9
                            && status.get("highRadiation").equals(true) && status.get("alert").equals(true)
                            && ((Number) status.get("lamp")).intValue() == 2, "getStatus: " + status);
                    s.check(Math.abs((Double) status.get("other") - rate) < 1e-9 && (Double) status.get("core") == 0.0
                            && (Double) status.get("airborne") == 0.0 && (Double) status.get("deposited") == 0.0
                            && (Double) status.get("release") == 0.0, "and says where it comes from: " + status);
                    // The setter moves the alarm, nothing else; a bad value is refused.
                    s.check(call(peripheral, "setAlarmSetpoint", 10.0)[0].equals(true) && monitor.highSetpoint() == 10.0, "setAlarmSetpoint");
                    s.check(call(peripheral, "isHighRadiation")[0].equals(false) && call(peripheral, "isAlert")[0].equals(true),
                            "3 mSv/h is now an alert (setpoint 10, alert 1) but not high radiation");
                    s.check(call(peripheral, "setAlarmSetpoint", -1.0)[0].equals(false) && monitor.highSetpoint() == 10.0,
                            "a bad setpoint is refused");
                    // The same numbers on the signal layer.
                    var provider = dev.bwr.mod.signal.SignalProviders.at(s.level, at);
                    s.check(provider.read("dose_rate").getAsDouble() == (Double) call(peripheral, "getDoseRate")[0]
                            && provider.read("high_radiation").getAsDouble() == 0.0 && provider.read("radiation_alert").getAsDouble() == 1.0
                            && provider.read("alarm_setpoint").getAsDouble() == 10.0, "the signal layer agrees with the peripheral");
                    // Every machine can be named and given a port, and so can this one.
                    s.check(call(peripheral, "setName", "ARM-1")[0].equals("ARM-1") && call(peripheral, "getName")[0].equals("ARM-1"),
                            "the machine name works");
                } catch (dan200.computercraft.api.lua.LuaException e) {
                    throw new AssertionError("Lua call failed", e);
                } finally {
                    RadiationSources.clear(s.level, owner);
                    s.level.removeBlock(at, false);
                }
            });
        });
    }

    private static Object[] call(IPeripheral peripheral, String name, Object... args) throws dan200.computercraft.api.lua.LuaException {
        return PeripheralTestCalls.call(peripheral, name, args);
    }
}
