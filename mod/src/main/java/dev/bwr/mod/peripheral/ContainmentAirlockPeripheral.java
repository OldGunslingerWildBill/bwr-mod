package dev.bwr.mod.peripheral;

import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.IPeripheral;
import dev.bwr.mod.containment.ContainmentAirlockBlockEntity;
import javax.annotation.Nullable;
import java.util.Map;

/** CC face on every airlock cell. Interlock is enforced by the root entity. */
public final class ContainmentAirlockPeripheral implements IPeripheral {
    private final ContainmentAirlockBlockEntity door;
    public ContainmentAirlockPeripheral(ContainmentAirlockBlockEntity door){this.door=door;}
    @Override public String getType(){return "bwr_containment_airlock";}
    @Override public Object getTarget(){return door;}
    @Override public boolean equals(@Nullable IPeripheral other){return other instanceof ContainmentAirlockPeripheral p&&p.door==door;}
    @LuaFunction(mainThread=true) public final boolean setInnerOpen(boolean open){
        return dev.bwr.mod.signal.DeviceCommands.airlockInnerOpen(door,open);
    }
    @LuaFunction(mainThread=true) public final boolean setOuterOpen(boolean open){
        return dev.bwr.mod.signal.DeviceCommands.airlockOuterOpen(door,open);
    }
    @LuaFunction(mainThread=true) public final void setOverride(boolean enabled){
        dev.bwr.mod.signal.DeviceCommands.airlockOverride(door,enabled);
    }
    @LuaFunction(mainThread=true) public final void releaseControl(){door.setComputerControlled(false);}
    @LuaFunction(mainThread=true) public final Map<String,Object> getStatus(){return Map.of(
            "innerOpen",door.innerOpen(),"outerOpen",door.outerOpen(),"forcedOverride",door.override(),
            "sealed",!door.innerOpen()||!door.outerOpen(),"damaged",door.damaged());}
}
