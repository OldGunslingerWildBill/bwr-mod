package dev.bwr.mod.devtest;

import dev.bwr.mod.containment.ContainmentControllerBlockEntity;
import dev.bwr.mod.containment.ContainmentPenetrationBlock;
import dev.bwr.mod.containment.ContainmentPenetrationBlockEntity;
import dev.bwr.mod.reactor.ReactorControllerBlockEntity;
import dev.bwr.mod.reactor.RpvWaterInjectionPortBlock;
import dev.bwr.mod.flow.RecirculationCircuit;
import dev.bwr.mod.eccs.PumpAssemblyBlock;
import dev.bwr.mod.eccs.AssemblyPort;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.water.WaterLineNetwork;
import dev.bwr.mod.water.WaterDischargeBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Review E0a: a connected recirculation circuit is not an environmental water sink. */
@GameTestHolder("bwr")
@PrefixGameTestTemplate(false)
public final class Alpha31BypassReviewTests {
    private Alpha31BypassReviewTests() {}

    @GameTest(template="empty", timeoutTicks=240)
    public static void waterBypassRequiresMeasuredNonreturningFlow(GameTestHelper h) throws Exception {
        ServerLevel level=h.getLevel();
        BlockPos min=new BlockPos(40200,190,40200);
        BlockPos lo=min.offset(-4,-4,-4), hi=min.offset(14,10,14);
        for(int cx=lo.getX()>>4;cx<=(hi.getX()+10)>>4;cx++)
            for(int cz=lo.getZ()>>4;cz<=(hi.getZ()+10)>>4;cz++)level.setChunkForced(cx,cz,true);
        for(int x=-4;x<=14;x++)for(int z=-4;z<=14;z++)for(int y=-4;y<=10;y++) {
            boolean wall=x==-4||x==14||z==-4||z==14||y==-4||y==10;
            level.setBlock(min.offset(x,y,z),(wall?Blocks.STONE:Blocks.AIR).defaultBlockState(),2);
        }
        ReactorControllerBlockEntity reactor=CompactCoreRegressionTests.build(level,min,11,11,8,false);
        BlockPos outlet=min.offset(11,6,5), pipe=min.offset(12,6,5), sleeve=min.offset(14,6,5);
        BlockPos sink=min.offset(16,6,5);
        level.setBlock(outlet,BwrBlocks.RECIRCULATION_OUTLET.get().defaultBlockState()
                .setValue(RpvWaterInjectionPortBlock.FACING,Direction.EAST),3);
        level.setBlock(pipe,BwrBlocks.HIGH_PRESSURE_WATER_PIPE.get().defaultBlockState(),3);
        level.setBlock(min.offset(13,6,5),BwrBlocks.HIGH_PRESSURE_WATER_PIPE.get().defaultBlockState(),3);
        level.setBlock(sleeve,BwrBlocks.CONTAINMENT_WATER_PENETRATION.get().defaultBlockState()
                .setValue(ContainmentPenetrationBlock.FACING,Direction.EAST),3);
        BlockPos returnPort=min.offset(11,0,5), returnSleeve=min.offset(14,0,5);
        level.setBlock(returnPort,BwrBlocks.RECIRCULATION_INLET.get().defaultBlockState()
                .setValue(RpvWaterInjectionPortBlock.FACING,Direction.EAST),3);
        level.setBlock(returnSleeve,BwrBlocks.CONTAINMENT_WATER_PENETRATION.get().defaultBlockState()
                .setValue(ContainmentPenetrationBlock.FACING,Direction.EAST),3);
        // Clear the old far-coordinate fixture before placing the 5x10x5 pump.
        for(int x=15;x<=22;x++)for(int y=0;y<=9;y++)for(int z=5;z<=12;z++)
            level.setBlock(min.offset(x,y,z),Blocks.AIR.defaultBlockState(),2);
        BlockPos pumpPos=min.offset(19,0,8);
        var pump=BwrBlocks.RECIRCULATION_PUMP.get();
        var pumpState=pump.placementState().setValue(PumpAssemblyBlock.FACING,Direction.NORTH);
        level.setBlock(pumpPos,pumpState,3);
        pump.setPlacedBy(level,pumpPos,pumpState,null,new ItemStack(pump));
        h.assertTrue(pump.complete(level,pumpPos,pumpState),"fixture outside pump did not form");
        h.assertTrue(pump.portPosition(pumpPos,pumpState,AssemblyPort.WATER_SUCTION).equals(min.offset(19,0,10))
                        && pump.portPosition(pumpPos,pumpState,AssemblyPort.WATER_DISCHARGE).equals(min.offset(19,2,6)),
                "fixture pump ports changed; reroute the outside circuit");
        for(int x=12;x<=16;x++)for(int y:new int[]{0,6})
            if(x!=14)level.setBlock(min.offset(x,y,5),BwrBlocks.HIGH_PRESSURE_WATER_PIPE.get().defaultBlockState(),3);
        for(int z=6;z<=11;z++)level.setBlock(min.offset(16,6,z),
                BwrBlocks.HIGH_PRESSURE_WATER_PIPE.get().defaultBlockState(),3);
        for(int y=0;y<=5;y++)level.setBlock(min.offset(16,y,11),
                BwrBlocks.HIGH_PRESSURE_WATER_PIPE.get().defaultBlockState(),3);
        for(int x=17;x<=19;x++)level.setBlock(min.offset(x,0,11),
                BwrBlocks.HIGH_PRESSURE_WATER_PIPE.get().defaultBlockState(),3);
        for(int x=16;x<=19;x++)level.setBlock(min.offset(x,2,5),
                BwrBlocks.HIGH_PRESSURE_WATER_PIPE.get().defaultBlockState(),3);
        for(int y=0;y<=1;y++)level.setBlock(min.offset(16,y,5),
                BwrBlocks.HIGH_PRESSURE_WATER_PIPE.get().defaultBlockState(),3);
        reactor.markStructureDirty();
        ReactorControllerBlockEntity.serverTick(level,reactor.getBlockPos(),reactor.getBlockState(),reactor);
        h.assertTrue(reactor.isFormed()&&reactor.structure().waterInjectionPositions().contains(outlet),
                "review bypass vessel did not include its recirculation outlet");
        BlockPos control=min.offset(-3,0,-3);
        level.setBlock(control,BwrBlocks.CONTAINMENT_CONTROLLER.get().defaultBlockState(),3);
        var containment=(ContainmentControllerBlockEntity)level.getBlockEntity(control);
        for(int i=0;i<32;i++) {
            ContainmentControllerBlockEntity.serverTick(level,control,containment.getBlockState(),containment);
            if(!containment.isScanning()&&containment.strengthReady())break;
        }
        h.assertTrue(containment.isSealed()&&containment.openings().contains(sleeve),
                "review bypass room or penetration failed survey: "+containment.statusLines());
        var valve=(ContainmentPenetrationBlockEntity)level.getBlockEntity(sleeve);
        valve.energy().receiveEnergy(20_000,false);
        var returnValve=(ContainmentPenetrationBlockEntity)level.getBlockEntity(returnSleeve);
        returnValve.energy().receiveEnergy(20_000,false);
        // The production reactor calculates these after measuring its external
        // recirculation pump. Keep that legitimate operating state during the test.
        var pumps=ReactorControllerBlockEntity.class.getDeclaredField("connectedExternalPumps");
        var fraction=ReactorControllerBlockEntity.class.getDeclaredField("deliveredRecirculationFraction");
        pumps.setAccessible(true);fraction.setAccessible(true);
        h.runAfterDelay(80,()->{
            try {pumps.setInt(reactor,1);fraction.setDouble(reactor,1.0);}
            catch(ReflectiveOperationException error){throw new AssertionError(error);}
            h.assertTrue(valve.position()>.99,"water sleeve did not open");
            h.assertTrue(returnValve.position()>.99
                            && RecirculationCircuit.controller(level,pumpPos)==reactor,
                    "fixture outside pump did not return to the same reactor");
            h.assertTrue(containment.bypassFlowFraction(reactor)==0,
                    "closed recirculation loop was counted as environmental bypass: "
                            +containment.bypassFlowFraction(reactor));
            level.setBlock(sink,BwrBlocks.WATER_DISCHARGE_PORT.get().defaultBlockState()
                    .setValue(WaterDischargeBlock.FACING,Direction.EAST),3);
            IFluidHandler inlet=WaterLineNetwork.inlet(level,pipe);
            int moved=inlet.fill(new FluidStack(Fluids.WATER,1000),IFluidHandler.FluidAction.EXECUTE);
            h.assertTrue(moved>0,"fixture failed to move water through sleeve to discharge");
            h.assertTrue(containment.bypassFlowFraction(reactor)>0,
                    "measured discharge flow did not count as water bypass");
            valve.setDemandOpen(false);
        });
        h.runAfterDelay(170,()->{
            h.assertTrue(valve.position()==0&&containment.bypassFlowFraction(reactor)==0,
                    "isolated water sleeve still counted as bypass");
            for(int cx=lo.getX()>>4;cx<=(hi.getX()+10)>>4;cx++)
                for(int cz=lo.getZ()>>4;cz<=(hi.getZ()+10)>>4;cz++)level.setChunkForced(cx,cz,false);
            h.succeed();
        });
    }
}
