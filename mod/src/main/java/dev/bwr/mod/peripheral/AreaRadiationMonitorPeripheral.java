package dev.bwr.mod.peripheral;

import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.IPeripheral;
import dev.bwr.mod.radiation.AreaRadiationMonitorBlockEntity;
import dev.bwr.mod.radiation.RadiationField;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * CC:Tweaked peripheral {@code bwr_radiation_monitor}: the dose rate where the monitor hangs (mSv/h) and its alarm.
 * It only reports; the one setter is the alarm setpoint, which does nothing but move the lamp and the outputs of this
 * monitor. Nothing here trips, isolates or alarms anything else, that is the player's program.
 */
public final class AreaRadiationMonitorPeripheral implements IPeripheral {
    private final AreaRadiationMonitorBlockEntity monitor;

    public AreaRadiationMonitorPeripheral(AreaRadiationMonitorBlockEntity monitor) {
        this.monitor = monitor;
    }

    @Override
    public String getType() {
        return "bwr_radiation_monitor";
    }

    @Override
    public boolean equals(IPeripheral other) {
        return other instanceof AreaRadiationMonitorPeripheral p && p.monitor == monitor;
    }

    @Override
    public Object getTarget() {
        return monitor;
    }

    /** The dose rate at the monitor, mSv/h. */
    @LuaFunction(mainThread = true)
    public double getDoseRate() {
        return monitor.doseRate();
    }

    /** Whether the dose rate is at or above the alarm setpoint (the lamp is red). */
    @LuaFunction(mainThread = true)
    public boolean isHighRadiation() {
        return monitor.isHigh();
    }

    /** Whether the dose rate is at or above the alert setpoint, a tenth of the alarm setpoint (the lamp is amber or red). */
    @LuaFunction(mainThread = true)
    public boolean isAlert() {
        return monitor.isAlert();
    }

    /** The alarm setpoint, mSv/h. */
    @LuaFunction(mainThread = true)
    public double getAlarmSetpoint() {
        return monitor.highSetpoint();
    }

    /** The alert setpoint, mSv/h. */
    @LuaFunction(mainThread = true)
    public double getAlertSetpoint() {
        return monitor.alertSetpoint();
    }

    /** Sets the alarm setpoint, mSv/h (0.001 to 1000000); false if the value is out of range. */
    @LuaFunction(mainThread = true)
    public boolean setAlarmSetpoint(double msvPerHour) {
        return monitor.setHighSetpoint(msvPerHour);
    }

    /** Everything at once: the reading, the setpoints, the lamp and the dose rate by source (all mSv/h). */
    @LuaFunction(mainThread = true)
    public Map<String, Object> getStatus() {
        RadiationField.Reading reading = monitor.current();
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("doseRate", reading.total());
        status.put("unit", "mSv/h");
        status.put("highRadiation", monitor.isHigh());
        status.put("alert", monitor.isAlert());
        status.put("lamp", monitor.lamp());
        status.put("alarmSetpoint", monitor.highSetpoint());
        status.put("alertSetpoint", monitor.alertSetpoint());
        status.put("airborne", reading.airborne());
        status.put("deposited", reading.deposited());
        status.put("release", reading.release());
        status.put("core", reading.core());
        status.put("other", reading.other());
        return status;
    }
}
