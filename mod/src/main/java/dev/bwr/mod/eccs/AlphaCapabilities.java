package dev.bwr.mod.eccs;

import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.water.*;
import dev.bwr.mod.world.LiveCapabilities;
import net.neoforged.neoforge.capabilities.*;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

@EventBusSubscriber(modid="bwr")
public final class AlphaCapabilities {
    @SubscribeEvent public static void register(RegisterCapabilitiesEvent e){
        e.registerBlock(Capabilities.FluidHandler.BLOCK,(level,pos,state,unused,side)->{
            if(side==null||((SlcTankBlock)state.getBlock()).portAt(state,side)!=AssemblyPort.WATER_SUCTION)return null;
            return LiveCapabilities.fluid(level,pos,state.getBlock(),current->{
                var block=(SlcTankBlock)current.getBlock();
                if(block.portAt(current,side)!=AssemblyPort.WATER_SUCTION)return null;
                return block.controller(level,pos,current) instanceof SlcTankBlockEntity tank&&tank.ready()?tank.waterInlet():null;
            });
        },BwrBlocks.SLC_BORON_TANK.get());
        e.registerBlock(Capabilities.FluidHandler.BLOCK,(level,pos,state,unused,side)->{
            if(side!=state.getValue(WaterDischargeBlock.FACING).getOpposite())return null;
            return LiveCapabilities.fluid(level,pos,state.getBlock(),current->side==current.getValue(WaterDischargeBlock.FACING).getOpposite()
                    &&level.getBlockEntity(pos) instanceof WaterDischargeBlockEntity out?out.inlet():null);
        },BwrBlocks.WATER_DISCHARGE_PORT.get());
    }
}
