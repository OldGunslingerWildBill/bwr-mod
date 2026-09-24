package dev.bwr.mod.devtest;

import com.mojang.logging.LogUtils;
import dev.bwr.mod.eccs.*;
import dev.bwr.mod.power.*;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.reactor.*;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import java.util.*;

/** Real-world assembly, shaft topology, shared steam, energy, water and save/load regressions. */
public final class PowerModuleRuntimeCheck {
    private static void check(boolean b,String message){if(!b)throw new AssertionError(message);}
    private static void near(double a,double b,String message){check(Math.abs(a-b)<1e-6*Math.max(1,Math.abs(b)),message+": "+a+" != "+b);}
    private static void clear(ServerLevel l){for(var p:BlockPos.betweenClosed(new BlockPos(121,237,119),new BlockPos(187,254,150)))if(!l.getBlockState(p).isAir())l.removeBlock(p,false);}
    public static PowerModuleBlockEntity place(ServerLevel l,PowerModuleBlock b,BlockPos p,Direction facing){
        var s=b.placementState().setValue(PumpAssemblyBlock.FACING,facing);l.setBlock(p,s,3);b.setPlacedBy(l,p,s,null,new ItemStack(b));
        check(b.complete(l,p,s),"Incomplete placement "+b);return (PowerModuleBlockEntity)l.getBlockEntity(p);
    }
    private static void nextTick(ServerLevel l){((net.minecraft.world.level.storage.ServerLevelData)l.getLevelData()).setGameTime(l.getGameTime()+1);}
    public static void pipe(ServerLevel l,BlockPos a,BlockPos b){
        check((a.getX()!=b.getX()?1:0)+(a.getY()!=b.getY()?1:0)+(a.getZ()!=b.getZ()?1:0)<=1,"non-axis pipe segment");
        for(var p:BlockPos.betweenClosed(a,b))l.setBlock(p,BwrBlocks.PRESSURISED_TUBE.get().stateWithConnections(l,p),3);
    }
    private static void seed(PowerModuleBlockEntity m,double kg){var t=m.saveWithoutMetadata(m.getLevel().registryAccess());var steam=new CompoundTag();steam.putDouble("Mass",kg);steam.putDouble("Enthalpy",2770);steam.putDouble("Pressure",1015);t.put("Inlet",steam);t.putDouble("RPM",1500);t.putBoolean("Running",true);m.loadWithComponents(t,m.getLevel().registryAccess());}
    public static int run(ServerLevel l){
        long time=l.getGameTime();int failures=0,passed=0;
        try {
            for(var b:new PowerModuleBlock[]{BwrBlocks.HP_TURBINE.get(),BwrBlocks.LP_TURBINE.get(),BwrBlocks.NUCLEAR_GENERATOR.get()})for(Direction d:Direction.Plane.HORIZONTAL){
                clear(l);try{geometry(l,b,d);passed++;}catch(Throwable e){failures++;LogUtils.getLogger().error("POWER geometry FAIL {} {}",b,d,e);}
            }
            clear(l);try{modularPlant(l);passed++;}catch(Throwable e){failures++;LogUtils.getLogger().error("POWER parallel plant FAIL",e);}
            clear(l);try{condenserRequired(l);passed++;}catch(Throwable e){failures++;LogUtils.getLogger().error("POWER condenser requirement FAIL",e);}
            clear(l);try{shaftGeometry(l);passed++;}catch(Throwable e){failures++;LogUtils.getLogger().error("POWER shaft topology FAIL",e);}
            clear(l);try{nozzleSharing(l);passed++;}catch(Throwable e){failures++;LogUtils.getLogger().error("POWER shared nozzle FAIL",e);}
        }finally{clear(l);((net.minecraft.world.level.storage.ServerLevelData)l.getLevelData()).setGameTime(time);}
        LogUtils.getLogger().info("POWER MODULE RUNTIME CHECK: {} scenarios passed, {} failure(s)",passed,failures);return failures;
    }
    private static void geometry(ServerLevel l,PowerModuleBlock b,Direction d) throws Exception {
        var root=new BlockPos(143,245,132);var m=place(l,b,root,d);var s=m.getBlockState();
        int entities=0;for(int i=0;i<b.cellCount(s);i++){
            var pos=root.offset(TurbineAssemblyBlock.turn(b.cellOffset(s,i),d));if(l.getBlockEntity(pos)!=null)entities++;
            var cell=l.getBlockState(pos);check(b.origin(pos,cell).equals(root),"child has wrong owner");
            for(Direction side:Direction.values()){
                boolean water=b.portAt(cell,side)==AssemblyPort.WATER_DISCHARGE;
                check((l.getCapability(Capabilities.FluidHandler.BLOCK,pos,side)!=null)==water,"fluid capability on wrong face");
                check((l.getCapability(Capabilities.EnergyStorage.BLOCK,pos,side)!=null)==b.energyFace(pos,cell,side),"energy capability on wrong face");
            }
        }
        check(entities==1,"duplicate simulation entities");
        var saved=m.saveWithoutMetadata(l.registryAccess());saved.putDouble("Water",15.75);saved.putDouble("Energy",123.75);m.loadWithComponents(saved,l.registryAccess());
        if(!b.generator()&&!b.highPressure()){
            check(!b.hasPort(AssemblyPort.WATER_DISCHARGE),"LP still advertises water outlet");
            var c=attachCondenser(m);nextTick(l);PowerTrain.tick(m);
            near(c.plant().condensate(),15.75,"legacy LP condensate was not conserved on migration");
            near(m.waterStored(),0,"legacy LP condensate transferred twice");
        }

        if(b.generator()){
            near(m.energy.receiveEnergy(100,false),0,"generator accepted external energy");near(m.energy.extractEnergy(10,true),10,"energy simulate");near(m.energyStored(),123.75,"simulate energy changed");
            m.energy.extractEnergy(10,false);near(m.energyStored(),113.75,"fractional energy lost");
        }
        var reload=new PowerModuleBlockEntity(root,s);reload.loadWithComponents(m.saveWithoutMetadata(l.registryAccess()),l.registryAccess());
        near(reload.waterStored(),m.waterStored(),"water reload");near(reload.energyStored(),m.energyStored(),"energy reload");
        BlockPos child=b.shaft(root,s,true);l.removeBlock(child,false);check(l.getBlockState(root).isAir(),"breaking child left owner");
    }
    public static dev.bwr.mod.condenser.CondenserBlockEntity attachCondenser(PowerModuleBlockEntity m){
        var l=m.getLevel();var p=m.getBlockPos().below(6);var b=BwrBlocks.ARABELLE_CONDENSER.get();
        if(l.getBlockEntity(p) instanceof dev.bwr.mod.condenser.CondenserBlockEntity old)l.removeBlock(p,false);
        var s=b.defaultBlockState().setValue(dev.bwr.mod.condenser.CondenserBlock.FACING,m.getBlockState().getValue(PumpAssemblyBlock.FACING).getClockWise());
        l.setBlock(p,s,3);b.setPlacedBy(l,p,s,null,new ItemStack(b));
        var c=(dev.bwr.mod.condenser.CondenserBlockEntity)l.getBlockEntity(p);check(c!=null&&c.ready(),"condenser placement incomplete");return c;
    }
    public static double plantMass(List<PowerModuleBlockEntity> modules){
        return modules.stream().mapToDouble(m->m.inletMass()+m.exhaustMass()+m.waterStored()
                +(m.condenser()==null?0:m.condenser().plant().steam.mass()+m.condenser().plant().condensate())).sum();
    }
    public static List<PowerModuleBlockEntity> makeTrain(ServerLevel l,int y,int z){
        int[] x={130,139,147,154,161,168,176};
        var out=new ArrayList<PowerModuleBlockEntity>();
        for(int i=0;i<x.length;i++){var b=i<2?BwrBlocks.HP_TURBINE.get():i<6?BwrBlocks.LP_TURBINE.get():BwrBlocks.NUCLEAR_GENERATOR.get();out.add(place(l,b,new BlockPos(x[i],y,z),Direction.EAST));}
        for(var m:out)if(!m.block().generator()&&!m.block().highPressure())attachCondenser(m);
        // Separate crossover header: both HP outlets feed four LP admissions.
        pipe(l,new BlockPos(130,y+5,z-6),new BlockPos(168,y+5,z-6));
        for(var m:out)if(!m.block().generator()){
            var role=m.block().highPressure()?AssemblyPort.STEAM_EXHAUST:AssemblyPort.STEAM_INLET;
            var p=m.block().portPosition(m.getBlockPos(),m.getBlockState(),role).relative(m.block().portFace(m.getBlockState(),role));
            var outside=new BlockPos(p.getX(),p.getY(),z-6);pipe(l,p,outside);pipe(l,outside,new BlockPos(p.getX(),y+5,z-6));
        }
        return out;
    }
    private static void condenserRequired(ServerLevel l){
        var lp=place(l,BwrBlocks.LP_TURBINE.get(),new BlockPos(143,245,132),Direction.EAST);
        var gen=place(l,BwrBlocks.NUCLEAR_GENERATOR.get(),new BlockPos(151,245,132),Direction.EAST);seed(lp,100);
        for(int i=0;i<5;i++){nextTick(l);PowerTrain.tick(lp);}
        near(lp.inletMass(),100,"LP consumed steam without condenser");near(lp.waterStored(),0,"LP made water without condenser");
        var c=attachCondenser(lp);c.plant().steam.offer(new dev.bwr.core.turbine.SteamInventory.Packet(c.plant().steam.space(),2100,1));
        nextTick(l);PowerTrain.tick(lp);near(lp.inletMass(),100,"full condenser did not cause backpressure");
        c.plant().steam.take(c.plant().steam.mass());
        for(int i=0;i<30;i++){nextTick(l);PowerTrain.tick(lp);gen.energy.extractEnergy(Integer.MAX_VALUE,false);}
        check(c.plant().steam.mass()>0,"connected condenser received no exhaust");near(lp.inletMass()+c.plant().steam.mass(),100,"LP discharge lost steam");
        c.plant().tick(.05);near(c.plant().condensate(),0,"dry condenser created water");
        c.plant().fillCold(10000,false);c.plant().tick(.05);check(c.plant().condensate()>0,"cooled condenser made no water");
        near(lp.inletMass()+c.plant().steam.mass()+c.plant().condensate(),100,"condensing lost mass");
        // Retained remote capability must resolve the replacement controller after reload.
        var entry=c.layout().ports.stream().filter(p->p.role()==dev.bwr.mod.condenser.CondenserBlock.Port.CONDENSATE).findFirst().orElseThrow();
        var at=c.layout().world(c.root(),c.getBlockState().getValue(dev.bwr.mod.condenser.CondenserBlock.FACING),entry);var face=dev.bwr.mod.condenser.CondenserBlock.portFace(l.getBlockState(at));
        var cache=net.neoforged.neoforge.capabilities.BlockCapabilityCache.create(Capabilities.FluidHandler.BLOCK,l,at,face);var oldHandler=cache.getCapability();
        var nbt=c.saveWithFullMetadata(l.registryAccess());var state=c.getBlockState();l.removeBlockEntity(c.root());var replacement=new dev.bwr.mod.condenser.CondenserBlockEntity(c.root(),state);replacement.loadWithComponents(nbt,l.registryAccess());l.setBlockEntity(replacement);
        double old=c.plant().condensate();check(oldHandler.drain(1,IFluidHandler.FluidAction.EXECUTE).getAmount()==1,"cached condenser outlet did not reconnect");near(c.plant().condensate(),old,"remote drain touched obsolete condenser");near(replacement.plant().condensate(),old-1,"replacement water inventory not debited");
    }
    private static void modularPlant(ServerLevel l){
        var modules=makeTrain(l,245,133);var gen=modules.getLast();seed(modules.get(0),200);seed(modules.get(1),200);
        check(PowerTrain.members(gen).size()==7,"2HP/4LP train did not join");
        for(int i=2;i<6;i++)check(PowerSteamNetwork.sources(modules.get(i)).size()==2,"LP header did not reach both HP exhausts");
        double exported=0;
        for(int t=0;t<300;t++){
            nextTick(l);for(var m:modules)PowerTrain.tick(m);
            double before=gen.energyStored();PowerTrain.tick(gen);near(gen.energyStored(),before,"second ticker duplicated work");
            exported+=gen.energy.extractEnergy(Integer.MAX_VALUE,false);
            double mass=plantMass(modules);near(mass,400,"steam-to-water mass conservation");
        }
        double steam=modules.stream().filter(m->m.condenser()!=null).mapToDouble(m->m.condenser().plant().steam.mass()).sum();near(steam,400,"LP did not discharge all steam to condenser");
        near(modules.stream().mapToDouble(PowerModuleBlockEntity::waterStored).sum(),0,"LP still condensed water");
        double expected=400*850*1000*.985*20/EccsPower.WATTS_PER_FE_PER_TICK;
        near(exported+gen.energyStored(),expected,"duplicate or missing FE in parallel train");
        // Inventory reload must not give a second bite at already expanded steam.
        for(var m:modules)m.loadWithComponents(m.saveWithoutMetadata(l.registryAccess()),l.registryAccess());
        nextTick(l);PowerTrain.tick(gen);near(gen.electricMW,0,"reload regenerated consumed steam");
        // All four parallel LPs must receive a share, even if the first can swallow the entire header.
        var hp=modules.getFirst();var tag=hp.saveWithoutMetadata(l.registryAccess());var exhaust=new CompoundTag();
        exhaust.putDouble("Mass",80);exhaust.putDouble("Enthalpy",2490);exhaust.putDouble("Pressure",145);tag.put("Exhaust",exhaust);tag.putDouble("RPM",1500);
        hp.loadWithComponents(tag,l.registryAccess());nextTick(l);PowerTrain.tick(gen);
        for(int i=2;i<6;i++)near(modules.get(i).flowKgPerS,400,"parallel LP allocation is not balanced");
        // Isolate one inlet and confirm no remote steam shortcut.
        var lp=modules.get(2);var p=lp.block().portPosition(lp.getBlockPos(),lp.getBlockState(),AssemblyPort.STEAM_INLET).above();l.removeBlock(p,false);
        check(PowerSteamNetwork.sources(lp).isEmpty(),"broken steam inlet still attached");
    }
    private static void shaftGeometry(ServerLevel l){
        var root=new BlockPos(140,245,133);var hp=place(l,BwrBlocks.HP_TURBINE.get(),root,Direction.NORTH);
        var gen=place(l,BwrBlocks.NUCLEAR_GENERATOR.get(),root.south(10),Direction.SOUTH);
        check(PowerTrain.members(hp).size()==1,"one-block shaft gap joined");
        l.removeBlock(gen.getBlockPos(),false);gen=place(l,BwrBlocks.NUCLEAR_GENERATOR.get(),root.south(9),Direction.SOUTH);
        check(PowerTrain.members(hp).size()==2,"reversed coaxial module rejected");
        l.removeBlock(gen.getBlockPos(),false);gen=place(l,BwrBlocks.NUCLEAR_GENERATOR.get(),root.south(9).above(),Direction.NORTH);
        check(PowerTrain.members(hp).size()==1,"wrong shaft height joined");seed(hp,10);nextTick(l);PowerTrain.tick(hp);near(hp.inletMass(),10,"turbine consumed steam without generator");
    }
    private static void nozzleSharing(ServerLevel l){
        var reactor=PumpAssemblyRuntimeCheck.vessel(l);reactor.core().initialiseHotShutdown();
        var a=place(l,BwrBlocks.HP_TURBINE.get(),new BlockPos(140,245,132),Direction.EAST);
        var b=place(l,BwrBlocks.HP_TURBINE.get(),new BlockPos(149,245,132),Direction.EAST);
        var g=place(l,BwrBlocks.NUCLEAR_GENERATOR.get(),new BlockPos(158,245,132),Direction.EAST);
        var nozzlePos=new BlockPos(164,201,127);l.setBlock(nozzlePos,BwrBlocks.RPV_STEAM_OUTLET.get().defaultBlockState(),3);
        pipe(l,new BlockPos(164,201,126),new BlockPos(164,250,126));pipe(l,new BlockPos(164,250,126),new BlockPos(140,250,126));
        for(var m:List.of(a,b)){pipe(l,new BlockPos(m.getBlockPos().getX(),250,126),m.block().portPosition(m.getBlockPos(),m.getBlockState(),AssemblyPort.STEAM_INLET).above());}
        var nozzle=(RpvSteamOutletBlockEntity)l.getBlockEntity(nozzlePos);nozzle.noteController(reactor.getBlockPos());nozzle.setPosition(1);nozzle.refreshAttachment(l);nozzle.refreshLine(l);
        nextTick(l);double available=nozzle.flowKgPerS(reactor.core().getPressurePsig());check(available>0,"fixture nozzle had no flow");
        check(PowerSteamNetwork.sources(a).size()==1&&PowerSteamNetwork.sources(b).size()==1,"HP inlet did not reach nozzle");
        PowerTrain.tick(g);double drawn=(a.inletMass()+b.inletMass()+a.exhaustMass()+b.exhaustMass())*20;
        check(drawn>0,"HP sections did not claim reactor steam");
        double rest=nozzle.claimFlowKgPerS(l.getGameTime(),Double.MAX_VALUE);near(drawn+rest,available,"reactor nozzle shared ledger duplicated or lost flow");
        // Clean up only the fixture's tall steam riser; vessel is in the pre-existing test area.
        for(var p:BlockPos.betweenClosed(new BlockPos(164,201,126),new BlockPos(164,244,126)))l.removeBlock(p,false);
    }
}
