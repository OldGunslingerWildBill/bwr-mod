package dev.bwr.mod.condenser;

import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.world.LiveCapabilities;
import net.neoforged.neoforge.capabilities.*;

public final class CondenserCapabilities {
    private CondenserCapabilities(){}
    public static void register(RegisterCapabilitiesEvent e){
        e.registerBlock(Capabilities.FluidHandler.BLOCK,(l,p,s,unused,side)->{
            if(side==null||!CondenserBlock.acceptsWater(s,side))return null;
            return LiveCapabilities.fluid(l,p,s.getBlock(),current -> {
                if(!CondenserBlock.acceptsWater(current,side)||!(l.getBlockEntity(p) instanceof CondenserBlockEntity part))return null;
                var owner=part.owner();return owner!=null&&owner.ready()?owner.water(current.getValue(CondenserBlock.PORT)):null;
            });
        },BwrBlocks.ARABELLE_CONDENSER.get());
    }
}
