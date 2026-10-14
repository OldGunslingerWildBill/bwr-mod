package dev.bwr.mod.devtest;

import dev.bwr.mod.grid.*;
import dev.bwr.mod.power.*;
import dev.bwr.mod.registry.*;
import dev.bwr.mod.signal.*;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.*;
import net.neoforged.neoforge.gametest.*;
import java.util.*;

@GameTestHolder("bwr")
@PrefixGameTestTemplate(false)
public final class GridControlTests {
    private static final BlockPos ROOT=new BlockPos(95000,245,95000);
    @GameTest(template="empty",timeoutTicks=200)
    public static void linkerCommandsAndAlignedReport(GameTestHelper h){
        var l=h.getLevel();var min=ROOT.offset(-8,-2,-8);var max=ROOT.offset(20,9,16);
        EntityLookupProbe.runWhenLive(h,new AABB(min.getX(),min.getY(),min.getZ(),max.getX(),max.getY(),max.getZ()),()->{
            long time=l.getGameTime();
            try {
                for(var p:BlockPos.betweenClosed(min,max))l.setBlock(p,Blocks.AIR.defaultBlockState(),3);
                var gen=PowerModuleRuntimeCheck.place(l,BwrBlocks.NUCLEAR_GENERATOR.get(),ROOT,Direction.NORTH);
                var first=gen.block().terminal(ROOT,gen.getBlockState()).east();var tp=first.east();var basePos=ROOT.offset(8,1,8);
                l.setBlock(first,BwrBlocks.FE_METER.get().defaultBlockState().setValue(FeMeterBlock.FACING,Direction.EAST),3);
                l.setBlock(tp,BwrBlocks.GRID_TIE.get().defaultBlockState(),3);
                l.setBlock(basePos,BwrBlocks.FE_METER.get().defaultBlockState().setValue(FeMeterBlock.FACING,Direction.EAST),3);
                l.setBlock(basePos.east(),BwrBlocks.FUEL_FABRICATOR.get().defaultBlockState(),3);
                var tie=(GridTieBlockEntity)l.getBlockEntity(tp);var export=(FeMeterBlockEntity)l.getBlockEntity(first);var base=(FeMeterBlockEntity)l.getBlockEntity(basePos);
                var player=net.neoforged.neoforge.common.util.FakePlayerFactory.get(l,new com.mojang.authlib.GameProfile(UUID.fromString("efc231f1-34c0-4d5b-8028-5129b50b6fd9"),"GridLinkerTest"));
                var stack=new net.minecraft.world.item.ItemStack(BwrItems.PANEL_LINKER.get());player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,stack);player.getAbilities().mayBuild=true;player.setPos(tp.getX(),tp.getY(),tp.getZ());
                use(player,ROOT,true);use(player,tp,false);h.assertTrue(tie.generator()==gen,"CC-free linker failed generator binding");
                use(player,basePos,true);use(player,tp,false);h.assertTrue(tie.meters().size()==1&&tie.meters().getFirst().id().equals(base.deviceId()),"linker failed explicit meter selection");
                h.assertTrue(!tie.setMeters(List.of(basePos,basePos))&&tie.meters().size()==1,"duplicate selection partially mutated list");
                h.assertTrue(!tie.setMeters(List.of(ROOT.above(100)))&&tie.meters().size()==1,"invalid selection partially mutated list");
                var signals=SignalProviders.of(tie);h.assertTrue(signals!=null&&signals.command("fixed_mw",1)&&signals.command("close_breaker",1),"panel commands unavailable");
                h.assertTrue(!signals.command("trip_breaker",Double.POSITIVE_INFINITY)&&tie.closed()&&!signals.command("demand_mode",.5)&&!signals.command("fixed_mw",Double.NaN),"invalid panel input accepted");
                h.assertTrue(!SignalProviders.of(base).command("direction",6)&&SignalProviders.of(base).command("display_units",1),"meter command validation failed");
                h.assertTrue(!tie.aggregateValid()&&!GridTelemetry.tie(tie).containsKey("mwError"),"warming history masquerades as valid");
                var saved=gen.saveWithoutMetadata(l.registryAccess());saved.putDouble("Energy",2000000000);gen.loadWithComponents(saved,l.registryAccess());
                for(int tick=1;tick<=25;tick++){
                    ((net.minecraft.world.level.storage.ServerLevelData)l.getLevelData()).setGameTime(time+tick);
                    int draw=gen.energy.extractEnergy(1000,false);h.assertTrue(base.capability(Direction.WEST).receiveEnergy(draw,false)==draw,"base transfer rejected");
                    PowerTrain.tick(gen);tie.tickDevice();base.tickDevice();export.tickDevice();
                }
                double baseMW=1000*13.4/1e6;
                h.assertTrue(tie.aggregateValid()&&Math.abs(tie.observedBaseMW()-baseMW)<1e-12&&Math.abs(tie.mwError()-(1+baseMW))<1e-12,"time-aligned MW error incorrect: "+GridTelemetry.tie(tie));
                h.assertTrue(export.ledger().totalFE()==tie.ledger().totalFE(),"generator -> meter -> tie not conserved");
                h.assertTrue(tie.meters().size()==1&&!tie.meters().getFirst().id().equals(export.deviceId()),"unselected serial/export meter included");
                h.assertTrue(tie.setMeters(List.of())&&tie.aggregateValid()&&tie.observedBaseMW()==0&&tie.mwError()==1,"empty selection is not explicit zero base load");
                h.assertTrue(tie.setMeters(List.of(basePos)),"base selection restore failed");
                String dynamic="meter."+base.deviceId()+".";h.assertTrue(signals.read(dynamic+"valid").orElseThrow()==1&&Math.abs(signals.read(dynamic+"mw_1s").orElseThrow()-baseMW)<1e-12,"selected-meter signals wrong");
                if(net.neoforged.fml.ModList.get().isLoaded("computercraft"))cc(h,tie,base,signals);
                // Queries cannot publish a tick or advance the daily curve.
                var before=tie.saveWithoutMetadata(l.registryAccess());for(int i=0;i<20;i++){GridTelemetry.tie(tie);signals.read("phase_degrees");}
                h.assertTrue(before.equals(tie.saveWithoutMetadata(l.registryAccess())),"telemetry mutated curve or budget");
                tie.tripBreaker();((net.minecraft.world.level.storage.ServerLevelData)l.getLevelData()).setGameTime(time+26);tie.tickDevice();PowerTrain.tick(gen);base.tickDevice();export.tickDevice();
                h.assertTrue(tie.demandMW()==1&&!tie.exportEnabled(),"opening hid requested demand");
                var savedTie=tie.saveWithoutMetadata(l.registryAccess());tie.loadWithComponents(savedTie,l.registryAccess());
                h.assertTrue(tie.meters().size()==1&&!tie.aggregateValid(),"reload selection/warmup wrong");
                l.removeBlock(basePos,false);l.setBlock(basePos,BwrBlocks.FE_METER.get().defaultBlockState(),3);
                h.assertTrue(tie.meter(tie.meters().getFirst())==null&&!tie.aggregateValid()&&signals.read("mw_error").isEmpty(),"replacement reported stale aggregate");
                h.succeed();
            }finally{for(var p:BlockPos.betweenClosed(min,max))l.setBlock(p,Blocks.AIR.defaultBlockState(),3);((net.minecraft.world.level.storage.ServerLevelData)l.getLevelData()).setGameTime(time);}
        });
    }
    private static void cc(GameTestHelper h,GridTieBlockEntity tie,FeMeterBlockEntity meter,SignalProvider signals)throws Exception{
        var cc=new dev.bwr.mod.peripheral.GridTiePeripheral(tie);var mc=new dev.bwr.mod.peripheral.FeMeterPeripheral(meter);
        h.assertTrue(((Number)cc.getStatus().get("mwError")).doubleValue()==signals.read("mw_error").orElseThrow(),"CC/panel readout mismatch");
        h.assertTrue(!cc.setFixedDemandMW(Double.NaN)&&!cc.setDailyCurve(5,4,100)&&!cc.setDemandMode(3),"CC accepted invalid demand");
        h.assertTrue(cc.setRemoteDemandMW(2)&&signals.read("remote_mw").orElseThrow()==2,"CC/panel command mismatch");
        h.assertTrue(mc.setLabel("RHR A")&&!mc.setLabel("x".repeat(65))&&!mc.setDirection("sideways")&&!mc.setDisplayUnits("amps"),"meter CC validation failed");
        h.assertTrue(mc.getStatus().get("label").equals(SignalProviders.of(meter).readText("label").orElseThrow()),"meter CC/text parity failed");
        h.assertTrue(!cc.setMeters(Map.of(1,Map.of("x",Double.NaN,"y",1,"z",1)))&&tie.meters().size()==1,"CC list accepted invalid coordinates");
        var live=ReleaseCcChecks.computer(h.getLevel(),tie.getBlockPos());
        var status=(Map<?,?>)PeripheralTestCalls.call(live,"getStatus")[0];
        h.assertTrue(((Number)status.get("mwError")).doubleValue()==signals.read("mw_error").orElseThrow(),"real CC dispatch lost status");
        h.assertTrue(Boolean.TRUE.equals(PeripheralTestCalls.call(live,"setRemoteDemandMW",3.0)[0])&&signals.read("remote_mw").orElseThrow()==3,"real CC dispatch failed numeric command");
        h.assertTrue(Boolean.FALSE.equals(PeripheralTestCalls.call(live,"setDemandMode",.5)[0]),"real CC dispatch truncated fractional enum");
        var pos=meter.getBlockPos();
        h.assertTrue(Boolean.TRUE.equals(PeripheralTestCalls.call(live,"setMeters",Map.of(1.0,Map.of("x",(double)pos.getX(),"y",(double)pos.getY(),"z",(double)pos.getZ())))[0]),"real CC table dispatch failed");
    }
    private static void use(net.minecraft.server.level.ServerPlayer p,BlockPos pos,boolean shift){
        p.setShiftKeyDown(shift);var ctx=new net.minecraft.world.item.context.UseOnContext(p,net.minecraft.world.InteractionHand.MAIN_HAND,new BlockHitResult(Vec3.atCenterOf(pos),Direction.UP,pos,false));
        p.getMainHandItem().getItem().useOn(ctx);p.setShiftKeyDown(false);
    }
}
