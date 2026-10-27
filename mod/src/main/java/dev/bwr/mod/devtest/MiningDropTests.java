package dev.bwr.mod.devtest;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Every block of the mod can be mined for its drop. A block registered with {@code requiresCorrectToolForDrops()} that is in no
 * {@code minecraft:mineable/*} tag has no correct tool at all, so {@code hasCorrectToolForDrops} is false for everyone and a
 * survival player gets nothing (16 blocks did that until a23d0459). This reads the registry, the tags and the loaded loot tables,
 * so a new block cannot slip through: it fails with the ids of the offenders.
 */
@GameTestHolder("bwrmining")
@PrefixGameTestTemplate(false)
public final class MiningDropTests {

    /**
     * Blocks that intentionally drop nothing when mined, and so may skip the loot table check below. Only blocks that are not
     * meant to be mined for an item belong here; a block that should drop and has no loot table is a bug, fixed in its loot JSON.
     * The mineable tag check is never skipped: a block that needs the right tool is mineable with one, exempt or not.
     */
    private static final Map<String, String> NO_LOOT_TABLE = Map.of();

    private static final List<TagKey<Block>> MINEABLE = List.of(BlockTags.MINEABLE_WITH_PICKAXE, BlockTags.MINEABLE_WITH_AXE,
            BlockTags.MINEABLE_WITH_SHOVEL, BlockTags.MINEABLE_WITH_HOE);

    /** The tools of one kind, wood through diamond: the matching tool of a tier high enough for the block's needs_*_tool tag. */
    private static Item tool(TagKey<Block> mineable, int tier) {
        // tier 0 wood, 1 stone, 2 iron, 3 diamond
        if (mineable == BlockTags.MINEABLE_WITH_PICKAXE) {
            return new Item[] {Items.WOODEN_PICKAXE, Items.STONE_PICKAXE, Items.IRON_PICKAXE, Items.DIAMOND_PICKAXE}[tier];
        } else if (mineable == BlockTags.MINEABLE_WITH_AXE) {
            return new Item[] {Items.WOODEN_AXE, Items.STONE_AXE, Items.IRON_AXE, Items.DIAMOND_AXE}[tier];
        } else if (mineable == BlockTags.MINEABLE_WITH_SHOVEL) {
            return new Item[] {Items.WOODEN_SHOVEL, Items.STONE_SHOVEL, Items.IRON_SHOVEL, Items.DIAMOND_SHOVEL}[tier];
        }
        return new Item[] {Items.WOODEN_HOE, Items.STONE_HOE, Items.IRON_HOE, Items.DIAMOND_HOE}[tier];
    }

    private static int tierNeeded(BlockState s) {
        return s.is(BlockTags.NEEDS_DIAMOND_TOOL) ? 3 : s.is(BlockTags.NEEDS_IRON_TOOL) ? 2 : s.is(BlockTags.NEEDS_STONE_TOOL) ? 1 : 0;
    }

    /** True when the block's class (below Block) overrides getDrops: it decides its own drops, not the loot table. */
    private static boolean customGetDrops(Block block) {
        for (Class<?> c = block.getClass(); c != null && c != Block.class && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals("getDrops") && !m.isSynthetic() && !m.isBridge()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<Block> bwrBlocks() {
        TreeMap<String, Block> blocks = new TreeMap<>();
        for (Block b : BuiltInRegistries.BLOCK) {
            ResourceLocation id = BuiltInRegistries.BLOCK.getKey(b);
            if (id.getNamespace().equals("bwr")) {
                blocks.put(id.getPath(), b);
            }
        }
        return new ArrayList<>(blocks.values());
    }

    private static String id(Block b) {
        return BuiltInRegistries.BLOCK.getKey(b).getPath();
    }

    /**
     * A block that needs the correct tool for drops is in a mineable tag, and the right tool of the tier its needs_*_tool tag asks
     * for (wood when it has none) is a correct tool for it; a tool of a lower tier is not, so the needs_* tags really bite.
     */
    @GameTest(template = "empty", templateNamespace = "bwrmining", timeoutTicks = 100)
    public static void everyToolGatedBlockCanBeMinedWithItsTool(GameTestHelper h) {
        List<Block> blocks = bwrBlocks();
        h.assertTrue(blocks.size() > 100, "only " + blocks.size() + " bwr blocks found; is the registry scan broken?");
        Set<String> unmineable = new TreeSet<>(), wrongTool = new TreeSet<>(), tierIgnored = new TreeSet<>();
        int gated = 0;
        for (Block b : blocks) {
            BlockState s = b.defaultBlockState();
            if (!s.requiresCorrectToolForDrops()) {
                continue;
            }
            gated++;
            List<TagKey<Block>> kinds = MINEABLE.stream().filter(s::is).toList();
            if (kinds.isEmpty()) {
                unmineable.add(id(b));
                continue;
            }
            int tier = tierNeeded(s);
            boolean ok = false;
            for (TagKey<Block> kind : kinds) {
                ok |= new ItemStack(tool(kind, tier)).isCorrectToolForDrops(s);
            }
            if (!ok) {
                wrongTool.add(id(b) + " (needs tier " + tier + ")");
            }
            if (tier > 0) {
                boolean lowerWorks = false;
                for (TagKey<Block> kind : kinds) {
                    lowerWorks |= new ItemStack(tool(kind, tier - 1)).isCorrectToolForDrops(s);
                }
                if (lowerWorks) {
                    tierIgnored.add(id(b) + " (tier " + tier + ")");
                }
            }
        }
        h.assertTrue(gated > 0, "no bwr block requires the correct tool; is the scan broken?");
        h.assertTrue(unmineable.isEmpty(), unmineable.size() + " blocks require the correct tool for drops but are in no minecraft:mineable/* tag, so nothing can mine them for a drop: " + unmineable);
        h.assertTrue(wrongTool.isEmpty(), "no tool of the tier its needs_*_tool tag asks for is correct for: " + wrongTool);
        h.assertTrue(tierIgnored.isEmpty(), "a tool one tier below its needs_*_tool tag still counts as correct for: " + tierIgnored);
        System.out.println("MINING TAGS PASS: " + gated + " of " + blocks.size() + " bwr blocks require the correct tool; each is mineable with a tool of its tier");
        h.succeed();
    }

    /**
     * Every block that is supposed to drop has a loot table in the loaded data: {@code bwr:blocks/<id>} or the one it
     * {@code dropsLike}. Not needed: a block with {@code noLootTable()} or its own getDrops, and the {@link #NO_LOOT_TABLE} exemptions.
     */
    @GameTest(template = "empty", templateNamespace = "bwrmining", timeoutTicks = 100)
    public static void everyBlockThatShouldDropHasALootTable(GameTestHelper h) {
        var registries = h.getLevel().getServer().reloadableRegistries();
        Set<ResourceLocation> tables = Set.copyOf(registries.getKeys(Registries.LOOT_TABLE));
        h.assertTrue(tables.contains(ResourceLocation.fromNamespaceAndPath("bwr", "blocks/large_rtg")), "the bwr loot tables are not loaded");
        Set<String> missing = new TreeSet<>(), staleExemptions = new TreeSet<>();
        int checked = 0;
        for (Block b : bwrBlocks()) {
            String id = id(b);
            boolean has = b.getLootTable() == BuiltInLootTables.EMPTY || tables.contains(b.getLootTable().location());
            boolean exempt = b.getLootTable() == BuiltInLootTables.EMPTY || customGetDrops(b);
            if (NO_LOOT_TABLE.containsKey(id)) {
                if (has) {
                    staleExemptions.add(id);
                }
                continue;
            }
            checked++;
            if (!has && !exempt) {
                missing.add(id + " (" + b.getLootTable().location() + ")");
            }
        }
        h.assertTrue(staleExemptions.isEmpty(), "exempt from the loot table check but it has one now, remove the exemption: " + staleExemptions);
        h.assertTrue(missing.isEmpty(), missing.size() + " blocks have no loot table, no noLootTable() and no getDrops override: " + missing);
        System.out.println("MINING LOOT PASS: " + checked + " bwr blocks checked, " + NO_LOOT_TABLE.size() + " exempt");
        h.succeed();
    }
}
