package dev.bwr.mod.devtest;

import dev.bwr.mod.config.GameplayConfig;
import dev.bwr.mod.fuel.FabricatorItemView;
import dev.bwr.mod.fuel.FuelAssemblies;
import dev.bwr.mod.fuel.FuelFabricatorBlockEntity;
import dev.bwr.mod.fuel.FuelItemRoute;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.registry.BwrItems;
import dev.bwr.mod.worldgen.UraniumBiomeModifier;
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
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.common.world.BiomeModifier;
import net.neoforged.neoforge.common.world.ModifiableBiomeInfo;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The mod's own uranium: blocks and items, tags, recipes, drops, the fuel fabricator taking our ingot, the shape of the
 * super veins (pure geometry, no world needed), the biome table read back from the loaded datapack, and the server option.
 * What generation really produces in fresh chunks is measured by the worldgen check ({@code :mod:runWorldgenCheck}).
 */
@GameTestHolder("bwruranium")
@PrefixGameTestTemplate(false)
public final class UraniumOreTests {

    private static final String[] BLOCKS = {"uranium_ore", "deepslate_uranium_ore", "raw_uranium_block", "uranium_block"};

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
    public static void blocksAndItemsExistAndNeedAnIronPickaxe(GameTestHelper h) {
        for (String name : BLOCKS) {
            h.assertTrue(BuiltInRegistries.BLOCK.containsKey(bwr(name)), name + " block is not registered");
            h.assertTrue(BuiltInRegistries.ITEM.containsKey(bwr(name)), name + " block item is not registered");
        }
        for (String name : new String[] {"raw_uranium", "uranium_ingot"}) {
            h.assertTrue(BuiltInRegistries.ITEM.containsKey(bwr(name)), name + " is not registered");
        }
        ItemStack iron = new ItemStack(Items.IRON_PICKAXE), stone = new ItemStack(Items.STONE_PICKAXE), wood = new ItemStack(Items.WOODEN_PICKAXE);
        for (Block b : new Block[] {BwrBlocks.URANIUM_ORE.get(), BwrBlocks.DEEPSLATE_URANIUM_ORE.get(), BwrBlocks.RAW_URANIUM_BLOCK.get(), BwrBlocks.URANIUM_BLOCK.get()}) {
            BlockState s = b.defaultBlockState();
            h.assertTrue(s.requiresCorrectToolForDrops(), b + " drops without the right tool");
            h.assertTrue(iron.isCorrectToolForDrops(s), "an iron pickaxe is not enough for " + b);
            h.assertTrue(!stone.isCorrectToolForDrops(s) && !wood.isCorrectToolForDrops(s), "a stone or wooden pickaxe is enough for " + b);
            h.assertTrue(s.is(blockTag("minecraft", "mineable/pickaxe")) && s.is(blockTag("minecraft", "needs_iron_tool")), b + " tags");
            h.assertTrue(!s.is(blockTag("minecraft", "needs_diamond_tool")) && !s.is(blockTag("minecraft", "needs_stone_tool")), b + " is in another tool tier");
        }
        // a vanilla comparison: the ore is as hard as iron ore, the deepslate one as deepslate iron ore
        BlockState ore = BwrBlocks.URANIUM_ORE.get().defaultBlockState(), deep = BwrBlocks.DEEPSLATE_URANIUM_ORE.get().defaultBlockState();
        h.assertTrue(ore.getDestroySpeed(h.getLevel(), BlockPos.ZERO) == Blocks.IRON_ORE.defaultBlockState().getDestroySpeed(h.getLevel(), BlockPos.ZERO)
                && deep.getDestroySpeed(h.getLevel(), BlockPos.ZERO) == Blocks.DEEPSLATE_IRON_ORE.defaultBlockState().getDestroySpeed(h.getLevel(), BlockPos.ZERO),
                "ore hardness is not iron's");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void conventionTagsAreSet(GameTestHelper h) {
        Object[][] blockTags = {
                {"c", "ores/uranium", new String[] {"uranium_ore", "deepslate_uranium_ore"}},
                {"c", "ores_in_ground/stone", new String[] {"uranium_ore"}},
                {"c", "ores_in_ground/deepslate", new String[] {"deepslate_uranium_ore"}},
                {"c", "storage_blocks/uranium", new String[] {"uranium_block"}},
                {"c", "storage_blocks/raw_uranium", new String[] {"raw_uranium_block"}},
        };
        for (Object[] row : blockTags) {
            for (String name : (String[]) row[2]) {
                h.assertTrue(BuiltInRegistries.BLOCK.get(bwr(name)).defaultBlockState().is(blockTag((String) row[0], (String) row[1])), name + " is not in block tag " + row[1]);
                h.assertTrue(new ItemStack(BuiltInRegistries.ITEM.get(bwr(name))).is(itemTag((String) row[0], (String) row[1])), name + " is not in item tag " + row[1]);
            }
        }
        h.assertTrue(!BwrBlocks.URANIUM_BLOCK.get().defaultBlockState().is(blockTag("c", "ores/uranium")), "the metal block is an ore");
        h.assertTrue(new ItemStack(BwrItems.RAW_URANIUM.get()).is(itemTag("c", "raw_materials/uranium")), "raw uranium is not c:raw_materials/uranium");
        h.assertTrue(new ItemStack(BwrItems.URANIUM_INGOT.get()).is(itemTag("c", "ingots/uranium")), "the ingot is not c:ingots/uranium");
        h.assertTrue(!new ItemStack(BwrItems.URANIUM_INGOT.get()).is(itemTag("c", "ingots/depleted_uranium")), "the ingot is depleted uranium");
        h.assertTrue(!new ItemStack(BwrItems.DEPLETED_URANIUM.get()).is(itemTag("c", "ingots/uranium")), "depleted uranium became a uranium ingot");
        h.succeed();
    }

    // ------------------------------------------------------------------------------------------------------------
    // recipes

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void recipesSmeltBlastAndStoreNineToOne(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        var manager = l.getServer().getRecipeManager();
        ItemStack ingot = new ItemStack(BwrItems.URANIUM_INGOT.get());
        for (Item input : new Item[] {BwrItems.RAW_URANIUM.get(), BwrItems.URANIUM_ORE.get(), BwrItems.DEEPSLATE_URANIUM_ORE.get()}) {
            var smelt = manager.getRecipeFor(RecipeType.SMELTING, new SingleRecipeInput(new ItemStack(input)), l);
            h.assertTrue(smelt.isPresent() && smelt.get().value().getResultItem(l.registryAccess()).is(ingot.getItem()), "no smelting recipe " + input + " -> ingot");
            var blast = manager.getRecipeFor(RecipeType.BLASTING, new SingleRecipeInput(new ItemStack(input)), l);
            h.assertTrue(blast.isPresent() && blast.get().value().getResultItem(l.registryAccess()).is(ingot.getItem()), "no blasting recipe " + input + " -> ingot");
            h.assertTrue(blast.get().value().getCookingTime() < smelt.get().value().getCookingTime(), "the blast furnace is not faster");
        }
        h.assertTrue(manager.getRecipeFor(RecipeType.SMELTING, new SingleRecipeInput(new ItemStack(Items.RAW_IRON)), l).map(r -> !r.value().getResultItem(l.registryAccess()).is(ingot.getItem())).orElse(true),
                "raw iron smelts to uranium");

        Object[][] pairs = {
                {"raw_uranium_block", BwrItems.RAW_URANIUM.get(), BwrItems.RAW_URANIUM_BLOCK.get()},
                {"uranium_block", BwrItems.URANIUM_INGOT.get(), BwrItems.URANIUM_BLOCK.get()},
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
            h.assertTrue(r.matches(CraftingInput.of(3, 3, in), l) && r.getResultItem(l.registryAccess()).is(big) && r.getResultItem(l.registryAccess()).getCount() == 1, "nine do not make the block: " + p[0]);
            in.set(8, ItemStack.EMPTY);
            h.assertTrue(!r.matches(CraftingInput.of(3, 3, in), l), "eight made the block: " + p[0]);
            String unpack = ((String) p[0]).equals("uranium_block") ? "uranium_ingot_from_block" : "raw_uranium_from_block";
            var un = manager.byKey(bwr(unpack));
            h.assertTrue(un.isPresent(), "no recipe " + unpack);
            var one = NonNullList.withSize(9, ItemStack.EMPTY);
            one.set(4, new ItemStack(big));
            CraftingRecipe u = (CraftingRecipe) un.get().value();
            var result = u.getResultItem(l.registryAccess());
            h.assertTrue(u.matches(CraftingInput.of(3, 3, one), l) && result.is(small) && result.getCount() == 9, "the block does not make nine: " + unpack);
        }
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

    /** Raw uranium in a set of drops. */
    private static int rawUranium(List<ItemStack> drops) {
        return drops.stream().filter(s -> s.is(BwrItems.RAW_URANIUM.get())).mapToInt(ItemStack::getCount).sum();
    }

    /** True when every drop is raw uranium or one of the byproducts of uranium ore: lead, radium, raw copper, or anything a mod tags as raw vanadium. */
    private static boolean onlyListedByproducts(List<ItemStack> drops) {
        for (ItemStack s : drops) {
            if (!(s.is(BwrItems.RAW_URANIUM.get()) || s.is(BwrItems.LEAD_NUGGET.get()) || s.is(BwrItems.RADIUM.get()) || s.is(Items.RAW_COPPER)
                    || s.is(itemTag("c", "raw_materials/vanadium")))) {
                return false;
            }
        }
        return true;
    }

    private static int count(List<ItemStack> drops, Item item) {
        return drops.stream().filter(s -> s.is(item)).mapToInt(ItemStack::getCount).sum();
    }

    /** The share of {@code draws} mines that gave at least one of {@code item}. */
    private static double share(ServerLevel l, BlockState s, ItemStack tool, Item item, int draws) {
        int hits = 0;
        for (int i = 0; i < draws; i++) {
            if (count(mine(l, s, tool), item) > 0) hits++;
        }
        return hits / (double) draws;
    }

    /**
     * What real uranium ore comes with: lead (the end of the uranium-238 series), radium (in the series), raw copper (deposits such as Olympic Dam)
     * and, from any mod that has it, vanadium (carnotite). Each is its own chance, raised by Fortune and skipped with Silk Touch, and the
     * main drop (one raw uranium, the vanilla ore bonus with Fortune) is untouched.
     */
    @GameTest(template = "empty", timeoutTicks = 400)
    public static void uraniumByproductsAreChanceBasedFortuneHelpsAndSilkTouchSkipsThem(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        ItemStack iron = new ItemStack(Items.IRON_PICKAXE);
        ItemStack silk = enchanted(l, Items.IRON_PICKAXE, Enchantments.SILK_TOUCH, 1);
        ItemStack fortune = enchanted(l, Items.IRON_PICKAXE, Enchantments.FORTUNE, 3);
        int draws = 6000;
        Object[][] rows = {
                // item, chance without Fortune, with Fortune III
                {BwrItems.LEAD_NUGGET.get(), 0.10, 0.20}, {BwrItems.RADIUM.get(), 0.01, 0.025}, {Items.RAW_COPPER, 0.04, 0.08}};
        for (Block ore : new Block[] {BwrBlocks.URANIUM_ORE.get(), BwrBlocks.DEEPSLATE_URANIUM_ORE.get()}) {
            BlockState s = ore.defaultBlockState();
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
            // nothing but the main drop and the listed byproducts ever comes out, with any tool, and Silk Touch gives the block alone
            for (int i = 0; i < 2000; i++) {
                h.assertTrue(onlyListedByproducts(mine(l, s, fortune)) && onlyListedByproducts(mine(l, s, iron)), ore + " gave something that is not listed");
                var sk = mine(l, s, silk);
                h.assertTrue(sk.size() == 1 && sk.get(0).is(ore.asItem()), ore + " with Silk Touch gave " + sk);
            }
        }
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void oresDropRawUraniumWithFortuneAndThemselvesWithSilkTouch(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        ItemStack iron = new ItemStack(Items.IRON_PICKAXE);
        ItemStack silk = enchanted(l, Items.IRON_PICKAXE, Enchantments.SILK_TOUCH, 1);
        ItemStack fortune = enchanted(l, Items.IRON_PICKAXE, Enchantments.FORTUNE, 3);
        for (Block ore : new Block[] {BwrBlocks.URANIUM_ORE.get(), BwrBlocks.DEEPSLATE_URANIUM_ORE.get()}) {
            BlockState s = ore.defaultBlockState();
            for (int i = 0; i < 40; i++) {
                var plain = mine(l, s, iron);
                h.assertTrue(rawUranium(plain) == 1 && onlyListedByproducts(plain), ore + " without Fortune gave " + plain);
                var sk = mine(l, s, silk);
                h.assertTrue(sk.size() == 1 && sk.get(0).is(ore.asItem()) && sk.get(0).getCount() == 1, ore + " with Silk Touch gave " + sk);
            }
            int total = 0, most = 0, draws = 3000;
            for (int i = 0; i < draws; i++) {
                var f = mine(l, s, fortune);
                int raw = rawUranium(f);
                h.assertTrue(raw >= 1 && onlyListedByproducts(f), ore + " with Fortune gave " + f);
                total += raw;
                most = Math.max(most, raw);
            }
            double mean = total / (double) draws;
            // Fortune III is the vanilla ore bonus: 1 + (0 + 0 + 1 + 2 + 3) / 5 = 2.2 on average, 1 to 4 each
            h.assertTrue(mean > 1.95 && mean < 2.45 && most == 4, ore + " Fortune III: mean " + mean + ", most " + most);
        }
        for (Block b : new Block[] {BwrBlocks.RAW_URANIUM_BLOCK.get(), BwrBlocks.URANIUM_BLOCK.get()}) {
            for (ItemStack tool : new ItemStack[] {iron, silk, fortune}) {
                var drops = mine(l, b.defaultBlockState(), tool);
                h.assertTrue(drops.size() == 1 && drops.get(0).is(b.asItem()) && drops.get(0).getCount() == 1, b + " dropped " + drops);
            }
        }
        h.succeed();
    }

    // ------------------------------------------------------------------------------------------------------------
    // the fuel fabricator

    private static final BlockPos FAB = new BlockPos(47_200, 210, 47_200);

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void fuelFabricatorAcceptsOurIngotAndRawUranium(GameTestHelper h) {
        FuelItemRoute.overrideInputsForTest(null, null);   // the real tags
        try {
            ItemStack ingot = new ItemStack(BwrItems.URANIUM_INGOT.get()), raw = new ItemStack(BwrItems.RAW_URANIUM.get());
            h.assertTrue(FuelItemRoute.uraniumUnits(ingot) == FuelItemRoute.UNITS_PER_INGOT, "an ingot is " + FuelItemRoute.uraniumUnits(ingot) + " units");
            h.assertTrue(FuelItemRoute.uraniumUnits(raw) == FuelItemRoute.UNITS_PER_INGOT, "raw uranium is " + FuelItemRoute.uraniumUnits(raw) + " units");
            for (Item notFeed : new Item[] {BwrItems.DEPLETED_URANIUM.get(), BwrItems.URANIUM_ORE.get(), BwrItems.URANIUM_BLOCK.get(), Items.IRON_INGOT}) {
                h.assertTrue(FuelItemRoute.uraniumUnits(new ItemStack(notFeed)) == 0, notFeed + " counts as uranium");
            }
            // Mekanism's uranium keeps working beside ours (the same tags)
            if (ModList.get().isLoaded("mekanism")) {
                for (String id : new String[] {"ingot_uranium", "raw_uranium"}) {
                    Item m = BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath("mekanism", id));
                    h.assertTrue(m != Items.AIR && FuelItemRoute.uraniumUnits(new ItemStack(m)) == FuelItemRoute.UNITS_PER_INGOT, "Mekanism's " + id + " is not fabricator feed");
                }
            }

            // the machine itself, with no stand-ins for uranium: steel is the only item another mod would give (iron stands in)
            FuelItemRoute.overrideInputsForTest(null, stack -> stack.is(Items.IRON_INGOT));
            ServerLevel l = h.getLevel();
            for (int round = 0; round < 2; round++) {
                BlockPos p = FAB.offset(4 * round, 0, 0);
                l.getChunk(p);
                l.removeBlock(p, false);
                l.setBlock(p, BwrBlocks.FUEL_FABRICATOR.get().defaultBlockState(), 2);
                var be = (FuelFabricatorBlockEntity) l.getBlockEntity(p);
                h.assertTrue(be.setTarget("natural_uranium"), "target refused");
                var up = l.getCapability(Capabilities.ItemHandler.BLOCK, p, Direction.UP);
                // round 0: four of our ingots; round 1: two raw uranium and two ingots
                Item first = round == 0 ? BwrItems.URANIUM_INGOT.get() : BwrItems.RAW_URANIUM.get();
                var cost = FuelItemRoute.cost("natural_uranium");
                h.assertTrue(up.insertItem(FabricatorItemView.STEEL, new ItemStack(Items.IRON_INGOT, cost.steel()), false).isEmpty(), "steel refused");
                int fed = 0;
                for (int tick = 0; tick < 5000 && be.output().getStackInSlot(0).isEmpty(); tick++) {
                    if (fed < cost.ingots()) {
                        Item pick = round == 1 && fed >= cost.ingots() / 2 ? BwrItems.URANIUM_INGOT.get() : first;
                        if (up.insertItem(FabricatorItemView.URANIUM, new ItemStack(pick), false).isEmpty()) {
                            fed++;
                        }
                    }
                    be.energy().receiveEnergy(10_000, false);
                    FuelFabricatorBlockEntity.serverTick(l, p, l.getBlockState(p), be);
                }
                var assembly = be.output().getStackInSlot(0);
                h.assertTrue(assembly.is(BwrItems.FUEL_ASSEMBLY.get()), "round " + round + ": no assembly from " + fed + " items of our uranium");
                h.assertTrue(FuelAssemblies.dataOf(assembly).fuelTypeName().equals("natural_uranium"), "round " + round + ": wrong fuel");
                h.assertTrue(fed == cost.ingots(), "round " + round + ": " + fed + " items fed for a cost of " + cost.ingots());
                be.removalDrops();
                l.removeBlock(p, false);
            }
            h.succeed();
        } finally {
            FuelItemRoute.overrideInputsForTest(null, null);
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // the shape of the veins (geometry only)

    private static final long SEED = 8675309L;

    /** Every block of a vein, by whole-box slice, as packed position -> kind. */
    private static Map<Long, UraniumVeins.Kind> wholeVein(UraniumVeins.Vein v, double density, double raw) {
        Map<Long, UraniumVeins.Kind> out = new HashMap<>();
        UraniumVeins.slice(v, SEED, (int) v.minX - 2, (int) v.minY - 2, (int) v.minZ - 2, (int) v.maxX + 2, (int) v.maxY + 2, (int) v.maxZ + 2, density, raw,
                (x, y, z, k) -> out.put(UraniumStats.pack(x, y, z), k));
        return out;
    }

    private static List<UraniumVeins.Vein> sampleVeins(int count, double chance) {
        List<UraniumVeins.Vein> out = new ArrayList<>();
        for (int cx = -8; cx < 8 && out.size() < count; cx++) {
            for (int cz = -8; cz < 8 && out.size() < count; cz++) {
                var v = UraniumVeins.vein(SEED, cx, cz, UraniumVeins.Geometry.DEFAULT);
                if (v.roll < chance) {
                    out.add(v);
                }
            }
        }
        return out;
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void aVeinIsLongConnectedAndSeamlessAcrossChunks(GameTestHelper h) {
        var geometry = UraniumVeins.Geometry.DEFAULT;
        var veins = sampleVeins(12, 1.0);
        h.assertTrue(veins.size() == 12, "only " + veins.size() + " veins");
        double longest = 0;
        int largestBody = 0;
        for (var v : veins) {
            var whole = wholeVein(v, 0.34, 0.08);
            h.assertTrue(whole.size() > 3000, "a vein of only " + whole.size() + " blocks (cell " + v.cellX + ", " + v.cellZ + ")");
            // determinism: the same cell gives the same blocks
            h.assertTrue(wholeVein(v, 0.34, 0.08).equals(whole), "the vein changed between two builds");
            h.assertTrue(UraniumVeins.vein(SEED, v.cellX, v.cellZ, geometry).roll == v.roll, "the roll changed");

            // chunk by chunk: each slice stays inside its own 16 x 16 columns and the slices add up to exactly the whole vein
            Map<Long, UraniumVeins.Kind> sliced = new HashMap<>();
            int minCx = Math.floorDiv((int) v.minX, 16), maxCx = Math.floorDiv((int) v.maxX, 16);
            int minCz = Math.floorDiv((int) v.minZ, 16), maxCz = Math.floorDiv((int) v.maxZ, 16);
            for (int cx = minCx; cx <= maxCx; cx++) {
                for (int cz = minCz; cz <= maxCz; cz++) {
                    final int fcx = cx, fcz = cz;
                    UraniumVeins.slice(v, SEED, cx * 16, -64, cz * 16, cx * 16 + 15, 319, cz * 16 + 15, 0.34, 0.08, (x, y, z, k) -> {
                        if (Math.floorDiv(x, 16) != fcx || Math.floorDiv(z, 16) != fcz) {
                            throw new AssertionError("chunk " + fcx + ", " + fcz + " was given block " + x + ", " + z);
                        }
                        if (sliced.put(UraniumStats.pack(x, y, z), k) != null) {
                            throw new AssertionError("block " + x + ", " + y + ", " + z + " given twice");
                        }
                    });
                }
            }
            h.assertTrue(sliced.equals(whole), "the chunk slices (" + sliced.size() + " blocks) are not the whole vein (" + whole.size() + " blocks)");

            var parts = UraniumStats.components(whole.keySet(), 1);
            h.assertTrue(parts.get(0).blocks() >= whole.size() * 0.55, "the largest connected body is only " + parts.get(0).blocks() + " of " + whole.size());
            longest = Math.max(longest, parts.get(0).horizontalSpan());
            largestBody = Math.max(largestBody, parts.get(0).blocks());
            h.assertTrue(parts.get(0).horizontalSpan() > 100, "a vein only " + parts.get(0).horizontalSpan() + " blocks long: " + parts.get(0));
            h.assertTrue(parts.get(0).height() < 80, "a vein " + parts.get(0).height() + " blocks tall: " + parts.get(0));
            h.assertTrue(v.minY >= geometry.minY() - 30 && v.maxY <= geometry.maxY() + 30, "the vein leaves its height range: " + v.minY + " to " + v.maxY);
            long ore = whole.values().stream().filter(k -> k != UraniumVeins.Kind.FILL).count();
            h.assertTrue(ore > 200 && ore < whole.size() * 0.7, "ore share odd: " + ore + " of " + whole.size());
        }
        System.out.println("URANIUM VEIN SHAPE: " + veins.size() + " sample veins, longest connected body spans " + Math.round(longest)
                + " blocks horizontally, largest has " + largestBody + " blocks");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void gradeScalesTheOreAndAChanceOnlyRemovesVeins(GameTestHelper h) {
        var v = sampleVeins(1, 1.0).get(0);
        long previous = -1;
        for (double density : new double[] {0.05, 0.12, 0.2, 0.34, 0.5}) {
            long ore = wholeVein(v, density, 0.0).values().stream().filter(k -> k == UraniumVeins.Kind.ORE).count();
            h.assertTrue(ore > previous, "ore did not rise with the grade at " + density + ": " + ore + " after " + previous);
            previous = ore;
        }
        var none = wholeVein(v, 0.0, 0.0);
        h.assertTrue(none.values().stream().noneMatch(k -> k != UraniumVeins.Kind.FILL), "ore at grade 0");
        var rich = wholeVein(v, 0.34, 0.5);
        long raws = rich.values().stream().filter(k -> k == UraniumVeins.Kind.RAW).count();
        long ores = rich.values().stream().filter(k -> k == UraniumVeins.Kind.ORE).count();
        h.assertTrue(raws > 0 && raws < (raws + ores) * 0.7 && wholeVein(v, 0.34, 0.0).values().stream().noneMatch(k -> k == UraniumVeins.Kind.RAW), "raw uranium blocks: " + raws + " of " + (raws + ores));
        // the body does not depend on the grade: same host rock turned to filler either way
        h.assertTrue(wholeVein(v, 0.05, 0.0).keySet().equals(wholeVein(v, 0.5, 0.0).keySet()), "the grade changed the shape of the vein");
        // a chance only removes veins: whatever exists at a low chance exists at a higher one
        Set<Long> lowCells = new HashSet<>(), highCells = new HashSet<>();
        for (int cx = -20; cx < 20; cx++) {
            for (int cz = -20; cz < 20; cz++) {
                double roll = UraniumVeins.vein(SEED, cx, cz, UraniumVeins.Geometry.DEFAULT).roll;
                if (roll < 0.12) lowCells.add(UraniumStats.pack(cx, 0, cz));
                if (roll < 0.85) highCells.add(UraniumStats.pack(cx, 0, cz));
            }
        }
        h.assertTrue(highCells.containsAll(lowCells) && highCells.size() > lowCells.size() * 3, "chance sets: " + lowCells.size() + " in " + highCells.size());
        h.succeed();
    }

    // ------------------------------------------------------------------------------------------------------------
    // the datapack: the biome table and the server option

    private static final String[] GROUPS = {"arid", "highland", "semi_arid", "lowland", "aquatic"};

    private static UraniumVeinConfig veinConfig(ServerLevel l, String group) {
        var configured = l.registryAccess().registryOrThrow(Registries.CONFIGURED_FEATURE);
        var holder = configured.getHolderOrThrow(ResourceKey.create(Registries.CONFIGURED_FEATURE, bwr("uranium_vein_" + group)));
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
    public static void biomeGroupsConcentrateUraniumByGeology(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        // the table: more veins, richer ore, more blobs, going arid > highland > semi-arid > lowland > aquatic
        double[] chance = new double[GROUPS.length], grade = new double[GROUPS.length];
        for (int i = 0; i < GROUPS.length; i++) {
            var c = veinConfig(l, GROUPS[i]);
            chance[i] = c.veinChance();
            grade[i] = c.oreDensity();
            h.assertTrue(c.geometry().equals(veinConfig(l, "arid").geometry()), GROUPS[i] + " has a different vein shape from arid (the vein would change outline at a biome border)");
            var blob = l.registryAccess().registryOrThrow(Registries.CONFIGURED_FEATURE).get(bwr("uranium_blob_" + GROUPS[i]));
            h.assertTrue(blob != null && blob.feature() instanceof dev.bwr.mod.worldgen.UraniumOreFeature, GROUPS[i] + " blobs are not the gated ore feature (the server option would not stop them)");
        }
        for (int i = 1; i < GROUPS.length; i++) {
            h.assertTrue(chance[i - 1] > chance[i], "vein chance does not fall from " + GROUPS[i - 1] + " to " + GROUPS[i] + ": " + chance[i - 1] + " then " + chance[i]);
            h.assertTrue(grade[i - 1] > grade[i], "grade does not fall from " + GROUPS[i - 1] + " to " + GROUPS[i]);
        }
        h.assertTrue(chance[0] >= 0.7 && chance[GROUPS.length - 1] <= 0.05, "the ends of the table are " + chance[0] + " and " + chance[GROUPS.length - 1]);

        // the biomes: every ordinary overworld biome is in exactly one group; the nether and the end in none
        var biomes = l.registryAccess().registryOrThrow(Registries.BIOME);
        var overworld = TagKey.create(Registries.BIOME, ResourceLocation.withDefaultNamespace("is_overworld"));
        Set<ResourceKey<Biome>> caves = Set.of(Biomes.DRIPSTONE_CAVES, Biomes.LUSH_CAVES, Biomes.DEEP_DARK);
        Map<String, List<String>> membership = new HashMap<>();
        for (var entry : biomes.holders().toList()) {
            var key = entry.key();
            int groups = 0;
            for (String g : GROUPS) {
                if (has(entry, placed(l, "uranium_vein_" + g))) {
                    groups++;
                    membership.computeIfAbsent(g, k -> new ArrayList<>()).add(key.location().getPath());
                    h.assertTrue(entry.is(TagKey.create(Registries.BIOME, bwr("has_uranium/" + g))), key.location() + " has the " + g + " feature without being in its tag");
                    h.assertTrue(has(entry, placed(l, "uranium_blob_" + g)), key.location() + " has the vein feature of " + g + " but not its blobs");
                }
            }
            boolean ordinary = entry.is(overworld) && !caves.contains(key);
            if (ordinary) {
                h.assertTrue(groups == 1, key.location() + " is in " + groups + " uranium groups (it must be in exactly one)");
            }
            if (entry.is(TagKey.create(Registries.BIOME, ResourceLocation.withDefaultNamespace("is_nether"))) || entry.is(TagKey.create(Registries.BIOME, ResourceLocation.withDefaultNamespace("is_end")))) {
                h.assertTrue(groups == 0, key.location() + " has uranium");
            }
        }
        Holder<Biome> desert = biomes.getHolderOrThrow(Biomes.DESERT), plains = biomes.getHolderOrThrow(Biomes.PLAINS), peaks = biomes.getHolderOrThrow(Biomes.JAGGED_PEAKS);
        Holder<Biome> ocean = biomes.getHolderOrThrow(Biomes.DEEP_OCEAN), river = biomes.getHolderOrThrow(Biomes.RIVER), badlands = biomes.getHolderOrThrow(Biomes.BADLANDS);
        h.assertTrue(has(desert, placed(l, "uranium_vein_arid")) && has(badlands, placed(l, "uranium_vein_arid")), "desert and badlands are not arid");
        h.assertTrue(has(peaks, placed(l, "uranium_vein_highland")), "mountains are not highland");
        h.assertTrue(has(plains, placed(l, "uranium_vein_lowland")) && !has(plains, placed(l, "uranium_vein_arid")), "plains are not lowland");
        h.assertTrue(has(ocean, placed(l, "uranium_vein_aquatic")) && has(river, placed(l, "uranium_vein_aquatic")), "oceans and rivers are not aquatic");
        System.out.println("URANIUM BIOME TABLE: " + membership.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue().size() + " biomes").sorted().toList());
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void serverOptionDecidesWhetherBiomesGetUranium(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        h.assertTrue(GameplayConfig.SPEC.isLoaded() && GameplayConfig.URANIUM_ORE_GENERATION.getDefault(), "the option is missing or off by default");
        var modifiers = l.registryAccess().registryOrThrow(NeoForgeRegistries.Keys.BIOME_MODIFIERS);
        BiomeModifier arid = modifiers.get(bwr("uranium_arid"));
        h.assertTrue(arid instanceof UraniumBiomeModifier, "bwr:uranium_arid is " + arid);
        var biomes = l.registryAccess().registryOrThrow(Registries.BIOME);
        Holder<Biome> desert = biomes.getHolderOrThrow(Biomes.DESERT), plains = biomes.getHolderOrThrow(Biomes.PLAINS);
        Holder<PlacedFeature> vein = placed(l, "uranium_vein_arid");
        boolean before = GameplayConfig.URANIUM_ORE_GENERATION.get();
        try {
            for (boolean on : new boolean[] {true, false, true}) {
                GameplayConfig.URANIUM_ORE_GENERATION.set(on);
                var builder = ModifiableBiomeInfo.BiomeInfo.Builder.copyOf(desert.value().modifiableBiomeInfo().getOriginalBiomeInfo());
                arid.modify(desert, BiomeModifier.Phase.ADD, builder);
                boolean added = builder.getGenerationSettings().getFeatures(GenerationStep.Decoration.UNDERGROUND_ORES).contains(vein);
                h.assertTrue(added == on, "option " + on + ": desert " + (added ? "got" : "did not get") + " the arid vein feature");
                var other = ModifiableBiomeInfo.BiomeInfo.Builder.copyOf(plains.value().modifiableBiomeInfo().getOriginalBiomeInfo());
                arid.modify(plains, BiomeModifier.Phase.ADD, other);
                h.assertTrue(!other.getGenerationSettings().getFeatures(GenerationStep.Decoration.UNDERGROUND_ORES).contains(vein), "the arid modifier changed the plains");
            }
        } finally {
            GameplayConfig.URANIUM_ORE_GENERATION.set(before);
        }
        // and with the option on at the start of this server, the live biomes carry the features
        h.assertTrue(has(desert, vein), "the loaded desert has no uranium vein feature although the option is on");
        h.succeed();
    }
}
