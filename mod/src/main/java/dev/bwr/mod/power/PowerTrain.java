package dev.bwr.mod.power;

import dev.bwr.core.turbine.PowerTurbine;
import dev.bwr.mod.eccs.EccsPower;
import net.minecraft.core.BlockPos;
import java.util.*;

/** Coaxial end-to-end modules share work once per server tick. No block/chunk is loaded by a search. */
public final class PowerTrain {
    public static final int MAX_MODULES=32;
    private PowerTrain() {}
    public static List<PowerModuleBlockEntity> members(PowerModuleBlockEntity start) {
        var level=start.getLevel();Map<BlockPos,PowerModuleBlockEntity> found=new TreeMap<>();
        var queue=new ArrayDeque<PowerModuleBlockEntity>();queue.add(start);
        while(!queue.isEmpty()) {
            var m=queue.remove();if(found.containsKey(m.getBlockPos()))continue;
            if(!m.block().complete(level,m.getBlockPos(),m.getBlockState()))return List.of();
            found.put(m.getBlockPos(),m);if(found.size()>MAX_MODULES)return List.of();
            for(boolean front:new boolean[]{true,false}) {
                var shaft=m.block().shaft(m.getBlockPos(),m.getBlockState(),front);
                var face=m.block().shaftFace(m.getBlockState(),front);var next=shaft.relative(face);
                if(!level.isLoaded(next))continue;
                var s=level.getBlockState(next);
                if(!(s.getBlock() instanceof PowerModuleBlock b))continue;
                var root=b.origin(next,s);
                for(boolean end:new boolean[]{true,false})
                    if(b.shaftFace(s,end)==face.getOpposite() && b.shaft(root,s,end).equals(next)
                            && b.controller(level,next,s) instanceof PowerModuleBlockEntity other)queue.add(other);
            }
        }
        return List.copyOf(found.values());
    }
    public static void tick(PowerModuleBlockEntity start) {
        long now=start.getLevel().getGameTime();if(start.lastTick==now)return;
        var train=members(start);
        if(train.isEmpty()){start.lastTick=now;start.hasWorkLoad=false;start.resetReadouts();start.status="Incomplete or over 32 modules";return;}
        // If a train was joined after one half already ran this tick, only unprocessed modules can act.
        var active=train.stream().filter(m->m.lastTick!=now).toList();
        var generators=active.stream().filter(m->m.block().generator()).toList();
        double budget=generators.stream().mapToDouble(PowerModuleBlockEntity::workRoomKJ).sum();
        double rpm=train.stream().mapToDouble(m->m.rpm).max().orElse(0);
        int hp=(int)train.stream().filter(m->m.block().highPressure()).count();
        int lp=(int)train.stream().filter(m->m.block().kind()==dev.bwr.mod.eccs.PumpAssemblyBlock.Kind.LP_TURBINE).count();
        for(var m:active){m.lastTick=now;m.hasWorkLoad=budget>0;m.resetReadouts();m.rpm=rpm;m.hpCount=hp;m.lpCount=lp;m.generatorCount=(int)train.stream().filter(n->n.block().generator()).count();m.status=budget>0?"Ready":"No generator load / buffer full";}
        double work=0;
        // HP output can feed LP immediately, independent of block-entity insertion order.
        for(boolean high:new boolean[]{true,false})for(var m:active)if(!m.block().generator()&&m.block().highPressure()==high)
            work+=m.expand(Math.max(0,budget-work));
        double remaining=work;
        for(var g:generators){double share=Math.min(remaining,g.workRoomKJ());g.generate(share);remaining-=share;}
        rpm+=(work>0?1500-rpm:-rpm)*(1-Math.exp(-.05/8));
        for(var m:active){m.rpm=rpm;m.trainMW=work*20/1000;m.pushOutputs();m.setChanged();}
    }
}
