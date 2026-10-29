package dev.bwr.mod.peripheral;

import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.IPeripheral;
import dev.bwr.mod.power.LubeOilValveBlockEntity;

import java.util.LinkedHashMap;
import java.util.Map;

/** CC face of the Lube Oil Valve (TURBINE-AUX.md): turn it, and read its limit switches. Nothing else ever moves the valve. */
public final class LubeOilValvePeripheral implements IPeripheral {
    private final LubeOilValveBlockEntity valve;

    public LubeOilValvePeripheral(LubeOilValveBlockEntity valve) { this.valve = valve; }

    @Override public String getType() { return "bwr_lube_oil_valve"; }
    @Override public boolean equals(IPeripheral other) { return other instanceof LubeOilValvePeripheral p && p.valve == valve; }
    @Override public Object getTarget() { return valve; }

    @LuaFunction(mainThread = true) public void open() { valve.setOpen(true); }
    @LuaFunction(mainThread = true) public void close() { valve.setOpen(false); }
    @LuaFunction(mainThread = true) public void setOpen(boolean open) { valve.setOpen(open); }
    /** True while the valve is open (the open limit switch is made). */
    @LuaFunction(mainThread = true) public boolean isOpen() { return valve.limitOpen(); }
    /** True while the valve is shut (the closed limit switch is made). */
    @LuaFunction(mainThread = true) public boolean isClosed() { return valve.limitClosed(); }
    @LuaFunction(mainThread = true) public double getPosition() { return valve.position(); }

    @LuaFunction(mainThread = true) public Map<String, Object> getStatus() {
        var m = new LinkedHashMap<String, Object>();
        m.put("open", valve.isOpen());
        m.put("closed", valve.limitClosed());
        m.put("limitOpen", valve.limitOpen());
        m.put("limitClosed", valve.limitClosed());
        m.put("position", valve.position());
        return m;
    }
}
