package dev.bwr.mod.peripheral;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.IPeripheral;
import java.util.Map;
import java.util.function.Supplier;
/** Measurements for passive hardware and the fuel fabricator. */
public final class HardwarePeripheral implements IPeripheral {
    private final String type;private final Supplier<Map<String,Object>> status;
    public HardwarePeripheral(String type,Supplier<Map<String,Object>> status){this.type=type;this.status=status;}
    public String getType(){return type;}
    public boolean equals(IPeripheral other){return other==this;}
    @LuaFunction(mainThread=true) public Map<String,Object> getStatus(){return status.get();}
}
