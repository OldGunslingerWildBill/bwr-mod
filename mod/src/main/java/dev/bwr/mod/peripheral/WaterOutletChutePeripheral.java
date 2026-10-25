package dev.bwr.mod.peripheral;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.IPeripheral;
import dev.bwr.mod.water.WaterOutletChuteBlockEntity;
import java.util.LinkedHashMap;
import java.util.Map;

/** The heat exchanger's river outfall: reports whether it faces open water and what it returns to the river. */
public final class WaterOutletChutePeripheral implements IPeripheral {
    private final WaterOutletChuteBlockEntity be;
    public WaterOutletChutePeripheral(WaterOutletChuteBlockEntity b){be=b;}
    public String getType(){return "bwr_water_outlet_chute";}
    public boolean equals(IPeripheral other){return other instanceof WaterOutletChutePeripheral p&&p.be==be;}
    /** Open or close the chute. A closed chute accepts no water, so the line behind it backs up. */
    @LuaFunction(mainThread=true) public void setEnabled(boolean enabled){be.setEnabled(enabled);}
    @LuaFunction(mainThread=true) public Map<String,Object> getStatus(){
        var m=new LinkedHashMap<String,Object>();
        m.put("enabled",be.enabled());m.put("facingWater",be.clearMouth());m.put("waterState",be.waterState().name());
        m.put("dischargedKg",be.totalKg());m.put("flowKgPerS",be.flowKgPerS());m.put("temperatureC",be.temperatureC());
        m.put("riverTemperatureC",WaterOutletChuteBlockEntity.RIVER_C);m.put("heatToRiverMW",be.heatToRiverMW());
        return m;
    }
}
