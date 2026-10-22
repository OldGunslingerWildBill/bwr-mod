package dev.bwr.mod.devtest;

import dev.bwr.mod.containment.ContainmentAirlockBlock;
import dev.bwr.mod.containment.ContainmentAirlockBlockEntity;
import dev.bwr.mod.containment.ContainmentControllerBlockEntity;
import dev.bwr.mod.registry.BwrBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("bwr")
@PrefixGameTestTemplate(false)
public final class Alpha31AirlockTests {
    private Alpha31AirlockTests(){}
    private static void hold(ServerLevel level,BlockPos center,int radius,boolean force){
        for(int x=(center.getX()-radius)>>4;x<=(center.getX()+radius)>>4;x++)
            for(int z=(center.getZ()-radius)>>4;z<=(center.getZ()+radius)>>4;z++)FixtureChunks.force(level, center, x, z, force);
    }
    private static void survey(ServerLevel level,ContainmentControllerBlockEntity controller){
        controller.markDirty();
        for(int i=0;i<64;i++){
            ContainmentControllerBlockEntity.serverTick(level,controller.getBlockPos(),controller.getBlockState(),controller);
            if(!controller.isScanning()&&controller.strengthReady())return;
            if(!controller.isScanning()&&!controller.isSealed())return;
        }
        throw new AssertionError("airlock survey unfinished: "+controller.statusLines());
    }
    @GameTest(template="empty",timeoutTicks=200)
    public static void interlockAllowsWalkingThroughButOverrideLeaks(GameTestHelper helper){
        var level=helper.getLevel();var center=new BlockPos(44712,220,44712);
        hold(level,center,56,true);
        for(int x=-5;x<=5;x++)for(int z=-5;z<=5;z++){
            level.getChunk((center.getX()+x)>>4,(center.getZ()+z)>>4);
            for(int y=-5;y<=5;y++){
                boolean wall=Math.max(Math.max(Math.abs(x),Math.abs(y)),Math.abs(z))>=4;
                level.setBlock(center.offset(x,y,z),(wall?Blocks.OBSIDIAN:Blocks.AIR).defaultBlockState(),2);
            }
        }
        // The inner leaf replaces the inner wall. The chamber and outer leaf
        // run along +X; player-built stone surrounds its sides and ceiling.
        BlockPos root=center.offset(4,-1,0);
        var block=BwrBlocks.CONTAINMENT_AIRLOCK.get();
        var state=block.defaultBlockState().setValue(ContainmentAirlockBlock.FACING,Direction.EAST);
        for(int i=0;i<6;i++)level.setBlock(block.cell(root,Direction.EAST,i),Blocks.AIR.defaultBlockState(),2);
        level.setBlock(root,state,3);block.setPlacedBy(level,root,state,null,net.minecraft.world.item.ItemStack.EMPTY);
        var door=(ContainmentAirlockBlockEntity)level.getBlockEntity(root);
        level.setBlock(center,BwrBlocks.CONTAINMENT_CONTROLLER.get().defaultBlockState(),3);
        var controller=(ContainmentControllerBlockEntity)level.getBlockEntity(center);
        survey(level,controller);
        helper.assertTrue(controller.isSealed()&&controller.failurePressurePsig()==120,
                "closed airlock was not a 120-psig boundary: "+controller.statusLines());
        helper.assertTrue(door.setInnerOpen(true,false),"inner leaf did not open");
        helper.assertTrue(!door.setOuterOpen(true,false),"interlock permitted both leaves open");
        survey(level,controller);
        helper.assertTrue(controller.isSealed(),"passing into the chamber unsealed containment: "+controller.statusLines());
        helper.assertTrue(door.setInnerOpen(false,false)&&door.setOuterOpen(true,false),
                "sequential walk-through was blocked");
        survey(level,controller);
        helper.assertTrue(controller.isSealed(),"outer leaf alone leaked: "+controller.statusLines());
        var snapshot=door.saveWithoutMetadata(level.registryAccess());
        var restored=new ContainmentAirlockBlockEntity(root,door.getBlockState());
        restored.loadWithComponents(snapshot,level.registryAccess());
        helper.assertTrue(!restored.innerOpen()&&restored.outerOpen()&&!restored.damaged(),
                "airlock leaf states did not survive NBT round-trip");
        door.setComputerControlled(true);
        door.setOverride(true);
        survey(level,controller);
        helper.assertTrue(!controller.isSealed()&&door.innerOpen()&&door.outerOpen(),
                "forced dual-open airlock did not leak: "+controller.statusLines());
        helper.assertTrue(controller.isForcedAirlockLeak()
                        &&dev.bwr.mod.containment.ContainmentControllerRegistry.forcedAirlockFor(level,center)==controller,
                "forced airlock did not retain its surveyed medium-leak route: "+controller.statusLines());
        door.setOverride(false);
        helper.assertTrue(door.setInnerOpen(false,false)&&door.setOuterOpen(false,false),
                "dual-open override could not be reset by closing both leaves");
        survey(level,controller);
        helper.assertTrue(controller.isSealed(),"airlock failed to reseal after override was released");
        helper.assertTrue(controller.ruptureWeakest(level)!=null,"airlock was not considered the weakest spot");
        helper.assertTrue(door.damaged()&&!door.setOuterOpen(false,false),"blown airlock could be resealed");
        hold(level,center,56,false);helper.succeed();
    }
}
