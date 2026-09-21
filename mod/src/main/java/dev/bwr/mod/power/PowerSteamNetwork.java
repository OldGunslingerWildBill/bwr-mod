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
        Map<BlockPos,Double> seen=new HashMap<>();ArrayDeque<Node> queue=new ArrayDeque<>();
        Map<BlockPos,Source> sources=new TreeMap<>();queue.add(new Node(start,1,List.of()));seen.put(start,1.0);
        Map<BlockPos,PowerModuleBlockEntity> consumers=new HashMap<>();consumers.put(module.getBlockPos(),module);
        while(!queue.isEmpty()) {
            var node=queue.remove();var current=level.getBlockState(node.pos);
            for(Direction d:Direction.values()) {
                if(node.pos.equals(start)&&d!=out)continue;
                BlockPos next=node.pos.relative(d);if(!level.isLoaded(next))continue;
                var s=level.getBlockState(next);
                if(!SteamLineNetwork.acceptsLineOn(current,d)||!SteamLineNetwork.acceptsLineOn(s,d.getOpposite()))continue;
                double opening=node.opening;
                List<TurbineValveBlockEntity> valves=node.valves;
                var be=level.getBlockEntity(next);
                if(be instanceof MainSteamIsolationValveBlockEntity v)opening=Math.min(opening,v.getPosition());
                if(be instanceof TurbineValveBlockEntity v){opening=Math.min(opening,v.position());var copy=new ArrayList<>(valves);copy.add(v);valves=List.copyOf(copy);}
                if(opening<=0||seen.getOrDefault(next,0.0)>=opening)continue;
                seen.put(next,opening);
                if(seen.size()>SteamLineNetwork.MAX_LINE_BLOCKS)return List.of();
                if(s.getBlock() instanceof PowerModuleBlock other) {
                    if(other.highPressure()==block.highPressure() && !other.generator() && other.portAt(s,d.getOpposite())==AssemblyPort.STEAM_INLET
                            && other.controller(level,next,s) instanceof PowerModuleBlockEntity peer && other.complete(level,peer.getBlockPos(),s))
                        consumers.put(peer.getBlockPos(),peer);
                    if(!block.highPressure() && other.highPressure() && other.portAt(s,d.getOpposite())==AssemblyPort.STEAM_EXHAUST
                            && other.controller(level,next,s) instanceof PowerModuleBlockEntity hp && other.complete(level,hp.getBlockPos(),s))
                        sources.put(hp.getBlockPos(),new Source(hp,opening,0,valves));
                } else if(block.highPressure() && be instanceof RpvSteamOutletBlockEntity nozzle) {
                    if(nozzle.isPartOfFormedReactor() && nozzle.getPosition()>0)sources.put(next,new Source(nozzle,opening,0,valves));
                } else if(s.is(BwrBlocks.PRESSURISED_TUBE.get())||s.is(BwrBlocks.MSIV.get())||s.is(BwrBlocks.SAFETY_RELIEF_VALVE.get())||s.getBlock() instanceof TurbineValveBlock)queue.add(new Node(next,opening,valves));
            }
        }
        double demand=consumers.values().stream().mapToDouble(PowerModuleBlockEntity::headerDemandKg).sum();
        double share=demand>0?Math.min(1,module.headerDemandKg()/demand):0;
        return sources.values().stream().map(s->new Source(s.entity,s.opening,share,s.valves)).toList();
    }
}
