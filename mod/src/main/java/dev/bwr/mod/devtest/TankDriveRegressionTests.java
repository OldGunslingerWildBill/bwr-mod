package dev.bwr.mod.devtest;

import dev.bwr.mod.eccs.*;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.rods.*;
import dev.bwr.mod.water.WaterLineNetwork;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.server.level.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.gametest.*;
import static net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.*;

@GameTestHolder("bwr") @PrefixGameTestTemplate(false)
public final class TankDriveRegressionTests {
    private static final TicketType<ChunkPos> TICKET=TicketType.create("bwr_drive_tank_lifecycle",java.util.Comparator.comparingLong(ChunkPos::toLong));
    private static void load(ServerLevel l,BlockPos p){for(int x=-1;x<=2;x++)for(int z=-1;z<=2;z++)l.getChunk((p.getX()>>4)+x,(p.getZ()>>4)+z);}
    private static void near(GameTestHelper h,double a,double b,String message){h.assertTrue(Math.abs(a-b)<1e-7*Math.max(1,Math.abs(b)),message+": "+a+" != "+b);}
    private static ControlRodDriveBlockEntity drive(ServerLevel l,BlockPos p){
        l.setBlock(p,BwrBlocks.CONTROL_ROD_DRIVE.get().defaultBlockState(),2);
        var d=(ControlRodDriveBlockEntity)l.getBlockEntity(p);d.hardware().setEnergyStoredFe(0);d.hardware().setWaterStoredMb(0);return d;
    }
    private static int drops(ServerLevel l,BlockPos root){return l.getEntitiesOfClass(ItemEntity.class,new AABB(root).inflate(25)).stream().filter(e->e.getItem().is(BwrBlocks.CONDENSATE_STORAGE_TANK.get().asItem())).mapToInt(e->e.getItem().getCount()).sum();}
    private static void clearDrops(ServerLevel l,BlockPos root){l.getEntitiesOfClass(ItemEntity.class,new AABB(root).inflate(25)).forEach(ItemEntity::discard);}

