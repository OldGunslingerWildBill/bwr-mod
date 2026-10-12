package dev.bwr.mod.peripheral;

import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.IPeripheral;
import dev.bwr.mod.containment.ContainmentVaultDoorBlockEntity;
import javax.annotation.Nullable;
import java.util.Map;

/** Computer face shared by every leaf cell; commands execute on the root. */
public final class ContainmentVaultDoorPeripheral implements IPeripheral {
    private final ContainmentVaultDoorBlockEntity door;
    public ContainmentVaultDoorPeripheral(ContainmentVaultDoorBlockEntity door){this.door=door;}
    @Override public String getType(){return "bwr_containment_vault_door";}
    @Override public Object getTarget(){return door;}
    @Override public boolean equals(@Nullable IPeripheral other){return other instanceof ContainmentVaultDoorPeripheral p&&p.door==door;}
    @LuaFunction(mainThread=true) public final boolean setOpen(boolean open){
        boolean started=door.setOpen(open,true);
        if(started)door.setComputerControlled(true);
        return started;
    }
    @LuaFunction(mainThread=true) public final void releaseControl(){door.setComputerControlled(false);}
    @LuaFunction(mainThread=true) public final Map<String,Object> getStatus(){return Map.of(
            "state",door.state().name(),"openFraction",door.swing(0),"energyFE",door.energy().getEnergyStored(),
            "computerControlled",door.isComputerControlled(),"sealed",door.state()==ContainmentVaultDoorBlockEntity.State.CLOSED_SEALED);
    }
}
