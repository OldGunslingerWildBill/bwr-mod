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
            // Only the two shell-side flanges expose water: a single block's side faces, or the two flange cells of a full-size unit.
            var port=RhrHeatExchangerBlock.portOf(s,face);
            if(port!=RhrHeatExchangerBlock.HxPort.SHELL_IN&&port!=RhrHeatExchangerBlock.HxPort.SHELL_OUT)return null;
            return LiveCapabilities.fluid(l,p,s.getBlock(),current->{
                if(RhrHeatExchangerBlock.portOf(current,face)!=port)return null;
                var hx=RhrHeatExchangerBlock.controller(l,p,current);
                return hx==null?null:hx.secondary(port==RhrHeatExchangerBlock.HxPort.SHELL_IN);
            });
        },BwrBlocks.RHR_HEAT_EXCHANGER.get());
        event.registerBlock(Capabilities.FluidHandler.BLOCK, (l,p,s,be,face) ->
                LiveCapabilities.fluid(l,p,s.getBlock(), current ->
                        l.getBlockEntity(p) instanceof dev.bwr.mod.containment.ContainmentSprayHeaderBlockEntity header
                                ? header.waterInlet() : null), BwrBlocks.CONTAINMENT_SPRAY_HEADER.get());
    }
}
