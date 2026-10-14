package dev.bwr.mod.devtest;

import dev.bwr.mod.grid.*;
import dev.bwr.mod.power.*;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.signal.DeviceCommands;
import dev.bwr.mod.eccs.AssemblyPort;
import dev.bwr.mod.steam.*;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.*;

@GameTestHolder("bwr")
@PrefixGameTestTemplate(false)
public final class GridLoadScenarioTests {
    private static final BlockPos ROOT=new BlockPos(97000,245,97000);
    @GameTest(template="empty",timeoutTicks=200)
    public static void rejectionThrottleRecoveryAndBaseFeeder(GameTestHelper h){
        var l=h.getLevel();var min=ROOT.offset(-28,-8,-12);var max=ROOT.offset(20,9,12);
        EntityLookupProbe.runWhenLive(h,new AABB(min.getX(),min.getY(),min.getZ(),max.getX(),max.getY(),max.getZ()),()->{
            long time=l.getGameTime();
            try {
                for(var p:BlockPos.betweenClosed(min,max))l.setBlock(p,Blocks.AIR.defaultBlockState(),3);
                var supply=PowerModuleRuntimeCheck.place(l,BwrBlocks.HP_TURBINE.get(),ROOT.west(20),Direction.EAST);
                var lp=PowerModuleRuntimeCheck.place(l,BwrBlocks.LP_TURBINE.get(),ROOT,Direction.EAST);
                var gen=PowerModuleRuntimeCheck.place(l,BwrBlocks.NUCLEAR_GENERATOR.get(),ROOT.east(8),Direction.EAST);
                var condenser=PowerModuleRuntimeCheck.attachCondenser(lp);
                var a=supply.block().portPosition(supply.getBlockPos(),supply.getBlockState(),AssemblyPort.STEAM_EXHAUST).relative(supply.block().portFace(supply.getBlockState(),AssemblyPort.STEAM_EXHAUST));
                var b=lp.block().portPosition(lp.getBlockPos(),lp.getBlockState(),AssemblyPort.STEAM_INLET).relative(lp.block().portFace(lp.getBlockState(),AssemblyPort.STEAM_INLET));
                var ah=new BlockPos(a.getX(),ROOT.getY()+6,ROOT.getZ()-8);var bh=new BlockPos(b.getX(),ah.getY(),ah.getZ());
                PowerModuleRuntimeCheck.pipe(l,a,new BlockPos(a.getX(),ah.getY(),a.getZ()));PowerModuleRuntimeCheck.pipe(l,new BlockPos(a.getX(),ah.getY(),a.getZ()),ah);
                PowerModuleRuntimeCheck.pipe(l,ah,bh);PowerModuleRuntimeCheck.pipe(l,bh,new BlockPos(b.getX(),bh.getY(),b.getZ()));PowerModuleRuntimeCheck.pipe(l,new BlockPos(b.getX(),bh.getY(),b.getZ()),b);
                var vp=new BlockPos(ROOT.getX()-10,ah.getY(),ah.getZ());
                l.setBlock(vp,BwrBlocks.TURBINE_CONTROL_VALVE.get().defaultBlockState().setValue(TurbineValveBlock.FACING,Direction.EAST),3);
                var valve=(TurbineValveBlockEntity)l.getBlockEntity(vp);
                var tiePos=gen.block().terminal(gen.getBlockPos(),gen.getBlockState()).relative(gen.block().terminalFace(gen.getBlockState()));
                l.setBlock(tiePos,BwrBlocks.GRID_TIE.get().defaultBlockState(),3);
                var tie=(GridTieBlockEntity)l.getBlockEntity(tiePos);

                var sp=vp.west(2);l.setBlock(sp,BwrBlocks.STEAM_STOP_VALVE.get().defaultBlockState().setValue(TurbineValveBlock.FACING,Direction.EAST),3);
                var stop=(TurbineValveBlockEntity)l.getBlockEntity(sp);stop.setTarget(1);stop.stroke(1);
                h.assertTrue(tie.bindGenerator(gen.getBlockPos())&&DeviceCommands.grid(tie,"fixed_mw",50),"scenario tie setup failed");
                int stage=0,stageTicks=0;double integral=0,frozen=0,fullFlow=0,lowFlow=0,rejectRpm=0,peak=0;long baseFE=0;
                double sumFlow=0,sumMW=0;int samples=0;
                for(int tick=0;tick<20000;tick++){
                    ((net.minecraft.world.level.storage.ServerLevelData)l.getLevelData()).setGameTime(time+tick+1);
                    var tag=supply.saveWithoutMetadata(l.registryAccess());var steam=new CompoundTag();steam.putDouble("Mass",400);steam.putDouble("Enthalpy",2490);steam.putDouble("Pressure",145);tag.put("Exhaust",steam);supply.loadWithComponents(tag,l.registryAccess());
                    condenser.plant().steam.take(condenser.plant().steam.mass());
                    double load=stage==3?35:50,desired=stage==0?1500.9:1500;
                    if(stage==0){
                        valve.setTarget(gen.rpm<desired-5?.09:gen.rpm<desired?.002:0);
                    }else if(stage!=2&&stage<5){
                        double error=desired-gen.rpm;integral=Math.max(-.006,Math.min(.006,integral+error*.0000001));
                        double command=(load/.985+3.75)/4560+error*.00005+integral;
                        valve.setTarget(Math.max(0,Math.min(.09,command)));
                    }else if(stage==2||stage==5)valve.setTarget(frozen);
                    valve.stroke(.05);stop.stroke(.05);
                    // Explicit player feeder order: base is served before the controlled export.
                    // This is not a claim of priority in an arbitrary foreign cable network.
                    if(stage==5)baseFE+=gen.energy.extractEnergy((int)dev.bwr.core.grid.EnergyUnits.fePerTick(5),false);
                    PowerTrain.tick(gen);tie.tickDevice();stageTicks++;
                    // The physical stop valve takes half a second to close; include
                    // that still-admitted torque in the measured rejection peak.
                    if(stage==6&&stageTicks<=12)peak=Math.max(peak,gen.rpm);
                    if(stage==0&&tie.withinWindow()&&gen.rpm>1499&&gen.rpm<1502){
                        h.assertTrue(DeviceCommands.grid(tie,"close_breaker",1)&&!tie.lastCloseFault(),"scenario synchronization failed");stage=1;stageTicks=0;integral=0;
                    }else if(stage==1||stage==3||stage==4){
                        if(stageTicks>2200){sumFlow+=lp.flowKgPerS;sumMW+=dev.bwr.core.grid.EnergyUnits.mw(tie.ledger().lastTickFE());samples++;}
                        if(stageTicks==2400){
                            double mw=sumMW/samples,flow=sumFlow/samples;h.assertTrue(Math.abs(mw-load)<2.5&&Math.abs(gen.rpm-1500)<15,"controlled stage "+stage+" outside declared MW/RPM bands: mw="+mw+" rpm="+gen.rpm);
                            com.mojang.logging.LogUtils.getLogger().info("GRID LOAD stage={} MW={} steam={} kg/s rpm={} buffer={} FE loss={} MW valve={}",stage,mw,flow,gen.rpm,gen.energyStored(),gen.trainLossKJ*.02,valve.target());
                            if(stage==1){fullFlow=flow;rejectRpm=gen.rpm;frozen=valve.target();DeviceCommands.grid(tie,"fixed_mw",35);stage=2;}
                            else if(stage==3){lowFlow=flow;h.assertTrue(lowFlow<fullFlow*.85,"explicit throttle failed to reduce steam draw");DeviceCommands.grid(tie,"fixed_mw",50);stage=4;}
                            else {h.assertTrue(flow>lowFlow*1.15,"recovery failed to increase steam draw");frozen=valve.target();DeviceCommands.grid(tie,"trip_breaker",1);stage=5;}
                            stageTicks=0;sumFlow=sumMW=0;samples=0;integral=0;
                        }
                    }else if(stage==2&&stageTicks==200){
                        h.assertTrue(gen.rpm>rejectRpm+10&&Math.abs(dev.bwr.core.grid.EnergyUnits.mw(tie.ledger().lastTickFE())-35)<.01&&lp.flowKgPerS>fullFlow*.95,"fixed-valve rejection did not reduce delivery and accelerate: rpm="+gen.rpm);
                        com.mojang.logging.LogUtils.getLogger().info("GRID LOAD fixed valve rejection: {} -> {} rpm, steam={} kg/s",rejectRpm,gen.rpm,lp.flowKgPerS);
                        stage=3;stageTicks=0;integral=0;
                    }else if(stage==5&&gen.rpm>1700){
                        h.assertTrue(!tie.closed()&&tie.energy.receiveEnergy(1,false)==0&&baseFE>0&&gen.damage()>0,"manual trip/base delivery/overspeed consequence missing");
                        peak=gen.rpm;stop.setTarget(0);stage=6;stageTicks=0;
                    }else if(stage==6&&stageTicks==60){
                        h.assertTrue(lp.flowKgPerS==0&&gen.trainMW==0&&gen.rpm<peak&&!tie.closed(),"manual stop failed to remove steam torque: flow="+lp.flowKgPerS+" trainMW="+gen.trainMW+" rpm="+gen.rpm+" peak="+peak);
                        com.mojang.logging.LogUtils.getLogger().info("GRID LOAD trip: peak={} rpm base={} FE isolated={} rpm damage={}",peak,baseFE,gen.rpm,gen.damage());h.succeed();return;
                    }
                }
                h.fail("load scenario timed out stage="+stage+" rpm="+gen.rpm);
            }finally{for(var p:BlockPos.betweenClosed(min,max))l.setBlock(p,Blocks.AIR.defaultBlockState(),3);((net.minecraft.world.level.storage.ServerLevelData)l.getLevelData()).setGameTime(time);}
        });
    }
}
