package dev.bwr.mod.devtest;

import dev.bwr.mod.eccs.*;
import dev.bwr.mod.feedwater.FeedwaterPumpBlockEntity;
import dev.bwr.mod.flow.*;
import dev.bwr.mod.reactor.*;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.steam.*;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.*;

/** GitHub #16/#18 regressions. The extended phase-one scan test also covers #17. */
@GameTestHolder("bwr") @PrefixGameTestTemplate(false)
public final class PhaseTwoRegressionTests {
    private static void load(ServerLevel level, BlockPos root) {
        for (int x=(root.getX()-64)>>4; x<=(root.getX()+64)>>4; x++)
            for (int z=(root.getZ()-64)>>4; z<=(root.getZ()+64)>>4; z++) level.getChunk(x,z);
    }

    private static BlockState place(ServerLevel level, PumpAssemblyBlock block, BlockPos root) {
        load(level,root); level.removeBlock(root,false);
        BlockState state=block.placementState();level.setBlock(root,state,2);
        block.setPlacedBy(level,root,state,null,new ItemStack(block));return state;
    }

    @GameTest(template="empty",timeoutTicks=400)
    public static void cachedWaterRoutes(GameTestHelper h) throws Exception {
        var level=h.getLevel();int index=0;
        for (var block:java.util.List.of(BwrBlocks.HPCS_PUMP.get(),BwrBlocks.MOTOR_FEED_PUMP.get(),BwrBlocks.TURBINE_FEED_PUMP.get())) {
            var root=new BlockPos(3300+80*index++,180,3300);var state=place(level,block,root);
            var role=AssemblyPort.WATER_SUCTION;var port=block.portPosition(root,state,role);var face=block.portFace(state,role);
            // GameTest worlds survive local reruns; remove the tee from an earlier run.
            var branch=port.relative(face,4).relative(face.getAxis()==Direction.Axis.X?Direction.NORTH:Direction.EAST);
            level.removeBlock(branch,false);
            for(int i=1;i<=32;i++)level.setBlock(port.relative(face,i),BwrBlocks.HIGH_PRESSURE_WATER_PIPE.get().defaultBlockState(),2);
            var tank=port.relative(face,33);level.removeBlock(tank,false);
            level.setBlock(tank,BwrBlocks.CONDENSATE_STORAGE_TANK.get().defaultBlockState(),2);
            var owner=level.getBlockEntity(root);var field=owner.getClass().getDeclaredField("plumbing");field.setAccessible(true);
            var cache=(AssemblyPlumbing.Cache)field.get(owner);
            var first=cache.trace(level,root,state,role);
            h.assertTrue(first.ends().equals(java.util.List.of(tank)),"water fixture has missing or leftover endpoints: "+first.ends());
            long initial=cache.rebuildCount();
            for(int i=0;i<100;i++) {
                if(owner instanceof EccsPumpBlockEntity pump)EccsPumpBlockEntity.serverTick(level,root,state,pump);
                else FeedwaterPumpBlockEntity.serverTick(level,root,state,(FeedwaterPumpBlockEntity)owner);
            }
            h.assertTrue(cache.rebuildCount()-initial<=3,"machine still retraces its stable ports on every tick");
            benchmark(h,level,root,state,role,cache,block.kind().name());
            // A cached route must still resolve a replacement inventory, not retain its old owner.
            var oldTank=level.getBlockEntity(tank);var tankState=level.getBlockState(tank);
            var savedTank=oldTank.saveWithFullMetadata(level.registryAccess());level.removeBlockEntity(tank);
            var replacement=net.minecraft.world.level.block.entity.BlockEntity.loadStatic(tank,tankState,savedTank,level.registryAccess());
            level.setBlockEntity(replacement);
            h.assertTrue(AssemblyPlumbing.endpoint(level,cache.trace(level,root,state,role),CondensateStorageTankBlockEntity.class)==replacement,
                    "cached route retained an obsolete inventory");
            var cut=port.relative(face,16);level.removeBlock(cut,false);
            h.assertTrue(!cache.trace(level,root,state,role).ends().contains(tank),"broken cached pipe still reaches source");
            level.setBlock(cut,BwrBlocks.HIGH_PRESSURE_WATER_PIPE.get().defaultBlockState(),2);
            long time=level.getGameTime();var data=(net.minecraft.world.level.storage.ServerLevelData)level.getLevelData();
            try {
                data.setGameTime(time+500);
                h.assertTrue(cache.trace(level,root,state,role).ends().contains(tank),"repaired pipe not discovered by deadline");
                level.setBlock(branch,BwrBlocks.CONDENSATE_STORAGE_TANK.get().defaultBlockState(),2);
                data.setGameTime(time+2*500);
                h.assertTrue(cache.trace(level,root,state,role).ends().contains(branch),"new tee not discovered by deadline");
            } finally {data.setGameTime(time);}
            level.removeBlock(root,false);
            level.removeBlock(branch,false);level.removeBlock(tank,false);
            for(int i=1;i<=32;i++)level.removeBlock(port.relative(face,i),false);
        }
        h.succeed();
    }

