package dev.bwr.mod.devtest;

import dev.bwr.core.boundary.*;
import dev.bwr.mod.reactor.*;
import dev.bwr.mod.registry.BwrBlocks;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.gametest.*;

@GameTestHolder("bwr") @PrefixGameTestTemplate(false)
public final class Alpha26RegressionTests {
    private static void tick(ReactorControllerBlockEntity be) {
        ReactorControllerBlockEntity.serverTick(be.getLevel(),be.getBlockPos(),be.getBlockState(),be);
    }
    @GameTest(template="empty",timeoutTicks=200)
    public static void disconnectedRecircPortsUseActualElevationAndPersist(GameTestHelper h) throws Exception {
        var l=h.getLevel();var min=new BlockPos(34400,190,34400);
        var be=CompactCoreRegressionTests.build(l,min,9,9,12,false);
        var upper=min.offset(3,7,-1);var lower=min.offset(6,1,-1);
        l.setBlock(upper,BwrBlocks.RECIRCULATION_OUTLET.get().defaultBlockState()
                .setValue(RpvWaterInjectionPortBlock.FACING,Direction.NORTH),3);
        l.setBlock(lower,BwrBlocks.RECIRCULATION_INLET.get().defaultBlockState()
                .setValue(RpvWaterInjectionPortBlock.FACING,Direction.NORTH),3);
        SpargerRegressionTests.validate(be);tick(be);
        h.assertTrue(!be.core().getBoundaryStress().hasFailed(),"unused construction ports created a rupture");
        for(var p:java.util.List.of(upper,lower))
            l.setBlock(p.north(),BwrBlocks.HIGH_PRESSURE_WATER_PIPE.get().stateWithConnections(l,p.north()),3);
        tick(be);
        long builds=dev.bwr.mod.piping.PipeTopology.buildCount();tick(be);
        h.assertTrue(dev.bwr.mod.piping.PipeTopology.buildCount()==builds,"steady boundary checks rebuilt a pipe network");
        l.removeBlock(upper.north(),false);tick(be);
        h.assertTrue(be.core().getBoundaryStress().isBroken(BoundaryComponent.RECIRCULATION_LINE),"removed outlet pipe did not create LOCA");
        var a=be.core().getBoundaryStress().activeOpenings();
        h.assertTrue(a.size()==1,"upper break unexpectedly ruptured another port");
        l.removeBlock(lower.north(),false);tick(be);
        var both=be.core().getBoundaryStress().activeOpenings();
        h.assertTrue(both.size()==2,"second recirculation opening was lost");
        h.assertTrue(both.get(0).elevationIn()>both.get(1).elevationIn(),"port height not mapped to level scale");
        h.assertTrue(both.get(1).ratedLiquidKgPerS()>both.get(0).ratedLiquidKgPerS()*2,"lower inlet was not larger than upper outlet");
        var root=be.getBlockPos();var saved=be.saveWithFullMetadata(l.registryAccess());l.removeBlockEntity(root);
        be=(ReactorControllerBlockEntity)BlockEntity.loadStatic(root,l.getBlockState(root),saved,l.registryAccess());
        l.setBlockEntity(be);SpargerRegressionTests.validate(be);tick(be);
        h.assertTrue(be.core().getBoundaryStress().activeOpenings().equals(both),"reload changed nozzle elevations or break areas");
        h.assertTrue(be.core().getBoundaryStress().getRuptureVolumeM3()>0,"reload lost vessel blowdown volume");
        h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=200)
    public static void feedwaterAndSteamRemovalCreateIndependentLeaks(GameTestHelper h) throws Exception {
        var l=h.getLevel();var min=new BlockPos(34600,190,34600);
        var be=CompactCoreRegressionTests.build(l,min,9,9,12,false);
        var feed=min.offset(4,7,-1);var steam=min.offset(6,10,-1);
        l.setBlock(feed,BwrBlocks.RPV_WATER_INJECTION_PORT.get().defaultBlockState()
                .setValue(RpvWaterInjectionPortBlock.FACING,Direction.NORTH),3);
        l.setBlock(steam,BwrBlocks.RPV_STEAM_OUTLET.get().defaultBlockState(),3);
        l.setBlock(feed.north(),BwrBlocks.HIGH_PRESSURE_WATER_PIPE.get().stateWithConnections(l,feed.north()),3);
        l.setBlock(steam.north(),BwrBlocks.PRESSURISED_TUBE.get().stateWithConnections(l,steam.north()),3);
        SpargerRegressionTests.validate(be);tick(be);
        // Persist the connection ledger, then remove pipes while the controller is unloaded.
        var root=be.getBlockPos();var saved=be.saveWithFullMetadata(l.registryAccess());l.removeBlockEntity(root);
        l.removeBlock(feed.north(),false);l.removeBlock(steam.north(),false);
        be=(ReactorControllerBlockEntity)BlockEntity.loadStatic(root,l.getBlockState(root),saved,l.registryAccess());
        l.setBlockEntity(be);SpargerRegressionTests.validate(be);tick(be);
        h.assertTrue(be.core().getBoundaryStress().isBroken(BoundaryComponent.FEEDWATER_LINE),"unloaded feed pipe removal forgotten");
        h.assertTrue(be.core().getBoundaryStress().isBroken(BoundaryComponent.MAIN_STEAM_LINE),"unloaded steam pipe removal forgotten");
        h.assertTrue(be.getUpdateTag(l.registryAccess()).getLongArray("VisualBoundaryLeaks").length==2,"both leak plumes must sync");
        h.succeed();
    }
    @GameTest(template="empty")
    public static void portElevationMatchesWaterSurfaceForEveryVesselSize(GameTestHelper h) {
        for(int size:new int[]{7,17,23}) for(int height:new int[]{10,22,90}) {
            var e=new VesselAppearance.Envelope(BlockPos.ZERO,new BlockPos(size-1,height-1,size-1),java.util.List.of());
            for(double level:new double[]{-500,-317,-250,-167,-80,8,60}) {
                double y=VesselWaterGeometry.height(e,level);
                h.assertTrue(Math.abs(VesselWaterGeometry.levelAtHeight(e,y)-level)<1e-8,"water surface/nozzle elevation mismatch");
            }
        }
        h.succeed();
    }
}
