package dev.bwr.mod.devtest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The Grid Tie's Create: Electro Energetics integration (GRID-TIE-SPEC.md 8.2). Without CEE every test says it was skipped (and the first
 * checks that no CEE class is visible); with {@code -PwithCee} they run against the real mod through {@link GridTieCeeChecks}, which is the
 * only class that names a CEE type. Each test has its own row far from the others.
 */
@GameTestHolder("bwrcee")
@PrefixGameTestTemplate(false)
public final class GridTieCeeTests {
    private static final BlockPos ROOT = new BlockPos(95000, 245, 95000);

    private GridTieCeeTests() {
    }

    private static BlockPos row(int n) {
        return ROOT.offset(0, 0, 96 * n);
    }

    private static boolean skipped(GameTestHelper h, String what) {
        if (ModList.get().isLoaded("electroenergetics")) return false;
        System.out.println("GRID TIE CEE: " + what + " skipped, Create: Electro Energetics is not loaded");
        h.succeed();
        return true;
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void theTieIsAPlainBlockWithoutCee(GameTestHelper h) throws Exception {
        if (ModList.get().isLoaded("electroenergetics")) {
            h.succeed();
            return;
        }
        boolean found;
        try {
            Class.forName("com.george_vi.electroenergetics.CEERegistries", false, GridTieCeeTests.class.getClassLoader());
            found = true;
        } catch (ClassNotFoundException e) {
            found = false;
        }
        h.assertTrue(!found, "CEE classes are visible but the mod is not loaded");
        h.assertTrue(dev.bwr.mod.registry.BwrBlocks.GRID_TIE.get().getClass() == dev.bwr.mod.grid.GridTieBlock.class, "the Grid Tie is not the plain block without CEE");
        System.out.println("GRID TIE CEE: absent; the Grid Tie is the plain block");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void nodesRegisterOnPlacementWithLabelsAndPositions(GameTestHelper h) {
        if (skipped(h, "nodes")) return;
        var l = h.getLevel();
        var at = row(0);
        var min = at.offset(-8, -2, -8);
        var max = at.offset(8, 6, 8);
        EntityLookupProbe.runWhenLive(h, GridTieCeeChecks.box(min, max), () -> {
            try {
                GridTieCeeChecks.clear(l, min, max);
                GridTieCeeChecks.nodesAndLabels(h, at);
                h.succeed();
            } finally { GridTieCeeChecks.clear(l, min, max); }
        });
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void aLegacyCubeHasNoNodes(GameTestHelper h) {
        if (skipped(h, "legacy cube")) return;
        var l = h.getLevel();
        var at = row(1);
        var min = at.offset(-8, -2, -8);
        var max = at.offset(8, 6, 8);
        EntityLookupProbe.runWhenLive(h, GridTieCeeChecks.box(min, max), () -> {
            try {
                GridTieCeeChecks.clear(l, min, max);
                GridTieCeeChecks.legacyCubeHasNoNodes(h, at);
                h.succeed();
            } finally { GridTieCeeChecks.clear(l, min, max); }
        });
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void closestNodeFromAFlangeCellFindsTheControllersNode(GameTestHelper h) {
        if (skipped(h, "closestNode")) return;
        var l = h.getLevel();
        var at = row(2);
        var min = at.offset(-8, -2, -8);
        var max = at.offset(8, 6, 8);
        EntityLookupProbe.runWhenLive(h, GridTieCeeChecks.box(min, max), () -> {
            try {
                GridTieCeeChecks.clear(l, min, max);
                GridTieCeeChecks.closestNodeThroughFlangeCells(h, at);
                h.succeed();
            } finally { GridTieCeeChecks.clear(l, min, max); }
        });
    }

    @GameTest(template = "empty", timeoutTicks = 600)
    public static void wiresAreRemovedWhenTheBreakerIsBrokenOrDisassembled(GameTestHelper h) {
        if (skipped(h, "wire removal")) return;
        var l = h.getLevel();
        var at = row(3);
        var min = at.offset(-12, -2, -14);
        var max = at.offset(12, 8, 14);
        EntityLookupProbe.runWhenLive(h, GridTieCeeChecks.box(min, max), () -> {
            try {
                // a survival player breaking a flange cell (not the controller)
                GridTieCeeChecks.wiresGoWithTheBlock(h, at, min, max, p -> {
                    var player = net.neoforged.neoforge.common.util.FakePlayerFactory.get(l,
                            new com.mojang.authlib.GameProfile(java.util.UUID.nameUUIDFromBytes("bwr_gcb_cee".getBytes()), "bwr_gcb_cee"));
                    player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
                    player.setPos(net.minecraft.world.phys.Vec3.atCenterOf(p.above(60)));
                    player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.NETHERITE_PICKAXE));
                    h.assertTrue(player.gameMode.destroyBlock(dev.bwr.mod.grid.GridTieBlock.cellPos(p, Direction.EAST, 0)), "cell 0 would not break");
                }, "a player breaking a flange cell");
                // the controller removed by code, as an explosion or /setblock would
                GridTieCeeChecks.wiresGoWithTheBlock(h, at, min, max, p -> l.removeBlock(p, false), "the controller removed");
                // another cell removed by code
                GridTieCeeChecks.wiresGoWithTheBlock(h, at, min, max,
                        p -> l.removeBlock(dev.bwr.mod.grid.GridTieBlock.cellPos(p, Direction.EAST, 104), false), "a corner cell removed");
                // the tie losing its assembled flag
                GridTieCeeChecks.wiresGoWithTheBlock(h, at, min, max,
                        p -> l.setBlock(p, l.getBlockState(p).setValue(dev.bwr.mod.grid.GridTieBlock.ASSEMBLED, false), 3), "disassembled");
                h.succeed();
            } finally { GridTieCeeChecks.clear(l, min, max); }
        });
    }

    @GameTest(template = "empty", timeoutTicks = 600)
    public static void polesSwitchACircuitOfCreativeBatteryAndResistor(GameTestHelper h) {
        if (skipped(h, "pole switching")) return;
        var l = h.getLevel();
        var root = row(4);
        var min = root.offset(-12, -2, -16);
        var max = root.offset(40, 9, 16);
        EntityLookupProbe.runWhenLive(h, GridTieCeeChecks.box(min, max), () -> {
            GridTieCeeChecks.clear(l, min, max);
            try {
                GridTieCeeChecks.polesSwitchTheCircuit(h, root, () -> {
                    GridTieCeeChecks.clear(l, min, max);
                    h.succeed();
                });
            } catch (RuntimeException | Error e) {
                GridTieCeeChecks.clear(l, min, max);
                throw e;
            }
        });
    }
}
