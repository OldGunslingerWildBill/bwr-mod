package dev.bwr.mod.devtest;

import dev.bwr.mod.fuel.FabricatorItemView;
import dev.bwr.mod.fuel.FuelAssemblies;
import dev.bwr.mod.fuel.FuelFabricatorBlockEntity;
import dev.bwr.mod.fuel.FuelItemRoute;
import dev.bwr.mod.fuel.MekanismFuelFeed;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.registry.BwrItems;
import mekanism.api.Action;
import mekanism.api.MekanismAPI;
import mekanism.api.chemical.ChemicalStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.capabilities.Capabilities;

/**
 * The parts of the fabricator tests that need Mekanism itself: its uranium and steel items through the tags, and its
 * chemical tanks through a save that was written before the item route existed. Only ever loaded when Mekanism is, so
 * a run without it never resolves a Mekanism type ({@link FuelFabricatorItemRouteTests#mekanismTagsAndChemicalRoute}).
 */
final class FuelFabricatorMekanismChecks {

    private FuelFabricatorMekanismChecks() {
    }

    private static Item item(String id) {
        Item item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(id));
        if (item == net.minecraft.world.item.Items.AIR) {
            throw new AssertionError("Mekanism has no item " + id);
        }
        return item;
    }

    private static ChemicalStack chemical(String path, long mb) {
        var holder = MekanismAPI.CHEMICAL_REGISTRY.getHolder(ResourceLocation.fromNamespaceAndPath("mekanism", path)).orElseThrow();
        return new ChemicalStack(holder, mb);
    }

    private static void tick(ServerLevel l, BlockPos p, FuelFabricatorBlockEntity be) {
        be.energy().receiveEnergy(10_000, false);
        FuelFabricatorBlockEntity.serverTick(l, p, l.getBlockState(p), be);
    }

    static void run(GameTestHelper h) {
        ServerLevel l = h.getLevel();

        // 1. Mekanism's items are uranium and steel by tag, with no help from the tests
        Item ingot = item("mekanism:ingot_uranium");
        Item raw = item("mekanism:raw_uranium");
        Item cake = item("mekanism:yellow_cake_uranium");
        Item steel = item("mekanism:ingot_steel");
        h.assertTrue(FuelItemRoute.uraniumUnits(new ItemStack(ingot)) == 2, "a Mekanism uranium ingot is not one ingot");
        h.assertTrue(FuelItemRoute.uraniumUnits(new ItemStack(raw)) == 2, "raw Mekanism uranium is not one ingot");
        h.assertTrue(FuelItemRoute.uraniumUnits(new ItemStack(cake)) == 1, "yellow cake is not half an ingot");
        h.assertTrue(FuelItemRoute.isSteel(new ItemStack(steel)) && !FuelItemRoute.isSteel(new ItemStack(ingot)), "Mekanism steel is not steel");
        h.assertTrue(FuelItemRoute.uraniumUnits(new ItemStack(steel)) == 0 && FuelItemRoute.uraniumUnits(new ItemStack(BwrItems.DEPLETED_URANIUM.get())) == 0,
                "steel or depleted uranium counts as uranium");

        // 2. the item route with the real items: 3.5 % takes 29 ingots and 4 steel
        BlockPos p = new BlockPos(600, 210, 720);
        l.getChunk(p);
        l.removeBlock(p, false);
        l.setBlock(p, BwrBlocks.FUEL_FABRICATOR.get().defaultBlockState(), 2);
        var be = (FuelFabricatorBlockEntity) l.getBlockEntity(p);
        var up = l.getCapability(Capabilities.ItemHandler.BLOCK, p, Direction.UP);
        up.insertItem(FabricatorItemView.STEEL, new ItemStack(steel, 4), false);
        int remaining = 29;
        for (int i = 0; i < 2000 && be.output().getStackInSlot(0).isEmpty(); i++) {
            if (remaining > 0) {
                int offer = Math.min(64, remaining);
                remaining -= offer - up.insertItem(FabricatorItemView.URANIUM, new ItemStack(ingot, offer), false).getCount();
            }
            tick(l, p, be);
        }
        var assembly = be.output().getStackInSlot(0);
        h.assertTrue(assembly.is(BwrItems.FUEL_ASSEMBLY.get()) && FuelAssemblies.dataOf(assembly).fuelTypeName().equals("leu"),
                "no 3.5 % assembly from 29 Mekanism ingots and 4 steel");
        h.assertTrue(be.tailsHeld() == 24, "depleted uranium: " + be.tailsHeld());
        be.removalDrops();
        l.removeBlock(p, false);

        // 3. a Mekanism fabricator saved before the item route: chemical tanks full, none of the new keys
        l.setBlock(p, BwrBlocks.FUEL_FABRICATOR.get().defaultBlockState(), 2);
        be = (FuelFabricatorBlockEntity) l.getBlockEntity(p);
        var feed = (MekanismFuelFeed) be.feed();
        h.assertTrue(feed.insertChemical(0, chemical("uranium_oxide", 9000), Action.EXECUTE).isEmpty(), "the matrix tank refused uranium oxide");
        h.assertTrue(feed.insertChemical(1, chemical("fissile_fuel", 500), Action.EXECUTE).isEmpty(), "the fissile tank refused fissile fuel");
        CompoundTag saved = be.saveWithFullMetadata(l.registryAccess());
        for (String key : new String[] {"Target", "UraniumUnits", "TailsPending", "WorkTicks", "UraniumIn", "SteelIn", "TailsOut"}) {
            saved.remove(key); // what an older build wrote has none of them
        }
        var old = new FuelFabricatorBlockEntity(p, l.getBlockState(p));
        old.loadWithComponents(saved, l.registryAccess());
        var oldFeed = (MekanismFuelFeed) old.feed();
        h.assertTrue(oldFeed.getChemicalInTank(0).getAmount() == 9000 && oldFeed.getChemicalInTank(1).getAmount() == 500,
                "the chemical tanks changed on load: " + oldFeed.getChemicalInTank(0).getAmount() + " / " + oldFeed.getChemicalInTank(1).getAmount());
        h.assertTrue(old.saveWithFullMetadata(l.registryAccess()).getCompound("Feed").equals(saved.getCompound("Feed")), "the saved feed differs after a reload");

        // 4. and the chemical route still makes what it always made: 9000 mB (180 kg) at the ratio fed
        l.removeBlock(p, false);
        l.setBlock(p, BwrBlocks.FUEL_FABRICATOR.get().defaultBlockState(), 2);
        be = (FuelFabricatorBlockEntity) l.getBlockEntity(p);
        feed = (MekanismFuelFeed) be.feed();
        feed.insertChemical(0, chemical("uranium_oxide", 8550), Action.EXECUTE);
        feed.insertChemical(1, chemical("fissile_fuel", 450), Action.EXECUTE);
        for (int i = 0; i < 2000 && be.output().getStackInSlot(0).isEmpty(); i++) {
            tick(l, p, be);
        }
        var chem = be.output().getStackInSlot(0);
        h.assertTrue(chem.is(BwrItems.FUEL_ASSEMBLY.get()), "the chemical route made nothing");
        var data = FuelAssemblies.dataOf(chem);
        // 450 mB fissile (1.0) and 8550 mB oxide (0.0072) in 9000 mB: (450 + 8550 x 0.0072) / 9000 = 5.7 %, so 4.95 % uranium (the highest grade at or below it)
        double expected = (450 + 8550 * 0.0072) / 9000.0;
        h.assertTrue(Math.abs(data.enrichmentWeightFraction() - expected) < 2e-4 && data.fuelTypeName().equals("uranium_495"),
                "chemical route: " + data.fuelTypeName() + " at " + data.enrichmentWeightFraction() + ", wanted " + expected);
        h.assertTrue(be.tailsHeld() == 0 && be.uraniumHeldUnits() == 0, "the chemical route made depleted uranium");
        be.removalDrops();
        l.removeBlock(p, false);
        System.out.println("FUEL FABRICATOR MEKANISM: real items, old-format tanks and the chemical route all pass");
        h.succeed();
    }
}
