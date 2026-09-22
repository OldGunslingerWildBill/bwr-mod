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
        record Edge(BlockPos pos,Direction face){}
        var queue=new ArrayDeque<Edge>();queue.add(new Edge(at.relative(side),side.getOpposite()));
        var seen=new HashSet<BlockPos>();var unique=Collections.newSetFromMap(new IdentityHashMap<IFluidHandler,Boolean>());var sources=new ArrayList<IFluidHandler>();
        while(!queue.isEmpty()){
            var edge=queue.remove();var p=edge.pos();if(!l.isLoaded(p))continue;var state=l.getBlockState(p);
            if(state.is(BwrBlocks.HIGH_PRESSURE_WATER_PIPE.get())){
                if(!seen.add(p))continue;if(seen.size()>WaterLineNetwork.MAX_PIPE_BLOCKS)return;
                for(Direction d:Direction.values())queue.add(new Edge(p.relative(d),d.getOpposite()));
            }else{
                if(l.getBlockEntity(p) instanceof CoolingBlockEntity part&&part.owner()==pump)continue;
                if(!WaterLineNetwork.connectsTo(l,p,edge.face()))continue;
                var source=l.getCapability(Capabilities.FluidHandler.BLOCK,p,edge.face());if(source!=null&&unique.add(source))sources.add(source);
            }
        }
        int wanted=(int)Math.floor(Math.min(pump.design().flow/20,pump.design().capacity-pump.plant().input()));
        for(var source:sources){
            if(wanted<=0)break;
            var taken=source.drain(new FluidStack(Fluids.WATER,wanted),IFluidHandler.FluidAction.EXECUTE);
            if(!taken.isEmpty()){pump.plant().fillInput(taken.getAmount(),false);wanted-=taken.getAmount();pump.setChanged();}
        }
    }
}
