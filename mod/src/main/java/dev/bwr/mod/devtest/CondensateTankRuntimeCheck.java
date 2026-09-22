package dev.bwr.mod.devtest;
import com.mojang.logging.LogUtils;
import dev.bwr.mod.eccs.*;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.water.WaterLineNetwork;
import net.minecraft.core.*;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import java.util.*;
import static net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.*;

/** Structural sizing, survival accounting and legacy hydraulic regression. */
public final class CondensateTankRuntimeCheck {
    private static final BlockPos ROOT=new BlockPos(145,260,138);
    private static void check(boolean b,String s){if(!b)throw new AssertionError(s);}
    private static void near(double a,double b,String s){check(Math.abs(a-b)<1e-7,s+": "+a+" != "+b);}
    private static int items(ServerPlayer p){int n=0;for(int i=0;i<p.getInventory().getContainerSize();i++){var s=p.getInventory().getItem(i);if(s.is(BwrBlocks.CONDENSATE_STORAGE_TANK.get().asItem()))n+=s.getCount();}return n;}
    public static int run(ServerLevel l){int passed=0;var block=BwrBlocks.CONDENSATE_STORAGE_TANK.get();var player=net.neoforged.neoforge.common.util.FakePlayerFactory.getMinecraft(l);
        try{player.setGameMode(GameType.CREATIVE);player.setPos(190,290,145);l.setBlock(ROOT,block.defaultBlockState(),3);var tank=(CondensateStorageTankBlockEntity)l.getBlockEntity(ROOT);
            tank.fillKg(2000000);near(tank.storedKg(),2000000,"legacy capacity lost");var legacy=tank.saveWithoutMetadata(l.registryAccess());legacy.remove("TankRoot");legacy.remove("TankAssembly");legacy.remove("Diameter");legacy.remove("Height");legacy.remove("PaidBlocks");tank.loadWithComponents(legacy,l.registryAccess());near(tank.storedKg(),2000000,"legacy save water lost");
            check(!CondensateTankAssembly.resize(tank,player,3,3).startsWith("Assembled"),"overflow resize accepted");check(!tank.assembled(),"refused resize changed state");tank.fluidHandler().drain(Integer.MAX_VALUE,EXECUTE);passed++;
            for(int[] size:new int[][]{{3,3},{7,8},{15,24}}){
                check(CondensateTankAssembly.resize(tank,player,size[0],size[1]).startsWith("Assembled"),"resize failed");check(tank.ready(),"assembled tank incomplete");var shape=CondensateTankShape.get(size[0],size[1]);
                near(tank.capacityKg(),shape.capacity,"wrong cylinder capacity");int roots=0;
                for(var e:shape.cells.entrySet()){var p=ROOT.offset(e.getKey());var s=l.getBlockState(p);check(l.getBlockEntity(p) instanceof CondensateStorageTankBlockEntity part&&part.owner()==tank,"part owner mismatch");if(s.getValue(CondensateStorageTankBlock.CONTROLLER))roots++;
                    var b=s.getShape(l,p).bounds();check(b.minX>=0&&b.maxX<=1&&b.minY>=0&&b.maxY<=1&&b.minZ>=0&&b.maxZ<=1,"collision outside cell");}
                check(roots==1,"multiple inventory controllers");
                for(var face:Direction.Plane.HORIZONTAL){var p=ROOT.offset(CondensateTankShape.port(size[0],face));for(var side:Direction.values()){
                    check(WaterLineNetwork.acceptsLineOn(l.getBlockState(p),side)==(side==face),"wrong water side");check((l.getCapability(Capabilities.FluidHandler.BLOCK,p,side)!=null)==(side==face),"wrong fluid capability");}
                    var water=l.getCapability(Capabilities.FluidHandler.BLOCK,p,face);check(water.fill(new FluidStack(Fluids.WATER,1000),EXECUTE)==1000,"port fill failed");}
                near(tank.storedKg(),4000,"ports have duplicated tanks");tank.fluidHandler().drain(4000,EXECUTE);passed++;
            }
            tank.fillKg(1234);var port=ROOT.offset(CondensateTankShape.port(15,Direction.NORTH));var part=(CondensateStorageTankBlockEntity)l.getBlockEntity(port);near(part.drawKg(.25),.25,"part fractional suction");var saved=tank.saveWithoutMetadata(l.registryAccess());tank.loadWithComponents(saved,l.registryAccess());check(tank.ready(),"reload broke assembly");
            var cap=l.getCapability(Capabilities.FluidHandler.BLOCK,port,Direction.NORTH);check(cap.drain(999999,EXECUTE).getAmount()==1233,"external drain stole fractional debt");near(tank.drawKg(10),.75,"fractional water lost on load");near(tank.storedKg(),0,"tank not empty");passed++;
            check(CondensateTankAssembly.resize(tank,player,3,3).startsWith("Assembled"),"shrink failed");var obstruct=ROOT.east(3).above(2);l.setBlock(obstruct,Blocks.DIAMOND_BLOCK.defaultBlockState(),3);
            check(!CondensateTankAssembly.resize(tank,player,7,8).startsWith("Assembled"),"obstruction overwritten");check(l.getBlockState(obstruct).is(Blocks.DIAMOND_BLOCK)&&tank.diameter==3,"failed resize changed world");l.removeBlock(obstruct,false);passed++;
            player.getInventory().clearContent();player.setGameMode(GameType.SURVIVAL);int previous=tank.paidBlocks;
            check(!CondensateTankAssembly.resize(tank,player,7,8).startsWith("Assembled"),"free survival expansion");
            for(int i=0;i<36;i++)player.getInventory().setItem(i,new ItemStack(block,64));int before=items(player);check(CondensateTankAssembly.resize(tank,player,7,8).startsWith("Assembled"),"funded expansion refused");check(items(player)==before-tank.paidBlocks+previous,"wrong expansion cost");
            check(CondensateTankAssembly.resize(tank,player,3,3).startsWith("Assembled"),"funded shrink failed");check(items(player)==before,"shrink did not refund materials");passed++;
            var clicked=ROOT.offset(CondensateTankShape.port(3,Direction.NORTH));var state=l.getBlockState(clicked);var child=(CondensateStorageTankBlockEntity)l.getBlockEntity(clicked);var stale=child.fluidHandler();var required=tank.paidBlocks;
            var tool=new ItemStack(Items.NETHERITE_PICKAXE);player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,tool);
            block.playerWillDestroy(l,clicked,state,player);l.removeBlock(clicked,false);block.playerDestroy(l,player,clicked,state,child,tool);
            int drops=l.getEntitiesOfClass(ItemEntity.class,new AABB(ROOT).inflate(30)).stream().filter(e->e.getItem().is(block.asItem())).mapToInt(e->e.getItem().getCount()).sum();check(drops==required,"broken shell refund "+drops+" != "+required);check(stale.fill(new FluidStack(Fluids.WATER,100),EXECUTE)==0,"destroyed tank still accepts water");
            for(var p:CondensateTankShape.get(3,3).cells.keySet())check(l.getBlockState(ROOT.offset(p)).isAir(),"orphan tank part");passed++;
            automatic(l,player);passed++;
            LogUtils.getLogger().info("CONDENSATE TANK RUNTIME CHECK PASS: {} scenarios, automatic shells, min/mid/max, survival costs, pipes, legacy water and fractional persistence",passed);return 0;
        }catch(Throwable e){LogUtils.getLogger().error("CONDENSATE TANK RUNTIME CHECK FAIL after {} scenarios",passed,e);return 1;}
        finally{l.removeBlock(ROOT,false);l.getEntitiesOfClass(ItemEntity.class,new AABB(ROOT).inflate(30)).forEach(ItemEntity::discard);player.getInventory().clearContent();player.setGameMode(GameType.CREATIVE);}
    }
    public static CondensateStorageTankBlockEntity buildShell(ServerLevel l,ServerPlayer player,BlockPos root,int d,int h){
        var b=BwrBlocks.CONDENSATE_STORAGE_TANK.get();int r=d/2;
        // Roof center is deliberately last, just as a player completes a box.
        for(var p:BlockPos.betweenClosed(root.offset(-r,0,-r),root.offset(r,h-1,r))){
            var o=p.subtract(root);if(o.equals(new BlockPos(0,h-1,0)))continue;
            if(Math.abs(o.getX())==r||Math.abs(o.getZ())==r||o.getY()==0||o.getY()==h-1){
                l.setBlock(p,b.defaultBlockState(),3);b.setPlacedBy(l,p,l.getBlockState(p),player,new ItemStack(b));
            }
        }
        check(!((CondensateStorageTankBlockEntity)l.getBlockEntity(root)).assembled(),"incomplete roof autoformed");
        var p=root.above(h-1);l.setBlock(p,b.defaultBlockState(),3);b.setPlacedBy(l,p,l.getBlockState(p),player,new ItemStack(b));
        return (CondensateStorageTankBlockEntity)l.getBlockEntity(root);
    }
    private static void automatic(ServerLevel l,ServerPlayer player){
        var b=BwrBlocks.CONDENSATE_STORAGE_TANK.get();player.setPos(190,290,145);player.setGameMode(GameType.SURVIVAL);
        for(int[] size:new int[][]{{3,3},{7,8},{15,24}}){
            var t=buildShell(l,player,ROOT,size[0],size[1]);check(t.assembled()&&t.ready(),"complete shell did not autoform");
            int paid=size[0]*size[0]*size[1]-(size[0]-2)*(size[0]-2)*(size[1]-2);check(t.paidBlocks==paid,"paid shell blocks were miscounted");
            var tag=t.saveWithoutMetadata(l.registryAccess());t.loadWithComponents(tag,l.registryAccess());check(t.ready(),"automatic tank failed reload");l.removeBlock(ROOT,false);
        }
        // Incomplete/obstructed boxes keep their blocks and fractional water untouched.
        l.setBlock(ROOT.above(),Blocks.DIAMOND_BLOCK.defaultBlockState(),3);
        var t=buildShell(l,player,ROOT,3,3);check(!t.assembled(),"autoformation destroyed an interior block");
        t.fillKg(25);near(t.drawKg(.25),.25,"fractional seed failed");
        l.removeBlock(ROOT.above(),false);var last=ROOT.above(2);b.setPlacedBy(l,last,l.getBlockState(last),player,new ItemStack(b));
        check(t.ready(),"unobstructed complete shell failed to form");near(t.availableWaterKg(),24.75,"autoformation duplicated fractional water");
        var port=ROOT.offset(CondensateTankShape.port(3,Direction.NORTH));var part=(CondensateStorageTankBlockEntity)l.getBlockEntity(port);var state=l.getBlockState(port);var tool=new ItemStack(Items.NETHERITE_PICKAXE);
        l.getEntitiesOfClass(ItemEntity.class,new AABB(ROOT).inflate(30)).forEach(ItemEntity::discard);
        player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,tool);b.playerWillDestroy(l,port,state,player);l.removeBlock(port,false);b.playerDestroy(l,player,port,state,part,tool);
        int drops=l.getEntitiesOfClass(ItemEntity.class,new AABB(ROOT).inflate(30)).stream().filter(e->e.getItem().is(b.asItem())).mapToInt(e->e.getItem().getCount()).sum();check(drops==26,"automatic shell refund did not preserve 26 placed blocks");
    }
}
