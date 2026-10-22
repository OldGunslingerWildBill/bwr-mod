package dev.bwr.mod.devtest;

import dev.bwr.mod.containment.ContainmentControllerBlockEntity;
import dev.bwr.mod.containment.ContainmentVaultDoorBlock;
import dev.bwr.mod.containment.ContainmentVaultDoorBlockEntity;
import dev.bwr.mod.registry.BwrBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("bwr")
@PrefixGameTestTemplate(false)
public final class Alpha31VaultDoorTests {
    private Alpha31VaultDoorTests(){}
    private static void hold(ServerLevel level,BlockPos center,int radius,boolean force){
        for(int x=(center.getX()-radius)>>4;x<=(center.getX()+radius)>>4;x++)
            for(int z=(center.getZ()-radius)>>4;z<=(center.getZ()+radius)>>4;z++)FixtureChunks.force(level, center, x, z, force);
    }
    private static ContainmentControllerBlockEntity room(ServerLevel level,BlockPos center,int size){
        int radius=size+2;
        hold(level,center,radius,true);
        for(int x=-radius;x<=radius;x++)for(int z=-radius;z<=radius;z++){
            level.getChunk((center.getX()+x)>>4,(center.getZ()+z)>>4);
            for(int y=-radius;y<=radius;y++){
                boolean wall=Math.max(Math.max(Math.abs(x),Math.abs(y)),Math.abs(z))>=radius-1;
                level.setBlock(center.offset(x,y,z),(wall?Blocks.OBSIDIAN:Blocks.AIR).defaultBlockState(),2);
            }
        }
        level.setBlock(center,BwrBlocks.CONTAINMENT_CONTROLLER.get().defaultBlockState(),3);
        return (ContainmentControllerBlockEntity)level.getBlockEntity(center);
    }
    private static void survey(ServerLevel level,ContainmentControllerBlockEntity controller){
        controller.markDirty();
        for(int i=0;i<64;i++){
            ContainmentControllerBlockEntity.serverTick(level,controller.getBlockPos(),controller.getBlockState(),controller);
            if(!controller.isScanning()&&controller.strengthReady())return;
            if(!controller.isScanning()&&!controller.isSealed())return;
        }
        throw new AssertionError("vault room survey unfinished: "+controller.statusLines());
    }
    private static ContainmentVaultDoorBlockEntity install(ServerLevel level,BlockPos center,int size){
        int wall=size+1;
        var door=size==3?BwrBlocks.CONTAINMENT_VAULT_DOOR.get():BwrBlocks.CONTAINMENT_VAULT_DOOR_4X4.get();
        BlockPos root=center.offset(wall,-(size/2),-(size/2));
        var state=door.defaultBlockState().setValue(ContainmentVaultDoorBlock.FACING,Direction.EAST);
        for(int i=0;i<size*size;i++){
            BlockPos cell=door.cell(root,Direction.EAST,i);
            level.setBlock(cell,Blocks.AIR.defaultBlockState(),2);
            level.setBlock(cell.east(),Blocks.AIR.defaultBlockState(),2);
        }
        level.setBlock(root,state,3);
        door.setPlacedBy(level,root,state,null,net.minecraft.world.item.ItemStack.EMPTY);
        return (ContainmentVaultDoorBlockEntity)level.getBlockEntity(root);
    }
    @GameTest(template="empty",timeoutTicks=300)
    public static void closedDoorSealsPoweredSwingLeaksAndReseals(GameTestHelper helper){
        var level=helper.getLevel();var center=new BlockPos(44512,220,44512);
        var controller=room(level,center,3);var door=install(level,center,3);
        survey(level,controller);
        helper.assertTrue(controller.isSealed()&&controller.failurePressurePsig()==200.0,
                "closed vault did not seal at 200 psig: "+controller.statusLines());
        var power=level.getCapability(Capabilities.EnergyStorage.BLOCK,door.getBlockPos(),Direction.UP);
        helper.assertTrue(power!=null&&power.receiveEnergy(8000,false)==8000,"vault door has no FE face");
        // An open wall reaches the 200k-cell escape cap only after exploring
        // nearby exterior chunks; keep them loaded for this delayed far-room check.
        hold(level,center,56,true);
        helper.assertTrue(door.setOpen(true,true),"powered vault refused opening command");
        var poweredSave=door.saveWithoutMetadata(level.registryAccess());
        helper.assertTrue(poweredSave.getLong("StrokeEnd")-poweredSave.getLong("StrokeStart")==50,
                "powered vault swing is not 2.5 seconds");
        survey(level,controller);
        helper.assertTrue(!controller.isSealed()&&door.state()==ContainmentVaultDoorBlockEntity.State.OPENING,
                "swinging vault remained airtight: "+controller.statusLines()+" state="+door.state()
                +" root="+level.getBlockState(door.getBlockPos())+" outer="+level.getBlockState(door.getBlockPos().east()));
        helper.runAfterDelay(205,()->{
            helper.assertTrue(door.state()==ContainmentVaultDoorBlockEntity.State.OPEN,"powered swing did not finish in 10 s");
            helper.assertTrue(door.handwheelToggle(),"unpowered handwheel could not start closing");
            helper.assertTrue(door.state()==ContainmentVaultDoorBlockEntity.State.CLOSING,
                    "manual closing sealed before stroke finished");
            var manualSave=door.saveWithoutMetadata(level.registryAccess());
            helper.assertTrue(manualSave.getLong("StrokeEnd")-manualSave.getLong("StrokeStart")==160,
                    "manual handwheel stroke is not 8 seconds");
            door.jamBlownOpen();
            helper.assertTrue(door.state()==ContainmentVaultDoorBlockEntity.State.DAMAGED_JAMMED
                    &&!door.setOpen(false,false),"ruptured vault could be resealed");
            hold(level,center,56,false);helper.succeed();
        });
    }
    @GameTest(template="empty",timeoutTicks=120)
    public static void fourWideDoorHasAllSixteenCells(GameTestHelper helper){
        var level=helper.getLevel();var center=new BlockPos(44612,220,44612);
        var controller=room(level,center,4);var door=install(level,center,4);
        var block=(ContainmentVaultDoorBlock)door.getBlockState().getBlock();
        for(int i=0;i<16;i++){
            BlockPos pos=block.cell(door.getBlockPos(),Direction.EAST,i);
            helper.assertTrue(level.getBlockState(pos).is(block)
                    &&level.getBlockState(pos).getValue(ContainmentVaultDoorBlock.CELL)==i,
                    "4x4 vault cell missing at "+i);
        }
        survey(level,controller);
        helper.assertTrue(controller.isSealed()&&controller.failurePressurePsig()==200,
                "4x4 closed vault did not seal at 200 psig");
        helper.assertTrue(controller.ruptureWeakest(level)!=null
                &&door.state()==ContainmentVaultDoorBlockEntity.State.DAMAGED_JAMMED,
                "weakest-spot rupture did not blow open and jam the vault");
        hold(level,center,6,false);helper.succeed();
    }
}
