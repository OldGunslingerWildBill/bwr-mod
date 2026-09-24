package dev.bwr.mod.devtest;

import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.piping.PipeTopology;
import dev.bwr.mod.steam.*;
import dev.bwr.mod.condenser.*;
import dev.bwr.mod.cooling.*;
import dev.bwr.mod.water.*;
import dev.bwr.core.turbine.CoolingWaterUnit.Design;
import dev.bwr.core.thermal.Saturation;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.*;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;
import net.neoforged.neoforge.gametest.*;
import static net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.*;

/** First-release regressions: shared inventories, event-only geometry, thermal packets and live modem faces. */
@GameTestHolder("bwr") @PrefixGameTestTemplate(false)
public final class ReleaseRegressionTests {
    private static final net.minecraft.server.level.TicketType<net.minecraft.world.level.ChunkPos> PIPE_TICKET=
            net.minecraft.server.level.TicketType.create("bwr_pipe_lifecycle",java.util.Comparator.comparingLong(net.minecraft.world.level.ChunkPos::toLong));
    static void load(ServerLevel l,BlockPos p){for(int x=-2;x<=2;x++)for(int z=-2;z<=2;z++)l.getChunk((p.getX()>>4)+x,(p.getZ()>>4)+z);}
    static void near(GameTestHelper h,double a,double b,String reason){h.assertTrue(Math.abs(a-b)<1e-7*Math.max(1,Math.abs(b)),reason+": "+a+" != "+b);}
    private static void pipe(ServerLevel l,BlockPos a,BlockPos b,boolean steam){for(var p:BlockPos.betweenClosed(a,b))l.setBlock(p,(steam?BwrBlocks.PRESSURISED_TUBE.get():BwrBlocks.HIGH_PRESSURE_WATER_PIPE.get()).defaultBlockState(),2);}

