package dev.bwr.mod.peripheral;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.IPeripheral;
import dev.bwr.mod.suppression.RhrHeatExchangerBlockEntity;
import java.util.*;
public final class HeatExchangerPeripheral implements IPeripheral {
    private final RhrHeatExchangerBlockEntity be;
    public HeatExchangerPeripheral(RhrHeatExchangerBlockEntity be){this.be=be;}
    public String getType(){return "bwr_rhr_heat_exchanger";}
    public boolean equals(IPeripheral other){return other instanceof HeatExchangerPeripheral p&&p.be==be;}
    @LuaFunction(mainThread=true) public Map<String,Object> getStatus(){
        var p=be.plant();var m=new LinkedHashMap<String,Object>();m.put("poolConnected",be.returnPool()!=null);
        m.put("coldWaterKg",p.cold());m.put("hotWaterKg",p.hot());m.put("heatRejectedMW",p.heatMW());m.put("hotWaterC",p.hotTemperatureC());m.put("coldWaterC",p.coldTemperatureC());m.put("primaryFlowKgPerS",p.primaryFlow());m.put("secondaryFlowKgPerS",p.secondaryFlow());m.put("primaryOutletC",p.primaryOutC());return m;
    }
}
