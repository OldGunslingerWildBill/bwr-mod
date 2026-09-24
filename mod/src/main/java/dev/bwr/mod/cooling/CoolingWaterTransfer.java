package dev.bwr.mod.cooling;

import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.water.WaterLineNetwork;
import net.minecraft.core.*;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import java.util.*;

/** Pump suction visits only water pipes, ending at real tanks or foreign networks. */
public final class CoolingWaterTransfer {
    public static void pull(CoolingBlockEntity pump){
        var l=pump.getLevel();var facing=pump.getBlockState().getValue(CoolingBlock.FACING);
        var port=pump.layout().ports.stream().filter(c->c.role()==CoolingBlock.Port.INLET).findFirst().orElseThrow();
        var at=pump.layout().world(pump.root(),facing,port);var side=CoolingBlock.portFace(l.getBlockState(at));
        var graph=dev.bwr.mod.piping.PipeTopology.get(l,at,side,false,true);
        if(graph.truncated())return;
        var unique=Collections.newSetFromMap(new IdentityHashMap<IFluidHandler,Boolean>());
        var sources=new ArrayList<IFluidHandler>();
        for(var end:graph.ends()) {
            var p=end.pos();if(!l.isLoaded(p))continue;
            if(l.getBlockEntity(p) instanceof CoolingBlockEntity part&&part.owner()==pump)continue;
            var source=l.getCapability(Capabilities.FluidHandler.BLOCK,p,end.face());
            var identity=dev.bwr.mod.world.LiveCapabilities.fluidIdentity(source);
            if(identity!=null&&unique.add(identity))sources.add(source);
        }
        int wanted=(int)Math.floor(Math.min(pump.design().flow/20,pump.design().capacity-pump.plant().input()));
        for(var source:sources){
            if(wanted<=0)break;
            var taken=source.drain(wanted,IFluidHandler.FluidAction.SIMULATE);
            if(!taken.isEmpty()&&taken.is(Fluids.WATER)){
                taken=source.drain(taken,IFluidHandler.FluidAction.EXECUTE);
                pump.plant().fillInput(taken.getAmount(),dev.bwr.mod.water.ThermalWater.enthalpy(taken),false);wanted-=taken.getAmount();pump.setChanged();
            }
        }
    }
}
