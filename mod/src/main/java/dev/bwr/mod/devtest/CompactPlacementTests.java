package dev.bwr.mod.devtest;

import com.mojang.authlib.GameProfile;
import dev.bwr.mod.eccs.PumpAssemblyBlock;
import dev.bwr.mod.eccs.TurbineAssemblyBlock;
import dev.bwr.mod.registry.BwrBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
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

/**
 * Placing a turbine, generator or pump the way a building mod does, beside the vanilla way. Effortless Structure 3.4.0 (read from its
 * jar) asks {@code BlockItem.getPlacementState}, calls {@code setBlockAndUpdate}, then {@code Block.setPlacedBy} with the inventory
 * stack, and takes the item afterwards in survival; its Array, Mirror and Radial patterns place a copy of that state elsewhere
 * without asking again. A mod that hands over the default (compact) state instead gets the full assembly or its item back. Each test
 * has its own row of every kind, far from the others; compact blocks set without setPlacedBy (old saves) stay as they are.
 */
@GameTestHolder("bwrturbine")
@PrefixGameTestTemplate(false)
public final class CompactPlacementTests {
    private CompactPlacementTests() {
    }

    private static final Direction[] DIRS = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};

    private static BlockPos at(int row, int i) {
        return new BlockPos(-93000 + 24 * i, 150, -93000 + 64 * row);
    }

    private static List<PumpAssemblyBlock> kinds() {
        return List.of(BwrBlocks.HP_TURBINE.get(), BwrBlocks.LP_TURBINE.get(), BwrBlocks.NUCLEAR_GENERATOR.get(),
                BwrBlocks.LPCS_PUMP.get(), BwrBlocks.HPCS_PUMP.get(), BwrBlocks.RHR_PUMP.get(), BwrBlocks.MOTOR_FEED_PUMP.get(),
                BwrBlocks.TURBINE_FEED_PUMP.get(), BwrBlocks.SLC_PUMP.get(), BwrBlocks.SLC_BORON_TANK.get(), BwrBlocks.JET_PUMP.get(),
                BwrBlocks.RIP_PUMP.get(), BwrBlocks.RECIRCULATION_PUMP.get());
    }

    private static AABB rowBounds(int row) {
        BlockPos a = at(row, 0), b = at(row, kinds().size() - 1);
        return new AABB(a.getX() - 14, a.getY() - 12, a.getZ() - 14, b.getX() + 14, a.getY() + 16, b.getZ() + 14);
    }

    private static void clear(ServerLevel l, int row) {
        AABB box = rowBounds(row);
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
    private static void run(GameTestHelper h, int row, int delay, Later now, Later later) {
        ServerLevel l = h.getLevel();
        EntityLookupProbe.runWhenLive(h, rowBounds(row).inflate(8), () -> {
            clear(l, row);
            try {
                now.run();
            } catch (Throwable t) {
                clear(l, row);
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
                    clear(l, row);
                }
            });
        });
    }

    private static FakePlayer player(ServerLevel l, String name, GameType mode) {
        FakePlayer p = FakePlayerFactory.get(l, new GameProfile(UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)), name));
        p.setGameMode(mode);
        p.getInventory().clearContent();
        p.setPos(Vec3.atCenterOf(at(0, 0).above(40)));
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

    private static int dropped(ServerLevel l, int row, Item item) {
        return l.getEntitiesOfClass(ItemEntity.class, rowBounds(row).inflate(4), e -> e.getItem().is(item)).stream()
                .mapToInt(e -> e.getItem().getCount()).sum();
    }

    /** Any block of {@code b} within the footprint's reach of {@code p}. */
    private static boolean anyLeft(ServerLevel l, PumpAssemblyBlock b, BlockPos p) {
        for (var q : BlockPos.betweenClosed(p.offset(-10, -10, -10), p.offset(10, 10, 10))) if (l.getBlockState(q).is(b)) return true;
        return false;
    }

    /**
     * Effortless 3.4.0's BlockStateUpdateOperation.updateBlock once it holds {@code state}: World.setBlockAndUpdate, Block.place (which
     * is Block.setPlacedBy with the player and the storage stack, in survival the player's own inventory stack), then in survival
     * Storage.consume of one item.
     */
    private static void effortless(ServerLevel l, FakePlayer player, BlockPos p, BlockState state, ItemStack stack) {
        if (!l.setBlockAndUpdate(p, state)) throw new IllegalStateException("setBlockAndUpdate refused " + state);
        state.getBlock().setPlacedBy(l, p, state, player, stack);
        if (!player.getAbilities().instabuild) stack.shrink(1);
    }

    /** A cell of {@code full} at {@code p} that is not the controller, where a stone goes to leave no room. */
    private static BlockPos blockerFor(PumpAssemblyBlock b, BlockState full, BlockPos p) {
        int k = b.controllerCell(full) == 0 ? 1 : 0;
        return p.offset(TurbineAssemblyBlock.turn(b.cellOffset(full, k), full.getValue(PumpAssemblyBlock.FACING)));
    }

    private static void check(GameTestHelper h, boolean ok, String what) {
        h.assertTrue(ok, what);
    }

    // ------------------------------------------------------------------------------------------------ compact state by a building mod

    @GameTest(template = "empty", timeoutTicks = 600)
    public static void aCompactStateFromABuildingModBuildsEveryKindInFullFacingThePlacerAndItStays(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        List<BlockEntity> controllers = new ArrayList<>();
        List<BlockState> expected = new ArrayList<>();
        run(h, 0, 80, () -> {
            FakePlayer player = player(l, "bwr_compact_place_a", GameType.SURVIVAL);
            var kinds = kinds();
            for (int i = 0; i < kinds.size(); i++) {
                var b = kinds.get(i);
                check(h, b.asItem() != Items.AIR, b + " has an item");
                BlockPos p = at(0, i);
                Direction d = DIRS[i % 4];
                player.setYRot(d.toYRot());
                check(h, player.getDirection() == d, "the player looks " + d);
                player.getInventory().clearContent();
                ItemStack stack = new ItemStack(b, 2);
                player.setItemInHand(InteractionHand.MAIN_HAND, stack);
                effortless(l, player, p, b.defaultBlockState().setValue(PumpAssemblyBlock.FACING, DIRS[(i + 1) % 4]), stack);
                BlockState s = l.getBlockState(p);
                BlockState want = b.placementState().setValue(PumpAssemblyBlock.FACING, d.getOpposite());
                check(h, s == want, b + ": the full assembly as getStateForPlacement gives it, facing the placer: " + want + ", got " + s);
                check(h, b.complete(l, b.origin(p, s), s), b + ": every cell is there and owned");
                boolean hasController = b.newBlockEntity(p, s) != null;
                BlockEntity be = l.getBlockEntity(p);
                check(h, hasController == (be != null), b + ": the controller block entity is there exactly when the kind has one: " + be);
                check(h, count(player, b.asItem()) == 1 && dropped(l, 0, b.asItem()) == 0, b + ": one item spent, none given back or dropped");
                controllers.add(be);
                expected.add(want);
            }
        }, () -> {
            var kinds = kinds();
            for (int i = 0; i < kinds.size(); i++) {
                var b = kinds.get(i);
                BlockPos p = at(0, i);
                BlockState s = l.getBlockState(p);
                check(h, s == expected.get(i) && b.complete(l, b.origin(p, s), s), b + ": still whole after 80 ticks of its own scheduled checks: " + s);
                BlockEntity be = controllers.get(i);
                check(h, be == null || (l.getBlockEntity(p) == be && !be.isRemoved()), b + ": the same controller block entity lives on");
                check(h, dropped(l, 0, b.asItem()) == 0, b + ": nothing dropped since");
            }
        });
    }

    @GameTest(template = "empty", timeoutTicks = 600)
    public static void aCompactStateWithoutRoomGivesExactlyOneItemBackAndLeavesNothing(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        run(h, 1, 5, () -> {
            var kinds = kinds();
            for (int i = 0; i < kinds.size(); i++) {
                var b = kinds.get(i);
                Item item = b.asItem();
                Direction d = DIRS[i % 4];
                BlockState full = b.placementState().setValue(PumpAssemblyBlock.FACING, d.getOpposite());
                // survival, creative and no placer at all, each at its own spot along z
                for (int mode = 0; mode < 3; mode++) {
                    BlockPos p = at(1, i).offset(0, 0, mode == 0 ? 0 : mode == 1 ? -10 : 10);
                    BlockPos blocker = blockerFor(b, full, p);
                    l.setBlock(blocker, Blocks.STONE.defaultBlockState(), 3);
                    if (mode == 2) {
                        // with no placer to face, the assembly keeps the facing the compact state came with
                        BlockState compact = b.defaultBlockState().setValue(PumpAssemblyBlock.FACING, d.getOpposite());
                        l.setBlock(p, compact, 3);
                        b.setPlacedBy(l, p, compact, null, new ItemStack(b));
                        check(h, l.getEntitiesOfClass(ItemEntity.class, new AABB(p).inflate(2), e -> e.getItem().is(item)).stream()
                                .mapToInt(e -> e.getItem().getCount()).sum() == 1, b + " with no placer: exactly one item dropped back");
                    } else {
                        FakePlayer player = player(l, "bwr_compact_place_b" + mode, mode == 0 ? GameType.SURVIVAL : GameType.CREATIVE);
                        player.setYRot(d.toYRot());
                        ItemStack stack = new ItemStack(b, 2);
                        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
                        effortless(l, player, p, b.defaultBlockState(), stack);
                        check(h, count(player, item) == 2, b + (mode == 0 ? " in survival: the spent item came back, exactly one" : " in creative: nothing taken, nothing given") + "; has " + count(player, item));
                        check(h, l.getEntitiesOfClass(ItemEntity.class, new AABB(p).inflate(12), e -> e.getItem().is(item)).isEmpty(), b + ": nothing dropped in the world");
                    }
                    check(h, l.getBlockState(p).isAir() && l.getBlockState(blocker).is(Blocks.STONE), b + ": the spot is empty and the blocker stands");
                    check(h, !anyLeft(l, b, p), b + ": no compact block or stray cell is left");
                    l.removeBlock(blocker, false);
                    for (var e : l.getEntitiesOfClass(ItemEntity.class, new AABB(p).inflate(12))) e.discard();
                }
            }
        }, null);
    }

    // ------------------------------------------------------------------------------------------------ Effortless pattern copies

    @GameTest(template = "empty", timeoutTicks = 600)
    public static void aFullStateCopiedToAPlaceWithoutRoomGivesTheItemBack(GameTestHelper h) {
        // Effortless's Array/Mirror/Radial patterns move the state getStateForPlacement gave for the clicked spot and set it down
        // elsewhere, then take the item. Where that footprint is not free the block goes, and the item must come back with it.
        ServerLevel l = h.getLevel();
        run(h, 2, 5, () -> {
            var kinds = kinds();
            for (int i = 0; i < kinds.size(); i++) {
                var b = kinds.get(i);
                Item item = b.asItem();
                Direction d = DIRS[i % 4];
                BlockState full = b.placementState().setValue(PumpAssemblyBlock.FACING, d.getOpposite());
                for (int mode = 0; mode < 2; mode++) {
                    BlockPos p = at(2, i).offset(0, 0, mode == 0 ? -6 : 6);
                    BlockPos blocker = blockerFor(b, full, p);
                    l.setBlock(blocker, Blocks.STONE.defaultBlockState(), 3);
                    FakePlayer player = player(l, "bwr_compact_place_c" + mode, mode == 0 ? GameType.SURVIVAL : GameType.CREATIVE);
                    player.setYRot(d.toYRot());
                    ItemStack stack = new ItemStack(b, 2);
                    player.setItemInHand(InteractionHand.MAIN_HAND, stack);
                    effortless(l, player, p, full, stack);
                    check(h, count(player, item) == 2, b + (mode == 0 ? " in survival: the spent item came back" : " in creative: nothing taken, nothing given") + "; has " + count(player, item));
                    check(h, l.getEntitiesOfClass(ItemEntity.class, new AABB(p).inflate(12), e -> e.getItem().is(item)).isEmpty(), b + ": nothing dropped in the world");
                    check(h, l.getBlockState(p).isAir() && l.getBlockState(blocker).is(Blocks.STONE) && !anyLeft(l, b, p), b + ": nothing of it is left and the blocker stands");
                    l.removeBlock(blocker, false);
                }
            }
        }, null);
    }

    // ------------------------------------------------------------------------------------------------ vanilla placement, unchanged

    @GameTest(template = "empty", timeoutTicks = 600)
    public static void anItemUsedByAPlayerStillPlacesTheFullAssemblyAndTakesTheItemOnce(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        List<BlockState> expected = new ArrayList<>();
        run(h, 3, 80, () -> {
            var kinds = kinds();
            for (int i = 0; i < kinds.size(); i++) {
                var b = kinds.get(i);
                Item item = b.asItem();
                Direction d = DIRS[i % 4];
                for (int mode = 0; mode < 2; mode++) {
                    BlockPos p = at(3, i).offset(0, 0, mode == 0 ? -6 : 6);
                    FakePlayer player = player(l, "bwr_compact_place_d" + mode, mode == 0 ? GameType.SURVIVAL : GameType.CREATIVE);
                    player.setYRot(d.toYRot());
                    player.setXRot(0);
                    ItemStack stack = new ItemStack(b, 2);
                    player.setItemInHand(InteractionHand.MAIN_HAND, stack);
                    var hit = new BlockHitResult(Vec3.atCenterOf(p), Direction.UP, p, false);
                    check(h, stack.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, hit)).consumesAction(), b + ": the item placed");
                    BlockState s = l.getBlockState(p);
                    BlockState want = b.placementState().setValue(PumpAssemblyBlock.FACING, d.getOpposite());
                    check(h, s == want && b.complete(l, b.origin(p, s), s), b + ": full, owned and facing the player: " + s);
                    check(h, (b.newBlockEntity(p, s) != null) == (l.getBlockEntity(p) != null), b + ": controller block entity");
                    check(h, count(player, item) == (mode == 0 ? 1 : 2) && dropped(l, 3, item) == 0, b + ": the item was taken once in survival, not in creative");
                    expected.add(want);
                }
                // and with no room the item is refused before anything is set down, as before
                BlockPos q = at(3, i).offset(0, 0, -18);
                BlockState full = b.placementState().setValue(PumpAssemblyBlock.FACING, d.getOpposite());
                BlockPos blocker = blockerFor(b, full, q);
                l.setBlock(blocker, Blocks.STONE.defaultBlockState(), 3);
                FakePlayer player = player(l, "bwr_compact_place_d0", GameType.SURVIVAL);
                player.setYRot(d.toYRot());
                ItemStack stack = new ItemStack(b, 2);
                player.setItemInHand(InteractionHand.MAIN_HAND, stack);
                var hit = new BlockHitResult(Vec3.atCenterOf(q), Direction.UP, q, false);
                check(h, !stack.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, hit)).consumesAction(), b + ": no room, no placement");
                check(h, l.getBlockState(q).isAir() && count(player, item) == 2 && dropped(l, 3, item) == 0, b + ": no room: nothing set down, nothing taken");
                l.removeBlock(blocker, false);
            }
        }, () -> {
            var kinds = kinds();
            for (int i = 0; i < kinds.size(); i++) {
                var b = kinds.get(i);
                for (int mode = 0; mode < 2; mode++) {
                    BlockPos p = at(3, i).offset(0, 0, mode == 0 ? -6 : 6);
                    BlockState s = l.getBlockState(p);
                    check(h, s == expected.get(2 * i + mode) && b.complete(l, b.origin(p, s), s), b + ": still whole after 80 ticks");
                }
            }
        });
    }

    // ------------------------------------------------------------------------------------------------ old saves

    @GameTest(template = "empty", timeoutTicks = 600)
    public static void aCompactBlockFromAnOldSaveIsLeftAsItIs(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        List<BlockState> placed = new ArrayList<>();
        List<BlockEntity> controllers = new ArrayList<>();
        run(h, 4, 80, () -> {
            var kinds = kinds();
            for (int i = 0; i < kinds.size(); i++) {
                var b = kinds.get(i);
                BlockPos p = at(4, i);
                BlockState compact = b.defaultBlockState().setValue(PumpAssemblyBlock.FACING, DIRS[i % 4]);
                check(h, !b.isFull(compact), b + ": the default state is the compact one");
                l.setBlock(p, compact, 3);
                placed.add(compact);
                controllers.add(l.getBlockEntity(p));
                check(h, l.getBlockState(p) == compact, b + ": set as saved");
            }
        }, () -> {
            var kinds = kinds();
            for (int i = 0; i < kinds.size(); i++) {
                var b = kinds.get(i);
                BlockPos p = at(4, i);
                check(h, l.getBlockState(p) == placed.get(i), b + ": the compact block is unchanged after 80 ticks: " + l.getBlockState(p));
                int cells = 0;
                for (var q : BlockPos.betweenClosed(p.offset(-10, -10, -10), p.offset(10, 10, 10))) if (l.getBlockState(q).is(b)) cells++;
                check(h, cells == 1, b + ": still one block, nothing built around it (" + cells + ")");
                BlockEntity be = controllers.get(i);
                check(h, be == null || (l.getBlockEntity(p) == be && !be.isRemoved()), b + ": its block entity is the same one");
                check(h, dropped(l, 4, b.asItem()) == 0, b + ": nothing dropped");
            }
        });
    }
}
