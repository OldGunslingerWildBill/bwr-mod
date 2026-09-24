package dev.bwr.mod.steam;

import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.world.LiveCapabilities;
import net.neoforged.neoforge.capabilities.*;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

@EventBusSubscriber(modid="bwr")
public final class MsivCapabilities {
    @SubscribeEvent public static void register(RegisterCapabilitiesEvent event){
        event.registerBlock(Capabilities.EnergyStorage.BLOCK,(level,pos,state,unused,side)->{
            var block=(MainSteamIsolationValveBlock)state.getBlock();
            if(!block.energyFace(state,side)||block.controller(level,pos,state)==null)return null;
            return LiveCapabilities.energy(level,pos,block,current->{
                if(!block.energyFace(current,side))return null;
                var valve=block.controller(level,pos,current);
                return valve==null?null:valve.energy();
            });
        },BwrBlocks.MSIV.get());
    }
}
