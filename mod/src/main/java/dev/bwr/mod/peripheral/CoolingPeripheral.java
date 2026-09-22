package dev.bwr.mod.peripheral;
import dan200.computercraft.api.lua.*;
import dan200.computercraft.api.peripheral.IPeripheral;
import dev.bwr.mod.cooling.CoolingBlockEntity;
import java.util.*;
public class CoolingPeripheral implements IPeripheral {
    private final CoolingBlockEntity be;public CoolingPeripheral(CoolingBlockEntity be){this.be=be;}
    @Override public String getType(){return "bwr_"+be.design().id;}
    @Override public boolean equals(IPeripheral other){return other instanceof CoolingPeripheral p&&p.be==be;}
    @LuaFunction(mainThread=true) public void setSpeed(double speed)throws LuaException{
        if(!Double.isFinite(speed)||speed<0||speed>1)throw new LuaException("Speed must be a finite fraction from 0 to 1");
        if(be.design().watts==0)throw new LuaException("This machine has no electric drive");be.setTarget(speed);
    }
    @LuaFunction(mainThread=true) public Map<String,Object> getStatus(){
        var p=be.plant();Map<String,Object> m=new LinkedHashMap<>();m.put("ready",be.ready());m.put("target",be.target());m.put("speed",be.actual());
        m.put("inputKg",p.input());m.put("outputKg",p.output());m.put("flowKgPerS",p.flow());m.put("ratedKgPerS",be.design().flow);
        m.put("makeupKgPerS",p.loss());m.put("heatRejectedMW",p.heatMW());m.put("energyFE",be.storedFE());m.put("drawFEPerTick",be.draw());return m;
    }
}
