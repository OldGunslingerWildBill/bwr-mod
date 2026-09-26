package dev.bwr.mod.devtest;

import dev.bwr.mod.gui.ServiceMenu;
import dev.bwr.mod.eccs.AdsControllerBlockEntity;
import dev.bwr.mod.steam.SafetyReliefValveBlockEntity;
import dev.bwr.mod.reactor.*;
import dev.bwr.mod.registry.BwrBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.*;

@GameTestHolder("bwr") @PrefixGameTestTemplate(false)
public final class SpargerRegressionTests {
    static void validate(ReactorControllerBlockEntity be)throws Exception {
        var method=ReactorControllerBlockEntity.class.getDeclaredMethod("revalidate",Level.class);
        method.setAccessible(true);method.invoke(be,be.getLevel());
    }
    static void rings(ServerLevel level,ReactorControllerBlockEntity be) {
        var min=be.structure().interiorMin();var max=be.structure().interiorMax();
        for(var loop:CoreSpraySpargerBlock.Loop.values()) {
            int y=CoreSpraySpargerBlock.requiredY(loop,be.structure().topOfActiveFuelY());
            for(int x=min.getX();x<=max.getX();x++)for(int z=min.getZ();z<=max.getZ();z++)
                if(x==min.getX()||x==max.getX()||z==min.getZ()||z==max.getZ())
                    level.setBlock(new BlockPos(x,y,z),BwrBlocks.CORE_SPRAY_SPARGER.get().defaultBlockState().setValue(CoreSpraySpargerBlock.LOOP,loop),2);
        }
    }
    @GameTest(template="empty",timeoutTicks=400)
    public static void ringVisualSnapshotTracksPhysicalCapacity(GameTestHelper h)throws Exception {
        var l=h.getLevel();int n=0;
        for(var size:new int[][]{{5,5,8},{15,15,20},{21,21,22},{7,11,14}}) {
            var be=CompactCoreRegressionTests.build(l,new BlockPos(18000+n++*64,190,18000),size[0],size[1],size[2],false);
            h.assertTrue(be.isFormed(),"sparger fixture failed");rings(l,be);validate(be);
            int expected=2*(2*size[0]+2*size[1]-4);
            var e=VesselAppearance.read(be.getUpdateTag(l.registryAccess()));
            h.assertTrue(e!=null&&e.spargers().size()==expected,"complete ring snapshot wrong");
            h.assertTrue(be.structure().sprayRingCompleteness()==1,"complete ring lost capacity");
            var p=e.spargers().getFirst().pos();l.removeBlock(p,false);validate(be);
            var partial=VesselAppearance.read(be.getUpdateTag(l.registryAccess()));
            h.assertTrue(be.isFormed()&&partial!=null&&partial.spargers().size()==expected-1,"missing segment not reflected visually");
            h.assertTrue(Math.abs(be.structure().sprayRingCompleteness()-(expected-1.0)/expected)<1e-12,"damage capacity changed");
            l.setBlock(p,BwrBlocks.CORE_SPRAY_SPARGER.get().defaultBlockState().setValue(CoreSpraySpargerBlock.LOOP,e.spargers().getFirst().loop()),2);validate(be);
            h.assertTrue(e.equals(VesselAppearance.read(be.getUpdateTag(l.registryAccess()))),"repaired snapshot not restored");
            var tag=be.getUpdateTag(l.registryAccess());tag.remove("VisualSpargers_lpcs");tag.remove("VisualSpargers_hpcs");
            h.assertTrue(VesselAppearance.read(tag).spargers().isEmpty(),"legacy snapshot failed");
            var breach=be.structure().interiorMin().west().above();l.setBlock(breach,Blocks.AIR.defaultBlockState(),2);validate(be);
            h.assertTrue(!be.isFormed()&&VesselAppearance.read(be.getUpdateTag(l.registryAccess()))==null,"broken vessel retained ring");
            l.removeBlock(be.getBlockPos(),false);
        }
        h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=200)
    public static void genericDivisionPanelAndLegacySettings(GameTestHelper h) {
        var l=h.getLevel();var p=new BlockPos(18300,220,18300);l.getChunk(p);
        var player=net.neoforged.neoforge.common.util.FakePlayerFactory.getMinecraft(l);
        player.setPos(p.getX()+.5,p.getY(),p.getZ()+.5);
        for(var block:java.util.List.of(BwrBlocks.ADS_CONTROLLER.get(),BwrBlocks.ADS_RELIEF_VALVE.get())) {
            l.setBlock(p,block.defaultBlockState(),2);var be=l.getBlockEntity(p);
            java.util.function.IntSupplier division=()->be instanceof AdsControllerBlockEntity a?a.getDivision():((SafetyReliefValveBlockEntity)be).getDivision();
            h.assertTrue(division.getAsInt()==1,"new hardware must default to division 1");
            var menu=new ServiceMenu(30,player.getInventory(),p);
            for(int expected:new int[]{2,3,4,1,2}) {menu.handleCommand(player,2,0,0);h.assertTrue(division.getAsInt()==expected,"GUI division cycle escaped 1-4");}
            var tag=be.saveWithoutMetadata(l.registryAccess());tag.putInt("Division",0);be.loadWithComponents(tag,l.registryAccess());
            h.assertTrue(division.getAsInt()==0,"legacy division 0 lost");
            menu.handleCommand(player,2,0,0);h.assertTrue(division.getAsInt()==1,"legacy division cannot enter new cycle");
            tag=be.saveWithoutMetadata(l.registryAccess());be.loadWithComponents(tag,l.registryAccess());
            h.assertTrue(division.getAsInt()==1,"division lost after reload");l.removeBlock(p,false);
        }
        h.succeed();
    }
}
