package dev.bwr.mod.power;

import dev.bwr.mod.eccs.*;
import dev.bwr.mod.reactor.RpvSteamOutletBlockEntity;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.steam.*;
import net.minecraft.core.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import java.util.*;

/** Trace one steam header only. Machines terminate the search; no cross-port shortcuts. */
public final class PowerSteamNetwork {
    private PowerSteamNetwork() {}
    public record Source(BlockEntity entity,double opening,double share,List<TurbineValveBlockEntity> valves) {
        public double limit(double wanted,double rawKg){
            for(var v:valves)wanted=Math.min(wanted,v.allowance(entity.getBlockPos(),rawKg));
            return wanted;
        }
        public void record(double kg){for(var v:valves)v.record(entity.getBlockPos(),kg);}
    }
    private record Node(BlockPos pos,double opening,List<TurbineValveBlockEntity> valves) {}
    public static List<Source> sources(PowerModuleBlockEntity module) {
        Level level=module.getLevel();var block=module.block();var state=module.getBlockState();
        if(block.generator())return List.of();
        var start=block.portPosition(module.getBlockPos(),state,AssemblyPort.STEAM_INLET);
        Direction out=block.portFace(state,AssemblyPort.STEAM_INLET);
        var graph=dev.bwr.mod.piping.PipeTopology.get(level,start,out,true,false);
        if(graph.truncated())return List.of();
        Map<BlockPos,Source> sources=new TreeMap<>();
        Map<BlockPos,PowerModuleBlockEntity> consumers=new HashMap<>();consumers.put(module.getBlockPos(),module);
        for(var route:dev.bwr.mod.piping.PipeTopology.routes(level,graph)) {
            var node=route.node();if(node.kind()!=dev.bwr.mod.piping.PipeTopology.Kind.END||route.opening()<=0)continue;
            var next=node.pos();if(!level.isLoaded(next))continue;
            var s=level.getBlockState(next);var be=level.getBlockEntity(next);
            if(s.getBlock() instanceof PowerModuleBlock other) {
                if(other.highPressure()==block.highPressure()&&!other.generator()&&other.portAt(s,node.face())==AssemblyPort.STEAM_INLET
                        &&other.controller(level,next,s) instanceof PowerModuleBlockEntity peer&&other.complete(level,peer.getBlockPos(),s))consumers.put(peer.getBlockPos(),peer);
                if(!block.highPressure()&&other.highPressure()&&other.portAt(s,node.face())==AssemblyPort.STEAM_EXHAUST
                        &&other.controller(level,next,s) instanceof PowerModuleBlockEntity hp&&other.complete(level,hp.getBlockPos(),s))
                    sources.merge(hp.getBlockPos(),new Source(hp,route.opening(),0,route.valves()),(a,b)->a.opening>=b.opening?a:b);
            } else if(block.highPressure()&&be instanceof RpvSteamOutletBlockEntity nozzle&&nozzle.isPartOfFormedReactor()&&nozzle.getPosition()>0)
                sources.merge(next,new Source(nozzle,route.opening(),0,route.valves()),(a,b)->a.opening>=b.opening?a:b);
        }
        double demand=consumers.values().stream().mapToDouble(PowerModuleBlockEntity::headerDemandKg).sum();
        double share=demand>0?Math.min(1,module.headerDemandKg()/demand):0;
        return sources.values().stream().map(s->new Source(s.entity,s.opening,share,s.valves)).toList();
    }
}
