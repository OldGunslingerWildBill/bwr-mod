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
import java.util.List;
import java.util.Optional;

/**
 * The control-room panel and furniture recipes, crafted through the loaded {@link RecipeManager}: every desk (and the
 * rest of the control-room set) is laid out in the crafting grid exactly as its recipe says (and mirrored, which a
 * shaped recipe also accepts) and the manager must hand back that recipe, which fails when another recipe, ours or
 * vanilla's, shadows it. The desks must also cost proportionally more as they grow, counted in iron through every
 * panel they are made from.
 *
 * <p>None of these recipes uses Mekanism (no condition, no Mekanism or tag ingredient), so the ingredients are the same
 * with and without it; the test checks that, and the suite runs in both configurations (the {@code -PbwrNoMekanism}
 * gate runs it without Mekanism). It starts no fixture.
 */
@GameTestHolder("bwr")
@PrefixGameTestTemplate(false)
public final class ControlRoomRecipeTests {
    private ControlRoomRecipeTests() {
    }

    /** Every desk: the two control desks, the operator desks and the supervisor desk. */
    private static final String[] DESKS = {
            "control_panel_desk", "control_panel_desk_wide",
            "operator_desk", "operator_desk_round", "operator_desk_corner", "supervisor_desk"};

    /** The rest of the control-room set: panel bodies, trim, headers, furniture. */
    private static final String[] OTHERS = {
            "control_panel_vertical", "control_panel_benchboard", "control_panel_console", "control_panel_back",
            "control_panel_duplex",
            "control_panel_end_cap_flat", "control_panel_end_cap_bench", "control_panel_end_cap_console", "control_panel_end_cap_round",
            "control_panel_header_strip", "control_panel_header_canopy", "control_panel_header_vent",
            "control_panel_plinth_kick", "control_panel_plinth_apron", "control_panel_plinth_base",
            "control_panel_corner_vertical_inside", "control_panel_corner_vertical_outside", "control_panel_corner_vertical_chamfer",
            "control_panel_corner_bench_inside", "control_panel_corner_bench_outside", "control_panel_corner_bench_chamfer",
            "relay_cabinet", "relay_cabinet_plain", "mimic_band_cream", "mimic_band_black",
            "wall_display", "wall_display_wide", "control_stand"};

    private static ResourceLocation bwr(String name) {
        return ResourceLocation.fromNamespaceAndPath("bwr", name);
    }

    /** The recipe as a crafting grid: width, height and one stack per cell (empty where the pattern is blank). */
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

    private static Grid gridOf(GameTestHelper h, String name, Recipe<?> recipe) {
        if (recipe instanceof ShapedRecipe shaped) {
            List<ItemStack> cells = new ArrayList<>();
            for (Ingredient ingredient : shaped.getIngredients()) {
                cells.add(ingredient.isEmpty() ? ItemStack.EMPTY : first(ingredient));
            }
            return new Grid(shaped.getWidth(), shaped.getHeight(), cells);
        }
        if (recipe instanceof ShapelessRecipe shapeless) {
            List<ItemStack> cells = new ArrayList<>();
            for (Ingredient ingredient : shapeless.getIngredients()) {
                cells.add(first(ingredient));
            }
            return new Grid(cells.size(), 1, cells);
        }
        h.fail(name + ": not a shaped or shapeless crafting recipe");
        return null;
    }

    /** Lay the recipe out in the grid and ask the manager what it makes; the answer must be this recipe. */
    private static void craftsFromItsRecipe(GameTestHelper h, String name) {
        ServerLevel level = h.getLevel();
        RecipeManager manager = level.getServer().getRecipeManager();
        Optional<RecipeHolder<?>> holder = manager.byKey(bwr(name));
        h.assertTrue(holder.isPresent(), name + " has no recipe");
        Recipe<?> recipe = holder.get().value();
        Grid grid = gridOf(h, name, recipe);
        for (ItemStack cell : grid.cells()) {
            h.assertTrue(!cell.isEmpty() || recipe instanceof ShapedRecipe, name + " has an ingredient with no matching item");
            if (!cell.isEmpty()) {
                String ns = BuiltInRegistries.ITEM.getKey(cell.getItem()).getNamespace();
                h.assertTrue(ns.equals("minecraft") || ns.equals("bwr"),
                        name + " uses " + BuiltInRegistries.ITEM.getKey(cell.getItem()) + ", which is neither vanilla nor ours: the recipe would differ with Mekanism");
            }
        }
        for (boolean mirror : new boolean[] {false, true}) {
            Grid laid = mirror ? grid.mirrored() : grid;
            Optional<RecipeHolder<CraftingRecipe>> found = manager.getRecipeFor(RecipeType.CRAFTING, laid.input(), level);
            h.assertTrue(found.isPresent(), name + (mirror ? " (mirrored)" : "") + ": its own ingredients craft nothing");
            h.assertTrue(found.get().id().equals(bwr(name)),
                    name + (mirror ? " (mirrored)" : "") + ": its ingredients craft " + found.get().id() + " instead (a collision)");
            ItemStack made = found.get().value().assemble(laid.input(), level.registryAccess());
            h.assertTrue(BuiltInRegistries.ITEM.getKey(made.getItem()).equals(bwr(name)) && made.getCount() >= 1,
                    name + ": the recipe yields " + made);
        }
    }