    @GameTest(template="empty",timeoutTicks=200)
    public static void sharedCondenserTankSimulation(GameTestHelper h){
        var l=h.getLevel();var root=new BlockPos(8000,200,8000);load(l,root);l.removeBlock(root,false);
        var block=BwrBlocks.ARABELLE_CONDENSER.get();var s=block.defaultBlockState();l.setBlock(root,s,2);block.setPlacedBy(l,root,s,null,new ItemStack(block));
        var be=(CondenserBlockEntity)l.getBlockEntity(root);h.assertTrue(be!=null&&be.ready(),"condenser fixture incomplete");
        var cold=be.layout().ports.stream().filter(p->p.role()==CondenserBlock.Port.COLD).map(p->be.layout().world(root,Direction.NORTH,p).below()).toList();
        h.assertTrue(cold.size()==2,"expected two cold flanges");pipe(l,cold.get(0),cold.get(1),false);
        var inlet=WaterLineNetwork.inlet(l,cold.get(0));be.plant().restore(999900,0,0);
        var source=new FluidTank(1000);source.fill(ThermalWater.atTemperature(1000,45),EXECUTE);
        near(h,inlet.fill(source.getFluid(),SIMULATE),100,"two faces double-counted free space");near(h,be.plant().cold(),999900,"simulation changed storage");
        var moved=FluidUtil.tryFluidTransfer(inlet,source,1000,true);
        near(h,moved.getAmount(),100,"transfer reported wrong amount");near(h,source.getFluidAmount(),900,"shared tank deleted source water");near(h,be.plant().cold(),1000000,"shared receiver overfilled");
        double expected=(999900*Saturation.subcooledLiquidEnthalpyKJPerKg(13)+100*Saturation.subcooledLiquidEnthalpyKJPerKg(45))/1000000;
        near(h,be.plant().coldH(),expected,"incoming heat lost on shared fill");
        l.removeBlock(root,false);for(var p:BlockPos.betweenClosed(cold.get(0),cold.get(1)))l.removeBlock(p,false);h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=200)
    public static void topologyOnlyChangesOnEvents(GameTestHelper h){
        var l=h.getLevel();var start=new BlockPos(8200,200,8200);load(l,start);
        pipe(l,start,start.east(32),true);var end=start.east(33);l.setBlock(end,BwrBlocks.TURBINE_STEAM_OUTLET.get().defaultBlockState(),2);
        var graph=PipeTopology.get(l,start,null,true,false);long count=PipeTopology.buildCount();
        h.assertTrue(graph.ends().stream().anyMatch(n->n.pos().equals(end)),"static route missing end");
        h.assertTrue(graph.edges().size()<=3,"plain pipe run was not compressed");
        var data=(net.minecraft.world.level.storage.ServerLevelData)l.getLevelData();long time=l.getGameTime();
        try{for(int tick=1;tick<=1000;tick++){data.setGameTime(time+tick);h.assertTrue(PipeTopology.get(l,start,null,true,false)==graph,"static route rebuilt on a timer");}}
        finally{data.setGameTime(time);}
        h.assertTrue(PipeTopology.buildCount()==count,"geometry rebuild without an edit");
        var cut=start.east(16);l.setBlock(cut,Blocks.AIR.defaultBlockState(),18);
        h.assertTrue(PipeTopology.get(l,start,null,true,false).ends().isEmpty(),"flags-18 pipe removal retained path");
        l.setBlock(cut,BwrBlocks.TURBINE_CONTROL_VALVE.get().defaultBlockState().setValue(TurbineValveBlock.FACING,Direction.EAST),2);
        var v=(TurbineValveBlockEntity)l.getBlockEntity(cut);v.setTarget(1);v.stroke(10);
        var withValve=PipeTopology.get(l,start,null,true,false);count=PipeTopology.buildCount();
        h.assertTrue(withValve.ends().size()==1,"inline valve disconnected path");v.setTarget(.25);v.stroke(10);
        near(h,PipeTopology.routes(l,withValve).stream().filter(r->r.node().pos().equals(end)).findFirst().orElseThrow().opening(),.25,"valve opening stale");
        h.assertTrue(PipeTopology.buildCount()==count,"moving valve rebuilt geometry");
        l.setBlock(cut,l.getBlockState(cut).setValue(TurbineValveBlock.FACING,Direction.NORTH),2);
        h.assertTrue(PipeTopology.get(l,start,null,true,false).ends().isEmpty(),"same-block rotation retained path");
        for(var p:BlockPos.betweenClosed(start,end))l.removeBlock(p,false);h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=200)
    public static void closedIsolationBranchDoesNotThrottleHeader(GameTestHelper h){
        var l=h.getLevel();var source=new BlockPos(8400,200,8400);load(l,source);
        l.setBlock(source,BwrBlocks.RPV_STEAM_OUTLET.get().defaultBlockState(),2);pipe(l,source.east(),source.east(4),true);
        l.setBlock(source.east(5),BwrBlocks.TURBINE_STEAM_OUTLET.get().defaultBlockState(),2);
        var branch=source.east(2).north();l.setBlock(branch,BwrBlocks.MSIV.get().defaultBlockState(),2);
        var msiv=(MainSteamIsolationValveBlockEntity)l.getBlockEntity(branch);msiv.setDemandOpen(false);msiv.tickValve(10);
        near(h,SteamValveRouting.nozzleOpening(l,source),1,"unused closed MSIV shut open main header");
        var inline=source.east(3);l.setBlock(inline,BwrBlocks.MSIV.get().defaultBlockState().setValue(MainSteamIsolationValveBlock.FACING,Direction.EAST),2);
        var valve=(MainSteamIsolationValveBlockEntity)l.getBlockEntity(inline);valve.setDemandOpen(false);valve.tickValve(10);
        near(h,SteamValveRouting.nozzleOpening(l,source),0,"closed inline MSIV admitted steam");
        valve.energy().receiveEnergy(20_000,false);valve.setDemandOpen(true);valve.tickValve(2);near(h,valve.getPosition(),.5,"powered half stroke failed");near(h,SteamValveRouting.nozzleOpening(l,source),valve.getPosition(),"partial valve did not govern main path");
        valve.setDemandOpen(false);valve.tickValve(10);
        pipe(l,source.east(2).south(),source.east(2).south(2),true);pipe(l,source.east(2).south(2),source.east(4).south(2),true);pipe(l,source.east(4).south(),source.east(4).south(2),true);
        near(h,SteamValveRouting.nozzleOpening(l,source),1,"open parallel route was isolated by closed branch");
        for(var p:BlockPos.betweenClosed(source.north(),source.east(5).south(2)))l.removeBlock(p,false);h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=200)
    public static void thermalFluidPacketsAndReload(GameTestHelper h){
        var tank=new ThermalWaterTank(1000,32);tank.fill(ThermalWater.atTemperature(250,20),EXECUTE);tank.fill(ThermalWater.atTemperature(750,80),EXECUTE);
        double expected=(250*Saturation.subcooledLiquidEnthalpyKJPerKg(20)+750*Saturation.subcooledLiquidEnthalpyKJPerKg(80))/1000;
        near(h,ThermalWater.enthalpy(tank.getFluid()),expected,"water temperatures did not mix by energy");
        var preview=tank.drain(new FluidStack(Fluids.WATER,1000),SIMULATE);near(h,preview.getAmount(),1000,"plain-water filter rejected thermal packet");near(h,tank.getFluidAmount(),1000,"drain simulation mutated inventory");
        var saved=tank.writeToNBT(h.getLevel().registryAccess(),new net.minecraft.nbt.CompoundTag());var copy=new ThermalWaterTank(1000,32);copy.readFromNBT(h.getLevel().registryAccess(),saved);
        var output=copy.drain(1000,EXECUTE);near(h,ThermalWater.enthalpy(output),expected,"last drain/reload lost heat");near(h,copy.getFluidAmount(),0,"last drain retained water");
        var debtTank=new ThermalWaterTank(1000,32,()->.75);debtTank.setFluid(ThermalWater.atTemperature(1,80));debtTank.fill(ThermalWater.atTemperature(1,20),EXECUTE);
        near(h,ThermalWater.enthalpy(debtTank.getFluid()),(.25*Saturation.subcooledLiquidEnthalpyKJPerKg(80)+Saturation.subcooledLiquidEnthalpyKJPerKg(20))/1.25,"spent fractional water contributed heat twice");
        h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=200)
    public static void liveComputerFaceAfterControllerReplacement(GameTestHelper h)throws Exception {
        if(net.neoforged.fml.ModList.get().isLoaded("computercraft"))ReleaseCcChecks.liveComputerFaceAfterControllerReplacement(h);
        else h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=1000)
    public static void pipeChunkFrontierReconnects(GameTestHelper h){
        var l=h.getLevel();var start=new BlockPos(9007,220,9000);var end=start.east(9);load(l,start);
        pipe(l,start,end.west(),true);l.setBlock(end,BwrBlocks.TURBINE_STEAM_OUTLET.get().defaultBlockState(),2);
        var targetChunk=l.getChunkAt(end);var held=new net.minecraft.world.level.ChunkPos(start);
        l.getChunkSource().addRegionTicket(PIPE_TICKET,held,0,held);
        h.assertTrue(PipeTopology.get(l,start,null,true,false).ends().stream().anyMatch(n->n.pos().equals(end)),"initial chunk route missing");
        h.startSequence().thenWaitUntil(()->h.assertTrue(l.isLoaded(start)&&!l.isLoaded(end),"waiting for far pipe chunk to become unavailable"))
                .thenExecute(()->{
                    var be=targetChunk.getBlockEntity(end);var saved=be==null?null:be.saveWithFullMetadata(l.registryAccess());
                    l.unload(targetChunk);if(saved!=null)targetChunk.setBlockEntityNbt(saved);
                    h.assertTrue(PipeTopology.get(l,start,null,true,false).ends().stream().noneMatch(n->n.pos().equals(end)),"cached route reached unloaded chunk");
                    l.getChunkAt(end);
                    h.assertTrue(PipeTopology.get(l,start,null,true,false).ends().stream().anyMatch(n->n.pos().equals(end)),"loaded frontier failed to reconnect");
                    for(var p:BlockPos.betweenClosed(start,end))l.removeBlock(p,false);l.getChunkSource().removeRegionTicket(PIPE_TICKET,held,0,held);
                }).thenSucceed();
    }

