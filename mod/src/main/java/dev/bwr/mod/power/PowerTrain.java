package dev.bwr.mod.power;

import dev.bwr.core.turbine.ShaftDynamics;
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
        // Each module owns only its share of rotor energy. Never clone the fastest
        // rotor's energy into a newly attached stationary train.
        double inertia=active.stream().mapToDouble(PowerModuleBlockEntity::shaftInertia).sum();
        var coupling=ShaftDynamics.couple(active.stream().mapToDouble(PowerModuleBlockEntity::shaftInertia).toArray(),
                active.stream().mapToDouble(m->m.rpm).toArray());
        double rpm=coupling.rpm();
        int hp=(int)train.stream().filter(m->m.block().highPressure()).count();
        int lp=(int)train.stream().filter(m->m.block().kind()==dev.bwr.mod.eccs.PumpAssemblyBlock.Kind.LP_TURBINE).count();
        boolean connectedGenerator=train.stream().anyMatch(m->m.block().generator());
        for(var m:active){m.lastTick=now;m.hasWorkLoad=connectedGenerator;m.resetReadouts();m.rpm=rpm;m.hpCount=hp;m.lpCount=lp;m.generatorCount=(int)train.stream().filter(n->n.block().generator()).count();m.status=connectedGenerator?"Ready":"No generator attached";}
        double work=0;
        // HP output can feed LP immediately, independent of block-entity insertion order.
        for(boolean high:new boolean[]{true,false})for(var m:active)if(!m.block().generator()&&m.block().highPressure()==high)
            if(connectedGenerator)work+=m.expand(Double.MAX_VALUE);
        double request=generators.stream().mapToDouble(PowerModuleBlockEntity::workRoomKJ).sum();
        var step=ShaftDynamics.step(inertia,rpm,work,request,.05);
        double remaining=step.generatorWorkKJ();
        for(var g:generators){double share=Math.min(remaining,g.workRoomKJ());g.generate(share);remaining-=share;}
        for(var m:active){m.rpm=step.rpm();m.trainMW=work*20/1000;
            m.trainLossKJ=step.lossKJ()+coupling.lossKJ();
            m.addShaftDamage(ShaftDynamics.damage(m.damage(),m.rpm,.05)-m.damage());}
        // A mid-tick connection can include a half already processed. Couple it
        // once after the new half's step; never repeat steam or electrical work.
        if(active.size()!=train.size()) {
            var joined=ShaftDynamics.couple(train.stream().mapToDouble(PowerModuleBlockEntity::shaftInertia).toArray(),train.stream().mapToDouble(m->m.rpm).toArray());
            for(var m:train){m.rpm=joined.rpm();m.trainLossKJ+=joined.lossKJ();m.setChanged();}
        }
        for(var m:active){m.pushOutputs();m.setChanged();}
    }
}