    @GameTest(template="empty",timeoutTicks=400)
    public static void tankPlayerBreakPreservesCasings(GameTestHelper h){
        var l=h.getLevel();var root=new BlockPos(12000,200,12000);load(l,root);
        var player=net.neoforged.neoforge.common.util.FakePlayerFactory.getMinecraft(l);var oldMode=player.gameMode.getGameModeForPlayer();var oldPos=player.position();
        try{
            for(int scenario=0;scenario<6;scenario++){
                CondensateTankRuntimeCheck.clear(l,root);clearDrops(l,root);
                int d=scenario<4?3:7,height=scenario<4?3:8;
                player.setGameMode(GameType.SURVIVAL);player.setPos(root.getX()+20,root.getY()+25,root.getZ()+20);
                var tank=CondensateTankRuntimeCheck.buildShell(l,player,root,d,height);int paid=tank.paidBlocks;
                h.assertTrue(tank.assembled()&&tank.ready(),"tank fixture did not form");
                boolean creative=scenario==2,wrongTool=scenario==3;
                var clicked=scenario%2==0?root:root.offset(CondensateTankShape.port(d,Direction.NORTH));
                var oldPort=l.getCapability(Capabilities.FluidHandler.BLOCK,clicked,Direction.NORTH);
                player.setGameMode(creative?GameType.CREATIVE:GameType.SURVIVAL);
                player.setItemInHand(InteractionHand.MAIN_HAND,wrongTool?ItemStack.EMPTY:new ItemStack(Items.NETHERITE_PICKAXE));
                player.setPos(clicked.getX()+.5,clicked.getY()-1,clicked.getZ()-2);
                if(scenario==5)h.assertTrue(l.destroyBlock(clicked,true),"world destruction failed");
                else h.assertTrue(player.gameMode.destroyBlock(clicked),"player mining failed");
                int materials=CondensateTankRuntimeCheck.casings(l,root)+drops(l,root);
                h.assertTrue(materials==paid-(creative||wrongTool?1:0),"tank materials lost/duplicated in scenario "+scenario+": "+materials+" / "+paid);
                h.assertTrue(CondensateTankRuntimeCheck.casings(l,root)>0,"entire tank disappeared");
                for(var off:CondensateTankShape.get(d,height).cells.keySet()){
                    var s=l.getBlockState(root.offset(off));h.assertTrue(!s.is(BwrBlocks.CONDENSATE_STORAGE_TANK.get())||!s.getValue(CondensateStorageTankBlock.ASSEMBLED),"formed shell survived dismantling");
                }
                if(oldPort!=null)near(h,oldPort.fill(new FluidStack(Fluids.WATER,10),EXECUTE),0,"mined tank retained a fluid port");
            }
        }finally{CondensateTankRuntimeCheck.clear(l,root);clearDrops(l,root);player.setGameMode(oldMode);player.setPos(oldPos);player.setItemInHand(InteractionHand.MAIN_HAND,ItemStack.EMPTY);}
        h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=400)
    public static void crdGridSingleBottomSupply(GameTestHelper h){
        var l=h.getLevel();var root=new BlockPos(14000,220,14000);load(l,root);
        var drives=new java.util.ArrayList<ControlRodDriveBlockEntity>();
        for(int x=0;x<17;x++)for(int z=0;z<17;z++)drives.add(drive(l,root.offset(x,0,z)));
        var diagonal=drive(l,root.offset(-1,0,-1));drives.get(50).hardware().setHealth(.4);
        var energy=l.getCapability(Capabilities.EnergyStorage.BLOCK,root,Direction.DOWN);
        var water=l.getCapability(Capabilities.FluidHandler.BLOCK,root.offset(16,0,16),Direction.DOWN);
        h.assertTrue(energy!=null&&water!=null,"bottom faces lack supply capabilities");
        near(h,energy.getMaxEnergyStored(),289*20000,"one energy inlet cannot reach full 17x17 grid");
        near(h,water.getTankCapacity(0),289*4000,"one water inlet cannot reach full grid");
        near(h,energy.receiveEnergy(289*2000,true),289*2000,"energy simulation limit");
        near(h,water.fill(new FluidStack(Fluids.WATER,289*100),SIMULATE),289*100,"water simulation limit");
        near(h,energy.getEnergyStored(),0,"simulate changed drive power");near(h,water.getFluidInTank(0).getAmount(),0,"simulate changed drive water");
        near(h,energy.receiveEnergy(289*2000,false),289*2000,"actual grid power");near(h,water.fill(new FluidStack(Fluids.WATER,289*100),EXECUTE),289*100,"actual grid water");
        near(h,water.fill(new FluidStack(Fluids.LAVA,10),EXECUTE),0,"drive accepted lava");
        for(var d:drives){near(h,d.energyStoredFe(),2000,"remote drive starved of FE");near(h,d.waterStoredMb(),100,"remote drive starved of water");}
        near(h,diagonal.energyStoredFe(),0,"diagonal drives were connected");near(h,diagonal.waterStoredMb(),0,"diagonal water crossed air gap");
        long builds=ControlRodDriveSupplies.buildCount(),time=l.getGameTime();var data=(net.minecraft.world.level.storage.ServerLevelData)l.getLevelData();
        try{
            for(int tick=0;tick<100;tick++){data.setGameTime(time+tick+1);for(var d:drives)ControlRodDriveBlockEntity.serverTick(l,d.getBlockPos(),d.getBlockState(),d);}
        }finally{data.setGameTime(time);}
        near(h,drives.stream().mapToDouble(ControlRodDriveBlockEntity::energyStoredFe).sum(),289*1500,"grid energy not conserved");
        near(h,drives.stream().mapToDouble(ControlRodDriveBlockEntity::waterStoredMb).sum(),289*90,"grid water not conserved");
        h.assertTrue(drives.stream().allMatch(ControlRodDriveBlockEntity::canPerformNormalMotion),"supplied remote drive cannot move");
        near(h,drives.get(50).health(),.4,"supply manifold changed independent drive wear");
        h.assertTrue(ControlRodDriveSupplies.buildCount()==builds,"drive graph rebuilt as time passed");
        // BWR pipes may tee into two drive faces, but must reserve shared capacity only once.
        for(var d:drives)d.hardware().setWaterStoredMb(4000);drives.get(0).hardware().setWaterStoredMb(3990);
        for(int z=0;z<=1;z++)l.setBlock(root.offset(0,-1,z),BwrBlocks.HIGH_PRESSURE_WATER_PIPE.get().defaultBlockState(),2);
        var inlet=WaterLineNetwork.inlet(l,root.below());
        near(h,inlet.fill(new FluidStack(Fluids.WATER,100),SIMULATE),10,"pipe tee double reserved manifold space");
        near(h,inlet.fill(new FluidStack(Fluids.WATER,100),EXECUTE),10,"pipe tee lost actual fill");
        near(h,inlet.fill(new FluidStack(Fluids.WATER,1),EXECUTE),0,"full manifold accepted excess water");
        for(var d:drives)l.removeBlock(d.getBlockPos(),false);l.removeBlock(diagonal.getBlockPos(),false);l.removeBlock(root.below(),false);l.removeBlock(root.below().south(),false);
        near(h,energy.receiveEnergy(100,false),0,"removed inlet accepted power");near(h,water.fill(new FluidStack(Fluids.WATER,100),EXECUTE),0,"removed water inlet accepted fluid");
        h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=200)
    public static void crdSplitRejoinAndReload(GameTestHelper h){
        var l=h.getLevel();var root=new BlockPos(14400,220,14400);load(l,root);
        var a=drive(l,root);var bridge=drive(l,root.east());var b=drive(l,root.east(2));var vertical=drive(l,root.east(2).above());
        var energy=l.getCapability(Capabilities.EnergyStorage.BLOCK,root,Direction.DOWN);var water=l.getCapability(Capabilities.FluidHandler.BLOCK,root.east(2).above(),Direction.UP);
        near(h,energy.getMaxEnergyStored(),80000,"vertical manifold not joined");energy.receiveEnergy(4000,false);water.fill(new FluidStack(Fluids.WATER,400),EXECUTE);
        var tag=b.saveWithFullMetadata(l.registryAccess());var state=b.getBlockState();l.removeBlockEntity(b.getBlockPos());
        var restored=(ControlRodDriveBlockEntity)BlockEntity.loadStatic(b.getBlockPos(),state,tag,l.registryAccess());l.setBlockEntity(restored);
        near(h,energy.getEnergyStored(),4000,"drive reload lost/duplicated energy");near(h,water.getFluidInTank(0).getAmount(),400,"reload lost water");
        l.removeBlock(bridge.getBlockPos(),false);near(h,energy.getMaxEnergyStored(),20000,"broken bridge still supplied far bank");
        double before=restored.energyStoredFe();energy.receiveEnergy(5000,false);near(h,restored.energyStoredFe(),before,"energy crossed removed bridge");
        h.assertTrue(b.energyStoredFe()==1000,"retained handler mutated replaced BE");
        near(h,water.getTankCapacity(0),8000,"far side did not form independent bank");
        drive(l,root.east());near(h,energy.getMaxEnergyStored(),80000,"repaired bridge remained split");
        near(h,energy.getEnergyStored(),8000,"bridge repair created free energy");
        for(var p:java.util.List.of(root,root.east(),root.east(2),root.east(2).above()))l.removeBlock(p,false);h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=1000)
    public static void crdChunkSupplyReconnects(GameTestHelper h){
        var l=h.getLevel();var start=new BlockPos(15004,220,15000);var end=start.east(9);load(l,start);
        for(int x=0;x<=9;x++)drive(l,start.east(x));
        var held=new ChunkPos(start);l.getChunkSource().addRegionTicket(TICKET,held,0,held);
        var energy=l.getCapability(Capabilities.EnergyStorage.BLOCK,start,Direction.DOWN);
        near(h,energy.getMaxEnergyStored(),200000,"initial chunk manifold");
        var far=(ControlRodDriveBlockEntity)l.getBlockEntity(end);
        h.startSequence().thenWaitUntil(()->h.assertTrue(l.isLoaded(start)&&!l.isLoaded(end),"waiting for CRD far chunk unload"))
            .thenExecute(()->{
                double before=far.energyStoredFe();near(h,energy.getMaxEnergyStored(),80000,"unloaded drives retained capacity");energy.receiveEnergy(1000,false);
                near(h,far.energyStoredFe(),before,"supply wrote to unloaded drive");h.assertTrue(!l.isLoaded(end),"manifold forced far chunk to load");
                l.getChunkAt(end);near(h,energy.getMaxEnergyStored(),200000,"loaded drive frontier did not reconnect");
                for(int x=0;x<=9;x++)l.removeBlock(start.east(x),false);l.getChunkSource().removeRegionTicket(TICKET,held,0,held);
            }).thenSucceed();
    }

