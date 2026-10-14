package dev.bwr.mod.devtest;

import dev.bwr.core.grid.GridDemand;
import dev.bwr.mod.eccs.AssemblyPort;
import dev.bwr.mod.grid.GridTieBlockEntity;
import dev.bwr.mod.power.*;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.steam.*;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.*;

@GameTestHolder("bwr")
@PrefixGameTestTemplate(false)
public final class GridTieTests {
    private static final BlockPos ROOT=new BlockPos(91000,245,91000);
    @GameTest(template="empty",timeoutTicks=200)
    public static void sharedAllowanceAndPureSimulation(GameTestHelper h) {
        var l=h.getLevel();var root=ROOT.east(128);var min=root.offset(-8,-2,-8);var max=root.offset(16,9,12);
        EntityLookupProbe.runWhenLive(h,new AABB(min.getX(),min.getY(),min.getZ(),max.getX(),max.getY(),max.getZ()),()->{
            long time=l.getGameTime();double config=dev.bwr.mod.grid.GridConfig.maximumMW();
            try {
                for(var p:BlockPos.betweenClosed(min,max))l.setBlock(p,Blocks.AIR.defaultBlockState(),3);
                dev.bwr.mod.grid.GridConfig.MAXIMUM_MW.set(48000.0);
                var gen=PowerModuleRuntimeCheck.place(l,BwrBlocks.NUCLEAR_GENERATOR.get(),root,Direction.EAST);
                var pos=root.offset(12,0,0);l.setBlock(pos,BwrBlocks.GRID_TIE.get().defaultBlockState(),3);
                var tie=(GridTieBlockEntity)l.getBlockEntity(pos);
                h.assertTrue(tie.bindGenerator(root)&&tie.configure(new GridDemand(0,48000,0,0,0,20))&&tie.closeBreaker(),"tie setup failed");
                // Deliberately close a stationary generator: the consequence is damage, not refusal.
                h.assertTrue(tie.lastCloseFault()&&gen.failed(),"stationary close was protected");
                ((net.minecraft.world.level.storage.ServerLevelData)l.getLevelData()).setGameTime(time+1);
                var before=tie.saveWithoutMetadata(l.registryAccess());
                for(int i=0;i<30;i++)h.assertTrue(tie.energy.receiveEnergy(Integer.MAX_VALUE,true)==Integer.MAX_VALUE,"simulation spent allowance");
                h.assertTrue(before.equals(tie.saveWithoutMetadata(l.registryAccess())),"simulation mutated saved state");
                long total=0;
                for(var side:Direction.values())total+=l.getCapability(net.neoforged.neoforge.capabilities.Capabilities.EnergyStorage.BLOCK,pos,side).receiveEnergy(Integer.MAX_VALUE,false);
                long expected=(long)Math.floor(dev.bwr.core.grid.EnergyUnits.fePerTick(48000));
                h.assertTrue(total==expected&&total>Integer.MAX_VALUE&&tie.ledger().totalFE()==total,"faces did not share a long allowance");
                h.assertTrue(tie.configure(new GridDemand(0,1,0,0,0,20))&&tie.energy.receiveEnergy(10,false)==0,"configuration reset spent allowance");
                ((net.minecraft.world.level.storage.ServerLevelData)l.getLevelData()).setGameTime(time+2);tie.tickDevice();
                h.assertTrue(tie.demandMW()==1&&tie.energy.receiveEnergy(Integer.MAX_VALUE,false)<100000,"next tick ignored configuration");
                var saved=tie.saveWithoutMetadata(l.registryAccess());var id=tie.deviceId();var lifetime=tie.ledger().totalFE();
                tie.loadWithComponents(saved,l.registryAccess());
                h.assertTrue(tie.deviceId().equals(id)&&tie.closed()&&tie.ledger().totalFE()==lifetime&&tie.ledger().sampleTicks()==0,"reload lost durable state or restored rates");
                h.assertTrue(!saved.contains("Budget")&&!saved.contains("Tick"),"per-tick budget persisted");
                tie.tripBreaker();h.assertTrue(tie.energy.receiveEnergy(Integer.MAX_VALUE,false)==0,"open tie consumed");
                h.succeed();
            } finally {
                dev.bwr.mod.grid.GridConfig.MAXIMUM_MW.set(config);
                for(var p:BlockPos.betweenClosed(min,max))l.setBlock(p,Blocks.AIR.defaultBlockState(),3);
                ((net.minecraft.world.level.storage.ServerLevelData)l.getLevelData()).setGameTime(time);
            }
        });
    }
    @GameTest(template="empty",timeoutTicks=200)
    public static void realThrottleSynchronizationAndManualBadClose(GameTestHelper h) {
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
                var otherPos=ROOT.offset(14,0,8);l.setBlock(otherPos,BwrBlocks.GRID_TIE.get().defaultBlockState(),3);
                var other=(GridTieBlockEntity)l.getBlockEntity(otherPos);
                h.assertTrue(!tie.closed()&&tie.energy.receiveEnergy(10,false)==0,"default tie consumed FE");
                h.assertTrue(tie.bindGenerator(gen.getBlockPos())&&other.bindGenerator(gen.getBlockPos()),"multiple one-way ties refused");
                h.assertTrue(tie.configure(new GridDemand(0,50,0,0,50,24000)),"fixed demand refused");
                int closedAt=-1,tripAt=-1;double closeRPM=0;long delivered=0;
                for(int tick=0;tick<10000;tick++) {
                    ((net.minecraft.world.level.storage.ServerLevelData)l.getLevelData()).setGameTime(time+tick+1);
                    var tag=supply.saveWithoutMetadata(l.registryAccess());var steam=new CompoundTag();
                    steam.putDouble("Mass",400);steam.putDouble("Enthalpy",2490);steam.putDouble("Pressure",145);tag.put("Exhaust",steam);supply.loadWithComponents(tag,l.registryAccess());
                    condenser.plant().steam.take(condenser.plant().steam.mass());
                    // Explicit test-side operator actions through the real fractional valve.
                    // Production has no governor; neither RPM nor phase is ever written here.
                    double desired=1500.9,base=tie.closed()?.012:0;
                    valve.setTarget(gen.rpm<desired-5?.09:gen.rpm<desired?base+.002:Math.max(0,base-.002));valve.stroke(.05);
                    PowerTrain.tick(gen);tie.tickDevice();other.tickDevice();
                    if(closedAt<0&&gen.rpm>1499&&gen.rpm<1502&&tie.withinWindow()){
                        closeRPM=gen.rpm;h.assertTrue(tie.closeBreaker()&&!tie.lastCloseFault()&&gen.damage()==0,"real in-window close damaged generator");
                        h.assertTrue(!tie.bindGenerator(gen.getBlockPos()),"closed tie allowed rebind");closedAt=tick;
                    }
                    if(closedAt>=0&&tripAt<0&&tick>closedAt+80){
                        delivered=tie.ledger().totalFE();h.assertTrue(delivered>0,"real generator terminal delivered no FE");
                        h.assertTrue(tie.ledger().totalFE()==gen.delivered().totalFE(),"terminal and tie accounting disagree");
                        tie.tripBreaker();h.assertTrue(!tie.closed()&&tie.energy.receiveEnergy(1,false)==0,"manual trip failed");tripAt=tick;
                    }
                    if(tripAt>=0&&tick>tripAt+40&&Math.abs(other.phase())>45&&gen.rpm>1499&&gen.rpm<1502){
                        h.assertTrue(other.closeBreaker()&&other.closed()&&other.lastCloseFault()&&gen.damage()>0,"bad close was protected or harmless");
                        double damage=gen.damage();h.assertTrue(other.closeBreaker()&&gen.damage()==damage,"duplicate close repeated damage");
                        var saved=other.saveWithoutMetadata(l.registryAccess());other.loadWithComponents(saved,l.registryAccess());
                        h.assertTrue(other.closed()&&other.binding().id().equals(gen.deviceId())&&other.lastCloseFault(),"tie state did not round trip");
                        var oldId=gen.deviceId();l.removeBlock(gen.getBlockPos(),false);
                        h.assertTrue(other.generator()==null&&other.closed()&&other.energy.receiveEnergy(10,false)==0,"stale binding changed breaker or consumed FE");
                        var replacement=PowerModuleRuntimeCheck.place(l,BwrBlocks.NUCLEAR_GENERATOR.get(),ROOT.east(8),Direction.EAST);
                        h.assertTrue(!replacement.deviceId().equals(oldId)&&other.generator()==null,"replacement inherited generator identity");
                        other.tripBreaker();h.assertTrue(other.bindGenerator(replacement.getBlockPos()),"explicit open rebind failed");
                        com.mojang.logging.LogUtils.getLogger().info("GRID sync: close at {} s, {} rpm, angle={} slip={}; delivered={} FE; bad close angle={}",closedAt/20.0,closeRPM,tie.lastAngle(),tie.lastSlip(),delivered,saved.getDouble("LastAngle"));
                        h.succeed();return;
                    }
                }
                h.fail("operator did not complete real synchronization / bad-close sequence; rpm="+gen.rpm+" phase="+tie.phase()+" closedAt="+closedAt+" tripAt="+tripAt);
            } finally {
                for(var p:BlockPos.betweenClosed(min,max))l.setBlock(p,Blocks.AIR.defaultBlockState(),3);
                ((net.minecraft.world.level.storage.ServerLevelData)l.getLevelData()).setGameTime(time);
            }
        });
    }
}
