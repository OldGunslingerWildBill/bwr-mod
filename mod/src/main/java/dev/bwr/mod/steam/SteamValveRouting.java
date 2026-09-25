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
        return transfer(level,start,nozzle,wantedKgPerS,false);
    }
    public static double claimWithoutRelief(Level level,BlockPos start,RpvSteamOutletBlockEntity nozzle,double wantedKgPerS){
        return transfer(level,start,null,nozzle,wantedKgPerS,false,true);
    }
    public static double claim(Level level,BlockPos start,Direction outlet,RpvSteamOutletBlockEntity nozzle,double wantedKgPerS){
        return transfer(level,start,outlet,nozzle,wantedKgPerS,false);
    }
    public static double available(Level level,BlockPos start,RpvSteamOutletBlockEntity nozzle){
        return transfer(level,start,nozzle,Double.MAX_VALUE,true);
    }
    private static double transfer(Level level,BlockPos start,RpvSteamOutletBlockEntity nozzle,double wantedKgPerS,boolean simulate){
        return transfer(level,start,null,nozzle,wantedKgPerS,simulate);
    }
    private static double transfer(Level level,BlockPos start,Direction outlet,RpvSteamOutletBlockEntity nozzle,double wantedKgPerS,boolean simulate){
        return transfer(level,start,outlet,nozzle,wantedKgPerS,simulate,false);
    }
    private static double transfer(Level level,BlockPos start,Direction outlet,RpvSteamOutletBlockEntity nozzle,double wantedKgPerS,boolean simulate,boolean excludeRelief){
        if(level==null||!level.isLoaded(start)||!level.isLoaded(nozzle.getBlockPos())||nozzle.isRemoved()||!(wantedKgPerS>0))return 0;
        var graph=dev.bwr.mod.piping.PipeTopology.get(level,start,outlet,true,false);
        PathNode route=null;
        for(var path:dev.bwr.mod.piping.PipeTopology.routes(level,graph,excludeRelief))if(path.node().kind()==dev.bwr.mod.piping.PipeTopology.Kind.END
                &&path.node().pos().equals(nozzle.getBlockPos())&&(route==null||path.opening()>route.opening))
            route=new PathNode(path.node().pos(),path.opening(),path.valves());
        if(route==null)return 0;
        double raw=nozzle.getLineOpenFraction()>0?nozzle.getLastFlowKgPerS()/nozzle.getLineOpenFraction()/20:0;
        double limit=Math.min(wantedKgPerS/20,raw*route.opening);
        for(var v:route.valves)limit=Math.min(limit,v.allowance(nozzle.getBlockPos(),raw));
        if(simulate)return Math.min(limit*20,nozzle.availableFlowKgPerS(level.getGameTime()));
        double kg=nozzle.claimFlowKgPerS(level.getGameTime(),limit*20)/20;
        for(var v:route.valves)v.record(nozzle.getBlockPos(),kg);
        return kg*20;
    }
    /** -1 preserves bare nozzle operation; an MSIV is also a physical admission control. */
    public static double nozzleOpening(Level level,BlockPos start){
        if(level==null||!level.isLoaded(start))return -1;
        var graph=dev.bwr.mod.piping.PipeTopology.get(level,start,null,true,false);
        boolean controlled=graph.edges().keySet().stream().anyMatch(n->n.state()!=null&&
                (n.state().is(BwrBlocks.MSIV.get())||n.state().getBlock() instanceof TurbineValveBlock||n.state().getBlock() instanceof AdsReliefValveBlock));
        if(graph.truncated())return 0;
        if(!controlled)return -1;
        double best=0;
        for(var route:dev.bwr.mod.piping.PipeTopology.routes(level,graph)) {
            var node=route.node();
            if(node.state()!=null&&node.state().getBlock() instanceof AdsReliefValveBlock
                    &&level.isLoaded(node.pos())&&level.getBlockEntity(node.pos()) instanceof SafetyReliefValveBlockEntity relief
                    &&relief.isOpen()&&relief.isDischargeSubmerged())best=Math.max(best,route.opening());
            if(node.kind()!=dev.bwr.mod.piping.PipeTopology.Kind.END)continue;
            var s=node.state();boolean sink;
            if(s.getBlock() instanceof ProcessAssembly a)sink=a.portAt(s,node.face())==AssemblyPort.STEAM_INLET;
            else if(s.getBlock() instanceof dev.bwr.mod.condenser.CondenserBlock)sink=dev.bwr.mod.condenser.CondenserBlockEntity.acceptsSteam(level,node.pos());
            else if(s.getBlock() instanceof dev.bwr.mod.suppression.SuppressionPoolSteamPortBlock){
                var pool=dev.bwr.mod.suppression.SuppressionPoolSteamPortBlock.owner(level,node.pos());
                sink=pool!=null&&pool.pool().inletSteam.space()>1e-6;
            }
            else sink=s.is(BwrBlocks.TURBINE_STEAM_OUTLET.get())||s.is(BwrBlocks.SUPPRESSION_POOL_QUENCHER.get());
            if(sink)best=Math.max(best,route.opening());
        }
        return best;
    }
}
