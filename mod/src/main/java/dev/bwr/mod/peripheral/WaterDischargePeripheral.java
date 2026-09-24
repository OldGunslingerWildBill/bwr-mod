package dev.bwr.mod.peripheral;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.IPeripheral;
import dev.bwr.mod.water.WaterDischargeBlockEntity;
import java.util.Map;
public final class WaterDischargePeripheral implements IPeripheral {
    private final WaterDischargeBlockEntity be;
    public WaterDischargePeripheral(WaterDischargeBlockEntity b){be=b;}
    public String getType(){return "bwr_water_discharge";}
    public boolean equals(IPeripheral other){return other instanceof WaterDischargePeripheral p&&p.be==be;}
    @LuaFunction(mainThread=true) public void setEnabled(boolean enabled){be.setEnabled(enabled);}
    @LuaFunction(mainThread=true) public Map<String,Object> getStatus(){return Map.of("enabled",be.enabled(),"mouthClear",be.clearMouth(),"dischargedKg",be.totalKg(),"heatKJ",be.heatKJ(),"flowKgPerS",be.flowKgPerS(),"temperatureC",be.temperatureC());}
}
