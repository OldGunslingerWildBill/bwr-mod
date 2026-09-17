package dev.bwr.mod.devtest;

import com.mojang.logging.LogUtils;
import dev.bwr.mod.eccs.*;
import dev.bwr.mod.feedwater.FeedwaterPumpBlockEntity;
import dev.bwr.mod.flow.*;
import dev.bwr.mod.reactor.*;
import dev.bwr.mod.registry.BwrBlocks;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.DirectionalPlaceContext;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.entity.item.ItemEntity;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import java.util.*;

/** Real-world placement, capabilities, piping and recirculation regression cases. */
public final class PumpAssemblyRuntimeCheck {
    private static final BlockPos ROOT=new BlockPos(175,210,140);
    private PumpAssemblyRuntimeCheck() {}
    private static void check(boolean okay,String message) { if(!okay) throw new AssertionError(message); }
    private static PumpAssemblyBlock[] blocks() { return new PumpAssemblyBlock[]{BwrBlocks.LPCS_PUMP.get(),BwrBlocks.RHR_PUMP.get(),BwrBlocks.HPCS_PUMP.get(),
            BwrBlocks.MOTOR_FEED_PUMP.get(),BwrBlocks.TURBINE_FEED_PUMP.get(),BwrBlocks.JET_PUMP.get(),BwrBlocks.RIP_PUMP.get()}; }
    private static void clear(ServerLevel l) {
        for(BlockPos p:BlockPos.betweenClosed(new BlockPos(155,190,125),new BlockPos(191,219,153))) if(!l.getBlockState(p).isAir()) l.removeBlock(p,false);
        l.getEntitiesOfClass(ItemEntity.class,new AABB(155,190,125,192,220,154)).forEach(net.minecraft.world.entity.Entity::discard);
    }
    private static BlockState place(ServerLevel l,PumpAssemblyBlock b,BlockPos root,Direction d) {
        var s=b.defaultBlockState().setValue(PumpAssemblyBlock.ASSEMBLED,true).setValue(PumpAssemblyBlock.FACING,d);
        l.setBlock(root,s,3); b.setPlacedBy(l,root,s,null,new ItemStack(b));
        check(b.complete(l,root,s),"placement incomplete "+b+" "+d); return s;
    }
    private static BlockPos pos(PumpAssemblyBlock b,BlockPos root,BlockState s,int cell) {
        return root.offset(TurbineAssemblyBlock.turn(b.cellOffset(cell),s.getValue(PumpAssemblyBlock.FACING)));
    }
    public static int run(ServerLevel l) {
        int failures=0,checks=0;
        long time=l.getGameTime();
        boolean drops=l.getGameRules().getBoolean(net.minecraft.world.level.GameRules.RULE_DOBLOCKDROPS);
        l.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DOBLOCKDROPS).set(true,l.getServer());
        try {
            for(var block:blocks()) for(Direction d:Direction.Plane.HORIZONTAL) {
                clear(l);
                try {
                    geometry(l,block,d); checks++;
                } catch(RuntimeException | AssertionError e) { failures++; LogUtils.getLogger().error("Pump assembly FAIL: {} {}",block,d,e); }
            }
            clear(l);
            try { circuits(l); checks++; } catch(RuntimeException | AssertionError e) { failures++; LogUtils.getLogger().error("Pump circuit FAIL",e); }
            clear(l);
            try { recirculation(l); checks++; } catch(RuntimeException | AssertionError e) { failures++; LogUtils.getLogger().error("Jet recirculation FAIL",e); }
            clear(l);
            try { turbineFeed(l); checks++; } catch(RuntimeException | AssertionError e) { failures++; LogUtils.getLogger().error("Turbine feedwater FAIL",e); }
            for(var b:new PumpAssemblyBlock[]{BwrBlocks.LPCS_PUMP.get(),BwrBlocks.HPCS_PUMP.get(),BwrBlocks.RHR_PUMP.get()}) {
                clear(l);
                try { motorEccs(l,b); checks++; } catch(RuntimeException | AssertionError e) { failures++; LogUtils.getLogger().error("Motor ECCS FAIL: {}",b,e); }
            }
        } finally {
            l.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DOBLOCKDROPS).set(drops,l.getServer());
            clear(l); ((net.minecraft.world.level.storage.ServerLevelData)l.getLevelData()).setGameTime(time);
        }
        LogUtils.getLogger().info("PUMP RUNTIME CHECK: {} scenarios passed, {} failure(s)",checks,failures);
        return failures;
    }
    private static void geometry(ServerLevel l,PumpAssemblyBlock b,Direction d) {
        var s=place(l,b,ROOT,d); int entities=0;
        for(int i=0;i<b.cellCount();i++) {
            BlockPos p=pos(b,ROOT,s,i);
            check(b.origin(p,l.getBlockState(p)).equals(ROOT),"wrong rotated owner");
            if(l.getBlockEntity(p)!=null) entities++;
            var shape=l.getBlockState(p).getShape(l,p);
            if(!shape.isEmpty()) { var box=shape.bounds(); check(box.minX>=0 && box.maxX<=1 && box.minY>=0 && box.maxY<=1 && box.minZ>=0 && box.maxZ<=1,"collision outside cell"); }
        }
        check(entities==(b.kind()==PumpAssemblyBlock.Kind.JET?0:1),"more than one machine simulation");
        for(var port:b.ports()) {
            BlockPos p=ROOT.offset(TurbineAssemblyBlock.turn(port.cell(),d));
            Direction face=TurbineAssemblyBlock.turn(port.face(),d);
            check(b.portAt(l.getBlockState(p),face)==port.role(),"wrong port role");
            BlockPos tube=p.relative(face); l.setBlock(tube,BwrBlocks.PRESSURISED_TUBE.get().stateWithConnections(l,tube),3);
            check(l.getBlockState(tube).getValue(PipeBlock.PROPERTY_BY_DIRECTION.get(face.getOpposite())),"tube does not connect to flange");
            l.removeBlock(tube,false);
        }
        if(b.kind()!=PumpAssemblyBlock.Kind.JET) {
            BlockPos child=pos(b,ROOT,s,b.cellCount()-1);
            check(l.getCapability(Capabilities.EnergyStorage.BLOCK,child,Direction.UP)!=null,"part cannot access shared power");
        }
        if(b.kind()==PumpAssemblyBlock.Kind.MOTOR_FEED || b.kind()==PumpAssemblyBlock.Kind.TURBINE_FEED) {
            BlockPos inlet=b.portPosition(ROOT,s,AssemblyPort.WATER_SUCTION); Direction face=b.portFace(s,AssemblyPort.WATER_SUCTION);
            var handler=l.getCapability(Capabilities.FluidHandler.BLOCK,inlet,face);
            check(handler!=null && handler.fill(new FluidStack(Fluids.WATER,100),IFluidHandler.FluidAction.EXECUTE)==100,"model suction has no fluid capability");
            check(l.getCapability(Capabilities.FluidHandler.BLOCK,inlet,face.getOpposite())==null,"water accepted through back of inlet cell");
            check(l.getCapability(Capabilities.FluidHandler.BLOCK,b.portPosition(ROOT,s,AssemblyPort.WATER_DISCHARGE),b.portFace(s,AssemblyPort.WATER_DISCHARGE))==null,"discharge exposes suction buffer");
        }
        // Root loot once; all owned parts disappear. Removing a part cannot leave a ghost controller.
        l.destroyBlock(ROOT,true);
        long drops=l.getEntitiesOfClass(ItemEntity.class,new AABB(ROOT).inflate(10)).stream().filter(e -> e.getItem().is(b.asItem())).mapToInt(e -> e.getItem().getCount()).sum();
        check(drops==1,"assembly root did not drop exactly one item: "+drops);
        for(int i=0;i<b.cellCount();i++) check(!l.getBlockState(pos(b,ROOT,s,i)).is(b),"orphan after root destruction");
        s=place(l,b,ROOT,d); l.removeBlock(pos(b,ROOT,s,b.cellCount()-1),false);
        check(!l.getBlockState(ROOT).is(b),"child removal retained controller");
        // Old blockstates must load compactly and must not reserve surrounding blocks.
        CompoundTag old=new CompoundTag(); old.putString("Name",net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(b).toString());
        var saved=NbtUtils.readBlockState(l.holderLookup(net.minecraft.core.registries.Registries.BLOCK),old);
        check(!saved.getValue(PumpAssemblyBlock.ASSEMBLED) && saved.getValue(PumpAssemblyBlock.CELL)==b.controllerCell(),"old save expanded or lost its owner");
        l.setBlock(ROOT.east(),Blocks.STONE.defaultBlockState(),3); l.setBlock(ROOT,saved,3);
        check(l.getBlockState(ROOT.east()).is(Blocks.STONE),"migration overwrote neighbour");
        l.removeBlock(ROOT,false); l.removeBlock(ROOT.east(),false);
        var context=new DirectionalPlaceContext(l,ROOT,Direction.DOWN,new ItemStack(b),Direction.UP);
        var intended=b.getStateForPlacement(context); check(intended!=null,"empty footprint refused");
        BlockPos obstruction=pos(b,ROOT,intended,b.cellCount()-1); l.setBlock(obstruction,Blocks.STONE.defaultBlockState(),3);
        check(b.getStateForPlacement(context)==null,"blocked footprint accepted");
    }
    private static ReactorControllerBlockEntity vessel(ServerLevel l) {
        for(BlockPos p:BlockPos.betweenClosed(new BlockPos(158,194,127),new BlockPos(170,204,139))) {
            boolean wall=p.getX()==158 || p.getX()==170 || p.getY()==194 || p.getY()==204 || p.getZ()==127 || p.getZ()==139;
            l.setBlock(p,(wall?BwrBlocks.REACTOR_VESSEL.get():Blocks.AIR).defaultBlockState(),3);
        }
        BlockPos controller=new BlockPos(170,199,133);
        l.setBlock(controller,BwrBlocks.REACTOR_CONTROLLER.get().defaultBlockState(),3);
        for(int x=160;x<=168;x+=2) for(int z=129;z<=137;z+=2) l.setBlock(new BlockPos(x,193,z),BwrBlocks.CONTROL_ROD_DRIVE.get().defaultBlockState(),3);
        var be=(ReactorControllerBlockEntity)l.getBlockEntity(controller);
        ReactorControllerBlockEntity.serverTick(l,controller,be.getBlockState(),be);
        check(be.isFormed(),"test vessel not formed: "+be.statusLines()); return be;
    }
    private static void pipe(ServerLevel l,BlockPos a,BlockPos b) {
        int dx=Integer.signum(b.getX()-a.getX()),dy=Integer.signum(b.getY()-a.getY()),dz=Integer.signum(b.getZ()-a.getZ());
        check(Math.abs(dx)+Math.abs(dy)+Math.abs(dz)<=1,"bad fixture pipe segment");
        for(BlockPos p=a;;p=p.offset(dx,dy,dz)) { l.setBlock(p,BwrBlocks.PRESSURISED_TUBE.get().defaultBlockState(),3); if(p.equals(b))break; }
    }
    private static void circuits(ServerLevel l) {
        var reactor=vessel(l); var b=BwrBlocks.MOTOR_FEED_PUMP.get(); var s=place(l,b,ROOT,Direction.NORTH);
        BlockPos tank=ROOT.offset(1,4,1); l.setBlock(tank,BwrBlocks.CONDENSATE_STORAGE_TANK.get().defaultBlockState(),3);
        pipe(l,ROOT.offset(1,2,1),ROOT.offset(1,3,1));
        pipe(l,ROOT.offset(3,2,1),ROOT.offset(3,4,1));
        pipe(l,ROOT.offset(3,4,1),ROOT.offset(3,4,-3));
        pipe(l,ROOT.offset(3,4,-3),new BlockPos(172,214,137));
        pipe(l,new BlockPos(172,214,137),new BlockPos(172,199,137));
        pipe(l,new BlockPos(172,199,137),new BlockPos(172,199,133));
        pipe(l,new BlockPos(172,199,133),new BlockPos(171,199,133));
        var t=(CondensateStorageTankBlockEntity)l.getBlockEntity(tank);t.fillKg(50000);
        BlockPos branch=ROOT.offset(0,3,1);
        l.setBlock(branch,BwrBlocks.MSIV.get().defaultBlockState(),3);
        var valve=(dev.bwr.mod.steam.MainSteamIsolationValveBlockEntity)l.getBlockEntity(branch);
        valve.setDemandOpen(false);valve.tickValve(10);
        check(AssemblyPlumbing.trace(l,ROOT,s,AssemblyPort.WATER_SUCTION).opening()>0,"closed side branch blocked the open suction header");
        var pump=(FeedwaterPumpBlockEntity)l.getBlockEntity(ROOT);pump.setComputerControlled(true);pump.setRunning(true);
        double before=t.storedKg(),mass=0;
        for(int i=0;i<800;i++) {
            pump.energy().receiveEnergy(Integer.MAX_VALUE,false);
            FeedwaterPumpBlockEntity.serverTick(l,ROOT,s,pump);mass+=pump.getDeliveredFlowKgPerS()*.05;
        }
        check(mass>0,"connected motor feed pump did not deliver: "+pump.statusLines());
        check(Math.abs(before-t.storedKg()-mass)<1.01,"feedwater created water");
        l.removeBlock(ROOT.offset(3,3,1),false); FeedwaterPumpBlockEntity.serverTick(l,ROOT,s,pump);
        check(pump.getDeliveredFlowKgPerS()==0 && pump.isRunning(),"broken discharge delivered water or changed command");
    }
    private static void recirculation(ServerLevel l) {
        var reactor=vessel(l); var jet=BwrBlocks.JET_PUMP.get();
        BlockPos a=new BlockPos(160,195,128),b=new BlockPos(163,195,128),drive=new BlockPos(164,195,125);
        place(l,jet,a,Direction.NORTH);place(l,jet,b,Direction.NORTH);
        pipe(l,new BlockPos(161,195,127),new BlockPos(161,195,126));
        pipe(l,new BlockPos(161,195,126),new BlockPos(164,195,126));
        pipe(l,new BlockPos(164,195,126),new BlockPos(164,195,127));
        l.setBlock(drive,BwrBlocks.RECIRCULATION_PUMP.get().defaultBlockState(),3);
        var motor=(RecirculationPumpBlockEntity)l.getBlockEntity(drive);motor.setTargetSpeedFraction(1);
        ((net.minecraft.world.level.storage.ServerLevelData)l.getLevelData()).setGameTime(l.getGameTime()+21);
        RecirculationPumpBlockEntity.serverTick(l,drive,motor.getBlockState(),motor);
        check(reactor.getBlockPos().equals(motor.getControllerPos()),"external pump did not bind through its actual drive pipe");
        for(int i=0;i<1000;i++) { motor.energy().receiveEnergy(Integer.MAX_VALUE,false);motor.tickPump(.05); }
        var flow=RecirculationNetwork.measure(l,reactor,List.of(drive));
        check(flow.pairedJets()==2 && Math.abs(flow.fraction()-.2)<1e-6,"two connected pairs did not cap at 20%: "+flow);
        l.removeBlock(a.above(3),false);
        flow=RecirculationNetwork.measure(l,reactor,List.of(drive));
        check(flow.pairedJets()==1 && Math.abs(flow.fraction()-.1)<1e-6,"removed jet retained capacity: "+flow);
        l.removeBlock(new BlockPos(164,195,126),false);
        check(RecirculationNetwork.measure(l,reactor,List.of(drive)).fraction()==0,"disconnected jet still drives flow");
        // The RIP crosses a prepared floor opening; it contributes independently of jet drive lines.
        var rip=BwrBlocks.RIP_PUMP.get();BlockPos mount=new BlockPos(158,194,134);
        for(int i=0;i<rip.cellCount();i++) l.removeBlock(mount.offset(rip.cellOffset(i)),false);
        var rs=place(l,rip,mount,Direction.NORTH);
        reactor.markStructureDirty();ReactorControllerBlockEntity.serverTick(l,reactor.getBlockPos(),reactor.getBlockState(),reactor);
        check(reactor.isFormed(),"RIP mount invalidated the vessel or displaced a CRD: "+reactor.statusLines());
        var internal=(RecirculationPumpBlockEntity)l.getBlockEntity(mount);internal.setTargetSpeedFraction(1);
        RecirculationPumpBlockEntity.serverTick(l,mount,rs,internal);
        check(reactor.getBlockPos().equals(internal.getControllerPos()),"RIP did not bind through its vessel mount");
        var player=net.neoforged.neoforge.common.util.FakePlayerFactory.getMinecraft(l);
        player.setPos(mount.getX()+2,mount.getY(),mount.getZ()+2);
        var menu=new dev.bwr.mod.gui.RecirculationPumpMenu(1,player.getInventory(),mount);
        check(menu.stillValid(player),"internal pump control menu closes immediately");
        for(int i=0;i<1000;i++) { internal.energy().receiveEnergy(Integer.MAX_VALUE,false);internal.tickPump(.05); }
        flow=RecirculationNetwork.measure(l,reactor,List.of(drive,mount));
        check(flow.internalPumps()==1 && Math.abs(flow.fraction()-.1)<1e-6,"mounted internal pump missing from flow: "+flow);
    }
    private static void waterDischarge(ServerLevel l,PumpAssemblyBlock b,BlockState s) {
        BlockPos start=b.portPosition(ROOT,s,AssemblyPort.WATER_DISCHARGE).relative(b.portFace(s,AssemblyPort.WATER_DISCHARGE));
        if(b.portFace(s,AssemblyPort.WATER_DISCHARGE)==Direction.EAST) {
            BlockPos outside=new BlockPos(184,start.getY(),start.getZ()); pipe(l,start,outside); start=outside;
        }
        BlockPos high=new BlockPos(start.getX(),218,start.getZ()); pipe(l,start,high);
        BlockPos north=new BlockPos(start.getX(),218,137);pipe(l,high,north);
        pipe(l,north,new BlockPos(172,218,137));
        pipe(l,new BlockPos(172,218,137),new BlockPos(172,199,137));
        pipe(l,new BlockPos(172,199,137),new BlockPos(172,199,133));
        pipe(l,new BlockPos(172,199,133),new BlockPos(171,199,133));
    }
    private static void turbineFeed(ServerLevel l) {
        var reactor=vessel(l);reactor.core().initialiseHotShutdown();
        var b=BwrBlocks.TURBINE_FEED_PUMP.get(); var s=place(l,b,ROOT,Direction.NORTH);
        waterDischarge(l,b,s);
        BlockPos nozzlePos=new BlockPos(164,201,127);
        l.setBlock(nozzlePos,BwrBlocks.RPV_STEAM_OUTLET.get().defaultBlockState(),3);
        pipe(l,new BlockPos(164,201,126),new BlockPos(164,216,126));
        pipe(l,new BlockPos(164,216,126),new BlockPos(180,216,126));
        pipe(l,new BlockPos(180,216,126),new BlockPos(180,216,141));
        pipe(l,new BlockPos(180,216,141),new BlockPos(180,213,141));
        BlockPos export=new BlockPos(180,211,146);
        l.setBlock(export,BwrBlocks.TURBINE_STEAM_OUTLET.get().defaultBlockState(),3);
        pipe(l,new BlockPos(180,211,143),new BlockPos(180,211,145));
        var outlet=(dev.bwr.mod.steam.TurbineSteamOutletBlockEntity)l.getBlockEntity(export);outlet.setCommandedFlowKgPerS(500);
        var nozzle=(RpvSteamOutletBlockEntity)l.getBlockEntity(nozzlePos);
        nozzle.noteController(reactor.getBlockPos());nozzle.setPosition(1);nozzle.refreshAttachment(l);nozzle.refreshLine(l);
        var pump=(FeedwaterPumpBlockEntity)l.getBlockEntity(ROOT);pump.setComputerControlled(true);pump.setRunning(true);
        double steam=0,water=0;long exported=0;
        for(int i=0;i<1200;i++) {
            ((net.minecraft.world.level.storage.ServerLevelData)l.getLevelData()).setGameTime(l.getGameTime()+1);
            double supply=nozzle.flowKgPerS(reactor.core().getPressurePsig());
            pump.suction().fill(new FluidStack(Fluids.WATER,2000),IFluidHandler.FluidAction.EXECUTE);
            exported+=outlet.drainMilliBuckets(Long.MAX_VALUE,false);
            FeedwaterPumpBlockEntity.serverTick(l,ROOT,s,pump);
            double used=pump.getSteamDrawKgPerS(); steam+=used*.05;water+=pump.getDeliveredFlowKgPerS()*.05;
            check(used+nozzle.claimFlowKgPerS(l.getGameTime(),Double.MAX_VALUE)<=supply+1e-8,"RFPT duplicated the nozzle ledger");
        }
        exported+=outlet.drainMilliBuckets(Long.MAX_VALUE,false);
        check(water>0 && steam>0,"piped RFPT failed to pump: "+pump.statusLines());
        check(Math.abs(steam*1000-exported)<1.01,"RFPT exhaust mass differs from claimed steam");
        l.removeBlock(new BlockPos(180,211,144),false);
        FeedwaterPumpBlockEntity.serverTick(l,ROOT,s,pump);
        check(pump.getSteamDrawKgPerS()==0 && pump.isRunning(),"broken exhaust consumed steam or changed command");
        var saved=outlet.saveWithFullMetadata(l.registryAccess());
        check(saved.getBoolean("PumpExhaustReceiver"),"receiver mode was not persisted");
        dev.bwr.mod.steam.TurbineSteamOutletBlockEntity.serverTick(l,export,outlet.getBlockState(),outlet);
        check(outlet.getBufferedMilliBuckets()==0,"disconnected exhaust receiver manufactured direct vessel steam");
    }
    private static void motorEccs(ServerLevel l,PumpAssemblyBlock b) {
        var reactor=vessel(l);
        for(var loop:CoreSpraySpargerBlock.Loop.values()) for(int x=159;x<=169;x++) for(int z=128;z<=138;z++)
            if(x==159 || x==169 || z==128 || z==138) l.setBlock(new BlockPos(x,CoreSpraySpargerBlock.requiredY(loop,reactor.structure().topOfActiveFuelY()),z),
                    BwrBlocks.CORE_SPRAY_SPARGER.get().defaultBlockState().setValue(CoreSpraySpargerBlock.LOOP,loop),3);
        reactor.markStructureDirty();ReactorControllerBlockEntity.serverTick(l,reactor.getBlockPos(),reactor.getBlockState(),reactor);
        check(reactor.sprayRingCompleteness()>0,"test sparger rings missing");
        var s=place(l,b,ROOT,Direction.NORTH);waterDischarge(l,b,s);
        BlockPos source=b.portPosition(ROOT,s,AssemblyPort.WATER_SUCTION).west();
        BlockPos tank=source.west(2);pipe(l,source,source.west());
        l.setBlock(tank,BwrBlocks.CONDENSATE_STORAGE_TANK.get().defaultBlockState(),3);
        var tankBe=(CondensateStorageTankBlockEntity)l.getBlockEntity(tank);tankBe.fillKg(100000);
        var pump=(EccsPumpBlockEntity)l.getBlockEntity(ROOT);pump.setComputerControlled(true);pump.setSuctionSource(SuctionSource.CONDENSATE_TANK);pump.setRunning(true);
        double before=tankBe.storedKg(),mass=0;
        for(int i=0;i<800;i++) { pump.energy().receiveEnergy(Integer.MAX_VALUE,false);EccsPumpBlockEntity.serverTick(l,ROOT,s,pump);mass+=pump.getDeliveredFlowKgPerS()*.05; }
        check(mass>0 && Math.abs(before-tankBe.storedKg()-mass)<1.01,"motor ECCS failed water accounting "+pump.statusLines());
        l.removeBlock(source,false);EccsPumpBlockEntity.serverTick(l,ROOT,s,pump);
        check(pump.getDeliveredFlowKgPerS()==0 && pump.isRunning(),"broken motor suction still delivered or changed command");
    }
}
