package dev.bwr.mod.devtest;

import com.mojang.logging.LogUtils;
import dev.bwr.mod.registry.*;
import dev.bwr.mod.steam.*;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.DirectionalPlaceContext;
import net.minecraft.world.level.block.*;
import net.minecraft.world.phys.AABB;
import java.util.List;

/** Placement, saved-world compatibility, real steam routing and a timed four-second stroke. */
public final class MsivRuntimeCheck {
    private static final BlockPos ROOT=new BlockPos(174,244,142);
    private static void check(boolean okay,String message) { if(!okay)throw new AssertionError(message); }
    private static void near(double a,double b,String message) { check(Math.abs(a-b)<1e-9,message+": "+a+" != "+b); }
    private static void clear(ServerLevel l) {
        for(var p:BlockPos.betweenClosed(ROOT.offset(-3,-1,-3),ROOT.offset(3,4,3)))if(!l.getBlockState(p).isAir())l.removeBlock(p,false);
        l.getEntitiesOfClass(ItemEntity.class,new AABB(ROOT).inflate(5)).forEach(ItemEntity::discard);
    }
    public static int run(ServerLevel l) {
        int passed=0;var b=BwrBlocks.MSIV.get();
        try {
            for(Direction facing:Direction.Plane.HORIZONTAL) {
                clear(l);
                var ctx=new DirectionalPlaceContext(l,ROOT,Direction.DOWN,new ItemStack(b),Direction.UP);
                check(b.getStateForPlacement(ctx)!=null,"clear three-block footprint refused");
                l.setBlock(ROOT.above(2),Blocks.STONE.defaultBlockState(),3);
                check(b.getStateForPlacement(ctx)==null,"MSIV accepted an obstructed actuator");
                l.removeBlock(ROOT.above(2),false);
                var state=b.placementState().setValue(MainSteamIsolationValveBlock.FACING,facing);
                l.setBlock(ROOT,state,3);b.setPlacedBy(l,ROOT,state,null,new ItemStack(b));
                for(int part=0;part<3;part++) {
                    var p=ROOT.above(part);var s=l.getBlockState(p);
                    check(s.is(b)&&s.getValue(MainSteamIsolationValveBlock.PART)==part,"missing MSIV part");
                    check((l.getBlockEntity(p)!=null)==(part==0),"MSIV has duplicate actuator simulation");
                    for(Direction side:Direction.values()) {
                        boolean port=part==0&&side.getAxis()==facing.getAxis();
                        check(SteamLineNetwork.acceptsLineOn(s,side)==port,"steam entered actuator or wrong flange");
                        check(!dev.bwr.mod.water.WaterLineNetwork.acceptsLineOn(s,side),"MSIV accepted water");
                        var pipe=p.relative(side);if(l.getBlockState(pipe).is(b))continue;
                        var tube=BwrBlocks.PRESSURISED_TUBE.get().stateWithConnections(l,pipe);
                        check(tube.getValue(PipeBlock.PROPERTY_BY_DIRECTION.get(side.getOpposite()))==port,"visible pipe does not match physical flange");
                    }
                }
                // The new model must remain a traversable valve, not a machine endpoint.
                var output=ROOT.relative(facing,2);
                l.setBlock(ROOT.relative(facing),BwrBlocks.PRESSURISED_TUBE.get().stateWithConnections(l,ROOT.relative(facing)),3);
                l.setBlock(output,BwrBlocks.TURBINE_STEAM_OUTLET.get().defaultBlockState(),3);
                var controlPos=ROOT.relative(facing.getOpposite());
                var source=ROOT.relative(facing.getOpposite(),2);
                l.setBlock(source,BwrBlocks.PRESSURISED_TUBE.get().stateWithConnections(l,source),3);
                var cb=BwrBlocks.TURBINE_CONTROL_VALVE.get();
                l.setBlock(controlPos,cb.defaultBlockState().setValue(TurbineValveBlock.FACING,facing),3);
                var control=(TurbineValveBlockEntity)l.getBlockEntity(controlPos);control.setTarget(1);control.stroke(3);
                var valve=(MainSteamIsolationValveBlockEntity)l.getBlockEntity(ROOT);
                var energy=l.getCapability(net.neoforged.neoforge.capabilities.Capabilities.EnergyStorage.BLOCK,ROOT.above(2),Direction.UP);
                check(energy!=null,"MSIV actuator has no cable port");
                near(valve.getPosition(),0,"new unpowered valve started open");
                valve.tickValve(4);near(valve.getPosition(),0,"unpowered opening command moved MSIV");
                energy.receiveEnergy(20_000,false);valve.tickValve(4);
                near(valve.getPosition(),1,"powered valve did not open");
                // Redstone on the upper actuator reaches the single base controller.
                var signal=ROOT.above(2).relative(facing.getClockWise());
                l.setBlock(signal,Blocks.REDSTONE_BLOCK.defaultBlockState(),3);
                check(!valve.isDemandOpen(),"upper-part redstone did not close MSIV");
                var ticker=b.getTicker(l,l.getBlockState(ROOT),BwrBlockEntities.MSIV.get());
                check(ticker!=null,"MSIV has no server ticker");
                for(int tick=0;tick<=80;tick++) {
                    if(tick>0)ticker.tick(l,ROOT,l.getBlockState(ROOT),valve);
                    near(valve.getPosition(),1-tick/80.0,"four-second closing trajectory");
                    near(SteamValveRouting.nozzleOpening(l,source),valve.getPosition(),"steam did not follow actual valve position");
                }
                check(valve.getPosition()==0,"80th tick did not reach the closed stop exactly");
                l.removeBlock(signal,false);check(valve.isDemandOpen(),"redstone release did not reopen MSIV");
                for(int i=0;i<30;i++)ticker.tick(l,ROOT,l.getBlockState(ROOT),valve);
                var saved=valve.saveWithoutMetadata(l.registryAccess());
                var copy=new MainSteamIsolationValveBlockEntity(ROOT,state);copy.loadWithComponents(saved,l.registryAccess());
                near(copy.getPosition(),.375,"moving MSIV lost position on reload");
                copy.tickValve(Double.NaN);copy.tickValve(-1);near(copy.getPosition(),.375,"invalid elapsed time changed stroke");
                copy.setDemandOpen(false);copy.tickValve(.5);near(copy.getPosition(),.25,"reversal teleported stem");
                if(net.neoforged.fml.ModList.get().isLoaded("computercraft")) {
                    Object peripheral=Class.forName("dev.bwr.mod.peripheral.MainSteamIsolationValvePeripheral")
                            .getConstructor(MainSteamIsolationValveBlockEntity.class).newInstance(valve);
                    peripheral.getClass().getMethod("close").invoke(peripheral);
                    check(valve.isComputerControlled()&&!valve.isDemandOpen(),"MSIV CC control regressed");
                }
                // Survival removal through an upper part drops exactly one complete valve.
                var player=net.neoforged.neoforge.common.util.FakePlayerFactory.getMinecraft(l);
                player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,new ItemStack(Items.NETHERITE_PICKAXE));
                var top=ROOT.above(2);var topState=l.getBlockState(top);
                b.playerWillDestroy(l,top,topState,player);l.destroyBlock(top,true);
                for(int i=0;i<3;i++)check(l.getBlockState(ROOT.above(i)).isAir(),"broken MSIV left an orphan part");
                int drops=l.getEntitiesOfClass(ItemEntity.class,new AABB(ROOT).inflate(4)).stream()
                        .filter(e->e.getItem().is(b.asItem())).mapToInt(e->e.getItem().getCount()).sum();
                check(drops==1,"upper MSIV removal produced "+drops+" items");passed++;
            }
            clear(l);
            var tag=new CompoundTag();tag.putString("Name","bwr:msiv");var properties=new CompoundTag();properties.putString("open","false");tag.put("Properties",properties);
            var legacy=NbtUtils.readBlockState(l.holderLookup(net.minecraft.core.registries.Registries.BLOCK),tag);
            check(!legacy.getValue(MainSteamIsolationValveBlock.ASSEMBLED),"old valve silently expanded");
            l.setBlock(ROOT,legacy,3);l.setBlock(ROOT.above(),Blocks.STONE.defaultBlockState(),3);
            for(Direction side:Direction.values())check(SteamLineNetwork.acceptsLineOn(legacy,side),"old valve lost a pipe connection");
            var valve=(MainSteamIsolationValveBlockEntity)l.getBlockEntity(ROOT);
            var saved=new CompoundTag();saved.putDouble("Position",.4);saved.putBoolean("DemandOpen",false);saved.putBoolean("ComputerControlled",true);
            valve.loadWithComponents(saved,l.registryAccess());valve.tickValve(.4);near(valve.getPosition(),.3,"legacy stroke/NBT broken");
            l.removeBlock(ROOT,false);check(l.getBlockState(ROOT.above()).is(Blocks.STONE),"legacy removal damaged neighbour");passed++;
            LogUtils.getLogger().info("MSIV RUNTIME CHECK PASS: {} scenarios (four rotations and legacy save)",passed);return 0;
        } catch(Throwable e) { LogUtils.getLogger().error("MSIV RUNTIME CHECK FAIL after {} scenarios",passed,e);return 1; }
        finally { clear(l); }
    }
}