    @GameTest(template="empty",timeoutTicks=200)
    public static void computerFacesCoverMachineRegistry(GameTestHelper h)throws Exception {
        if(net.neoforged.fml.ModList.get().isLoaded("computercraft"))ReleaseCcChecks.computerFacesCoverMachineRegistry(h);
        else h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=200)
    public static void oversizedPipeNetworksFailClosed(GameTestHelper h){
        var l=h.getLevel();var start=new BlockPos(9000,220,9500);var end=start.east(260);
        for(int i=0;i<=260;i++){l.getChunkAt(start.east(i));l.setBlock(start.east(i),BwrBlocks.PRESSURISED_TUBE.get().defaultBlockState(),2);}
        l.setBlock(start,BwrBlocks.RPV_STEAM_OUTLET.get().defaultBlockState(),2);l.setBlock(end,BwrBlocks.TURBINE_STEAM_OUTLET.get().defaultBlockState(),2);
        var graph=PipeTopology.get(l,start,null,true,false);h.assertTrue(graph.truncated(),"network limit was not enforced");
        h.assertTrue(PipeTopology.routes(l,graph).isEmpty(),"partial oversized graph transferred steam");near(h,SteamValveRouting.nozzleOpening(l,start),0,"oversized line reverted to open bare nozzle");
        for(var p:BlockPos.betweenClosed(start,end))l.removeBlock(p,false);h.succeed();
    }

}
