package dev.bwr.mod.suppression;

import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.world.LiveCapabilities;
import net.neoforged.neoforge.capabilities.*;

public final class SuppressionWaterCapabilities {
    public static void register(RegisterCapabilitiesEvent event){
        for(var block:new SuppressionPoolPortBlock[]{BwrBlocks.SUPPRESSION_POOL_SUCTION.get(),BwrBlocks.SUPPRESSION_POOL_RETURN.get()}){
            event.registerBlock(Capabilities.FluidHandler.BLOCK,(l,p,s,be,face)->{
                if(face==null||face!=s.getValue(SuppressionPoolPortBlock.FACING))return null;
                return LiveCapabilities.fluid(l,p,s.getBlock(),current->{
                    if(face!=current.getValue(SuppressionPoolPortBlock.FACING)||!(l.getBlockEntity(p) instanceof SuppressionPoolPortBlockEntity part))return null;
                    var owner=part.owner();return owner==null?null:owner.water(block.suction);
                });
            },block);
        }
        event.registerBlock(Capabilities.FluidHandler.BLOCK,(l,p,s,be,face)->{
            if(face==null||face!=RhrHeatExchangerBlock.coldIn(s)&&face!=RhrHeatExchangerBlock.hotOut(s))return null;
            return LiveCapabilities.fluid(l,p,s.getBlock(),current->{
                if(!(l.getBlockEntity(p) instanceof RhrHeatExchangerBlockEntity hx))return null;
                if(face==RhrHeatExchangerBlock.coldIn(current))return hx.secondary(true);
                if(face==RhrHeatExchangerBlock.hotOut(current))return hx.secondary(false);
                return null;
            });
        },BwrBlocks.RHR_HEAT_EXCHANGER.get());
    }
}
