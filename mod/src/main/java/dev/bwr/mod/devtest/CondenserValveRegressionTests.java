package dev.bwr.mod.devtest;

import dev.bwr.mod.condenser.*;
import dev.bwr.mod.eccs.PumpAssemblyBlock;
import dev.bwr.mod.eccs.TurbineAssemblyBlock;
import dev.bwr.mod.power.PowerModuleBlockEntity;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.steam.*;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.*;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.gametest.*;

/** Real placement and capability regressions; never shipped in the release jar. */
@GameTestHolder("bwr") @PrefixGameTestTemplate(false)
public final class CondenserValveRegressionTests {
    @GameTest(template="empty",timeoutTicks=200)
    public static void condenserUndersidePlacement(GameTestHelper h){
        var level=h.getLevel();var root=new BlockPos(11900,210,11900);
        ReleaseRegressionTests.load(level,root);
        var player=net.neoforged.neoforge.common.util.FakePlayerFactory.getMinecraft(level);
        var lp=BwrBlocks.LP_TURBINE.get();var block=BwrBlocks.ARABELLE_CONDENSER.get();
        player.setGameMode(GameType.SURVIVAL);player.setPos(root.getX()+12,root.getY()+4,root.getZ());
        h.assertTrue(CondenserLayout.INSTANCE.size.equals(new BlockPos(7,6,9)),"condenser transverse footprint incorrect");
        for(var facing:Direction.Plane.HORIZONTAL){
            var condenserFacing=facing.getClockWise();
            var turbine=root.above(6);var state=lp.placementState().setValue(PumpAssemblyBlock.FACING,facing);
            level.setBlock(turbine,state,3);lp.setPlacedBy(level,turbine,state,null,new ItemStack(lp));
            // Deliberately click away from the root and face across the shaft.
            var clicked=turbine.offset(TurbineAssemblyBlock.turn(new BlockPos(2,0,-2),facing));
            h.assertTrue(level.getBlockState(clicked).is(lp),"off-centre underside fixture is empty");
            var hit=new BlockHitResult(Vec3.atBottomCenterOf(clicked),Direction.DOWN,clicked,false);
            var target=CondenserPlacement.attachment(level,hit);
            h.assertTrue(target!=null&&target.root().equals(root)&&target.facing()==condenserFacing&&target.complete(level),"underside did not resolve LP root/facing");
            player.setYRot(facing.getClockWise().toYRot());
            var stack=new ItemStack(block,2);player.setItemInHand(InteractionHand.MAIN_HAND,stack);
            var context=new BlockPlaceContext(level,player,InteractionHand.MAIN_HAND,stack,hit);
            var obstacle=CondenserLayout.INSTANCE.world(root,condenserFacing,CondenserLayout.INSTANCE.ports.getFirst());
            level.setBlock(obstacle,Blocks.DIAMOND_BLOCK.defaultBlockState(),3);
            h.assertTrue(!CondenserBlock.canPlaceAt(level,player,root,condenserFacing),"preview accepts blocked footprint");
            h.assertTrue(!((CondenserItem)stack.getItem()).place(context).consumesAction()&&stack.getCount()==2
                    && level.getBlockState(root).isAir()&&level.getBlockState(obstacle).is(Blocks.DIAMOND_BLOCK),"rejected snap wrote blocks or consumed item");
            level.removeBlock(obstacle,false);
            var entity=new net.minecraft.world.entity.decoration.ArmorStand(level,root.getX()+.5,root.getY(),root.getZ()+.5);
            level.addFreshEntity(entity);
            h.assertTrue(!CondenserBlock.canPlaceAt(level,player,root,condenserFacing)&&!((CondenserItem)stack.getItem()).place(context).consumesAction()
                    &&stack.getCount()==2,"snap placed through an entity");
            entity.discard();
            h.assertTrue(CondenserBlock.canPlaceAt(level,player,root,condenserFacing),"clear snap would not show green");
            h.assertTrue(level.getBlockState(clicked).useItemOn(stack,level,player,InteractionHand.MAIN_HAND,hit)
                    ==net.minecraft.world.ItemInteractionResult.SKIP_DEFAULT_BLOCK_INTERACTION,"LP menu swallows condenser placement");
            player.setItemInHand(InteractionHand.MAIN_HAND,ItemStack.EMPTY);player.setItemInHand(InteractionHand.OFF_HAND,stack);
            h.assertTrue(level.getBlockState(clicked).useItemOn(ItemStack.EMPTY,level,player,InteractionHand.MAIN_HAND,hit)
                    ==net.minecraft.world.ItemInteractionResult.SKIP_DEFAULT_BLOCK_INTERACTION,"LP menu swallows offhand condenser");
            player.setItemInHand(InteractionHand.OFF_HAND,ItemStack.EMPTY);player.setItemInHand(InteractionHand.MAIN_HAND,stack);
            h.assertTrue(((CondenserItem)stack.getItem()).place(context).consumesAction()&&stack.getCount()==1,"snapping did not place exactly one item");
            h.assertTrue(level.getBlockEntity(root) instanceof CondenserBlockEntity be&&be.ready()
                    &&be.getBlockState().getValue(CondenserBlock.FACING)==condenserFacing,"condenser did not form at snapped root");
            h.assertTrue(((PowerModuleBlockEntity)level.getBlockEntity(turbine)).condenser()!=null,"LP did not recognize attached condenser");
            level.removeBlock(root,false);h.assertTrue(lp.complete(level,turbine,state),"condenser removal damaged LP");
            level.removeBlock(turbine,false);
        }
        h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=200)
    public static void narrowCondenserSaveStillFits(GameTestHelper h){
        var l=h.getLevel();var root=new BlockPos(12000,210,12000);ReleaseRegressionTests.load(l,root);
        var block=BwrBlocks.ARABELLE_CONDENSER.get();var state=block.defaultBlockState();
        for(int version:new int[]{2,3}){var old=version==2?CondenserLayout.COMPACT_V2:CondenserLayout.WIDE_V3;
        l.setBlock(root,state,3);var owner=(CondenserBlockEntity)l.getBlockEntity(root);
        var saved=owner.saveWithoutMetadata(l.registryAccess());saved.putInt("LayoutVersion",version);saved.putInt("Cell",old.controllerIndex());saved.putDouble("Cold",4567);
        owner.loadWithComponents(saved,l.registryAccess());
        for(var cell:old.cells){var p=old.world(root,Direction.NORTH,cell);if(p.equals(root))continue;
            l.setBlock(p,state.setValue(CondenserBlock.CONTROLLER,false).setValue(CondenserBlock.PORT,cell.role()),2);
            ((CondenserBlockEntity)l.getBlockEntity(p)).bind(owner,cell.index());
        }
        h.assertTrue(owner.layout()==old&&owner.ready(),"v2 save changed footprint/cell indices");
        ReleaseRegressionTests.near(h,owner.plant().cold(),4567,"v2 inventory changed");
        var lp=BwrBlocks.LP_TURBINE.get();var ls=lp.placementState();l.setBlock(root.above(6),ls,3);lp.setPlacedBy(l,root.above(6),ls,null,new ItemStack(lp));
        h.assertTrue(((PowerModuleBlockEntity)l.getBlockEntity(root.above(6))).condenser()==owner,"old LP seat disconnected");
        var neighbor=root.east(old.size.getX()/2+1);l.setBlock(neighbor,Blocks.DIAMOND_BLOCK.defaultBlockState(),3);
        l.removeBlock(root,false);h.assertTrue(l.getBlockState(neighbor).is(Blocks.DIAMOND_BLOCK),"v2 teardown erased new-width neighbour");
        l.removeBlock(neighbor,false);l.removeBlock(root.above(6),false);}
        h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=200)
    public static void msivPoweredActuatorAndComputers(GameTestHelper h)throws Exception{
        var l=h.getLevel();var root=new BlockPos(12100,210,12100);ReleaseRegressionTests.load(l,root);var block=BwrBlocks.MSIV.get();
        for(var facing:Direction.Plane.HORIZONTAL){
            var state=block.placementState().setValue(MainSteamIsolationValveBlock.FACING,facing);
            l.setBlock(root,state,3);block.setPlacedBy(l,root,state,null,new ItemStack(block));
            var valve=(MainSteamIsolationValveBlockEntity)l.getBlockEntity(root);
            valve.setDemandOpen(true);valve.tickValve(4);ReleaseRegressionTests.near(h,valve.getPosition(),0,"opening without power");
            for(int part=0;part<3;part++)for(var side:Direction.values()){
                boolean expected=part!=0||side.getAxis()!=facing.getAxis();
                h.assertTrue((l.getCapability(Capabilities.EnergyStorage.BLOCK,root.above(part),side)!=null)==expected,"wrong electrical face");
            }
            var energy=l.getCapability(Capabilities.EnergyStorage.BLOCK,root.above(2),Direction.UP);
            h.assertTrue(energy.receiveEnergy(-10,false)==0&&energy.receiveEnergy(20_000,true)==20_000&&energy.getEnergyStored()==0,"simulated/invalid FE changed tank");
            h.assertTrue(energy.receiveEnergy(25_000,false)==20_000&&energy.extractEnergy(100,false)==0,"FE capacity/direction");
            for(int t=1;t<=80;t++){valve.tickValve(.05);ReleaseRegressionTests.near(h,valve.getPosition(),t/80.0,"powered opening trajectory");}
            h.assertTrue(energy.getEnergyStored()==12_000,"opening energy not conserved");
            valve.tickValve(1);h.assertTrue(energy.getEnergyStored()==11_600,"holding power missing");
            var tag=valve.saveWithoutMetadata(l.registryAccess());var copy=new MainSteamIsolationValveBlockEntity(root,state);copy.loadWithComponents(tag,l.registryAccess());
            h.assertTrue(copy.energy().getEnergyStored()==11_600&&copy.getPosition()==1,"actuator save lost state");
            if(net.neoforged.fml.ModList.get().isLoaded("computercraft"))MsivCcChecks.verify(h,root,valve);
            if(net.neoforged.fml.ModList.get().isLoaded("mekanism"))cable(h,root);
            tag=valve.saveWithoutMetadata(l.registryAccess());tag.putInt("EnergyFe",0);tag.putDouble("Position",1);tag.putBoolean("DemandOpen",true);valve.loadWithComponents(tag,l.registryAccess());
            for(int t=1;t<=80;t++){valve.tickValve(.05);ReleaseRegressionTests.near(h,valve.getPosition(),1-t/80.0,"unpowered spring closure trajectory");}
            h.assertTrue(valve.energy().getEnergyStored()==0&&valve.isDemandOpen(),"spring closure altered operator command or created energy");
            energy.receiveEnergy(8000,false);valve.tickValve(4);ReleaseRegressionTests.near(h,valve.getPosition(),1,"power restoration failed");
            l.removeBlock(root.above(2),false);h.assertTrue(energy.receiveEnergy(100,false)==0,"retained cable powers removed valve");
        }
        h.succeed();
    }
    private static void cable(GameTestHelper h,BlockPos root)throws Exception{
        var l=h.getLevel();var pos=root.above(3);var cable=net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("mekanism","basic_universal_cable"));
        l.setBlock(pos,cable.defaultBlockState(),3);var tile=l.getBlockEntity(pos);
        var base=Class.forName("mekanism.common.tile.transmitter.TileEntityTransmitter");
        base.getMethod("tickServer",net.minecraft.world.level.Level.class,BlockPos.class,net.minecraft.world.level.block.state.BlockState.class,base).invoke(null,l,pos,l.getBlockState(pos),tile);
        var transmitter=tile.getClass().getMethod("getTransmitter").invoke(tile);var type=transmitter.getClass();type.getMethod("refreshConnections").invoke(transmitter);
        h.assertTrue(type.getMethod("getAcceptor",Direction.class).invoke(transmitter,Direction.DOWN)!=null,"Mekanism cable does not recognize upper MSIV actuator");
        l.removeBlock(pos,false);
    }
}
