package dev.bwr.mod.power;

import dev.bwr.mod.eccs.*;
import dev.bwr.mod.registry.BwrBlocks;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.capabilities.*;

public final class PowerModuleCapabilities {
    private PowerModuleCapabilities() {}
    public static Block[] blocks(){return new Block[]{BwrBlocks.HP_TURBINE.get(),BwrBlocks.LP_TURBINE.get(),BwrBlocks.NUCLEAR_GENERATOR.get()};}
    public static void register(RegisterCapabilitiesEvent event) {
        event.registerBlock(Capabilities.EnergyStorage.BLOCK,(level,pos,state,unused,side)->
            state.getBlock() instanceof PowerModuleBlock b && b.energyFace(pos,state,side)
                    && PumpAssemblyCapabilities.controller(level,pos,state) instanceof PowerModuleBlockEntity m?m.energy:null,blocks());
        event.registerBlock(Capabilities.FluidHandler.BLOCK,(level,pos,state,unused,side)->
            state.getBlock() instanceof PowerModuleBlock b && side!=null && b.portAt(state,side)==AssemblyPort.WATER_DISCHARGE
                    && PumpAssemblyCapabilities.controller(level,pos,state) instanceof PowerModuleBlockEntity m?m.condensate:null,blocks());
    }
}
