package dev.bwr.mod.steam;

import dev.bwr.mod.eccs.*;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.reactor.RpvSteamOutletBlockEntity;
import net.minecraft.core.*;
import net.minecraft.world.level.Level;
import java.util.*;

/** Loaded, bounded widest-path survey. A closed side branch cannot close an open header. */
public final class SteamValveRouting {
    private SteamValveRouting(){}
    private record Node(BlockPos pos,double opening){}
    private record PathNode(BlockPos pos,double opening,List<TurbineValveBlockEntity> valves){}
    /** Claim on one real path, also used by the Mekanism steam boundary. */
    public static double claim(Level level,BlockPos start,RpvSteamOutletBlockEntity nozzle,double wantedKgPerS){
        Map<BlockPos,Double> seen=new HashMap<>();ArrayDeque<PathNode> q=new ArrayDeque<>();
        q.add(new PathNode(start,1,List.of()));seen.put(start,1.0);PathNode route=null;
        while(!q.isEmpty()){
            var n=q.remove();var current=level.getBlockState(n.pos);
            for(Direction d:Direction.values()){
                var next=n.pos.relative(d);if(!level.isLoaded(next))continue;var s=level.getBlockState(next);
                if(!SteamLineNetwork.acceptsLineOn(current,d)||!SteamLineNetwork.acceptsLineOn(s,d.getOpposite()))continue;
                double opening=n.opening;List<TurbineValveBlockEntity> valves=n.valves;
                var be=level.getBlockEntity(next);
                if(be instanceof MainSteamIsolationValveBlockEntity v)opening=Math.min(opening,v.getPosition());
                if(be instanceof TurbineValveBlockEntity v){opening=Math.min(opening,v.position());var copy=new ArrayList<>(valves);copy.add(v);valves=List.copyOf(copy);}
                if(opening<=0||seen.getOrDefault(next,0.0)>=opening)continue;
                seen.put(next,opening);if(seen.size()>SteamLineNetwork.MAX_LINE_BLOCKS)return 0;
                var path=new PathNode(next,opening,valves);
                if(next.equals(nozzle.getBlockPos())){route=path;continue;}
                if(s.is(BwrBlocks.PRESSURISED_TUBE.get())||s.is(BwrBlocks.MSIV.get())||s.is(BwrBlocks.SAFETY_RELIEF_VALVE.get())||s.getBlock() instanceof TurbineValveBlock)q.add(path);
            }
        }
        if(route==null)return 0;
        double raw=nozzle.getLineOpenFraction()>0?nozzle.getLastFlowKgPerS()/nozzle.getLineOpenFraction()/20:0;
        double limit=Math.min(wantedKgPerS/20,raw*route.opening);
        for(var v:route.valves)limit=Math.min(limit,v.allowance(nozzle.getBlockPos(),raw));
        double kg=nozzle.claimFlowKgPerS(level.getGameTime(),limit*20)/20;
        for(var v:route.valves)v.record(nozzle.getBlockPos(),kg);
        return kg*20;
    }
    /** -1 preserves the legacy nozzle boundary on lines without a new valve. */
    public static double nozzleOpening(Level level,BlockPos start){
        if(level==null||!level.isLoaded(start))return -1;
        Map<BlockPos,Double> seen=new HashMap<>();ArrayDeque<Node> q=new ArrayDeque<>();
        seen.put(start,1.0);q.add(new Node(start,1));boolean controlled=false;double best=0;
        while(!q.isEmpty()){
            var n=q.remove();var current=level.getBlockState(n.pos);
            for(Direction d:Direction.values()){
                var next=n.pos.relative(d);if(!level.isLoaded(next))continue;
                var s=level.getBlockState(next);
                if(!SteamLineNetwork.acceptsLineOn(current,d)||!SteamLineNetwork.acceptsLineOn(s,d.getOpposite()))continue;
                double opening=n.opening;var be=level.getBlockEntity(next);
                if(be instanceof TurbineValveBlockEntity v){controlled=true;opening=Math.min(opening,v.position());}
                if(be instanceof MainSteamIsolationValveBlockEntity v)opening=Math.min(opening,v.getPosition());
                if(seen.getOrDefault(next,-1.0)>=opening)continue;
                seen.put(next,opening);if(seen.size()>SteamLineNetwork.MAX_LINE_BLOCKS)return controlled?0:-1;
                if(s.getBlock() instanceof ProcessAssembly a){
                    if(a.portAt(s,d.getOpposite())==AssemblyPort.STEAM_INLET)best=Math.max(best,opening);
                }else if(s.is(BwrBlocks.TURBINE_STEAM_OUTLET.get())||s.is(BwrBlocks.SUPPRESSION_POOL_QUENCHER.get()))best=Math.max(best,opening);
                else if(s.is(BwrBlocks.PRESSURISED_TUBE.get())||s.is(BwrBlocks.MSIV.get())||s.is(BwrBlocks.SAFETY_RELIEF_VALVE.get())||s.getBlock() instanceof TurbineValveBlock)
                    q.add(new Node(next,opening));
            }
        }
        return controlled?best:-1;
    }
}
