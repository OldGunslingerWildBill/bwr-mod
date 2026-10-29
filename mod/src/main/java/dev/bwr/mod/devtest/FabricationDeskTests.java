package dev.bwr.mod.devtest;

import com.mojang.authlib.GameProfile;
import dev.bwr.mod.fabrication.FabricationCategory;
import dev.bwr.mod.fabrication.FabricationCrafting;
import dev.bwr.mod.fabrication.FabricationCrafting.Outcome;
import dev.bwr.mod.fabrication.FabricationDeskBlock;
import dev.bwr.mod.fabrication.FabricationRecipe;
import dev.bwr.mod.gui.FabricationDeskMenu;
import dev.bwr.mod.panel.PanelRateLimit;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.registry.BwrItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.crafting.SizedIngredient;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The fabrication desk (FABRICATION-DESK.md): the recipe sets with and without Mekanism, the data of every desk recipe, and the
 * server's crafting: the exact items taken and given, a refusal when short, from too far away and when not permitted, a tag
 * ingredient counted across stacks, a full inventory (the rest is dropped, for a creative player too), a burst of requests
 * that never duplicates, the rate limit, and the menu's snapshot. The suite runs in both configurations (the
 * {@code -PbwrNoMekanism} gate runs it without Mekanism); it starts no fixture. Namespace {@code bwrfab}
 * ({@code -PbwrGameTestNamespaces=bwrfab} runs just these).
 */
@GameTestHolder("bwrfab")
@PrefixGameTestTemplate(false)
public final class FabricationDeskTests {
    private FabricationDeskTests() {
    }

    /** The 42 control-room parts the desk makes, with the tab each is in. */
    private static final Map<String, FabricationCategory> PARTS = Map.ofEntries(
            Map.entry("control_panel_vertical", FabricationCategory.PANELS), Map.entry("control_panel_console", FabricationCategory.PANELS),
            Map.entry("control_panel_corner_bench_chamfer", FabricationCategory.PANELS),
            Map.entry("control_panel_corner_bench_outside", FabricationCategory.PANELS),
            Map.entry("control_panel_corner_vertical_chamfer", FabricationCategory.PANELS),
            Map.entry("control_panel_corner_vertical_outside", FabricationCategory.PANELS),
            Map.entry("control_panel_duplex", FabricationCategory.PANELS), Map.entry("control_panel_end_cap_round", FabricationCategory.PANELS),
            Map.entry("control_panel_header_canopy", FabricationCategory.PANELS), Map.entry("control_panel_header_vent", FabricationCategory.PANELS),
            Map.entry("control_panel_plinth_apron", FabricationCategory.PANELS), Map.entry("control_panel_plinth_base", FabricationCategory.PANELS),
            Map.entry("control_panel_plinth_kick", FabricationCategory.PANELS),
            Map.entry("control_panel_desk", FabricationCategory.FURNITURE), Map.entry("control_panel_desk_wide", FabricationCategory.FURNITURE),
            Map.entry("control_stand", FabricationCategory.FURNITURE), Map.entry("operator_desk", FabricationCategory.FURNITURE),
            Map.entry("operator_desk_corner", FabricationCategory.FURNITURE), Map.entry("operator_desk_round", FabricationCategory.FURNITURE),
            Map.entry("supervisor_desk", FabricationCategory.FURNITURE), Map.entry("relay_cabinet", FabricationCategory.FURNITURE),
            Map.entry("relay_cabinet_plain", FabricationCategory.FURNITURE),
            Map.entry("control_switch", FabricationCategory.SWITCHES), Map.entry("key_switch", FabricationCategory.SWITCHES),
            Map.entry("rotary_selector", FabricationCategory.SWITCHES), Map.entry("setpoint_knob", FabricationCategory.SWITCHES),
            Map.entry("toggle_switch", FabricationCategory.SWITCHES), Map.entry("thumbwheel", FabricationCategory.SWITCHES),
            Map.entry("pushbutton", FabricationCategory.SWITCHES), Map.entry("rps_trip", FabricationCategory.SWITCHES),
            Map.entry("push_turn_switch", FabricationCategory.SWITCHES),
            Map.entry("dial_gauge", FabricationCategory.GAUGES), Map.entry("edgewise_gauge_horizontal", FabricationCategory.GAUGES),
            Map.entry("edgewise_gauge_vertical", FabricationCategory.GAUGES), Map.entry("bar_gauge", FabricationCategory.GAUGES),
            Map.entry("lamp", FabricationCategory.LAMPS), Map.entry("annunciator", FabricationCategory.LAMPS),
            Map.entry("seven_segment_display", FabricationCategory.DISPLAYS), Map.entry("sixteen_segment_display", FabricationCategory.DISPLAYS),
            Map.entry("bar_graph", FabricationCategory.DISPLAYS), Map.entry("wall_display", FabricationCategory.DISPLAYS),
            Map.entry("wall_display_wide", FabricationCategory.DISPLAYS));

    private static boolean mekanism() {
        return ModList.get().isLoaded("mekanism");
    }

    private static ResourceLocation bwr(String path) {
        return ResourceLocation.fromNamespaceAndPath("bwr", path);
    }

