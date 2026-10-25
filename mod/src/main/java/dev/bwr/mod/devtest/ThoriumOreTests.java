package dev.bwr.mod.devtest;

import dev.bwr.core.fuel.ThoriumMassBalance;
import dev.bwr.mod.config.GameplayConfig;
import dev.bwr.mod.fuel.FabricatorItemView;
import dev.bwr.mod.fuel.FuelAssemblies;
import dev.bwr.mod.fuel.FuelAssemblyData;
import dev.bwr.mod.fuel.FuelFabricatorBlockEntity;
import dev.bwr.mod.fuel.FuelItemRoute;
import dev.bwr.mod.fuel.FuelTypes;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.registry.BwrItems;
import dev.bwr.mod.worldgen.ThoriumBiomeModifier;
import dev.bwr.mod.worldgen.ThoriumOreFeature;
import dev.bwr.mod.worldgen.UraniumVeinConfig;
import dev.bwr.mod.worldgen.UraniumVeins;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.common.world.BiomeModifier;
import net.neoforged.neoforge.common.world.ModifiableBiomeInfo;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The mod's own thorium (THORIUM.md): blocks and items, tags, recipes, drops and their byproducts (with and without Silk Touch,
 * with Fortune), the fuel fabricator's thorium route (costs, the machine, its slot, its save), the datapack's biome table and the
 * server option {@code thoriumOreGeneration}. What generation really produces in fresh chunks (density by biome, old chunks left
 * alone, the option off giving none) is measured by the worldgen check ({@code :mod:runWorldgenCheck}, ThoriumWorldgenCheck).
 */
@GameTestHolder("bwrthorium")
@PrefixGameTestTemplate(false)
public final class ThoriumOreTests {

    private static final String[] BLOCKS = {"thorium_ore", "deepslate_thorium_ore", "monazite_sand", "raw_thorium_block", "thorium_block"};

    private static ResourceLocation bwr(String path) {
        return ResourceLocation.fromNamespaceAndPath("bwr", path);
    }

