package dev.bwr.mod.devtest;

import com.mojang.authlib.GameProfile;
import dev.bwr.mod.condenser.CondenserBlock;
import dev.bwr.mod.condenser.CondenserLayout;
import dev.bwr.mod.containment.ContainmentAirlockBlock;
import dev.bwr.mod.containment.ContainmentVaultDoorBlock;
import dev.bwr.mod.cooling.CoolingBlock;
import dev.bwr.mod.eccs.TurbineAssemblyBlock;
import dev.bwr.mod.isotope.LargeRtgBlock;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.steam.MainSteamIsolationValveBlock;
import dev.bwr.mod.suppression.RhrHeatExchangerBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * Every multi-cell machine other than the pump family (which CompactPlacementTests covers): when its placement is refused after the
 * root block was set, the placer gets the item back; a creative player is not charged; the player's own item use still builds the
 * full structure and takes the item once; and the old-save states (the legacy turbine box, the single exchanger and single valve)
 * stay as they were. A building mod (Effortless 3.4.0: setBlockAndUpdate, then setPlacedBy with the inventory stack, then it takes the
 * item in survival) is emulated by {@link #effortless}. Each test has its own row, far from the others.
 */
@GameTestHolder("bwrturbine")
@PrefixGameTestTemplate(false)
public final class AssemblyRefundPlacementTests {
    private AssemblyRefundPlacementTests() {
    }

    private static final Direction[] DIRS = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};

    /**
     * One kind of machine. {@code full(f)}: the root state a normal placement gives, facing {@code f}. {@code compact(f)}: the
     * one-cell default state a building mod may hand over (null where the default state already is the full machine). {@code legacy(f)}:
     * the state an old save keeps (null where there is none). {@code blocker}: a cell of the footprint that is not the root.
     */
    private record Kind(String name, Block block, Function<Direction, BlockState> full, Function<Direction, BlockState> compact,
                        Function<Direction, BlockState> legacy, BiFunction<BlockPos, BlockState, BlockPos> blocker, ToIntFunction<BlockState> cells) {
        Item item() {
            return block.asItem();
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private record Group(List<Kind> kinds, int dx, int dz, int reach, int up) {
    }

    private static Kind turbine(String name, TurbineAssemblyBlock b) {
        return new Kind(name, b, f -> b.placementState().setValue(TurbineAssemblyBlock.FACING, f), null,
                f -> b.defaultBlockState().setValue(TurbineAssemblyBlock.FACING, f),
                (root, s) -> root.offset(TurbineAssemblyBlock.turn(b.cellOffset(s, 1), s.getValue(TurbineAssemblyBlock.FACING))),
                b::cellCount);
    }

    private static Kind condenser() {
        CondenserBlock b = BwrBlocks.ARABELLE_CONDENSER.get();
        var layout = CondenserLayout.INSTANCE;
        return new Kind("condenser", b, f -> b.defaultBlockState().setValue(CondenserBlock.FACING, f), null, null,
                (root, s) -> layout.cells.stream().map(c -> layout.world(root, s.getValue(CondenserBlock.FACING), c))
                        .filter(p -> !p.equals(root)).findFirst().orElseThrow(),
                s -> layout.cells.size());
    }

    private static Kind cooling(String name, CoolingBlock b) {
        var layout = b.layout();
        return new Kind(name, b, f -> b.defaultBlockState().setValue(CoolingBlock.FACING, f), null, null,
                (root, s) -> layout.cells.stream().map(c -> layout.world(root, s.getValue(CoolingBlock.FACING), c))
                        .filter(p -> !p.equals(root)).reduce((a, c) -> c).orElseThrow(),
                s -> layout.cells.size());
    }

    private static Kind airlock() {
        ContainmentAirlockBlock b = BwrBlocks.CONTAINMENT_AIRLOCK.get();
        return new Kind("airlock", b, f -> b.defaultBlockState().setValue(ContainmentAirlockBlock.FACING, f), null, null,
                (root, s) -> b.cell(root, s.getValue(ContainmentAirlockBlock.FACING), 5), s -> 6);
    }

    private static Kind vault(String name, ContainmentVaultDoorBlock b) {
        return new Kind(name, b, f -> b.defaultBlockState().setValue(ContainmentVaultDoorBlock.FACING, f), null, null,
                (root, s) -> b.cell(root, s.getValue(ContainmentVaultDoorBlock.FACING), b.size() * b.size() - 1), s -> b.size() * b.size());
    }

    private static Kind rtg() {
        LargeRtgBlock b = BwrBlocks.LARGE_RTG.get();
        return new Kind("large rtg", b, f -> b.defaultBlockState().setValue(LargeRtgBlock.FACING, f), null, null,
                (root, s) -> LargeRtgBlock.cellPos(root, s.getValue(LargeRtgBlock.FACING), LargeRtgBlock.CELLS - 1), s -> LargeRtgBlock.CELLS);
    }

    private static Kind msiv() {
        MainSteamIsolationValveBlock b = BwrBlocks.MSIV.get();
        Function<Direction, BlockState> single = f -> b.defaultBlockState().setValue(MainSteamIsolationValveBlock.FACING, f);
        return new Kind("msiv", b, f -> b.placementState().setValue(MainSteamIsolationValveBlock.FACING, f), single, single,
                (root, s) -> root.above(2), s -> 3);
    }

    private static Kind exchanger() {
        RhrHeatExchangerBlock b = BwrBlocks.RHR_HEAT_EXCHANGER.get();
        Function<Direction, BlockState> single = f -> b.defaultBlockState().setValue(RhrHeatExchangerBlock.FACING, f);
        return new Kind("rhr heat exchanger", b, f -> b.placementState().setValue(RhrHeatExchangerBlock.FACING, f), single, single,
                (root, s) -> RhrHeatExchangerBlock.cellPos(root, s, RhrHeatExchangerBlock.LAYOUT.controller == 0 ? 1 : 0),
                s -> RhrHeatExchangerBlock.LAYOUT.count());
    }

    private static Group small() {
        return new Group(List.of(turbine("hpci turbine", BwrBlocks.HPCI_TURBINE.get()), turbine("rcic turbine", BwrBlocks.RCIC_TWL.get()), condenser(),
                cooling("circulating water pump", BwrBlocks.CIRCULATING_WATER_PUMP.get()), cooling("makeup water pump", BwrBlocks.MAKEUP_WATER_PUMP.get()),
                cooling("screened water intake", BwrBlocks.SCREENED_WATER_INTAKE.get()), airlock(),
                vault("vault door 3x3", BwrBlocks.CONTAINMENT_VAULT_DOOR.get()), vault("vault door 4x4", BwrBlocks.CONTAINMENT_VAULT_DOOR_4X4.get()),
                rtg(), msiv(), exchanger()), 28, 28, 14, 24);
    }

    private static Group towers() {
        return new Group(List.of(cooling("mechanical draft tower", BwrBlocks.MECHANICAL_DRAFT_TOWER.get()),
                cooling("natural draft tower", BwrBlocks.NATURAL_DRAFT_TOWER.get())), 64, 64, 26, 44);
    }

    private static final int X0 = -95000, Z0 = -95000, Y0 = 150;

    private static BlockPos at(int row, Group g, int i, int slot) {
        return new BlockPos(X0 + g.dx() * i, Y0, Z0 + 400 * row + g.dz() * slot);
    }

    private static AABB rowBounds(int row, Group g, int slots) {
        BlockPos a = at(row, g, 0, 0), b = at(row, g, g.kinds().size() - 1, slots - 1);
        return new AABB(a.getX() - g.reach(), a.getY() - 12, a.getZ() - g.reach(), b.getX() + g.reach(), a.getY() + g.up(), b.getZ() + g.reach());
    }

    private static void clear(ServerLevel l, AABB box) {
        for (var p : BlockPos.betweenClosed((int) box.minX, (int) box.minY, (int) box.minZ, (int) box.maxX, (int) box.maxY, (int) box.maxZ)) {
            if (!l.getBlockState(p).isAir()) l.setBlock(p, Blocks.AIR.defaultBlockState(), 2 | 16);
        }
        for (var e : l.getEntitiesOfClass(ItemEntity.class, box.inflate(4))) e.discard();
    }

    @FunctionalInterface
    private interface Later {
        void run() throws Exception;
    }

    /** Runs {@code now} with the row's chunks live and clear, then {@code later} after {@code delay} ticks of the real server, then clears. */
    private static void run(GameTestHelper h, int row, Group g, int slots, int delay, Later now, Later later) {
        ServerLevel l = h.getLevel();
        AABB box = rowBounds(row, g, slots);
        EntityLookupProbe.runWhenLive(h, box.inflate(8), () -> {
            clear(l, box);
            try {
                now.run();
            } catch (Throwable t) {
                clear(l, box);
                throw t;
            }
            h.runAfterDelay(delay, () -> {
                try {
                    if (later != null) later.run();
                    h.succeed();
                } catch (RuntimeException | Error e) {
                    throw e;
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    clear(l, box);
                }
            });
        });
    }

    private static FakePlayer player(ServerLevel l, String name, GameType mode, BlockPos near) {
        FakePlayer p = FakePlayerFactory.get(l, new GameProfile(UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)), name));
        p.setGameMode(mode);
        p.getInventory().clearContent();
        p.setPos(Vec3.atCenterOf(near.above(60)));
        return p;
    }

    private static int count(FakePlayer p, Item item) {
        int n = 0;
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (s.is(item)) n += s.getCount();
        }
        return n;
    }

    private static int droppedNear(ServerLevel l, BlockPos p, int radius, Item item) {
        return l.getEntitiesOfClass(ItemEntity.class, new AABB(p).inflate(radius), e -> e.getItem().is(item)).stream().mapToInt(e -> e.getItem().getCount()).sum();
    }

    /** Blocks of {@code b} in the footprint's reach of {@code p}. */
    private static int cellsOf(ServerLevel l, Block b, BlockPos p, Group g) {
        int n = 0;
        for (var q : BlockPos.betweenClosed(p.offset(-g.reach(), -12, -g.reach()), p.offset(g.reach(), g.up(), g.reach()))) if (l.getBlockState(q).is(b)) n++;
        return n;
    }

    /**
     * Effortless 3.4.0's BlockStateUpdateOperation.updateBlock once it holds {@code state}: setBlockAndUpdate, Block.place (which is
     * Block.setPlacedBy with the player and the storage stack, in survival the player's own inventory stack), then in survival one
     * item consumed.
     */
    private static void effortless(ServerLevel l, FakePlayer player, BlockPos p, BlockState state, ItemStack stack) {
        if (!l.setBlockAndUpdate(p, state)) throw new IllegalStateException("setBlockAndUpdate refused " + state);
        state.getBlock().setPlacedBy(l, p, state, player, stack);
        if (!player.getAbilities().instabuild) stack.shrink(1);
    }

    private static void check(GameTestHelper h, boolean ok, String what) {
        h.assertTrue(ok, what);
    }

    private static Direction facingOf(BlockState s) {
        return s.getValue(BlockStateProperties.HORIZONTAL_FACING);
    }

    // ------------------------------------------------------------------------------------------------ refusal gives the item back

    private static void blockedPlacementGivesTheItemBack(GameTestHelper h, int row, Group g) {
        ServerLevel l = h.getLevel();
        boolean compactToo = g.kinds().stream().anyMatch(k -> k.compact() != null);
        int slots = compactToo ? 6 : 3;
        run(h, row, g, slots, 5, () -> {
            for (int i = 0; i < g.kinds().size(); i++) {
                Kind k = g.kinds().get(i);
                Item item = k.item();
                Direction d = DIRS[i % 4];
                // variant 0: the full state, as a pattern copy or the plain default state hands it over; variant 1: the one-cell state
                for (int v = 0; v < (k.compact() != null ? 2 : 1); v++) {
                    Direction f = v == 0 ? d : d.getOpposite();
                    BlockState handed = v == 0 ? k.full().apply(d) : k.compact().apply(DIRS[(i + 1) % 4]);
                    // survival, creative and no placer at all, each at its own spot
                    for (int mode = 0; mode < 3; mode++) {
                        BlockPos p = at(row, g, i, 3 * v + mode);
                        BlockPos blocker = k.blocker().apply(p, k.full().apply(f));
                        check(h, !blocker.equals(p), k + ": the blocker is not the root");
                        l.setBlock(blocker, Blocks.STONE.defaultBlockState(), 3);
                        String what = k + (v == 0 ? "" : " (one-cell state)");
                        if (mode == 2) {
                            if (v == 1) handed = handed.setValue(BlockStateProperties.HORIZONTAL_FACING, f);
                            l.setBlock(p, handed, 3);
                            handed.getBlock().setPlacedBy(l, p, handed, null, new ItemStack(k.block()));
                            int want = k.block() instanceof TurbineAssemblyBlock ? 0 : 1; // the turbine keeps TurbineAssemblyRuntimeCheck's "no placer, nothing dropped"
                            check(h, droppedNear(l, p, 2, item) == want, what + " with no placer: " + want + " item(s) dropped back");
                        } else {
                            FakePlayer player = player(l, "bwr_refund_a" + mode, mode == 0 ? GameType.SURVIVAL : GameType.CREATIVE, at(row, g, 0, 0));
                            player.setYRot(d.toYRot());
                            ItemStack stack = new ItemStack(k.block(), 2);
                            player.setItemInHand(InteractionHand.MAIN_HAND, stack);
                            effortless(l, player, p, handed, stack);
                            check(h, count(player, item) == 2, what + (mode == 0 ? " in survival: the spent item came back, exactly one" : " in creative: nothing taken, nothing given") + "; has " + count(player, item));
                            check(h, droppedNear(l, p, 12, item) == 0, what + ": nothing dropped in the world");
                        }
                        check(h, l.getBlockState(p).isAir() && l.getBlockState(blocker).is(Blocks.STONE), what + ": the spot is empty and the blocker stands");
                        check(h, cellsOf(l, k.block(), p, g) == 0, what + ": no block or stray cell is left");
                        l.removeBlock(blocker, false);
                        for (var e : l.getEntitiesOfClass(ItemEntity.class, new AABB(p).inflate(12))) e.discard();
                    }
                }
            }
        }, null);
    }

    @GameTest(template = "empty", timeoutTicks = 900)
    public static void aRefusedPlacementGivesTheItemBackAndCreativeIsNotCharged(GameTestHelper h) {
        blockedPlacementGivesTheItemBack(h, 0, small());
    }

    @GameTest(template = "empty", timeoutTicks = 900)
    public static void aRefusedCoolingTowerPlacementGivesTheItemBackAndCreativeIsNotCharged(GameTestHelper h) {
        blockedPlacementGivesTheItemBack(h, 1, towers());
    }

    // ------------------------------------------------------------------------------------------------ the player's own item use

    private static void aPlayersItemStillBuildsTheFullStructure(GameTestHelper h, int row, Group g) {
        ServerLevel l = h.getLevel();
        List<BlockState> placed = new ArrayList<>();
        run(h, row, g, 3, 60, () -> {
            for (int i = 0; i < g.kinds().size(); i++) {
                Kind k = g.kinds().get(i);
                Item item = k.item();
                Direction d = DIRS[i % 4];
                for (int mode = 0; mode < 2; mode++) {
                    BlockPos p = at(row, g, i, mode);
                    FakePlayer player = player(l, "bwr_refund_b" + mode, mode == 0 ? GameType.SURVIVAL : GameType.CREATIVE, at(row, g, 0, 0));
                    player.setYRot(d.toYRot());
                    player.setXRot(0);
                    ItemStack stack = new ItemStack(k.block(), 2);
                    player.setItemInHand(InteractionHand.MAIN_HAND, stack);
                    var hit = new BlockHitResult(Vec3.atCenterOf(p), Direction.UP, p, false);
                    check(h, stack.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, hit)).consumesAction(), k + ": the item placed");
                    BlockState s = l.getBlockState(p);
                    check(h, s.is(k.block()), k + ": the root is at the clicked spot: " + s);
                    check(h, cellsOf(l, k.block(), p, g) == k.cells().applyAsInt(s), k + ": the whole structure stands, " + k.cells().applyAsInt(s) + " cells; found " + cellsOf(l, k.block(), p, g));
                    check(h, count(player, item) == (mode == 0 ? 1 : 2) && droppedNear(l, p, 30, item) == 0, k + ": the item was taken once in survival, not in creative; has " + count(player, item));
                    placed.add(s);
                }
                // and with no room the item is refused before anything is set down, as before
                BlockPos q = at(row, g, i, 2);
                // the airlock faces the player, the others away from it: block the footprint either way
                BlockPos blocker = k.blocker().apply(q, k.full().apply(d.getOpposite()));
                BlockPos blocker2 = k.blocker().apply(q, k.full().apply(d));
                l.setBlock(blocker, Blocks.STONE.defaultBlockState(), 3);
                l.setBlock(blocker2, Blocks.STONE.defaultBlockState(), 3);
                FakePlayer player = player(l, "bwr_refund_b0", GameType.SURVIVAL, at(row, g, 0, 0));
                player.setYRot(d.toYRot());
                ItemStack stack = new ItemStack(k.block(), 2);
                player.setItemInHand(InteractionHand.MAIN_HAND, stack);
                var hit = new BlockHitResult(Vec3.atCenterOf(q), Direction.UP, q, false);
                check(h, !stack.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, hit)).consumesAction(), k + ": no room, no placement");
                check(h, l.getBlockState(q).isAir() && count(player, item) == 2 && droppedNear(l, q, 30, item) == 0, k + ": no room: nothing set down, nothing taken");
                l.removeBlock(blocker, false);
                l.removeBlock(blocker2, false);
            }
        }, () -> {
            for (int i = 0; i < g.kinds().size(); i++) {
                Kind k = g.kinds().get(i);
                for (int mode = 0; mode < 2; mode++) {
                    BlockPos p = at(row, g, i, mode);
                    BlockState s = l.getBlockState(p);
                    check(h, s == placed.get(2 * i + mode) && cellsOf(l, k.block(), p, g) == k.cells().applyAsInt(s), k + ": still whole after 60 ticks: " + s);
                }
            }
        });
    }

    @GameTest(template = "empty", timeoutTicks = 900)
    public static void anItemUsedByAPlayerStillBuildsTheFullStructureAndTakesTheItemOnce(GameTestHelper h) {
        aPlayersItemStillBuildsTheFullStructure(h, 2, small());
    }

    @GameTest(template = "empty", timeoutTicks = 900)
    public static void aCoolingTowerItemUsedByAPlayerStillBuildsInFullAndTakesTheItemOnce(GameTestHelper h) {
        aPlayersItemStillBuildsTheFullStructure(h, 3, towers());
    }

    // ------------------------------------------------------------------------------------------------ one-cell state from a building mod

    @GameTest(template = "empty", timeoutTicks = 900)
    public static void aOneCellStateFromABuildingModBuildsInFullFacingThePlacerAndStays(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        Group g = small();
        List<Kind> kinds = g.kinds().stream().filter(k -> k.compact() != null).toList();
        check(h, kinds.size() == 2, "the exchanger and the valve are the kinds with a one-cell state");
        Group only = new Group(kinds, g.dx(), g.dz(), g.reach(), g.up());
        List<BlockState> expected = new ArrayList<>();
        run(h, 4, only, 2, 60, () -> {
            for (int i = 0; i < kinds.size(); i++) {
                Kind k = kinds.get(i);
                Direction d = DIRS[i % 4];
                for (int mode = 0; mode < 2; mode++) {
                    BlockPos p = at(4, only, i, mode);
                    FakePlayer player = player(l, "bwr_refund_c" + mode, mode == 0 ? GameType.SURVIVAL : GameType.CREATIVE, at(4, only, 0, 0));
                    player.setYRot(d.toYRot());
                    check(h, player.getDirection() == d, "the player looks " + d);
                    ItemStack stack = new ItemStack(k.block(), 2);
                    player.setItemInHand(InteractionHand.MAIN_HAND, stack);
                    effortless(l, player, p, k.compact().apply(DIRS[(i + 1) % 4]), stack);
                    BlockState s = l.getBlockState(p);
                    BlockState want = k.full().apply(d.getOpposite());
                    check(h, s == want, k + ": the full machine as getStateForPlacement gives it, facing the placer: " + want + ", got " + s);
                    check(h, cellsOf(l, k.block(), p, g) == k.cells().applyAsInt(s), k + ": every cell is there; found " + cellsOf(l, k.block(), p, g));
                    check(h, l.getBlockEntity(p) != null && !l.getBlockEntity(p).isRemoved(), k + ": the controller has its block entity");
                    check(h, count(player, k.item()) == (mode == 0 ? 1 : 2) && droppedNear(l, p, 30, k.item()) == 0, k + ": one item spent in survival, none in creative, none dropped");
                    expected.add(want);
                }
            }
        }, () -> {
            for (int i = 0; i < kinds.size(); i++) {
                Kind k = kinds.get(i);
                for (int mode = 0; mode < 2; mode++) {
                    BlockPos p = at(4, only, i, mode);
                    BlockState s = l.getBlockState(p);
                    check(h, s == expected.get(2 * i + mode) && cellsOf(l, k.block(), p, g) == k.cells().applyAsInt(s), k + ": still whole after 60 ticks: " + s);
                    check(h, droppedNear(l, p, 30, k.item()) == 0, k + ": nothing dropped since");
                }
            }
        });
    }

    // ------------------------------------------------------------------------------------------------ old saves

    @GameTest(template = "empty", timeoutTicks = 900)
    public static void oldSaveStatesStayAsTheyAre(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        Group g = small();
        List<Kind> kinds = g.kinds().stream().filter(k -> k.legacy() != null).toList();
        check(h, kinds.size() == 4, "the legacy turbine boxes, the single exchanger and the single valve have an old-save state");
        Group only = new Group(kinds, g.dx(), g.dz(), g.reach(), g.up());
        run(h, 5, only, 3, 60, () -> {
            for (int i = 0; i < kinds.size(); i++) {
                Kind k = kinds.get(i);
                Direction d = DIRS[i % 4];
                BlockState old = k.legacy().apply(d);
                if (k.block() instanceof TurbineAssemblyBlock t) {
                    check(h, !old.getValue(TurbineAssemblyBlock.MODERN), k + ": the default state is the legacy box");
                    int n = t.cellCount(old);
                    // a saved legacy skid: every cell set as the save stores it, setPlacedBy never called
                    BlockPos p = at(5, only, i, 0);
                    for (int c = 0; c < n; c++) l.setBlock(p.offset(TurbineAssemblyBlock.turn(t.cellOffset(old, c), d)), old.setValue(TurbineAssemblyBlock.CELL, c), 3);
                    check(h, t.complete(l, p, old) && cellsOf(l, k.block(), p, g) == n, k + ": the saved box loaded whole");
                    // a legacy default state that does reach setPlacedBy builds the same legacy box, not the modern layout
                    BlockPos q = at(5, only, i, 1);
                    FakePlayer player = player(l, "bwr_refund_d", GameType.SURVIVAL, at(5, only, 0, 0));
                    player.setYRot(d.toYRot());
                    ItemStack stack = new ItemStack(k.block(), 2);
                    player.setItemInHand(InteractionHand.MAIN_HAND, stack);
                    effortless(l, player, q, old, stack);
                    check(h, l.getBlockState(q) == old && t.complete(l, q, old) && cellsOf(l, k.block(), q, g) == n, k + ": the default state placed by a mod stays the legacy box of " + n + " cells: " + l.getBlockState(q));
                    check(h, count(player, k.item()) == 1 && droppedNear(l, q, 30, k.item()) == 0, k + ": and the item is spent once");
                    // and where the box does not fit, the item comes back
                    BlockPos r = at(5, only, i, 2);
                    BlockPos blocker = r.offset(TurbineAssemblyBlock.turn(t.cellOffset(old, n - 1), d));
                    l.setBlock(blocker, Blocks.STONE.defaultBlockState(), 3);
                    FakePlayer again = player(l, "bwr_refund_d", GameType.SURVIVAL, at(5, only, 0, 0));
                    again.setYRot(d.toYRot());
                    ItemStack stack2 = new ItemStack(k.block(), 2);
                    again.setItemInHand(InteractionHand.MAIN_HAND, stack2);
                    effortless(l, again, r, old, stack2);
                    check(h, l.getBlockState(r).isAir() && l.getBlockState(blocker).is(Blocks.STONE) && cellsOf(l, k.block(), r, g) == 0 && count(again, k.item()) == 2,
                            k + ": a blocked legacy box sets nothing down and the item is back");
                    l.removeBlock(blocker, false);
                } else {
                    // a single block as an alpha save stores it, set without setPlacedBy, neighbours all round
                    BlockPos p = at(5, only, i, 0);
                    for (var dir : Direction.values()) l.setBlock(p.relative(dir), Blocks.STONE.defaultBlockState(), 3);
                    l.setBlock(p, old, 3);
                    check(h, l.getBlockState(p) == old && cellsOf(l, k.block(), p, g) == 1, k + ": the single block loaded as stored");
                }
            }
        }, () -> {
            for (int i = 0; i < kinds.size(); i++) {
                Kind k = kinds.get(i);
                Direction d = DIRS[i % 4];
                BlockState old = k.legacy().apply(d);
                if (k.block() instanceof TurbineAssemblyBlock t) {
                    BlockPos p = at(5, only, i, 0), q = at(5, only, i, 1);
                    check(h, l.getBlockState(p) == old && t.complete(l, p, old), k + ": the saved legacy box is unchanged after 60 ticks: " + l.getBlockState(p));
                    check(h, l.getBlockState(q) == old && t.complete(l, q, old), k + ": the legacy box a mod placed is unchanged after 60 ticks: " + l.getBlockState(q));
                } else {
                    BlockPos p = at(5, only, i, 0);
                    // (a valve may have shut itself since: the open flag is its own, the form is what must stay)
                    BlockState now = l.getBlockState(p);
                    check(h, now.is(k.block()) && facingOf(now) == d && cellsOf(l, k.block(), p, g) == 1 && !(k.block() instanceof MainSteamIsolationValveBlock
                            && now.getValue(MainSteamIsolationValveBlock.ASSEMBLED)) && !(k.block() instanceof RhrHeatExchangerBlock && RhrHeatExchangerBlock.isAssembled(now)),
                            k + ": the single block was not expanded: " + now);
                    for (var dir : Direction.values()) check(h, l.getBlockState(p.relative(dir)).is(Blocks.STONE), k + ": neighbour " + dir + " untouched");
                    check(h, l.getBlockEntity(p) != null, k + ": the single block keeps its block entity");
                    check(h, droppedNear(l, p, 30, k.item()) == 0, k + ": nothing dropped");
                }
            }
        });
    }
}
