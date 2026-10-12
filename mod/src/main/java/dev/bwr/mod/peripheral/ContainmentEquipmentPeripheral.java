package dev.bwr.mod.peripheral;

import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.IPeripheral;
import dev.bwr.mod.containment.ContainmentEquipmentBlockEntity;
import javax.annotation.Nullable;
import java.util.Map;

/** Direct CC control of a powered atmosphere device. */
public final class ContainmentEquipmentPeripheral implements IPeripheral {
    private final ContainmentEquipmentBlockEntity equipment;
    public ContainmentEquipmentPeripheral(ContainmentEquipmentBlockEntity equipment) { this.equipment = equipment; }
    @Override public String getType() { return equipment.isVent() ? "bwr_containment_vent" : "bwr_containment_nitrogen"; }
    @Override public Object getTarget() { return equipment; }
    @Override public boolean equals(@Nullable IPeripheral other) {
        return other instanceof ContainmentEquipmentPeripheral p && p.equipment == equipment;
    }
    @LuaFunction(mainThread = true) public final boolean setActive(boolean active) {
        return dev.bwr.mod.signal.DeviceCommands.equipmentActive(equipment, active);
    }
    @LuaFunction(mainThread = true) public final Map<String, Object> getStatus() {
        return Map.of("type", getType(), "demand", equipment.demand(),
                "energyFE", equipment.energy().getEnergyStored(),
                "nitrogenTicks", equipment.nitrogenTicks(),
                "poweredAndSupplied", equipment.supplied());
    }
}
