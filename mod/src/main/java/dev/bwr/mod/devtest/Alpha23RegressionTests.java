package dev.bwr.mod.devtest;

import dev.bwr.mod.reactor.*;
import dev.bwr.mod.registry.BwrBlocks;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.*;

@GameTestHolder("bwr") @PrefixGameTestTemplate(false)
public final class Alpha23RegressionTests {
    @GameTest(template="empty",timeoutTicks=200)
    public static void smallestVesselHeadAlsoOpens(GameTestHelper h)throws Exception {
        var l=h.getLevel();var min=new BlockPos(24500,190,24400);
        var be=CompactCoreRegressionTests.build(l,min,5,5,8,false);
        var roof=min.offset(2,8,2);be.setVesselState(VesselState.REFUELING);
        h.assertTrue(l.getBlockState(roof).getCollisionShape(l,roof).isEmpty(),"smallest head retains barrier");
        be.setVesselState(VesselState.SHUTDOWN);
        h.assertTrue(!l.getBlockState(roof).getCollisionShape(l,roof).isEmpty(),"smallest closed head has hole");
        h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=300)
    public static void openHeadAllowsPhysicalEntryAndRestores(GameTestHelper h)throws Exception {
        var l=h.getLevel();var min=new BlockPos(24300,190,24300);
        var be=CompactCoreRegressionTests.build(l,min,15,15,20,false);
        var root=be.getBlockPos();var roof=min.offset(7,20,7);
        var state=l.getBlockState(roof);
        h.assertTrue(state.is(BwrBlocks.REACTOR_VESSEL.get()),"wrong roof fixture");
        h.assertTrue(!state.getCollisionShape(l,roof).isEmpty(),"closed roof passable");
        var entity=EntityType.PIG.create(l);
        entity.setPos(roof.getX()+.5,roof.getY()+1.1,roof.getZ()+.5);
        entity.move(MoverType.SELF,new Vec3(0,-3,0));
        h.assertTrue(entity.getY()>=roof.getY()+1,"closed lid does not stop entity");
        h.assertTrue(be.setVesselState(VesselState.REFUELING)==null,"cold head cannot open");
        h.assertTrue(state.getShape(l,roof).isEmpty()&&state.getCollisionShape(l,roof).isEmpty(),"open roof still blocks collision/picking");
        entity.move(MoverType.SELF,new Vec3(0,-3,0));
        h.assertTrue(entity.getY()<roof.getY()-1,"entity cannot enter open reactor");
        h.assertTrue(!l.getBlockState(min.offset(-1,3,7)).getCollisionShape(l,min.offset(-1,3,7)).isEmpty(),"opening head removed vessel wall");
        var saved=be.saveWithFullMetadata(l.registryAccess());
        l.removeBlockEntity(root);
        be=(ReactorControllerBlockEntity)BlockEntity.loadStatic(root,l.getBlockState(root),saved,l.registryAccess());
        l.setBlockEntity(be);SpargerRegressionTests.validate(be);
        h.assertTrue(state.getCollisionShape(l,roof).isEmpty(),"reload restored invisible barrier");
        be.setVesselState(VesselState.SHUTDOWN);
        h.assertTrue(!state.getCollisionShape(l,roof).isEmpty(),"closing head failed to restore collision");
        be.core().getBoundaryStress().forceFailure(dev.bwr.core.boundary.BoundaryComponent.REACTOR_VESSEL_HEAD,1600);
        ReactorControllerBlockEntity.serverTick(l,root,l.getBlockState(root),be);
        h.assertTrue(state.getCollisionShape(l,roof).isEmpty(),"failed head still blocks entry");
        l.removeBlock(min.offset(7,3,-1),false);SpargerRegressionTests.validate(be);
        h.assertTrue(!state.getCollisionShape(l,roof).isEmpty(),"unformed vessel kept stale access index");
        h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=200)
    public static void spargersTurnAtAllFourCorners(GameTestHelper h) {
        var l=h.getLevel();int i=0;
        for(var d:Direction.Plane.HORIZONTAL) {
            var p=new BlockPos(24400+5*i++,220,24300);l.getChunk(p);
            // The development GameTest world persists between launches.
            for(var old:BlockPos.betweenClosed(p.offset(-1,0,-1),p.offset(1,0,1)))l.removeBlock(old,false);
            var s=BwrBlocks.CORE_SPRAY_SPARGER.get().defaultBlockState();
            l.setBlock(p,s,3);l.setBlock(p.relative(d),s,3);l.setBlock(p.relative(d.getClockWise()),s,3);
            var corner=l.getBlockState(p);
            h.assertTrue(corner.getValue(CoreSpraySpargerBlock.CORNER)&&corner.getValue(CoreSpraySpargerBlock.FACING)==d,"wrong elbow orientation "+d);
            var rotated=corner.rotate(net.minecraft.world.level.block.Rotation.CLOCKWISE_90);
            h.assertTrue(rotated.getValue(CoreSpraySpargerBlock.FACING)==d.getClockWise(),"corner rotation lost");
            for(var mirror:net.minecraft.world.level.block.Mirror.values()) {
                var mirrored=corner.mirror(mirror);var a=mirrored.getValue(CoreSpraySpargerBlock.FACING);
                var expected=java.util.Set.of(mirror.mirror(d),mirror.mirror(d.getClockWise()));
                h.assertTrue(expected.equals(java.util.Set.of(a,a.getClockWise())),"corner mirror broke endpoints");
            }
            l.removeBlock(p.relative(d.getClockWise()),false);
            h.assertTrue(!l.getBlockState(p).getValue(CoreSpraySpargerBlock.CORNER),"removed neighbor leaves phantom elbow");
            l.setBlock(p.relative(d.getOpposite()),s,3);
            h.assertTrue(!l.getBlockState(p).getValue(CoreSpraySpargerBlock.CORNER),"straight run became corner");
        }
        var p=new BlockPos(24450,220,24300);l.getChunk(p);
        l.removeBlock(p.east(),false);
        var block=BwrBlocks.CORE_SPRAY_SPARGER.get();
        l.setBlock(p,block.defaultBlockState().setValue(CoreSpraySpargerBlock.LOOP,CoreSpraySpargerBlock.Loop.HPCS),3);
        var player=net.neoforged.neoforge.common.util.FakePlayerFactory.getMinecraft(l);
        var context=new net.minecraft.world.item.context.BlockPlaceContext(player,net.minecraft.world.InteractionHand.MAIN_HAND,
                new net.minecraft.world.item.ItemStack(block.asItem()),new net.minecraft.world.phys.BlockHitResult(Vec3.atCenterOf(p),Direction.EAST,p,false));
        h.assertTrue(block.getStateForPlacement(context).getValue(CoreSpraySpargerBlock.LOOP)==CoreSpraySpargerBlock.Loop.HPCS,"placing next to HPCS resets loop");
        h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=100)
    public static void cherenkovRequiresWetActiveOrDecayingFuel(GameTestHelper h) {
        h.assertTrue(CherenkovAppearance.strength(0,true,true,12)==0,"unused fuel glows");
        h.assertTrue(CherenkovAppearance.strength(1,false,true,12)==0,"empty lattice glows");
        h.assertTrue(CherenkovAppearance.strength(1,true,false,12)==0,"dry reactor glows");
        h.assertTrue(CherenkovAppearance.strength(1,true,true,1.9)==0,"water below fuel glows");
        h.assertTrue(CherenkovAppearance.strength(Double.NaN,true,true,12)==0,"invalid power glows");
        double decay=CherenkovAppearance.strength(.06,true,true,12),operating=CherenkovAppearance.strength(1,true,true,12);
        h.assertTrue(decay>0&&operating>=decay,"decay glow/power response absent");
        h.assertTrue(CherenkovAppearance.strength(1,true,true,2.1)<operating,"coverage not reflected in glow");
        h.succeed();
    }
}
