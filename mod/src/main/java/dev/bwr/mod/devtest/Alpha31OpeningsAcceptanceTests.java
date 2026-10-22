package dev.bwr.mod.devtest;

import dev.bwr.mod.containment.ContainmentAirlockBlock;
import dev.bwr.mod.containment.ContainmentAirlockBlockEntity;
import dev.bwr.mod.containment.ContainmentControllerBlockEntity;
import dev.bwr.mod.containment.ContainmentControllerRegistry;
import dev.bwr.mod.containment.ContainmentVaultDoorBlock;
import dev.bwr.mod.containment.ContainmentVaultDoorBlockEntity;
import dev.bwr.mod.reactor.ReactorControllerBlockEntity;
import dev.bwr.mod.registry.BwrBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Phase E integrated acceptance: the forced aperture reaches the reactor physics. */
@GameTestHolder("bwr")
@PrefixGameTestTemplate(false)
public final class Alpha31OpeningsAcceptanceTests {
    private Alpha31OpeningsAcceptanceTests(){}
    private static void hold(ServerLevel level,BlockPos center,int radius,boolean force){
        for(int x=(center.getX()-radius)>>4;x<=(center.getX()+radius)>>4;x++)
            for(int z=(center.getZ()-radius)>>4;z<=(center.getZ()+radius)>>4;z++)FixtureChunks.force(level, center, x, z, force);
    }
    private static void survey(ServerLevel level,ContainmentControllerBlockEntity c){
        c.markDirty();
        for(int i=0;i<64;i++){
            ContainmentControllerBlockEntity.serverTick(level,c.getBlockPos(),c.getBlockState(),c);
            if(!c.isScanning()&&(c.strengthReady()||!c.isSealed()))return;
        }
        throw new AssertionError("opening acceptance survey unfinished: "+c.statusLines());
    }
    @GameTest(template="empty",timeoutTicks=180)
    public static void vanillaDoorDoesNotSealContainment(GameTestHelper helper){
        ServerLevel level=helper.getLevel();BlockPos center=new BlockPos(45112,220,45112);
        hold(level,center,56,true);
        for(int x=-4;x<=4;x++)for(int z=-4;z<=4;z++){
            level.getChunk((center.getX()+x)>>4,(center.getZ()+z)>>4);
            for(int y=-4;y<=4;y++){
                boolean shell=Math.max(Math.max(Math.abs(x),Math.abs(y)),Math.abs(z))==4;
                level.setBlock(center.offset(x,y,z),(shell?Blocks.OBSIDIAN:Blocks.AIR).defaultBlockState(),2);
            }
        }
        level.setBlock(center,BwrBlocks.CONTAINMENT_CONTROLLER.get().defaultBlockState(),3);
        var controller=(ContainmentControllerBlockEntity)level.getBlockEntity(center);
        survey(level,controller);
        helper.assertTrue(controller.isSealed(),"reference room did not seal");
        BlockPos gap=center.offset(4,-1,0);
        level.setBlock(gap,Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.HALF,DoubleBlockHalf.LOWER),2);
        level.setBlock(gap.above(),Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.HALF,DoubleBlockHalf.UPPER),2);
        survey(level,controller);
        helper.assertTrue(!controller.isSealed()&&controller.leak()!=null
                        &&controller.leak().distManhattan(gap)<=1,
                "closed vanilla door was treated as a containment seal: "+controller.statusLines());
        hold(level,center,56,false);helper.succeed();
    }
    @GameTest(template="empty",timeoutTicks=300)
    public static void forcedAirlockIsAMediumReactorLeak(GameTestHelper helper) throws Exception {
        ServerLevel level=helper.getLevel();BlockPos origin=new BlockPos(44912,190,44912);
        hold(level,origin,56,true);
        // Stone chamber holds an 11x11 formed reactor and a two-door passage.
        for(int x=-4;x<=14;x++)for(int z=-4;z<=14;z++){
            level.getChunk((origin.getX()+x)>>4,(origin.getZ()+z)>>4);
            for(int y=-4;y<=11;y++){
                boolean shell=x==-4||x==14||z==-4||z==14||y==-4||y==11;
                level.setBlock(origin.offset(x,y,z),(shell?Blocks.OBSIDIAN:Blocks.AIR).defaultBlockState(),2);
            }
        }
        ReactorControllerBlockEntity reactor=CompactCoreRegressionTests.build(level,origin,11,11,8,false);
        helper.assertTrue(reactor.isFormed(),"acceptance vessel did not form");
        BlockPos root=origin.offset(14,1,5);
        var block=BwrBlocks.CONTAINMENT_AIRLOCK.get();
        var state=block.defaultBlockState().setValue(ContainmentAirlockBlock.FACING,Direction.EAST);
        for(int x=15;x<=16;x++)for(int z=4;z<=6;z++)for(int y=0;y<=3;y++){
            boolean shell=z==4||z==6||y==0||y==3;
            level.setBlock(origin.offset(x,y,z),(shell?Blocks.OBSIDIAN:Blocks.AIR).defaultBlockState(),2);
        }
        for(int i=0;i<6;i++)level.setBlock(block.cell(root,Direction.EAST,i),Blocks.AIR.defaultBlockState(),2);
        level.setBlock(root,state,3);block.setPlacedBy(level,root,state,null,net.minecraft.world.item.ItemStack.EMPTY);
        var door=(ContainmentAirlockBlockEntity)level.getBlockEntity(root);
        BlockPos cpos=origin.offset(-2,0,-2);
        level.setBlock(cpos,BwrBlocks.CONTAINMENT_CONTROLLER.get().defaultBlockState(),3);
        var controller=(ContainmentControllerBlockEntity)level.getBlockEntity(cpos);
        survey(level,controller);
        helper.assertTrue(controller.isSealed()&&ContainmentControllerRegistry.linkedReactor(level,reactor.getBlockPos())==controller,
                "acceptance reactor not linked to closed airlock room: "+controller.statusLines());
        ReactorControllerBlockEntity.serverTick(level,reactor.getBlockPos(),reactor.getBlockState(),reactor);
        helper.assertTrue(reactor.core().getContainment().getLeakArea()==0,"closed airlock leaked into reactor physics");
        door.setComputerControlled(true);
        helper.assertTrue(door.setInnerOpen(true,false),"inner leaf could not open");
        survey(level,controller);
        helper.assertTrue(controller.isSealed(),"one open leaf unsealed the chamber");
        door.setOverride(true);
        survey(level,controller);
        ReactorControllerBlockEntity.serverTick(level,reactor.getBlockPos(),reactor.getBlockState(),reactor);
        helper.assertTrue(!controller.isSealed()&&reactor.core().getContainment().getLeakArea()==0.35,
                "forced airlock did not produce medium leak: "+controller.statusLines()
                        +" actual="+reactor.core().getContainment().getLeakArea());
        door.setOverride(false);door.setInnerOpen(false,false);door.setOuterOpen(false,false);
        survey(level,controller);
        ReactorControllerBlockEntity.serverTick(level,reactor.getBlockPos(),reactor.getBlockState(),reactor);
        helper.assertTrue(controller.isSealed()&&reactor.core().getContainment().getLeakArea()==0,
                "closed airlock did not restore reactor seal");
        hold(level,origin,56,false);helper.succeed();
    }
    @GameTest(template="empty",timeoutTicks=80)
    public static void vaultPowerAndStrokeSurviveNbt(GameTestHelper helper){
        var level=helper.getLevel();var root=new BlockPos(45012,220,45012);
        hold(level,root,5,true);
        var block=BwrBlocks.CONTAINMENT_VAULT_DOOR.get();
        var state=block.defaultBlockState().setValue(ContainmentVaultDoorBlock.FACING,Direction.NORTH);
        for(int i=0;i<9;i++)level.setBlock(block.cell(root,Direction.NORTH,i),Blocks.AIR.defaultBlockState(),2);
        level.setBlock(root,state,3);block.setPlacedBy(level,root,state,null,net.minecraft.world.item.ItemStack.EMPTY);
        var door=(ContainmentVaultDoorBlockEntity)level.getBlockEntity(root);
        door.energy().receiveEnergy(10_000,false);
        helper.assertTrue(door.setOpen(true,true),"vault stroke did not start");
        var saved=door.saveWithoutMetadata(level.registryAccess());
        var copy=new ContainmentVaultDoorBlockEntity(root,door.getBlockState());
        copy.loadWithComponents(saved,level.registryAccess());
        helper.assertTrue(copy.state()==ContainmentVaultDoorBlockEntity.State.OPENING
                        &&copy.energy().getEnergyStored()==2_000
                        &&saved.getLong("StrokeEnd")-saved.getLong("StrokeStart")==50,
                "vault state, energy or animation time failed NBT round-trip");
        hold(level,root,5,false);helper.succeed();
    }
}
