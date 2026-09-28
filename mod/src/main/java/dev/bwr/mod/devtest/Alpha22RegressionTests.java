package dev.bwr.mod.devtest;

import dev.bwr.core.boundary.BoundaryComponent;
import dev.bwr.core.turbine.CoolingWaterUnit.Design;
import dev.bwr.mod.cooling.*;
import dev.bwr.mod.reactor.*;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.suppression.*;
import dev.bwr.mod.water.HighPressureWaterPipeBlock;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import static net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.*;

@net.neoforged.neoforge.gametest.GameTestHolder("bwr")
@net.neoforged.neoforge.gametest.PrefixGameTestTemplate(false)
public final class Alpha22RegressionTests {
    private static final int SHIFT=Math.floorMod(java.util.UUID.randomUUID().hashCode(),90000)*64;
    private static void near(GameTestHelper h,double a,double b,String m){h.assertTrue(Math.abs(a-b)<1e-5,m+": "+a+" != "+b);}
    private static void pipe(ServerLevel l,BlockPos a,BlockPos b){
        for(var p:BlockPos.betweenClosed(a,b))l.setBlock(p,BwrBlocks.HIGH_PRESSURE_WATER_PIPE.get().stateWithConnections(l,p),3);
    }
    @GameTest(template="empty",timeoutTicks=100)
    public static void underwaterPipeSurvives(GameTestHelper h){
        var l=h.getLevel();var p=new BlockPos(25000+SHIFT,195,25000);l.getChunkAt(p);
        l.setBlock(p,Blocks.WATER.defaultBlockState(),3);
        l.setBlock(p,BwrBlocks.HIGH_PRESSURE_WATER_PIPE.get().stateWithConnections(l,p),3);
        l.setBlock(p.east(),Blocks.WATER.defaultBlockState(),3);
        l.setBlock(p.east(),BwrBlocks.PRESSURISED_TUBE.get().stateWithConnections(l,p.east()),3);
        h.runAfterDelay(15,()->{
            h.assertTrue(l.getBlockState(p).is(BwrBlocks.HIGH_PRESSURE_WATER_PIPE.get())&&l.getBlockState(p).getValue(HighPressureWaterPipeBlock.WATERLOGGED),"water destroyed or drained the pipe");
            h.assertTrue(l.getFluidState(p).isSource(),"submerged pipe displaced its source water");
            h.assertTrue(l.getBlockState(p.east()).is(BwrBlocks.PRESSURISED_TUBE.get())&&l.getFluidState(p.east()).isSource(),"underwater steam pipe failed");
            h.succeed();
        });
    }
    @GameTest(template="empty",timeoutTicks=150)
    public static void tankDrainAndRhrSuction(GameTestHelper h)throws Exception{
        var l=h.getLevel();var o=new BlockPos(25200+SHIFT,190,25200);var pool=SuppressionTankRegressionTests.build(l,o);
        var drain=o.offset(0,1,3);
        l.setBlock(drain,BwrBlocks.SUPPRESSION_POOL_DRAIN.get().defaultBlockState().setValue(SuppressionPoolPortBlock.FACING,Direction.WEST),3);
        SuppressionBasinRegressionTests.validate(pool);h.assertTrue(pool.isFormed(),"drain rejected as tank wall");
        var suction=o.offset(8,1,3);var cap=l.getCapability(Capabilities.FluidHandler.BLOCK,suction,Direction.EAST);
        h.assertTrue(cap!=null&&l.getCapability(Capabilities.FluidHandler.BLOCK,suction,Direction.WEST)==null,"RHR suction face incorrect");
        pool.water(false).fill(new FluidStack(Fluids.WATER,50000),EXECUTE);
        near(h,cap.drain(100,SIMULATE).getAmount(),100,"RHR suction simulation failed");near(h,pool.pool().getMassKg(),50000,"simulation drained tank");
        near(h,cap.drain(100,EXECUTE).getAmount(),100,"RHR suction failed");near(h,pool.pool().getMassKg(),49900,"RHR suction did not debit tank");
        long time=l.getGameTime();var data=l.getServer().getWorldData().overworldData();
        try{
            data.setGameTime(time+1);SuppressionPoolBlockEntity.serverTick(l,pool.getBlockPos(),pool.getBlockState(),pool);
            near(h,pool.pool().getMassKg(),49900,"closed drain leaked");pool.setDrainsOpen(true);
            data.setGameTime(time+2);SuppressionPoolBlockEntity.serverTick(l,pool.getBlockPos(),pool.getBlockState(),pool);
            near(h,pool.pool().getMassKg(),49850,"open drain did not discharge to outside");
            l.setBlock(drain.west(),Blocks.STONE.defaultBlockState(),3);
            data.setGameTime(time+3);SuppressionPoolBlockEntity.serverTick(l,pool.getBlockPos(),pool.getBlockState(),pool);
            near(h,pool.pool().getMassKg(),49850,"blocked drain discarded water");
            l.removeBlock(drain.west(),false);pool.pool().drainWaterKg(49840);
            h.assertTrue(cap.drain(100,SIMULATE).isEmpty(),"RHR suction ignored minimum level");
            data.setGameTime(time+4);SuppressionPoolBlockEntity.serverTick(l,pool.getBlockPos(),pool.getBlockState(),pool);
            near(h,pool.pool().getMassKg(),0,"maintenance drain left inaccessible inventory");
            l.removeBlock(o.offset(4,4,3),false);
            h.assertTrue(cap.drain(1,EXECUTE).isEmpty(),"stale suction capability drained an unformed tank");
        }finally{data.setGameTime(time);}
        h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=150)
    public static void multipleIntakesSharePumpSupply(GameTestHelper h){
        var l=h.getLevel();var o=new BlockPos(25400+SHIFT,190,25400);
        for(int x=-2;x<4;x++)for(int z=-2;z<4;z++)l.getChunk((o.getX()>>4)+x,(o.getZ()>>4)+z);
        var sources=new java.util.ArrayList<CoolingBlockEntity>();
        for(int i=0;i<3;i++){
            var s=CoolingRuntimeCheck.place(l,Design.INTAKE,o.offset(i*8,0,0),Direction.NORTH);sources.add(s);
            s.plant().restore(0,6000,0);
            var port=CoolingRuntimeCheck.port(s,CoolingBlock.Port.OUTLET);var outside=port.relative(CoolingBlock.portFace(l.getBlockState(port)));
            pipe(l,outside,new BlockPos(outside.getX(),o.getY()-5,outside.getZ()));
            pipe(l,new BlockPos(outside.getX(),o.getY()-5,outside.getZ()),new BlockPos(outside.getX(),o.getY()-5,o.getZ()+8));
        }
        pipe(l,o.offset(0,-5,8),o.offset(16,-5,8));
        var pump=CoolingRuntimeCheck.place(l,Design.MAKEUP,o.offset(8,0,16),Direction.NORTH);
        var inlet=CoolingRuntimeCheck.port(pump,CoolingBlock.Port.INLET);var outside=inlet.relative(CoolingBlock.portFace(l.getBlockState(inlet)));
        pipe(l,outside,new BlockPos(outside.getX(),o.getY()-5,outside.getZ()));
        pipe(l,new BlockPos(outside.getX(),o.getY()-5,outside.getZ()),new BlockPos(outside.getX(),o.getY()-5,o.getZ()+8));
        CoolingWaterTransfer.pull(pump);
        h.assertTrue(pump.connectedSources==3,"pump did not identify all three screens: "+pump.connectedSources);
        near(h,pump.plant().input(),75,"combined supply exceeded/lost makeup pump capacity");
        for(var source:sources)near(h,source.plant().output(),5975,"shared header monopolized a single screen");
        var builds=dev.bwr.mod.piping.PipeTopology.buildCount();CoolingWaterTransfer.pull(pump);
        h.assertTrue(dev.bwr.mod.piping.PipeTopology.buildCount()==builds,"steady suction rebuilt the pipe graph");h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=200)
    public static void boundaryFailuresBreakPipesOnceAndPersist(GameTestHelper h)throws Exception{
        var l=h.getLevel();var min=new BlockPos(25700+SHIFT,190,25700);
        var reactor=CompactCoreRegressionTests.build(l,min,9,9,12,false);
        var port=min.offset(4,3,-1);var outside=port.north();
        l.setBlock(port,BwrBlocks.RPV_WATER_INJECTION_PORT.get().defaultBlockState().setValue(RpvWaterInjectionPortBlock.FACING,Direction.NORTH),3);pipe(l,outside,outside.north());
        SpargerRegressionTests.validate(reactor);h.assertTrue(reactor.isFormed(),"failure fixture not formed");
        reactor.core().getBoundaryStress().forceFailure(BoundaryComponent.FEEDWATER_LINE,1500);
        reactor.core().getBoundaryStress().forceFailure(BoundaryComponent.REACTOR_VESSEL_HEAD,1700);
        ReactorControllerBlockEntity.serverTick(l,reactor.getBlockPos(),reactor.getBlockState(),reactor);
        h.assertTrue(l.getBlockState(outside).isAir()&&l.getBlockState(outside.north()).is(BwrBlocks.HIGH_PRESSURE_WATER_PIPE.get()),"failure did not break only connected pipe");
        var tag=reactor.getUpdateTag(l.registryAccess());h.assertTrue((tag.getInt("VisualBoundaryMask")&ReactorBoundaryEffects.HEAD)!=0,"head failure not synced");
        h.assertTrue(reactor.setVesselState(VesselState.SHUTDOWN)!=null,"head toggle repaired failed vessel");
        var root=reactor.getBlockPos();var saved=reactor.saveWithFullMetadata(l.registryAccess());l.removeBlockEntity(root);
        reactor=(ReactorControllerBlockEntity)BlockEntity.loadStatic(root,l.getBlockState(root),saved,l.registryAccess());l.setBlockEntity(reactor);SpargerRegressionTests.validate(reactor);
        pipe(l,outside,outside);ReactorControllerBlockEntity.serverTick(l,root,reactor.getBlockState(),reactor);
        h.assertTrue(l.getBlockState(outside).is(BwrBlocks.HIGH_PRESSURE_WATER_PIPE.get()),"reload replayed destructive failure");
        h.assertTrue(reactor.core().getBoundaryStress().isBroken(BoundaryComponent.REACTOR_VESSEL_HEAD),"reload healed failed head");h.succeed();
    }
}
