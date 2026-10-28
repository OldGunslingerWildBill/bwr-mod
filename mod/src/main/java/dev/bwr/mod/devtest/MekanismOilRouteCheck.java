package dev.bwr.mod.devtest;

import dev.bwr.mod.registry.BwrItems;
import mekanism.api.MekanismAPI;
import mekanism.api.chemical.ChemicalStack;
import mekanism.api.recipes.PressurizedReactionRecipe;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.neoforged.neoforge.fluids.FluidStack;

import java.util.List;
import java.util.Optional;

/**
 * Reached only after the optional-mod check of {@link MekanismRecipeTierTests}: the turbine oil route as the recipe manager holds it
 * (MEKANISM-RECIPES.md). A Pressurized Reaction Chamber turns substrate, liquid ethene and hydrogen into four oil cans, four cans
 * around a steel ingot craft a drum, and the Mekanism recipes this is fed by (the reaction chamber's own ethene and substrate
 * recipe, the rotary condensentrator's ethene, the electrolytic separator's hydrogen) are loaded and balance on substrate.
 */
final class MekanismOilRouteCheck {
    private MekanismOilRouteCheck() {
    }

    private static ResourceLocation id(String namespace, String path) {
        return ResourceLocation.fromNamespaceAndPath(namespace, path);
    }

    private static net.minecraft.core.Holder<mekanism.api.chemical.Chemical> chemical(String path) {
        return MekanismAPI.CHEMICAL_REGISTRY.wrapAsHolder(MekanismAPI.CHEMICAL_REGISTRY.get(id("mekanism", path)));
    }

