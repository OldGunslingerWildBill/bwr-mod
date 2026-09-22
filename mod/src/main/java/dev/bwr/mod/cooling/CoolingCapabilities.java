package dev.bwr.mod.cooling;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.world.LiveCapabilities;
import net.neoforged.neoforge.capabilities.*;
public final class CoolingCapabilities {
    public static void register(RegisterCapabilitiesEvent e){
        for(var b:new CoolingBlock[]{BwrBlocks.NATURAL_DRAFT_TOWER.get(),BwrBlocks.MECHANICAL_DRAFT_TOWER.get(),BwrBlocks.CIRCULATING_WATER_PUMP.get(),BwrBlocks.MAKEUP_WATER_PUMP.get(),BwrBlocks.SCREENED_WATER_INTAKE.get()}){
            e.registerBlock(Capabilities.FluidHandler.BLOCK,(l,p,s,be,side)->{
                if(side==null||!CoolingBlock.acceptsWater(s,side))return null;
                return LiveCapabilities.fluid(l,p,s.getBlock(),current -> {
                    if(!CoolingBlock.acceptsWater(current,side)||!(l.getBlockEntity(p) instanceof CoolingBlockEntity part))return null;
                    var owner=part.owner();return owner!=null&&owner.ready()?owner.water(current.getValue(CoolingBlock.PORT)):null;
                });
            },b);
            e.registerBlock(Capabilities.EnergyStorage.BLOCK,(l,p,s,be,side)->{
                if(side==null||s.getValue(CoolingBlock.PORT)!=CoolingBlock.Port.POWER||side!=CoolingBlock.portFace(s))return null;
                return LiveCapabilities.energy(l,p,s.getBlock(),current -> {
                    if(current.getValue(CoolingBlock.PORT)!=CoolingBlock.Port.POWER||side!=CoolingBlock.portFace(current)||!(l.getBlockEntity(p) instanceof CoolingBlockEntity part))return null;
                    var owner=part.owner();return owner!=null&&owner.ready()?owner.power():null;
                });
            },b);
        }
    }
}
