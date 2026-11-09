package dev.bwr.mod.devtest;

import com.mojang.authlib.GameProfile;
import dev.bwr.core.grid.GridDemand;
import dev.bwr.mod.grid.GcbConnections;
import dev.bwr.mod.grid.GridTieBlock;
import dev.bwr.mod.grid.GridTieBlockEntity;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.registry.BwrItems;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The Grid Tie as the 5 x 3 x 7 generator circuit breaker (GRID-TIE-SPEC.md, "New look"): placement and facing, breaking any cell,
 * the closed indicator on every cell, FE into a generator-end cell, a saved one-block tie, refused placement, and the CEE flange points.
 * Each test has its own row far from the others.
 */
@GameTestHolder("bwr")
@PrefixGameTestTemplate(false)
public final class GridTieModelTests {
    private static final BlockPos ROOT = new BlockPos(93000, 245, 93000);
    private static final Direction[] DIRS = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};

    private static BlockPos row(int n) { return ROOT.offset(0, 0, 96 * n); }

    private static AABB box(BlockPos min, BlockPos max) { return new AABB(min.getX(), min.getY(), min.getZ(), max.getX() + 1, max.getY() + 1, max.getZ() + 1); }

    private static void clear(ServerLevel l, BlockPos min, BlockPos max) {
        for (var p : BlockPos.betweenClosed(min, max)) l.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
        for (var e : l.getEntitiesOfClass(ItemEntity.class, box(min, max).inflate(4))) e.discard();
    }

    private static FakePlayer player(ServerLevel l, String name, GameType mode, BlockPos near) {
        FakePlayer p = FakePlayerFactory.get(l, new GameProfile(UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)), name));
        p.setGameMode(mode);
        p.getInventory().clearContent();
        p.setPos(Vec3.atCenterOf(near.above(60)));
        return p;
    }

    /** Uses one Grid Tie item at {@code p} by a player looking toward {@code looking}. */
    private static boolean useItem(ServerLevel l, FakePlayer player, BlockPos p, Direction looking) {
        player.setYRot(looking.toYRot());
        player.setXRot(0);
        ItemStack stack = new ItemStack(BwrBlocks.GRID_TIE.get(), 1);
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        return stack.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(p), Direction.UP, p, false))).consumesAction();
    }

    private static int cellsStanding(ServerLevel l, BlockPos root, Direction facing) {
        int n = 0;
        for (int i = 0; i < GridTieBlock.CELLS; i++) {
            var s = l.getBlockState(GridTieBlock.cellPos(root, facing, i));
            if (s.is(BwrBlocks.GRID_TIE.get()) && s.getValue(GridTieBlock.ASSEMBLED) && s.getValue(GridTieBlock.CELL) == i && s.getValue(GridTieBlock.FACING) == facing) n++;
        }
        return n;
    }

    private static int itemsNear(ServerLevel l, BlockPos min, BlockPos max) {
        int n = 0;
        for (var e : l.getEntitiesOfClass(ItemEntity.class, box(min, max).inflate(4))) if (e.getItem().is(BwrItems.GRID_TIE.get())) n += e.getItem().getCount();
        return n;
    }

    private static int count(FakePlayer p, net.minecraft.world.item.Item item) {
        int n = 0;
        for (var s : p.getInventory().items) if (s.is(item)) n += s.getCount();
        return n;
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void placingTheItemBuildsAllCellsFacingAwayFromThePlayer(GameTestHelper h) {
        var l = h.getLevel();
        var root = row(0);
        var min = root.offset(-8, -2, -8);
        var max = root.offset(8, 6, 8);
        EntityLookupProbe.runWhenLive(h, box(min, max), () -> {
            try {
                for (var d : DIRS) {
                    clear(l, min, max);
                    var player = player(l, "bwr_gcb_place", GameType.SURVIVAL, root);
                    // the player looks toward `looking`; the tie faces the way the pumps do, the opposite
                    var looking = d.getOpposite();
                    h.assertTrue(useItem(l, player, root, looking), d + ": the item did not place");
                    var s = l.getBlockState(root);
                    h.assertTrue(s.is(BwrBlocks.GRID_TIE.get()) && s.getValue(GridTieBlock.ASSEMBLED) && s.getValue(GridTieBlock.FACING) == d
                            && s.getValue(GridTieBlock.CELL) == GridTieBlock.CONTROLLER && !s.getValue(GridTieBlock.CLOSED), d + ": root state " + s);
                    h.assertTrue(cellsStanding(l, root, d) == 105, d + ": " + cellsStanding(l, root, d) + " of 105 cells stand");
                    int blocks = 0, entities = 0;
                    for (var p : BlockPos.betweenClosed(min, max)) {
                        if (l.getBlockState(p).is(BwrBlocks.GRID_TIE.get())) blocks++;
                        if (l.getBlockEntity(p) != null) entities++;
                    }
                    h.assertTrue(blocks == 105 && entities == 1 && l.getBlockEntity(root) instanceof GridTieBlockEntity, d + ": " + blocks + " blocks, " + entities + " block entities");
                    h.assertTrue(count(player, BwrItems.GRID_TIE.get()) == 0, d + ": the item was not taken once");
                }
                // a placer that sets the default state and calls setPlacedBy (no getStateForPlacement) still gets the full tie
                clear(l, min, max);
                var player = player(l, "bwr_gcb_place", GameType.SURVIVAL, root);
                player.setYRot(Direction.SOUTH.toYRot());
                var stack = new ItemStack(BwrBlocks.GRID_TIE.get(), 1);
                var def = BwrBlocks.GRID_TIE.get().defaultBlockState();
                l.setBlock(root, def, 3);
                BwrBlocks.GRID_TIE.get().setPlacedBy(l, root, def, player, stack);
                h.assertTrue(cellsStanding(l, root, Direction.NORTH) == 105, "a default-state placer did not get the full tie: " + cellsStanding(l, root, Direction.NORTH));
                h.succeed();
            } finally { clear(l, min, max); }
        });
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void breakingAnyCellRemovesTheWholeTieAndDropsOneItem(GameTestHelper h) {
        var l = h.getLevel();
        var root = row(1);
        var min = root.offset(-8, -2, -8);
        var max = root.offset(8, 6, 8);
        EntityLookupProbe.runWhenLive(h, box(min, max), () -> {
            try {
                // the first and last cells, the controller, and two in between
                for (int cell : new int[]{0, GridTieBlock.CONTROLLER, 52, 71, 104}) {
                    clear(l, min, max);
                    var player = player(l, "bwr_gcb_break", GameType.SURVIVAL, root);
                    h.assertTrue(useItem(l, player, root, Direction.SOUTH), "no placement");
                    h.assertTrue(cellsStanding(l, root, Direction.NORTH) == 105, "not whole before the break");
                    player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.NETHERITE_PICKAXE));
                    h.assertTrue(player.gameMode.destroyBlock(GridTieBlock.cellPos(root, Direction.NORTH, cell)), "cell " + cell + " would not break");
                    int left = 0;
                    for (var p : BlockPos.betweenClosed(min, max)) if (l.getBlockState(p).is(BwrBlocks.GRID_TIE.get())) left++;
                    h.assertTrue(left == 0, "cell " + cell + ": " + left + " cells left standing");
                    h.assertTrue(itemsNear(l, min, max) == 1, "cell " + cell + ": " + itemsNear(l, min, max) + " items dropped, not exactly one");
                }
                h.succeed();
            } finally { clear(l, min, max); }
        });
    }

    /** A generator at the root and a full tie 24 blocks east, bound and with a fixed demand; {@code tie} is its controller. */
    private static GridTieBlockEntity tieWithGenerator(ServerLevel l, BlockPos root, Direction facing) {
        PowerModuleRuntimeCheck.place(l, BwrBlocks.NUCLEAR_GENERATOR.get(), root, Direction.EAST);
        var tiePos = root.east(24);
        var s = BwrBlocks.GRID_TIE.get().placementState().setValue(GridTieBlock.FACING, facing);
        l.setBlock(tiePos, s, 3);
        BwrBlocks.GRID_TIE.get().setPlacedBy(l, tiePos, s, null, new ItemStack(BwrBlocks.GRID_TIE.get()));
        return (GridTieBlockEntity) l.getBlockEntity(tiePos);
    }

    private static int closedCells(ServerLevel l, BlockPos root, Direction facing) {
        int n = 0;
        for (int i = 0; i < GridTieBlock.CELLS; i++) if (l.getBlockState(GridTieBlock.cellPos(root, facing, i)).getValue(GridTieBlock.CLOSED)) n++;
        return n;
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void closingAndTrippingTheBreakerSwitchesEveryCell(GameTestHelper h) {
        var l = h.getLevel();
        var root = row(2);
        var min = root.offset(-8, -2, -8);
        var max = root.offset(40, 6, 8);
        EntityLookupProbe.runWhenLive(h, box(min, max), () -> {
            try {
                clear(l, min, max);
                var tie = tieWithGenerator(l, root, Direction.EAST);
                var at = tie.getBlockPos();
                h.assertTrue(closedCells(l, at, Direction.EAST) == 0 && tie.bindGenerator(root), "setup");
                h.assertTrue(GridTieTests.closeWithDraw(.999, tie::closeBreaker) && tie.closed(), "the breaker did not close");
                h.assertTrue(closedCells(l, at, Direction.EAST) == 105, "closed on " + closedCells(l, at, Direction.EAST) + " of 105 cells");
                h.assertTrue(cellsStanding(l, at, Direction.EAST) == 105, "closing changed the cells' other properties");
                tie.tripBreaker();
                h.assertTrue(!tie.closed() && closedCells(l, at, Direction.EAST) == 0, "tripped but " + closedCells(l, at, Direction.EAST) + " cells still closed");
                // a load re-applies the saved state to cells that disagree (a stale chunk, or cells that were not loaded when it changed)
                GridTieTests.closeWithDraw(.999, tie::closeBreaker);
                var saved = tie.saveWithoutMetadata(l.registryAccess());
                tie.tripBreaker();
                tie.loadWithComponents(saved, l.registryAccess());
                tie.tickDevice();
                h.assertTrue(tie.closed() && closedCells(l, at, Direction.EAST) == 105, "a load left the indicator at " + closedCells(l, at, Direction.EAST) + " of 105");
                tie.tripBreaker();
                h.succeed();
            } finally { clear(l, min, max); }
        });
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void feIntoAGeneratorEndCellReachesTheTie(GameTestHelper h) {
        var l = h.getLevel();
        var root = row(3);
        var min = root.offset(-8, -2, -8);
        var max = root.offset(40, 6, 8);
        EntityLookupProbe.runWhenLive(h, box(min, max), () -> {
            long time = l.getGameTime();
            double config = dev.bwr.mod.grid.GridConfig.maximumMW();
            try {
                clear(l, min, max);
                dev.bwr.mod.grid.GridConfig.MAXIMUM_MW.set(48000.0);
                var tie = tieWithGenerator(l, root, Direction.EAST);
                var at = tie.getBlockPos();
                h.assertTrue(tie.bindGenerator(root) && tie.configure(new GridDemand(0, 48000, 0, 0, 0, 20)) && GridTieTests.closeWithDraw(.999, tie::closeBreaker), "tie setup");
                ((net.minecraft.world.level.storage.ServerLevelData) l.getLevelData()).setGameTime(time + 1);
                // phase B's generator-end flange cell (x 2, y 2, z 0), through the capability of that cell, not of the controller
                var cell = GridTieBlock.cellPos(at, Direction.EAST, 2 + 5 * (0 + 7 * 2));
                var cap = l.getCapability(Capabilities.EnergyStorage.BLOCK, cell, null);
                h.assertTrue(cap != null && l.getCapability(Capabilities.EnergyStorage.BLOCK, cell, Direction.WEST) != null, "no energy capability on the generator-end cell");
                int before = (int) tie.ledger().totalFE();
                int took = cap.receiveEnergy(1000, false);
                h.assertTrue(took == 1000 && tie.ledger().totalFE() - before == 1000, "the cell took " + took + ", the tie ledger moved " + (tie.ledger().totalFE() - before));
                tie.tripBreaker();
                h.assertTrue(cap.receiveEnergy(1000, false) == 0, "an open tie took FE through a cell");
                h.succeed();
            } finally {
                dev.bwr.mod.grid.GridConfig.MAXIMUM_MW.set(config);
                clear(l, min, max);
                ((net.minecraft.world.level.storage.ServerLevelData) l.getLevelData()).setGameTime(time);
            }
        });
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void aSavedOneBlockTieStillWorksAndKeepsItsCube(GameTestHelper h) {
        var l = h.getLevel();
        var root = row(4);
        var min = root.offset(-8, -2, -8);
        var max = root.offset(40, 6, 8);
        EntityLookupProbe.runWhenLive(h, box(min, max), () -> {
            long time = l.getGameTime();
            double config = dev.bwr.mod.grid.GridConfig.maximumMW();
            try {
                clear(l, min, max);
                dev.bwr.mod.grid.GridConfig.MAXIMUM_MW.set(48000.0);
                PowerModuleRuntimeCheck.place(l, BwrBlocks.NUCLEAR_GENERATOR.get(), root, Direction.EAST);
                var pos = root.east(24);
                l.setBlock(pos, BwrBlocks.GRID_TIE.get().defaultBlockState(), 3);
                var s = l.getBlockState(pos);
                h.assertTrue(!s.getValue(GridTieBlock.ASSEMBLED) && s.getShape(l, pos).equals(net.minecraft.world.phys.shapes.Shapes.block()), "the saved single block is not a cube");
                var tie = (GridTieBlockEntity) l.getBlockEntity(pos);
                h.assertTrue(dev.bwr.mod.signal.DeviceResolver.resolve(l, pos) == tie, "a one-block tie does not resolve to itself");
                h.assertTrue(tie.bindGenerator(root) && tie.configure(new GridDemand(0, 48000, 0, 0, 0, 20)) && GridTieTests.closeWithDraw(.999, tie::closeBreaker), "legacy tie setup");
                tie.tickDevice();
                h.assertTrue(!l.getBlockState(pos).getValue(GridTieBlock.CLOSED) && !l.getBlockState(pos.above()).is(BwrBlocks.GRID_TIE.get()), "the legacy tie changed its blockstate or spread");
                ((net.minecraft.world.level.storage.ServerLevelData) l.getLevelData()).setGameTime(time + 1);
                var cap = l.getCapability(Capabilities.EnergyStorage.BLOCK, pos, Direction.UP);
                h.assertTrue(cap != null && cap.receiveEnergy(1000, false) == 1000 && tie.ledger().totalFE() >= 1000, "the legacy tie did not take FE");
                tie.tripBreaker();
                h.succeed();
            } finally {
                dev.bwr.mod.grid.GridConfig.MAXIMUM_MW.set(config);
                clear(l, min, max);
                ((net.minecraft.world.level.storage.ServerLevelData) l.getLevelData()).setGameTime(time);
            }
        });
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void anObstructedPlacementIsRefused(GameTestHelper h) {
        var l = h.getLevel();
        var root = row(5);
        var min = root.offset(-8, -2, -8);
        var max = root.offset(8, 6, 8);
        EntityLookupProbe.runWhenLive(h, box(min, max), () -> {
            try {
                for (int cell : new int[]{0, 52, 104}) {
                    clear(l, min, max);
                    // facing north (the player looks south): any one cell taken refuses the whole
                    l.setBlock(GridTieBlock.cellPos(root, Direction.NORTH, cell), Blocks.STONE.defaultBlockState(), 3);
                    var player = player(l, "bwr_gcb_refuse", GameType.SURVIVAL, root);
                    h.assertTrue(!useItem(l, player, root, Direction.SOUTH), "cell " + cell + " blocked, but the item placed");
                    h.assertTrue(l.getBlockState(root).isAir() && cellsStanding(l, root, Direction.NORTH) == 0, "cell " + cell + ": something was set down");
                    // a placer that skips getStateForPlacement: the root goes and the item comes back
                    var def = BwrBlocks.GRID_TIE.get().defaultBlockState();
                    var stack = new ItemStack(BwrBlocks.GRID_TIE.get(), 1);
                    player.getInventory().clearContent();
                    player.setYRot(Direction.SOUTH.toYRot());
                    l.setBlock(root, def, 3);
                    BwrBlocks.GRID_TIE.get().setPlacedBy(l, root, def, player, stack);
                    h.assertTrue(l.getBlockState(root).isAir() && cellsStanding(l, root, Direction.NORTH) == 0, "cell " + cell + ": the refused root stayed");
                    h.assertTrue(count(player, BwrItems.GRID_TIE.get()) == 1, "cell " + cell + ": the item was not given back");
                }
                h.succeed();
            } finally { clear(l, min, max); }
        });
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void theSixFlangePointsFollowTheTieThroughEveryFacing(GameTestHelper h) {
        var at = new BlockPos(10, 20, 30);
        // north: x across (1, 2.5, 4) from the controller cell's west edge, y 2, z 0 and 7 relative to the controller cell's z = 1
        var north = GcbConnections.flanges(at, Direction.NORTH);
        h.assertTrue(north.size() == 6, "six flanges");
        h.assertTrue(close(GcbConnections.flange(at, Direction.NORTH, GcbConnections.Phase.A, GcbConnections.End.GENERATOR), 11.0, 22.0, 29.0), "north A generator end");
        h.assertTrue(close(GcbConnections.flange(at, Direction.NORTH, GcbConnections.Phase.C, GcbConnections.End.TRANSFORMER), 14.0, 22.0, 36.0), "north C transformer end");
        // a quarter turn about the controller cell's centre, the way the cell models turn
        h.assertTrue(close(GcbConnections.flange(at, Direction.EAST, GcbConnections.Phase.A, GcbConnections.End.GENERATOR), 12.0, 22.0, 31.0), "east A generator end");
        // every flange of every facing lies inside its tie's footprint box
        for (var d : DIRS) for (var f : GcbConnections.flanges(at, d)) {
            boolean inside = false;
            for (int i = 0; i < GridTieBlock.CELLS && !inside; i++) inside = new AABB(GridTieBlock.cellPos(at, d, i)).inflate(1e-6).contains(f.pos());
            h.assertTrue(inside, d + " " + f.phase() + " " + f.end() + " is outside the footprint at " + f.pos());
        }
        h.succeed();
    }

    private static boolean close(Vec3 v, double x, double y, double z) { return v.distanceToSqr(x, y, z) < 1e-9; }
}