    static void run(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        RecipeManager manager = level.getServer().getRecipeManager();

        // --- the reaction chamber recipe
        Optional<RecipeHolder<?>> holder = manager.byKey(id("bwr", "turbine_oil_can_from_ethene"));
        h.assertTrue(holder.isPresent(), "the oil can recipe is not loaded (an unknown id or a bad field makes Mekanism drop it)");
        h.assertTrue(holder.get().value() instanceof PressurizedReactionRecipe, "the oil can recipe is not a Pressurized Reaction Chamber recipe");
        PressurizedReactionRecipe oil = (PressurizedReactionRecipe) holder.get().value();
        h.assertTrue(!oil.isIncomplete(), "the oil can recipe has an input that matches nothing");

        ItemStack substrate = new ItemStack(BuiltInRegistries.ITEM.get(id("mekanism", "substrate")));
        FluidStack ethene = new FluidStack(BuiltInRegistries.FLUID.get(id("mekanism", "ethene")), 200);
        ChemicalStack hydrogen = new ChemicalStack(chemical("hydrogen"), 100);
        h.assertTrue(!substrate.isEmpty() && !ethene.isEmpty() && !hydrogen.isEmpty(), "substrate, ethene or hydrogen is not registered");
        h.assertTrue(oil.test(substrate, ethene, hydrogen), "substrate, 200 mB of ethene and 100 mB of hydrogen do not match the oil can recipe");
        h.assertTrue(oil.getInputSolid().getNeededAmount(substrate) == 1, "the recipe does not take one substrate");
        h.assertTrue(oil.getInputFluid().getNeededAmount(ethene) == 200, "the recipe does not take 200 mB of ethene");
        h.assertTrue(oil.getInputChemical().getNeededAmount(hydrogen) == 100, "the recipe does not take 100 mB of hydrogen");
        h.assertTrue(!oil.test(new ItemStack(BuiltInRegistries.ITEM.get(id("mekanism", "hdpe_pellet"))), ethene, hydrogen),
                "a pellet stands in for the substrate");
        h.assertTrue(!oil.test(substrate, new FluidStack(net.minecraft.world.level.material.Fluids.WATER, 200), hydrogen),
                "water stands in for the ethene");
        h.assertTrue(!oil.test(substrate, ethene, new ChemicalStack(chemical("oxygen"), 100)),
                "oxygen stands in for the hydrogen");
        ItemStack cans = oil.getOutput(substrate, ethene, hydrogen).item();
        h.assertTrue(cans.is(BwrItems.TURBINE_OIL_CAN.get()) && cans.getCount() == 4, "the recipe makes " + cans + " instead of four oil cans");
        h.assertTrue(oil.getDuration() > 0 && oil.getEnergyRequired() > 0, "the recipe is free or instant");

        // --- four cans around a steel ingot fill a drum, and the old recipe is gone
        ItemStack can = new ItemStack(BwrItems.TURBINE_OIL_CAN.get());
        ItemStack steel = new ItemStack(BuiltInRegistries.ITEM.get(id("mekanism", "ingot_steel")));
        ItemStack empty = ItemStack.EMPTY;
        List<ItemStack> plus = List.of(empty, can, empty, can, steel, can, empty, can, empty);
        Optional<RecipeHolder<CraftingRecipe>> drum = manager.getRecipeFor(RecipeType.CRAFTING, CraftingInput.of(3, 3, plus), level);
        h.assertTrue(drum.isPresent() && drum.get().id().equals(id("bwr", "turbine_oil_drum_mekanism")),
                "four oil cans around a steel ingot do not craft a drum: " + drum.map(RecipeHolder::id));
        ItemStack made = drum.get().value().assemble(CraftingInput.of(3, 3, plus), level.registryAccess());
        h.assertTrue(made.is(BwrItems.TURBINE_OIL_DRUM.get()) && made.getCount() == 1, "the drum recipe yields " + made);
        List<ItemStack> threeCans = List.of(empty, can, empty, can, steel, empty, empty, can, empty);
        h.assertTrue(manager.getRecipeFor(RecipeType.CRAFTING, CraftingInput.of(3, 3, threeCans), level).isEmpty(), "three cans craft something");
        ItemStack iron = new ItemStack(net.minecraft.world.item.Items.IRON_INGOT);
        List<ItemStack> ironDrum = List.of(empty, can, empty, can, iron, can, empty, can, empty);
        h.assertTrue(manager.getRecipeFor(RecipeType.CRAFTING, CraftingInput.of(3, 3, ironDrum), level).isEmpty(), "an iron ingot stands in for the steel");
        ItemStack coal = new ItemStack(net.minecraft.world.item.Items.COAL);
        List<ItemStack> oldDrum = List.of(empty, coal, empty, coal, iron, coal, empty, coal, empty);
        Optional<RecipeHolder<CraftingRecipe>> old = manager.getRecipeFor(RecipeType.CRAFTING, CraftingInput.of(3, 3, oldDrum), level);
        h.assertTrue(old.isEmpty() || !old.get().value().assemble(CraftingInput.of(3, 3, oldDrum), level.registryAccess()).is(BwrItems.TURBINE_OIL_DRUM.get()),
                "four coal around an iron ingot still craft a drum with Mekanism installed");
        h.assertTrue(manager.byKey(id("bwr", "turbine_oil_drum")).isEmpty(), "the vanilla drum recipe is loaded with Mekanism");

        // --- the chain behind it: what makes substrate, ethene as a liquid, and hydrogen
        Optional<RecipeHolder<?>> upstream = manager.byKey(id("mekanism", "reaction/substrate/water_hydrogen"));
        h.assertTrue(upstream.isPresent() && upstream.get().value() instanceof PressurizedReactionRecipe,
                "Mekanism's own substrate and ethene recipe (reaction/substrate/water_hydrogen) is not loaded");
        PressurizedReactionRecipe makesEthene = (PressurizedReactionRecipe) upstream.get().value();
        var made1 = makesEthene.getOutputDefinition().get(0);
        h.assertTrue(made1.item().is(substrate.getItem()) && made1.item().getCount() >= 1 && made1.chemical().getAmount() > 0
                        && BuiltInRegistries.FLUID.getKey(ethene.getFluid()).getPath().equals(MekanismAPI.CHEMICAL_REGISTRY.getKey(made1.chemical().getChemical()).getPath()),
                "the substrate and ethene recipe does not make substrate and ethene: " + made1);
        // one drum is four runs of the oil recipe; making its ethene makes its substrate too, so nothing has to come from outside
        long etheneNeeded = 4L * ethene.getAmount();
        long runs = (etheneNeeded + made1.chemical().getAmount() - 1) / made1.chemical().getAmount();
        long substrateMade = runs * made1.item().getCount();
        h.assertTrue(substrateMade >= 4L, "a drum's ethene (" + etheneNeeded + " mB, " + runs + " runs) makes only " + substrateMade
                + " substrate, and a drum needs 4");
        h.assertTrue(manager.byKey(id("mekanism", "rotary/ethene")).isPresent(), "the rotary condensentrator's ethene recipe is not loaded");
        h.assertTrue(manager.byKey(id("mekanism", "separator/water")).isPresent(), "the electrolytic separator's water recipe is not loaded");
        h.succeed();
    }
}