    private static void benchmark(GameTestHelper h,ServerLevel level,BlockPos root,BlockState state,
                                  AssemblyPort role,AssemblyPlumbing.Cache cache,String label) {
        var rawBean=java.lang.management.ManagementFactory.getThreadMXBean();
        h.assertTrue(rawBean instanceof com.sun.management.ThreadMXBean,"allocation counter unavailable on this JDK");
        var bean=(com.sun.management.ThreadMXBean)rawBean;
        h.assertTrue(bean.isThreadAllocatedMemorySupported(),"allocation tracking unsupported");
        bean.setThreadAllocatedMemoryEnabled(true);
        for(int i=0;i<50;i++){AssemblyPlumbing.trace(level,root,state,role);cache.trace(level,root,state,role);}
        long thread=Thread.currentThread().threadId();int runs=200;
        long bytes=bean.getThreadAllocatedBytes(thread),start=System.nanoTime();
        for(int i=0;i<runs;i++)AssemblyPlumbing.trace(level,root,state,role);
        long rawNs=System.nanoTime()-start,rawBytes=bean.getThreadAllocatedBytes(thread)-bytes;
        bytes=bean.getThreadAllocatedBytes(thread);start=System.nanoTime();
        for(int i=0;i<runs;i++)cache.trace(level,root,state,role);
        long cachedNs=System.nanoTime()-start,cachedBytes=bean.getThreadAllocatedBytes(thread)-bytes;
        long time=level.getGameTime(),rebuilds=cache.rebuildCount();
        var data=(net.minecraft.world.level.storage.ServerLevelData)level.getLevelData();
        bytes=bean.getThreadAllocatedBytes(thread);start=System.nanoTime();
        try {
            for(int i=0;i<runs;i++) {data.setGameTime(time+i);cache.trace(level,root,state,role);}
        } finally {data.setGameTime(time);}
        long amortizedNs=System.nanoTime()-start,amortizedBytes=bean.getThreadAllocatedBytes(thread)-bytes;
        System.out.printf(java.util.Locale.ROOT,"F16 PLUMBING %s: 32-pipe route, uncached %.0f ns / %.0f bytes; cached %.0f ns / %.0f bytes per lookup%n",
                label,(double)rawNs/runs,(double)rawBytes/runs,(double)cachedNs/runs,(double)cachedBytes/runs);
        System.out.printf(java.util.Locale.ROOT,"F16 PLUMBING %s: event-cached, across time changes %.0f ns / %.0f bytes per tick; %d rebuilds in %d ticks%n",
                label,(double)amortizedNs/runs,(double)amortizedBytes/runs,cache.rebuildCount()-rebuilds,runs);
        h.assertTrue(cachedBytes<rawBytes,"stable route allocation did not improve: "+label);
        h.assertTrue(amortizedBytes<rawBytes&&cache.rebuildCount()==rebuilds,
                "static network rebuilt without an edit: "+label);
    }

