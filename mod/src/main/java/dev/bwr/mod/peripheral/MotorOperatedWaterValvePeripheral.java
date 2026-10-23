package dev.bwr.mod.peripheral;

import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.IPeripheral;
import dev.bwr.mod.water.MotorOperatedWaterValveBlockEntity;

import java.util.LinkedHashMap;
import java.util.Map;

/** CC face of the motor-operated water valve: demands, the fail position, and the readings. */
public final class MotorOperatedWaterValvePeripheral implements IPeripheral {
    private final MotorOperatedWaterValveBlockEntity valve;

    public MotorOperatedWaterValvePeripheral(MotorOperatedWaterValveBlockEntity valve) { this.valve = valve; }

    @Override public String getType() { return "bwr_motor_operated_water_valve"; }
    @Override public boolean equals(IPeripheral other) { return other instanceof MotorOperatedWaterValvePeripheral p && p.valve == valve; }
    @Override public Object getTarget() { return valve; }

    @LuaFunction(mainThread = true) public void setPosition(double fraction) throws LuaException {
        if (!Double.isFinite(fraction) || fraction < 0 || fraction > 1) throw new LuaException("Opening must be a finite fraction from 0 to 1");
        valve.setTarget(fraction);
    }
    @LuaFunction(mainThread = true) public void open() { valve.setTarget(1); }
    @LuaFunction(mainThread = true) public void close() { valve.setTarget(0); }
    @LuaFunction(mainThread = true) public double getPosition() { return valve.position(); }
    @LuaFunction(mainThread = true) public double getDemand() { return valve.target(); }

    /** "as_is", "open" or "closed" (also "fail_open", "fail_closed", "none"). */
    @LuaFunction(mainThread = true) public void setFailPosition(String mode) throws LuaException {
        var parsed = MotorOperatedWaterValveBlockEntity.FailMode.byKey(mode);
        if (parsed == null) throw new LuaException("Fail position must be \"as_is\", \"open\" or \"closed\"");
        valve.setFailMode(parsed);
    }
    @LuaFunction(mainThread = true) public String getFailPosition() { return valve.failMode().key(); }

    @LuaFunction(mainThread = true) public Map<String, Object> getStatus() {
        var m = new LinkedHashMap<String, Object>();
        m.put("target", valve.target());
        m.put("position", valve.position());
        m.put("open", valve.isOpen());
        m.put("closed", valve.isClosed());
        m.put("flowKgPerS", valve.flowKgPerS());
        m.put("powered", valve.isPowered());
        m.put("energyFE", valve.energy().getEnergyStored());
        m.put("failPosition", valve.failMode().key());
        m.put("ratingPsi", MotorOperatedWaterValveBlockEntity.RATING_PSI);
        m.put("wearAbovePsig", MotorOperatedWaterValveBlockEntity.wearAbovePsig());
        return m;
    }
}
