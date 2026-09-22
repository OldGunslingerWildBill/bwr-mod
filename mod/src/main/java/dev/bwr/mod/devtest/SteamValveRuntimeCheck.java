package dev.bwr.mod.devtest;

import com.mojang.logging.LogUtils;
import dev.bwr.mod.eccs.*;
import dev.bwr.mod.power.*;
import dev.bwr.mod.reactor.*;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.steam.*;
import dev.bwr.mod.gui.TurbineValveMenu;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import java.util.*;

/** Real server regressions for shared admission, physical isolation and menu authority. */
public final class SteamValveRuntimeCheck {
    private static final BlockPos CONTROL=new BlockPos(173,250,140),STOP=new BlockPos(171,250,140);
    private static void check(boolean b,String message){if(!b)throw new AssertionError(message);}
    private static void near(double a,double b,String message){check(Math.abs(a-b)<1e-6*Math.max(1,Math.abs(b)),message+": "+a+" != "+b);}
    private static void tick(ServerLevel l){((net.minecraft.world.level.storage.ServerLevelData)l.getLevelData()).setGameTime(l.getGameTime()+1);}
    private static TurbineValveBlockEntity valve(ServerLevel l,BlockPos p,boolean stop){
        var b=stop?BwrBlocks.STEAM_STOP_VALVE.get():BwrBlocks.TURBINE_CONTROL_VALVE.get();
        l.setBlock(p,b.defaultBlockState().setValue(TurbineValveBlock.FACING,Direction.EAST),3);
        return (TurbineValveBlockEntity)l.getBlockEntity(p);
    }
    private static void reset(List<PowerModuleBlockEntity> modules){
        for(var m:modules){if(m.condenser()!=null){m.condenser().plant().steam.restore(0,0,0);m.condenser().plant().restore(0,0,0);}var t=new CompoundTag();t.putDouble("RPM",1500);t.putBoolean("Running",false);t.putDouble("Valve",0);m.loadWithComponents(t,m.getLevel().registryAccess());}
    }
    public static int run(ServerLevel l){
        long original=l.getGameTime();int passed=0;
        try{
            var modules=PowerModuleRuntimeCheck.makeTrain(l,245,133);var gen=modules.getLast();
            var reactor=PumpAssemblyRuntimeCheck.vessel(l);reactor.core().initialiseHotShutdown();
            var np=new BlockPos(164,201,127);l.setBlock(np,BwrBlocks.RPV_STEAM_OUTLET.get().defaultBlockState(),3);
            var route=List.of(new BlockPos(164,201,126),new BlockPos(180,201,126),new BlockPos(180,250,126),new BlockPos(180,250,140),new BlockPos(130,250,140));
            for(int i=1;i<route.size();i++)PowerModuleRuntimeCheck.pipe(l,route.get(i-1),route.get(i));
            for(int i=0;i<2;i++){var m=modules.get(i);PowerModuleRuntimeCheck.pipe(l,new BlockPos(m.getBlockPos().getX(),250,140),m.block().portPosition(m.getBlockPos(),m.getBlockState(),AssemblyPort.STEAM_INLET).above());}
            var control=valve(l,CONTROL,false);var stop=valve(l,STOP,true);
            var nozzle=(RpvSteamOutletBlockEntity)l.getBlockEntity(np);nozzle.noteController(reactor.getBlockPos());nozzle.setPosition(1);nozzle.refreshAttachment(l);nozzle.refreshLine(l);
            near(control.position(),0,"new valve must be closed");stop.setTarget(1);stop.stroke(1);
            double full=0;
            for(double fraction:new double[]{1,.5,.25,.251,0}){
                reset(modules);control.setTarget(fraction);control.stroke(3);tick(l);
                double supply=nozzle.flowKgPerS(reactor.core().getPressurePsig());if(fraction==1)full=supply;
                check(full>0,"fixture has no steam");near(supply,full*fraction,"valve did not govern vessel discharge");
                PowerTrain.tick(gen);
                double draw=modules.get(0).flowKgPerS+modules.get(1).flowKgPerS;
                near(draw,supply,"shared HP admission duplicated, squared or lost valve flow");
                near(modules.get(0).flowKgPerS,modules.get(1).flowKgPerS,"HP supply not shared");
                for(int i=2;i<6;i++)near(modules.get(i).flowKgPerS,draw/4,"HP-to-LP steam allocation");
                near(PowerModuleRuntimeCheck.plantMass(modules),draw/20,"steam/water mass balance");
                if(fraction==0)tick(l); // Meter holds its most recent sample for at most one tick.
                near(control.flowKgPerS(),draw,"control valve meter");near(stop.flowKgPerS(),draw,"stop valve meter");
                double stored=gen.energyStored();PowerTrain.tick(gen);near(gen.energyStored(),stored,"second tick regenerated steam");passed++;
            }
            // A stop valve closes the one shared header; trapped downstream steam can still expand.
            control.setTarget(1);control.stroke(3);stop.setTarget(0);stop.stroke(1);reset(modules);tick(l);
            near(nozzle.flowKgPerS(reactor.core().getPressurePsig()),0,"closed stop passed reactor steam");
            var hp=modules.getFirst();var t=hp.saveWithoutMetadata(l.registryAccess());var steam=new CompoundTag();
            steam.putDouble("Mass",5);steam.putDouble("Enthalpy",2770);steam.putDouble("Pressure",1015);t.put("Inlet",steam);hp.loadWithComponents(t,l.registryAccess());
            PowerTrain.tick(gen);near(PowerModuleRuntimeCheck.plantMass(modules),5,"stop erased trapped steam or local legacy controls blocked expansion");passed++;
            // Physically bypass both valves: a closed parallel branch must not shut the open route.
            PowerModuleRuntimeCheck.pipe(l,new BlockPos(170,250,140),new BlockPos(170,250,142));
            PowerModuleRuntimeCheck.pipe(l,new BlockPos(170,250,142),new BlockPos(175,250,142));
            PowerModuleRuntimeCheck.pipe(l,new BlockPos(175,250,142),new BlockPos(175,250,140));
            reset(modules);tick(l);near(nozzle.flowKgPerS(reactor.core().getPressurePsig()),full,"closed side branch stopped bypass");
            PowerTrain.tick(gen);near(modules.get(0).flowKgPerS+modules.get(1).flowKgPerS,full,"real bypass not honored");passed++;
            // Old Mekanism boundary must not claim through a closed branch on an otherwise open header.
            var outlet=new BlockPos(173,250,144);l.setBlock(outlet,BwrBlocks.TURBINE_STEAM_OUTLET.get().defaultBlockState(),3);
            var branch=valve(l,new BlockPos(173,250,143),true);
            l.setBlock(branch.getBlockPos(),branch.getBlockState().setValue(TurbineValveBlock.FACING,Direction.NORTH),3);
            tick(l);nozzle.flowKgPerS(reactor.core().getPressurePsig());near(SteamValveRouting.claim(l,outlet,nozzle,1000),0,"Mek export bypassed closed branch");
            branch.setTarget(1);branch.stroke(1);check(SteamValveRouting.claim(l,outlet,nozzle,1000)>0,"Mek export cannot traverse open valve");passed++;
            // All four model rotations have exactly two steam flanges, no water acceptance.
            for(var b:List.of(BwrBlocks.STEAM_STOP_VALVE.get(),BwrBlocks.TURBINE_CONTROL_VALVE.get()))for(Direction d:Direction.Plane.HORIZONTAL){
                var s=b.defaultBlockState().setValue(TurbineValveBlock.FACING,d);
                for(Direction side:Direction.values()){
                    check(SteamLineNetwork.acceptsLineOn(s,side)==(side.getAxis()==d.getAxis()),"steam connection on bonnet/wrong axis");
                    check(!dev.bwr.mod.water.WaterLineNetwork.acceptsLineOn(s,side),"water line connected to steam valve");
                }
            }passed++;
            control.setTarget(.427);control.stroke(.2);var saved=control.saveWithoutMetadata(l.registryAccess());
            var copy=new TurbineValveBlockEntity(CONTROL,control.getBlockState());copy.loadWithComponents(saved,l.registryAccess());
            near(copy.target(),.427,"target reload");near(copy.position(),control.position(),"stroke reload");
            saved.putDouble("Target",Double.NaN);saved.putDouble("Position",Double.POSITIVE_INFINITY);copy.loadWithComponents(saved,l.registryAccess());near(copy.position(),0,"nonfinite position");near(copy.target(),0,"nonfinite target");passed++;
            var player=net.neoforged.neoforge.common.util.FakePlayerFactory.getMinecraft(l);player.setPos(CONTROL.getX()+.5,CONTROL.getY()+1,CONTROL.getZ()+1);
            var menu=new TurbineValveMenu(1,player.getInventory(),CONTROL);menu.handleCommand(player,0,653,0);near(control.target(),.653,"fine GUI command");
            menu.handleCommand(player,0,1001,0);near(control.target(),.653,"invalid GUI range accepted");
            player.setPos(0,0,0);menu.handleCommand(player,0,0,0);near(control.target(),.653,"remote GUI command accepted");passed++;
            if(net.neoforged.fml.ModList.get().isLoaded("computercraft")){
                Object peripheral=Class.forName("dev.bwr.mod.peripheral.TurbineValvePeripheral").getConstructor(TurbineValveBlockEntity.class).newInstance(control);
                peripheral.getClass().getMethod("setPosition",double.class).invoke(peripheral,.321);near(control.target(),.321,"CC command");
                peripheral.getClass().getMethod("close").invoke(peripheral);near(control.target(),0,"CC close");passed++;
            }
            LogUtils.getLogger().info("STEAM VALVE RUNTIME CHECK PASS: {} scenarios",passed);return 0;
        }catch(Throwable e){LogUtils.getLogger().error("STEAM VALVE RUNTIME CHECK FAIL after {} scenarios",passed,e);return 1;}
        finally{
            for(var p:BlockPos.betweenClosed(new BlockPos(121,240,119),new BlockPos(187,254,150)))if(!l.getBlockState(p).isAir())l.removeBlock(p,false);
            for(var p:BlockPos.betweenClosed(new BlockPos(180,201,126),new BlockPos(180,239,126)))l.removeBlock(p,false);
            for(var p:BlockPos.betweenClosed(new BlockPos(164,201,126),new BlockPos(180,201,126)))l.removeBlock(p,false);
            ((net.minecraft.world.level.storage.ServerLevelData)l.getLevelData()).setGameTime(original);
        }
    }
}
