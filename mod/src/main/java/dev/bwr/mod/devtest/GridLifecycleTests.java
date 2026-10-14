package dev.bwr.mod.devtest;

import dev.bwr.mod.grid.*;
import dev.bwr.mod.power.*;
import dev.bwr.mod.registry.BwrBlocks;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.*;

@GameTestHolder("bwr")
@PrefixGameTestTemplate(false)
public final class GridLifecycleTests {
    @GameTest(template="empty",timeoutTicks=200)
    public static void detachedEndpointsFrozenClockAndItemState(GameTestHelper h){
        var l=h.getLevel();var root=new BlockPos(99016,245,99016);var min=root.offset(-8,-2,-8);var max=root.offset(72,9,8);
        EntityLookupProbe.runWhenLive(h,new AABB(min.getX(),min.getY(),min.getZ(),max.getX(),max.getY(),max.getZ()),()->{
            long time=l.getGameTime();
            try{
                for(var p:BlockPos.betweenClosed(min,max))l.setBlock(p,Blocks.AIR.defaultBlockState(),3);
                var gen=PowerModuleRuntimeCheck.place(l,BwrBlocks.NUCLEAR_GENERATOR.get(),root,Direction.NORTH);
                var legacy=gen.saveWithoutMetadata(l.registryAccess());legacy.remove("DeviceId");legacy.remove("GridVersion");legacy.remove("DeliveredFE");legacy.putDouble("Energy",10000);gen.loadWithComponents(legacy,l.registryAccess());
                h.assertTrue(gen.damage()==0&&gen.delivered().totalFE()==0&&gen.energyStored()==10000,"legacy generator load changed inventory or condition");
                var tp=root.east(32);var mp=root.east(64);l.setBlock(tp,BwrBlocks.GRID_TIE.get().defaultBlockState(),3);l.setBlock(mp,BwrBlocks.FE_METER.get().defaultBlockState(),3);
                var tie=(GridTieBlockEntity)l.getBlockEntity(tp);var meter=(FeMeterBlockEntity)l.getBlockEntity(mp);
                tie.bindGenerator(root);tie.setMeters(java.util.List.of(mp));tie.configure(new dev.bwr.core.grid.GridDemand(1,0,0,1,2,200));
                for(int i=1;i<=10;i++){((net.minecraft.world.level.storage.ServerLevelData)l.getLevelData()).setGameTime(time+i);tie.tickDevice();meter.tickDevice();}
                long phase=tie.curvePhase();double angle=tie.phase();var tag=tie.saveWithFullMetadata(l.registryAccess());var state=tie.getBlockState();var oldCap=tie.energy;
                tie.onChunkUnloaded();l.removeBlockEntity(tp);((net.minecraft.world.level.storage.ServerLevelData)l.getLevelData()).setGameTime(time+24000);
                h.assertTrue(oldCap.receiveEnergy(100,false)==0,"detached tie consumed FE");
                var loaded=(GridTieBlockEntity)BlockEntity.loadStatic(tp,state,tag,l.registryAccess());l.setBlockEntity(loaded);loaded.onLoad();loaded.tickDevice();
                h.assertTrue(loaded.curvePhase()==phase&&loaded.phase()==angle&&loaded.ledger().sampleTicks()==0,"offline phase/curve catch-up or stale history");
                loaded.closeBreaker();((net.minecraft.world.level.storage.ServerLevelData)l.getLevelData()).setGameTime(time+24001);loaded.tickDevice();
                int transfer=gen.energy.extractEnergy(100,false);h.assertTrue(loaded.energy.receiveEnergy(transfer,false)==transfer,"reloaded tie could not consume real FE");
                var genState=gen.getBlockState();var genTag=gen.saveWithFullMetadata(l.registryAccess());gen.onChunkUnloaded();l.removeBlockEntity(root);
                h.assertTrue(loaded.closed()&&loaded.generator()==null&&loaded.energy.receiveEnergy(1,false)==0,"absent generator changed breaker or served binding");
                var newGen=(PowerModuleBlockEntity)BlockEntity.loadStatic(root,genState,genTag,l.registryAccess());l.setBlockEntity(newGen);
                h.assertTrue(loaded.generator()==newGen&&loaded.closed()&&newGen.energyStored()==9900,"same source identity did not reconnect exactly");
                var meterTag=meter.saveWithFullMetadata(l.registryAccess());var meterState=meter.getBlockState();var meterId=meter.deviceId();meter.onChunkUnloaded();l.removeBlockEntity(mp);
                h.assertTrue(loaded.meter(loaded.meters().getFirst())==null,"absent meter reported live");
                var newMeter=(FeMeterBlockEntity)BlockEntity.loadStatic(mp,meterState,meterTag,l.registryAccess());l.setBlockEntity(newMeter);
                h.assertTrue(newMeter.deviceId().equals(meterId)&&loaded.meter(loaded.meters().getFirst())==newMeter&&!loaded.aggregateValid(),"meter identity/warmup reconnect failed");
                long total=loaded.ledger().totalFE();var id=loaded.deviceId();var item=Block.getDrops(state,l,tp,loaded).getFirst();
                l.removeBlock(tp,false);l.setBlock(tp,state,3);state.getBlock().setPlacedBy(l,tp,state,null,item);
                var placed=(GridTieBlockEntity)l.getBlockEntity(tp);
                h.assertTrue(!placed.deviceId().equals(id)&&!placed.closed()&&placed.binding()==null&&placed.meters().isEmpty()&&placed.ledger().totalFE()==total,"tie item retained world operations or lost totals");
                var malformed=placed.saveWithoutMetadata(l.registryAccess());malformed.putDouble("FixedMW",Double.NaN);malformed.putDouble("Phase",Double.POSITIVE_INFINITY);malformed.putLong("Period",-3);placed.loadWithComponents(malformed,l.registryAccess());
                h.assertTrue(Double.isFinite(placed.settings().fixedMW())&&Double.isFinite(placed.phase())&&placed.settings().periodTicks()>=20,"malformed numeric save escaped validation");
                h.succeed();
            }finally{for(var p:BlockPos.betweenClosed(min,max))l.setBlock(p,Blocks.AIR.defaultBlockState(),3);((net.minecraft.world.level.storage.ServerLevelData)l.getLevelData()).setGameTime(time);}
        });
    }
}
