package dev.bwr.mod.devtest;

import dev.bwr.core.fuel.*;
import dev.bwr.mod.fuel.*;
import dev.bwr.mod.gui.CoreMapSnapshot;
import dev.bwr.mod.reactor.ReactorControllerBlockEntity;
import dev.bwr.mod.registry.BwrItems;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.gametest.*;

@GameTestHolder("bwr") @PrefixGameTestTemplate(false)
public final class FuelVarietyRegressionTests {
    @GameTest(template="empty",timeoutTicks=300)
    public static void catalogueAndComponents(GameTestHelper h) {
        for (var type : FuelType.presets()) {
            h.assertTrue(type.equals(FuelTypes.byName(type.name())), "Datapack and compiled grade differ: " + type.name());
            var stack=FuelAssemblyItem.stackOf(BwrItems.FUEL_ASSEMBLY.get(),FuelAssemblyData.fresh(type));
            var tag=stack.save(h.getLevel().registryAccess());
            var copy=ItemStack.parse(h.getLevel().registryAccess(),tag).orElseThrow();
            h.assertTrue(FuelAssemblies.dataOf(copy).equals(FuelAssemblies.dataOf(stack)),"Fuel item lost properties");
        }
        for (var family : FuelFabrication.Family.values()) for(double enrichment:new double[]{.00711,.012,.014,.027,.035,.0495,.08,.1975,.2}) {
            var type=FuelFabrication.select(family,enrichment);
            h.assertTrue(type!=null,"Unreachable grade");
        }
        h.assertTrue(FuelFabrication.select(FuelFabrication.Family.URANIUM,.027).name().equals("uranium_27"),"Fabricator ignores low enrichment grades");
        h.assertTrue(FuelFabrication.select(FuelFabrication.Family.URANIUM,.2).name().equals("heu"),"HEU compatibility lost");
        for(var k:CoreInsert.Kind.values()) {
            var data=new CoreInsertData(k,123);var stack=SpecialtyRodItem.stack(data);
            var restored=ItemStack.parse(h.getLevel().registryAccess(),stack.save(h.getLevel().registryAccess())).orElseThrow();
            h.assertTrue(SpecialtyRodItem.data(restored).equals(data),"Insert component lost exposure");
        }
        h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=300)
    public static void mixedCoreSaveShuffleUnload(GameTestHelper h) throws Exception {
        var l=h.getLevel();var be=CompactCoreRegressionTests.build(l,new BlockPos(4800,190,4800),5,5,false);
        int a=be.corePositions()[0],b=be.corePositions()[1];
        var data=new CoreInsertData(CoreInsert.Kind.COBALT_TARGET,333);
        h.assertTrue(be.loadAssembly(a,SpecialtyRodItem.stack(data)),"Target rejected from refuelling slot");
        h.assertTrue(!be.loadAssembly(a,new ItemStack(BwrItems.FUEL_ASSEMBLY.get())),"Fuel overwrote target");
        h.assertTrue(be.loadAssembly(b,new ItemStack(BwrItems.FUEL_ASSEMBLY.get())),"Fuel load failed");
        be.swapAssemblies(a,b);be.refreshFuelDefinitions();
        var buf=new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        try {
            CoreMapSnapshot.write(buf,be.core(),be.corePositions());var map=CoreMapSnapshot.read(buf);
            h.assertTrue(map.isOccupied(1)&&map.isInsert(1)&&!map.isInsert(0),"Map hid or mislabeled insert");
            h.assertTrue(Math.abs(map.insertProgress[1]-333.0/CoreInsert.Kind.COBALT_TARGET.exposureSeconds)<1e-12&&map.assemblyThermalMW(1)==0,"Map lost exposure or fabricated power");
            h.assertTrue(buf.readableBytes()==0,"Core-map protocol mismatch");
        } finally {buf.release();}
        var saved=be.saveWithFullMetadata(l.registryAccess());var pos=be.getBlockPos();var state=be.getBlockState();l.removeBlockEntity(pos);
        var restored=(ReactorControllerBlockEntity)net.minecraft.world.level.block.entity.BlockEntity.loadStatic(pos,state,saved,l.registryAccess());
        l.setBlockEntity(restored);var validate=ReactorControllerBlockEntity.class.getDeclaredMethod("revalidate",Level.class);validate.setAccessible(true);validate.invoke(restored,l);
        h.assertTrue(restored.isFormed(),"Mixed core no longer forms");
        var target=restored.unloadAssembly(b);h.assertTrue(SpecialtyRodItem.data(target).equals(data),"Target exposure changed on reload");
        h.assertTrue(restored.unloadAssembly(b).isEmpty(),"Target duplicated on unload");
        h.assertTrue(restored.takeFuelForRemoval().size()==1&&restored.takeFuelForRemoval().isEmpty(),"Controller removal duplicated contents");
        // Recovery before formation must also preserve the new tagged entries.
        restored.loadWithComponents(saved,l.registryAccess());
        l.removeBlockEntity(pos);
        var pending=(ReactorControllerBlockEntity)net.minecraft.world.level.block.entity.BlockEntity.loadStatic(pos,state,saved,l.registryAccess());
        var recovered=pending.takeFuelForRemoval();
        h.assertTrue(recovered.size()==2&&recovered.stream().anyMatch(s->s.is(BwrItems.SPECIALTY_ROD.get())&&SpecialtyRodItem.data(s).equals(data)),"Unformed recovery lost target");
        h.assertTrue(pending.takeFuelForRemoval().isEmpty(),"Pending target recovered twice");l.removeBlock(pos,false);h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=200)
    public static void targetHarvestIsSingleUse(GameTestHelper h) {
        var player=net.neoforged.neoforge.common.util.FakePlayerFactory.getMinecraft(h.getLevel());player.getInventory().clearContent();
        var partial=SpecialtyRodItem.stack(new CoreInsertData(CoreInsert.Kind.TRITIUM_TARGET,200));player.setItemInHand(InteractionHand.MAIN_HAND,partial);
        partial.getItem().use(h.getLevel(),player,InteractionHand.MAIN_HAND);
        h.assertTrue(!partial.isEmpty(),"Premature target harvest");
        var finished=SpecialtyRodItem.stack(new CoreInsertData(CoreInsert.Kind.TRITIUM_TARGET,CoreInsert.Kind.TRITIUM_TARGET.exposureSeconds));player.setItemInHand(InteractionHand.MAIN_HAND,finished);
        BwrItems.SPECIALTY_ROD.get().use(h.getLevel(),player,InteractionHand.MAIN_HAND);
        BwrItems.SPECIALTY_ROD.get().use(h.getLevel(),player,InteractionHand.MAIN_HAND);
        h.assertTrue(player.getInventory().countItem(BwrItems.TRITIUM_SAMPLE.get())==1250,"Harvest did not yield exactly one batch of 1,250 samples");
        h.assertTrue(player.getInventory().countItem(BwrItems.IRRADIATION_CASING.get())==1,"Harvest did not return one casing");
        for(var stack:player.getInventory().items) h.assertTrue(stack.getCount()<=stack.getMaxStackSize(),"Harvest created an oversized inventory stack");
        player.getInventory().clearContent();h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=200)
    public static void largeTritiumHarvestPreservesOverflow(GameTestHelper h) {
        var player=net.neoforged.neoforge.common.util.FakePlayerFactory.getMinecraft(h.getLevel());
        var oldMode=player.gameMode.getGameModeForPlayer();var oldPos=player.position();
        var pos=h.absolutePos(new BlockPos(1,1,1));
        var bounds=new net.minecraft.world.phys.AABB(pos).inflate(4);
        try {
            player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
            player.setPos(pos.getX()+.5,pos.getY(),pos.getZ()+.5);
            player.getInventory().clearContent();player.getInventory().selected=0;
            for(int i=0;i<36;i++)player.getInventory().setItem(i,new ItemStack(net.minecraft.world.item.Items.COBBLESTONE,64));
            player.getInventory().setItem(1,new ItemStack(BwrItems.TRITIUM_SAMPLE.get(),60));
            var finished=SpecialtyRodItem.stack(new CoreInsertData(CoreInsert.Kind.TRITIUM_TARGET,CoreInsert.Kind.TRITIUM_TARGET.exposureSeconds));
            player.setItemInHand(InteractionHand.MAIN_HAND,finished);
            BwrItems.SPECIALTY_ROD.get().use(h.getLevel(),player,InteractionHand.MAIN_HAND);
            BwrItems.SPECIALTY_ROD.get().use(h.getLevel(),player,InteractionHand.MAIN_HAND);
            var drops=h.getLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,bounds);
            int sampleDrops=drops.stream().filter(e->e.getItem().is(BwrItems.TRITIUM_SAMPLE.get())).mapToInt(e->e.getItem().getCount()).sum();
            int casingDrops=drops.stream().filter(e->e.getItem().is(BwrItems.IRRADIATION_CASING.get())).mapToInt(e->e.getItem().getCount()).sum();
            h.assertTrue(sampleDrops>0,"Overflow path was not exercised");
            h.assertTrue(player.getInventory().countItem(BwrItems.TRITIUM_SAMPLE.get())+sampleDrops==1310,"Harvest lost or duplicated samples when inventory was full");
            h.assertTrue(player.getInventory().countItem(BwrItems.IRRADIATION_CASING.get())+casingDrops==1,"Overflow lost or duplicated the casing");
            for(var stack:player.getInventory().items)h.assertTrue(stack.getCount()<=stack.getMaxStackSize(),"Oversized inventory stack");
            for(var drop:drops)h.assertTrue(drop.getItem().getCount()<=drop.getItem().getMaxStackSize(),"Oversized dropped stack");
            h.succeed();
        } finally {
            h.getLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,bounds).forEach(net.minecraft.world.entity.item.ItemEntity::discard);
            player.getInventory().clearContent();player.setGameMode(oldMode);player.setPos(oldPos);
        }
    }
    @GameTest(template="empty",timeoutTicks=500)
    public static void optionalMekanismTritiumProcessing(GameTestHelper h) {
        var recipe=h.getLevel().getRecipeManager().byKey(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("bwr","tritium_sample_oxidizing"));
        if (!net.neoforged.fml.ModList.get().isLoaded("mekanism") || !net.neoforged.fml.ModList.get().isLoaded("mekanismgenerators")) {
            h.assertTrue(recipe.isEmpty(),"Tritium recipe loaded without both Mekanism and Generators"); h.succeed(); return;
        }
        h.assertTrue(recipe.isPresent(),"Tritium processing recipe missing");
        MekanismTritiumCheck.run(h);
    }
    @GameTest(template="empty",timeoutTicks=200)
    public static void poweredCoreIrradiatesNonFuelTarget(GameTestHelper h) {
        var cfg=new dev.bwr.core.CoreConfig();cfg.controlRodCount=4;cfg.assemblyCount=24;
        var loading=new CoreLoading(5);for(int i=0;i<25;i++)if(i!=12)loading.load(i,new FuelAssembly(FuelType.LEU));
        var target=new CoreInsert(CoreInsert.Kind.SILICON_TARGET,0);loading.loadInsert(12,target);
        var core=new dev.bwr.core.ReactorCore(cfg,loading);core.initialiseAtTotalPowerFraction(.1);
        for(int i=0;i<20;i++)core.step();
        h.assertTrue(target.exposureSeconds()>0&&target.exposureSeconds()<1,"Target does not integrate local fission flux");
        h.assertTrue(loading.assemblyThermalMW(core.getThermalPowerMW())[12]==0,"Target counted as fuel power");h.succeed();
    }
}
