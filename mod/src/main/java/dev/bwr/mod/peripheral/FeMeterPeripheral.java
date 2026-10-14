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
        return dev.bwr.mod.grid.GridTelemetry.meter(meter);
    }
    @LuaFunction(mainThread=true) public boolean setLabel(String s){return dev.bwr.mod.signal.DeviceCommands.meterLabel(meter,s);}
    @LuaFunction(mainThread=true) public boolean setDisplayUnits(String s){return dev.bwr.mod.signal.DeviceCommands.meter(meter,"display_units","MW".equals(s)?0:"FE/t".equals(s)?1:-1);}
    @LuaFunction(mainThread=true) public boolean setDirection(String s){var d=Direction.byName(s);return d!=null&&dev.bwr.mod.signal.DeviceCommands.meter(meter,"direction",d.get3DDataValue());}
}
