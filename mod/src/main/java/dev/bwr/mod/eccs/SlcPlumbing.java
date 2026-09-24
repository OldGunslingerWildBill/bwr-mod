package dev.bwr.mod.eccs;

import dev.bwr.mod.piping.PipeTopology;
import dev.bwr.mod.reactor.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/** Geometry is cached by PipeTopology. Only live endpoint inventories are resolved each transfer. */
public final class SlcPlumbing {
    public record Circuit(SlcTankBlockEntity tank,ReactorControllerBlockEntity reactor,String status){}
    public static Circuit resolve(Level level,BlockPos root,BlockState state){
        if(!(state.getBlock() instanceof PumpAssemblyBlock pump)||!pump.isFull(state)||!pump.complete(level,root,state))return new Circuit(null,null,"Replace legacy SLC pump to build its physical ports");
        var suction=PipeTopology.get(level,pump.portPosition(root,state,AssemblyPort.WATER_SUCTION),pump.portFace(state,AssemblyPort.WATER_SUCTION),false,false);
        var outlet=PipeTopology.get(level,pump.portPosition(root,state,AssemblyPort.WATER_DISCHARGE),pump.portFace(state,AssemblyPort.WATER_DISCHARGE),false,false);
        if(suction.truncated()||outlet.truncated())return new Circuit(null,null,"SLC line exceeds survey limit");
        SlcTankBlockEntity tank=null;ReactorControllerBlockEntity reactor=null;
        for(var end:suction.ends()){
            if(!level.isLoaded(end.pos()))return new Circuit(null,null,"SLC supply is unloaded");
            if(end.state().getBlock() instanceof SlcTankBlock b&&b.portAt(end.state(),end.face())==AssemblyPort.WATER_DISCHARGE){
                var owner=b.controller(level,end.pos(),end.state());
                if(!(owner instanceof SlcTankBlockEntity t)||!t.ready()||tank!=null&&tank!=t)return new Circuit(null,null,"One complete boron tank required");
                tank=t;
            }else if(!(end.state().getBlock() instanceof PumpAssemblyBlock b&&b.kind()==PumpAssemblyBlock.Kind.SLC&&b.portAt(end.state(),end.face())==AssemblyPort.WATER_SUCTION))return new Circuit(null,null,"Invalid SLC suction branch");
        }
        for(var end:outlet.ends()){
            if(!level.isLoaded(end.pos()))return new Circuit(null,null,"SLC recipient is unloaded");
            if(end.state().getBlock() instanceof RpvWaterInjectionPortBlock && !(end.state().getBlock() instanceof RecirculationPortBlock)
                    &&level.getBlockEntity(end.pos()) instanceof RpvWaterInjectionPortBlockEntity port){
                var c=port.controller();if(c==null||!c.isFormed()||reactor!=null&&reactor!=c)return new Circuit(null,null,"Line must feed one formed reactor");reactor=c;
            }else if(!(end.state().getBlock() instanceof ProcessAssembly b&&b.portAt(end.state(),end.face())==AssemblyPort.WATER_DISCHARGE))return new Circuit(null,null,"Invalid SLC discharge branch");
        }
        return new Circuit(tank,reactor,tank==null?"Connect a boron tank to suction":reactor==null?"Connect discharge to an RPV water inlet":"Boron circuit connected");
    }
    private SlcPlumbing(){}
}