    /** Iron in ninths of an ingot (an ingot is 9, a nugget 1) to craft one item, through every bwr item it is made from. */
    private static double ninths(RecipeManager manager, RegistryAccess access, Item item, int depth) {
        h_check(depth < 10, "recipe chain too deep (circular?) at " + BuiltInRegistries.ITEM.getKey(item));
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
        if (id.getNamespace().equals("minecraft")) {
            return id.getPath().equals("iron_ingot") ? 9 : id.getPath().equals("iron_nugget") ? 1 : 0;
        }
        Optional<RecipeHolder<?>> holder = manager.byKey(id);
        if (holder.isEmpty()) {
            return 0;
        }
        Recipe<?> recipe = holder.get().value();
        double total = 0;
        for (Ingredient ingredient : recipe.getIngredients()) {
            if (!ingredient.isEmpty()) {
                total += ninths(manager, access, first(ingredient).getItem(), depth + 1);
            }
        }
        return total / Math.max(1, recipe.getResultItem(access).getCount());
    }

    private static void h_check(boolean ok, String message) {
        if (!ok) {
            throw new IllegalStateException(message);
        }
    }

    private static double ironOf(GameTestHelper h, String name) {
        ServerLevel level = h.getLevel();
        return ninths(level.getServer().getRecipeManager(), level.registryAccess(), BuiltInRegistries.ITEM.get(bwr(name)), 0);
    }

    @GameTest(template = "empty")
    public static void everyDeskCraftsFromItsRecipe(GameTestHelper h) {
        for (String name : DESKS) {
            craftsFromItsRecipe(h, name);
        }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void everyOtherControlRoomRecipeCraftsItsOwnResultAndIsNotShadowed(GameTestHelper h) {
        for (String name : OTHERS) {
            craftsFromItsRecipe(h, name);
        }
        h.succeed();
    }

    /** The control-room recipes are the same with and without Mekanism: no condition, nothing from Mekanism (checked above). */
    @GameTest(template = "empty")
    public static void controlRoomRecipesDoNotDependOnMekanism(GameTestHelper h) {
        boolean mekanism = ModList.get().isLoaded("mekanism");
        ServerLevel level = h.getLevel();
        RecipeManager manager = level.getServer().getRecipeManager();
        List<String> all = new ArrayList<>(List.of(DESKS));
        all.addAll(List.of(OTHERS));
        for (String name : all) {
            h.assertTrue(manager.byKey(bwr(name)).isPresent(),
                    name + " is not loaded" + (mekanism ? " (with Mekanism)" : " (without Mekanism)"));
        }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void biggerDesksAndDisplaysCostProportionallyMoreIron(GameTestHelper h) {
        double console = ironOf(h, "control_panel_console");
        double vertical = ironOf(h, "control_panel_vertical");
        double desk = ironOf(h, "control_panel_desk");
        double wide = ironOf(h, "control_panel_desk_wide");
        h.assertTrue(vertical > 0 && console > vertical, "a console costs more than a vertical board: " + console + " vs " + vertical);
        h.assertTrue(desk > console, "a 1 m control desk costs more than the console it holds: " + desk + " vs " + console);
        h.assertTrue(wide >= 2 * desk, "a 2 m control desk costs at least two 1 m desks: " + wide + " vs 2 x " + desk);
        h.assertTrue(wide <= 2.25 * desk, "a 2 m control desk is proportional, not a multiple of two 1 m desks: " + wide + " vs 2 x " + desk);

        double operator = ironOf(h, "operator_desk");
        double supervisor = ironOf(h, "supervisor_desk");
        h.assertTrue(operator > 0, "the operator desk costs iron");
        h.assertTrue(supervisor >= 2 * operator && supervisor <= 3 * operator,
                "the two-block supervisor desk costs two to three operator desks: " + supervisor + " vs " + operator);
        h.assertTrue(ironOf(h, "operator_desk_round") > operator && ironOf(h, "operator_desk_corner") > operator,
                "a round-end or corner desk costs more than the plain desk it is made from");

        double small = ironOf(h, "wall_display");
        double large = ironOf(h, "wall_display_wide");
        h.assertTrue(large >= 1.25 * small && large <= 2 * small,
                "the 3 x 2 display (six blocks) costs more than the 2 x 2 (four blocks) but not double: " + large + " vs " + small);
        double duplex = ironOf(h, "control_panel_duplex");
        h.assertTrue(duplex > desk, "a six-block duplex column costs more than a one-block desk: " + duplex + " vs " + desk);
        h.succeed();
    }
}
