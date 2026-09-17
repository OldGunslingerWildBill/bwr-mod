package dev.bwr.mod.devtest;

import com.mojang.logging.LogUtils;
import dev.bwr.core.CoreConfig;
import dev.bwr.core.ReactorCore;
import dev.bwr.mod.eccs.*;
import dev.bwr.mod.reactor.*;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.steam.SteamLineNetwork;
import dev.bwr.mod.suppression.SuppressionPoolBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/** Real pipe networks and tank/nozzle bookkeeping in the isolated GameTest world. */
public final class TurbinePlumbingRuntimeCheck {
    private static final BlockPos ROOT=new BlockPos(160,200,140);
    private static final BlockPos CONTROLLER=new BlockPos(151,198,132);
    private static final BlockPos NOZZLE=new BlockPos(145,200,132);
    private static final BlockPos TANK=new BlockPos(165,201,141);
    private static final BlockPos POOL=new BlockPos(164,195,147);
    private static final BlockPos QUENCHER=new BlockPos(160,193,147);
    private TurbinePlumbingRuntimeCheck() {}
    private static void check(boolean condition,String message) { if(!condition) throw new AssertionError(message); }
    private static void set(ServerLevel l,BlockPos p,Block b) { l.setBlock(p,b.defaultBlockState(),3); }
    private static void clear(ServerLevel l) {
        for(BlockPos p:BlockPos.betweenClosed(new BlockPos(144,190,128),new BlockPos(166,207,151)))
            if(!l.getBlockState(p).isAir()) l.removeBlock(p,false);
    }
    private static void box(ServerLevel l,BlockPos min,BlockPos max,Block wall,Block inside) {
        for(BlockPos p:BlockPos.betweenClosed(min,max)) {
            boolean boundary=p.getX()==min.getX() || p.getY()==min.getY() || p.getZ()==min.getZ()
                    || p.getX()==max.getX() || p.getY()==max.getY() || p.getZ()==max.getZ();
            set(l,p,boundary?wall:inside);
        }
    }
    private static void pipe(ServerLevel l,BlockPos... points) { pipe(l,BwrBlocks.PRESSURISED_TUBE.get(),points); }
    private static void water(ServerLevel l,BlockPos... points) { pipe(l,BwrBlocks.HIGH_PRESSURE_WATER_PIPE.get(),points); }
    private static void pipe(ServerLevel l,Block material,BlockPos... points) {
        for(int i=1;i<points.length;i++) {
            BlockPos a=points[i-1], b=points[i];
            int dx=Integer.signum(b.getX()-a.getX()),dy=Integer.signum(b.getY()-a.getY()),dz=Integer.signum(b.getZ()-a.getZ());
            check(Math.abs(dx)+Math.abs(dy)+Math.abs(dz)<=1,"test pipe must be axis aligned");
            for(BlockPos p=a;;p=p.offset(dx,dy,dz)) {
                set(l,p,material);
                if(p.equals(b)) break;
            }
        }
    }
    public static int run(ServerLevel level) {
        int failures=0;
        long savedTime=level.getGameTime();
        try {
            busRegression();
            for(TurbineAssemblyBlock block:new TurbineAssemblyBlock[]{BwrBlocks.RCIC_TWL.get(),BwrBlocks.HPCI_TURBINE.get()}) {
                clear(level);
                try { plant(level,block); LogUtils.getLogger().info("Turbine plumbing PASS: {}",block.design().id()); }
                catch(RuntimeException | AssertionError e) { failures++; LogUtils.getLogger().error("Turbine plumbing FAIL: {}",block.design().id(),e); }
            }
        } catch(RuntimeException | AssertionError e) { failures++; LogUtils.getLogger().error("Turbine bus regression FAIL",e); }
        finally { clear(level); ((net.minecraft.world.level.storage.ServerLevelData)level.getLevelData()).setGameTime(savedTime); }
        return failures;
    }
    private static void plant(ServerLevel level,TurbineAssemblyBlock block) {
        box(level,new BlockPos(145,193,129),new BlockPos(151,202,135),BwrBlocks.REACTOR_VESSEL.get(),Blocks.AIR);
        set(level,CONTROLLER,BwrBlocks.REACTOR_CONTROLLER.get());
        level.setBlock(new BlockPos(151,197,132),BwrBlocks.RPV_WATER_INJECTION_PORT.get().defaultBlockState()
                .setValue(RpvWaterInjectionPortBlock.FACING,net.minecraft.core.Direction.EAST),3);
        set(level,NOZZLE,BwrBlocks.RPV_STEAM_OUTLET.get());
        for(int x:new int[]{147,149}) for(int z:new int[]{131,133}) set(level,new BlockPos(x,192,z),BwrBlocks.CONTROL_ROD_DRIVE.get());
        box(level,new BlockPos(157,193,144),new BlockPos(163,197,150),BwrBlocks.SUPPRESSION_POOL_WALL.get(),Blocks.WATER);
        set(level,POOL,BwrBlocks.SUPPRESSION_POOL_CONTROLLER.get());
        set(level,QUENCHER,BwrBlocks.SUPPRESSION_POOL_QUENCHER.get());
        set(level,TANK,BwrBlocks.CONDENSATE_STORAGE_TANK.get());
        var state=block.defaultBlockState();
        level.setBlock(ROOT,state,3); block.setPlacedBy(level,ROOT,state,null,new ItemStack(block));
        var inlet=block.portPosition(ROOT,state,AssemblyPort.STEAM_INLET).above();
        pipe(level,new BlockPos(144,200,132),new BlockPos(144,206,132),
                new BlockPos(inlet.getX(),206,132),new BlockPos(inlet.getX(),206,141),inlet);
        BlockPos exhaust=block.portPosition(ROOT,state,AssemblyPort.STEAM_EXHAUST).relative(block.portFace(state,AssemblyPort.STEAM_EXHAUST));
        pipe(level,exhaust,new BlockPos(exhaust.getX(),192,exhaust.getZ()),
                new BlockPos(exhaust.getX(),192,147),new BlockPos(160,192,147));
        if(!block.isHpci()) {
            water(level,new BlockPos(163,201,141),new BlockPos(164,201,141));
            water(level,new BlockPos(162,203,141),new BlockPos(163,203,141),new BlockPos(163,203,138));
        } else {
            water(level,new BlockPos(164,200,140),new BlockPos(164,201,140),new BlockPos(164,201,141));
            water(level,new BlockPos(164,200,142),new BlockPos(164,199,142),new BlockPos(166,199,142),
                    new BlockPos(166,203,142),new BlockPos(166,203,138),new BlockPos(163,203,138));
        }
        water(level,new BlockPos(163,203,138),new BlockPos(153,203,138),new BlockPos(153,197,138),
                new BlockPos(153,197,132),new BlockPos(152,197,132));
        var receiver=(ReactorControllerBlockEntity)level.getBlockEntity(CONTROLLER);
        ReactorControllerBlockEntity.serverTick(level,CONTROLLER,receiver.getBlockState(),receiver);
        check(receiver.isFormed(),"test reactor failed to form: "+receiver.statusLines());
        receiver.core().initialiseHotShutdown();
        var pool=(SuppressionPoolBlockEntity)level.getBlockEntity(POOL);
        pool.refreshForReport(level);
        check(pool.isFormed() && pool.ownsQuencher(QUENCHER),"test pool/quencher failed to form: "+pool.statusLines());
        var tank=(CondensateStorageTankBlockEntity)level.getBlockEntity(TANK); tank.fillKg(100000);
        var nozzle=(RpvSteamOutletBlockEntity)level.getBlockEntity(NOZZLE);
        nozzle.noteController(CONTROLLER); nozzle.setPosition(1); nozzle.refreshAttachment(level); nozzle.refreshLine(level);
        var pump=(EccsPumpBlockEntity)level.getBlockEntity(ROOT);
        pump.setComputerControlled(true); pump.setRunning(true); pump.setSuctionSource(SuctionSource.CONDENSATE_TANK);
        check(AssemblyPlumbing.trace(level,ROOT,state,AssemblyPort.STEAM_INLET).ends().contains(NOZZLE),"inlet failed to reach nozzle");
        check(AssemblyPlumbing.exhaustPool(level,AssemblyPlumbing.trace(level,ROOT,state,AssemblyPort.STEAM_EXHAUST))==pool,"exhaust failed to reach its own basin");
        double before=tank.storedKg(), deliveredMass=0, claimed=0;
        for(int i=0;i<1600;i++) {
            ((net.minecraft.world.level.storage.ServerLevelData)level.getLevelData()).setGameTime(level.getGameTime()+1);
            double available=nozzle.flowKgPerS(receiver.core().getPressurePsig());
            EccsPumpBlockEntity.serverTick(level,ROOT,state,pump);
            double used=pump.getAssemblySteamDrawKgPerS();
            double otherConsumer=nozzle.claimFlowKgPerS(level.getGameTime(),Double.MAX_VALUE);
            check(used+otherConsumer<=available+1e-8,"shared nozzle ledger duplicated steam");
            claimed+=used*.05; deliveredMass+=pump.getDeliveredFlowKgPerS()*.05;
        }
        check(pump.getDeliveredFlowKgPerS()>0 && claimed>0,"connected turbine did not inject: "+pump.statusLines()+" tank="+tank.storedKg());
        check(Math.abs((before-tank.storedKg())-deliveredMass)<1.01,"tank debit differs from delivered water");
        var bus=EccsNetwork.existingBusFor(level,CONTROLLER); bus.applyTo(receiver.core(),level.getGameTime());
        check(bus.getTotalSteamKgPerS()==0,"assembly debited turbine steam twice via ECCS bus");
        check(receiver.core().getInjectionFlowKgPerS()>0,"injection failed to reach core");
        if(!block.isHpci()) {
            // Adjacent water and steam risers must stay separate, including when both are installed.
            check(AssemblyPlumbing.trace(level,ROOT,state,AssemblyPort.WATER_DISCHARGE).valid(),"water and steam risers merged");
            var waterTube=new BlockPos(164,201,141);
            set(level,waterTube.north(),BwrBlocks.CONDENSATE_STORAGE_TANK.get());
            check(AssemblyPlumbing.endpoint(level,AssemblyPlumbing.trace(level,ROOT,state,AssemblyPort.WATER_SUCTION),
                    CondensateStorageTankBlockEntity.class)==null,"ambiguous suction chose an arbitrary tank");
            level.removeBlock(waterTube.north(),false);
            set(level,waterTube.north(),BwrBlocks.TURBINE_STEAM_OUTLET.get());
            check(AssemblyPlumbing.trace(level,ROOT,state,AssemblyPort.WATER_SUCTION).valid()
                    && !AssemblyPlumbing.trace(level,ROOT,state,AssemblyPort.WATER_SUCTION).nodes().contains(waterTube.north()),"water accepted a steam export branch");
            level.removeBlock(waterTube.north(),false);
            level.removeBlock(new BlockPos(153,200,138),false);
            EccsPumpBlockEntity.serverTick(level,ROOT,state,pump);
            check(pump.getDeliveredFlowKgPerS()==0 && receiver.core().getInjectionFlowKgPerS()==0,"broken discharge retained injection");
        }
        level.removeBlock(exhaust,false);
        EccsPumpBlockEntity.serverTick(level,ROOT,state,pump);
        check(pump.getAssemblySteamDrawKgPerS()==0,"disconnected exhaust still consumed steam");
        check(pump.isRunning(),"disconnection changed the player's command");
        check(SteamLineNetwork.survey(level,NOZZLE).quenchers().isEmpty(),"steam survey crossed through the turbine");
        LogUtils.getLogger().info("Turbine {} delivered {} kg from tank and admitted {} kg steam",block.design().id(),deliveredMass,claimed);
    }
    private static void busRegression() {
        ReactorCore core=new ReactorCore(new CoreConfig());
        ReactorEccsBus bus=new ReactorEccsBus();
        bus.report(ROOT,0,10,32,2,32,3,0,true,true); bus.applyTo(core,0);
        bus.withdraw(ROOT); bus.applyTo(core,1);
        check(core.getInjectionFlowKgPerS()==0 && core.getCoreSprayFlowKgPerS()==0 && core.getReliefSteamFlowKgPerS()==0,"last withdrawn flow remained latched");
        bus.reportFeedwater(ROOT,2,5,32,0,false); bus.applyTo(core,2); bus.applyTo(core,100);
        check(core.getFeedwaterFlowKgPerS()==0,"expired feedwater remained latched");
        core.setInjectionFlowKgPerS(7); bus.applyTo(core,101);
        check(core.getInjectionFlowKgPerS()==7,"inactive bus overwrote an unowned channel");
    }
}
