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
        Set<BlockPos> seen=new HashSet<>();ArrayDeque<BlockPos> queue=new ArrayDeque<>();
        seen.add(root);queue.add(root);ReactorControllerBlockEntity found=null;
        while(!queue.isEmpty()){
            BlockPos p=queue.remove();
            for(Direction face:Direction.values()){
                if(p.equals(root) && face!=direction)continue;
                BlockPos next=p.relative(face);if(!level.isLoaded(next) || seen.contains(next))continue;
                var s=level.getBlockState(next);
                if(s.is(BwrBlocks.HIGH_PRESSURE_WATER_PIPE.get())){
                    seen.add(next);if(seen.size()>256)return null;queue.add(next);
                } else if(s.getBlock() instanceof RecirculationPortBlock port && s.getValue(RpvWaterInjectionPortBlock.FACING)==face.getOpposite()){
                    seen.add(next);
                    if(port.isOutlet()!=suction || !(level.getBlockEntity(next) instanceof RpvWaterInjectionPortBlockEntity nozzle))return null;
                    var owner=nozzle.controller();if(owner==null || found!=null && found!=owner)return null;found=owner;
                } else if(s.getBlock() instanceof RecirculationPumpBlock pump){
                    var role=pump.waterPortAt(s,face.getOpposite());
                    if(role!=null && role!=(suction?AssemblyPort.WATER_SUCTION:AssemblyPort.WATER_DISCHARGE))return null;
                } else if(dev.bwr.mod.water.WaterLineNetwork.acceptsLineOn(s,face.getOpposite()))return null;
            }
        }
        return found;
    }
}
