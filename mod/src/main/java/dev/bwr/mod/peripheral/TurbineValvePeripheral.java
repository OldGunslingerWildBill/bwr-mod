package dev.bwr.mod.peripheral;

import dan200.computercraft.api.lua.*;
import dan200.computercraft.api.peripheral.IPeripheral;
import dev.bwr.mod.steam.TurbineValveBlockEntity;
import java.util.Map;

public final class TurbineValvePeripheral implements IPeripheral {
    private final TurbineValveBlockEntity valve;
    public TurbineValvePeripheral(TurbineValveBlockEntity v){valve=v;}
    @Override public String getType(){return valve.stopValve()?"bwr_steam_stop_valve":valve.bypassValve()?"bwr_bypass_steam_valve":"bwr_turbine_control_valve";}
    @Override public boolean equals(IPeripheral p){return p instanceof TurbineValvePeripheral v&&v.valve==valve;}
    @LuaFunction(mainThread=true) public void setPosition(double fraction)throws LuaException{
        if(!Double.isFinite(fraction)||fraction<0||fraction>1)throw new LuaException("Opening must be a finite fraction from 0 to 1");
        if(valve.stopValve()&&fraction!=0&&fraction!=1)throw new LuaException("Stop valve accepts only 0 (closed) or 1 (open)");
        valve.setTarget(fraction);
    }
    @LuaFunction(mainThread=true) public void open(){valve.setTarget(1);}
    @LuaFunction(mainThread=true) public void close(){valve.setTarget(0);}
    @LuaFunction(mainThread=true) public Map<String,Object> getStatus(){return Map.of("target",valve.target(),"position",valve.position(),"turbineSteamKgPerS",valve.flowKgPerS(),"steamKgPerS",valve.flowKgPerS(),"stopValve",valve.stopValve(),"bypassValve",valve.bypassValve());}
}
