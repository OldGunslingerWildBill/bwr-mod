package dev.bwr.mod.devtest;

import dev.bwr.mod.eccs.*;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.suppression.*;
import dev.bwr.core.pool.*;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
import net.neoforged.neoforge.gametest.*;

@GameTestHolder("bwr") @PrefixGameTestTemplate(false)
public final class SuppressionBasinRegressionTests {
    // GameTestServer reuses its world. Each run needs previously unmetered basin cells.
    private static final int RUN_SHIFT=Math.floorMod(java.util.UUID.randomUUID().hashCode(),100_000)*64;
    public static void validate(SuppressionPoolBlockEntity pool) throws Exception {
        var m=SuppressionPoolBlockEntity.class.getDeclaredMethod("revalidate",Level.class);m.setAccessible(true);m.invoke(pool,pool.getLevel());
    }
    public static SuppressionPoolBlockEntity build(ServerLevel l,BlockPos origin) throws Exception {
        for(int x=(origin.getX()-16)>>4;x<=(origin.getX()+32)>>4;x++)for(int z=(origin.getZ()-16)>>4;z<=(origin.getZ()+20)>>4;z++)l.getChunk(x,z);
        for(var p:BlockPos.betweenClosed(origin.offset(-3,-1,-7),origin.offset(18,9,9)))l.setBlock(p,Blocks.AIR.defaultBlockState(),2);
        for(int x=0;x<9;x++)for(int y=0;y<5;y++)for(int z=0;z<7;z++) {
            boolean shell=x==0||x==8||z==0||z==6||y==0;
            var s=shell?BwrBlocks.SUPPRESSION_POOL_WALL.get().defaultBlockState().setValue(SuppressionPoolWallBlock.RIM,y==4)
                    :Blocks.AIR.defaultBlockState();l.setBlock(origin.offset(x,y,z),s,2);
        }
        var controller=origin.offset(4,2,0);l.setBlock(controller,BwrBlocks.SUPPRESSION_POOL_CONTROLLER.get().defaultBlockState(),2);
        l.setBlock(origin.offset(8,1,3),BwrBlocks.SUPPRESSION_POOL_SUCTION.get().defaultBlockState().setValue(SuppressionPoolPortBlock.FACING,Direction.EAST),2);
        l.setBlock(origin.offset(6,1,0),BwrBlocks.SUPPRESSION_POOL_RETURN.get().defaultBlockState(),2);
        var pool=(SuppressionPoolBlockEntity)l.getBlockEntity(controller);
        // Saving before the first formation must not turn the default physics inventory into free water.
        var unfinished=pool.saveWithFullMetadata(l.registryAccess());l.removeBlockEntity(controller);
        l.setBlockEntity(net.minecraft.world.level.block.entity.BlockEntity.loadStatic(controller,l.getBlockState(controller),unfinished,l.registryAccess()));
        pool=(SuppressionPoolBlockEntity)l.getBlockEntity(controller);
        // Exercise the same automatic server tick used after placing a complete shell.
        for(int i=0;i<40;i++)SuppressionPoolBlockEntity.serverTick(l,controller,l.getBlockState(controller),pool);
        if(!pool.isFormed())throw new AssertionError(pool.statusLines());
        return pool;
    }
    private static void near(GameTestHelper h,double a,double b,String message){h.assertTrue(Double.isFinite(a)&&Math.abs(a-b)<1e-7*Math.max(1,Math.abs(b)),message+": "+a+" != "+b);}
    @GameTest(template="empty",timeoutTicks=300)
    public static void concreteBasinLifecycle(GameTestHelper h) throws Exception {
        var l=h.getLevel();var o=new BlockPos(4200+RUN_SHIFT,190,4200);var pool=build(l,o);
        h.assertTrue(pool.isConcreteBasin(),"concrete shell not recognized");near(h,pool.structureMassKg(),105_000,"geometric capacity");
        var suction=o.offset(8,1,3);var returned=o.offset(6,1,0);
        var drain=l.getCapability(Capabilities.FluidHandler.BLOCK,suction,Direction.EAST);
        var fill=l.getCapability(Capabilities.FluidHandler.BLOCK,returned,Direction.NORTH);
        h.assertTrue(drain!=null&&fill!=null,"wall ports missing capabilities");
        near(h,pool.pool().getMassKg(),0,"dry shell created water");
        h.assertTrue(drain.drain(100,FluidAction.EXECUTE).isEmpty(),"empty pool supplied water");
        h.assertTrue(fill.fill(new FluidStack(Fluids.WATER,105_000),FluidAction.EXECUTE)==105_000,"piped filling failed");
        double initial=pool.pool().getMassKg();near(h,drain.drain(123,FluidAction.SIMULATE).getAmount(),123,"simulated suction");near(h,pool.pool().getMassKg(),initial,"simulation changed mass");
        drain.drain(123,FluidAction.EXECUTE);validate(pool);near(h,pool.pool().getMassKg(),initial-123,"survey refilled basin");
        h.assertTrue(drain.fill(new FluidStack(Fluids.WATER,10),FluidAction.EXECUTE)==0&&fill.drain(10,FluidAction.EXECUTE).isEmpty(),"directional ports crossed roles");
        pool.pool().fromArray(new SuppressionPool(105_000,32).toArray());pool.pool().condenseSteam(100,1000,1);
        double condensed=pool.pool().getMassKg();validate(pool);near(h,pool.pool().getMassKg(),condensed,"revalidation erased condensate");
        var breach=o.offset(0,2,2);l.removeBlock(breach,false);
        h.assertTrue(!pool.isFormed()&&drain.drain(100,FluidAction.EXECUTE).isEmpty(),"broken wall retained active cached suction");
        l.setBlock(breach,BwrBlocks.SUPPRESSION_POOL_WALL.get().defaultBlockState(),3);validate(pool);near(h,pool.pool().getMassKg(),condensed,"repair changed inventory");
        var tag=pool.saveWithFullMetadata(l.registryAccess());var root=pool.getBlockPos();l.removeBlockEntity(root);
        l.setBlockEntity(net.minecraft.world.level.block.entity.BlockEntity.loadStatic(root,l.getBlockState(root),tag,l.registryAccess()));
        var replacement=(SuppressionPoolBlockEntity)l.getBlockEntity(root);validate(replacement);
        h.assertTrue(drain.drain(1,FluidAction.EXECUTE).getAmount()==1,"cached wall handler did not resolve replacement");
        near(h,replacement.pool().getMassKg(),condensed-1,"reload lost inventory");
        l.setBlock(o.offset(2,2,0),BwrBlocks.SUPPRESSION_POOL_CONTROLLER.get().defaultBlockState(),3);validate(replacement);
        h.assertTrue(!replacement.isFormed(),"duplicate concrete controller accepted");
        l.setBlock(o.offset(2,2,0),BwrBlocks.SUPPRESSION_POOL_WALL.get().defaultBlockState(),3);validate(replacement);
        h.assertTrue(replacement.isFormed(),"duplicate removal did not repair basin");
        System.out.println("CONCRETE BASIN PASS: automatic formation, ports, mass, break/repair, duplicate rejection and live replacement");h.succeed();
    }
    private static void pipe(ServerLevel l,BlockPos a,BlockPos b){
        if(a.getX()!=b.getX()&&a.getZ()!=b.getZ()||a.getY()!=b.getY()&&(a.getX()!=b.getX()||a.getZ()!=b.getZ()))throw new IllegalArgumentException("straight segment required");
        for(var p:BlockPos.betweenClosed(a,b))l.setBlock(p,BwrBlocks.HIGH_PRESSURE_WATER_PIPE.get().stateWithConnections(l,p),3);
    }
    public static EccsPumpBlockEntity connect(ServerLevel l,BlockPos o) {
        var block=BwrBlocks.RHR_PUMP.get();var root=o.offset(12,0,3);var s=block.placementState();
        l.setBlock(root,s,3);block.setPlacedBy(l,root,s,null,new ItemStack(block));
        var pump=(EccsPumpBlockEntity)l.getBlockEntity(root);pump.setPanelControlled(true);pump.setSuctionSource(SuctionSource.SUPPRESSION_POOL);pump.setMode(EccsPumpBlockEntity.Mode.POOL_COOLING);pump.setRunning(true);pump.setSpeedDemandFraction(1);
        pipe(l,o.offset(10,3,3),o.offset(10,6,3));pipe(l,o.offset(10,6,3),o.offset(10,6,-4));pipe(l,o.offset(6,6,-4),o.offset(10,6,-4));
        var hx=o.offset(6,6,-3);l.setBlock(hx,BwrBlocks.RHR_HEAT_EXCHANGER.get().defaultBlockState(),3);
        pipe(l,o.offset(6,6,-2),o.offset(6,6,-1));pipe(l,o.offset(6,1,-1),o.offset(6,6,-1));
        return pump;
    }
    @GameTest(template="empty",timeoutTicks=400)
    public static void rhrPhysicalHeatExchanger(GameTestHelper h) throws Exception {
        var l=h.getLevel();var o=new BlockPos(4280+RUN_SHIFT,190,4280);var pool=build(l,o);var pump=connect(l,o);validate(pool);
        var hxPos=o.offset(6,6,-3);var hx=(RhrHeatExchangerBlockEntity)l.getBlockEntity(hxPos);var cold=hx.secondary(true);var hot=hx.secondary(false);
        h.assertTrue(AssemblyPlumbing.endpoint(l,AssemblyPlumbing.trace(l,pump.getBlockPos(),pump.getBlockState(),AssemblyPort.WATER_SUCTION),SuppressionPoolBlockEntity.class)==pool,"direct adjacent RHR suction disconnected");
        h.assertTrue(hx.returnPool()==pool,"exchanger return not connected");
        pool.pool().fromArray(new SuppressionPool(105_000,80).toArray());double secondaryAdded=0,secondaryRemoved=0;
        long time=l.getGameTime();var data=l.getServer().getWorldData().overworldData();
        try {
            for(int i=0;i<220;i++) {
                data.setGameTime(time+i);pump.energy().setStored(pump.energy().getMaxEnergyStored());
                secondaryAdded+=cold.fill(new FluidStack(Fluids.WATER,120),FluidAction.EXECUTE);
                EccsPumpBlockEntity.serverTick(l,pump.getBlockPos(),pump.getBlockState(),pump);
                secondaryRemoved+=hot.drain(120,FluidAction.EXECUTE).getAmount();
            }
            h.assertTrue(pump.getDeliveredFlowKgPerS()>0&&pool.pool().getTemperatureC()<80&&pool.pool().getCumulativeRhrRemovedMJ()>0,"powered RHR failed to cool through exchanger: "+pump.connectionStatus());
            near(h,pool.pool().getMassKg(),105_000,"primary loop lost pool water");near(h,hx.plant().cold()+hx.plant().hot()+secondaryRemoved,secondaryAdded,"secondary loop lost water");
            double temp=pool.pool().getTemperatureC();l.removeBlock(o.offset(6,4,-1),false);data.setGameTime(time+221);
            EccsPumpBlockEntity.serverTick(l,pump.getBlockPos(),pump.getBlockState(),pump);near(h,pool.pool().getTemperatureC(),temp,"broken return cooled pool");
            pipe(l,o.offset(6,4,-1),o.offset(6,4,-1));
            // Replace exchanger by a straight pipe: direct circulation moves water but rejects no heat.
            l.setBlock(hxPos,BwrBlocks.HIGH_PRESSURE_WATER_PIPE.get().stateWithConnections(l,hxPos),3);data.setGameTime(time+230);
            pump.energy().setStored(pump.energy().getMaxEnergyStored());EccsPumpBlockEntity.serverTick(l,pump.getBlockPos(),pump.getBlockState(),pump);
            h.assertTrue(pump.getDeliveredFlowKgPerS()>0,"direct pool return cannot circulate");near(h,pool.pool().getTemperatureC(),temp,"direct return created free cooling");
        } finally {data.setGameTime(time);}
        System.out.println("RHR EXCHANGER PASS: direct suction, physical pumping, isolated circuits, broken return and direct bypass");h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=200)
    public static void exchangerPortsAndPersistence(GameTestHelper h){
        var l=h.getLevel();var pos=new BlockPos(4380+RUN_SHIFT,195,4380);l.getChunkAt(pos);
        for(Direction facing:Direction.Plane.HORIZONTAL) {
            l.removeBlock(pos,false);var s=BwrBlocks.RHR_HEAT_EXCHANGER.get().defaultBlockState().setValue(RhrHeatExchangerBlock.FACING,facing);l.setBlock(pos,s,3);
            var cold=l.getCapability(Capabilities.FluidHandler.BLOCK,pos,RhrHeatExchangerBlock.coldIn(s));
            var hot=l.getCapability(Capabilities.FluidHandler.BLOCK,pos,RhrHeatExchangerBlock.hotOut(s));
            h.assertTrue(cold!=null&&hot!=null,"missing rotated secondary ports");
            h.assertTrue(l.getCapability(Capabilities.FluidHandler.BLOCK,pos,Direction.UP)==null,"top became a fifth water port");
            h.assertTrue(cold.fill(new FluidStack(Fluids.WATER,123),FluidAction.SIMULATE)==123&&cold.getFluidInTank(0).isEmpty(),"simulated fill mutated state");
            h.assertTrue(hot.fill(new FluidStack(Fluids.WATER,5),FluidAction.EXECUTE)==0&&cold.drain(5,FluidAction.EXECUTE).isEmpty(),"secondary directions crossed");
            var hx=(RhrHeatExchangerBlockEntity)l.getBlockEntity(pos);hx.plant().restore(11_999.5,0,0);
            h.assertTrue(cold.fill(new FluidStack(Fluids.WATER,2),FluidAction.EXECUTE)==0,"fractional headroom reported a whole transfer");
            near(h,hx.plant().cold(),11_999.5,"integer capability created fractional water");hx.plant().restore(0,0,0);
            cold.fill(new FluidStack(Fluids.WATER,123),FluidAction.EXECUTE);var be=l.getBlockEntity(pos);var saved=be.saveWithFullMetadata(l.registryAccess());l.removeBlockEntity(pos);
            l.setBlockEntity(net.minecraft.world.level.block.entity.BlockEntity.loadStatic(pos,s,saved,l.registryAccess()));
            h.assertTrue(cold.getFluidInTank(0).getAmount()==123,"cached exchanger inlet failed to rebind after reload");
        }
        h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=250)
    public static void pumpedFillSprayAndQuencherLevel(GameTestHelper h) throws Exception {
        var l=h.getLevel();var o=new BlockPos(4460+RUN_SHIFT,190,4460);var pool=build(l,o);
        var fill=l.getCapability(Capabilities.FluidHandler.BLOCK,o.offset(6,1,0),Direction.NORTH);
        var q=o.offset(4,1,3);l.setBlock(q,BwrBlocks.SUPPRESSION_POOL_QUENCHER.get().defaultBlockState(),3);
        // Vanilla water has no authority over a metered concrete pool's inventory or submersion.
        l.setBlock(q.above(),Blocks.WATER.defaultBlockState(),3);validate(pool);
        near(h,pool.pool().getMassKg(),0,"source block refilled concrete pool");
        h.assertTrue(!SuppressionPoolQuencherBlock.isSubmerged(l,q),"source block bypassed pumped level");
        l.setBlock(q.above(),Blocks.AIR.defaultBlockState(),3);
        fill.fill(new FluidStack(Fluids.WATER,55_000),FluidAction.EXECUTE);validate(pool);
        h.assertTrue(pool.ownsQuencher(q)&&SuppressionPoolQuencherBlock.isSubmerged(l,q),"pumped water did not submerge quencher");
        pool.setSprayMode(true);double before=pool.pool().getMassKg();
        near(h,fill.fill(new FluidStack(Fluids.WATER,100),FluidAction.SIMULATE),100,"spray simulate rejected water");
        near(h,pool.pool().getSprayWaterKg(),0,"spray simulate mutated header");
        near(h,fill.fill(new FluidStack(Fluids.WATER,100),FluidAction.EXECUTE),100,"spray fill failed");
        near(h,pool.pool().getMassKg(),before,"spray bypassed nozzle buffer");
        var tag=pool.saveWithFullMetadata(l.registryAccess());var root=pool.getBlockPos();l.removeBlockEntity(root);
        l.setBlockEntity(net.minecraft.world.level.block.entity.BlockEntity.loadStatic(root,l.getBlockState(root),tag,l.registryAccess()));
        pool=(SuppressionPoolBlockEntity)l.getBlockEntity(root);validate(pool);
        h.assertTrue(pool.pool().isSprayMode(),"spray mode lost on reload");near(h,pool.pool().getSprayWaterKg(),100,"spray supply lost on reload");
        // A live server tick sprays the queued water even without a reactor attached.
        SuppressionPoolBlockEntity.serverTick(l,root,l.getBlockState(root),pool);
        near(h,pool.pool().getSprayWaterKg(),70,"nozzle did not meter 30 kg per tick");near(h,pool.pool().getMassKg(),before+30,"spray water did not join pool");
        pool.setSprayMode(false);SuppressionPoolBlockEntity.serverTick(l,root,l.getBlockState(root),pool);
        near(h,pool.pool().getMassKg(),before+100,"mode switch deleted held water");near(h,pool.pool().getSprayWaterKg(),0,"mode switch retained stranded water");
        var breach=o.offset(0,2,2);l.removeBlock(breach,false);
        h.assertTrue(fill.fill(new FluidStack(Fluids.WATER,100),FluidAction.EXECUTE)==0,"broken basin accepted fill");
        h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=250)
    public static void passiveCoolingWithoutSupply(GameTestHelper h) throws Exception {
        var l=h.getLevel();var o=new BlockPos(4630+RUN_SHIFT,190,4630);var pool=build(l,o);
        var root=pool.getBlockPos();
        pool.pool().fromArray(new SuppressionPool(105_000,80).toArray());
        for(int i=0;i<40;i++)SuppressionPoolBlockEntity.serverTick(l,root,l.getBlockState(root),pool);
        h.assertTrue(pool.pool().getTemperatureC()<80&&pool.pool().getTemperatureC()>79.99,
                "pool must cool slowly without any attached pump or water supply");
        h.assertTrue(pool.passiveCoolingMW()>0&&pool.getEffectiveRhrDuty()==0,"natural cooling missing or enabled RHR");
        near(h,pool.pool().getMassKg(),105_000,"natural cooling consumed water");
        near(h,pool.pool().getCumulativeRhrRemovedMJ(),0,"passive heat counted as RHR");
        double fullDuty=pool.passiveCoolingMW();
        pool.pool().fromArray(new SuppressionPool(52_500,80).toArray());pool.pool().resizeCapacityKeepingInventory(105_000);
        SuppressionPoolBlockEntity.serverTick(l,root,l.getBlockState(root),pool);
        h.assertTrue(pool.passiveCoolingMW()<fullDuty,"lower water did not reduce wetted wall area");
        double cooled=pool.pool().getTemperatureC();
        var tag=pool.saveWithFullMetadata(l.registryAccess());l.removeBlockEntity(root);
        l.setBlockEntity(net.minecraft.world.level.block.entity.BlockEntity.loadStatic(root,l.getBlockState(root),tag,l.registryAccess()));
        pool=(SuppressionPoolBlockEntity)l.getBlockEntity(root);validate(pool);
        near(h,pool.pool().getTemperatureC(),cooled,"reload reset cooldown");
        SuppressionPoolBlockEntity.serverTick(l,root,l.getBlockState(root),pool);
        h.assertTrue(pool.pool().getTemperatureC()<cooled,"natural cooling did not resume after reload");
        pool.pool().fromArray(new SuppressionPool(52_500,25).toArray());
        SuppressionPoolBlockEntity.serverTick(l,root,l.getBlockState(root),pool);
        near(h,pool.pool().getTemperatureC(),25,"pool cooled below ambient");
        near(h,pool.passiveCoolingMW(),0,"ambient pool reported cooling");
        l.removeBlock(o.offset(0,2,2),false);
        near(h,pool.passiveCoolingMW(),0,"unformed pool reported stale cooling");
        System.out.println("PASSIVE POOL COOLING PASS: no supply or power, finite inventory, level geometry, reload and ambient floor");
        h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=250)
    public static void previousPumpLayoutsRemainUsable(GameTestHelper h) {
        var l=h.getLevel();int offset=0;
        for(var design:new dev.bwr.core.turbine.CoolingWaterUnit.Design[]{dev.bwr.core.turbine.CoolingWaterUnit.Design.CIRCULATING,dev.bwr.core.turbine.CoolingWaterUnit.Design.MAKEUP}) {
            var root=new BlockPos(4550+RUN_SHIFT+offset,195,4550);offset+=16;
            for(int x=(root.getX()-8)>>4;x<=(root.getX()+8)>>4;x++)for(int z=(root.getZ()-8)>>4;z<=(root.getZ()+8)>>4;z++)l.getChunk(x,z);
            var block=CoolingRuntimeCheck.block(design);var state=block.defaultBlockState();var layout=dev.bwr.mod.cooling.CoolingLayout.previous(design);
            l.setBlock(root,state,2);var owner=(dev.bwr.mod.cooling.CoolingBlockEntity)l.getBlockEntity(root);
            var tag=owner.saveWithoutMetadata(l.registryAccess());tag.putInt("LayoutVersion",2);tag.putInt("Cell",layout.controllerIndex());owner.loadWithComponents(tag,l.registryAccess());
            for(var cell:layout.cells) {
                var p=layout.world(root,Direction.NORTH,cell);if(p.equals(root))continue;
                l.setBlock(p,state.setValue(dev.bwr.mod.cooling.CoolingBlock.CONTROLLER,false).setValue(dev.bwr.mod.cooling.CoolingBlock.PORT,cell.role()),2);
                ((dev.bwr.mod.cooling.CoolingBlockEntity)l.getBlockEntity(p)).bind(owner,cell.index());
            }
            h.assertTrue(owner.ready()&&owner.previousPumpModel(),"legacy pump failed to retain layout/model");
            for(var port:layout.ports) {
                var p=layout.world(root,Direction.NORTH,port);
                if(port.role().water())h.assertTrue(l.getCapability(Capabilities.FluidHandler.BLOCK,p,port.face())!=null,"legacy flange disconnected");
                else h.assertTrue(l.getCapability(Capabilities.EnergyStorage.BLOCK,p,port.face())!=null,"legacy power disconnected");
            }
            l.removeBlock(root,false);
            for(var cell:layout.cells)h.assertTrue(l.getBlockState(layout.world(root,Direction.NORTH,cell)).isAir(),"legacy teardown left parts");
        }
        h.succeed();
    }
}