    private static void check(GameTestHelper h, boolean condition, String message) {
        h.assertTrue(condition, message);
    }

    private static FakePlayer player(ServerLevel level, String name, GameType mode) {
        FakePlayer player = FakePlayerFactory.get(level,
                new GameProfile(UUID.nameUUIDFromBytes(("fab-" + name).getBytes(StandardCharsets.UTF_8)), "fab-" + name));
        player.setGameMode(mode);
        player.getInventory().clearContent();
        player.setShiftKeyDown(false);
        PanelRateLimit.forget(player.getUUID());
        return player;
    }

    /** A desk one block above the test's floor, and the player two blocks in front of it. */
    private static BlockPos desk(GameTestHelper h, FakePlayer player) {
        BlockPos at = h.absolutePos(new BlockPos(1, 1, 1));
        h.getLevel().setBlock(at, BwrBlocks.FABRICATION_DESK.get().defaultBlockState(), 3);
        player.setPos(Vec3.atBottomCenterOf(at.north(2)));
        return at;
    }

    private static void removeDesk(GameTestHelper h, BlockPos at) {
        h.getLevel().removeBlock(at, false);
    }

    private static ItemStack stackOf(SizedIngredient input, int times) {
        ItemStack[] items = input.ingredient().getItems();
        return items[0].copyWithCount(input.count() * times);
    }

    /** The one live desk recipe that makes bwr:name. */
    private static RecipeHolder<FabricationRecipe> recipeFor(GameTestHelper h, String name) {
        var all = FabricationCrafting.making(h.getLevel().getServer().getRecipeManager(), h.getLevel().registryAccess(),
                BuiltInRegistries.ITEM.get(bwr(name)));
        check(h, all.size() == 1, name + " has " + all.size() + " desk recipes, expected exactly one");
        return all.get(0);
    }

    /** The paying slots as copies: the 36 main slots, then the off hand. */
    private static List<ItemStack> contents(Inventory inventory) {
        List<ItemStack> out = new ArrayList<>();
        for (ItemStack stack : FabricationCrafting.payingStacks(inventory)) {
            out.add(stack.copy());
        }
        return out;
    }

