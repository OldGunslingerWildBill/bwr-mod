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
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Every turbine, generator and pump placed by the exact server-side sequence Effortless Structure 3.4.0 runs, as recorded from the
 * real mod (client key press, PlayerBuildPacket, a dedicated server; EffortlessReproCheck / EffortlessMpRepro): for each position of
 * the build, one at a time and just before it is set down, {@code ItemRandomizer.transform -> RefactorContext.refactor ->
 * MinecraftBlockItem.getPlacementState}, which is {@code BlockItem.getPlacementState} on a {@code BlockPlaceContext} whose hit result
 * names the target (air) position with the clicked face; a null state is {@code FAIL_BLOCK_STATE_NULL} and nothing happens; else
 * {@code World.setBlockAndUpdate}, {@code Block.setPlacedBy(level, pos, state, player, inventory stack)} and, in survival, one item
 * taken from that stack. A Line build asks again for each block of the line, so a position inside a turbine already set down gets
 * null. Without Effortless at runtime. Rows far from CompactPlacementTests' rows.
 */
@GameTestHolder("bwrturbine")
@PrefixGameTestTemplate(false)
public final class EffortlessSequenceTests {
    private EffortlessSequenceTests() {
    }

    private static final Direction[] DIRS = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
    private static final int LINE = 12;

    private static BlockPos at(int row, int i) {
        return new BlockPos(-97000 + 24 * i, 150, -97000 + 64 * row);
    }

    private static List<PumpAssemblyBlock> kinds() {
        return List.of(BwrBlocks.HP_TURBINE.get(), BwrBlocks.LP_TURBINE.get(), BwrBlocks.NUCLEAR_GENERATOR.get(),
                BwrBlocks.LPCS_PUMP.get(), BwrBlocks.HPCS_PUMP.get(), BwrBlocks.RHR_PUMP.get(), BwrBlocks.MOTOR_FEED_PUMP.get(),
                BwrBlocks.TURBINE_FEED_PUMP.get(), BwrBlocks.SLC_PUMP.get(), BwrBlocks.SLC_BORON_TANK.get(), BwrBlocks.JET_PUMP.get(),
                BwrBlocks.RIP_PUMP.get(), BwrBlocks.RECIRCULATION_PUMP.get());
    }

