package dev.bwr.mod.peripheral;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.IPeripheral;
import dev.bwr.mod.condenser.CondenserBlockEntity;
import java.util.*;
public final class CondenserPeripheral implements IPeripheral {
    private final CondenserBlockEntity be;
    public CondenserPeripheral(CondenserBlockEntity be){this.be=be;}
    public String getType(){return "bwr_condenser";}
    public boolean equals(IPeripheral other){return other instanceof CondenserPeripheral p&&p.be==be;}
    @LuaFunction(mainThread=true) public Map<String,Object> getStatus(){
        var p=be.plant();var m=new LinkedHashMap<String,Object>();m.put("ready",be.ready());
        m.put("coldWaterKg",p.cold());m.put("hotWaterKg",p.hot());m.put("condensateKg",p.condensate());m.put("steamKg",p.steam.mass());
        m.put("hotwellKg",p.condensate());m.put("hotwellC",p.condensateC());m.put("makeupSpaceKg",dev.bwr.core.turbine.SurfaceCondenser.CONDENSATE_CAPACITY-p.condensate());
        m.put("steamKgPerS",p.steamRate());m.put("coolingKgPerS",p.coolingRate());m.put("heatRejectedMW",p.rejectedMW());m.put("coldWaterC",p.coldC());m.put("hotWaterC",p.hotC());m.put("condensateC",p.condensateC());m.put("backpressurePsia",p.backpressurePsia());m.put("vacuumInHg",p.vacuumInHg());return m;
    }
}
