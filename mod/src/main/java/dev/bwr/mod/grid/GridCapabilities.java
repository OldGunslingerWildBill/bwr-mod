package dev.bwr.mod.grid;

import dev.bwr.mod.registry.BwrBlockEntities;
import dev.bwr.mod.registry.BwrBlocks;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

public final class GridCapabilities {
    private GridCapabilities() {}
    public static void register(RegisterCapabilitiesEvent event){event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK,BwrBlockEntities.FE_METER.get(),(be,face)->be.capability(face));// Every cell of a tie (and a saved one-block tie) exposes the controller's sink, on every face, through a live proxy as the MSIV does.
        event.registerBlock(Capabilities.EnergyStorage.BLOCK,(level,pos,state,unused,side)->dev.bwr.mod.world.LiveCapabilities.energy(level,pos,state.getBlock(),current->{
            var tie=((GridTieBlock)current.getBlock()).controller(level,pos,current);
            return tie==null?null:tie.energy;
        }),BwrBlocks.GRID_TIE.get());}
}
