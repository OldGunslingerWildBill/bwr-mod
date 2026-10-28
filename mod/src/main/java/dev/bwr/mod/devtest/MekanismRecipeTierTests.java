package dev.bwr.mod.devtest;

import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * The Mekanism tier of the crafting recipes (MEKANISM-RECIPES.md), through the loaded {@link RecipeManager}.
 *
 * <p>Every recipe that has a Mekanism variant is a pair of files: {@code <name>.json}, the vanilla recipe, loaded only without
 * Mekanism, and {@code <name>_mekanism.json}, loaded only with it. So in each install exactly one recipe makes the item. With
 * Mekanism, every live crafting recipe of a bwr item names a Mekanism item (through an item or a tag) unless it is on the
 * exemption list below, and each Mekanism recipe, laid out in the grid and mirrored, is the recipe the manager hands back (it
 * collides with nothing of vanilla's, Mekanism's or ours). Without Mekanism the vanilla recipes are the live ones. The turbine oil
 * route is checked end to end: the reaction chamber recipe takes substrate, liquid ethene and hydrogen and makes cans, four cans
 * around a steel ingot craft a drum, and the Mekanism recipes it is fed by make its substrate and its ethene (which together
 * balance, so the chain needs nothing from outside).
 *
 * <p>The suite runs in both configurations (the {@code -PbwrNoMekanism} gate runs it without Mekanism). It starts no fixture. It has
 * its own namespace, {@code bwrmektier} ({@code -PbwrGameTestNamespaces=bwrmektier} runs just these).
 */
@GameTestHolder("bwrmektier")
@PrefixGameTestTemplate(false)
public final class MekanismRecipeTierTests {
    private MekanismRecipeTierTests() {
    }

