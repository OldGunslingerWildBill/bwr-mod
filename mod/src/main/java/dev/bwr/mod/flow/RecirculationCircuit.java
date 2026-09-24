package dev.bwr.mod.flow;

import dev.bwr.mod.eccs.AssemblyPort;
import dev.bwr.mod.reactor.*;
import dev.bwr.mod.registry.BwrBlocks;
import net.minecraft.core.*;
import net.minecraft.world.level.Level;
import java.util.*;

/** An external closed water loop. Suction and return must reach the same formed vessel. */
public final class RecirculationCircuit {
    private RecirculationCircuit(){}
    public static ReactorControllerBlockEntity controller(Level level,BlockPos pump){
        if(!level.isLoaded(pump) || !(level.getBlockState(pump).getBlock() instanceof RecirculationPumpBlock block))return null;
        var state=level.getBlockState(pump);
        BlockPos root=block.origin(pump,state);
        if(!block.complete(level,root,state) || !(level.getBlockEntity(root) instanceof RecirculationPumpBlockEntity))return null;
        var source=trace(level,block.portPosition(root,state,AssemblyPort.WATER_SUCTION),block.portFace(state,AssemblyPort.WATER_SUCTION),true);
        var destination=trace(level,block.portPosition(root,state,AssemblyPort.WATER_DISCHARGE),block.portFace(state,AssemblyPort.WATER_DISCHARGE),false);
        return source!=null && source==destination?source:null;
    }
    private static ReactorControllerBlockEntity trace(Level level,BlockPos root,Direction direction,boolean suction){
        var graph=dev.bwr.mod.piping.PipeTopology.get(level,root,direction,false,false);
        if(graph.truncated())return null;
        ReactorControllerBlockEntity found=null;
        for(var end:graph.ends()) {
            var next=end.pos();if(!level.isLoaded(next))return null;var s=level.getBlockState(next);
            if(s.getBlock() instanceof RecirculationPortBlock port&&s.getValue(RpvWaterInjectionPortBlock.FACING)==end.face()) {
                if(port.isOutlet()!=suction||!(level.getBlockEntity(next) instanceof RpvWaterInjectionPortBlockEntity nozzle))return null;
                var owner=nozzle.controller();if(owner==null||found!=null&&found!=owner)return null;found=owner;
            } else if(s.getBlock() instanceof RecirculationPumpBlock pump) {
                var role=pump.waterPortAt(s,end.face());if(role!=null&&role!=(suction?AssemblyPort.WATER_SUCTION:AssemblyPort.WATER_DISCHARGE))return null;
            } else return null;
        }
        return found;
    }
}
