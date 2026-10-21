package dev.bwr.mod.devtest;

import dev.bwr.core.CoreConfig;
import dev.bwr.core.ReactorCore;
import dev.bwr.mod.fuel.FuelAssemblies;
import dev.bwr.mod.fuel.FuelAssemblyData;
import dev.bwr.mod.fuel.FuelTypes;
import dev.bwr.mod.registry.BwrItems;
import net.minecraft.core.NonNullList;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Depleted uranium as a fuel: the fuel type is loaded, the recipe exists and makes it, and a core runs with it. */
@GameTestHolder("bwr")
@PrefixGameTestTemplate(false)
public final class DepletedUraniumTests {

    private static final ResourceLocation RECIPE = ResourceLocation.fromNamespaceAndPath("bwr", "depleted_uranium_assembly");

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void depletedUraniumIsAFuelTypeLikeTheOthers(GameTestHelper h) {
        var type = FuelTypes.byName("depleted_uranium");
        h.assertTrue(type != null, "the datapack did not load depleted_uranium");
        h.assertTrue(type.conversionGainKInf() > 0.0 && type.isNetBreeder(), "conversion gain is not positive");
        h.assertTrue(type.nominalEnrichmentWeightFraction() == 0.0025, "assay is not the tails assay");
        h.assertTrue(FuelTypes.catalogue().contains(type), "not offered in the catalogue (creative tab)");
        h.assertTrue(FuelTypes.byNameOrFallback("depleted_uranium") == type, "resolves to something else");
        var fresh = FuelAssemblies.toCore(FuelAssemblyData.fresh(type));
        h.assertTrue(fresh.kInf() < 0.5, "fresh depleted uranium is not a net absorber: " + fresh.kInf());
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void theRecipeMakesADepletedUraniumAssembly(GameTestHelper h) {
        var holder = h.getLevel().getServer().getRecipeManager().byKey(RECIPE);
        h.assertTrue(holder.isPresent(), "no recipe bwr:depleted_uranium_assembly");
        var recipe = (CraftingRecipe) holder.get().value();
        ItemStack result = recipe.getResultItem(h.getLevel().registryAccess());
        h.assertTrue(result.is(BwrItems.FUEL_ASSEMBLY.get()), "the recipe does not make a fuel assembly");
        h.assertTrue(FuelAssemblies.dataOf(result).equals(FuelAssemblyData.fresh(FuelTypes.byName("depleted_uranium"))),
                "the assembly is not a fresh depleted uranium one: " + FuelAssemblies.dataOf(result));
        // the ingredients: depleted uranium (always available) round steel (the c:ingots/steel tag, filled by Mekanism or any other mod)
        ItemStack du = new ItemStack(BwrItems.DEPLETED_URANIUM.get());
        h.assertTrue(recipe.getIngredients().stream().anyMatch(i -> i.test(du)), "depleted uranium is not an ingredient");
        h.assertTrue(recipe.getIngredients().stream().noneMatch(i -> i.test(new ItemStack(BwrItems.COBALT_SAMPLE.get()))), "an unrelated item is");
        if (ModList.get().isLoaded("mekanism")) {
            var steel = new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.get(ResourceLocation.parse("mekanism:ingot_steel")));
            NonNullList<ItemStack> grid = NonNullList.withSize(9, ItemStack.EMPTY);
            int[] edges = {1, 3, 5, 7};
            int[] corners = {0, 2, 6, 8};
            for (int i : edges) {
                grid.set(i, du.copy());
            }
            for (int i : corners) {
                grid.set(i, steel.copy());
            }
            h.assertTrue(recipe.matches(CraftingInput.of(3, 3, grid), h.getLevel()), "4 depleted uranium round 4 Mekanism steel did not craft it");
            grid.set(1, ItemStack.EMPTY);
            h.assertTrue(!recipe.matches(CraftingInput.of(3, 3, grid), h.getLevel()), "3 depleted uranium crafted it");
        }
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void aDepletedUraniumAssemblyItemLoadsIntoACoreAndRuns(GameTestHelper h) {
        var type = FuelTypes.byName("depleted_uranium");
        ItemStack stack = dev.bwr.mod.fuel.FuelAssemblyItem.stackOf(BwrItems.FUEL_ASSEMBLY.get(), FuelAssemblyData.fresh(type));
        ReactorCore core = new ReactorCore(new CoreConfig());
        var loading = core.getCoreLoading();
        double before = loading.kEffAllRodsOut();
        for (int i = 0; i < 6; i++) {
            loading.load(loading.positionCount() - 1 - i, FuelAssemblies.load(stack));
        }
        for (int i = 0; i < 100; i++) {
            core.step();
        }
        h.assertTrue(Double.isFinite(core.getNeutronPowerFraction()) && Double.isFinite(core.getThermalPowerMW()), "the core did not run");
        h.assertTrue(loading.kEffAllRodsOut() < before, "the blanket did not lower k_eff");
        // burned in the core, the exposure written back to the item keeps the type
        var burned = FuelAssemblies.dataOf(stack).withExposure(5000.0, 1.0);
        FuelAssemblies.setData(stack, burned);
        h.assertTrue(FuelAssemblies.toCore(FuelAssemblies.dataOf(stack)).kInf() > FuelAssemblies.toCore(FuelAssemblyData.fresh(type)).kInf(),
                "a burned depleted uranium item is not richer than a fresh one");
        h.succeed();
    }
}