    // BEGIN GENERATED EXEMPT LIST (tools-generate-mekanism-recipes.py writes this block; the reasons are in that tool and in MEKANISM-RECIPES.md)
    /** Crafting recipes (by id path) that have no Mekanism variant, or whose variant is held back. */
    private static final Set<String> EXEMPT = Set.of(
            // a one-to-one conversion between nugget, ingot and block (or smelting an ore): it makes nothing new, so it costs nothing and has no Mekanism form
            "actinium_227_block", "actinium_227_ingot_from_block", "americium_241_block", "americium_241_ingot_from_block",
            "americium_241_ingot_from_capsule", "caesium_137_block", "caesium_137_ingot_from_block",
            "caesium_137_ingot_from_capsule", "cobalt_60_block", "cobalt_60_ingot_from_block", "cobalt_60_ingot_from_sample",
            "lead_block", "lead_ingot", "lead_ingot_from_block", "lead_nugget", "plutonium_238_block",
            "plutonium_238_ingot_from_block", "plutonium_ingot", "plutonium_nugget", "polonium_210_block",
            "polonium_210_ingot_from_block", "raw_thorium_block", "raw_thorium_from_block", "raw_uranium_block",
            "raw_uranium_from_block", "strontium_90_block", "strontium_90_ingot_from_block", "strontium_90_ingot_from_capsule",
            "thorium_block", "thorium_ingot", "thorium_ingot_from_blasting", "thorium_ingot_from_block",
            "thorium_ingot_from_smelting", "thorium_nugget", "thulium_170_block", "thulium_170_ingot_from_block",
            "uranium_block", "uranium_ingot_from_blasting", "uranium_ingot_from_block", "uranium_ingot_from_smelting",
            // an isotope stand-in, target or source: priced by the isotope and by the irradiation casing it is made with, not by a machine
            "americium_beryllium_source", "americium_beryllium_source_from_capsule", "americium_beryllium_source_from_radium",
            "antimony_beryllium_source", "bismuth_target", "borate_charge", "boron_absorber", "californium_source",
            "cobalt_charge", "cobalt_target", "doped_silicon_redstone", "ferrocerium_flint_and_steel", "hafnium_absorber",
            "lithium_charge", "neptunium_target", "radium_target", "silicon_target", "thorium_target", "thulium_target",
            "tritium_sample_light", "tritium_target", "uranium_target",
            // HELD BACK, an existing test pins the vanilla recipe: RealScaleTests.craftedStacksStartAtRealSize loads the recipe of every panel component type by its
            "annunciator", "bar_gauge", "bar_graph", "control_switch", "dial_gauge", "edgewise_gauge_horizontal",
            "edgewise_gauge_vertical", "key_switch", "lamp", "push_turn_switch", "pushbutton", "rotary_selector", "rps_trip",
            "setpoint_knob", "seven_segment_display", "sixteen_segment_display", "thumbwheel", "toggle_switch",
            // made only of parts that carry the Mekanism price (panel bodies, vessels, pool walls, pipes) plus dye, glass or a hopper; a price of its own would coun
            "control_panel_back", "control_panel_benchboard", "control_panel_corner_bench_inside",
            "control_panel_corner_vertical_inside", "control_panel_end_cap_bench", "control_panel_end_cap_console",
            "control_panel_end_cap_flat", "control_panel_header_strip", "jet_pump", "mimic_band_black", "mimic_band_cream",
            "recirculation_inlet", "recirculation_outlet", "rpv_steam_outlet", "spent_fuel_pool_cooling_in",
            "spent_fuel_pool_cooling_out", "spent_fuel_pool_makeup_port", "suppression_pool_drain", "suppression_pool_return",
            "suppression_pool_steam_inlet", "suppression_pool_suction", "turbine_steam_outlet",
            // HELD BACK, an existing test pins the vanilla recipe: ControlRoomRecipeTests (also ControlDeskTests and RoomFurnitureTests) loads bwr:<name> by id, ass
            "control_panel_console", "control_panel_corner_bench_chamfer", "control_panel_corner_bench_outside",
            "control_panel_corner_vertical_chamfer", "control_panel_corner_vertical_outside", "control_panel_desk",
            "control_panel_desk_wide", "control_panel_duplex", "control_panel_end_cap_round", "control_panel_header_canopy",
            "control_panel_header_vent", "control_panel_plinth_apron", "control_panel_plinth_base", "control_panel_plinth_kick",
            "control_panel_vertical", "control_stand", "operator_desk", "operator_desk_corner", "operator_desk_round",
            "relay_cabinet", "relay_cabinet_plain", "supervisor_desk", "wall_display", "wall_display_wide",
            // scrap, not a build: it returns iron, and a steel return would turn the repair loop into a steel mine
            "damaged_lp_blade_section_scrap", "wrecked_lp_turbine_hood_scrap",
            // already built on Mekanism steel, uranium or netherite (a pair of recipes exists, or the recipe has no vanilla form to replace)
            "depleted_uranium_assembly", "fuel_assembly", "fuel_fabricator", "heavily_reinforced_shield_plug_concrete",
            "shield_plug_concrete", "ultra_reinforced_shield_plug_concrete",
            // a book and a nameplate; the nameplate carries the steel
            "guide_book",
            // a Mekanism chemical recipe, not a crafting recipe
            "tritium_sample_oxidizing");
    // END GENERATED EXEMPT LIST

    private static ResourceLocation bwr(String path) {
        return ResourceLocation.fromNamespaceAndPath("bwr", path);
    }

    private static boolean mekanism() {
        return ModList.get().isLoaded("mekanism");
    }

    /** The names with a Mekanism variant file (from the resource packs, whatever the conditions say). */
    private static Set<String> twinNames(GameTestHelper h) {
        Set<String> names = new TreeSet<>();
        var found = h.getLevel().getServer().getResourceManager().listResources("recipe",
                id -> id.getNamespace().equals("bwr") && id.getPath().endsWith("_mekanism.json") && !id.getPath().endsWith("_no_mekanism.json"));
        for (ResourceLocation id : found.keySet()) {
            String path = id.getPath();
            names.add(path.substring("recipe/".length(), path.length() - "_mekanism.json".length()));
        }
        return names;
    }

