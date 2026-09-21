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
            BwrBlocks.MOTOR_FEED_PUMP.get(),BwrBlocks.TURBINE_FEED_PUMP.get(),BwrBlocks.JET_PUMP.get(),BwrBlocks.RIP_PUMP.get(),BwrBlocks.RECIRCULATION_PUMP.get()}; }
    private static void clear(ServerLevel l) {
        for(BlockPos p:BlockPos.betweenClosed(new BlockPos(155,190,125),new BlockPos(191,235,153))) if(!l.getBlockState(p).isAir()) l.removeBlock(p,false);
        l.getEntitiesOfClass(ItemEntity.class,new AABB(155,190,125,192,236,154)).forEach(net.minecraft.world.entity.Entity::discard);
    }
    private static BlockState place(ServerLevel l,PumpAssemblyBlock b,BlockPos root,Direction d) {
        var s=b.placementState().setValue(PumpAssemblyBlock.FACING,d);
        l.setBlock(root,s,3); b.setPlacedBy(l,root,s,null,new ItemStack(b));
        check(b.complete(l,root,s),"placement incomplete "+b+" "+d); return s;
    }
    private static BlockPos pos(PumpAssemblyBlock b,BlockPos root,BlockState s,int cell) {
        return root.offset(TurbineAssemblyBlock.turn(b.cellOffset(s,cell),s.getValue(PumpAssemblyBlock.FACING)));
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
            for(Direction d:Direction.Plane.HORIZONTAL) {
                clear(l);
                try { legacyDvss(l,d);checks++; } catch(RuntimeException | AssertionError e) { failures++;LogUtils.getLogger().error("Legacy DVSS FAIL: {}",d,e); }
            }
            clear(l);
            try { circuits(l); checks++; } catch(RuntimeException | AssertionError e) { failures++; LogUtils.getLogger().error("Pump circuit FAIL",e); }
            for (var block : blocks()) if (block instanceof ModernPumpAssemblyBlock) for (Direction direction : Direction.Plane.HORIZONTAL) {
                clear(l);
                try { legacyModernPump(l, block, direction); checks++; }
                catch (RuntimeException | AssertionError e) { failures++; LogUtils.getLogger().error("Legacy pump layout FAIL: {} {}", block, direction, e); }
            }
            clear(l);
            try { pipeDyes(l); checks++; } catch (RuntimeException | AssertionError e) { failures++; LogUtils.getLogger().error("Pipe dye FAIL", e); }
            clear(l);
            try { waterBoundary(l); checks++; } catch(RuntimeException | AssertionError e) { failures++; LogUtils.getLogger().error("Water boundary FAIL",e); }
            if(net.neoforged.fml.ModList.get().isLoaded("mekanism")) for(var b:new PumpAssemblyBlock[]{BwrBlocks.LPCS_PUMP.get(),BwrBlocks.HPCS_PUMP.get(),BwrBlocks.RHR_PUMP.get(),BwrBlocks.MOTOR_FEED_PUMP.get()}) {
                clear(l);
                try { mechanicalPipe(l,b); checks++; } catch(Exception | AssertionError e) { failures++; LogUtils.getLogger().error("Mekanism suction FAIL: {}",b,e); }
            }
            clear(l);
            try { recirculation(l); checks++; } catch(RuntimeException | AssertionError e) { failures++; LogUtils.getLogger().error("Jet recirculation FAIL",e); }
            clear(l);
            try { jetRows(l); checks++; } catch(RuntimeException | AssertionError e) { failures++; LogUtils.getLogger().error("Jet row pairing FAIL",e); }
            clear(l);
            try { rodTravelNbt(); checks++; } catch(RuntimeException | AssertionError e) { failures++; LogUtils.getLogger().error("Rod travel NBT FAIL",e); }
            clear(l);
            try { dvssLoop(l); checks++; } catch(RuntimeException | AssertionError e) { failures++; LogUtils.getLogger().error("DVSS recirculation FAIL",e); }
            clear(l);
            try { recirculationVolume(l); checks++; } catch(RuntimeException | AssertionError e) { failures++; LogUtils.getLogger().error("Vessel volume sizing FAIL",e); }
            clear(l);
            try { recirculationDriveLimit(l); checks++; } catch(RuntimeException | AssertionError e) { failures++; LogUtils.getLogger().error("Recirculation drive limit FAIL",e); }
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
            BlockPos tube=p.relative(face);
            var correct=port.role().isSteam()?BwrBlocks.PRESSURISED_TUBE.get().stateWithConnections(l,tube):BwrBlocks.HIGH_PRESSURE_WATER_PIPE.get().stateWithConnections(l,tube);
            l.setBlock(tube,correct,3);
            check(l.getBlockState(tube).getValue(PipeBlock.PROPERTY_BY_DIRECTION.get(face.getOpposite())),"tube does not connect to flange");
            l.removeBlock(tube,false);
            var wrong=port.role().isSteam()?BwrBlocks.HIGH_PRESSURE_WATER_PIPE.get().stateWithConnections(l,tube):BwrBlocks.PRESSURISED_TUBE.get().stateWithConnections(l,tube);
            l.setBlock(tube,wrong,3);
            check(!l.getBlockState(tube).getValue(PipeBlock.PROPERTY_BY_DIRECTION.get(face.getOpposite())),"wrong pipe connected to flange");
            l.removeBlock(tube,false);
        }
        if(b.kind()!=PumpAssemblyBlock.Kind.JET) {
            BlockPos child=pos(b,ROOT,s,b.cellCount()-1);
            check(l.getCapability(Capabilities.EnergyStorage.BLOCK,child,Direction.UP)!=null,"part cannot access shared power");
            var player=net.neoforged.neoforge.common.util.FakePlayerFactory.getMinecraft(l);
            player.setPos(child.getX()+.5,child.getY()+1,child.getZ()+.5);
            var panel=new dev.bwr.mod.gui.PumpControlMenu(2,player.getInventory(),ROOT,child);
            check(panel.stillValid(player),"panel cannot open from far model cell");
            panel.handleCommand(player,dev.bwr.mod.gui.PumpControlMenu.CONTROL,0,0);
            panel.handleCommand(player,dev.bwr.mod.gui.PumpControlMenu.SPEED,420,0);
            panel.handleCommand(player,dev.bwr.mod.gui.PumpControlMenu.RUN,1,0);panel.sample();
            check(Math.abs(panel.target-.42)<1e-9 && panel.running,"manual panel did not command actual speed and start");
            panel.handleCommand(player,dev.bwr.mod.gui.PumpControlMenu.CONTROL,2,0);
            panel.handleCommand(player,dev.bwr.mod.gui.PumpControlMenu.SPEED,900,0);panel.sample();
            check(Math.abs(panel.target-.42)<1e-9,"panel fought computer control");
            panel.handleCommand(player,dev.bwr.mod.gui.PumpControlMenu.CONTROL,0,0);
            if(l.getBlockEntity(ROOT) instanceof EccsPumpBlockEntity ep) {
                panel.handleCommand(player,dev.bwr.mod.gui.PumpControlMenu.SUCTION,0,0);
                check(ep.getSuctionSource()==SuctionSource.CONDENSATE_TANK,"panel did not select piped water");
                check(ep.statusLines().stream().noneMatch(line->line.contains("steam admission")),"electric pump still reports steam admission");
                var saved=ep.saveWithFullMetadata(l.registryAccess());
                check(saved.getBoolean("PanelControlled"),"manual ownership lost on save");
            }
            player.setPos(child.getX()+30,child.getY(),child.getZ());
            check(!panel.stillValid(player),"remote player could command pump");

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
        check(!saved.getValue(PumpAssemblyBlock.ASSEMBLED) && saved.getValue(PumpAssemblyBlock.CELL)==b.controllerCell(saved),"old save expanded or lost its owner");
        l.setBlock(ROOT.east(),Blocks.STONE.defaultBlockState(),3); l.setBlock(ROOT,saved,3);
        check(l.getBlockState(ROOT.east()).is(Blocks.STONE),"migration overwrote neighbour");
        l.removeBlock(ROOT,false); l.removeBlock(ROOT.east(),false);
        var context=new DirectionalPlaceContext(l,ROOT,Direction.DOWN,new ItemStack(b),Direction.UP);
        var intended=b.getStateForPlacement(context); check(intended!=null,"empty footprint refused");
        BlockPos obstruction=pos(b,ROOT,intended,b.cellCount()-1); l.setBlock(obstruction,Blocks.STONE.defaultBlockState(),3);
        check(b.getStateForPlacement(context)==null,"blocked footprint accepted");
    }
    static ReactorControllerBlockEntity vessel(ServerLevel l) {
        return vessel(l,11,9,11);
    }
    private static ReactorControllerBlockEntity vessel(ServerLevel l,int width,int height,int depth) {
        int right=159+width,top=195+height,back=128+depth;
        for(BlockPos p:BlockPos.betweenClosed(new BlockPos(158,194,127),new BlockPos(right,top,back))) {
            boolean wall=p.getX()==158 || p.getX()==right || p.getY()==194 || p.getY()==top || p.getZ()==127 || p.getZ()==back;
            l.setBlock(p,(wall?BwrBlocks.REACTOR_VESSEL.get():Blocks.AIR).defaultBlockState(),3);
        }
        BlockPos controller=new BlockPos(right,200,128+depth/2);
        l.setBlock(controller.below(),BwrBlocks.RPV_WATER_INJECTION_PORT.get().defaultBlockState().setValue(RpvWaterInjectionPortBlock.FACING,Direction.EAST),3);
        l.setBlock(controller,BwrBlocks.REACTOR_CONTROLLER.get().defaultBlockState(),3);
        for(int x=160;x<right;x+=2) for(int z=129;z<back;z+=2) l.setBlock(new BlockPos(x,193,z),BwrBlocks.CONTROL_ROD_DRIVE.get().defaultBlockState(),3);
        var be=(ReactorControllerBlockEntity)l.getBlockEntity(controller);
        ReactorControllerBlockEntity.serverTick(l,controller,be.getBlockState(),be);
        check(be.isFormed(),"test vessel not formed: "+be.statusLines()); return be;
    }

    private static void legacyModernPump(ServerLevel level, PumpAssemblyBlock block, Direction direction) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Name", net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block).toString());
        CompoundTag properties = new CompoundTag();
        properties.putString("assembled", "true"); properties.putString("cell", "0");
        properties.putString("facing", direction.getName()); tag.put("Properties", properties);
        var state = NbtUtils.readBlockState(level.holderLookup(net.minecraft.core.registries.Registries.BLOCK), tag);
        check(!state.getValue(ModernPumpAssemblyBlock.MODERN), "save without modern flag expanded");
        level.setBlock(ROOT, state, 3);
        block.setPlacedBy(level, ROOT, state, null, new ItemStack(block));
        check(block.complete(level, ROOT, state), "legacy assembly incomplete");
        var owner = level.getBlockEntity(ROOT);
        check(owner != null, "legacy pump lost controller");
        for (int i=0; i<block.cellCount(state); i++) {
            var p=pos(block,ROOT,state,i);
            check(block.origin(p,level.getBlockState(p)).equals(ROOT), "legacy cell resolved wrong owner");
            check(block.controller(level,p,level.getBlockState(p))==owner, "legacy controller forwarding split");
        }
        for (var port:block.ports(state)) {
            var p=block.portPosition(ROOT,state,port.role()); var face=block.portFace(state,port.role());
            check(block.portAt(level.getBlockState(p),face)==port.role(), "legacy port moved");
        }
        BlockPos neighbour=ROOT.below(); level.setBlock(neighbour,Blocks.STONE.defaultBlockState(),3);
        level.removeBlock(pos(block,ROOT,state,block.cellCount(state)-1),false);
        check(level.getBlockState(ROOT).isAir() && level.getBlockState(neighbour).is(Blocks.STONE), "legacy removal changed neighbour or left root");
    }

    private static void pipeDyes(ServerLevel level) {
        var player=net.neoforged.neoforge.common.util.FakePlayerFactory.getMinecraft(level);
        boolean oldCreative=player.getAbilities().instabuild;
        boolean oldBuild=player.getAbilities().mayBuild;
        try {
            player.getAbilities().instabuild=false; player.getAbilities().mayBuild=true;
            for (var block:new dev.bwr.mod.piping.PaintedPipeBlock[]{BwrBlocks.HIGH_PRESSURE_WATER_PIPE.get(), BwrBlocks.PRESSURISED_TUBE.get()}) {
                level.setBlock(ROOT,block.defaultBlockState(),3);
                level.setBlock(ROOT.east(),block.defaultBlockState(),3);
                var opposite=block==BwrBlocks.HIGH_PRESSURE_WATER_PIPE.get()?BwrBlocks.PRESSURISED_TUBE.get():BwrBlocks.HIGH_PRESSURE_WATER_PIPE.get();
                level.setBlock(ROOT.west(),opposite.defaultBlockState(),3);
                for (DyeColor dye:DyeColor.values()) {
                    ItemStack stack=new ItemStack(DyeItem.byColor(dye),3);
                    var hit=new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(ROOT),Direction.UP,ROOT,false);
                    var state=level.getBlockState(ROOT);
                    state.useItemOn(stack,level,player,net.minecraft.world.InteractionHand.MAIN_HAND,hit);
                    state=level.getBlockState(ROOT);
                    check(stack.getCount()==2, "dye was not consumed exactly once");
                    var paint=dev.bwr.mod.piping.PipePaint.of(dye);
                    check(state.getValue(dev.bwr.mod.piping.PaintedPipeBlock.PAINT)==paint, "wrong dye applied");
                    check(state.getValue(PipeBlock.EAST) && !state.getValue(PipeBlock.WEST), "dye altered process connectivity");
                    state.useItemOn(stack,level,player,net.minecraft.world.InteractionHand.MAIN_HAND,hit);
                    check(stack.getCount()==2, "same-color repaint consumed dye");
                    var loaded=NbtUtils.readBlockState(level.holderLookup(net.minecraft.core.registries.Registries.BLOCK),NbtUtils.writeBlockState(state));
                    check(loaded.equals(state), "pipe paint did not survive NBT");
                    check(state.rotate(Rotation.CLOCKWISE_90).getValue(dev.bwr.mod.piping.PaintedPipeBlock.PAINT)==paint, "rotation lost paint");
                    level.removeBlock(ROOT.east(),false);
                    check(level.getBlockState(ROOT).getValue(dev.bwr.mod.piping.PaintedPipeBlock.PAINT)==paint, "neighbor update lost paint");
                    level.setBlock(ROOT.east(),block.defaultBlockState(),3);
                }
                player.getAbilities().instabuild=true;
                ItemStack stack=new ItemStack(Items.WHITE_DYE,2);
                var hit=new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(ROOT),Direction.UP,ROOT,false);
                level.getBlockState(ROOT).useItemOn(stack,level,player,net.minecraft.world.InteractionHand.MAIN_HAND,hit);
                check(stack.getCount()==2,"creative paint consumed dye"); player.getAbilities().instabuild=false;
                level.removeBlock(ROOT,false);level.removeBlock(ROOT.east(),false);level.removeBlock(ROOT.west(),false);
            }
        } finally {
            player.getAbilities().instabuild=oldCreative;player.getAbilities().mayBuild=oldBuild;
        }
    }
    private static void legacyDvss(ServerLevel l,Direction d) {
        var b=BwrBlocks.RECIRCULATION_PUMP.get();
        // Reconstruct an actual pre-resize saved state: it has no "enlarged" property.
        CompoundTag tag=new CompoundTag(),properties=new CompoundTag();
        tag.putString("Name","bwr:recirculation_pump");tag.put("Properties",properties);
        properties.putString("assembled","true");properties.putString("cell","4");properties.putString("facing",d.getName());
        var s=NbtUtils.readBlockState(l.holderLookup(net.minecraft.core.registries.Registries.BLOCK),tag);
        check(!s.getValue(RecirculationPumpBlock.ENLARGED) && b.cellCount(s)==54 && b.controllerCell(s)==4,"legacy footprint changed");
        l.setBlock(ROOT,s,3);b.setPlacedBy(l,ROOT,s,null,new ItemStack(b));
        check(b.complete(l,ROOT,s),"old assembled pump incomplete");
        int owners=0;
        for(int i=0;i<b.cellCount(s);i++) {
            BlockPos p=pos(b,ROOT,s,i);check(b.origin(p,l.getBlockState(p)).equals(ROOT),"legacy part points at wrong owner");
            if(l.getBlockEntity(p)!=null)owners++;
        }
        check(owners==1,"legacy model duplicated pump entity");
        for(var port:b.ports(s)) {
            var p=ROOT.offset(TurbineAssemblyBlock.turn(port.cell(),d));var face=TurbineAssemblyBlock.turn(port.face(),d);
            check(b.portAt(l.getBlockState(p),face)==port.role(),"legacy flange shifted");
            var tube=p.relative(face);l.setBlock(tube,BwrBlocks.HIGH_PRESSURE_WATER_PIPE.get().stateWithConnections(l,tube),3);
            check(l.getBlockState(tube).getValue(PipeBlock.PROPERTY_BY_DIRECTION.get(face.getOpposite())),"legacy flange lost pipe connection");
        }
        var pump=(RecirculationPumpBlockEntity)l.getBlockEntity(ROOT);pump.setTargetSpeedFraction(.37);
        var reloaded=new RecirculationPumpBlockEntity(ROOT,s);reloaded.loadWithComponents(pump.saveWithFullMetadata(l.registryAccess()),l.registryAccess());
        check(reloaded.getTargetSpeedFraction()==.37,"legacy speed lost on reload");
        var neighbor=ROOT.offset(TurbineAssemblyBlock.turn(new BlockPos(2,0,0),d));l.setBlock(neighbor,Blocks.STONE.defaultBlockState(),3);
        check(b.complete(l,ROOT,s),"legacy footprint expanded into neighbour");
        l.destroyBlock(ROOT,true);
        check(l.getBlockState(neighbor).is(Blocks.STONE),"legacy cleanup deleted neighbour");
        long drops=l.getEntitiesOfClass(ItemEntity.class,new AABB(ROOT).inflate(10)).stream().filter(e->e.getItem().is(b.asItem())).mapToInt(e->e.getItem().getCount()).sum();
        check(drops==1,"legacy pump dropped incorrect item count");
        for(int i=0;i<b.cellCount(s);i++)check(!l.getBlockState(pos(b,ROOT,s,i)).is(b),"legacy pump left orphan");
    }
    private static void pipe(ServerLevel l,BlockPos a,BlockPos b) { pipe(l,a,b,BwrBlocks.HIGH_PRESSURE_WATER_PIPE.get()); }
    private static void steamPipe(ServerLevel l,BlockPos a,BlockPos b) { pipe(l,a,b,BwrBlocks.PRESSURISED_TUBE.get()); }
    private static void pipe(ServerLevel l,BlockPos a,BlockPos b,Block material) {
        int dx=Integer.signum(b.getX()-a.getX()),dy=Integer.signum(b.getY()-a.getY()),dz=Integer.signum(b.getZ()-a.getZ());
        check(Math.abs(dx)+Math.abs(dy)+Math.abs(dz)<=1,"bad fixture pipe segment");
        for(BlockPos p=a;;p=p.offset(dx,dy,dz)) { l.setBlock(p,material.defaultBlockState(),3); if(p.equals(b))break; }
    }
    private static void circuits(ServerLevel l) {
        var reactor=vessel(l); var b=BwrBlocks.MOTOR_FEED_PUMP.get(); var s=place(l,b,ROOT,Direction.NORTH);
        BlockPos suction=b.portPosition(ROOT,s,AssemblyPort.WATER_SUCTION).above();
        BlockPos tank=suction.above(3); l.setBlock(tank,BwrBlocks.CONDENSATE_STORAGE_TANK.get().defaultBlockState(),3);
        pipe(l,suction,suction.above(2));
        waterDischarge(l,b,s);
        var t=(CondensateStorageTankBlockEntity)l.getBlockEntity(tank);t.fillKg(50000);
        BlockPos branch=suction.above().west();
        l.setBlock(branch,BwrBlocks.MSIV.get().defaultBlockState(),3);
        var valve=(dev.bwr.mod.steam.MainSteamIsolationValveBlockEntity)l.getBlockEntity(branch);
        valve.setDemandOpen(false);valve.tickValve(10);
        check(AssemblyPlumbing.trace(l,ROOT,s,AssemblyPort.WATER_SUCTION).opening()>0,"steam valve affected the water header");
        var pump=(FeedwaterPumpBlockEntity)l.getBlockEntity(ROOT);pump.setComputerControlled(true);pump.setRunning(true);
        double before=t.storedKg(),mass=0;
        for(int i=0;i<800;i++) {
            pump.energy().receiveEnergy(Integer.MAX_VALUE,false);
            FeedwaterPumpBlockEntity.serverTick(l,ROOT,s,pump);mass+=pump.getDeliveredFlowKgPerS()*.05;
        }
        check(mass>0,"connected motor feed pump did not deliver: "+pump.statusLines());
        check(Math.abs(before-t.storedKg()-mass)<1.01,"feedwater created water");
        l.removeBlock(b.portPosition(ROOT,s,AssemblyPort.WATER_DISCHARGE).above(2),false); FeedwaterPumpBlockEntity.serverTick(l,ROOT,s,pump);
        check(pump.getDeliveredFlowKgPerS()==0 && pump.isRunning(),"broken discharge delivered water or changed command");
    }
    private static void recirculation(ServerLevel l) {
        var reactor=vessel(l); var jet=BwrBlocks.JET_PUMP.get();
        check(RecirculationNetwork.sizing(reactor.structure()).requiredJets()==22,"fixture flow target did not scale with volume");
        BlockPos a=new BlockPos(160,195,128),b=new BlockPos(168,195,138),drive=new BlockPos(174,195,133);
        place(l,jet,a,Direction.NORTH);place(l,jet,b,Direction.SOUTH);
        check(jet.ports().isEmpty(),"jet mesh still exposes added external ports");
        BlockPos upper=new BlockPos(170,201,130),lower=new BlockPos(170,195,130);
        l.setBlock(upper,BwrBlocks.RECIRCULATION_OUTLET.get().defaultBlockState().setValue(RpvWaterInjectionPortBlock.FACING,Direction.EAST),3);
        l.setBlock(lower,BwrBlocks.RECIRCULATION_INLET.get().defaultBlockState().setValue(RpvWaterInjectionPortBlock.FACING,Direction.EAST),3);
        reactor.markStructureDirty();ReactorControllerBlockEntity.serverTick(l,reactor.getBlockPos(),reactor.getBlockState(),reactor);
        check(reactor.isFormed(),"recirculation nozzles broke vessel");
        pipe(l,new BlockPos(174,195,134),new BlockPos(174,201,134));
        pipe(l,new BlockPos(174,201,134),new BlockPos(174,201,130));
        pipe(l,new BlockPos(174,201,130),new BlockPos(171,201,130));
        pipe(l,new BlockPos(174,195,132),new BlockPos(174,195,130));
        pipe(l,new BlockPos(174,195,130),new BlockPos(171,195,130));
        l.setBlock(drive,BwrBlocks.RECIRCULATION_PUMP.get().defaultBlockState(),3);
        check(RecirculationCircuit.controller(l,drive)==reactor,"upper/lower vessel loop did not connect");
        var saved=l.getBlockState(lower);l.setBlock(lower,saved.setValue(RpvWaterInjectionPortBlock.FACING,Direction.WEST),3);
        check(RecirculationCircuit.controller(l,drive)==null,"backwards return port accepted");l.setBlock(lower,saved,3);
        var motor=(RecirculationPumpBlockEntity)l.getBlockEntity(drive);motor.setTargetSpeedFraction(1);
        ((net.minecraft.world.level.storage.ServerLevelData)l.getLevelData()).setGameTime(l.getGameTime()+21);
        RecirculationPumpBlockEntity.serverTick(l,drive,motor.getBlockState(),motor);
        check(reactor.getBlockPos().equals(motor.getControllerPos()),"external pump did not bind through both vessel ports");
        for(int i=0;i<1000;i++) { motor.energy().receiveEnergy(Integer.MAX_VALUE,false);motor.tickPump(.05); }
        var flow=RecirculationNetwork.measure(l,reactor,List.of(drive));
        check(flow.pairedJets()==2 && Math.abs(flow.fraction()-2.0/22)<1e-6,"two matched assemblies did not limit flow: "+flow);
        l.removeBlock(a.above(3),false);
        flow=RecirculationNetwork.measure(l,reactor,List.of(drive));
        check(flow.pairedJets()==0 && flow.unmatchedJets()==1 && Math.abs(flow.fraction()-1.0/22)<1e-6,"removed partner retained capacity: "+flow);
        l.removeBlock(new BlockPos(173,195,130),false);
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
        check(flow.internalPumps()==1 && Math.abs(flow.fraction()-1.2/22)<1e-6,"mounted internal pump missing from flow: "+flow);
    }
    private static void dvssLoop(ServerLevel l) {
        var reactor=vessel(l);var block=BwrBlocks.RECIRCULATION_PUMP.get();
        BlockPos root=new BlockPos(176,195,133),upper=new BlockPos(170,201,130),lower=new BlockPos(170,195,130);
        l.setBlock(upper,BwrBlocks.RECIRCULATION_OUTLET.get().defaultBlockState().setValue(RpvWaterInjectionPortBlock.FACING,Direction.EAST),3);
        l.setBlock(lower,BwrBlocks.RECIRCULATION_INLET.get().defaultBlockState().setValue(RpvWaterInjectionPortBlock.FACING,Direction.EAST),3);
        reactor.markStructureDirty();ReactorControllerBlockEntity.serverTick(l,reactor.getBlockPos(),reactor.getBlockState(),reactor);
        var state=place(l,block,root,Direction.NORTH);
        pipe(l,new BlockPos(176,195,136),new BlockPos(176,201,136));
        pipe(l,new BlockPos(176,201,136),new BlockPos(172,201,136));
        pipe(l,new BlockPos(172,201,136),new BlockPos(172,201,130));
        pipe(l,new BlockPos(172,201,130),new BlockPos(171,201,130));
        pipe(l,new BlockPos(176,197,130),new BlockPos(176,195,130));
        pipe(l,new BlockPos(176,195,130),new BlockPos(171,195,130));
        check(RecirculationCircuit.controller(l,root)==reactor,"DVSS lower suction / front discharge not connected");
        check(RecirculationCircuit.controller(l,root.above(4))==reactor,"DVSS child did not resolve loop controller");
        var motor=(RecirculationPumpBlockEntity)l.getBlockEntity(root);motor.setTargetSpeedFraction(1);
        RecirculationPumpBlockEntity.serverTick(l,root,state,motor);
        for(int i=0;i<500;i++) {
            motor.energy().receiveEnergy(Integer.MAX_VALUE,false);
            ReactorControllerBlockEntity.serverTick(l,reactor.getBlockPos(),reactor.getBlockState(),reactor);
        }
        check(reactor.core().getCoreFlowFraction()>1.0/22*.9,"no-jet loop never reached the core solver");
        var info=dev.bwr.mod.gui.ReactorConfigurationInfo.capture(reactor);
        check(info.externalPumps()==1 && info.flowCeilingFraction()==1.0/22 && info.flowSupportedMW()==0
                && info.interiorVolume()==1089 && info.requiredJets()==22 && info.requiredExternalPumps()==3,"empty-core configuration info is wrong: "+info);
        int slot=dev.bwr.mod.gui.CoreLattice.corePositions(reactor.core().getCoreLoading().latticeWidth(),reactor.assemblyCount())[0];
        reactor.core().getCoreLoading().load(slot,new dev.bwr.core.fuel.FuelAssembly(dev.bwr.core.fuel.FuelType.LEU));
        info=dev.bwr.mod.gui.ReactorConfigurationInfo.capture(reactor);
        check(Math.abs(info.fuelLoadedRatingMW()-reactor.getConfiguredRatedThermalMW()/reactor.assemblyCount())<1e-9
                && info.flowSupportedMW()==info.fuelLoadedRatingMW() && info.steamEquivalentKgPerS()>0,"loaded-fuel planning estimate is wrong: "+info);
        reactor.core().getCoreLoading().unload(slot);
        var buffer=new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        try {info.write(buffer);check(info.equals(dev.bwr.mod.gui.ReactorConfigurationInfo.read(buffer)) && !buffer.isReadable(),"configuration snapshot did not round trip");}
        finally {buffer.release();}
        double water=reactor.core().getLiquidMassKg(),temperature=reactor.core().getCoolantTemperatureC();
        for(int i=0;i<10;i++)RecirculationNetwork.measure(l,reactor,List.of(root));
        check(water==reactor.core().getLiquidMassKg() && temperature==reactor.core().getCoolantTemperatureC(),"closed recirculation created water or cooling");
        var jet=BwrBlocks.JET_PUMP.get();BlockPos a=new BlockPos(160,196,128),b=new BlockPos(168,196,138);
        place(l,jet,a,Direction.NORTH);
        ((net.minecraft.world.level.storage.ServerLevelData)l.getLevelData()).setGameTime(l.getGameTime()+21);
        var flow=RecirculationNetwork.measure(l,reactor,List.of(root));
        check(flow.unmatchedJets()==1 && flow.maximum()==1.0/22,"single unopposed jet gained capacity");
        place(l,jet,new BlockPos(164,196,128),Direction.NORTH);
        ((net.minecraft.world.level.storage.ServerLevelData)l.getLevelData()).setGameTime(l.getGameTime()+21);
        flow=RecirculationNetwork.measure(l,reactor,List.of(root));
        check(flow.unmatchedJets()==2 && flow.maximum()==1.0/22,"same-side jets gained paired capacity");
        place(l,jet,b,Direction.SOUTH);
        ((net.minecraft.world.level.storage.ServerLevelData)l.getLevelData()).setGameTime(l.getGameTime()+21);
        flow=RecirculationNetwork.measure(l,reactor,List.of(root));
        check(flow.pairedJets()==2 && flow.unmatchedJets()==1 && flow.maximum()==2.0/22,"opposing elevated jets did not match: "+flow);
        BlockPos second=new BlockPos(181,195,133);
        l.setBlock(second,block.defaultBlockState(),3);
        pipe(l,new BlockPos(176,195,136),new BlockPos(181,195,136));
        pipe(l,new BlockPos(181,195,136),new BlockPos(181,195,134));
        pipe(l,new BlockPos(181,195,132),new BlockPos(181,195,130));
        pipe(l,new BlockPos(181,195,130),new BlockPos(176,195,130));
        var parallel=RecirculationNetwork.measure(l,reactor,List.of(root,second));
        check(parallel.externalPumps()==2 && Math.abs(parallel.fraction()-flow.fraction())<1e-9,"stopped parallel pump diluted running pump: "+parallel);
        motor.setTargetSpeedFraction(.5);
        for(int i=0;i<2500;i++){motor.energy().receiveEnergy(Integer.MAX_VALUE,false);motor.tickPump(.05);}
        flow=RecirculationNetwork.measure(l,reactor,List.of(root));
        check(Math.abs(flow.fraction()-1.0/22)<1e-5,"half-speed DVSS did not halve limited core flow: "+flow);
        l.removeBlock(b,false);place(l,jet,b.above(),Direction.SOUTH);
        ((net.minecraft.world.level.storage.ServerLevelData)l.getLevelData()).setGameTime(l.getGameTime()+21);
        check(!RecirculationNetwork.installedJet(l,reactor.structure(),b.above()) && RecirculationNetwork.measure(l,reactor,List.of(root)).pairedJets()==0,"too-high jet counted");
        // Crossing the two headers cannot become a bypass that claims a working closed loop.
        pipe(l,new BlockPos(172,195,130),new BlockPos(172,201,130));
        check(RecirculationCircuit.controller(l,root)==null,"shorted suction and discharge headers accepted");
        for(int y=196;y<201;y++)l.removeBlock(new BlockPos(172,y,130),false);
        check(RecirculationCircuit.controller(l,root)==reactor,"loop did not recover after removing bypass");
        l.removeBlock(root.above(4),false);
        check(RecirculationCircuit.controller(l,root)==null && RecirculationNetwork.measure(l,reactor,List.of(root)).fraction()==0,"broken DVSS retained forced flow");
    }
    private static RecirculationNetwork.Flow freshJets(ServerLevel l,ReactorControllerBlockEntity reactor) {
        ((net.minecraft.world.level.storage.ServerLevelData)l.getLevelData()).setGameTime(l.getGameTime()+21);
        return RecirculationNetwork.measure(l,reactor,List.of());
    }
    private static void recirculationVolume(ServerLevel l) {
        var reactor=vessel(l,5,8,5);var core=reactor.core();
        var info=dev.bwr.mod.gui.ReactorConfigurationInfo.capture(reactor);
        check(info.interiorVolume()==200 && info.requiredJets()==12 && info.requiredExternalPumps()==2,"minimum vessel target");
        int slot=dev.bwr.mod.gui.CoreLattice.corePositions(core.getCoreLoading().latticeWidth(),reactor.assemblyCount())[0];
        core.getCoreLoading().load(slot,new dev.bwr.core.fuel.FuelAssembly(dev.bwr.core.fuel.FuelType.LEU));
        // Raise only the roof: the controller, rods, fuel, bottom head and operating core remain.
        for(var p:BlockPos.betweenClosed(new BlockPos(158,203,127),new BlockPos(164,211,133))) {
            boolean shell=p.getX()==158 || p.getX()==164 || p.getZ()==127 || p.getZ()==133 || p.getY()==211;
            l.setBlock(p,(shell?BwrBlocks.REACTOR_VESSEL.get():Blocks.AIR).defaultBlockState(),3);
        }
        double beforeFlow=core.getCoreFlowKgPerS(),beforeMass=core.getLiquidMassKg();
        reactor.markStructureDirty();ReactorControllerBlockEntity.serverTick(l,reactor.getBlockPos(),reactor.getBlockState(),reactor);
        check(reactor.isFormed() && reactor.core()==core && core.getCoreLoading().loadedAssemblyCount()==1,"height change replaced operating core or fuel");
        info=dev.bwr.mod.gui.ReactorConfigurationInfo.capture(reactor);
        check(info.interiorVolume()==400 && info.requiredJets()==16 && info.requiredExternalPumps()==2,"height did not increase target");
        check(core.getVoidModel().getRatedCoreFlowKgPerS()==info.requiredFlowKgPerS(),"solver kept old flow reference");
        check(core.getLiquidMassKg()==beforeMass && core.getCoreFlowKgPerS()<=beforeFlow && core.getCoreFlowKgPerS()>beforeFlow*.99,"resize invented water/flow or skipped normal coastdown");
        var saved=reactor.saveWithFullMetadata(l.registryAccess());
        var held=new ReactorControllerBlockEntity(reactor.getBlockPos(),reactor.getBlockState());
        held.loadWithComponents(saved,l.registryAccess());held.setLevel(l);
        var resaved=held.saveWithFullMetadata(l.registryAccess());
        check(resaved.getDouble("RatedCoreFlowKgPerS")==info.requiredFlowKgPerS(),"unformed pending save lost flow reference");
        beforeFlow=core.getCoreFlowKgPerS();
        l.setBlockEntity(held);ReactorControllerBlockEntity.serverTick(l,held.getBlockPos(),held.getBlockState(),held);
        check(held.isFormed() && held.core().getCoreLoading().loadedAssemblyCount()==1,"saved larger vessel failed restore");
        check(held.core().getCoreFlowKgPerS()<=beforeFlow && held.core().getCoreFlowKgPerS()>beforeFlow*.99,"reload multiplied physical flow");
        check(held.core().getVoidModel().getRatedCoreFlowKgPerS()==info.requiredFlowKgPerS(),"reload lost sized rating");
        if(net.neoforged.fml.ModList.get().isLoaded("computercraft")) {
            var readout=new dev.bwr.mod.peripheral.ReactorPeripheral(held).getRecirculationSizing();
            check(readout.get("requiredJetAssemblies").equals(16) && readout.get("interiorVolumeBlocks").equals(400L)
                    && readout.get("jetAssembliesPerPump").equals(10),"computer sizing disagrees with GUI");
        }
        // Legacy saves used the fixed reference; migration must preserve kg/s, not the old percentage.
        saved.remove("RatedCoreFlowKgPerS");saved.getCompound("Core").putDouble("flow",.4);
        var legacy=new ReactorControllerBlockEntity(reactor.getBlockPos(),reactor.getBlockState());
        legacy.loadWithComponents(saved,l.registryAccess());legacy.setLevel(l);l.setBlockEntity(legacy);
        ReactorControllerBlockEntity.serverTick(l,legacy.getBlockPos(),legacy.getBlockState(),legacy);
        double oldFlow=.4*dev.bwr.core.thermal.VoidModel.RATED_CORE_FLOW_KG_PER_S;
        check(legacy.core().getCoreFlowKgPerS()<=oldFlow && legacy.core().getCoreFlowKgPerS()>oldFlow*.99,"legacy flow migration changed kg/s");
        clear(l);var largest=vessel(l,21,8,21);
        check(RecirculationNetwork.sizing(largest.structure()).requiredJets()==32
                && largest.core().getVoidModel().getRatedCoreFlowKgPerS()==dev.bwr.core.flow.RecirculationSizing.forDimensions(21,8,21).ratedFlowKgPerS(),"maximum-footprint vessel ignored volume");
    }

    private static void recirculationDriveLimit(ServerLevel l) {
        var reactor=vessel(l,11,27,11);var jet=BwrBlocks.JET_PUMP.get();
        check(RecirculationNetwork.sizing(reactor.structure()).requiredJets()==32,"tall test vessel needs 32 jets");
        var face=RpvWaterInjectionPortBlock.FACING;
        l.setBlock(new BlockPos(170,213,130),BwrBlocks.RECIRCULATION_OUTLET.get().defaultBlockState().setValue(face,Direction.EAST),3);
        l.setBlock(new BlockPos(170,195,130),BwrBlocks.RECIRCULATION_INLET.get().defaultBlockState().setValue(face,Direction.EAST),3);
        reactor.markStructureDirty();ReactorControllerBlockEntity.serverTick(l,reactor.getBlockPos(),reactor.getBlockState(),reactor);
        List<BlockPos> roots=new ArrayList<>();List<RecirculationPumpBlockEntity> motors=new ArrayList<>();
        pipe(l,new BlockPos(171,213,130),new BlockPos(172,213,130));
        pipe(l,new BlockPos(172,213,130),new BlockPos(172,213,136));
        pipe(l,new BlockPos(172,213,136),new BlockPos(186,213,136));
        pipe(l,new BlockPos(171,195,130),new BlockPos(186,195,130));
        for(int x:new int[]{174,178,182,186}) {
            var root=new BlockPos(x,195,133);roots.add(root);
            l.setBlock(root,BwrBlocks.RECIRCULATION_PUMP.get().defaultBlockState(),3);
            pipe(l,root.south(),new BlockPos(x,195,136));pipe(l,new BlockPos(x,195,136),new BlockPos(x,213,136));
            pipe(l,root.north(),new BlockPos(x,195,130));
            check(RecirculationCircuit.controller(l,root)==reactor,"parallel drive did not complete vessel loop");
            var motor=(RecirculationPumpBlockEntity)l.getBlockEntity(root);motors.add(motor);motor.setTargetSpeedFraction(1);
            for(int i=0;i<1500;i++){motor.energy().receiveEnergy(Integer.MAX_VALUE,false);motor.tickPump(.05);}
        }
        List<BlockPos[]> pairs=new ArrayList<>();
        for(int z=128;z<=138;z++)pairs.add(new BlockPos[]{new BlockPos(159,195,z),new BlockPos(169,195,z)});
        for(int x=160;x<=168;x++)pairs.add(new BlockPos[]{new BlockPos(x,195,128),new BlockPos(x,195,138)});
        for(int i=0;i<10;i++){var p=pairs.get(i);place(l,jet,p[0],Direction.EAST);place(l,jet,p[1],Direction.WEST);}
        ((net.minecraft.world.level.storage.ServerLevelData)l.getLevelData()).setGameTime(l.getGameTime()+21);
        var flow=RecirculationNetwork.measure(l,reactor,roots.subList(0,2));
        check(flow.pairedJets()==20 && Math.abs(flow.fraction()-.625)<1e-6,"two drives did not support twenty assemblies");
        for(int i=10;i<16;i++){var p=pairs.get(i);place(l,jet,p[0],i<11?Direction.EAST:Direction.SOUTH);place(l,jet,p[1],i<11?Direction.WEST:Direction.NORTH);}
        ((net.minecraft.world.level.storage.ServerLevelData)l.getLevelData()).setGameTime(l.getGameTime()+21);
        flow=RecirculationNetwork.measure(l,reactor,roots.subList(0,2));
        check(flow.pairedJets()==32 && Math.abs(flow.fraction()-.625)<1e-6 && flow.maximum()==.625,"extra jets bypassed two-drive ceiling: "+flow);
        double reported=motors.get(0).getCoreFlowContributionKgPerS()+motors.get(1).getCoreFlowContributionKgPerS();
        check(Math.abs(reported-flow.fraction()*reactor.core().getVoidModel().getRatedCoreFlowKgPerS())<1e-6,"pump kg/s readouts used the old fixed reference");
        check(Math.abs(RecirculationNetwork.measure(l,reactor,roots.subList(0,3)).fraction()-.9375)<1e-6,"third drive gave wrong flow");
        check(Math.abs(RecirculationNetwork.measure(l,reactor,roots).fraction()-1)<1e-6,"four drives did not reach target");
        for(int i=16;i<pairs.size();i++){var p=pairs.get(i);place(l,jet,p[0],Direction.SOUTH);place(l,jet,p[1],Direction.NORTH);}
        ((net.minecraft.world.level.storage.ServerLevelData)l.getLevelData()).setGameTime(l.getGameTime()+21);
        flow=RecirculationNetwork.measure(l,reactor,roots.subList(0,2));
        check(flow.pairedJets()==40 && Math.abs(flow.fraction()-.625)<1e-6,"forty jets increased flow beyond two-drive rating");
        check(Math.abs(RecirculationNetwork.measure(l,reactor,List.of(roots.get(0),roots.get(0))).fraction()-.3125)<1e-6,"duplicate registration created a drive");
        motors.get(3).setTargetSpeedFraction(0);
        for(int i=0;i<5000;i++)motors.get(3).tickPump(.05);
        flow=RecirculationNetwork.measure(l,reactor,roots);
        check(Math.abs(flow.fraction()-.9375)<1e-5 && flow.maximum()==1,"stopped fourth pump failed to limit actual flow");
    }
    private static void jetRows(ServerLevel l) {
        var reactor=vessel(l);var jet=BwrBlocks.JET_PUMP.get();
        check(jet.cellCount()==6,"new jet still reserves two columns");
        BlockPos west=new BlockPos(159,195,130),east=new BlockPos(169,195,130);
        place(l,jet,west,Direction.EAST);place(l,jet,east,Direction.WEST);
        var flow=freshJets(l,reactor);
        check(flow.pairedJets()==2 && flow.unmatchedJets()==0,"directly opposite off-centre X row not paired: "+flow);
        BlockPos north=new BlockPos(161,195,128),south=new BlockPos(161,195,138);
        place(l,jet,north,Direction.SOUTH);place(l,jet,south,Direction.NORTH);
        check(freshJets(l,reactor).pairedJets()==4,"opposite Z row not paired independently");
        l.removeBlock(east,false);
        var sameWall=new BlockPos(159,195,136);place(l,jet,sameWall,Direction.WEST);
        check(freshJets(l,reactor).pairedJets()==2,"mirror along the same wall incorrectly paired");
        l.removeBlock(sameWall,false);
        place(l,jet,east,Direction.WEST);
        l.removeBlock(east,false);place(l,jet,east,Direction.EAST);
        check(freshJets(l,reactor).pairedJets()==2,"same-facing X jets incorrectly paired");
        l.removeBlock(east,false);place(l,jet,east.above(),Direction.WEST);
        check(freshJets(l,reactor).pairedJets()==2,"different elevations incorrectly paired");
        l.removeBlock(east.above(),false);place(l,jet,east.south(),Direction.WEST);
        check(freshJets(l,reactor).pairedJets()==2,"offset rows incorrectly paired");
        l.removeBlock(east.south(),false);place(l,jet,east,Direction.WEST);
        check(freshJets(l,reactor).pairedJets()==4,"matching did not recover after correction");
        // Adjacent columns remain free: a one-column footprint is real placement,
        // not merely a narrower visual inside the old two-column reservation.
        var neighbor=west.south();l.setBlock(neighbor,Blocks.STONE.defaultBlockState(),3);
        check(jet.complete(l,west,l.getBlockState(west)),"new jet owns an extra column");
        l.removeBlock(west.above(3),false);check(l.getBlockState(neighbor).is(Blocks.STONE),"jet cleanup erased neighboring column");
        clear(l);reactor=vessel(l);
        BlockPos a=new BlockPos(160,195,128),b=new BlockPos(168,195,138);
        for(var root:List.of(a,b)) {
            var state=jet.defaultBlockState().setValue(PumpAssemblyBlock.ASSEMBLED,true).setValue(PumpAssemblyBlock.FACING,root.equals(a)?Direction.NORTH:Direction.SOUTH);
            var tag=NbtUtils.writeBlockState(state);tag.getCompound("Properties").remove("narrow");
            state=NbtUtils.readBlockState(l.holderLookup(net.minecraft.core.registries.Registries.BLOCK),tag);
            check(jet.cellCount(state)==12,"old jet save changed footprint");
            l.setBlock(root,state,3);jet.setPlacedBy(l,root,state,null,new ItemStack(jet));
            for(int i=0;i<12;i++)check(jet.origin(pos(jet,root,state,i),l.getBlockState(pos(jet,root,state,i))).equals(root),"legacy jet owner shifted");
        }
        check(freshJets(l,reactor).pairedJets()==2,"old diagonal jet arrangement stopped working");
    }
    private static void rodTravelNbt() {
        var core=new dev.bwr.core.ReactorCore(new dev.bwr.core.CoreConfig());core.setRodNotchLabelDemand(0,28);core.step(.4);
        var saved=core.toState();var tag=ReactorStateNbt.write(saved);var decoded=ReactorStateNbt.read(tag);
        var restored=new dev.bwr.core.ReactorCore(new dev.bwr.core.CoreConfig());restored.fromState(decoded);
        check(restored.getRodPositionNotches(0)==core.getRodPositionNotches(0) && restored.getRodPositionNotches(0)>0,"NBT lost fractional rod travel");
        check(restored.toState().reactivityTotal()==saved.reactivityTotal(),"NBT changed in-flight rod worth");
        tag.remove("rodPositionsNotches");restored.fromState(ReactorStateNbt.read(tag));
        check(restored.getRodPositionNotches(0)==saved.rodNotches()[0],"old NBT did not fall back to its notch");
    }
    private static void waterBoundary(ServerLevel l) {
        var reactor=vessel(l);
        var b=BwrBlocks.MOTOR_FEED_PUMP.get(); var s=place(l,b,ROOT,Direction.NORTH);
        waterDischarge(l,b,s);
        BlockPos suction=b.portPosition(ROOT,s,AssemblyPort.WATER_SUCTION).above(), entry=suction.above(2);
        pipe(l,suction,entry);
        var inlet=l.getCapability(Capabilities.FluidHandler.BLOCK,entry,Direction.UP);
        check(inlet!=null,"water pipe exposes no NeoForge inlet for turbine condensate");
        var pump=(FeedwaterPumpBlockEntity)l.getBlockEntity(ROOT);
        var water=new FluidStack(Fluids.WATER,1500);
        check(inlet.fill(water,IFluidHandler.FluidAction.SIMULATE)==1500 && pump.suction().isEmpty(),"simulated fill changed inventory");
        check(inlet.fill(new FluidStack(Fluids.LAVA,1000),IFluidHandler.FluidAction.EXECUTE)==0,"water pipe accepted lava");
        check(inlet.fill(water,IFluidHandler.FluidAction.EXECUTE)==1500 && pump.suction().getFluidAmount()==1500,"condensate did not reach the modeled suction port");
        check(inlet.fill(water,IFluidHandler.FluidAction.SIMULATE)==500 && pump.suction().getFluidAmount()==1500,"fill ignored destination capacity");
        check(inlet.drain(1000,IFluidHandler.FluidAction.EXECUTE).isEmpty(),"water inlet fabricated extractable contents");
        // Adjoining steam must neither sprout a connecting arm nor join the water traversal.
        BlockPos steam=entry.east();
        l.setBlock(steam,BwrBlocks.PRESSURISED_TUBE.get().stateWithConnections(l,steam),3);
        check(!l.getBlockState(steam).getValue(PipeBlock.WEST),"steam pipe connected to water pipe");
        check(!BwrBlocks.HIGH_PRESSURE_WATER_PIPE.get().stateWithConnections(l,entry).getValue(PipeBlock.EAST),"water pipe connected to steam pipe");
        check(l.getCapability(Capabilities.FluidHandler.BLOCK,steam,Direction.WEST)==null,"steam pipe exposes water capability");
        var line=AssemblyPlumbing.trace(l,ROOT,s,AssemblyPort.WATER_DISCHARGE);
        check(AssemblyPlumbing.waterReceiver(l,line)==reactor,"injection port bound to wrong reactor");
        BlockPos portPos=new BlockPos(170,199,133);
        var portState=l.getBlockState(portPos);
        l.setBlock(portPos,portState.setValue(RpvWaterInjectionPortBlock.FACING,Direction.WEST),3);
        check(AssemblyPlumbing.waterReceiver(l,AssemblyPlumbing.trace(l,ROOT,s,AssemblyPort.WATER_DISCHARGE))==null,"back-facing injection flange accepted water");
        l.setBlock(portPos,portState,3);
        // Filled through the same public boundary used by Mekanism; all water must then be paid from that buffer.
        pump.setComputerControlled(true);pump.setRunning(true);
        double mass=0;
        for(int i=0;i<800;i++) {
            pump.energy().receiveEnergy(Integer.MAX_VALUE,false);
            FeedwaterPumpBlockEntity.serverTick(l,ROOT,s,pump);
            mass+=pump.getDeliveredFlowKgPerS()*.05;
        }
        check(mass>100 && mass<=1500+1e-7 && Math.abs(1500-pump.suction().getFluidAmount()-mass)<1.01,"condensate-to-vessel mass balance failed: "+mass);
        var bus=EccsNetwork.existingBusFor(l,reactor.getBlockPos());
        check(bus!=null,"pumped condensate was not reported to vessel");
        // Replacing one discharge segment with steam interrupts water immediately.
        inlet.fill(water,IFluidHandler.FluidAction.EXECUTE);
        BlockPos wrong=b.portPosition(ROOT,s,AssemblyPort.WATER_DISCHARGE).above(2);
        l.setBlock(wrong,BwrBlocks.PRESSURISED_TUBE.get().defaultBlockState(),3);
        FeedwaterPumpBlockEntity.serverTick(l,ROOT,s,pump);
        check(pump.getDeliveredFlowKgPerS()==0 && pump.isRunning(),"steam segment carried pumped water or changed controls");
        // A cached capability re-resolves its path after a break; it must not fill a disconnected pump.
        l.removeBlock(suction,false);
        int before=pump.suction().getFluidAmount();
        check(inlet.fill(water,IFluidHandler.FluidAction.EXECUTE)==0 && before==pump.suction().getFluidAmount(),"cached water route survived a broken pipe");
        l.removeBlock(entry,false);
        check(inlet.fill(water,IFluidHandler.FluidAction.EXECUTE)==0,"removed pipe retained its capability");
        // A port set down next to the vessel, or an unformed vessel, is not a reactor inlet.
        BlockPos loose=new BlockPos(173,199,133);
        l.setBlock(loose,portState,3);
        var loosePort=(RpvWaterInjectionPortBlockEntity)l.getBlockEntity(loose);
        loosePort.noteController(reactor.getBlockPos());
        check(loosePort.controller()==null,"free-standing injection port claimed a nearby vessel");
        l.removeBlock(new BlockPos(158,200,132),false);
        reactor.markStructureDirty();ReactorControllerBlockEntity.serverTick(l,reactor.getBlockPos(),reactor.getBlockState(),reactor);
        check(!reactor.isFormed() && ((RpvWaterInjectionPortBlockEntity)l.getBlockEntity(portPos)).controller()==null,"unformed vessel accepted injection");
        // Fractional water usage remains finite across save/reload and external draining.
        var buffer=new dev.bwr.mod.water.WaterSuctionBuffer(() -> {});
        buffer.tank().fill(new FluidStack(Fluids.WATER,1),IFluidHandler.FluidAction.EXECUTE);
        check(Math.abs(buffer.drawKg(.4)-.4)<1e-9,"fractional buffer draw failed");
        var restored=new dev.bwr.mod.water.WaterSuctionBuffer(() -> {});
        restored.load(l.registryAccess(),buffer.save(l.registryAccess()));
        check(restored.tank().drain(1,IFluidHandler.FluidAction.EXECUTE).isEmpty(),"external drain removed owed water");
        check(Math.abs(restored.drawKg(10)-.6)<1e-9 && restored.drawKg(10)==0,"reloaded buffer created fractional water");
        BlockPos tankPos=ROOT.above(4);l.setBlock(tankPos,BwrBlocks.CONDENSATE_STORAGE_TANK.get().defaultBlockState(),3);
        var tank=(CondensateStorageTankBlockEntity)l.getBlockEntity(tankPos);tank.fillKg(1);
        check(tank.drawKg(.4)==.4,"CST fractional draw failed");
        var saved=tank.saveWithFullMetadata(l.registryAccess());
        var reloaded=new CondensateStorageTankBlockEntity(tankPos,tank.getBlockState());reloaded.loadWithComponents(saved,l.registryAccess());
        check(reloaded.fluidHandler().drain(1,IFluidHandler.FluidAction.EXECUTE).isEmpty(),"CST pipe extracted already-delivered water");
        check(Math.abs(reloaded.drawKg(10)-.6)<1e-9 && reloaded.drawKg(1)==0,"CST reload created fractional water");
    }
    /** Use the installed Mekanism pipe and its actual acceptor/network code; keep it a soft dependency. */
    private static void mechanicalPipe(ServerLevel l,PumpAssemblyBlock b) throws Exception {
        var state=b.placementState();
        BlockPos inlet=b.portPosition(ROOT,state,AssemblyPort.WATER_SUCTION);
        Direction face=b.portFace(state,AssemblyPort.WATER_SUCTION);
        BlockPos pipePos=inlet.relative(face);
        var mechanical=net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("mekanism","basic_mechanical_pipe"));
        check(mechanical!=Blocks.AIR,"Mekanism mechanical pipe missing");
        l.setBlock(pipePos,mechanical.defaultBlockState(),3);
        var tile=l.getBlockEntity(pipePos);
        Class.forName("mekanism.common.tile.transmitter.TileEntityTransmitter")
                .getMethod("tickServer",net.minecraft.world.level.Level.class,BlockPos.class,BlockState.class,Class.forName("mekanism.common.tile.transmitter.TileEntityTransmitter"))
                .invoke(null,l,pipePos,l.getBlockState(pipePos),tile);
        Object transmitter=tile.getClass().getMethod("getTransmitter").invoke(tile);
        var type=transmitter.getClass();
        type.getMethod("refreshConnections").invoke(transmitter);
        check(type.getMethod("getAcceptor",Direction.class).invoke(transmitter,face.getOpposite())==null,"empty space was an acceptor");
        place(l,b,ROOT,Direction.NORTH);
        // The pipe exists first, so this also exercises assembly-completion capability invalidation.
        type.getMethod("refreshConnections").invoke(transmitter);
        check(type.getMethod("getAcceptor",Direction.class).invoke(transmitter,face.getOpposite()) instanceof IFluidHandler,"Mekanism did not recognize the suction flange");
        Object network=type.getMethod("createEmptyNetworkWithID",java.util.UUID.class).invoke(transmitter,java.util.UUID.randomUUID());
        var validator=Class.forName("mekanism.common.lib.transmitter.CompatibleTransmitterValidator");
        network.getClass().getMethod("addNewTransmitters",Collection.class,validator).invoke(network,List.of(transmitter),type.getMethod("getNewOrphanValidator").invoke(transmitter));
        network.getClass().getMethod("commit").invoke(network);
        try {
            var cap=(IFluidHandler)network;
            check(cap!=null && cap.fill(new FluidStack(Fluids.WATER,1000),IFluidHandler.FluidAction.EXECUTE)==1000,"Mekanism pipe network refused water; capacity="+cap.getTankCapacity(0));
            network.getClass().getMethod("onUpdate").invoke(network);
            var pumpCap=l.getCapability(Capabilities.FluidHandler.BLOCK,inlet,face);
            check(pumpCap!=null && pumpCap.getFluidInTank(0).getAmount()==1000,"Mekanism network did not deliver water to the pump");
            check(l.getCapability(Capabilities.FluidHandler.BLOCK,b.portPosition(ROOT,state,AssemblyPort.WATER_DISCHARGE),b.portFace(state,AssemblyPort.WATER_DISCHARGE))==null,"ordinary pipe bypassed pressure on discharge");
            l.removeBlock(ROOT,false);
            type.getMethod("refreshConnections").invoke(transmitter);
            check(type.getMethod("getAcceptor",Direction.class).invoke(transmitter,face.getOpposite())==null,"Mekanism cached a destroyed pump");
        } finally {network.getClass().getMethod("deregister").invoke(network);}
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
        BlockPos admission=b.portPosition(ROOT,s,AssemblyPort.STEAM_INLET).relative(b.portFace(s,AssemblyPort.STEAM_INLET));
        steamPipe(l,new BlockPos(164,201,126),new BlockPos(164,216,126));
        steamPipe(l,new BlockPos(164,216,126),new BlockPos(admission.getX(),216,126));
        steamPipe(l,new BlockPos(admission.getX(),216,126),new BlockPos(admission.getX(),216,admission.getZ()));
        steamPipe(l,new BlockPos(admission.getX(),216,admission.getZ()),admission);
        BlockPos exhaust=b.portPosition(ROOT,s,AssemblyPort.STEAM_EXHAUST).relative(b.portFace(s,AssemblyPort.STEAM_EXHAUST));
        BlockPos export=exhaust.south(3);
        l.setBlock(export,BwrBlocks.TURBINE_STEAM_OUTLET.get().defaultBlockState(),3);
        steamPipe(l,exhaust,exhaust.south(2));
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
        l.removeBlock(exhaust.south(),false);
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