    private static AABB rowBounds(int row) {
        BlockPos a = at(row, 0), b = at(row, kinds().size() - 1);
        return new AABB(a.getX() - 14, a.getY() - 12, a.getZ() - 14, b.getX() + LINE + 14, a.getY() + 16, b.getZ() + 14);
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

    private static FakePlayer player(ServerLevel l, String name, GameType mode, Direction look) {
        FakePlayer p = FakePlayerFactory.get(l, new GameProfile(UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)), name));
        p.setGameMode(mode);
        p.getInventory().clearContent();
        p.setPos(Vec3.atCenterOf(at(0, 0).above(40)));
        p.setYRot(look.toYRot());
        p.setXRot(30);
        return p;
    }

    private static int count(FakePlayer p, net.minecraft.world.item.Item item) {
        int n = 0;
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (s.is(item)) n += s.getCount();
        }
        return n;
    }

    private static Method placementState;

    /** BlockItem.getPlacementState (protected in vanilla; Effortless opens it with an access transformer). */
    private static BlockState placementState(BlockItem item, BlockPlaceContext ctx) throws Exception {
        if (placementState == null) {
            placementState = BlockItem.class.getDeclaredMethod("getPlacementState", BlockPlaceContext.class);
            placementState.setAccessible(true);
        }
        return (BlockState) placementState.invoke(item, ctx);
    }

    /**
     * One position of an Effortless build: its trace keeps the clicked face (UP, on the floor below) and moves the position to the
     * air block above it; MinecraftConvertor.toPlatformBlockPlaceContext makes a BlockPlaceContext of the player, the main hand, the
     * main-hand stack and that hit result. Returns whether a block was set down.
     */
    private static boolean effortless(ServerLevel l, FakePlayer player, BlockPos target) throws Exception {
        ItemStack stack = player.getItemInHand(InteractionHand.MAIN_HAND);
        BlockItem item = (BlockItem) stack.getItem();
        var hit = new BlockHitResult(new Vec3(target.getX() + 0.5, target.getY(), target.getZ() + 0.5), Direction.UP, target, false);
        BlockState state = placementState(item, new BlockPlaceContext(player, InteractionHand.MAIN_HAND, stack, hit));
        if (state == null) return false; // FAIL_BLOCK_STATE_NULL
        if (!l.setBlockAndUpdate(target, state)) return false;
        item.getBlock().setPlacedBy(l, target, state, player, stack);
        if (!player.getAbilities().instabuild) stack.shrink(1);
        return true;
    }

    /** Every block of {@code b} near {@code p}: assembled, and part of an assembly that is whole and owned. */
    private static String strays(ServerLevel l, PumpAssemblyBlock b, BlockPos from, BlockPos to) {
        List<String> bad = new ArrayList<>();
        for (var q : BlockPos.betweenClosed(from, to)) {
            BlockState s = l.getBlockState(q);
            if (!s.is(b)) continue;
            if (!b.isFull(s)) bad.add("compact " + q.toShortString() + " " + s);
            else if (!b.complete(l, b.origin(q, s), s)) bad.add("incomplete " + q.toShortString() + " " + s);
            if (bad.size() > 3) break;
        }
        return bad.isEmpty() ? null : String.join("; ", bad);
    }

    private static int controllers(ServerLevel l, PumpAssemblyBlock b, BlockPos from, BlockPos to) {
        int n = 0;
        for (var q : BlockPos.betweenClosed(from, to)) {
            BlockState s = l.getBlockState(q);
            if (s.is(b) && b.isFull(s) && s.getValue(PumpAssemblyBlock.CELL) == b.controllerCell(s)) n++;
        }
        return n;
    }

    private static BlockPos lo(BlockPos p) { return p.offset(-12, -12, -12); }
    private static BlockPos hi(BlockPos p) { return p.offset(LINE + 12, 12, 12); }

    // ------------------------------------------------------------------------------------------------ Single build mode

    @GameTest(template = "empty", timeoutTicks = 600)
    public static void anEffortlessSinglePlacementBuildsEveryKindInFullAndTakesOneItemInSurvivalOnly(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        run(h, 0, 60, () -> {
            var kinds = kinds();
            for (int i = 0; i < kinds.size(); i++) {
                var b = kinds.get(i);
                Direction d = DIRS[i % 4];
                for (int mode = 0; mode < 2; mode++) {
                    BlockPos p = at(0, i).offset(0, 0, mode == 0 ? -14 : 14);
                    FakePlayer player = player(l, "bwr_effortless_single" + mode, mode == 0 ? GameType.SURVIVAL : GameType.CREATIVE, d);
                    player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(b, 2));
                    h.assertTrue(effortless(l, player, p), b + ": Effortless's getPlacementState gave a state and it was set down");
                    BlockState s = l.getBlockState(p);
                    BlockState want = b.placementState().setValue(PumpAssemblyBlock.FACING, d.getOpposite());
                    h.assertTrue(s == want, b + ": the full assembly facing the player, " + want + ", got " + s);
                    h.assertTrue(b.complete(l, b.origin(p, s), s), b + ": every cell is there and owned");
                    h.assertTrue((b.newBlockEntity(p, s) != null) == (l.getBlockEntity(p) != null), b + ": the controller block entity");
                    h.assertTrue(count(player, b.asItem()) == (mode == 0 ? 1 : 2), b + ": one item in survival, none in creative; has " + count(player, b.asItem()));
                }
            }
        }, () -> {
            var kinds = kinds();
            for (int i = 0; i < kinds.size(); i++) {
                var b = kinds.get(i);
                for (int mode = 0; mode < 2; mode++) {
                    BlockPos p = at(0, i).offset(0, 0, mode == 0 ? -14 : 14);
                    String bad = strays(l, b, p.offset(-12, -12, -12), p.offset(12, 12, 12));
                    h.assertTrue(bad == null && controllers(l, b, p.offset(-12, -12, -12), p.offset(12, 12, 12)) == 1,
                            b + ": one whole assembly after 60 ticks, nothing compact or stray: " + bad);
                }
            }
        });
    }

    // ------------------------------------------------------------------------------------------------ Line build mode

    @GameTest(template = "empty", timeoutTicks = 600)
    public static void anEffortlessLineOfEveryKindBuildsWholeAssembliesWhereTheyFitAndNothingElse(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        int[][] placed = new int[kinds().size()][2];
        run(h, 1, 60, () -> {
            var kinds = kinds();
            for (int i = 0; i < kinds.size(); i++) {
                var b = kinds.get(i);
                Direction d = DIRS[i % 4];
                for (int mode = 0; mode < 2; mode++) {
                    BlockPos p = at(1, i).offset(0, 0, mode == 0 ? -14 : 14);
                    FakePlayer player = player(l, "bwr_effortless_line" + mode, mode == 0 ? GameType.SURVIVAL : GameType.CREATIVE, d);
                    player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(b, LINE + 4));
                    int n = 0;
                    for (int k = 0; k < LINE; k++) if (effortless(l, player, p.offset(k, 0, 0))) n++;
                    placed[i][mode] = n;
                    h.assertTrue(n >= 1, b + ": the line set down at least its first block");
                    String bad = strays(l, b, lo(p), hi(p));
                    h.assertTrue(bad == null, b + ": every block of the line is part of a whole assembly: " + bad);
                    h.assertTrue(controllers(l, b, lo(p), hi(p)) == n, b + ": one assembly per block set down (" + n + ")");
                    h.assertTrue(count(player, b.asItem()) == LINE + 4 - (mode == 0 ? n : 0), b + ": one item per assembly in survival, none in creative; has " + count(player, b.asItem()));
                    h.assertTrue(l.getEntitiesOfClass(ItemEntity.class, new AABB(lo(p)).minmax(new AABB(hi(p)))).isEmpty(), b + ": nothing dropped");
                }
            }
        }, () -> {
            var kinds = kinds();
            for (int i = 0; i < kinds.size(); i++) {
                var b = kinds.get(i);
                for (int mode = 0; mode < 2; mode++) {
                    BlockPos p = at(1, i).offset(0, 0, mode == 0 ? -14 : 14);
                    String bad = strays(l, b, lo(p), hi(p));
                    h.assertTrue(bad == null && controllers(l, b, lo(p), hi(p)) == placed[i][mode],
                            b + ": the same whole assemblies after 60 ticks: " + bad);
                }
            }
        });
    }

    // ------------------------------------------------------------------------------------------------ no room

    @GameTest(template = "empty", timeoutTicks = 600)
    public static void anEffortlessPlacementWithoutRoomSetsNothingDownAndTakesNothing(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        run(h, 2, 5, () -> {
            var kinds = kinds();
            for (int i = 0; i < kinds.size(); i++) {
                var b = kinds.get(i);
                Direction d = DIRS[i % 4];
                BlockState full = b.placementState().setValue(PumpAssemblyBlock.FACING, d.getOpposite());
                for (int mode = 0; mode < 2; mode++) {
                    BlockPos p = at(2, i).offset(0, 0, mode == 0 ? -14 : 14);
                    int k = b.controllerCell(full) == 0 ? 1 : 0;
                    BlockPos blocker = p.offset(TurbineAssemblyBlock.turn(b.cellOffset(full, k), d.getOpposite()));
                    l.setBlock(blocker, Blocks.STONE.defaultBlockState(), 3);
                    FakePlayer player = player(l, "bwr_effortless_blocked" + mode, mode == 0 ? GameType.SURVIVAL : GameType.CREATIVE, d);
                    player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(b, 2));
                    h.assertTrue(!effortless(l, player, p), b + ": no state for a footprint without room, so Effortless sets nothing down");
                    h.assertTrue(l.getBlockState(p).isAir() && l.getBlockState(blocker).is(Blocks.STONE), b + ": the spot is empty, the blocker stands");
                    h.assertTrue(count(player, b.asItem()) == 2, b + ": nothing taken");
                    h.assertTrue(strays(l, b, p.offset(-12, -12, -12), p.offset(12, 12, 12)) == null
                            && controllers(l, b, p.offset(-12, -12, -12), p.offset(12, 12, 12)) == 0, b + ": nothing of it anywhere");
                }
            }
        }, null);
    }
}
