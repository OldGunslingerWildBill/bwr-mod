package dev.bwr.mod.power;

import dev.bwr.core.turbine.ShaftDynamics;
import dev.bwr.core.turbine.TurbineAux;
import dev.bwr.core.turbine.TurbineBlades;
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
        long now=start.getLevel().getGameTime();if(start.lastTick==now)return;start.beginAccounting();
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
        for(var m:active){m.beginAccounting();m.lastTick=now;m.hasWorkLoad=connectedGenerator;m.resetReadouts();m.rpm=rpm;m.hpCount=hp;m.lpCount=lp;m.generatorCount=(int)train.stream().filter(n->n.block().generator()).count();m.status=connectedGenerator?"Ready":"No generator attached";}
        double work=0;
        // HP output can feed LP immediately, independent of block-entity insertion order.
        for(boolean high:new boolean[]{true,false})for(var m:active)if(!m.block().generator()&&m.block().highPressure()==high)
            if(connectedGenerator)work+=m.expand(Double.MAX_VALUE);
        double request=generators.stream().mapToDouble(PowerModuleBlockEntity::workRoomKJ).sum();
        // Reverse power (TURBINE-MAINTENANCE.md): a generator whose grid tie breaker is closed, turning within 1 Hz of synchronous
        // speed, is held in step by the grid. When the steam does less work than the train's windage and friction, the grid makes up
        // the rest: the generator runs as a motor, delivers nothing and draws that power. Nothing opens the breaker for the player.
        boolean held=connectedGenerator&&Math.abs(rpm-ShaftDynamics.RATED_RPM)<=TurbineBlades.SYNC_HOLD_RPM
                &&generators.stream().anyMatch(g->g.gridHeld(now));
        double lossNominal=ShaftDynamics.energyKJ(inertia,rpm)/ShaftDynamics.H_SECONDS*.005*.05;
        double motorKJ=held&&work<lossNominal?lossNominal-work:0;
        var step=motorKJ>0?ShaftDynamics.step(inertia,rpm,work+motorKJ,0,.05):ShaftDynamics.step(inertia,rpm,work,request,.05);
        double remaining=step.generatorWorkKJ();
        for(var g:generators){double share=Math.min(remaining,g.workRoomKJ());g.generate(share);remaining-=share;}
        double motored=lossNominal>0?Math.min(1,motorKJ/lossNominal):0;
        for(var m:active){m.rpm=step.rpm();m.trainMW=work*20/1000;
            m.trainLossKJ=step.lossKJ()+coupling.lossKJ();
            m.reverseMW=motorKJ*20/1000;m.motoring=motorKJ>0;
            m.addShaftDamage(ShaftDynamics.damage(m.shaftDamage(),m.rpm,.05)-m.shaftDamage());
            if(m.lp())blades(m,motored);}
        if(motorKJ>0)for(var g:generators)g.status=String.format(java.util.Locale.ROOT,"Motoring: the grid drives the shaft, %.1f MW reverse power",motorKJ*20/1000);
        TurbineAuxTrain.step(train,active,now);
        thrownAndShaking(train,active,now);
        // A mid-tick connection can include a half already processed. Couple it
        // once after the new half's step; never repeat steam or electrical work.
        if(active.size()!=train.size()) {
            var joined=ShaftDynamics.couple(train.stream().mapToDouble(PowerModuleBlockEntity::shaftInertia).toArray(),train.stream().mapToDouble(m->m.rpm).toArray());
            for(var m:train){m.rpm=joined.rpm();m.trainLossKJ+=joined.lossKJ();m.setChanged();}
        }
        for(var m:active){m.pushOutputs();m.setChanged();}
    }

    // ---- LP blade sections (TURBINE-MAINTENANCE.md)

    /** One LP's exhaust hood and blades this tick: windage heat while motored, overspeed load, and the damage from each. */
    private static void blades(PowerModuleBlockEntity m,double motored){
        double base=m.flowKgPerS>0&&m.outletC>0?m.outletC:TurbineAux.AMBIENT_C;
        double target=TurbineBlades.hoodTargetC(base,m.rpm,m.coverOff()?0:motored);
        m.hoodC=TurbineBlades.hoodStepC(m.hoodC,target,.05);
        m.addBladeDamageAll(TurbineBlades.windageDamage(m.hoodC,.05)+TurbineBlades.overspeedDamage(m.rpm,.05));
    }

    /** Failed blade sections let go while the shaft is fast, a disc bursts at 150 % speed; a thrown blade unbalances the whole train. */
    private static void thrownAndShaking(List<PowerModuleBlockEntity> train,List<PowerModuleBlockEntity> active,long now){
        var level=active.isEmpty()?null:active.get(0).getLevel();
        if(!(level instanceof net.minecraft.server.level.ServerLevel server))return;
        for(var m:active){
            if(!m.lp())continue;
            if(m.rpm>=TurbineBlades.BURST_RPM&&unburstSectionIn(m)){
                burst(server,m,m.worstFlow(),Double.NaN);continue;
            }
            for(int f=0;f<TurbineBlades.FLOWS;f++)
                if(m.bladeIn(f)&&!m.bladeThrown(f)&&m.blade(f)>=1&&m.rpm>TurbineBlades.MISSILE_MIN_RPM)throwBlade(server,m,f,Double.NaN);
        }
        int thrown=0;for(var m:train)thrown+=m.bladesThrownCount();
        double rpm=active.get(0).rpm;
        double imbalance=TurbineBlades.imbalanceMmPerS(thrown,rpm);
        if(imbalance>0)for(var m:active){
            m.vibration=Math.max(m.vibration,imbalance);
            m.addShaftDamage(TurbineAux.vibrationDamage(imbalance,TurbineAuxTrain.DT));
        }
        if(now%20==0&&!train.isEmpty()){
            double worst=0;PowerModuleBlockEntity at=train.get(0);double x=0,y=0,z=0;
            for(var m:train){
                double v=Math.max(m.vibration,Math.max(imbalance,m.block().generator()?TurbineAux.vibrationMmPerS(0,m.rpm):TurbineAux.vibrationMmPerS(m.bow(),m.rpm)));
                if(v>worst){worst=v;at=m;}
                x+=m.getBlockPos().getX()+.5;y+=m.getBlockPos().getY()+2.5;z+=m.getBlockPos().getZ()+.5;
            }
            if(rpm>1||worst>.1)TurbineEvents.post(new TurbineEvents.Vibration(server,at.getBlockPos(),
                    new net.minecraft.world.phys.Vec3(x/train.size(),y/train.size(),z/train.size()),worst,rpm,train.size()));
        }
    }

    /** Whether a blade section sits in the casing that no disc has burst from yet: a burst marks every installed section, so a section left out must not make the same disc burst every tick. */
    private static boolean unburstSectionIn(PowerModuleBlockEntity m){
        for(int f=0;f<TurbineBlades.FLOWS;f++)if(m.bladeIn(f)&&!m.bladeThrown(f))return true;
        return false;
    }

    /** A failed section throws one of its last-stage blades at the shaft's speed. {@code angle} NaN picks one. */
    public static TurbineMissile.Result throwBlade(net.minecraft.server.level.ServerLevel server,PowerModuleBlockEntity m,int flow,double angle){
        m.markThrown(flow);
        m.status="A blade was thrown from LP flow "+(flow==0?"A":"B");
        return TurbineMissile.launch(server,m,flow,TurbineBlades.Missile.BLADE,m.rpm,angle);
    }

    /** A last-stage disc bursts (150 % speed, or a close far out of phase): a disc fragment flies, both sections and the shaft are wrecked. */
    public static TurbineMissile.Result burst(net.minecraft.server.level.ServerLevel server,PowerModuleBlockEntity m,int flow,double angle){
        for(int f=0;f<TurbineBlades.FLOWS;f++)if(m.bladeIn(f))m.markThrown(f);
        m.addShaftDamage(1);
        m.status="An LP disc burst: the section is wrecked";
        return TurbineMissile.launch(server,m,flow,TurbineBlades.Missile.DISC,m.rpm,angle);
    }

    /**
     * Closing the generator breaker out of phase: the torque transient goes through the whole shaft train. The generator's own damage
     * is the grid tie's existing law (the tie applies it); this adds the turbine sections' share: shaft and couplings on every
     * turbine section, the blade sections on every LP, and past {@link TurbineBlades#SHOCK_BURST_PU} with the shaft fast an LP disc
     * lets go. Loaded sections only. Returns the transient, per unit.
     */
    public static double closingShock(PowerModuleBlockEntity generator,double angle,double slip){
        double pu=TurbineBlades.closingTorquePu(angle,slip);
        var level=generator.getLevel();
        for(var m:members(generator)){
            m.recordClose(angle,pu);
            if(m.block().generator())continue;
            m.addShaftDamage(TurbineBlades.closingShaftDamage(pu));
            if(!m.lp())continue;
            m.addBladeDamageAll(TurbineBlades.closingBladeDamage(pu));
            if(pu>=TurbineBlades.SHOCK_BURST_PU&&m.rpm>TurbineBlades.MISSILE_MIN_RPM&&m.worstFlow()>=0&&level instanceof net.minecraft.server.level.ServerLevel server)
                burst(server,m,m.worstFlow(),Double.NaN);
        }
        return pu;
    }
}