    private static TagKey<Block> blockTag(String ns, String path) {
        return TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath(ns, path));
    }

    private static TagKey<Item> itemTag(String ns, String path) {
        return TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath(ns, path));
    }

    // ------------------------------------------------------------------------------------------------------------
    // blocks, items, tags

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void blocksAndItemsExistAndHaveTheRightTool(GameTestHelper h) {
        for (String name : BLOCKS) {
            h.assertTrue(BuiltInRegistries.BLOCK.containsKey(bwr(name)), name + " block is not registered");
            h.assertTrue(BuiltInRegistries.ITEM.containsKey(bwr(name)), name + " block item is not registered");
        }
        for (String name : new String[] {"raw_thorium", "thorium_ingot", "thorium_nugget", "rare_earth_concentrate"}) {
            h.assertTrue(BuiltInRegistries.ITEM.containsKey(bwr(name)), name + " is not registered");
        }
        ItemStack iron = new ItemStack(Items.IRON_PICKAXE), stone = new ItemStack(Items.STONE_PICKAXE), wood = new ItemStack(Items.WOODEN_PICKAXE);
        for (Block b : new Block[] {BwrBlocks.THORIUM_ORE.get(), BwrBlocks.DEEPSLATE_THORIUM_ORE.get(), BwrBlocks.RAW_THORIUM_BLOCK.get(), BwrBlocks.THORIUM_BLOCK.get()}) {
            BlockState s = b.defaultBlockState();
            h.assertTrue(s.requiresCorrectToolForDrops(), b + " drops without the right tool");
            h.assertTrue(iron.isCorrectToolForDrops(s), "an iron pickaxe is not enough for " + b);
            h.assertTrue(!stone.isCorrectToolForDrops(s) && !wood.isCorrectToolForDrops(s), "a stone or wooden pickaxe is enough for " + b);
            h.assertTrue(s.is(blockTag("minecraft", "mineable/pickaxe")) && s.is(blockTag("minecraft", "needs_iron_tool")), b + " tags");
            h.assertTrue(!s.is(blockTag("minecraft", "needs_diamond_tool")) && !s.is(blockTag("minecraft", "needs_stone_tool")), b + " is in another tool tier");
        }
        // the sand is dug with a shovel, needs no tool, falls like sand
        BlockState sand = BwrBlocks.MONAZITE_SAND.get().defaultBlockState();
        h.assertTrue(sand.is(blockTag("minecraft", "mineable/shovel")) && !sand.requiresCorrectToolForDrops(), "monazite sand is not a plain shovel block");
        h.assertTrue(BwrBlocks.MONAZITE_SAND.get() instanceof net.minecraft.world.level.block.FallingBlock, "monazite sand does not fall");
        ServerLevel l = h.getLevel();
        BlockState ore = BwrBlocks.THORIUM_ORE.get().defaultBlockState(), deep = BwrBlocks.DEEPSLATE_THORIUM_ORE.get().defaultBlockState();
        h.assertTrue(ore.getDestroySpeed(l, BlockPos.ZERO) == Blocks.IRON_ORE.defaultBlockState().getDestroySpeed(l, BlockPos.ZERO)
                        && deep.getDestroySpeed(l, BlockPos.ZERO) == Blocks.DEEPSLATE_IRON_ORE.defaultBlockState().getDestroySpeed(l, BlockPos.ZERO),
                "ore hardness is not iron's");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void conventionTagsAreSet(GameTestHelper h) {
        Object[][] blockTags = {
                {"c", "ores/thorium", new String[] {"thorium_ore", "deepslate_thorium_ore", "monazite_sand"}},
                {"c", "ores_in_ground/stone", new String[] {"thorium_ore"}},
                {"c", "ores_in_ground/deepslate", new String[] {"deepslate_thorium_ore"}},
                {"c", "storage_blocks/thorium", new String[] {"thorium_block"}},
                {"c", "storage_blocks/raw_thorium", new String[] {"raw_thorium_block"}},
        };
        for (Object[] row : blockTags) {
            for (String name : (String[]) row[2]) {
                h.assertTrue(BuiltInRegistries.BLOCK.get(bwr(name)).defaultBlockState().is(blockTag((String) row[0], (String) row[1])), name + " is not in block tag " + row[1]);
                h.assertTrue(new ItemStack(BuiltInRegistries.ITEM.get(bwr(name))).is(itemTag((String) row[0], (String) row[1])), name + " is not in item tag " + row[1]);
            }
        }
        h.assertTrue(!BwrBlocks.MONAZITE_SAND.get().defaultBlockState().is(blockTag("c", "ores_in_ground/stone")), "the sand is in stone");
        h.assertTrue(!BwrBlocks.THORIUM_BLOCK.get().defaultBlockState().is(blockTag("c", "ores/thorium")), "the metal block is an ore");
        h.assertTrue(new ItemStack(BwrItems.RAW_THORIUM.get()).is(itemTag("c", "raw_materials/thorium")), "raw thorium is not c:raw_materials/thorium");
        h.assertTrue(new ItemStack(BwrItems.THORIUM_INGOT.get()).is(itemTag("c", "ingots/thorium")), "the ingot is not c:ingots/thorium");
        h.assertTrue(new ItemStack(BwrItems.THORIUM_NUGGET.get()).is(itemTag("c", "nuggets/thorium")), "the nugget is not c:nuggets/thorium");
        h.assertTrue(!new ItemStack(BwrItems.THORIUM_INGOT.get()).is(itemTag("c", "ingots/uranium")), "thorium is a uranium ingot (the uranium route would take it)");
        h.assertTrue(!new ItemStack(BwrItems.RAW_THORIUM.get()).is(itemTag("c", "raw_materials/uranium")), "raw thorium is raw uranium");
        h.succeed();
    }

    // ------------------------------------------------------------------------------------------------------------
    // recipes

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void recipesSmeltBlastStoreAndMakeAFlintAndSteel(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        var manager = l.getServer().getRecipeManager();
        ItemStack ingot = new ItemStack(BwrItems.THORIUM_INGOT.get());
        for (Item input : new Item[] {BwrItems.RAW_THORIUM.get(), BwrItems.THORIUM_ORE.get(), BwrItems.DEEPSLATE_THORIUM_ORE.get()}) {
            var smelt = manager.getRecipeFor(RecipeType.SMELTING, new SingleRecipeInput(new ItemStack(input)), l);
            h.assertTrue(smelt.isPresent() && smelt.get().value().getResultItem(l.registryAccess()).is(ingot.getItem()), "no smelting recipe " + input + " -> ingot");
            var blast = manager.getRecipeFor(RecipeType.BLASTING, new SingleRecipeInput(new ItemStack(input)), l);
            h.assertTrue(blast.isPresent() && blast.get().value().getResultItem(l.registryAccess()).is(ingot.getItem()), "no blasting recipe " + input + " -> ingot");
            h.assertTrue(blast.get().value().getCookingTime() < smelt.get().value().getCookingTime(), "the blast furnace is not faster");
        }
        h.assertTrue(manager.getRecipeFor(RecipeType.SMELTING, new SingleRecipeInput(new ItemStack(BwrItems.RAW_URANIUM.get())), l)
                .map(r -> !r.value().getResultItem(l.registryAccess()).is(ingot.getItem())).orElse(true), "raw uranium smelts to thorium");

        Object[][] pairs = {
                {"raw_thorium_block", BwrItems.RAW_THORIUM.get(), BwrItems.RAW_THORIUM_BLOCK.get(), "raw_thorium_from_block"},
                {"thorium_block", BwrItems.THORIUM_INGOT.get(), BwrItems.THORIUM_BLOCK.get(), "thorium_ingot_from_block"},
        };
        for (Object[] p : pairs) {
            Item small = (Item) p[1], big = (Item) p[2];
            var pack = manager.byKey(bwr((String) p[0]));
            h.assertTrue(pack.isPresent(), "no recipe " + p[0]);
            var in = NonNullList.withSize(9, ItemStack.EMPTY);
            for (int i = 0; i < 9; i++) {
                in.set(i, new ItemStack(small));
            }
            CraftingRecipe r = (CraftingRecipe) pack.get().value();
            h.assertTrue(r.matches(CraftingInput.of(3, 3, in), l) && r.getResultItem(l.registryAccess()).is(big), "nine do not make the block: " + p[0]);
            in.set(8, ItemStack.EMPTY);
            h.assertTrue(!r.matches(CraftingInput.of(3, 3, in), l), "eight made the block: " + p[0]);
            var un = manager.byKey(bwr((String) p[3]));
            h.assertTrue(un.isPresent(), "no recipe " + p[3]);
            var one = NonNullList.withSize(9, ItemStack.EMPTY);
            one.set(4, new ItemStack(big));
            CraftingRecipe u = (CraftingRecipe) un.get().value();
            var result = u.getResultItem(l.registryAccess());
            h.assertTrue(u.matches(CraftingInput.of(3, 3, one), l) && result.is(small) && result.getCount() == 9, "the block does not make nine: " + p[3]);
        }
        // nine nuggets and an ingot, both ways
        var nine = NonNullList.withSize(9, new ItemStack(BwrItems.THORIUM_NUGGET.get()));
        CraftingRecipe toIngot = (CraftingRecipe) manager.byKey(bwr("thorium_ingot")).orElseThrow().value();
        h.assertTrue(toIngot.matches(CraftingInput.of(3, 3, nine), l) && toIngot.getResultItem(l.registryAccess()).is(BwrItems.THORIUM_INGOT.get()), "nine nuggets do not make an ingot");
        var oneIngot = NonNullList.withSize(9, ItemStack.EMPTY);
        oneIngot.set(0, new ItemStack(BwrItems.THORIUM_INGOT.get()));
        CraftingRecipe toNuggets = (CraftingRecipe) manager.byKey(bwr("thorium_nugget")).orElseThrow().value();
        h.assertTrue(toNuggets.matches(CraftingInput.of(3, 3, oneIngot), l) && toNuggets.getResultItem(l.registryAccess()).getCount() == 9, "an ingot does not make nine nuggets");
        // the rare earth concentrate has a use: with an iron ingot it makes a flint and steel (ferrocerium)
        CraftingRecipe striker = (CraftingRecipe) manager.byKey(bwr("ferrocerium_flint_and_steel")).orElseThrow().value();
        var pair = NonNullList.withSize(2, ItemStack.EMPTY);
        pair.set(0, new ItemStack(Items.IRON_INGOT));
        pair.set(1, new ItemStack(BwrItems.RARE_EARTH_CONCENTRATE.get()));
        h.assertTrue(striker.matches(CraftingInput.of(2, 1, pair), l) && striker.getResultItem(l.registryAccess()).is(Items.FLINT_AND_STEEL), "an iron ingot and rare earth concentrate do not make a flint and steel");
        h.succeed();
    }

    // ------------------------------------------------------------------------------------------------------------
    // drops

    private static List<ItemStack> mine(ServerLevel l, BlockState state, ItemStack tool) {
        LootParams params = new LootParams.Builder(l).withParameter(LootContextParams.ORIGIN, Vec3.ZERO)
                .withParameter(LootContextParams.BLOCK_STATE, state).withParameter(LootContextParams.TOOL, tool).create(LootContextParamSets.BLOCK);
        return l.getServer().reloadableRegistries().getLootTable(state.getBlock().getLootTable()).getRandomItems(params);
    }

    private static ItemStack enchanted(ServerLevel l, Item item, ResourceKey<net.minecraft.world.item.enchantment.Enchantment> key, int level) {
        ItemStack stack = new ItemStack(item);
        stack.enchant(l.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(key), level);
        return stack;
    }

    private static int count(List<ItemStack> drops, Item item) {
        return drops.stream().filter(s -> s.is(item)).mapToInt(ItemStack::getCount).sum();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void oresDropRawThoriumWithFortuneAndThemselvesWithSilkTouch(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        ItemStack iron = new ItemStack(Items.IRON_PICKAXE);
        ItemStack silk = enchanted(l, Items.IRON_PICKAXE, Enchantments.SILK_TOUCH, 1);
        ItemStack fortune = enchanted(l, Items.IRON_PICKAXE, Enchantments.FORTUNE, 3);
        ItemStack shovel = new ItemStack(Items.IRON_SHOVEL);
        for (Block ore : new Block[] {BwrBlocks.THORIUM_ORE.get(), BwrBlocks.DEEPSLATE_THORIUM_ORE.get(), BwrBlocks.MONAZITE_SAND.get()}) {
            BlockState s = ore.defaultBlockState();
            ItemStack tool = ore == BwrBlocks.MONAZITE_SAND.get() ? shovel : iron;
            for (int i = 0; i < 40; i++) {
                var plain = mine(l, s, tool);
                h.assertTrue(count(plain, BwrItems.RAW_THORIUM.get()) == 1, ore + " without Fortune gave " + plain);
                var sk = mine(l, s, silk);
                h.assertTrue(sk.size() == 1 && sk.get(0).is(ore.asItem()) && sk.get(0).getCount() == 1, ore + " with Silk Touch gave " + sk + " (the byproducts must be skipped)");
            }
            int total = 0, most = 0, draws = 3000;
            for (int i = 0; i < draws; i++) {
                int raw = count(mine(l, s, fortune), BwrItems.RAW_THORIUM.get());
                total += raw;
                most = Math.max(most, raw);
            }
            double mean = total / (double) draws;
            // Fortune III is the vanilla ore bonus: 1 + (0 + 0 + 1 + 2 + 3) / 5 = 2.2 on average, 1 to 4 each
            h.assertTrue(mean > 1.95 && mean < 2.45 && most == 4, ore + " Fortune III: mean " + mean + ", most " + most);
        }
        for (Block b : new Block[] {BwrBlocks.RAW_THORIUM_BLOCK.get(), BwrBlocks.THORIUM_BLOCK.get()}) {
            for (ItemStack t : new ItemStack[] {iron, silk, fortune}) {
                var drops = mine(l, b.defaultBlockState(), t);
                h.assertTrue(drops.size() == 1 && drops.get(0).is(b.asItem()) && drops.get(0).getCount() == 1, b + " dropped " + drops);
            }
        }
        h.succeed();
    }

    /** The share of {@code draws} mines that gave at least one of {@code item}. */
    private static double share(ServerLevel l, BlockState s, ItemStack tool, Item item, int draws) {
        int hits = 0;
        for (int i = 0; i < draws; i++) {
            if (count(mine(l, s, tool), item) > 0) hits++;
        }
        return hits / (double) draws;
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void byproductsAreChanceBasedFortuneHelpsAndSilkTouchSkipsThem(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        ItemStack iron = new ItemStack(Items.IRON_PICKAXE), shovel = new ItemStack(Items.IRON_SHOVEL);
        ItemStack silk = enchanted(l, Items.IRON_PICKAXE, Enchantments.SILK_TOUCH, 1);
        ItemStack fortune = enchanted(l, Items.IRON_PICKAXE, Enchantments.FORTUNE, 3);
        ItemStack silkShovel = enchanted(l, Items.IRON_SHOVEL, Enchantments.SILK_TOUCH, 1);
        ItemStack fortuneShovel = enchanted(l, Items.IRON_SHOVEL, Enchantments.FORTUNE, 3);
        int draws = 6000;
        Item rare = BwrItems.RARE_EARTH_CONCENTRATE.get();
        for (Block ore : new Block[] {BwrBlocks.THORIUM_ORE.get(), BwrBlocks.DEEPSLATE_THORIUM_ORE.get()}) {
            BlockState s = ore.defaultBlockState();
            Object[][] rows = {
                    // item, unenchanted chance, Fortune III chance
                    {rare, 0.30, 0.60}, {Items.BONE_MEAL, 0.15, 0.30}, {BwrItems.RAW_URANIUM.get(), 0.02, 0.05},
                    // lead (the end of the thorium series) and radium (which it passes through): lead is common, radium rare
                    {BwrItems.LEAD_NUGGET.get(), 0.10, 0.20}, {BwrItems.RADIUM.get(), 0.01, 0.025}};
            for (Object[] row : rows) {
                Item item = (Item) row[0];
                double plain = share(l, s, iron, item, draws), fort = share(l, s, fortune, item, draws);
                double tol = item == BwrItems.RADIUM.get() ? 0.012 : 0.035;
                h.assertTrue(Math.abs(plain - (double) row[1]) < tol, ore + ": " + item + " drops " + plain + " of the time, not about " + row[1]);
                h.assertTrue(Math.abs(fort - (double) row[2]) < tol, ore + ": with Fortune III " + item + " drops " + fort + " of the time, not about " + row[2]);
                h.assertTrue(fort > plain, ore + ": Fortune did not raise the chance of " + item);
                for (int i = 0; i < 500; i++) {
                    h.assertTrue(count(mine(l, s, silk), item) == 0, ore + ": Silk Touch gave " + item);
                }
            }
        }
        // monazite sand: the rare earths, and iron from the ilmenite that lies with it; nothing else but raw thorium
        BlockState sand = BwrBlocks.MONAZITE_SAND.get().defaultBlockState();
        double ree = share(l, sand, shovel, rare, draws), nugget = share(l, sand, shovel, Items.IRON_NUGGET, draws);
        h.assertTrue(Math.abs(ree - 0.25) < 0.035 && Math.abs(nugget - 0.30) < 0.035, "monazite sand gives rare earths " + ree + " and iron nuggets " + nugget + " of the time");
        h.assertTrue(share(l, sand, fortuneShovel, rare, draws) > ree + 0.1, "Fortune did not help with the sand's rare earths");
        for (int i = 0; i < 500; i++) {
            var sk = mine(l, sand, silkShovel);
            h.assertTrue(sk.size() == 1 && sk.get(0).is(BwrItems.MONAZITE_SAND.get()), "monazite sand with Silk Touch gave " + sk);
            for (var d : mine(l, sand, shovel)) {
                h.assertTrue(d.is(BwrItems.RAW_THORIUM.get()) || d.is(rare) || d.is(Items.IRON_NUGGET), "monazite sand gave " + d);
            }
        }
        // the sand carries no lead and no radium: those are the ore's
        for (int i = 0; i < 2000; i++) {
            var d = mine(l, sand, fortuneShovel);
            h.assertTrue(count(d, BwrItems.LEAD_NUGGET.get()) == 0 && count(d, BwrItems.RADIUM.get()) == 0, "monazite sand gave lead or radium: " + d);
        }
        // nothing else comes out of the ores: the raw thorium and the byproducts above
        for (Block ore : new Block[] {BwrBlocks.THORIUM_ORE.get(), BwrBlocks.DEEPSLATE_THORIUM_ORE.get()}) {
            for (int i = 0; i < 2000; i++) {
                for (var d : mine(l, ore.defaultBlockState(), fortune)) {
                    h.assertTrue(d.is(BwrItems.RAW_THORIUM.get()) || d.is(rare) || d.is(Items.BONE_MEAL) || d.is(BwrItems.RAW_URANIUM.get())
                            || d.is(BwrItems.LEAD_NUGGET.get()) || d.is(BwrItems.RADIUM.get()), ore + " gave " + d);
                }
            }
        }
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void radiumIsRadioactiveWhenCarriedAndLeadIsTaggedForOtherMods(GameTestHelper h) {
        var player = h.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
        Vec3 beside = player.position().add(1, 1, 0);
        h.assertTrue(dev.bwr.mod.radiation.RadiumItem.carriedGammaWatts(player) == 0 && dev.bwr.mod.radiation.RadiumItem.doseRateOf(player, beside) == 0, "an empty-handed player is a source");
        player.getInventory().add(new ItemStack(BwrItems.RADIUM.get(), 1));
        double one = dev.bwr.mod.radiation.RadiumItem.doseRateOf(player, beside);
        h.assertTrue(dev.bwr.mod.radiation.RadiumItem.carriedGammaWatts(player) > 0 && one > 0, "carried radium is not a gamma source (" + one + " mSv/h)");
        player.getInventory().add(new ItemStack(BwrItems.RADIUM.get(), 3));
        double four = dev.bwr.mod.radiation.RadiumItem.doseRateOf(player, beside);
        h.assertTrue(Math.abs(four / one - 4.0) < 1e-9, "four radium read " + four / one + " times one");
        // farther away it reads less (inverse square), and it is a point source the survey meter sees through the registered provider
        h.assertTrue(dev.bwr.mod.radiation.RadiumItem.doseRateOf(player, player.position().add(6, 1, 0)) < one, "radium does not fall with distance");
        h.assertTrue(dev.bwr.mod.radiation.RadiumItem.doseRateOf(player, beside) > 0.01 && one < 100, "the reading is not plausible: " + one + " mSv/h at one block");
        // lead: a nugget and an ingot, tagged the way other mods look for them, and not radioactive
        for (String[] t : new String[][] {{"lead_nugget", "nuggets/lead"}, {"lead_ingot", "ingots/lead"}}) {
            h.assertTrue(new ItemStack(BuiltInRegistries.ITEM.get(bwr(t[0]))).is(itemTag("c", t[1])), t[0] + " is not c:" + t[1]);
        }
        ServerLevel l = h.getLevel();
        var manager = l.getServer().getRecipeManager();
        var nine = NonNullList.withSize(9, new ItemStack(BwrItems.LEAD_NUGGET.get()));
        CraftingRecipe toIngot = (CraftingRecipe) manager.byKey(bwr("lead_ingot")).orElseThrow().value();
        h.assertTrue(toIngot.matches(CraftingInput.of(3, 3, nine), l) && toIngot.getResultItem(l.registryAccess()).is(BwrItems.LEAD_INGOT.get()), "nine lead nuggets do not make an ingot");
        var one1 = NonNullList.withSize(9, ItemStack.EMPTY);
        one1.set(0, new ItemStack(BwrItems.LEAD_INGOT.get()));
        CraftingRecipe toNuggets = (CraftingRecipe) manager.byKey(bwr("lead_nugget")).orElseThrow().value();
        h.assertTrue(toNuggets.matches(CraftingInput.of(3, 3, one1), l) && toNuggets.getResultItem(l.registryAccess()).getCount() == 9, "a lead ingot does not make nine nuggets");
        h.succeed();
    }

    // ------------------------------------------------------------------------------------------------------------
    // the fuel fabricator's thorium route

    private static final BlockPos FAB = new BlockPos(47_300, 210, 47_300);

    private static FuelFabricatorBlockEntity place(ServerLevel l, BlockPos p) {
        l.getChunk(p);
        l.removeBlock(p, false);
        l.setBlock(p, BwrBlocks.FUEL_FABRICATOR.get().defaultBlockState(), 2);
        return (FuelFabricatorBlockEntity) l.getBlockEntity(p);
    }

    private static void tick(ServerLevel l, BlockPos p, FuelFabricatorBlockEntity be) {
        be.energy().receiveEnergy(10_000, false);
        FuelFabricatorBlockEntity.serverTick(l, p, l.getBlockState(p), be);
    }

    private static final Object[][] COSTS = {
            // target, fuel type, natural uranium, thorium, depleted uranium back
            {"thorium_lean", "thorium_lean", 18, 4, 16},
            {"thorium_standard", "thorium", 26, 4, 25},
            {"thorium_rich", "thorium_rich", 43, 3, 41},
    };

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void thoriumCostsFollowTheDriverFractionAndTheMassBalance(GameTestHelper h) {
        for (Object[] row : COSTS) {
            String target = (String) row[0];
            h.assertTrue(FuelItemRoute.isTarget(target) && FuelItemRoute.isThoriumTarget(target) && !FuelItemRoute.isMoxTarget(target), target + " is not a thorium target");
            var cost = FuelItemRoute.cost(target);
            h.assertTrue(cost != null && cost.fuelType().equals(row[1]), target + " makes " + (cost == null ? null : cost.fuelType()));
            h.assertTrue(cost.isThorium() && cost.ingots() == (int) row[2] && cost.thoriumIngots() == (int) row[3] && cost.tails() == (int) row[4] && cost.steel() == 4,
                    target + " costs " + cost);
            h.assertTrue(cost.workTicks() == FuelItemRoute.BASE_WORK_TICKS + FuelItemRoute.TICKS_PER_INGOT * cost.ingots(), target + " work time");
            // the numbers are the core's mass balance of the fuel type's own driver fraction, nothing else
            double driver = FuelTypes.byNameOrPreset((String) row[1]).nominalEnrichmentWeightFraction();
            h.assertTrue(cost.ingots() == ThoriumMassBalance.feedIngots(FuelItemRoute.PRODUCT_INGOTS, driver)
                    && cost.tails() == ThoriumMassBalance.tailsIngots(FuelItemRoute.PRODUCT_INGOTS, driver)
                    && cost.thoriumIngots() == ThoriumMassBalance.thoriumIngots(FuelItemRoute.PRODUCT_INGOTS, driver), target + " does not follow the mass balance");
        }
        // the driver fractions are the existing 2, 3 and 5 percent (the fuel physics are not changed by this route)
        h.assertTrue(FuelTypes.byNameOrPreset("thorium_lean").nominalEnrichmentWeightFraction() == 0.02
                && FuelTypes.byNameOrPreset("thorium").nominalEnrichmentWeightFraction() == 0.03
                && FuelTypes.byNameOrPreset("thorium_rich").nominalEnrichmentWeightFraction() == 0.05, "a thorium grade's driver fraction is not 2, 3 and 5 percent");
        // the ladder, its indexes and the uranium ladder are as they were; the selector is the ladder and then the thorium grades
        h.assertTrue(FuelItemRoute.LADDER.size() == 10 && FuelItemRoute.SELECTOR.size() == 13 && FuelItemRoute.SELECTOR.subList(0, 10).equals(FuelItemRoute.LADDER)
                && FuelItemRoute.SELECTOR.subList(10, 13).equals(FuelItemRoute.THORIUM_TARGETS), "the selector is not the ladder then the thorium grades");
        h.assertTrue(!FuelItemRoute.isTarget("thorium") && FuelItemRoute.cost("thorium") == null, "the fuel's own name became a target");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void fabricatorMakesThoriumFuelFromThoriumUraniumAndSteel(GameTestHelper h) {
        FuelItemRoute.overrideInputsForTest(null, stack -> stack.is(Items.IRON_INGOT));   // steel is the only item another mod would give
        try {
            ServerLevel l = h.getLevel();
            int at = 0;
            for (Object[] row : COSTS) {
                String target = (String) row[0];
                var cost = FuelItemRoute.cost(target);
                BlockPos p = FAB.offset(4 * at, 0, 0);
                var be = place(l, p);
                h.assertTrue(be.setTarget(target) && be.isThoriumTarget() && !be.isMoxTarget(), "target " + target + " refused");
                var up = l.getCapability(Capabilities.ItemHandler.BLOCK, p, Direction.UP);
                int steelRest = up.insertItem(FabricatorItemView.STEEL, new ItemStack(Items.IRON_INGOT, cost.steel() + 2), false).getCount();
                h.assertTrue(steelRest == 0, "steel refused");
                // a hopper feeds both kinds through the one slot; raw thorium counts as an ingot (the first one is raw)
                int extraU = 3, extraT = 1, fedU = 0, fedT = 0, ticks = 0;
                while (ticks < 6000 && be.output().getStackInSlot(0).isEmpty()) {
                    if (fedT < cost.thoriumIngots() + extraT && up.insertItem(FabricatorItemView.URANIUM, new ItemStack(fedT == 0 ? BwrItems.RAW_THORIUM.get() : BwrItems.THORIUM_INGOT.get()), false).isEmpty()) {
                        fedT++;
                    }
                    if (fedU < cost.ingots() + extraU && up.insertItem(FabricatorItemView.URANIUM, new ItemStack(fedU % 2 == 0 ? BwrItems.URANIUM_INGOT.get() : BwrItems.RAW_URANIUM.get()), false).isEmpty()) {
                        fedU++;
                    }
                    tick(l, p, be);
                    ticks++;
                }
                var assembly = be.output().getStackInSlot(0);
                h.assertTrue(assembly.is(BwrItems.FUEL_ASSEMBLY.get()), target + ": no assembly after " + ticks + " ticks (fed " + fedT + " thorium, " + fedU + " uranium)");
                var data = FuelAssemblies.dataOf(assembly);
                var type = FuelTypes.byNameOrPreset((String) row[1]);
                h.assertTrue(data.fuelTypeName().equals(row[1]), target + ": stamped " + data.fuelTypeName());
                h.assertTrue(data.equals(FuelAssemblyData.fresh(type)), target + ": not the data the other routes stamp on a fresh bundle");
                h.assertTrue(data.enrichmentWeightFraction() == type.nominalEnrichmentWeightFraction(), target + ": enrichment");
                h.assertTrue(ticks >= cost.workTicks(), target + ": made in " + ticks + " ticks, less than its work time " + cost.workTicks());
                // exactly the cost was consumed: what is left is what was fed over it
                ItemStack slot = be.uraniumIn().getStackInSlot(0);
                boolean slotHoldsThorium = FuelItemRoute.thoriumIngots(slot) > 0;
                int thoriumLeft = be.thoriumHeldIngots() + (slotHoldsThorium ? slot.getCount() : 0);
                int uraniumLeft = be.uraniumHeldUnits() / 2 + (slotHoldsThorium ? 0 : slot.getCount());
                h.assertTrue(fedT == cost.thoriumIngots() + extraT && fedU == cost.ingots() + extraU, target + ": fed " + fedT + " thorium and " + fedU + " uranium");
                h.assertTrue(thoriumLeft == extraT, target + ": thorium left " + thoriumLeft + ", wanted " + extraT);
                h.assertTrue(uraniumLeft == extraU, target + ": uranium left " + uraniumLeft + ", wanted " + extraU);
                h.assertTrue(be.steelIn().getStackInSlot(0).getCount() == 2, target + ": steel left " + be.steelIn().getStackInSlot(0).getCount());
                // the depleted uranium comes out underneath, exactly the cost's
                var down = l.getCapability(Capabilities.ItemHandler.BLOCK, p, Direction.DOWN);
                int tails = 0;
                for (int i = 0; i < 20 && be.tailsHeld() > 0; i++) {
                    tails += down.extractItem(FabricatorItemView.TAILS, 64, false).getCount();
                    tick(l, p, be);
                }
                h.assertTrue(tails == cost.tails(), target + ": depleted uranium " + tails + ", wanted " + cost.tails());
                be.removalDrops();
                l.removeBlock(p, false);
                at++;
            }
            h.succeed();
        } finally {
            FuelItemRoute.overrideInputsForTest(null, null);
        }
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void fabricatorSlotTakesTheRightKindsAndNeverJams(GameTestHelper h) {
        FuelItemRoute.overrideInputsForTest(null, stack -> stack.is(Items.IRON_INGOT));
        try {
            ServerLevel l = h.getLevel();
            BlockPos p = FAB.offset(0, 0, 8);
            var be = place(l, p);
            var in = be.uraniumIn();
            // off a thorium target thorium is refused, as plutonium and depleted uranium are
            for (String target : new String[] {"leu", "mox_standard"}) {
                be.setTarget(target);
                h.assertTrue(!in.isItemValid(0, new ItemStack(BwrItems.THORIUM_INGOT.get())) && !in.isItemValid(0, new ItemStack(BwrItems.RAW_THORIUM.get())), target + " takes thorium");
            }
            be.setTarget("leu");
            h.assertTrue(in.isItemValid(0, new ItemStack(BwrItems.URANIUM_INGOT.get())), "the uranium route stopped taking uranium");
            be.setTarget("thorium_rich");   // 3 thorium, 43 uranium: the stores hold twice that
            for (Item wrong : new Item[] {BwrItems.PLUTONIUM_NUGGET.get(), BwrItems.DEPLETED_URANIUM.get(), Items.IRON_INGOT, BwrItems.THORIUM_NUGGET.get(), BwrItems.THORIUM_ORE.get()}) {
                h.assertTrue(!in.isItemValid(0, new ItemStack(wrong)), "the thorium route takes " + wrong);
            }
            var up = l.getCapability(Capabilities.ItemHandler.BLOCK, p, Direction.UP);
            // fill the thorium store past its cap with a stack: only up to the cap goes in, and the slot is then free for uranium
            ItemStack rest = up.insertItem(FabricatorItemView.URANIUM, new ItemStack(BwrItems.THORIUM_INGOT.get(), 64), false);
            h.assertTrue(rest.getCount() == 64 - 6, "the thorium slot took " + (64 - rest.getCount()) + " of a stack (cap is 6)");
            // the slot holds thorium: uranium waits until the machine has taken the thorium in
            h.assertTrue(up.insertItem(FabricatorItemView.URANIUM, new ItemStack(BwrItems.URANIUM_INGOT.get()), true).getCount() == 1, "uranium went into a slot holding thorium");
            tick(l, p, be);
            h.assertTrue(be.thoriumHeldIngots() == 6 && in.getStackInSlot(0).isEmpty(), "the machine did not take the thorium in: " + be.thoriumHeldIngots());
            h.assertTrue(up.insertItem(FabricatorItemView.URANIUM, new ItemStack(BwrItems.THORIUM_INGOT.get()), true).getCount() == 1, "a full thorium store took more");
            ItemStack restU = up.insertItem(FabricatorItemView.URANIUM, new ItemStack(BwrItems.URANIUM_INGOT.get(), 64), false);
            h.assertTrue(restU.isEmpty(), "the uranium slot took " + (64 - restU.getCount()) + " of a stack of 64 (its store holds up to 86 ingots)");
            be.removalDrops();
            l.removeBlock(p, false);
            h.succeed();
        } finally {
            FuelItemRoute.overrideInputsForTest(null, null);
        }
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void thoriumStateSurvivesSaveAndReloadAndOldSavesLoadClean(GameTestHelper h) {
        FuelItemRoute.overrideInputsForTest(null, stack -> stack.is(Items.IRON_INGOT));
        try {
            ServerLevel l = h.getLevel();
            BlockPos p = FAB.offset(0, 0, 16), q = FAB.offset(4, 0, 16);
            var be = place(l, p);
            // an untouched fabricator writes no thorium key, so an old save and a new one of the default look the same
            CompoundTag clean = be.saveWithFullMetadata(l.registryAccess());
            h.assertTrue(!clean.contains("ThoriumIngots") && !clean.contains("Target"), "an idle fabricator writes thorium or target keys: " + clean.getAllKeys());
            h.assertTrue(be.setTarget("thorium_standard"), "target refused");
            var up = l.getCapability(Capabilities.ItemHandler.BLOCK, p, Direction.UP);
            up.insertItem(FabricatorItemView.URANIUM, new ItemStack(BwrItems.THORIUM_INGOT.get(), 3), false);
            tick(l, p, be);   // the machine takes them in
            up.insertItem(FabricatorItemView.URANIUM, new ItemStack(BwrItems.URANIUM_INGOT.get(), 5), false);
            tick(l, p, be);
            h.assertTrue(be.thoriumHeldIngots() == 3 && be.uraniumHeldUnits() == 10, "held " + be.thoriumHeldIngots() + " thorium, " + be.uraniumHeldUnits() + " uranium units");
            CompoundTag saved = be.saveWithFullMetadata(l.registryAccess());
            h.assertTrue(saved.getInt("ThoriumIngots") == 3 && saved.getString("Target").equals("thorium_standard"), "the save lacks the thorium state: " + saved);
            // a fresh block entity reads it back
            var fresh = place(l, q);
            fresh.loadWithComponents(saved, l.registryAccess());
            h.assertTrue(fresh.target().equals("thorium_standard") && fresh.thoriumHeldIngots() == 3 && fresh.uraniumHeldUnits() == 10 && fresh.isThoriumTarget(),
                    "thorium state did not load: " + fresh.target() + " " + fresh.thoriumHeldIngots());
            h.assertTrue(fresh.itemCost().thoriumIngots() == 4, "cost did not follow the loaded target");
            // an old save (no thorium key, any target) loads with nothing held
            CompoundTag old = saved.copy();
            old.remove("ThoriumIngots");
            old.remove("Target");
            fresh.loadWithComponents(old, l.registryAccess());
            h.assertTrue(fresh.thoriumHeldIngots() == 0 && fresh.target().equals(FuelItemRoute.DEFAULT_TARGET) && !fresh.isThoriumTarget(), "an old save came up with thorium state");
            // breaking the machine gives the thorium back as ingots
            var drops = be.removalDrops();
            int ingots = drops.stream().filter(s -> s.is(BwrItems.THORIUM_INGOT.get())).mapToInt(ItemStack::getCount).sum();
            h.assertTrue(ingots == 3, "the broken fabricator gave back " + ingots + " thorium ingots");
            l.removeBlock(p, false);
            fresh.removalDrops();
            l.removeBlock(q, false);
            h.succeed();
        } finally {
            FuelItemRoute.overrideInputsForTest(null, null);
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // the datapack: the biome table, the veins' independence and the server option

    private static final String[] GROUPS = {"highland", "placer", "lowland", "ocean"};

    private static UraniumVeinConfig veinConfig(ServerLevel l, String name) {
        var configured = l.registryAccess().registryOrThrow(Registries.CONFIGURED_FEATURE);
        var holder = configured.getHolderOrThrow(ResourceKey.create(Registries.CONFIGURED_FEATURE, bwr(name)));
        return (UraniumVeinConfig) holder.value().config();
    }

    private static Holder<PlacedFeature> placed(ServerLevel l, String path) {
        return l.registryAccess().registryOrThrow(Registries.PLACED_FEATURE).getHolderOrThrow(ResourceKey.create(Registries.PLACED_FEATURE, bwr(path)));
    }

    private static boolean has(Holder<Biome> biome, Holder<PlacedFeature> feature) {
        var steps = biome.value().getGenerationSettings().features();
        return steps.size() > GenerationStep.Decoration.UNDERGROUND_ORES.ordinal() && steps.get(GenerationStep.Decoration.UNDERGROUND_ORES.ordinal()).contains(feature);
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void biomeGroupsConcentrateThoriumByGeologyAndAreRarerThanUranium(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        double[] chance = new double[GROUPS.length], grade = new double[GROUPS.length];
        for (int i = 0; i < GROUPS.length; i++) {
            var c = veinConfig(l, "thorium_vein_" + GROUPS[i]);
            chance[i] = c.veinChance();
            grade[i] = c.oreDensity();
            h.assertTrue(c.geometry().equals(veinConfig(l, "thorium_vein_highland").geometry()), GROUPS[i] + " has a different vein shape from highland (the vein would change outline at a biome border)");
            var blob = l.registryAccess().registryOrThrow(Registries.CONFIGURED_FEATURE).get(bwr("thorium_blob_" + GROUPS[i]));
            h.assertTrue(blob != null && blob.feature() instanceof ThoriumOreFeature, GROUPS[i] + " blobs are not the gated ore feature (the server option would not stop them)");
        }
        for (int i = 1; i < GROUPS.length; i++) {
            h.assertTrue(chance[i - 1] > chance[i], "vein chance does not fall from " + GROUPS[i - 1] + " to " + GROUPS[i]);
            h.assertTrue(grade[i - 1] > grade[i], "grade does not fall from " + GROUPS[i - 1] + " to " + GROUPS[i]);
        }
        // rarer than uranium's super veins: the best thorium group has fewer veins than uranium's best, and every thorium group fewer than the uranium group of its rank
        double[] uranium = {veinChanceOf(l, "uranium_vein_arid"), veinChanceOf(l, "uranium_vein_highland"), veinChanceOf(l, "uranium_vein_lowland"), veinChanceOf(l, "uranium_vein_aquatic")};
        h.assertTrue(chance[0] < uranium[0] && chance[0] < uranium[1] && chance[1] < uranium[1] && chance[2] < uranium[2] && chance[3] < uranium[3],
                "thorium veins are not rarer than uranium's: thorium " + java.util.Arrays.toString(chance) + ", uranium " + java.util.Arrays.toString(uranium));
        // a second, independent set: the same cell has a different vein for thorium and for uranium
        var geometryTh = veinConfig(l, "thorium_vein_highland").geometry();
        var geometryU = veinConfig(l, "uranium_vein_highland").geometry();
        h.assertTrue(geometryTh.salt() != geometryU.salt(), "thorium and uranium veins share a salt");
        int same = 0;
        for (int cx = -3; cx <= 3; cx++) {
            for (int cz = -3; cz <= 3; cz++) {
                var a = UraniumVeins.vein(20261022L, cx, cz, geometryTh);
                var b = UraniumVeins.vein(20261022L, cx, cz, geometryU);
                if (a.centreLine[a.centreLine.length / 2][0] == b.centreLine[b.centreLine.length / 2][0]) same++;
            }
        }
        h.assertTrue(same == 0, same + " cells have the same vein for thorium and uranium");

        // the biomes: every ordinary overworld biome is in exactly one thorium group; the nether and the end in none
        var biomes = l.registryAccess().registryOrThrow(Registries.BIOME);
        var overworld = TagKey.create(Registries.BIOME, ResourceLocation.withDefaultNamespace("is_overworld"));
        Set<ResourceKey<Biome>> caves = Set.of(Biomes.DRIPSTONE_CAVES, Biomes.LUSH_CAVES, Biomes.DEEP_DARK);
        Map<String, List<String>> membership = new HashMap<>();
        for (var entry : biomes.holders().toList()) {
            var key = entry.key();
            int groups = 0;
            for (String g : GROUPS) {
                if (has(entry, placed(l, "thorium_vein_" + g))) {
                    groups++;
                    membership.computeIfAbsent(g, k -> new ArrayList<>()).add(key.location().getPath());
                    h.assertTrue(entry.is(TagKey.create(Registries.BIOME, bwr("has_thorium/" + g))), key.location() + " has the " + g + " feature without being in its tag");
                    h.assertTrue(has(entry, placed(l, "thorium_blob_" + g)), key.location() + " has the vein feature of " + g + " but not its blobs");
                }
            }
            if (entry.is(overworld) && !caves.contains(key)) {
                h.assertTrue(groups == 1, key.location() + " is in " + groups + " thorium groups (it must be in exactly one)");
            }
            if (entry.is(TagKey.create(Registries.BIOME, ResourceLocation.withDefaultNamespace("is_nether"))) || entry.is(TagKey.create(Registries.BIOME, ResourceLocation.withDefaultNamespace("is_end")))) {
                h.assertTrue(groups == 0, key.location() + " has thorium");
            }
            // the monazite sand is in beaches and rivers and nowhere else
            boolean sand = has(entry, placed(l, "monazite_sand_placer"));
            boolean beachOrRiver = entry.is(Biomes.BEACH) || entry.is(Biomes.SNOWY_BEACH) || entry.is(Biomes.RIVER) || entry.is(Biomes.FROZEN_RIVER);
            h.assertTrue(sand == beachOrRiver, key.location() + (sand ? " has" : " lacks") + " the monazite sand placer");
        }
        Holder<Biome> plains = biomes.getHolderOrThrow(Biomes.PLAINS), peaks = biomes.getHolderOrThrow(Biomes.JAGGED_PEAKS), ocean = biomes.getHolderOrThrow(Biomes.DEEP_OCEAN);
        Holder<Biome> river = biomes.getHolderOrThrow(Biomes.RIVER), beach = biomes.getHolderOrThrow(Biomes.BEACH), desert = biomes.getHolderOrThrow(Biomes.DESERT);
        h.assertTrue(has(peaks, placed(l, "thorium_vein_highland")), "mountains are not highland");
        h.assertTrue(has(plains, placed(l, "thorium_vein_lowland")) && has(desert, placed(l, "thorium_vein_lowland")), "plains and desert are not lowland");
        h.assertTrue(has(river, placed(l, "thorium_vein_placer")) && has(beach, placed(l, "thorium_vein_placer")), "rivers and beaches are not placer");
        h.assertTrue(has(ocean, placed(l, "thorium_vein_ocean")), "oceans are not ocean");
        System.out.println("THORIUM BIOME TABLE: " + membership.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue().size() + " biomes").sorted().toList());
        h.succeed();
    }

    private static double veinChanceOf(ServerLevel l, String name) {
        return veinConfig(l, name).veinChance();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void serverOptionDecidesWhetherBiomesGetThorium(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        h.assertTrue(GameplayConfig.SPEC.isLoaded() && GameplayConfig.THORIUM_ORE_GENERATION.getDefault(), "the option is missing or off by default");
        var modifiers = l.registryAccess().registryOrThrow(NeoForgeRegistries.Keys.BIOME_MODIFIERS);
        BiomeModifier placer = modifiers.get(bwr("thorium_placer"));
        h.assertTrue(placer instanceof ThoriumBiomeModifier, "bwr:thorium_placer is " + placer);
        var biomes = l.registryAccess().registryOrThrow(Registries.BIOME);
        Holder<Biome> river = biomes.getHolderOrThrow(Biomes.RIVER), plains = biomes.getHolderOrThrow(Biomes.PLAINS);
        Holder<PlacedFeature> vein = placed(l, "thorium_vein_placer"), sand = placed(l, "monazite_sand_placer");
        boolean before = GameplayConfig.THORIUM_ORE_GENERATION.get(), uraniumBefore = GameplayConfig.URANIUM_ORE_GENERATION.get();
        try {
            for (boolean on : new boolean[] {true, false, true}) {
                GameplayConfig.THORIUM_ORE_GENERATION.set(on);
                var builder = ModifiableBiomeInfo.BiomeInfo.Builder.copyOf(river.value().modifiableBiomeInfo().getOriginalBiomeInfo());
                placer.modify(river, BiomeModifier.Phase.ADD, builder);
                var features = builder.getGenerationSettings().getFeatures(GenerationStep.Decoration.UNDERGROUND_ORES);
                h.assertTrue(features.contains(vein) == on && features.contains(sand) == on, "option " + on + ": the river " + (features.contains(vein) ? "got" : "did not get") + " the thorium features");
                var other = ModifiableBiomeInfo.BiomeInfo.Builder.copyOf(plains.value().modifiableBiomeInfo().getOriginalBiomeInfo());
                placer.modify(plains, BiomeModifier.Phase.ADD, other);
                h.assertTrue(!other.getGenerationSettings().getFeatures(GenerationStep.Decoration.UNDERGROUND_ORES).contains(vein), "the placer modifier changed the plains");
            }
            // the two options are independent: uranium off does not stop thorium
            GameplayConfig.URANIUM_ORE_GENERATION.set(false);
            GameplayConfig.THORIUM_ORE_GENERATION.set(true);
            var builder = ModifiableBiomeInfo.BiomeInfo.Builder.copyOf(river.value().modifiableBiomeInfo().getOriginalBiomeInfo());
            placer.modify(river, BiomeModifier.Phase.ADD, builder);
            h.assertTrue(builder.getGenerationSettings().getFeatures(GenerationStep.Decoration.UNDERGROUND_ORES).contains(vein), "uranium off stopped thorium");
        } finally {
            GameplayConfig.THORIUM_ORE_GENERATION.set(before);
            GameplayConfig.URANIUM_ORE_GENERATION.set(uraniumBefore);
        }
        // and with the option on at the start of this server, the live biomes carry the features
        h.assertTrue(has(river, vein) && has(river, sand), "the loaded river has no thorium features although the option is on");
        h.succeed();
    }
}
