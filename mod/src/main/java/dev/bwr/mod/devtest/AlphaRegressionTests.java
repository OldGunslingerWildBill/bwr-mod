package dev.bwr.mod.devtest;

import dev.bwr.mod.eccs.*;
import dev.bwr.mod.reactor.*;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.water.*;
import dev.bwr.mod.steam.*;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.gametest.*;
import static net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.*;

@GameTestHolder("bwr") @PrefixGameTestTemplate(false)
public final class AlphaRegressionTests {
    static void load(ServerLevel l,BlockPos p){for(int x=-2;x<=2;x++)for(int z=-2;z<=2;z++)l.getChunk((p.getX()>>4)+x,(p.getZ()>>4)+z);}
    static void near(GameTestHelper h,double a,double b,String why){h.assertTrue(Math.abs(a-b)<1e-7*Math.max(1,Math.abs(b)),why+": "+a+" != "+b);}
    static void place(ServerLevel l,PumpAssemblyBlock b,BlockPos p,Direction d){l.removeBlock(p,false);var s=b.placementState().setValue(PumpAssemblyBlock.FACING,d);l.setBlock(p,s,2);b.setPlacedBy(l,p,s,null,new ItemStack(b));}
    static void pipe(ServerLevel l,BlockPos a,BlockPos b){for(var p:BlockPos.betweenClosed(a,b))l.setBlock(p,BwrBlocks.HIGH_PRESSURE_WATER_PIPE.get().defaultBlockState(),2);}
    @GameTest(template="empty",timeoutTicks=400)
    public static void finiteSlcCrossTieAndPower(GameTestHelper h)throws Exception{
        var l=h.getLevel();var o=new BlockPos(11000,200,11000);load(l,o);
        var build=PhaseOneRegressionTests.class.getDeclaredMethod("buildVessel",ServerLevel.class,BlockPos.class,int.class);build.setAccessible(true);build.invoke(null,l,o,11);
        var cp=o.offset(-1,3,2);l.setBlock(cp,BwrBlocks.REACTOR_CONTROLLER.get().defaultBlockState(),2);
        var reactor=(ReactorControllerBlockEntity)l.getBlockEntity(cp);
        var nozzle=o.offset(11,0,5);l.setBlock(nozzle,BwrBlocks.RPV_WATER_INJECTION_PORT.get().defaultBlockState().setValue(RpvWaterInjectionPortBlock.FACING,Direction.EAST),2);
        var validate=reactor.getClass().getDeclaredMethod("revalidate",Level.class);validate.setAccessible(true);validate.invoke(reactor,l);
        h.assertTrue(reactor.isFormed(),"SLC reactor fixture not formed");
        var pumpPos=o.offset(17,0,5);place(l,BwrBlocks.SLC_PUMP.get(),pumpPos,Direction.SOUTH);
        var tankPos=o.offset(23,0,5);place(l,BwrBlocks.SLC_BORON_TANK.get(),tankPos,Direction.WEST);
        pipe(l,o.offset(12,0,5),o.offset(15,0,5));pipe(l,o.offset(19,0,5),o.offset(21,0,5));
        var pump=(EccsPumpBlockEntity)l.getBlockEntity(pumpPos);var tank=(SlcTankBlockEntity)l.getBlockEntity(tankPos);
        var port=BwrBlocks.SLC_BORON_TANK.get().portPosition(tankPos,tank.getBlockState(),AssemblyPort.WATER_SUCTION);
        var fill=l.getCapability(Capabilities.FluidHandler.BLOCK,port,Direction.UP);h.assertTrue(fill!=null,"tank fill flange missing");
        near(h,fill.fill(ThermalWater.atTemperature(1000,42),SIMULATE),1000,"tank simulated fill");near(h,tank.solution().massKg(),0,"simulation mutated tank");
        fill.fill(ThermalWater.atTemperature(1000,42),EXECUTE);h.assertTrue(tank.addCharge(),"borate charge refused");
        var circuit=SlcPlumbing.resolve(l,pumpPos,pump.getBlockState());h.assertTrue(circuit.tank()==tank&&circuit.reactor()==reactor,"SLC route failed: "+circuit.status());
        pump.setPanelControlled(true);pump.setRunning(true);
        for(int i=0;i<150;i++)EccsPumpBlockEntity.serverTick(l,pumpPos,pump.getBlockState(),pump);
        near(h,tank.solution().massKg(),1025,"unpowered SLC consumed solution");
        double fraction=tank.solution().boronFraction();long builds=dev.bwr.mod.piping.PipeTopology.buildCount();
        for(int i=0;i<250;i++){pump.energy().receiveEnergy(Integer.MAX_VALUE,false);EccsPumpBlockEntity.serverTick(l,pumpPos,pump.getBlockState(),pump);}
        h.assertTrue(pump.getDeliveredFlowKgPerS()>0&&tank.solution().massKg()<1025,"powered SLC did not deliver");
        near(h,pump.deliveredBoronKgPerS(),pump.getDeliveredFlowKgPerS()*fraction,"boron mass not tied to solution");
        near(h,pump.pump().getSuctionTemperatureC(),42,"SLC solution temperature lost");
        h.assertTrue(dev.bwr.mod.piping.PipeTopology.buildCount()==builds,"stable SLC pipes rebuilt every tick");
        var bus=EccsNetwork.busFor(l,cp);bus.applyTo(reactor.core(),l.getGameTime());h.assertTrue(bus.getTotalBoronPpmPerMinute()>0,"SLC did not reach reactor poison channel");
        near(h,pump.deliveredBoronPpmPerMinute(),bus.getTotalBoronPpmPerMinute(),"SLC computer boron rate differs from delivered rate");
        var cut=o.offset(14,0,5);l.removeBlock(cut,false);double mass=tank.solution().massKg();EccsPumpBlockEntity.serverTick(l,pumpPos,pump.getBlockState(),pump);
        near(h,pump.getDeliveredFlowKgPerS(),0,"broken pipe still injected");near(h,tank.solution().massKg(),mass,"disconnected source was consumed");
        near(h,pump.deliveredBoronPpmPerMinute(),0,"disconnected SLC retained boron rate");
        l.setBlock(cut,BwrBlocks.HIGH_PRESSURE_WATER_PIPE.get().defaultBlockState(),2);
        // A feed pump sharing the delivery header is a legal cross-tie, not a second recipient.
        var feed=o.offset(14,0,13);place(l,BwrBlocks.MOTOR_FEED_PUMP.get(),feed,Direction.NORTH);
        var feedBlock=BwrBlocks.MOTOR_FEED_PUMP.get();var feedState=l.getBlockState(feed);
        var feedEnd=feedBlock.portPosition(feed,feedState,AssemblyPort.WATER_DISCHARGE).above();
        var bend=o.offset(14,3,7);
        pipe(l,o.offset(14,0,6),o.offset(14,0,7));pipe(l,o.offset(14,1,7),bend);
        var corner=new BlockPos(bend.getX(),feedEnd.getY(),feedEnd.getZ());
        pipe(l,bend,corner);pipe(l,corner,feedEnd);
        circuit=SlcPlumbing.resolve(l,pumpPos,pump.getBlockState());h.assertTrue(circuit.reactor()==reactor,"cross-tie rejected: "+circuit.status());
        // Cached fill handles cannot write into a removed or replaced tank.
        var nbt=tank.saveWithFullMetadata(l.registryAccess());l.removeBlockEntity(tankPos);
        var restored=net.minecraft.world.level.block.entity.BlockEntity.loadStatic(tankPos,tank.getBlockState(),nbt,l.registryAccess());l.setBlockEntity(restored);
        fill.fill(ThermalWater.atTemperature(10,13),EXECUTE);near(h,tank.solution().massKg(),mass,"retained capability changed stale tank");
        h.assertTrue(((SlcTankBlockEntity)restored).solution().massKg()>mass,"retained fill did not resolve restored owner");
        l.removeBlock(tankPos,false);near(h,fill.fill(ThermalWater.atTemperature(10,13),EXECUTE),0,"destroyed tank retained fill capability");
        l.removeBlock(pumpPos,false);l.removeBlock(feed,false);l.removeBlock(cp,false);h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=200)
    public static void outfallBudgetAndBlockedFace(GameTestHelper h){
        var l=h.getLevel();var p=new BlockPos(11200,220,11200);load(l,p);l.removeBlock(p,false);
        l.setBlock(p,BwrBlocks.WATER_DISCHARGE_PORT.get().defaultBlockState(),2);l.removeBlock(p.north(),false);
        var be=(WaterDischargeBlockEntity)l.getBlockEntity(p);var inlet=l.getCapability(Capabilities.FluidHandler.BLOCK,p,Direction.SOUTH);
        h.assertTrue(inlet!=null&&l.getCapability(Capabilities.FluidHandler.BLOCK,p,Direction.NORTH)==null,"outfall ports reversed");
        var water=ThermalWater.atTemperature(5000,71);near(h,inlet.fill(water,SIMULATE),3000,"outfall simulation rate");near(h,be.totalKg(),0,"outfall simulation consumed fluid");
        near(h,inlet.fill(water,EXECUTE),3000,"outfall actual rate");near(h,inlet.fill(water,EXECUTE),0,"outfall rate renewed in same tick");near(h,be.temperatureC(),71,"discharge temperature lost");
        near(h,inlet.fill(new net.neoforged.neoforge.fluids.FluidStack(net.minecraft.world.level.material.Fluids.LAVA,1000),EXECUTE),0,"outfall accepted non-water fluid");
        long tick=l.getGameTime();var data=(net.minecraft.world.level.storage.ServerLevelData)l.getLevelData();
        try{data.setGameTime(tick+1);near(h,inlet.fill(water,SIMULATE),3000,"outfall did not renew allowance next tick");}finally{data.setGameTime(tick);}
        l.setBlock(p.north(),Blocks.STONE.defaultBlockState(),2);h.assertTrue(!be.clearMouth(),"solid obstruction ignored");
        near(h,inlet.fill(water,SIMULATE),0,"blocked mouth accepted fluid");
        l.removeBlock(p,false);near(h,inlet.fill(water,EXECUTE),0,"stale outfall accepted fluid");h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=200)
    public static void adsDivisionsAndComputerFaces(GameTestHelper h)throws Exception{
        var l=h.getLevel();var p=new BlockPos(11400,220,11400);load(l,p);
        l.setBlock(p,BwrBlocks.ADS_CONTROLLER.get().defaultBlockState(),2);var ads=(AdsControllerBlockEntity)l.getBlockEntity(p);
        var a=p.east(3);var b=p.west(3);
        for(var at:java.util.List.of(a,b)){l.setBlock(at,BwrBlocks.ADS_RELIEF_VALVE.get().defaultBlockState(),2);l.setBlock(at.below(),Blocks.WATER.defaultBlockState(),2);}
        var first=(SafetyReliefValveBlockEntity)l.getBlockEntity(a);var second=(SafetyReliefValveBlockEntity)l.getBlockEntity(b);
        first.setDivision(1);second.setDivision(2);ads.setDivision(1);ads.setComputerControlled(true);ads.setOpen(true);
        for(int i=0;i<45;i++)AdsControllerBlockEntity.serverTick(l,p,ads.getBlockState(),ads);
        h.assertTrue(first.isOpen()&&!second.isOpen(),"ADS division opened wrong bank");
        ads.setDivision(2);AdsControllerBlockEntity.serverTick(l,p,ads.getBlockState(),ads);
        h.assertTrue(!first.isOpen()&&second.isOpen(),"division reassignment did not release old bank");
        if(net.neoforged.fml.ModList.get().isLoaded("computercraft"))AlphaCcChecks.check(h,l,p,a);
        var saved=ads.saveWithoutMetadata(l.registryAccess());ads.loadWithComponents(saved,l.registryAccess());h.assertTrue(ads.getDivision()==2,"ADS division lost on save");
        l.removeBlock(p,false);l.removeBlock(a,false);l.removeBlock(b,false);h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=200)
    public static void adsNeedsActualSteamSupply(GameTestHelper h)throws Exception{
        var l=h.getLevel();var o=new BlockPos(11600,200,11600);load(l,o);
        var build=PhaseOneRegressionTests.class.getDeclaredMethod("buildVessel",ServerLevel.class,BlockPos.class,int.class);build.setAccessible(true);build.invoke(null,l,o,11);
        var cp=o.offset(-1,3,2);l.setBlock(cp,BwrBlocks.REACTOR_CONTROLLER.get().defaultBlockState(),2);
        var nozzlePos=o.offset(11,6,5);l.setBlock(nozzlePos,BwrBlocks.RPV_STEAM_OUTLET.get().defaultBlockState(),2);
        var reactor=(ReactorControllerBlockEntity)l.getBlockEntity(cp);var validate=reactor.getClass().getDeclaredMethod("revalidate",Level.class);validate.setAccessible(true);validate.invoke(reactor,l);
        h.assertTrue(reactor.isFormed(),"ADS vessel fixture not formed");
        var nozzle=(RpvSteamOutletBlockEntity)l.getBlockEntity(nozzlePos);nozzle.setPosition(1);
        var at=o.offset(16,6,5);l.setBlock(at,BwrBlocks.ADS_RELIEF_VALVE.get().defaultBlockState(),2);
        var valve=(SafetyReliefValveBlockEntity)l.getBlockEntity(at);valve.setComputerControlled(true);valve.setOpen(true);
        l.setBlock(at.below(),Blocks.WATER.defaultBlockState(),2);valve.revalidateDischarge(l);
        near(h,valve.flowKgPerS(1000),0,"disconnected ADS used nearby pressure");
        for(int x=12;x<16;x++)l.setBlock(o.offset(x,6,5),BwrBlocks.PRESSURISED_TUBE.get().defaultBlockState(),2);
        ReactorControllerBlockEntity.serverTick(l,cp,reactor.getBlockState(),reactor);
        h.assertTrue(valve.steamSupply()!=null&&valve.steamSupply().reactor()==reactor,"ADS inlet failed to find piped source");
        var vesselField=reactor.core().getClass().getDeclaredField("vessel");vesselField.setAccessible(true);
        ((dev.bwr.core.thermal.PressureVessel)vesselField.get(reactor.core())).restorePressurePsig(1000);
        nozzle.refreshAttachment(l);nozzle.flowKgPerS(1000);
        double capacity=nozzle.availableFlowKgPerS(l.getGameTime());h.assertTrue(capacity>20,"ADS nozzle has no live steam budget");
        nozzle.claimFlowKgPerS(l.getGameTime(),capacity-20);
        double flow=valve.flowKgPerS(0);near(h,flow,20,"ADS did not share nozzle allocation");
        near(h,valve.flowKgPerS(0),flow,"second ADS/pool reporter claimed twice");
        near(h,nozzle.availableFlowKgPerS(l.getGameTime()),0,"ADS did not spend steam allocation");
        var bus=EccsNetwork.busFor(l,cp);valve.reportRelief(bus,l.getGameTime(),flow);bus.applyTo(reactor.core(),l.getGameTime());
        near(h,bus.getTotalSteamKgPerS(),0,"ADS debited nozzle steam a second time");
        nozzle.setPosition(0);h.assertTrue(valve.steamSupply()==null,"closed RPV nozzle still supplies ADS");nozzle.setPosition(1);
        l.removeBlock(o.offset(14,6,5),false);h.assertTrue(valve.steamSupply()==null,"broken steam pipe remained connected");
        l.removeBlock(at,false);l.removeBlock(cp,false);h.succeed();
    }
}
