package dev.bwr.mod.devtest;

import com.mojang.logging.LogUtils;
import dev.bwr.core.turbine.CoolingWaterUnit.Design;
import dev.bwr.mod.cooling.*;
import dev.bwr.mod.condenser.*;
import dev.bwr.mod.registry.*;
import dev.bwr.mod.steam.SteamLineNetwork;
import dev.bwr.mod.water.WaterLineNetwork;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.DirectionalPlaceContext;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import java.util.*;
import static dev.bwr.mod.cooling.CoolingBlock.*;
import static net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.*;

/** Runs in a disposable real server world, exercising production ports and pipe networks. */
public final class CoolingRuntimeCheck {
    private static final BlockPos ROOT=new BlockPos(145,260,138);
    private static final Set<BlockPos> WRITTEN=new HashSet<>();
    public static CoolingBlock block(Design d){return switch(d){case NATURAL->BwrBlocks.NATURAL_DRAFT_TOWER.get();case MECHANICAL->BwrBlocks.MECHANICAL_DRAFT_TOWER.get();case CIRCULATING->BwrBlocks.CIRCULATING_WATER_PUMP.get();case MAKEUP->BwrBlocks.MAKEUP_WATER_PUMP.get();case INTAKE->BwrBlocks.SCREENED_WATER_INTAKE.get();};}
    private static void check(boolean b,String message){if(!b)throw new AssertionError(message);}
    private static void near(double a,double b,String m){check(Math.abs(a-b)<1e-7*Math.max(1,Math.abs(b)),m+": "+a+" != "+b);}
    private static void put(ServerLevel l,BlockPos p,BlockState s){WRITTEN.add(p.immutable());l.setBlock(p,s,3);}
    private static void clear(ServerLevel l){for(var p:WRITTEN)if(l.isLoaded(p)&&!l.getBlockState(p).isAir())l.removeBlock(p,false);WRITTEN.clear();l.getEntitiesOfClass(ItemEntity.class,new AABB(ROOT).inflate(60)).forEach(ItemEntity::discard);}
    public static CoolingBlockEntity place(ServerLevel l,Design d,BlockPos root,Direction facing){
        var b=block(d);var s=b.defaultBlockState().setValue(FACING,facing);
        for(var c:b.layout().cells)WRITTEN.add(b.layout().world(root,facing,c));put(l,root,s);b.setPlacedBy(l,root,s,null,new ItemStack(b));
        var o=(CoolingBlockEntity)l.getBlockEntity(root);check(o!=null&&o.ready(),"incomplete "+d);return o;
    }
    public static BlockPos port(CoolingBlockEntity be,Port role){return be.layout().world(be.root(),be.getBlockState().getValue(FACING),be.layout().ports.stream().filter(c->c.role()==role).findFirst().orElseThrow());}
    private static IFluidHandler cap(ServerLevel l,BlockPos p){return l.getCapability(Capabilities.FluidHandler.BLOCK,p,CoolingBlock.portFace(l.getBlockState(p)));}
    private static void step(ServerLevel l,CoolingBlockEntity... machines){((net.minecraft.world.level.storage.ServerLevelData)l.getLevelData()).setGameTime(l.getGameTime()+1);for(var be:machines)CoolingBlockEntity.serverTick(l,be.root(),be.getBlockState(),be);}
    private static void power(CoolingBlockEntity be){be.power().receiveEnergy(Integer.MAX_VALUE,false);be.setTarget(1);}
    private static void pipe(ServerLevel l,BlockPos a,BlockPos b){check(a.getX()==b.getX()&&a.getY()==b.getY()||a.getX()==b.getX()&&a.getZ()==b.getZ()||a.getY()==b.getY()&&a.getZ()==b.getZ(),"nonaxis pipe");for(var p:BlockPos.betweenClosed(a,b))put(l,p,BwrBlocks.HIGH_PRESSURE_WATER_PIPE.get().stateWithConnections(l,p));}
    public static int run(ServerLevel l){int passed=0;long time=l.getGameTime();try{
        for(var d:Design.values())for(var facing:Direction.Plane.HORIZONTAL){
            clear(l);var b=block(d);var layout=b.layout();var ctx=new DirectionalPlaceContext(l,ROOT,facing.getOpposite(),new ItemStack(b),Direction.UP);
            check(b.getStateForPlacement(ctx)!=null,"empty placement refused "+d);
            var obstruction=layout.world(ROOT,facing,layout.ports.getFirst());put(l,obstruction,Blocks.STONE.defaultBlockState());check(b.getStateForPlacement(ctx)==null,"obstruction ignored");l.removeBlock(obstruction,false);
            var be=place(l,d,ROOT,facing);int controllers=0;
            for(var c:layout.cells){var p=layout.world(ROOT,facing,c);var s=l.getBlockState(p);check(l.getBlockEntity(p) instanceof CoolingBlockEntity child&&child.owner()==be,"lost ownership");if(s.getValue(CONTROLLER))controllers++;
                var bounds=s.getShape(l,p).bounds();check(bounds.minX>=0&&bounds.minY>=0&&bounds.minZ>=0&&bounds.maxX<=1&&bounds.maxY<=1&&bounds.maxZ<=1,"out-of-cell collision");}
            check(controllers==1,"duplicate controllers");
            for(var c:layout.ports){var p=layout.world(ROOT,facing,c);var s=l.getBlockState(p);var face=portFace(s);check(l.getBlockState(p.relative(face)).isAir(),"blocked flange "+d+" "+c.role());
                for(var side:Direction.values()){
                    check(!SteamLineNetwork.acceptsLineOn(s,side),"cooling machine accepted steam pipe");
                    check(WaterLineNetwork.acceptsLineOn(s,side)==(side==face&&c.role().water()),"wrong water face");
                    check((l.getCapability(Capabilities.FluidHandler.BLOCK,p,side)!=null)==(side==face&&c.role().water()),"wrong fluid face");
                    check((l.getCapability(Capabilities.EnergyStorage.BLOCK,p,side)!=null)==(side==face&&c.role()==Port.POWER),"wrong FE face");
                }
                var adj=p.relative(face);var state=BwrBlocks.HIGH_PRESSURE_WATER_PIPE.get().stateWithConnections(l,adj);check(state.getValue(PipeBlock.PROPERTY_BY_DIRECTION.get(face.getOpposite()))==c.role().water(),"pipe arm mismatch");
            }
            var anchor=layout.world(ROOT,facing,layout.ports.getFirst());var player=net.neoforged.neoforge.common.util.FakePlayerFactory.getMinecraft(l);player.setPos(anchor.getX()+.5,anchor.getY(),anchor.getZ()+.5);
            var menu=new dev.bwr.mod.gui.CoolingMenu(1,player.getInventory(),ROOT,anchor);check(menu.stillValid(player),"far flange GUI invalid");player.setPos(0,0,0);check(!menu.stillValid(player),"remote GUI valid");
            var saved=be.saveWithoutMetadata(l.registryAccess());be.loadWithComponents(saved,l.registryAccess());check(be.ready(),"reload loses structure");
            var stale=cap(l,port(be,Port.OUTLET));var energy=d.watts>0?be.power():null;
            var child=layout.world(ROOT,facing,layout.ports.getFirst());var neighbor=child.relative(portFace(l.getBlockState(child)));put(l,neighbor,Blocks.DIAMOND_BLOCK.defaultBlockState());
            player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,new ItemStack(Items.NETHERITE_PICKAXE));b.playerWillDestroy(l,child,l.getBlockState(child),player);l.destroyBlock(child,true);
            for(var c:layout.cells)check(l.getBlockState(layout.world(ROOT,facing,c)).isAir(),"teardown leaves parts");check(l.getBlockState(neighbor).is(Blocks.DIAMOND_BLOCK),"teardown removes neighbor");
            check(stale.drain(100,EXECUTE).isEmpty()&&(energy==null||energy.receiveEnergy(100,false)==0),"stale handler works");
            int drops=l.getEntitiesOfClass(ItemEntity.class,new AABB(ROOT).inflate(40)).stream().filter(e->e.getItem().is(b.asItem())).mapToInt(e->e.getItem().getCount()).sum();check(drops==1,"drop count "+drops);passed++;
        }
        clear(l);var pump=place(l,Design.CIRCULATING,ROOT,Direction.NORTH);var inlet=cap(l,port(pump,Port.INLET));var outlet=cap(l,port(pump,Port.OUTLET));
        check(inlet.fill(new FluidStack(Fluids.WATER,3000),SIMULATE)==3000,"simulate rejected");near(pump.plant().input(),0,"simulate mutates");check(inlet.fill(new FluidStack(Fluids.LAVA,100),EXECUTE)==0,"lava accepted");
        inlet.fill(new FluidStack(Fluids.WATER,3000),EXECUTE);pump.setTarget(1);step(l,pump);near(pump.plant().flow(),0,"unpowered pump works");
        power(pump);pump.setTarget(.5);step(l,pump);near(pump.plant().flow(),15000,"speed ignored");check(outlet.drain(10000,EXECUTE).getAmount()==750&&outlet.drain(1,EXECUTE).isEmpty(),"shared tick budget exceeded");
        pump.setTarget(0);step(l,pump);check(outlet.drain(100,EXECUTE).isEmpty(),"stopped pump drains");
        var save=pump.saveWithoutMetadata(l.registryAccess());double stored=pump.plant().input();pump.loadWithComponents(save,l.registryAccess());near(pump.plant().input(),stored,"saved water lost");check(pump.storedFE()>0,"saved energy lost");
        var peripheral=new dev.bwr.mod.peripheral.CoolingPeripheral(pump);peripheral.setSpeed(.25);near(pump.target(),.25,"CC speed ignored");boolean invalid=false;try{peripheral.setSpeed(Double.NaN);}catch(Exception e){invalid=true;}check(invalid,"CC accepts NaN");passed++;
        clear(l);var tower=place(l,Design.MECHANICAL,ROOT,Direction.NORTH);tower.plant().fillInput(10000,false);tower.setTarget(1);step(l,tower);near(tower.plant().flow(),0,"unpowered fan cools");power(tower);step(l,tower);near(tower.plant().output(),490,"fan flow/loss wrong");near(tower.plant().loss(),200,"fan makeup wrong");passed++;
        clear(l);var intake=place(l,Design.INTAKE,ROOT,Direction.NORTH);step(l,intake);near(intake.plant().output(),0,"dry intake creates water");
        for(var p:List.of(ROOT.below(),ROOT.north(2),ROOT.west(2)))put(l,p,Blocks.WATER.defaultBlockState());check(intake.submerged(),"lake not detected");step(l,intake);near(intake.plant().output(),300,"lake intake rate");
        put(l,ROOT.north(2),Blocks.LAVA.defaultBlockState());double water=intake.plant().output();step(l,intake);near(intake.plant().output(),water,"lava creates water");
        put(l,ROOT.north(2),Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL,1));step(l,intake);near(intake.plant().output(),water,"flowing water creates water");passed++;
        clear(l);wetScreen(l);passed++;
        clear(l);legacyTower(l);passed++;
        clear(l);loop(l);passed++;
        clear(l);makeup(l);passed++;
        LogUtils.getLogger().info("COOLING RUNTIME CHECK PASS: {} scenarios; rotations, finite loop, FE, CC, lake source, teardown",passed);return 0;
    }catch(Throwable e){LogUtils.getLogger().error("COOLING RUNTIME CHECK FAIL after {} scenarios",passed,e);return 1;}finally{clear(l);((net.minecraft.world.level.storage.ServerLevelData)l.getLevelData()).setGameTime(time);}}
    private static void wetScreen(ServerLevel l){
        var b=block(Design.INTAKE);var layout=b.layout();
        put(l,ROOT.below(),Blocks.STONE.defaultBlockState());
        for(var c:layout.cells)put(l,layout.world(ROOT,Direction.NORTH,c),Blocks.WATER.defaultBlockState());
        for(var p:List.of(ROOT.north(2),ROOT.west(2)))put(l,p,Blocks.WATER.defaultBlockState());
        var ctx=new DirectionalPlaceContext(l,ROOT,Direction.SOUTH,new ItemStack(b),Direction.UP);
        var state=b.getStateForPlacement(ctx);check(state!=null&&state.getValue(WATERLOGGED),"wet placement displaces root water");
        put(l,ROOT,state);b.setPlacedBy(l,ROOT,state,null,new ItemStack(b));
        var be=(CoolingBlockEntity)l.getBlockEntity(ROOT);
        for(var c:layout.cells)check(l.getFluidState(layout.world(ROOT,Direction.NORTH,c)).isSource(),"part displaced source water");
        check(be.ready()&&be.submerged(),"solid lakebed invalidates intake");step(l,be);near(be.plant().output(),300,"wet intake did not produce water");
        put(l,ROOT.north(2),Blocks.STONE.defaultBlockState());put(l,ROOT.west(2),Blocks.STONE.defaultBlockState());
        step(l,be);near(be.plant().output(),300,"waterlogged screen self-supplies without lake");
        l.removeBlock(ROOT,false);
        for(var c:layout.cells)check(l.getFluidState(layout.world(ROOT,Direction.NORTH,c)).isSource(),"teardown lost water");
    }
    private static void legacyTower(ServerLevel l){
        var b=block(Design.MECHANICAL);var layout=CoolingLayout.legacy(Design.MECHANICAL);var state=b.defaultBlockState();
        put(l,ROOT,state);var owner=(CoolingBlockEntity)l.getBlockEntity(ROOT);var tag=owner.saveWithoutMetadata(l.registryAccess());tag.remove("LayoutVersion");owner.loadWithComponents(tag,l.registryAccess());owner.forming=true;
        for(var c:layout.cells){var p=layout.world(ROOT,Direction.NORTH,c);put(l,p,state.setValue(CONTROLLER,p.equals(ROOT)).setValue(PORT,c.role()));
            var child=(CoolingBlockEntity)l.getBlockEntity(p);child.bind(owner,c.index());}
        owner.forming=false;owner.structureDirty=true;check(owner.ready(),"legacy footprint no longer complete");
        owner.plant().fillMakeup(1234,false);var port=port(owner,Port.OUTLET);check(cap(l,port).drain(100,EXECUTE).getAmount()==100,"legacy port no longer works");
        var saved=owner.saveWithoutMetadata(l.registryAccess());check(saved.getInt("LayoutVersion")==1,"legacy footprint not preserved on save");
    }
    private static void loop(ServerLevel l){
        var tower=place(l,Design.MECHANICAL,new BlockPos(140,260,138),Direction.NORTH);
        var pump=place(l,Design.CIRCULATING,new BlockPos(140,260,152),Direction.NORTH);
        // Cold basin outlet -> suction, using the actual BWR pipe endpoint traversal.
        pipe(l,port(tower,Port.OUTLET).south(),port(pump,Port.INLET).north());
        var condenserRoot=new BlockPos(158,264,150);var cb=BwrBlocks.ARABELLE_CONDENSER.get();var cs=cb.defaultBlockState();
        for(var c:CondenserLayout.INSTANCE.cells)WRITTEN.add(CondenserLayout.INSTANCE.world(condenserRoot,Direction.NORTH,c));put(l,condenserRoot,cs);cb.setPlacedBy(l,condenserRoot,cs,null,new ItemStack(cb));
        var condenser=(CondenserBlockEntity)l.getBlockEntity(condenserRoot);check(condenser.ready(),"loop condenser missing");
        var cold=CondenserLayout.INSTANCE.world(condenserRoot,Direction.NORTH,CondenserLayout.INSTANCE.ports.stream().filter(c->c.role()==CondenserBlock.Port.COLD).findFirst().orElseThrow()).below();
        var out=port(pump,Port.OUTLET).south();pipe(l,out,new BlockPos(cold.getX(),out.getY(),out.getZ()));pipe(l,new BlockPos(cold.getX(),out.getY(),out.getZ()),new BlockPos(cold.getX(),out.getY(),cold.getZ()));pipe(l,new BlockPos(cold.getX(),out.getY(),cold.getZ()),cold);
        var hot=CondenserLayout.INSTANCE.world(condenserRoot,Direction.NORTH,CondenserLayout.INSTANCE.ports.stream().filter(c->c.role()==CondenserBlock.Port.HOT).findFirst().orElseThrow()).below();
        var in=port(tower,Port.INLET).north();var bend=new BlockPos(hot.getX(),hot.getY(),120);pipe(l,hot,bend);pipe(l,bend,new BlockPos(in.getX(),hot.getY(),120));pipe(l,new BlockPos(in.getX(),hot.getY(),120),new BlockPos(in.getX(),in.getY(),120));pipe(l,new BlockPos(in.getX(),in.getY(),120),in);
        tower.plant().fillMakeup(30000,false);power(tower);power(pump);
        for(int i=0;i<8;i++){power(pump);power(tower);step(l,pump,tower);condenser.plant().steam.offer(new dev.bwr.core.turbine.SteamInventory.Packet(100,2770,1015));CondenserBlockEntity.serverTick(l,condenserRoot,cs,condenser);}
        check(condenser.plant().condensate()>0&&tower.plant().totalLoss()>0,"real loop never condensed/returned/cooled water");
        near(tower.plant().input()+tower.plant().output()+tower.plant().totalLoss()+pump.plant().input()+pump.plant().output()+condenser.plant().cold()+condenser.plant().hot(),30000,"real loop creates or destroys water");
    }
    private static void makeup(ServerLevel l){
        var intake=place(l,Design.INTAKE,new BlockPos(140,260,125),Direction.NORTH);
        var pump=place(l,Design.MAKEUP,new BlockPos(140,260,132),Direction.NORTH);
        var tower=place(l,Design.MECHANICAL,new BlockPos(158,260,138),Direction.NORTH);
        for(var p:List.of(intake.root().below(),intake.root().north(2),intake.root().west(2)))put(l,p,Blocks.WATER.defaultBlockState());
        pipe(l,port(intake,Port.OUTLET).south(),port(pump,Port.INLET).north());
        var out=port(pump,Port.OUTLET).south();var in=port(tower,Port.MAKEUP).west();
        pipe(l,out,new BlockPos(out.getX(),out.getY(),in.getZ()));pipe(l,new BlockPos(out.getX(),out.getY(),in.getZ()),new BlockPos(in.getX(),out.getY(),in.getZ()));pipe(l,new BlockPos(in.getX(),out.getY(),in.getZ()),in);
        for(int i=0;i<10;i++){power(pump);step(l,intake,pump);}
        near(tower.plant().output(),750,"lake -> makeup pump -> cold basin did not transfer at 1500 kg/s");
        near(intake.plant().input()+intake.plant().output()+pump.plant().input()+pump.plant().output()+tower.plant().output(),3000,"makeup path duplicated water");
        var cc=l.getCapability(dan200.computercraft.api.peripheral.PeripheralCapability.get(),port(pump,Port.POWER),Direction.EAST);
        check(cc instanceof dev.bwr.mod.peripheral.CoolingPeripheral&&cc.getType().equals("bwr_makeup_water_pump"),"CC capability missing from physical part");
    }
}
