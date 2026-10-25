package dev.bwr.mod.peripheral;

import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.IPeripheral;
import dev.bwr.mod.eccs.EccsPower;
import dev.bwr.mod.rwcu.RwcuPumpBlockEntity;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * CC:Tweaked peripheral of the RWCU pump {@code bwr_rwcu_pump}: start, stop and speed (a program that sets any of them takes
 * the pump from redstone), and the loop's readings. Nothing starts it, stops it or isolates it for you; this class
 * references CC types and is only loaded where CC:Tweaked is present ({@link BwrPeripheralSupport} is the guard).
 */
public class RwcuPumpPeripheral implements IPeripheral {
    private final RwcuPumpBlockEntity be;

    public RwcuPumpPeripheral(RwcuPumpBlockEntity be) { this.be = be; }

    @Override public String getType() { return "bwr_rwcu_pump"; }
    @Override public boolean equals(@Nullable IPeripheral other) { return other instanceof RwcuPumpPeripheral p && p.be == be; }
    @Override public Object getTarget() { return be; }

    @LuaFunction(mainThread = true) public final void start() { claim(); be.setRunning(true); }
    @LuaFunction(mainThread = true) public final void stop() { claim(); be.setRunning(false); }
    @LuaFunction(mainThread = true) public final void setRunning(boolean running) { claim(); be.setRunning(running); }
    @LuaFunction(mainThread = true) public final void setSpeed(double fraction) throws LuaException {
        if (!Double.isFinite(fraction) || fraction < 0 || fraction > 1) throw new LuaException("Speed must be 0..1");
        claim();
        be.setSpeedDemandFraction(fraction);
    }
    @LuaFunction(mainThread = true) public final void releaseControl() { dev.bwr.mod.eccs.PlantActuators.run(be, () -> be.setComputerControlled(false)); }
    @LuaFunction(mainThread = true) public final boolean isRunning() { return be.isRunning(); }
    @LuaFunction(mainThread = true) public final double getSpeed() { return be.pump().getSpeedFraction(); }
    @LuaFunction(mainThread = true) public final double getTargetSpeed() { return be.pump().getSpeedDemandFraction(); }
    @LuaFunction(mainThread = true) public final double getFlow() { return be.getFlowKgPerS(); }

    private void claim() { dev.bwr.mod.eccs.PlantActuators.run(be, () -> be.setComputerControlled(true)); }

    /** Everything the loop reports: flows, temperatures, heat and the circuit's state. */
    @LuaFunction(mainThread = true) public final Map<String, Object> getStatus() {
        var m = new LinkedHashMap<String, Object>();
        m.put("circuitComplete", be.circuitValid());
        m.put("status", be.connectionStatus());
        m.put("running", be.isRunning());
        m.put("speed", be.pump().getSpeedFraction());
        m.put("flowKgPerS", be.getFlowKgPerS());
        m.put("demineraliserFlowKgPerS", be.getDemineraliserKgPerS());
        m.put("blowdownKgPerS", be.getRejectKgPerS());
        m.put("demineraliserC", be.getDemineraliserC());
        m.put("returnC", be.getReturnC());
        m.put("vesselHeatLossMW", be.getHeatLossMW());
        m.put("coolerDutyMW", be.getCoolerDutyMW());
        m.put("regenHeatRecoveredMW", be.getRegenDutyMW());
        m.put("energyFE", be.energy().getEnergyStored());
        m.put("drawFEPerTick", EccsPower.fePerTickFromWatts(be.pump().getElectricalDemandWatts()));
        m.put("computerControlled", be.isComputerControlled());
        return m;
    }
}
