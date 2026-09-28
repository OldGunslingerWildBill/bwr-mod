package dev.bwr.mod.devtest;

import dev.bwr.mod.eccs.PumpAssemblyBlock;
import dev.bwr.mod.flow.RecirculationNetwork;
import dev.bwr.mod.reactor.*;
import dev.bwr.mod.registry.BwrBlocks;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.gametest.*;
import java.util.*;

@GameTestHolder("bwr") @PrefixGameTestTemplate(false)
public final class Alpha24RegressionTests {
    static void place(net.minecraft.server.level.ServerLevel l,BlockPos p,Direction facing,boolean narrow) {
        var b=BwrBlocks.JET_PUMP.get();
        var s=b.placementState().setValue(PumpAssemblyBlock.FACING,facing).setValue(dev.bwr.mod.flow.JetPumpBlock.NARROW,narrow);
        l.setBlock(p,s,3);b.setPlacedBy(l,p,s,null,new ItemStack(b));
    }
    @GameTest(template="empty",timeoutTicks=300)
    public static void jetsFormUnformAndReloadWithoutMovingOrLosingFlow(GameTestHelper h)throws Exception {
        var l=h.getLevel();var min=new BlockPos(24600,190,24600);
        var be=CompactCoreRegressionTests.build(l,min,15,15,20,false);
        var a=min.offset(0,0,5);var b=min.offset(14,0,5);
        var wall=min.offset(7,3,-1);var shell=l.getBlockState(wall);
        l.removeBlock(wall,false);SpargerRegressionTests.validate(be);
        place(l,a,Direction.EAST,true);place(l,b,Direction.WEST,true);
        h.assertTrue(!l.getBlockState(a.above(4)).getShape(l,a.above(4)).isEmpty(),"construction pump inaccessible");
        l.setBlock(wall,shell,3);SpargerRegressionTests.validate(be);
        var before=RecirculationNetwork.measure(l,be,List.of());
        h.assertTrue(before.pairedJets()==2&&before.unmatchedJets()==0,"pair lost on formation");
        var snapshot=VesselAppearance.read(be.getUpdateTag(l.registryAccess()));
        h.assertTrue(snapshot!=null&&snapshot.jets().size()==2,"jet positions not synced");
        for(var jet:snapshot.jets())for(var p:jet.cells()) {
            h.assertTrue(l.getBlockState(p).is(BwrBlocks.JET_PUMP.get()),"saved construction block moved");
            h.assertTrue(l.getBlockState(p).getShape(l,p).isEmpty()&&l.getBlockState(p).getCollisionShape(l,p).isEmpty(),"formed pump leaves invisible obstacle");
        }
        var root=be.getBlockPos();var saved=be.saveWithFullMetadata(l.registryAccess());l.removeBlockEntity(root);
        h.assertTrue(!VesselJetAccess.contains(l,a),"removed owner retained collision index");
        be=(ReactorControllerBlockEntity)BlockEntity.loadStatic(root,l.getBlockState(root),saved,l.registryAccess());l.setBlockEntity(be);
        SpargerRegressionTests.validate(be);
        h.assertTrue(VesselJetAccess.contains(l,a)&&RecirculationNetwork.measure(l,be,List.of()).pairedJets()==2,"reload lost mounted jets");
        l.removeBlock(wall,false);SpargerRegressionTests.validate(be);
        h.assertTrue(!VesselJetAccess.contains(l,a)&&!l.getBlockState(a).getCollisionShape(l,a).isEmpty(),"unforming did not restore construction model/collision");
        l.setBlock(wall,shell,3);SpargerRegressionTests.validate(be);
        h.assertTrue(VesselAppearance.read(be.getUpdateTag(l.registryAccess())).jets().size()==2,"reforming duplicated/lost jets");
        l.removeBlock(b.above(3),false);SpargerRegressionTests.validate(be);
        var remaining=VesselAppearance.read(be.getUpdateTag(l.registryAccess()));
        h.assertTrue(remaining.jets().size()==1&&!VesselJetAccess.contains(l,b),"removed assembly left ghost geometry");
        var after=RecirculationNetwork.measure(l,be,List.of());
        h.assertTrue(after.pairedJets()==0&&after.unmatchedJets()==1,"unmatched jet gained capacity");
        h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=300)
    public static void legacyAndInvalidJetConstructionRemainCompatible(GameTestHelper h)throws Exception {
        var l=h.getLevel();var min=new BlockPos(24700,190,24600);
        var be=CompactCoreRegressionTests.build(l,min,15,15,20,false);
        var legacy=min.offset(0,0,4);place(l,legacy,Direction.NORTH,false);
        var invalid=min.offset(7,3,7);place(l,invalid,Direction.WEST,true);
        SpargerRegressionTests.validate(be);
        var e=VesselAppearance.read(be.getUpdateTag(l.registryAccess()));
        h.assertTrue(e.jets().size()==1&&!e.jets().getFirst().narrow(),"legacy footprint lost or invalid jet hidden");
        h.assertTrue(e.jets().getFirst().cells().size()==12,"legacy footprint changed");
        for(var p:e.jets().getFirst().cells())h.assertTrue(VesselJetAccess.contains(l,p),"legacy child not concealed");
        h.assertTrue(!VesselJetAccess.contains(l,invalid)&&!l.getBlockState(invalid).getShape(l,invalid).isEmpty(),"invalid placement disguised as working jet");
        var tag=be.getUpdateTag(l.registryAccess());tag.remove("VisualJetRoots");tag.remove("VisualJetStates");
        h.assertTrue(VesselAppearance.read(tag).jets().isEmpty(),"old packet invented jets");
        be.onChunkUnloaded();h.assertTrue(!VesselJetAccess.contains(l,legacy),"chunk unload retained jet index");
        h.succeed();
    }
}