    @GameTest(template="empty",timeoutTicks=200)
    public static void cachedSteamValves(GameTestHelper h) {
        var level=h.getLevel();var root=new BlockPos(3600,180,3600);var block=BwrBlocks.TURBINE_FEED_PUMP.get();var state=place(level,block,root);
        var role=AssemblyPort.STEAM_INLET;var port=block.portPosition(root,state,role);var face=block.portFace(state,role);
        // Exit the casing before turning into the horizontal valve run.
        var bend=port.relative(face,3);
        for(int i=1;i<=3;i++)level.setBlock(port.relative(face,i),BwrBlocks.PRESSURISED_TUBE.get().defaultBlockState(),2);
        var across=face.getAxis()==Direction.Axis.X?Direction.NORTH:Direction.EAST;
        for(int i=1;i<=4;i++)level.setBlock(bend.relative(across,i),BwrBlocks.PRESSURISED_TUBE.get().defaultBlockState(),2);
        var nozzle=bend.relative(across,5);level.setBlock(nozzle,BwrBlocks.RPV_STEAM_OUTLET.get().defaultBlockState(),2);
        var at=bend.relative(across,2);level.setBlock(at,BwrBlocks.TURBINE_CONTROL_VALVE.get().defaultBlockState().setValue(TurbineValveBlock.FACING,across),2);
        var valve=(TurbineValveBlockEntity)level.getBlockEntity(at);valve.setTarget(1);valve.stroke(10);
        var cache=new AssemblyPlumbing.Cache();h.assertTrue(cache.trace(level,root,state,role).ends().contains(nozzle),"steam fixture disconnected");
        valve.setTarget(0);valve.stroke(10);
        h.assertTrue(cache.trace(level,root,state,role).ends().isEmpty(),"closed control valve used cached opening");
        valve.setTarget(.5);valve.stroke(10);
        h.assertTrue(Math.abs(cache.trace(level,root,state,role).opening()-.5)<1e-6,"valve movement waited for TTL");
        // A legacy inline MSIV also needs immediate closure/reopening detection.
        var isolation=bend.relative(across,3);level.setBlock(isolation,BwrBlocks.MSIV.get().defaultBlockState().setValue(MainSteamIsolationValveBlock.FACING,across),2);
        var msiv=(MainSteamIsolationValveBlockEntity)level.getBlockEntity(isolation);msiv.energy().receiveEnergy(20_000,false);msiv.setDemandOpen(true);msiv.tickValve(10);
        h.assertTrue(cache.trace(level,root,state,role).ends().contains(nozzle),"inline MSIV fixture disconnected");
        msiv.setDemandOpen(false);msiv.tickValve(10);
        h.assertTrue(cache.trace(level,root,state,role).ends().isEmpty(),"closed MSIV used cached opening");
        msiv.energy().receiveEnergy(20_000,false);msiv.setDemandOpen(true);msiv.tickValve(10);
        h.assertTrue(cache.trace(level,root,state,role).ends().contains(nozzle),"MSIV reopening waited for TTL");
        level.removeBlock(root,false);h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=200)
    public static void internalPumpRegistryLifecycle(GameTestHelper h) throws Exception {
        var level=h.getLevel();var origin=new BlockPos(3900,190,3900);load(level,origin);
        var build=PhaseOneRegressionTests.class.getDeclaredMethod("buildVessel",ServerLevel.class,BlockPos.class,int.class);
        build.setAccessible(true);build.invoke(null,level,origin,11);
        var root=origin.offset(11,3,5);level.setBlock(root,BwrBlocks.REACTOR_CONTROLLER.get().defaultBlockState(),2);
        var reactor=(ReactorControllerBlockEntity)level.getBlockEntity(root);
        var block=BwrBlocks.RIP_PUMP.get();var mount=origin.offset(-1,-1,-1);
        for(int i=0;i<block.cellCount();i++)level.removeBlock(mount.offset(block.cellOffset(i)),false);
        var state=place(level,block,mount);var pump=(RecirculationPumpBlockEntity)level.getBlockEntity(mount);
        var validate=ReactorControllerBlockEntity.class.getDeclaredMethod("revalidate",Level.class);validate.setAccessible(true);validate.invoke(reactor,level);
        h.assertTrue(reactor.isFormed(),"RIP vessel did not form: "+reactor.statusLines());
        var jetCache=RecirculationNetwork.class.getDeclaredField("CACHE");jetCache.setAccessible(true);
        ((java.util.Map<?,?>)jetCache.get(null)).clear();
        h.assertTrue(FormedReactorRegistry.controllers(level).contains(reactor),"formed vessel absent before measurement");
        RecirculationPumpBlockEntity.serverTick(level,mount,state,pump);
        h.assertTrue(root.equals(pump.getControllerPos()),"internal pump requires a jet survey to bind");
        RecirculationNetwork.measure(level,reactor,java.util.List.of(mount));
        var saved=reactor.saveWithFullMetadata(level.registryAccess());var reactorState=reactor.getBlockState();
        reactor.onChunkUnloaded();h.assertTrue(!FormedReactorRegistry.controllers(level).contains(reactor),"unloaded vessel remains registered");
        var surveys=(java.util.Map<?,?>)((java.util.Map<?,?>)jetCache.get(null)).get(level);
        h.assertTrue(surveys==null||!surveys.containsKey(root),"unloaded vessel retained stale survey geometry");
        level.removeBlockEntity(root);
        var replacement=(ReactorControllerBlockEntity)net.minecraft.world.level.block.entity.BlockEntity.loadStatic(root,reactorState,saved,level.registryAccess());
        level.setBlockEntity(replacement);validate.invoke(replacement,level);
        h.assertTrue(FormedReactorRegistry.controllers(level).contains(replacement),"restored vessel not registered");
        replacement.removePump(mount); // Do not let the saved pump list mask a failed rebind.
        var elapsed=RecirculationPumpBlockEntity.class.getDeclaredField("ticksSinceRebind");elapsed.setAccessible(true);elapsed.setInt(pump,40);
        RecirculationPumpBlockEntity.serverTick(level,mount,state,pump);
        h.assertTrue(replacement.pumpCount()==1,"pump did not bind restored controller");
        level.removeBlock(origin.offset(0,8,0),false);validate.invoke(replacement,level);
        h.assertTrue(!replacement.isFormed()&&!FormedReactorRegistry.controllers(level).contains(replacement),"broken vessel remains registered");
        level.removeBlock(root,false);level.removeBlock(mount,false);h.succeed();
    }
}