    private static boolean sameContents(List<ItemStack> a, List<ItemStack> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!ItemStack.matches(a.get(i), b.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static int count(Inventory inventory, Item item) {
        int n = 0;
        for (ItemStack stack : FabricationCrafting.payingStacks(inventory)) {
            if (stack.is(item)) {
                n += stack.getCount();
            }
        }
        return n;
    }

    private static int dropped(GameTestHelper h, FakePlayer player, Item item) {
        int n = 0;
        for (ItemEntity entity : h.getLevel().getEntitiesOfClass(ItemEntity.class, player.getBoundingBox().inflate(6.0))) {
            if (entity.getItem().is(item)) {
                n += entity.getItem().getCount();
                entity.discard();
            }
        }
        return n;
    }

    // ---- the recipe sets

    /** Without Mekanism the vanilla desk recipes are live, with it the Mekanism ones, and never both. */
    @GameTest(template = "empty", templateNamespace = "bwrfab")
    public static void theRightRecipeSetIsLiveInThisInstall(GameTestHelper h) {
        boolean mek = mekanism();
        var manager = h.getLevel().getServer().getRecipeManager();
        List<String> problems = new ArrayList<>();
        for (String name : PARTS.keySet()) {
            boolean plain = manager.byKey(bwr(name)).isPresent();
            boolean tier = manager.byKey(bwr(name + "_mekanism")).isPresent();
            if (plain == mek) {
                problems.add(name + ": the vanilla desk recipe is " + (plain ? "live with Mekanism" : "missing without Mekanism"));
            }
            if (tier != mek) {
                problems.add(name + ": the Mekanism desk recipe is " + (tier ? "live without Mekanism" : "missing with Mekanism"));
            }
            if (plain && !(manager.byKey(bwr(name)).get().value() instanceof FabricationRecipe)) {
                problems.add(name + ": bwr:" + name + " is not a desk recipe");
            }
        }
        // the desk itself is a grid recipe in the same pair
        check(h, manager.byKey(bwr("fabrication_desk")).isPresent() != mek, "the vanilla recipe of the desk is live with Mekanism or missing without it");
        check(h, manager.byKey(bwr("fabrication_desk_mekanism")).isPresent() == mek, "the Mekanism recipe of the desk is live without Mekanism or missing with it");
        check(h, problems.isEmpty(), problems.size() + " problem(s): " + problems.subList(0, Math.min(6, problems.size())));
        h.succeed();
    }

    /** Every one of the 42 has exactly one live desk recipe in this install, in its tab, and no grid recipe makes it any more. */
    @GameTest(template = "empty", templateNamespace = "bwrfab")
    public static void everyOneOfTheFortyTwoHasExactlyOneLiveDeskRecipeAndNoGridRecipe(GameTestHelper h) {
        var manager = h.getLevel().getServer().getRecipeManager();
        check(h, PARTS.size() == 42, "the list has " + PARTS.size() + " parts, expected 42");
        Set<ResourceLocation> gridResults = new HashSet<>();
        for (RecipeHolder<CraftingRecipe> holder : manager.getAllRecipesFor(RecipeType.CRAFTING)) {
            gridResults.add(BuiltInRegistries.ITEM.getKey(holder.value().getResultItem(h.getLevel().registryAccess()).getItem()));
        }
        List<String> problems = new ArrayList<>();
        for (var part : PARTS.entrySet()) {
            Item item = BuiltInRegistries.ITEM.get(bwr(part.getKey()));
            var desk = FabricationCrafting.making(manager, h.getLevel().registryAccess(), item);
            if (desk.size() != 1) {
                problems.add(part.getKey() + ": " + desk.size() + " live desk recipes " + desk.stream().map(RecipeHolder::id).toList());
                continue;
            }
            if (desk.get(0).value().category() != part.getValue()) {
                problems.add(part.getKey() + " is in the " + desk.get(0).value().category() + " tab, expected " + part.getValue());
            }
            if (gridResults.contains(bwr(part.getKey()))) {
                problems.add(part.getKey() + " can still be crafted in the grid");
            }
        }
        // and the desk makes nothing else: every live desk recipe is one of the 42
        for (var holder : FabricationCrafting.recipes(manager)) {
            String made = BuiltInRegistries.ITEM.getKey(holder.value().getResultItem(h.getLevel().registryAccess()).getItem()).getPath();
            if (!PARTS.containsKey(made)) {
                problems.add(holder.id() + " makes " + made + ", which is not one of the 42");
            }
        }
        check(h, FabricationCrafting.recipes(manager).size() == 42, FabricationCrafting.recipes(manager).size() + " live desk recipes, expected 42");
        check(h, problems.isEmpty(), problems.size() + " problem(s): " + problems.subList(0, Math.min(6, problems.size())));
        h.succeed();
    }

    /**
     * The data of every live desk recipe: at most five materials (the screen's list fits five), positive counts a stack can hold,
     * every material names items (a tag that is empty makes the recipe unmakeable), no item is matched by two materials of one
     * recipe (the desk pays them in order), a result that is one stack at most, the wire keys all different, and with Mekanism
     * the materials are Mekanism's, vanilla or ours.
     */
    @GameTest(template = "empty", templateNamespace = "bwrfab", timeoutTicks = 200)
    public static void everyDeskRecipeIsSound(GameTestHelper h) {
        var manager = h.getLevel().getServer().getRecipeManager();
        var recipes = FabricationCrafting.recipes(manager);
        List<String> problems = new ArrayList<>();
        Set<Integer> keys = new HashSet<>();
        List<Item> items = new ArrayList<>();
        BuiltInRegistries.ITEM.forEach(items::add);
        for (var holder : recipes) {
            FabricationRecipe recipe = holder.value();
            String id = holder.id().toString();
            if (!keys.add(FabricationCrafting.key(holder.id()))) {
                problems.add(id + ": its wire key collides with another recipe's");
            }
            if (recipe.inputs().isEmpty() || recipe.inputs().size() > 5) {
                problems.add(id + ": " + recipe.inputs().size() + " materials, expected 1 to 5");
            }
            ItemStack result = recipe.output();
            if (result.isEmpty() || result.getCount() > result.getMaxStackSize()) {
                problems.add(id + ": result " + result);
            }
            for (SizedIngredient input : recipe.inputs()) {
                ItemStack[] named = input.ingredient().getItems();
                if (named.length == 0) {
                    problems.add(id + ": a material names no item");
                    continue;
                }
                if (input.count() < 1 || input.count() > named[0].getMaxStackSize()) {
                    problems.add(id + ": a count of " + input.count());
                }
                String ns = BuiltInRegistries.ITEM.getKey(named[0].getItem()).getNamespace();
                if (!(ns.equals("minecraft") || ns.equals("bwr") || mekanism() && ns.equals("mekanism"))) {
                    problems.add(id + ": a material from " + ns);
                }
            }
            for (Item item : items) {
                ItemStack one = new ItemStack(item);
                int matches = 0;
                for (SizedIngredient input : recipe.inputs()) {
                    if (input.ingredient().test(one)) {
                        matches++;
                    }
                }
                if (matches > 1) {
                    problems.add(id + ": " + BuiltInRegistries.ITEM.getKey(item) + " matches " + matches + " of its materials");
                    break;
                }
            }
        }
        check(h, problems.isEmpty(), problems.size() + " problem(s): " + problems.subList(0, Math.min(6, problems.size())));
        h.succeed();
    }

    // ---- crafting

    /** Enough materials: the exact items are taken, the exact result is given, nothing else changes. */
    @GameTest(template = "empty", templateNamespace = "bwrfab")
    public static void aCraftTakesExactlyTheMaterialsAndGivesExactlyTheResult(GameTestHelper h) {
        FakePlayer player = player(h.getLevel(), "exact", GameType.SURVIVAL);
        BlockPos desk = desk(h, player);
        for (String name : new String[] {"dial_gauge", "pushbutton", "control_panel_vertical"}) {
            player.getInventory().clearContent();
            var holder = recipeFor(h, name);
            FabricationRecipe recipe = holder.value();
            // each material in a slot of its own, with one more than needed, and a bystander item in another slot
            int slot = 0;
            for (SizedIngredient input : recipe.inputs()) {
                player.getInventory().setItem(slot++, stackOf(input, 1).copyWithCount(input.count() + 1));
            }
            player.getInventory().setItem(30, new ItemStack(Items.COBBLESTONE, 17));
            List<ItemStack> before = contents(player.getInventory());
            Item made = recipe.output().getItem();
            check(h, count(player.getInventory(), made) == 0, name + ": the result is already in the inventory");

            var result = FabricationCrafting.craft(player, desk, FabricationCrafting.key(holder.id()), 1);
            check(h, result.outcome() == Outcome.CRAFTED && result.crafts() == 1 && result.dropped() == 0, name + ": " + result);
            int s = 0;
            for (SizedIngredient input : recipe.inputs()) {
                ItemStack now = player.getInventory().getItem(s);
                check(h, now.getCount() == 1 && now.is(before.get(s).getItem()), name + ": material " + s + " is " + now + ", expected exactly one left");
                s++;
            }
            check(h, player.getInventory().getItem(30).getCount() == 17 && player.getInventory().getItem(30).is(Items.COBBLESTONE),
                    name + ": a bystander item changed");
            check(h, count(player.getInventory(), made) == recipe.output().getCount(),
                    name + ": " + count(player.getInventory(), made) + " of the result, expected " + recipe.output().getCount());
            int others = 0;
            for (ItemStack stack : FabricationCrafting.payingStacks(player.getInventory())) {
                others += stack.isEmpty() ? 0 : stack.getCount();
            }
            int expected = 17 + recipe.inputs().size() + recipe.output().getCount();
            check(h, others == expected, name + ": " + others + " items in all, expected " + expected);
        }
        removeDesk(h, desk);
        h.succeed();
    }

    /** One unit short of any material: refused, and not a thing in the inventory changes. */
    @GameTest(template = "empty", templateNamespace = "bwrfab")
    public static void aCraftIsRefusedWhenShortAndChangesNothing(GameTestHelper h) {
        FakePlayer player = player(h.getLevel(), "short", GameType.SURVIVAL);
        BlockPos desk = desk(h, player);
        var holder = recipeFor(h, "dial_gauge");
        int key = FabricationCrafting.key(holder.id());
        for (int missing = 0; missing < holder.value().inputs().size(); missing++) {
            player.getInventory().clearContent();
            int slot = 0;
            for (int i = 0; i < holder.value().inputs().size(); i++) {
                SizedIngredient input = holder.value().inputs().get(i);
                int have = i == missing ? input.count() - 1 : input.count();
                if (have > 0) {
                    player.getInventory().setItem(slot++, stackOf(input, 1).copyWithCount(have));
                }
            }
            List<ItemStack> before = contents(player.getInventory());
            var result = FabricationCrafting.craft(player, desk, key, 1);
            check(h, result.outcome() == Outcome.SHORT && result.crafts() == 0, "short of material " + missing + ": " + result);
            check(h, sameContents(before, contents(player.getInventory())), "short of material " + missing + ": the inventory changed");
        }
        // an empty inventory, a recipe key the install does not have, and a request for no crafts or a thousand
        player.getInventory().clearContent();
        check(h, FabricationCrafting.craft(player, desk, key, 1).outcome() == Outcome.SHORT, "an empty inventory crafted");
        check(h, FabricationCrafting.craft(player, desk, 0x5EED, 1).outcome() == Outcome.UNKNOWN_RECIPE, "an unknown recipe key crafted");
        for (SizedIngredient input : holder.value().inputs()) {
            player.getInventory().add(stackOf(input, 1).copyWithCount(64));
        }
        var many = FabricationCrafting.craft(player, desk, key, 1000);
        check(h, many.outcome() == Outcome.CRAFTED && many.crafts() <= FabricationRecipe.MAX_BATCH, "a request for 1000 crafts made " + many.crafts());
        var none = FabricationCrafting.craft(player, desk, key, -5);
        check(h, none.outcome() == Outcome.CRAFTED && none.crafts() == 1, "a request for -5 crafts made " + none.crafts());
        removeDesk(h, desk);
        h.succeed();
    }

    /** Too far from the desk, not at a desk, a spectator: refused, nothing taken. Reach and adventure mode are the same as for the panels. */
    @GameTest(template = "empty", templateNamespace = "bwrfab")
    public static void aCraftIsRefusedFromTooFarFromNoDeskAndForASpectator(GameTestHelper h) {
        FakePlayer player = player(h.getLevel(), "far", GameType.SURVIVAL);
        BlockPos desk = desk(h, player);
        var holder = recipeFor(h, "pushbutton");
        int key = FabricationCrafting.key(holder.id());
        for (SizedIngredient input : holder.value().inputs()) {
            player.getInventory().add(stackOf(input, 3));
        }
        List<ItemStack> before = contents(player.getInventory());

        player.setPos(Vec3.atBottomCenterOf(desk.north(30)));
        check(h, FabricationCrafting.craft(player, desk, key, 1).outcome() == Outcome.TOO_FAR, "crafted from 30 blocks away");
        player.setPos(Vec3.atBottomCenterOf(desk.north(12)));
        check(h, FabricationCrafting.craft(player, desk, key, 1).outcome() == Outcome.TOO_FAR, "crafted from 12 blocks away");
        check(h, sameContents(before, contents(player.getInventory())), "a refused craft from afar changed the inventory");
        player.setPos(Vec3.atBottomCenterOf(desk.north(7)));
        check(h, FabricationCrafting.craft(player, desk, key, 1).outcome() == Outcome.CRAFTED, "a craft from 7 blocks (inside the screen's reach) was refused");
        player.getInventory().clearContent();
        for (SizedIngredient input : holder.value().inputs()) {
            player.getInventory().add(stackOf(input, 3));
        }
        before = contents(player.getInventory());

        player.setPos(Vec3.atBottomCenterOf(desk.north(2)));
        player.setGameMode(GameType.SPECTATOR);
        check(h, FabricationCrafting.craft(player, desk, key, 1).outcome() == Outcome.NOT_ALLOWED, "a spectator crafted");
        player.setGameMode(GameType.SURVIVAL);
        check(h, sameContents(before, contents(player.getInventory())), "a refused craft by a spectator changed the inventory");
        // adventure mode may operate controls, so it may craft (like pressing a panel button on an adventure map)
        player.setGameMode(GameType.ADVENTURE);
        check(h, FabricationCrafting.craft(player, desk, key, 1).outcome() == Outcome.CRAFTED, "an adventure mode player was refused");
        player.setGameMode(GameType.SURVIVAL);
        player.getInventory().clearContent();
        for (SizedIngredient input : holder.value().inputs()) {
            player.getInventory().add(stackOf(input, 3));
        }
        before = contents(player.getInventory());

        removeDesk(h, desk);
        check(h, FabricationCrafting.craft(player, desk, key, 1).outcome() == Outcome.NO_DESK, "crafted where there is no desk");
        check(h, sameContents(before, contents(player.getInventory())), "a craft at no desk changed the inventory");
        h.succeed();
    }

    /**
     * The desk block: right-click is handled on the server only, opens for a player who may operate things and not for a
     * spectator (a fake player cannot open a screen, so the gate itself is checked), and the open screen stays valid only near the
     * desk.
     */
    @GameTest(template = "empty", templateNamespace = "bwrfab")
    public static void theDeskBlockOpensForAnyoneButASpectatorAndTheScreenNeedsTheDeskNearby(GameTestHelper h) {
        FakePlayer player = player(h.getLevel(), "open", GameType.SURVIVAL);
        BlockPos desk = desk(h, player);
        BlockState state = h.getLevel().getBlockState(desk);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(desk), Direction.NORTH, desk, false);
        for (GameType mode : new GameType[] {GameType.SURVIVAL, GameType.CREATIVE, GameType.ADVENTURE}) {
            player.setGameMode(mode);
            check(h, FabricationDeskBlock.mayUse(player), mode + " may not use the desk");
            check(h, state.useWithoutItem(h.getLevel(), player, hit).consumesAction(), mode + ": the click was not handled");
        }
        player.setGameMode(GameType.SPECTATOR);
        check(h, !FabricationDeskBlock.mayUse(player), "a spectator may use the desk");
        check(h, state.useWithoutItem(h.getLevel(), player, hit).consumesAction() && !(player.containerMenu instanceof FabricationDeskMenu),
                "a spectator's click was not swallowed, or opened the desk");
        player.setGameMode(GameType.SURVIVAL);
        FabricationDeskMenu menu = new FabricationDeskMenu(5, player.getInventory(), desk);
        check(h, menu.stillValid(player), "the screen is not valid next to the desk");
        player.setPos(Vec3.atBottomCenterOf(desk.north(40)));
        check(h, !menu.stillValid(player), "the screen stays valid 40 blocks from the desk");
        player.setPos(Vec3.atBottomCenterOf(desk.north(2)));
        removeDesk(h, desk);
        check(h, !menu.stillValid(player), "the screen stays valid when the desk is gone");
        h.succeed();
    }

    // ---- tags, a full inventory, bursts

    /** A tag ingredient is counted across every stack and every item it names, and the armour slots do not count. */
    @GameTest(template = "empty", templateNamespace = "bwrfab")
    public static void aTagIngredientIsCountedAcrossStacksAndItems(GameTestHelper h) {
        FakePlayer player = player(h.getLevel(), "tag", GameType.SURVIVAL);
        BlockPos desk = desk(h, player);
        // five planks of any wood make two sticks (a recipe of this test, not one of the install's)
        FabricationRecipe planks = new FabricationRecipe(FabricationCategory.PANELS, List.of(SizedIngredient.of(ItemTags.PLANKS, 5)),
                new ItemStack(Items.STICK, 2));
        Inventory inventory = player.getInventory();
        inventory.setItem(3, new ItemStack(Items.OAK_PLANKS, 2));
        inventory.setItem(10, new ItemStack(Items.SPRUCE_PLANKS, 1));
        inventory.setItem(Inventory.SLOT_OFFHAND, new ItemStack(Items.BIRCH_PLANKS, 2));
        inventory.setItem(30, new ItemStack(Items.COBBLESTONE, 40));
        var made = FabricationCrafting.craft(player, desk, planks, 1);
        check(h, made.outcome() == Outcome.CRAFTED && made.crafts() == 1, "three kinds of planks in three places did not pay for the recipe: " + made);
        check(h, count(inventory, Items.OAK_PLANKS) + count(inventory, Items.SPRUCE_PLANKS) + count(inventory, Items.BIRCH_PLANKS) == 0,
                "planks were left over");
        check(h, count(inventory, Items.STICK) == 2 && count(inventory, Items.COBBLESTONE) == 40, "wrong items after the craft");
        // four planks are short, and planks in an armour slot are not the player's to spend
        inventory.clearContent();
        inventory.setItem(3, new ItemStack(Items.OAK_PLANKS, 4));
        inventory.armor.set(0, new ItemStack(Items.SPRUCE_PLANKS, 3));
        List<ItemStack> before = contents(inventory);
        check(h, FabricationCrafting.craft(player, desk, planks, 1).outcome() == Outcome.SHORT, "planks in an armour slot paid");
        check(h, sameContents(before, contents(inventory)) && inventory.armor.get(0).getCount() == 3, "a short craft changed the inventory");
        // twelve planks, a batch of eight: two crafts, ten planks, two left
        inventory.clearContent();
        inventory.setItem(0, new ItemStack(Items.OAK_PLANKS, 7));
        inventory.setItem(1, new ItemStack(Items.ACACIA_PLANKS, 5));
        var batch = FabricationCrafting.craft(player, desk, planks, 8);
        check(h, batch.crafts() == 2 && count(inventory, Items.STICK) == 4, "a batch made " + batch.crafts() + " crafts and " + count(inventory, Items.STICK) + " sticks");
        check(h, count(inventory, Items.OAK_PLANKS) + count(inventory, Items.ACACIA_PLANKS) == 2, "the planks left over are not two");
        // the same through the real recipe whose materials are a tag with Mekanism, an item without it: split in two stacks
        inventory.clearContent();
        var holder = recipeFor(h, "bar_gauge");
        SizedIngredient metal = holder.value().inputs().get(0);
        ItemStack ingot = stackOf(metal, 1);
        int half = metal.count() / 2;
        inventory.setItem(5, ingot.copyWithCount(half));
        inventory.setItem(25, ingot.copyWithCount(metal.count() - half));
        for (int i = 1; i < holder.value().inputs().size(); i++) {
            inventory.setItem(6 + i, stackOf(holder.value().inputs().get(i), 1));
        }
        var split = FabricationCrafting.craft(player, desk, FabricationCrafting.key(holder.id()), 1);
        check(h, split.outcome() == Outcome.CRAFTED, "a material split across two stacks did not pay: " + split);
        check(h, count(inventory, ingot.getItem()) == 0, "the split material was not all taken");
        removeDesk(h, desk);
        h.succeed();
    }

    /**
     * A full inventory: the part is dropped at the player's feet, not deleted, and the materials are still taken. The same for
     * a creative player, for whom a plain Inventory#add would count a part that did not fit as taken and delete it. A creative
     * player pays like anyone else: the mod gives nothing free anywhere else, and the part is in the creative tab anyway.
     */
    @GameTest(template = "empty", templateNamespace = "bwrfab")
    public static void aFullInventoryDropsThePartAndACreativePlayerPaysAndDropsToo(GameTestHelper h) {
        for (GameType mode : new GameType[] {GameType.SURVIVAL, GameType.CREATIVE}) {
            FakePlayer player = player(h.getLevel(), "full-" + mode.getName(), mode);
            BlockPos desk = desk(h, player);
            var holder = recipeFor(h, "dial_gauge");
            FabricationRecipe recipe = holder.value();
            Inventory inventory = player.getInventory();
            int slot = 0;
            for (SizedIngredient input : recipe.inputs()) {
                inventory.setItem(slot++, stackOf(input, 1).copyWithCount(input.count() + 1));
            }
            for (int i = slot; i < 36; i++) {
                inventory.setItem(i, new ItemStack(Items.COBBLESTONE, 64));
            }
            Item made = recipe.output().getItem();
            var result = FabricationCrafting.craft(player, desk, FabricationCrafting.key(holder.id()), 1);
            check(h, result.outcome() == Outcome.CRAFTED && result.dropped() == recipe.output().getCount(), mode + ": " + result);
            check(h, count(inventory, made) == 0, mode + ": the part went into a full inventory");
            check(h, dropped(h, player, made) == recipe.output().getCount(), mode + ": the part was not dropped, it was lost");
            for (int i = 0; i < recipe.inputs().size(); i++) {
                check(h, inventory.getItem(i).getCount() == 1, mode + ": the materials were not paid for in full (" + inventory.getItem(i) + ")");
            }
            // a part that fits goes onto a stack that has room, before an empty slot
            inventory.clearContent();
            for (SizedIngredient input : recipe.inputs()) {
                inventory.add(stackOf(input, 1));
            }
            ItemStack existing = recipe.output().copyWithCount(1);
            inventory.setItem(20, existing);
            FabricationCrafting.craft(player, desk, FabricationCrafting.key(holder.id()), 1);
            check(h, count(inventory, made) == recipe.output().getCount() + 1, mode + ": the part count is wrong after stacking");
            check(h, inventory.getItem(20).getCount() == recipe.output().getCount() + 1, mode + ": the part did not stack onto the stack with room");
            removeDesk(h, desk);
        }
        h.succeed();
    }

    /**
     * Many requests at once (one server tick, the order a burst arrives in): no craft is made without paying for it. With
     * materials for exactly three crafts, ten requests make three; a batch of eight with materials for five makes five; and
     * through the menu the rate limit stops a flood at its burst, whatever the materials.
     */
    @GameTest(template = "empty", templateNamespace = "bwrfab")
    public static void aBurstOfRequestsNeverMakesAPartItWasNotPaidFor(GameTestHelper h) {
        FakePlayer player = player(h.getLevel(), "burst", GameType.SURVIVAL);
        BlockPos desk = desk(h, player);
        var holder = recipeFor(h, "pushbutton");
        FabricationRecipe recipe = holder.value();
        int key = FabricationCrafting.key(holder.id());
        Inventory inventory = player.getInventory();
        int slot = 0;
        for (SizedIngredient input : recipe.inputs()) {
            inventory.setItem(slot++, stackOf(input, 3));
        }
        int crafted = 0;
        int refused = 0;
        for (int i = 0; i < 10; i++) {
            var result = FabricationCrafting.craft(player, desk, key, 1);
            if (result.outcome() == Outcome.CRAFTED) {
                crafted += result.crafts();
            } else if (result.outcome() == Outcome.SHORT) {
                refused++;
            }
        }
        check(h, crafted == 3 && refused == 7, "ten requests for three crafts' worth made " + crafted + " and refused " + refused);
        check(h, count(inventory, recipe.output().getItem()) == 3 * recipe.output().getCount(), "the parts in the inventory are not three crafts' worth");
        for (SizedIngredient input : recipe.inputs()) {
            check(h, count(inventory, input.ingredient().getItems()[0].getItem()) == 0, "materials were left over");
        }
        inventory.clearContent();
        slot = 0;
        for (SizedIngredient input : recipe.inputs()) {
            inventory.setItem(slot++, stackOf(input, 5));
        }
        var batch = FabricationCrafting.craft(player, desk, key, FabricationRecipe.MAX_BATCH);
        check(h, batch.crafts() == 5, "a batch of 8 with materials for 5 made " + batch.crafts());
        check(h, count(inventory, recipe.output().getItem()) == 5 * recipe.output().getCount(), "the parts of the batch are not five crafts' worth");

        // through the menu: a flood of 25 requests in one tick, plenty of materials, the rate limit's burst gets through
        inventory.clearContent();
        for (int i = 0; i < recipe.inputs().size(); i++) {
            inventory.setItem(i, stackOf(recipe.inputs().get(i), 1).copyWithCount(64));
        }
        inventory.setItem(10, recipe.inputs().get(0).ingredient().getItems()[0].copyWithCount(64));
        FabricationDeskMenu menu = new FabricationDeskMenu(7, inventory, desk);
        int accepted = 0;
        int limited = 0;
        for (int i = 0; i < 25; i++) {
            menu.handleCommand(player, FabricationDeskMenu.CMD_CRAFT, key, 1);
            if (menu.lastOutcome == Outcome.CRAFTED) {
                accepted += menu.lastCrafts;
            } else if (menu.lastOutcome == Outcome.RATE_LIMITED) {
                limited++;
            }
        }
        check(h, accepted == 10 && limited == 15, "a flood of 25 requests: " + accepted + " crafts accepted, " + limited + " limited (expected 10 and 15)");
        check(h, count(inventory, recipe.output().getItem()) == 10 * recipe.output().getCount(), "the flood made the wrong number of parts");
        removeDesk(h, desk);
        h.succeed();
    }

    // ---- the menu

    /** The menu's snapshot carries what the player holds and what the last request came to; the client reads back what was written. */
    @GameTest(template = "empty", templateNamespace = "bwrfab")
    public static void theMenuSnapshotCarriesTheInventoryAndTheLastAnswer(GameTestHelper h) {
        FakePlayer player = player(h.getLevel(), "snapshot", GameType.SURVIVAL);
        BlockPos desk = desk(h, player);
        Inventory inventory = player.getInventory();
        inventory.setItem(2, new ItemStack(Items.IRON_INGOT, 12));
        inventory.setItem(30, new ItemStack(Items.GLASS_PANE, 3));
        inventory.setItem(Inventory.SLOT_OFFHAND, new ItemStack(Items.REDSTONE, 5));
        inventory.armor.set(1, new ItemStack(Items.GOLD_INGOT, 9));
        FabricationDeskMenu server = new FabricationDeskMenu(9, inventory, desk);
        server.handleCommand(player, FabricationDeskMenu.CMD_CRAFT, 0x5EED, 1);   // an unknown key: answered, nothing made
        check(h, server.lastOutcome == Outcome.UNKNOWN_RECIPE && server.answered && server.serial == 1, "the answer to a bad request is " + server.lastOutcome);
        server.handleCommand(player, 99, 0, 0);   // an unknown command is ignored
        check(h, server.serial == 1, "an unknown command was answered");
        FabricationDeskMenu client = new FabricationDeskMenu(9, inventory, desk);
        client.acceptSnapshot(server.encodeSnapshot());
        check(h, client.lastOutcome == Outcome.UNKNOWN_RECIPE && client.serial == 1 && client.answered, "the answer did not arrive");
        int iron = 0;
        int redstone = 0;
        int gold = 0;
        for (ItemStack stack : client.held) {
            iron += stack.is(Items.IRON_INGOT) ? stack.getCount() : 0;
            redstone += stack.is(Items.REDSTONE) ? stack.getCount() : 0;
            gold += stack.is(Items.GOLD_INGOT) ? stack.getCount() : 0;
        }
        check(h, iron == 12 && redstone == 5 && gold == 0, "the held counts that arrived are " + iron + " iron, " + redstone + " redstone, " + gold + " gold");
        client.acceptSnapshot(new byte[] {1, 2, 3});   // junk keeps the last good state and does not throw
        check(h, client.serial == 1, "a junk snapshot changed the state");
        removeDesk(h, desk);
        h.succeed();
    }

    // ---- the block and its assets

    @GameTest(template = "empty", templateNamespace = "bwrfab")
    public static void theDeskIsRegisteredWithItemModelLootNameAndNoBlockEntity(GameTestHelper h) throws IOException {
        ResourceLocation id = bwr("fabrication_desk");
        check(h, BuiltInRegistries.BLOCK.containsKey(id) && BuiltInRegistries.ITEM.containsKey(id), "the desk is not registered");
        check(h, BuiltInRegistries.BLOCK.get(id) == BwrBlocks.FABRICATION_DESK.get(), "the registry entry is not the block in BwrBlocks");
        BlockState state = BwrBlocks.FABRICATION_DESK.get().defaultBlockState();
        check(h, !state.hasBlockEntity(), "the desk has a block entity (it has no state to keep: FABRICATION-DESK.md)");
        check(h, BwrBlocks.FABRICATION_DESK.get().getStateDefinition().getPossibleStates().size() == 4, "the desk is four facings");
        check(h, state.is(BlockTags.MINEABLE_WITH_PICKAXE), "the desk is not mined with a pickaxe");
        ResourceKey<LootTable> table = ResourceKey.create(Registries.LOOT_TABLE, bwr("blocks/fabrication_desk"));
        check(h, h.getLevel().getServer().reloadableRegistries().getLootTable(table) != LootTable.EMPTY, "the desk has no loot table");
        CreativeModeTabs.tryRebuildTabContents(h.getLevel().enabledFeatures(), true, h.getLevel().registryAccess());
        check(h, BwrItems.TAB.get().getDisplayItems().stream().anyMatch(s -> s.is(BwrItems.FABRICATION_DESK.get())), "the desk is not in the creative tab");
        for (String path : new String[] {"/assets/bwr/blockstates/fabrication_desk.json", "/assets/bwr/models/item/fabrication_desk.json",
                "/assets/bwr/models/block/fabrication_desk/fabrication_desk.json", "/assets/bwr/models/block/fabrication_desk/fabrication_desk.obj",
                "/assets/bwr/models/block/fabrication_desk/materials.mtl"}) {
            try (InputStream in = FabricationDeskTests.class.getResourceAsStream(path)) {
                check(h, in != null, path + " is missing");
            }
        }
        String lang;
        try (InputStream in = FabricationDeskTests.class.getResourceAsStream("/assets/bwr/lang/en_us.json")) {
            lang = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        check(h, lang.contains("\"block.bwr.fabrication_desk\""), "the desk has no name in the language file");
        for (FabricationCategory category : FabricationCategory.values()) {
            check(h, lang.contains("\"" + category.labelKey() + "\"") && lang.contains("\"" + category.labelKey() + ".short\""),
                    category + " has no tab label in the language file");
        }
        // the shape follows the facing: the pegboard is at the back, and the back turns with the block
        var north = state.setValue(FabricationDeskBlock.FACING, Direction.NORTH).getShape(h.getLevel(), BlockPos.ZERO);
        var east = state.setValue(FabricationDeskBlock.FACING, Direction.EAST).getShape(h.getLevel(), BlockPos.ZERO);
        check(h, north.bounds().maxY == 1.0 && east.bounds().maxY == 1.0, "the pegboard does not reach the top of the block");
        boolean northBack = north.toAabbs().stream().anyMatch(b -> b.contains(0.5, 0.95, 0.9)) && north.toAabbs().stream().noneMatch(b -> b.contains(0.5, 0.95, 0.1));
        boolean eastBack = east.toAabbs().stream().anyMatch(b -> b.contains(0.1, 0.95, 0.5)) && east.toAabbs().stream().noneMatch(b -> b.contains(0.9, 0.95, 0.5));
        check(h, northBack && eastBack, "the shape does not turn with the facing");
        h.succeed();
    }
}
