package dev.bwr.mod.peripheral;

import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.IPeripheral;
import dev.bwr.core.grid.EnergyUnits;
import dev.bwr.mod.grid.FeMeterBlockEntity;
import net.minecraft.core.Direction;
import java.util.*;

public final class FeMeterPeripheral implements IPeripheral {
    private final FeMeterBlockEntity meter;
    public FeMeterPeripheral(FeMeterBlockEntity meter){this.meter=meter;}
    public String getType(){return "bwr_fe_meter";}
    public boolean equals(IPeripheral other){return other instanceof FeMeterPeripheral p&&p.meter==meter;}
    @LuaFunction(mainThread=true) public Map<String,Object> getStatus(){
        var m=new LinkedHashMap<String,Object>();var e=meter.ledger();
        m.put("label",meter.label());m.put("displayUnits",meter.units());m.put("direction",meter.direction().getName());
        m.put("mwNow",EnergyUnits.mw(e.lastTickFE()));m.put("mw1s",EnergyUnits.mw(e.averageFE(20)));m.put("mw1min",EnergyUnits.mw(e.averageFE(1200)));
        m.put("fePerTickNow",e.lastTickFE());m.put("fePerTick1s",e.averageFE(20));m.put("fePerTick1min",e.averageFE(1200));m.put("mwh",EnergyUnits.mwh(e.totalFE()));
        m.put("sampleTicks",e.sampleTicks());m.put("windowReady1s",e.sampleTicks()>=20);m.put("windowReady1min",e.sampleTicks()>=1200);m.put("counterSaturated",e.saturated());return m;
    }
    @LuaFunction(mainThread=true) public boolean setLabel(String s){return meter.label(s);}
    @LuaFunction(mainThread=true) public boolean setDisplayUnits(String s){return meter.units(s);}
    @LuaFunction(mainThread=true) public boolean setDirection(String s){return meter.direction(Direction.byName(s));}
}
