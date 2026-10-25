package dev.bwr.mod.power;

import dev.bwr.mod.eccs.*;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.world.LiveCapabilities;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.capabilities.*;

public final class PowerModuleCapabilities {
    private PowerModuleCapabilities() {}
    public static Block[] blocks(){return new Block[]{BwrBlocks.HP_TURBINE.get(),BwrBlocks.LP_TURBINE.get(),BwrBlocks.NUCLEAR_GENERATOR.get()};}
    public static void register(RegisterCapabilitiesEvent event) {
        event.registerBlock(Capabilities.EnergyStorage.BLOCK,(level,pos,initial,unused,side)-> {
            if(!(initial.getBlock() instanceof PowerModuleBlock original)||!original.energyFace(pos,initial,side))return null;
            // Reconnect a reloaded controller with the same saved identity. A new
            // machine at these coordinates must require a newly acquired capability.
            java.util.UUID[] identity={PumpAssemblyCapabilities.controller(level,pos,initial) instanceof PowerModuleBlockEntity m&&m.identityReady()?m.deviceId():null};
            return LiveCapabilities.energy(level,pos,initial.getBlock(),state -> {
                if(!(state.getBlock() instanceof PowerModuleBlock b)||!b.energyFace(pos,state,side)
                        ||!(PumpAssemblyCapabilities.controller(level,pos,state) instanceof PowerModuleBlockEntity m)||!m.identityReady())return null;
                if(identity[0]==null)identity[0]=m.deviceId();
                return identity[0].equals(m.deviceId())?m.energy:null;
            });
        },blocks());
    }
}