    private static List<RecipeHolder<CraftingRecipe>> bwrCrafting(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        List<RecipeHolder<CraftingRecipe>> out = new ArrayList<>();
        for (RecipeHolder<CraftingRecipe> holder : level.getServer().getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING)) {
            if (holder.id().getNamespace().equals("bwr")) {
                out.add(holder);
            }
        }
        return out;
    }

    private static ResourceLocation resultOf(RecipeHolder<?> holder, RegistryAccess access) {
        return BuiltInRegistries.ITEM.getKey(holder.value().getResultItem(access).getItem());
    }

    private static boolean namesMekanism(Recipe<?> recipe) {
        for (Ingredient ingredient : recipe.getIngredients()) {
            for (ItemStack stack : ingredient.getItems()) {
                if (BuiltInRegistries.ITEM.getKey(stack.getItem()).getNamespace().equals("mekanism")) {
                    return true;
                }
            }
        }
        return false;
    }

    @GameTest(template = "empty", templateNamespace = "bwrmektier")
    public static void theExemptionListIsHonest(GameTestHelper h) {
        Set<String> twins = twinNames(h);
        h.assertTrue(twins.size() >= 100, "only " + twins.size() + " Mekanism variants are in the resources");
        for (String name : EXEMPT) {
            h.assertTrue(!twins.contains(name), name + " is exempt from the Mekanism tier but has a " + name + "_mekanism.json");
        }
        h.succeed();
    }

    /**
     * With Mekanism: each variant is live and is the item's only crafting recipe, its vanilla twin is not loaded, and every live
     * crafting recipe of a bwr item names a Mekanism item unless it is exempt. Without Mekanism: the reverse.
     */
    @GameTest(template = "empty", templateNamespace = "bwrmektier", timeoutTicks = 100)
    public static void exactlyOneRecipeIsLiveForEveryTieredItemAndTheTierNamesMekanism(GameTestHelper h) {
        boolean mek = mekanism();
        ServerLevel level = h.getLevel();
        RecipeManager manager = level.getServer().getRecipeManager();
        Map<ResourceLocation, List<ResourceLocation>> makers = new HashMap<>();
        for (RecipeHolder<CraftingRecipe> holder : bwrCrafting(h)) {
            makers.computeIfAbsent(resultOf(holder, level.registryAccess()), k -> new ArrayList<>()).add(holder.id());
        }
        List<String> problems = new ArrayList<>();
        for (String name : twinNames(h)) {
            Optional<RecipeHolder<?>> tier = manager.byKey(bwr(name + "_mekanism"));
            Optional<RecipeHolder<?>> plain = manager.byKey(bwr(name));
            if (tier.isPresent() != mek) {
                problems.add(name + "_mekanism is " + (tier.isPresent() ? "loaded without Mekanism" : "missing with Mekanism"));
            }
            if (plain.isPresent() == mek) {
                problems.add(name + " (the vanilla recipe) is " + (plain.isPresent() ? "loaded with Mekanism" : "missing without Mekanism"));
            }
            Optional<RecipeHolder<?>> live = mek ? tier : plain;
            if (live.isPresent()) {
                List<ResourceLocation> same = makers.getOrDefault(resultOf(live.get(), level.registryAccess()), List.of());
                if (same.size() != 1) {
                    problems.add(name + ": " + same.size() + " crafting recipes make its item " + same);
                }
            }
        }
        if (mek) {
            for (RecipeHolder<CraftingRecipe> holder : bwrCrafting(h)) {
                String path = holder.id().getPath();
                if (!EXEMPT.contains(path) && !namesMekanism(holder.value())) {
                    problems.add(path + " is live with Mekanism, is not exempt and names no Mekanism item or tag");
                }
            }
        }
        h.assertTrue(problems.isEmpty(), problems.size() + " problem(s): " + problems.subList(0, Math.min(8, problems.size())));
        h.succeed();
    }

    // ------------------------------------------------------------------------------------------------------------

    private record Grid(int width, int height, List<ItemStack> cells) {
        Grid mirrored() {
            List<ItemStack> out = new ArrayList<>();
            for (int row = 0; row < height; row++) {
                for (int col = width - 1; col >= 0; col--) {
                    out.add(cells.get(row * width + col).copy());
                }
            }
            return new Grid(width, height, out);
        }

        CraftingInput input() {
            return CraftingInput.of(width, height, cells);
        }
    }

    private static ItemStack first(Ingredient ingredient) {
        ItemStack[] items = ingredient.getItems();
        return items.length == 0 ? ItemStack.EMPTY : items[0].copy();
    }

    private static Grid gridOf(Recipe<?> recipe) {
        List<ItemStack> cells = new ArrayList<>();
        if (recipe instanceof ShapedRecipe shaped) {
            for (Ingredient ingredient : shaped.getIngredients()) {
                cells.add(ingredient.isEmpty() ? ItemStack.EMPTY : first(ingredient));
            }
            return new Grid(shaped.getWidth(), shaped.getHeight(), cells);
        }
        if (recipe instanceof ShapelessRecipe shapeless) {
            for (Ingredient ingredient : shapeless.getIngredients()) {
                cells.add(first(ingredient));
            }
            return new Grid(cells.size(), 1, cells);
        }
        return null;
    }

    /** Each Mekanism recipe, laid out and mirrored in the grid, is what the manager makes of it: nothing shadows it. */
    @GameTest(template = "empty", templateNamespace = "bwrmektier", timeoutTicks = 200)
    public static void everyMekanismRecipeCraftsFromItsOwnIngredientsAndIsNotShadowed(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        RecipeManager manager = level.getServer().getRecipeManager();
        if (!mekanism()) {
            for (String name : twinNames(h)) {
                h.assertTrue(manager.byKey(bwr(name + "_mekanism")).isEmpty(), name + "_mekanism is loaded without Mekanism");
            }
            h.succeed();
            return;
        }
        List<String> problems = new ArrayList<>();
        int checked = 0;
        for (String name : twinNames(h)) {
            Optional<RecipeHolder<?>> holder = manager.byKey(bwr(name + "_mekanism"));
            if (holder.isEmpty()) {
                problems.add(name + "_mekanism is not loaded");
                continue;
            }
            Grid grid = gridOf(holder.get().value());
            if (grid == null) {
                problems.add(name + "_mekanism is not a shaped or shapeless recipe");
                continue;
            }
            boolean usable = true;
            for (ItemStack cell : grid.cells()) {
                if (cell.isEmpty() && !(holder.get().value() instanceof ShapedRecipe)) {
                    usable = false;
                }
            }
            if (!usable) {
                problems.add(name + "_mekanism has an ingredient no item matches (an empty tag?)");
                continue;
            }
            for (boolean mirror : new boolean[] {false, true}) {
                Grid laid = mirror ? grid.mirrored() : grid;
                Optional<RecipeHolder<CraftingRecipe>> found = manager.getRecipeFor(RecipeType.CRAFTING, laid.input(), level);
                if (found.isEmpty()) {
                    problems.add(name + (mirror ? " (mirrored)" : "") + ": its own ingredients craft nothing");
                } else if (!found.get().id().equals(bwr(name + "_mekanism"))) {
                    problems.add(name + (mirror ? " (mirrored)" : "") + ": its ingredients craft " + found.get().id() + " instead");
                } else {
                    ItemStack made = found.get().value().assemble(laid.input(), level.registryAccess());
                    Item expected = holder.get().value().getResultItem(level.registryAccess()).getItem();
                    if (made.getItem() != expected || made.getCount() < 1) {
                        problems.add(name + ": yields " + made);
                    }
                }
            }
            checked++;
        }
        h.assertTrue(problems.isEmpty(), problems.size() + " problem(s) in " + checked + " recipes: " + problems.subList(0, Math.min(8, problems.size())));
        h.succeed();
    }

    // ------------------------------------------------------------------------------------------------------------
    // The turbine oil route
    // ------------------------------------------------------------------------------------------------------------

    /** Without Mekanism the old drum recipe is the only route and the reaction chamber recipe is not loaded; with it, the whole chain. */
    @GameTest(template = "empty", templateNamespace = "bwrmektier", timeoutTicks = 100)
    public static void turbineOilRouteProducesDrums(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        RecipeManager manager = level.getServer().getRecipeManager();
        ItemStack coal = new ItemStack(net.minecraft.world.item.Items.COAL);
        ItemStack iron = new ItemStack(net.minecraft.world.item.Items.IRON_INGOT);
        if (!mekanism()) {
            h.assertTrue(manager.byKey(bwr("turbine_oil_can_from_ethene")).isEmpty(), "the reaction chamber recipe is loaded without Mekanism");
            h.assertTrue(manager.byKey(bwr("turbine_oil_drum_mekanism")).isEmpty(), "the Mekanism drum recipe is loaded without Mekanism");
            // the old recipe: coal around an iron ingot, as it always was
            List<ItemStack> plus = List.of(ItemStack.EMPTY, coal, ItemStack.EMPTY, coal, iron, coal, ItemStack.EMPTY, coal, ItemStack.EMPTY);
            Optional<RecipeHolder<CraftingRecipe>> found = manager.getRecipeFor(RecipeType.CRAFTING, CraftingInput.of(3, 3, plus), level);
            h.assertTrue(found.isPresent() && found.get().id().equals(bwr("turbine_oil_drum")), "four coal around an iron ingot no longer craft a drum");
            h.succeed();
            return;
        }
        MekanismOilRouteCheck.run(h);
    }
}