    @GameTest(template="empty",timeoutTicks=1000)
    public static void tankDismantleAcrossUnloadedChunk(GameTestHelper h){
        // Mine inside the held chunk: vanilla mining itself notifies immediate neighbours.
        // The rest of the shell reaches into the unloaded chunk, exercising our restoration.
        var l=h.getLevel();var fixture=h.absolutePos(new BlockPos(2048,0,2048));
        // Fresh region per server run: item entities from a previously failed run can load
        // asynchronously after its chunks and must not contaminate conservation assertions.
        var root=new BlockPos(Math.floorDiv(fixture.getX(),16)*16+13,220,Math.floorDiv(fixture.getZ(),16)*16+8);load(l,root);
        CondensateTankDismantling.get(l).process(l);CondensateTankRuntimeCheck.clear(l,root);clearDrops(l,root);
        var player=net.neoforged.neoforge.common.util.FakePlayerFactory.getMinecraft(l);player.setGameMode(GameType.CREATIVE);player.setPos(15650,250,15650);
        var tank=CondensateTankRuntimeCheck.buildShell(l,player,root,7,8);h.assertTrue(tank.assembled(),"cross-chunk tank fixture");int paid=tank.paidBlocks;
        var held=new ChunkPos(root);l.getChunkSource().addRegionTicket(TICKET,held,0,held);var far=root.east(3);
        h.startSequence().thenWaitUntil(()->h.assertTrue(l.isLoaded(root)&&!l.isLoaded(far),"waiting for tank far chunk unload"))
            .thenExecute(()->{
                h.assertTrue(l.destroyBlock(root,true),"tank root not broken");
                h.assertTrue(!l.isLoaded(far),"dismantling loaded remote chunk");
                var data=CondensateTankDismantling.get(l);var saved=data.save(new net.minecraft.nbt.CompoundTag(),l.registryAccess());
                h.assertTrue(!saved.getList("Plans",10).isEmpty(),"unloaded casing restoration was not saved");
                try{
                    var read=CondensateTankDismantling.class.getDeclaredMethod("load",net.minecraft.nbt.CompoundTag.class,HolderLookup.Provider.class);read.setAccessible(true);
                    var restored=read.invoke(null,saved,l.registryAccess());var field=CondensateTankDismantling.class.getDeclaredField("pending");field.setAccessible(true);
                    var active=(java.util.Map)field.get(data);active.clear();active.putAll((java.util.Map)field.get(restored));
                }catch(Exception e){throw new RuntimeException(e);}
                l.getChunkAt(far);data.process(l);
                System.out.println("TANK DEFERRED RECOVERY: casings="+CondensateTankRuntimeCheck.casings(l,root)+" drops="+drops(l,root)+" paid="+paid);
                near(h,CondensateTankRuntimeCheck.casings(l,root)+drops(l,root),paid,"deferred dismantling lost/duplicated paid materials");
                h.assertTrue(data.save(new net.minecraft.nbt.CompoundTag(),l.registryAccess()).getList("Plans",10).isEmpty(),"completed dismantle remained pending");
                CondensateTankRuntimeCheck.clear(l,root);clearDrops(l,root);l.getChunkSource().removeRegionTicket(TICKET,held,0,held);
            }).thenSucceed();
    }
}
