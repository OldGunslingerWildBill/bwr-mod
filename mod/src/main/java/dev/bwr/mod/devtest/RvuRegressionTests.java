package dev.bwr.mod.devtest;

import dev.bwr.core.PhysicalConstants;
import dev.bwr.core.thermal.PressureVessel;
import dev.bwr.mod.reactor.*;
import dev.bwr.mod.registry.BwrBlocks;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.neoforged.neoforge.gametest.*;

@GameTestHolder("bwr") @PrefixGameTestTemplate(false)
public final class RvuRegressionTests {
    @GameTest(template="empty",timeoutTicks=400)
    public static void interfacesAndWaterSnapshots(GameTestHelper h)throws Exception {
        var l=h.getLevel();var min=new BlockPos(20000,190,20000);
        var be=CompactCoreRegressionTests.build(l,min,15,15,20,false);
        var water=min.offset(7,3,-1);var steam=min.offset(7,18,15);
        l.setBlock(water,BwrBlocks.RPV_WATER_INJECTION_PORT.get().defaultBlockState(),2);
        l.setBlock(steam,BwrBlocks.RPV_STEAM_OUTLET.get().defaultBlockState(),2);
        SpargerRegressionTests.validate(be);
        h.assertTrue(be.isFormed(),"New interfaces broke formation");
        h.assertTrue(((RpvWaterInjectionPortBlockEntity)l.getBlockEntity(water)).controller()==be,"Water inlet detached");
        h.assertTrue(be.structure().steamOutletPositions().contains(steam),"Steam outlet detached");
        var tag=be.getUpdateTag(l.registryAccess());var e=VesselAppearance.read(tag);
        h.assertTrue(e.kind(water)==VesselAppearance.PortKind.WATER&&e.kind(steam)==VesselAppearance.PortKind.STEAM
                &&e.kind(be.getBlockPos())==VesselAppearance.PortKind.CONTROLLER,"Typed interfaces lost on wire");
        h.assertTrue(e.face(water)==Direction.NORTH&&e.face(steam)==Direction.SOUTH,"Flange orientation changed");
        var core=be.core();var vessel=core.getPressureVessel();
        for(double level:new double[]{-400,-317,-240,-167,8,60}) {
            vessel.setCollapsedLevelIn(level);
            var next=be.getUpdateTag(l.registryAccess());
            h.assertTrue(Math.abs(next.getDouble("VisualWaterLevel")-core.getTwoPhaseLevelIn())<1e-10,"Not actual free surface");
            h.assertTrue(next.getBoolean("VisualWaterPresent"),"Wet vessel shown dry");
            h.assertTrue(e.equals(VesselAppearance.read(next)),"Water changes invalidate static steel geometry");
        }
        vessel.restoreLiquidMassKg(0);
        h.assertTrue(!be.getUpdateTag(l.registryAccess()).getBoolean("VisualWaterPresent"),"Empty vessel retains water plane");
        l.removeBlock(steam,false);SpargerRegressionTests.validate(be);
        h.assertTrue(!be.isFormed()&&VesselAppearance.read(be.getUpdateTag(l.registryAccess()))==null,"Broken port retains formed appearance");
        l.setBlock(steam,BwrBlocks.RPV_STEAM_OUTLET.get().defaultBlockState(),2);SpargerRegressionTests.validate(be);
        h.assertTrue(be.isFormed()&&be.core()==core,"RVU repair reset reactor inventory");
        l.setBlock(water,l.getBlockState(water).setValue(RpvWaterInjectionPortBlock.FACING,Direction.SOUTH),3);
        SpargerRegressionTests.validate(be);
        h.assertTrue(((RpvWaterInjectionPortBlockEntity)l.getBlockEntity(water)).controller()==null,"Inward flange accepts water");
        h.assertTrue(VesselAppearance.read(be.getUpdateTag(l.registryAccess())).kind(water)==VesselAppearance.PortKind.LEGACY,"Invalid water flange disguised as connected");
        h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=200)
    public static void waterSurfaceFitsAllVesselSizes(GameTestHelper h) {
        for(int w=7;w<=23;w++)for(int d=7;d<=23;d++)for(int height:new int[]{10,22,131}) {
            var e=new VesselAppearance.Envelope(BlockPos.ZERO,new BlockPos(w-1,height-1,d-1),java.util.List.of());
            h.assertTrue(Math.abs(VesselWaterGeometry.height(e,PressureVessel.BAF_ON_INSTRUMENT_SCALE_IN)-2)<1e-10,"BAF water/fuel mismatch");
            h.assertTrue(Math.abs(VesselWaterGeometry.height(e,PhysicalConstants.TAF_ON_INSTRUMENT_SCALE_IN)-(height-6.15))<1e-10,"TAF water/fuel mismatch");
            double prev=-1;
            for(double in=-530;in<=100;in+=5) {
                double y=VesselWaterGeometry.height(e,in),r=VesselWaterGeometry.radiusFraction(y);
                h.assertTrue(Double.isFinite(y)&&y>=prev&&y<=VesselWaterGeometry.rim(e)&&r>=0&&r<.448,"Water outside inner shell or non-monotonic");prev=y;
            }
        }
        h.succeed();
    }
}
