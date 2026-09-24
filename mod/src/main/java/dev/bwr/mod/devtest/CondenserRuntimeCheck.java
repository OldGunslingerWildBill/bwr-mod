package dev.bwr.mod.devtest;

import com.mojang.logging.LogUtils;
import dev.bwr.core.turbine.*;
import dev.bwr.mod.condenser.*;
import dev.bwr.mod.gui.*;
import dev.bwr.mod.reactor.RpvSteamOutletBlockEntity;
import dev.bwr.mod.registry.*;
import dev.bwr.mod.steam.*;
import dev.bwr.mod.water.WaterLineNetwork;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.DirectionalPlaceContext;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.capabilities.*;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import java.util.*;
import static dev.bwr.mod.condenser.CondenserBlock.*;
import static net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.*;

/** Real-server placement, independent circuits, valve-controlled bypass and mod capability checks. */
public final class CondenserRuntimeCheck {
    private static final BlockPos ROOT=new BlockPos(150,260,135);
    private static final CondenserLayout LAYOUT=CondenserLayout.INSTANCE;
    private static final Set<BlockPos> WRITTEN=new HashSet<>();
    private static void check(boolean b,String message){if(!b)throw new AssertionError(message);}
    private static void near(double a,double b,String message){check(Math.abs(a-b)<1e-7*Math.max(1,Math.abs(b)),message+": "+a+" != "+b);}
    private static void put(ServerLevel l,BlockPos p,BlockState state){WRITTEN.add(p.immutable());l.setBlock(p,state,3);}
    private static void tick(ServerLevel l){((net.minecraft.world.level.storage.ServerLevelData)l.getLevelData()).setGameTime(l.getGameTime()+1);}
    private static void clear(ServerLevel l){
        for(var p:WRITTEN)if(l.isLoaded(p)&&!l.getBlockState(p).isAir())l.removeBlock(p,false);
        WRITTEN.clear();l.getEntitiesOfClass(ItemEntity.class,new AABB(ROOT).inflate(28)).forEach(ItemEntity::discard);
    }
    private static CondenserBlockEntity place(ServerLevel l,Direction d){
        var block=BwrBlocks.ARABELLE_CONDENSER.get();var state=block.defaultBlockState().setValue(FACING,d);
        for(var c:LAYOUT.cells)WRITTEN.add(LAYOUT.world(ROOT,d,c));
        put(l,ROOT,state);block.setPlacedBy(l,ROOT,state,null,new ItemStack(block));
        var owner=(CondenserBlockEntity)l.getBlockEntity(ROOT);check(owner!=null&&owner.ready(),"condenser not complete");return owner;
    }
    private static BlockPos port(Port role){return LAYOUT.world(ROOT,Direction.NORTH,LAYOUT.ports.stream().filter(c->c.role()==role).findFirst().orElseThrow());}
    private static IFluidHandler cap(ServerLevel l,BlockPos p){return l.getCapability(Capabilities.FluidHandler.BLOCK,p,CondenserBlock.portFace(l.getBlockState(p)));}
    public static int run(ServerLevel l){
        int passed=0;long original=l.getGameTime();
        try{
            check(LAYOUT.size.equals(new BlockPos(7,6,9))&&LAYOUT.ports.size()==8,"transverse footprint/ports incorrect");
            for(Direction d:Direction.Plane.HORIZONTAL){
                clear(l);var b=BwrBlocks.ARABELLE_CONDENSER.get();
                var ctx=new DirectionalPlaceContext(l,ROOT,d.getOpposite(),new ItemStack(b),Direction.UP);
                check(b.getStateForPlacement(ctx)!=null,"empty footprint refused");
                var obstruction=LAYOUT.world(ROOT,d,LAYOUT.ports.getFirst());put(l,obstruction,Blocks.STONE.defaultBlockState());
                check(b.getStateForPlacement(ctx)==null,"occupied footprint accepted");l.removeBlock(obstruction,false);
                var owner=place(l,d);int controllers=0;
                for(var cell:LAYOUT.cells){var at=LAYOUT.world(ROOT,d,cell);var state=l.getBlockState(at);
                    check(l.getBlockEntity(at) instanceof CondenserBlockEntity part&&part.owner()==owner,"wrong part ownership");
                    if(state.getValue(CONTROLLER))controllers++;
                    var bounds=state.getShape(l,at).bounds();check(bounds.minX>=0&&bounds.minY>=0&&bounds.minZ>=0&&bounds.maxX<=1&&bounds.maxY<=1&&bounds.maxZ<=1,"collision exceeds cell");
                }check(controllers==1,"duplicate simulation/render controllers");
                for(var cell:LAYOUT.ports){var at=LAYOUT.world(ROOT,d,cell);var s=l.getBlockState(at);var face=portFace(s);
                    check(l.getBlockState(at.relative(face)).isAir(),"flange blocked by own model");
                    for(Direction side:Direction.values()){
                        check(SteamLineNetwork.acceptsLineOn(s,side)==(side==face&&cell.role()==Port.BYPASS),"wrong steam face");
                        check(WaterLineNetwork.acceptsLineOn(s,side)==(side==face&&cell.role().water()),"wrong water face");
                        check((l.getCapability(Capabilities.FluidHandler.BLOCK,at,side)!=null)==(side==face&&cell.role().water()),"wrong fluid capability face");
                    }
                    var adjacent=at.relative(face);
                    var steam=BwrBlocks.PRESSURISED_TUBE.get().stateWithConnections(l,adjacent);
                    var water=BwrBlocks.HIGH_PRESSURE_WATER_PIPE.get().stateWithConnections(l,adjacent);
                    var property=PipeBlock.PROPERTY_BY_DIRECTION.get(face.getOpposite());
                    check(steam.getValue(property)==(cell.role()==Port.BYPASS),"steam model arm mismatch");
                    check(water.getValue(property)==cell.role().water(),"water model arm mismatch");
                    var originalPart=(CondenserBlockEntity)l.getBlockEntity(at);var saved=originalPart.saveWithoutMetadata(l.registryAccess());
                    originalPart.loadWithComponents(saved,l.registryAccess());check(originalPart.owner()==owner,"saved port lost owner");
                }
                // The real LP assembly sits directly above the condenser in every facing.
                var lp=BwrBlocks.LP_TURBINE.get();var lpRoot=ROOT.above(6);var lpFacing=d.getCounterClockWise();
                var lpContext=new DirectionalPlaceContext(l,lpRoot,lpFacing.getOpposite(),new ItemStack(lp),Direction.UP);
                check(lp.getStateForPlacement(lpContext)!=null,"LP cannot be placed on condenser");
                var lpState=lp.placementState().setValue(dev.bwr.mod.eccs.PumpAssemblyBlock.FACING,lpFacing);
                for(int i=0;i<lp.cellCount(lpState);i++)WRITTEN.add(lpRoot.offset(dev.bwr.mod.eccs.TurbineAssemblyBlock.turn(lp.cellOffset(lpState,i),lpFacing)));
                put(l,lpRoot,lpState);lp.setPlacedBy(l,lpRoot,lpState,null,new ItemStack(lp));
                check(lp.complete(l,lpRoot,lpState)&&owner.ready(),"LP/condenser intersect or invalidate each other");
                l.removeBlock(ROOT,false);check(lp.complete(l,lpRoot,lpState),"condenser teardown removed LP turbine");
                passed++;
            }
            clear(l);var owner=place(l,Direction.NORTH);var cold=cap(l,port(Port.COLD));var hot=cap(l,port(Port.HOT));var condensate=cap(l,port(Port.CONDENSATE));
            check(cold.fill(new FluidStack(Fluids.WATER,200000),SIMULATE)==200000,"cold simulate rejected");near(owner.plant().cold(),0,"simulate mutated tank");
            check(cold.fill(new FluidStack(Fluids.LAVA,1000),EXECUTE)==0,"non-water accepted");
            check(hot.fill(new FluidStack(Fluids.WATER,1000),EXECUTE)==0&&condensate.fill(new FluidStack(Fluids.WATER,1000),EXECUTE)==0,"output accepted inlet water");
            var wp=port(Port.COLD).below();put(l,wp,BwrBlocks.HIGH_PRESSURE_WATER_PIPE.get().stateWithConnections(l,wp));
            check(l.getCapability(Capabilities.FluidHandler.BLOCK,wp,Direction.NORTH).fill(new FluidStack(Fluids.WATER,200000),EXECUTE)==200000,"BWR water pipe could not supply cold port");
            check(cold.drain(100,EXECUTE).isEmpty(),"cold port became output");
            owner.plant().steam.offer(new SteamInventory.Packet(50.75,2770,1015));owner.plant().tick(.05);
            check(hot.drain(1000000,SIMULATE).getAmount()>0&&condensate.drain(1000,SIMULATE).getAmount()>0,"separate outputs missing");
            double coldStored=owner.plant().cold(),condStored=owner.plant().condensate();hot.drain(123,EXECUTE);
            near(owner.plant().cold(),coldStored,"hot drain drew cold tank");near(owner.plant().condensate(),condStored,"hot drain drew condensate");
            var saved=owner.saveWithoutMetadata(l.registryAccess());owner.loadWithComponents(saved,l.registryAccess());check(owner.ready(),"reloaded controller incomplete");near(owner.plant().condensate(),condStored,"fractional condensate lost on reload");
            // Both products can push into ordinary finite NeoForge tanks.
            var hotSink=port(Port.HOT).below();var condSink=port(Port.CONDENSATE).south();
            put(l,hotSink,BwrBlocks.CONDENSATE_STORAGE_TANK.get().defaultBlockState());put(l,condSink,BwrBlocks.CONDENSATE_STORAGE_TANK.get().defaultBlockState());
            double before=owner.plant().hot()+owner.plant().condensate()+owner.plant().steam.mass();CondenserBlockEntity.serverTick(l,ROOT,owner.getBlockState(),owner);
            var hs=l.getCapability(Capabilities.FluidHandler.BLOCK,hotSink,Direction.UP);var cs=l.getCapability(Capabilities.FluidHandler.BLOCK,condSink,Direction.NORTH);
            check(hs.getFluidInTank(0).getAmount()>0&&cs.getFluidInTank(0).getAmount()>0,"products not pushed into connected tanks");
            near(hs.getFluidInTank(0).getAmount()+cs.getFluidInTank(0).getAmount()+owner.plant().hot()+owner.plant().condensate()+owner.plant().steam.mass(),before,"output transfer duplicated water");passed++;
            if(net.neoforged.fml.ModList.get().isLoaded("mekanism")){l.removeBlock(wp,false);mechanicalPipe(l,owner,Port.COLD);mechanicalPipe(l,owner,Port.MAKEUP);passed++;}
            var player=net.neoforged.neoforge.common.util.FakePlayerFactory.getMinecraft(l);var anchor=port(Port.COLD);
            player.setPos(anchor.getX()+.5,anchor.getY(),anchor.getZ()+.5);var menu=new CondenserMenu(1,player.getInventory(),ROOT,anchor);
            check(menu.stillValid(player),"far port GUI cannot reach owner");player.setPos(0,0,0);check(!menu.stillValid(player),"remote condenser GUI remains valid");passed++;
            clear(l);owner=place(l,Direction.NORTH);bypass(l,owner);passed++;
            var stale=cap(l,port(Port.COLD));var child=port(Port.BYPASS);var neighbor=child.north();put(l,neighbor,Blocks.DIAMOND_BLOCK.defaultBlockState());
            player.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Items.NETHERITE_PICKAXE));
            var block=BwrBlocks.ARABELLE_CONDENSER.get();block.playerWillDestroy(l,child,l.getBlockState(child),player);l.destroyBlock(child,true);
            for(var c:LAYOUT.cells)check(l.getBlockState(LAYOUT.world(ROOT,Direction.NORTH,c)).isAir(),"broken condenser left owned cells");
            check(l.getBlockState(neighbor).is(Blocks.DIAMOND_BLOCK),"teardown removed neighboring block");
            check(stale.fill(new FluidStack(Fluids.WATER,1),EXECUTE)==0,"cached capability still operates destroyed machine");
            int drops=l.getEntitiesOfClass(ItemEntity.class,new AABB(ROOT).inflate(28)).stream().filter(e->e.getItem().is(block.asItem())).mapToInt(e->e.getItem().getCount()).sum();
            check(drops==1,"part break produced "+drops+" condenser items");passed++;
            clear(l);legacy(l);passed++;
            LogUtils.getLogger().info("CONDENSER RUNTIME CHECK PASS: {} scenarios; compact LP fit, eight rotated ports, CC and legacy replacement",passed);return 0;
        }catch(Throwable e){LogUtils.getLogger().error("CONDENSER RUNTIME CHECK FAIL after {} scenarios",passed,e);return 1;}
        finally{clear(l);((net.minecraft.world.level.storage.ServerLevelData)l.getLevelData()).setGameTime(original);}
    }
    private static void line(ServerLevel l,BlockPos a,BlockPos b){
        check(a.getX()==b.getX()&&a.getY()==b.getY()||a.getX()==b.getX()&&a.getZ()==b.getZ()||a.getY()==b.getY()&&a.getZ()==b.getZ(),"nonaxis test pipe");
        for(var p:BlockPos.betweenClosed(a,b))put(l,p,BwrBlocks.PRESSURISED_TUBE.get().stateWithConnections(l,p));
    }
    private static void bypass(ServerLevel l,CondenserBlockEntity owner)throws Exception{
        var reactor=PumpAssemblyRuntimeCheck.vessel(l);reactor.core().initialiseHotShutdown();
        var np=new BlockPos(164,201,127);put(l,np,BwrBlocks.RPV_STEAM_OUTLET.get().defaultBlockState());
        int headerY=port(Port.BYPASS).getY();
        line(l,new BlockPos(164,201,126),new BlockPos(180,201,126));line(l,new BlockPos(180,201,126),new BlockPos(180,headerY,126));
        line(l,new BlockPos(port(Port.BYPASS).getX(),headerY,126),new BlockPos(180,headerY,126));
        for(var c:LAYOUT.ports)if(c.role()==Port.BYPASS){var at=LAYOUT.world(ROOT,Direction.NORTH,c);line(l,new BlockPos(at.getX(),headerY,126),at.north());}
        var vp=new BlockPos(175,headerY,126);put(l,vp,BwrBlocks.BYPASS_STEAM_VALVE.get().defaultBlockState().setValue(TurbineValveBlock.FACING,Direction.EAST));
        var valve=(TurbineValveBlockEntity)l.getBlockEntity(vp);check(valve.bypassValve()&&!valve.stopValve(),"wrong bypass valve type");
        var nozzle=(RpvSteamOutletBlockEntity)l.getBlockEntity(np);nozzle.noteController(reactor.getBlockPos());nozzle.setPosition(1);nozzle.refreshAttachment(l);nozzle.refreshLine(l);
        double full=0;
        for(double fraction:new double[]{1,.25,0,.001}){
            owner.plant().restore(200000,0,0);owner.plant().steam.restore(0,0,0);valve.setTarget(fraction);valve.stroke(3);tick(l);
            double supply=nozzle.flowKgPerS(reactor.core().getPressurePsig());if(fraction==1)full=supply;
            check(full>0,"no reactor bypass supply");near(supply,full*fraction,"valve did not govern bypass source");
            CondenserBlockEntity.serverTick(l,ROOT,owner.getBlockState(),owner);
            double received=owner.plant().steam.mass()+owner.plant().condensate();near(received,supply/20,"bypass nozzle duplicated/lost steam");
            if(fraction==0)tick(l);near(valve.flowKgPerS(),supply,"bypass meter differs from transfer");
        }
        owner.plant().steam.restore(10000,2770,1015);tick(l);valve.setTarget(1);valve.stroke(3);
        near(nozzle.flowKgPerS(reactor.core().getPressurePsig()),0,"full steam inventory failed to choke boundary");
        if(net.neoforged.fml.ModList.get().isLoaded("computercraft")){
            var capability=(BlockCapability<?,?>)Class.forName("dan200.computercraft.api.peripheral.PeripheralCapability").getMethod("get").invoke(null);
            @SuppressWarnings("unchecked") var typed=(BlockCapability<Object,Direction>)capability;
            Object cc=l.getCapability(typed,vp,Direction.UP);check(cc!=null,"no CC peripheral capability on bypass valve");
            check(cc.getClass().getMethod("getType").invoke(cc).equals("bwr_bypass_steam_valve"),"wrong CC type");
            PeripheralTestCalls.call(cc,"setPosition",.251);near(valve.target(),.251,"CC fine position");
            try{PeripheralTestCalls.call(cc,"setPosition",Double.NaN);throw new AssertionError("NaN CC command accepted");}
            catch(dan200.computercraft.api.lua.LuaException expected){check(expected.getMessage()!=null,"missing invalid input reason");}
            PeripheralTestCalls.call(cc,"close");near(valve.target(),0,"CC close");
        }
        var player=net.neoforged.neoforge.common.util.FakePlayerFactory.getMinecraft(l);player.setPos(vp.getX()+.5,vp.getY(),vp.getZ()+.5);
        var menu=new TurbineValveMenu(3,player.getInventory(),vp);menu.handleCommand(player,0,357,0);near(valve.target(),.357,"bypass local GUI control");
        valve.stroke(.2);var tag=valve.saveWithoutMetadata(l.registryAccess());var copy=new TurbineValveBlockEntity(vp,valve.getBlockState());copy.loadWithComponents(tag,l.registryAccess());near(copy.target(),.357,"bypass target persistence");near(copy.position(),valve.position(),"bypass stroke persistence");
    }
    private static void mechanicalPipe(ServerLevel l,CondenserBlockEntity owner,Port role)throws Exception{
        var face=portFace(l.getBlockState(port(role)));var pipePos=port(role).relative(face);var block=BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("mekanism","basic_mechanical_pipe"));
        put(l,pipePos,block.defaultBlockState());var tile=l.getBlockEntity(pipePos);
        var base=Class.forName("mekanism.common.tile.transmitter.TileEntityTransmitter");base.getMethod("tickServer",net.minecraft.world.level.Level.class,BlockPos.class,BlockState.class,base).invoke(null,l,pipePos,l.getBlockState(pipePos),tile);
        Object t=tile.getClass().getMethod("getTransmitter").invoke(tile);var type=t.getClass();type.getMethod("refreshConnections").invoke(t);
        check(type.getMethod("getAcceptor",Direction.class).invoke(t,face.getOpposite()) instanceof IFluidHandler,"Mekanism does not recognize "+role+" flange");
        Object network=type.getMethod("createEmptyNetworkWithID",UUID.class).invoke(t,UUID.randomUUID());var validator=Class.forName("mekanism.common.lib.transmitter.CompatibleTransmitterValidator");
        network.getClass().getMethod("addNewTransmitters",Collection.class,validator).invoke(network,List.of(t),type.getMethod("getNewOrphanValidator").invoke(t));network.getClass().getMethod("commit").invoke(network);
        try{owner.plant().restore(0,0,0);var cap=(IFluidHandler)network;check(cap.fill(new FluidStack(Fluids.WATER,1000),EXECUTE)==1000,"Mek network fill failed");network.getClass().getMethod("onUpdate").invoke(network);near(role==Port.MAKEUP?owner.plant().condensate():owner.plant().cold(),1000,"Mek pipe did not supply "+role);}
        finally{network.getClass().getMethod("deregister").invoke(network);l.removeBlock(pipePos,false);}
    }
    private static void legacy(ServerLevel l){
        var layout=CondenserLayout.LEGACY;var block=BwrBlocks.ARABELLE_CONDENSER.get();var state=block.defaultBlockState();put(l,ROOT,state);
        var owner=(CondenserBlockEntity)l.getBlockEntity(ROOT);var tag=owner.saveWithoutMetadata(l.registryAccess());
        tag.remove("LayoutVersion");tag.putInt("Cell",layout.controllerIndex());owner.loadWithComponents(tag,l.registryAccess());
        for(var cell:layout.cells){var p=layout.world(ROOT,Direction.NORTH,cell);WRITTEN.add(p);
            if(p.equals(ROOT))continue;
            put(l,p,state.setValue(CONTROLLER,false).setValue(PORT,cell.role()));var part=(CondenserBlockEntity)l.getBlockEntity(p);part.bind(owner,cell.index());
            var old=part.saveWithoutMetadata(l.registryAccess());old.remove("LayoutVersion");part.loadWithComponents(old,l.registryAccess());
        }
        check(owner.layout()==layout&&owner.ready(),"old NBT lost original footprint");
        var inlet=layout.ports.stream().filter(c->c.role()==Port.COLD).findFirst().orElseThrow();var p=layout.world(ROOT,Direction.NORTH,inlet);
        check(cap(l,p).fill(new FluidStack(Fluids.WATER,1234),EXECUTE)==1234,"legacy plumbing lost its ports");
        var saved=owner.saveWithoutMetadata(l.registryAccess());owner.loadWithComponents(saved,l.registryAccess());check(owner.layout()==layout&&owner.ready(),"legacy resave expanded/reduced incorrectly");near(owner.plant().cold(),1234,"legacy inventory changed");
        l.removeBlock(p,false);for(var c:layout.cells)check(l.getBlockState(layout.world(ROOT,Direction.NORTH,c)).isAir(),"legacy replacement left ghost cells");
        var replacement=place(l,Direction.NORTH);check(replacement.layout()==LAYOUT&&replacement.ready(),"replacement did not use compact layout");
    }
}
