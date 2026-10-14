package dev.bwr.mod.grid;

import dev.bwr.mod.registry.BwrBlockEntities;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

public final class GridCapabilities {
    private GridCapabilities() {}
    public static void register(RegisterCapabilitiesEvent event){event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK,BwrBlockEntities.GRID_TIE.get(),(be,face)->be.energy);}
}
