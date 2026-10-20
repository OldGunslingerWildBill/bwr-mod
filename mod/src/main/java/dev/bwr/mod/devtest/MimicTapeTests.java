package dev.bwr.mod.devtest;

import com.mojang.authlib.GameProfile;
import dev.bwr.mod.fuel.BwrDataComponents;
import dev.bwr.mod.panel.PanelBlock;
import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.PanelBus;
import dev.bwr.mod.panel.PanelGeometry;
import dev.bwr.mod.panel.PanelOps;
import dev.bwr.mod.panel.PanelType;
import dev.bwr.mod.panel.component.ComponentTypes;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.mimic.MimicDraft;
import dev.bwr.mod.panel.mimic.MimicGeometry;
import dev.bwr.mod.panel.mimic.MimicMode;
import dev.bwr.mod.panel.mimic.MimicOps;
import dev.bwr.mod.panel.mimic.MimicOps.Result;
import dev.bwr.mod.panel.mimic.MimicPath;
import dev.bwr.mod.panel.mimic.MimicRoute;
import dev.bwr.mod.panel.mimic.MimicStyle;
import dev.bwr.mod.panel.mimic.MimicTapeItem;
import dev.bwr.mod.panel.net.PanelPayloads;
import dev.bwr.mod.panel.wiring.PanelSampler;
import dev.bwr.mod.panel.wiring.PlantSource;
import dev.bwr.mod.panel.wiring.WiringOps;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.registry.BwrItems;
import dev.bwr.mod.steam.MainSteamIsolationValveBlock;
import dev.bwr.mod.steam.MainSteamIsolationValveBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The mimic tape and the mimic layer of the panels (MIMIC-TAPE-SPEC.md): drawing orthogonal and free-angle paths through
 * the item's real use path, a path across two coplanar blocks stored on both and erased together, non-coplanar
 * neighbours refused, a component placed over a path, style and binding through a save and a reload, an old save, the
 * limits, a bound path that dims and lights from its signal, and nothing changing in the plant.
 *
 * <p>Every test builds at its own far-away fixture (origin 92,900, 128 blocks apart), force-loads only the chunks it
 * needs, waits until entity lookups work there ({@link EntityLookupProbe}; the components a test leaves on a wall drop as
 * items when the wall is cleared, and stale items of an earlier run must be found to be removed), empties the volume first
 * and releases the chunks in every outcome.
 */
@GameTestHolder("bwr")
@PrefixGameTestTemplate(false)
public final class MimicTapeTests {
    private MimicTapeTests() {
    }

    static final int ORIGIN = 92_900;
    private static final Direction FACING = Direction.NORTH;

    /** Plant-facing events of any panel block near a fixture, to show a path never operates an output. */
    private static final List<BlockPos> OUTPUT_EVENTS = new ArrayList<>();

    static {
        PanelBus.addOutputListener((level, owner, component, index, value) -> {
            synchronized (OUTPUT_EVENTS) {
                OUTPUT_EVENTS.add(owner.getBlockPos().immutable());
            }
        });
    }

    private static int outputEventsNear(BlockPos origin) {
        synchronized (OUTPUT_EVENTS) {
            return (int) OUTPUT_EVENTS.stream().filter(pos -> Math.abs(pos.getX() - origin.getX()) < 64
                    && Math.abs(pos.getZ() - origin.getZ()) < 64).count();
        }
    }

    // ---- fixture

    /** A cleared, force-loaded stage with a survival fake player standing in front of the wall, run in timed steps. */
    private static final class Stage {
        final GameTestHelper helper;
        final ServerLevel level;
        /** Top-left block of the panel wall. Columns run toward -X (the right of a north-facing panel). */
        final BlockPos origin;
        final FakePlayer player;
        /** Where the plant device stands: inside the forced area, 8 blocks below the wall. */
        final BlockPos devices;

        private final EntityLookupProbe probe;
        private final List<Runnable> steps = new ArrayList<>();
        private final List<Integer> delays = new ArrayList<>();
        private boolean released;
        private int polls;

        private Stage(GameTestHelper helper, int slot) {
            this.helper = helper;
            level = helper.getLevel();
            origin = new BlockPos(ORIGIN + slot * 128, 110, ORIGIN);
            devices = origin.offset(-12, -8, 0);
            probe = new EntityLookupProbe(level, origin.getY() + 4.0);
            helper.testInfo.addListener(new GameTestListener() {
                @Override public void testStructureLoaded(GameTestInfo info) { }
                @Override public void testPassed(GameTestInfo info, GameTestRunner runner) { release(); }
                @Override public void testFailed(GameTestInfo info, GameTestRunner runner) { release(); }
                @Override public void testAddedForRerun(GameTestInfo before, GameTestInfo after, GameTestRunner runner) { release(); }
            });
            hold(true);
            player = FakePlayerFactory.get(level, new GameProfile(
                    UUID.nameUUIDFromBytes(("mimic-tape-test-" + slot).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    "mimic-tape-" + slot));
        }

        /**
         * Runs {@code body} on a clean fixture once entity lookups work in its chunks, then each {@link #then} step in
         * order. The chunks are released and the fixture emptied in every outcome.
         */
        static void run(GameTestHelper helper, int slot, Consumer<Stage> body) {
            Stage s = new Stage(helper, slot);
            s.whenLive(() -> {
                try {
                    s.clear();
                    s.player.setGameMode(GameType.SURVIVAL);
                    s.player.getInventory().clearContent();
                    s.player.setShiftKeyDown(false);
                    s.player.setPos(s.origin.getX() - 1.5, s.origin.getY() - 1.0, s.origin.getZ() - 2.5);
                    body.accept(s);
                } catch (RuntimeException | Error e) {
                    s.release();
                    throw e;
                }
                s.next();
            });
        }

        private void whenLive(Runnable then) {
            helper.runAfterDelay(1, () -> {
                boolean ready = true;
                for (int x = -24; x <= 4; x += 8) {
                    ready &= probe.live(origin.offset(x, 0, 0));
                }
                if (ready) {
                    then.run();
                    return;
                }
                if (++polls > 200) {
                    release();
                    helper.assertTrue(false, "the fixture chunks never answered entity lookups");
                }
                whenLive(then);
            });
        }

        /** A step to run {@code delay} ticks after the previous one. */
        void then(int delay, Runnable step) {
            delays.add(delay);
            steps.add(step);
        }

        private void next() {
            if (steps.isEmpty()) {
                release();
                helper.succeed();
                return;
            }
            int delay = delays.remove(0);
            Runnable step = steps.remove(0);
            helper.runAfterDelay(delay, () -> {
                try {
                    step.run();
                } catch (RuntimeException | Error e) {
                    release();
                    throw e;
                }
                next();
            });
        }

        private void hold(boolean force) {
            for (int x = (origin.getX() - 30) >> 4; x <= (origin.getX() + 8) >> 4; x++) {
                for (int z = (origin.getZ() - 16) >> 4; z <= (origin.getZ() + 16) >> 4; z++) {
                    level.setChunkForced(x, z, force);
                    if (force) {
                        level.getChunk(x, z);
                    }
                }
            }
        }

        private void clear() {
            for (int x = -24; x <= 4; x++) {
                for (int y = -12; y <= 12; y++) {
                    for (int z = -6; z <= 6; z++) {
                        BlockPos pos = origin.offset(x, y, z);
                        if (!level.getBlockState(pos).isAir()) {
                            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
                        }
                    }
                }
            }
            for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, new AABB(origin).inflate(30, 12, 14))) {
                item.discard();
            }
        }

        void release() {
            if (released) {
                return;
            }
            released = true;
            try {
                probe.forget();
                clear();
            } finally {
                hold(false);
            }
        }

        BlockPos col(int column) {
            return origin.relative(FACING.getCounterClockWise(), column);
        }

        BlockPos at(int column, int row) {
            return col(column).below(row);
        }

        void panel(BlockPos pos, PanelType type, Direction facing) {
            var block = switch (type) {
                case VERTICAL -> BwrBlocks.CONTROL_PANEL_VERTICAL.get();
                case BENCHBOARD -> BwrBlocks.CONTROL_PANEL_BENCHBOARD.get();
                case CONSOLE -> BwrBlocks.CONTROL_PANEL_CONSOLE.get();
                case BACK_PANEL -> BwrBlocks.CONTROL_PANEL_BACK.get();
                case DUPLEX_BOARD, DUPLEX_BENCH, DUPLEX_HEADER -> throw new UnsupportedOperationException("a duplex cell comes with its column");
                case HEADER_TILT -> BwrBlocks.CONTROL_PANEL_HEADER_STRIP.get();
                case CHAMFER_VERTICAL -> BwrBlocks.CONTROL_PANEL_CORNER_VERTICAL_CHAMFER.get();
                case CHAMFER_BENCH -> BwrBlocks.CONTROL_PANEL_CORNER_BENCH_CHAMFER.get();
            };
            level.setBlock(pos, block.defaultBlockState().setValue(PanelBlock.FACING, facing), 3);
        }

        void wall(int columns, int rows) {
            for (int r = 0; r < rows; r++) {
                for (int c = 0; c < columns; c++) {
                    panel(at(c, r), PanelType.VERTICAL, FACING);
                }
            }
        }

        PanelBlockEntity be(BlockPos pos) {
            return (PanelBlockEntity) level.getBlockEntity(pos);
        }

        /** Puts a stack of tape in the player's main hand. */
        ItemStack tape(int count) {
            ItemStack stack = new ItemStack(BwrItems.MIMIC_TAPE.get(), count);
            player.setItemInHand(InteractionHand.MAIN_HAND, stack);
            return stack;
        }

        Result use(PanelBlockEntity block, double cu, double cv) {
            return MimicOps.use(player, InteractionHand.MAIN_HAND, block.getBlockPos(), cu, cv);
        }

        /** The real {@code Item.use} for the air: what a right-click in the air does with the tape in hand. */
        void useInAir(ItemStack stack) {
            ((MimicTapeItem) stack.getItem()).use(level, player, InteractionHand.MAIN_HAND);
        }

        /** Places a path directly (no player): points in the cell space of {@code block}. */
        MimicOps.Placed place(PanelBlockEntity block, int... points) {
            return MimicOps.place(level, null, block.getBlockPos(), points, new MimicStyle());
        }

        MainSteamIsolationValveBlockEntity msiv(BlockPos root) {
            var block = BwrBlocks.MSIV.get();
            var state = block.placementState().setValue(MainSteamIsolationValveBlock.FACING, Direction.NORTH);
            level.setBlock(root, state, 3);
            block.setPlacedBy(level, root, state, null, new ItemStack(block));
            var valve = (MainSteamIsolationValveBlockEntity) level.getBlockEntity(root);
            valve.setDemandOpen(false);
            valve.tickValve(5);
            return valve;
        }

        ItemStack linker(BlockPos device) {
            ItemStack stack = new ItemStack(BwrItems.PANEL_LINKER.get());
            if (device != null) {
                stack.set(BwrDataComponents.LINKED_DEVICE.get(), GlobalPos.of(level.dimension(), device));
            }
            player.setItemInHand(InteractionHand.MAIN_HAND, stack);
            return stack;
        }

        WiringOps.Result bindPath(PanelBlockEntity block, MimicPath path, WiringOps.BindKind kind, String key) {
            return WiringOps.bind(player, new PanelPayloads.Bind(block.getBlockPos(), -path.localId(), false, 0, kind.ordinal(),
                    kind == WiringOps.BindKind.PLANT ? devices : BlockPos.ZERO, key, BlockPos.ZERO, 0, 0, 1.0));
        }

        PanelBlockEntity copyOf(PanelBlockEntity be) {
            PanelBlockEntity copy = (PanelBlockEntity) BlockEntity.loadStatic(be.getBlockPos(), be.getBlockState(),
                    be.saveWithFullMetadata(level.registryAccess()), level.registryAccess());
            helper.assertTrue(copy != null, "block entity reload");
            return copy;
        }
    }

    private static void check(GameTestHelper helper, boolean condition, String message) {
        helper.assertTrue(condition, message);
    }

    private static String points(MimicPath path) {
        return java.util.Arrays.toString(path.points());
    }

    private static int[] draftPoints(ItemStack stack) {
        MimicDraft draft = MimicTapeItem.draft(stack);
        return draft == null ? new int[0] : draft.toArray();
    }

    // ---- pure pieces

    @GameTest(template = "empty")
    public static void tapeIsRegisteredWithItsRecipeComponentsAndDefaults(GameTestHelper helper) {
        var recipe = helper.getLevel().getRecipeManager().byKey(ResourceLocation.fromNamespaceAndPath("bwr", "mimic_tape"));
        check(helper, recipe.isPresent(), "the mimic tape recipe is missing");
        var result = recipe.get().value().getResultItem(helper.getLevel().registryAccess());
        check(helper, result.is(BwrItems.MIMIC_TAPE.get()) && result.getCount() >= 1 && result.getCount() <= result.getMaxStackSize(),
                "the recipe yields tape in a valid stack");
        ItemStack tape = new ItemStack(BwrItems.MIMIC_TAPE.get());
        check(helper, MimicTapeItem.mode(tape) == MimicMode.DRAW && MimicTapeItem.draft(tape) == null,
                "a new tape draws and holds no path");
        MimicTapeItem.setMode(tape, MimicMode.EDIT);
        check(helper, MimicTapeItem.mode(tape) == MimicMode.EDIT, "the mode is stored on the stack");
        MimicTapeItem.setMode(tape, MimicMode.DRAW);
        check(helper, tape.get(BwrDataComponents.MIMIC_MODE.get()) == null, "the default mode is not stored");
        check(helper, MimicMode.DRAW.step(1) == MimicMode.ERASE && MimicMode.DRAW.step(-1) == MimicMode.EDIT
                && MimicMode.EDIT.step(1) == MimicMode.DRAW, "the modes cycle both ways");
        MimicStyle style = new MimicStyle();
        check(helper, style.width == 2 && !style.dashed && style.endEnd == MimicStyle.END_ARROW
                && style.startEnd == MimicStyle.END_NONE && style.flowEvery == 0 && style.color == MimicStyle.DEFAULT_COLOR,
                "the default style: width 2, solid, an arrow at the end");
        CompoundTag hostile = new CompoundTag();
        hostile.putInt("Width", 99);
        hostile.putInt("FlowEvery", 13);
        hostile.putString("Color", "red");
        hostile.putDouble("Threshold", Double.NaN);
        hostile.putInt("EndEnd", -4);
        style.apply(hostile);
        check(helper, style.width == MimicStyle.MAX_WIDTH && style.flowEvery == 16 && style.color == MimicStyle.DEFAULT_COLOR
                && style.threshold == 0.0 && style.endEnd == MimicStyle.END_NONE,
                "hostile values are clamped or ignored: " + style.toTag());
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void snappingAndPickingFollowTheSpecRules(GameTestHelper helper) {
        // Orthogonal by default: along the axis the cursor is farther from the last point.
        int[] ortho = MimicGeometry.snap(true, 4, 4, 11.4, 6.2, false);
        check(helper, ortho[0] == 11 && ortho[1] == 4, "orthogonal snap: " + java.util.Arrays.toString(ortho));
        int[] vertical = MimicGeometry.snap(true, 4, 4, 5.0, 12.6, false);
        check(helper, vertical[0] == 4 && vertical[1] == 13, "orthogonal snap, vertical: " + java.util.Arrays.toString(vertical));
        // Sneak: 45 degrees and free angles.
        int[] diagonal = MimicGeometry.snap(true, 4, 4, 11.2, 10.4, true); // 7 across, 6 down: 40.6 degrees
        check(helper, diagonal[0] == 11 && diagonal[1] == 11, "a nearly diagonal free click snaps to 45 degrees: "
                + java.util.Arrays.toString(diagonal));
        int[] free = MimicGeometry.snap(true, 4, 4, 14.0, 8.0, true);
        check(helper, free[0] == 14 && free[1] == 8, "a free angle is kept: " + java.util.Arrays.toString(free));
        int[] first = MimicGeometry.snap(false, 0, 0, 2.6, 7.4, false);
        check(helper, first[0] == 3 && first[1] == 7, "the first point rounds to the grid");
        // Junctions: a point on another path's inside is a T; a crossing or an end to end meeting is not.
        MimicPath main = new MimicPath(1L, 1, new int[] {0, 8, 16, 8}, new MimicStyle());
        MimicStyle plain = new MimicStyle();
        plain.endEnd = MimicStyle.END_NONE;
        MimicPath branch = new MimicPath(2L, 2, new int[] {8, 0, 8, 8}, plain);
        MimicPath cross = new MimicPath(3L, 3, new int[] {4, 2, 4, 14}, plain);
        MimicPath continuation = new MimicPath(4L, 4, new int[] {16, 8, 16, 14}, plain);
        List<MimicPath> all = List.of(main, branch, cross, continuation);
        check(helper, MimicGeometry.junction(branch, 1, all), "a branch ending on a path's inside gets a dot");
        check(helper, !MimicGeometry.junction(cross, 0, all) && !MimicGeometry.junction(cross, 1, all),
                "a path that only crosses another gets no dot");
        check(helper, !MimicGeometry.junction(continuation, 0, all), "two paths meeting end to end get no dot");
        MimicStyle arrow = new MimicStyle();
        check(helper, !MimicGeometry.junction(new MimicPath(5L, 5, new int[] {8, 0, 8, 8}, arrow), 1, all),
                "an end that carries an arrow keeps the arrow instead of a dot");
        // Picking: the nearest path within its half width plus a margin.
        check(helper, MimicGeometry.pick(all, 12.0, 8.5, 1.0) == main, "a click on the line picks it");
        check(helper, MimicGeometry.pick(all, 12.0, 14.0, 1.0) == null, "a click far from every path picks none");
        check(helper, MimicGeometry.cost(0.5) == 1 && MimicGeometry.cost(32.0) == 1 && MimicGeometry.cost(32.5) == 2
                && MimicGeometry.cost(96.0) == 3, "one tape per 32 cells, at least one");
        helper.succeed();
    }

    // ---- drawing

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void drawsAnOrthogonalPathThroughTheItemUse(GameTestHelper helper) {
        Stage.run(helper, 0, s -> {
            s.wall(1, 1);
            PanelBlockEntity block = s.be(s.at(0, 0));
            ItemStack stack = s.tape(4);

            check(helper, s.use(block, 2.3, 2.4) == Result.POINT_ADDED, "the first click adds a point");
            check(helper, java.util.Arrays.equals(draftPoints(stack), new int[] {2, 2}), "the point snaps to the grid");
            check(helper, s.use(block, 10.2, 3.1) == Result.POINT_ADDED, "the second click adds a point");
            check(helper, java.util.Arrays.equals(draftPoints(stack), new int[] {2, 2, 10, 2}),
                    "an orthogonal segment follows the axis the cursor is farther along: " + java.util.Arrays.toString(draftPoints(stack)));
            check(helper, s.use(block, 10.3, 9.8) == Result.POINT_ADDED, "the third click adds a point");
            check(helper, s.use(block, 10.1, 10.2) == Result.SAME_POINT, "a click on the last point adds nothing");
            check(helper, java.util.Arrays.equals(draftPoints(stack), new int[] {2, 2, 10, 2, 10, 10}),
                    "the corner is orthogonal: " + java.util.Arrays.toString(draftPoints(stack)));
            check(helper, block.mimicCount() == 0, "nothing is placed until the path is finished");

            s.useInAir(stack);
            check(helper, MimicTapeItem.draft(stack) == null, "the right-click in the air finished the path");
            check(helper, block.mimicCount() == 1, "one path was placed");
            MimicPath path = block.mimicPaths().get(0);
            check(helper, points(path).equals("[2, 2, 10, 2, 10, 10]"), "the points are stored: " + points(path));
            check(helper, path.style().width == 2 && path.style().endEnd == MimicStyle.END_ARROW && !path.bound(),
                    "a new path has the default style and no signal");
            check(helper, stack.getCount() == 3, "16 cells cost one tape: " + stack.getCount());
            check(helper, block.ownedCount() == 0 && block.isFree(0, 0, 16, 16), "a path reserves no cell");
            s.useInAir(stack);
            check(helper, block.mimicCount() == 1 && stack.getCount() == 3, "the air with nothing drawn does nothing");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void sneakDrawsDiagonalAndFreeAnglesAndSneakOnTheLastPointFinishes(GameTestHelper helper) {
        Stage.run(helper, 1, s -> {
            s.wall(1, 1);
            PanelBlockEntity block = s.be(s.at(0, 0));
            ItemStack stack = s.tape(2);

            check(helper, s.use(block, 2.0, 2.0) == Result.POINT_ADDED, "first point");
            s.player.setShiftKeyDown(true);
            check(helper, s.use(block, 8.4, 8.2) == Result.POINT_ADDED, "sneaking: a diagonal point");
            check(helper, java.util.Arrays.equals(draftPoints(stack), new int[] {2, 2, 8, 8}),
                    "sneak keeps a 45 degree segment exact: " + java.util.Arrays.toString(draftPoints(stack)));
            check(helper, s.use(block, 12.0, 9.0) == Result.POINT_ADDED, "sneaking: a free angle");
            check(helper, java.util.Arrays.equals(draftPoints(stack), new int[] {2, 2, 8, 8, 12, 9}),
                    "a free angle is kept as clicked: " + java.util.Arrays.toString(draftPoints(stack)));
            check(helper, s.use(block, 12.2, 9.1) == Result.FINISHED, "sneak-using the last point finishes the path");
            s.player.setShiftKeyDown(false);
            check(helper, MimicTapeItem.draft(stack) == null && block.mimicCount() == 1, "the path was placed");
            check(helper, points(block.mimicPaths().get(0)).equals("[2, 2, 8, 8, 12, 9]"), "diagonal and free segments are stored");

            // A path of fewer than two points is dropped, not placed.
            check(helper, s.use(block, 5.0, 5.0) == Result.POINT_ADDED, "a lone point");
            s.useInAir(stack);
            check(helper, MimicTapeItem.draft(stack) == null && block.mimicCount() == 1 && stack.getCount() == 1,
                    "a single point places nothing and costs nothing");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void aRightClickOnTheFaceReachesTheSameCodeThroughTheBlock(GameTestHelper helper) {
        Stage.run(helper, 2, s -> {
            s.wall(1, 1);
            PanelBlockEntity block = s.be(s.at(0, 0));
            ItemStack stack = s.tape(2);
            // Stand in front of the middle of the face, level with it, looking straight at it.
            PanelGeometry.Frame frame = PanelGeometry.frame(PanelType.VERTICAL, FACING);
            BlockPos pos = block.getBlockPos();
            Vec3 target = new Vec3(pos.getX() + frame.x(8, 8, 0), pos.getY() + frame.y(8, 8, 0), pos.getZ() + frame.z(8, 8, 0));
            s.player.setPos(target.x, target.y - s.player.getEyeHeight(), target.z - 1.5);
            s.player.setYRot(0.0f);
            s.player.setXRot(0.0f);
            var result = s.level.getBlockState(pos).useItemOn(stack, s.level, s.player, InteractionHand.MAIN_HAND,
                    new BlockHitResult(target, FACING, pos, false));
            check(helper, result.consumesAction(), "the panel takes the tape's right-click: " + result);
            check(helper, java.util.Arrays.equals(draftPoints(stack), new int[] {8, 8}),
                    "the vanilla path takes the exact cursor from the view ray: " + java.util.Arrays.toString(draftPoints(stack)));
            // Hostile numbers from a packet are refused before anything changes.
            check(helper, MimicOps.use(s.player, InteractionHand.MAIN_HAND, pos, Double.NaN, 3.0) == Result.BAD_ARGUMENTS
                    && MimicOps.use(s.player, InteractionHand.MAIN_HAND, pos, 3.0, 99.0) == Result.BAD_ARGUMENTS,
                    "NaN and out-of-face cursors are refused");
            s.player.setPos(target.x, target.y - s.player.getEyeHeight(), target.z - 40.0);
            check(helper, s.use(block, 4.0, 4.0) == Result.OUT_OF_REACH, "a player out of reach changes nothing");
            check(helper, draftPoints(stack).length == 2, "the draft is unchanged");
            s.player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            check(helper, s.use(block, 4.0, 4.0) == Result.NOT_HOLDING_TAPE, "without the tape nothing happens");
        });
    }

    // ---- across blocks

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void aPathAcrossTwoCoplanarBlocksIsStoredOnBothAndErasedTogether(GameTestHelper helper) {
        Stage.run(helper, 3, s -> {
            s.wall(2, 1);
            PanelBlockEntity left = s.be(s.at(0, 0));
            PanelBlockEntity right = s.be(s.at(1, 0));
            ItemStack stack = s.tape(2);

            check(helper, s.use(left, 10.0, 8.0) == Result.POINT_ADDED, "a point in the first block");
            check(helper, s.use(right, 6.0, 8.0) == Result.POINT_ADDED, "a point in the neighbouring block");
            check(helper, java.util.Arrays.equals(draftPoints(stack), new int[] {10, 8, 22, 8}),
                    "the second point is in the first block's cell space: " + java.util.Arrays.toString(draftPoints(stack)));
            s.useInAir(stack);
            check(helper, left.mimicCount() == 1 && right.mimicCount() == 1, "the path is stored on both blocks");
            MimicPath onLeft = left.mimicPaths().get(0);
            MimicPath onRight = right.mimicPaths().get(0);
            check(helper, onLeft.pathId() == onRight.pathId() && onLeft.pathId() != 0L, "the pieces share one path id");
            check(helper, points(onLeft).equals("[10, 8, 22, 8]") && points(onRight).equals("[-6, 8, 6, 8]"),
                    "each block holds the path in its own cell space: " + points(onLeft) + " " + points(onRight));

            // Editing one piece updates the other.
            s.tape(1);
            CompoundTag edit = new CompoundTag();
            edit.putInt("Color", 0xE02020);
            edit.putInt("Width", 3);
            check(helper, MimicOps.configure(s.player, right.getBlockPos(), onRight.localId(), edit), "configure the right piece");
            check(helper, onLeft.style().color == 0xE02020 && onLeft.style().width == 3 && onRight.style().width == 3,
                    "both pieces took the new style");

            // Erasing one piece removes both.
            ItemStack eraser = s.tape(1);
            MimicTapeItem.setMode(eraser, MimicMode.ERASE);
            check(helper, s.use(right, 12.0, 3.0) == Result.NO_PATH, "erasing away from the line removes nothing");
            check(helper, left.mimicCount() == 1, "still there");
            check(helper, s.use(right, 2.0, 8.2) == Result.OK, "erasing on the line removes the path");
            check(helper, left.mimicCount() == 0 && right.mimicCount() == 0, "both pieces are gone");
            check(helper, !PanelSampler.isTracked(left) && !PanelSampler.isTracked(right), "no sampling is left behind");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void nonCoplanarNeighboursAndHolesAreRefused(GameTestHelper helper) {
        Stage.run(helper, 4, s -> {
            // A vertical board, a board facing another way beside it, and a benchboard on its other side.
            s.panel(s.at(0, 0), PanelType.VERTICAL, FACING);
            s.panel(s.at(1, 0), PanelType.VERTICAL, Direction.EAST);
            s.panel(s.col(-1), PanelType.BENCHBOARD, FACING);
            PanelBlockEntity board = s.be(s.at(0, 0));
            PanelBlockEntity turned = s.be(s.at(1, 0));
            PanelBlockEntity bench = s.be(s.col(-1));

            check(helper, s.place(board, 8, 8, 40, 8).result() == Result.OFF_BOARD, "a path may not leave the board to the side that is turned");
            check(helper, s.place(board, 8, 8, -20, 8).result() == Result.OFF_BOARD, "nor onto a neighbour of another slope");
            check(helper, s.place(board, 8, 8, 8, 40).result() == Result.OFF_BOARD, "nor into empty space");
            check(helper, board.mimicCount() == 0 && turned.mimicCount() == 0 && bench.mimicCount() == 0,
                    "a refused path leaves no piece anywhere");

            // With the tape: a click on another plane finishes the path and starts nothing there.
            ItemStack stack = s.tape(2);
            check(helper, s.use(board, 8.0, 8.0) == Result.POINT_ADDED, "start on the vertical board");
            check(helper, s.use(turned, 8.0, 8.0) == Result.CANCELLED, "a click on another plane finishes the path (one point: dropped)");
            check(helper, MimicTapeItem.draft(stack) == null && turned.mimicCount() == 0 && board.mimicCount() == 0,
                    "nothing was drawn on either board");
            check(helper, s.use(board, 8.0, 8.0) == Result.POINT_ADDED && s.use(board, 8.0, 14.0) == Result.POINT_ADDED,
                    "a two point path on the vertical board");
            check(helper, s.use(bench, 8.0, 8.0) == Result.FINISHED, "a click on a board of another slope finishes the path");
            check(helper, board.mimicCount() == 1 && bench.mimicCount() == 0, "it was placed on its own board only");

            // A hole in a wall: a segment over a block that is not there is refused, though both ends are on boards.
            for (int[] cell : new int[][] {{0, 3}, {1, 3}, {2, 3}, {0, 4}, {2, 4}}) {
                s.panel(s.at(cell[0], cell[1]), PanelType.VERTICAL, FACING);
            }
            check(helper, s.place(s.be(s.at(0, 3)), 4, 8, 36, 8).result() == Result.OK, "along the whole top row of an L shaped wall");
            check(helper, s.place(s.be(s.at(0, 4)), 4, 8, 36, 8).result() == Result.OFF_BOARD,
                    "a segment across the missing block is refused even when both ends are on boards");
            check(helper, s.be(s.at(2, 4)).mimicCount() == 0, "and leaves nothing on the far block");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void aComponentCanBePlacedOverAPath(GameTestHelper helper) {
        Stage.run(helper, 5, s -> {
            s.wall(2, 1);
            PanelBlockEntity left = s.be(s.at(0, 0));
            PanelBlockEntity right = s.be(s.at(1, 0));
            check(helper, s.place(left, 2, 8, 30, 8).result() == Result.OK, "a path along both blocks");
            PanelComponent lamp = PanelOps.placeDirect(s.level, left.getBlockPos(), 6, 6, ComponentTypes.LAMP,
                    ComponentTypes.LAMP.footprints().get(0));
            check(helper, lamp != null, "a lamp fits on top of the path");
            PanelComponent wide = PanelOps.placeDirect(s.level, left.getBlockPos(), 12, 6, ComponentTypes.NAMEPLATE,
                    ComponentTypes.NAMEPLATE.footprints().get(0));
            check(helper, wide != null, "a nameplate straddling both blocks fits on top of the path");
            check(helper, left.mimicCount() == 1 && right.mimicCount() == 1, "the path is untouched");
            check(helper, left.claimAt(6, 7) != null && !left.isFree(6, 6, 7, 7), "the component holds its own cells");
            // Removing the component leaves the path.
            s.level.removeBlock(right.getBlockPos(), false);
            check(helper, left.mimicCount() == 1, "the path piece in the left block stays when the right block goes");
        });
    }

    // ---- saves

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void styleAndBindingRoundTripThroughSaveAndLoad(GameTestHelper helper) {
        Stage.run(helper, 6, s -> {
            s.wall(2, 1);
            PanelBlockEntity left = s.be(s.at(0, 0));
            PanelBlockEntity right = s.be(s.at(1, 0));
            var valve = s.msiv(s.devices);
            check(helper, s.place(left, 3, 4, 3, 12, 22, 12).result() == Result.OK, "an L shaped path across two blocks");
            MimicPath path = left.mimicPaths().get(0);

            s.tape(1);
            CompoundTag edit = new CompoundTag();
            edit.putInt("Color", 0x20B040);
            edit.putInt("Width", 4);
            edit.putBoolean("Dashed", true);
            edit.putInt("StartEnd", MimicStyle.END_DOT);
            edit.putInt("EndEnd", MimicStyle.END_NONE);
            edit.putInt("FlowEvery", 16);
            edit.putString("Route", MimicStyle.ROUTE_ELBOW);
            edit.putString("Corners", MimicStyle.CORNERS_ROUNDED);
            edit.putInt("Radius", 5);
            edit.putInt("ArrowSize", 6);
            edit.putBoolean("ArrowOwnColor", true);
            edit.putInt("ArrowColor", 0xFFB000);
            edit.putDouble("Threshold", 0.25);
            check(helper, MimicOps.configure(s.player, left.getBlockPos(), path.localId(), edit), "configure");

            s.linker(s.devices);
            check(helper, s.bindPath(left, path, WiringOps.BindKind.REDSTONE, "") == WiringOps.Result.BAD_VALUE,
                    "a path takes a plant signal only");
            check(helper, s.bindPath(left, path, WiringOps.BindKind.PLANT, "no_such_signal") == WiringOps.Result.NO_SUCH_KEY,
                    "an unknown signal is refused");
            check(helper, !path.bound(), "nothing was bound by the refusals");
            check(helper, s.bindPath(left, path, WiringOps.BindKind.PLANT, "position") == WiringOps.Result.OK, "bind to a signal");
            MimicPath twin = right.mimicByPathId(path.pathId());
            check(helper, path.source() instanceof PlantSource p && p.key().equals("position") && p.device().equals(s.devices)
                    && twin.source() instanceof PlantSource, "every piece of the path is bound");
            check(helper, PanelSampler.isTracked(left) && PanelSampler.isTracked(right), "a bound path keeps its panel sampled");

            PanelBlockEntity reloaded = s.copyOf(left);
            PanelBlockEntity reloadedRight = s.copyOf(right);
            check(helper, reloaded.savePanel(false).equals(left.savePanel(false)), "the block saves identically after a reload");
            check(helper, reloadedRight.savePanel(false).equals(right.savePanel(false)), "the neighbour too");
            MimicPath back = reloaded.mimicPaths().get(0);
            MimicStyle style = back.style();
            check(helper, back.pathId() == path.pathId() && back.localId() == path.localId() && points(back).equals(points(path)),
                    "id and points survive");
            check(helper, style.color == 0x20B040 && style.width == 4 && style.dashed && style.startEnd == MimicStyle.END_DOT
                            && style.endEnd == MimicStyle.END_NONE && style.flowEvery == 16 && style.threshold == 0.25
                            && MimicStyle.ROUTE_ELBOW.equals(style.route) && MimicStyle.CORNERS_ROUNDED.equals(style.corners)
                            && style.radius == 5 && style.arrowSize == 6 && style.arrowOwnColor && style.arrowColor == 0xFFB000
                            && style.arrowRgb() == 0xFFB000,
                    "style survives: " + style.toTag());
            check(helper, back.source() instanceof PlantSource p && p.key().equals("position") && p.device().equals(s.devices),
                    "the binding survives with its absolute position");
            check(helper, !valve.isComputerControlled(), "nothing fired a command");

            // Unbinding the clicked piece unbinds every piece.
            check(helper, s.bindPath(right, twin, WiringOps.BindKind.NONE, "") == WiringOps.Result.OK
                    && !path.bound() && !twin.bound(), "clearing from one piece clears both");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void anOldPanelSaveLoadsAndAPanelWithoutPathsSavesAsBefore(GameTestHelper helper) {
        Stage.run(helper, 7, s -> {
            s.wall(1, 1);
            PanelBlockEntity block = s.be(s.at(0, 0));
            PanelOps.placeDirect(s.level, block.getBlockPos(), 2, 2, ComponentTypes.LAMP, ComponentTypes.LAMP.footprints().get(0));
            CompoundTag saved = block.savePanel(false);
            check(helper, !saved.contains("MimicPaths") && !saved.contains("NextMimicId"),
                    "a panel without paths writes no mimic key: " + saved.getAllKeys());
            check(helper, saved.getAllKeys().equals(java.util.Set.of("Version", "NextId", "Components", "Claims")),
                    "the saved form is exactly the old one: " + saved.getAllKeys());
            check(helper, !block.saveWithFullMetadata(s.level.registryAccess()).getCompound("bwr_panel").contains("MimicPaths"),
                    "the chunk form too");

            // An old tag (no mimic keys at all) loads as it did.
            PanelBlockEntity old = s.copyOf(block);
            check(helper, old.mimicCount() == 0 && old.ownedCount() == 1, "an old save loads with no paths");

            // Damaged path data is dropped, never thrown on, and the good paths stay.
            CompoundTag damaged = saved.copy();
            ListTag list = new ListTag();
            CompoundTag good = new CompoundTag();
            good.putLong("PathId", 77L);
            good.putInt("Id", 3);
            good.put("Points", new IntArrayTag(new int[] {1, 1, 9, 1}));
            good.putInt("Width", 99);
            good.putString("Source", "not a compound");
            list.add(good);
            CompoundTag noPoints = new CompoundTag();
            noPoints.putLong("PathId", 78L);
            list.add(noPoints);
            CompoundTag odd = new CompoundTag();
            odd.put("Points", new IntArrayTag(new int[] {1, 2, 3}));
            list.add(odd);
            CompoundTag wrongType = new CompoundTag();
            wrongType.putString("Points", "1,2,3,4");
            list.add(wrongType);
            damaged.put("MimicPaths", list);
            old.loadPanel(damaged);
            check(helper, old.mimicCount() == 1 && old.mimicPaths().get(0).style().width == MimicStyle.MAX_WIDTH
                    && old.mimicPaths().get(0).localId() == 3, "only the usable path is kept, with its style clamped");
            ListTag many = new ListTag();
            for (int i = 0; i < 400; i++) {
                CompoundTag entry = new CompoundTag();
                entry.putLong("PathId", 1000L + i);
                entry.put("Points", new IntArrayTag(new int[] {1, 1, 9, 1}));
                many.add(entry);
            }
            damaged.put("MimicPaths", many);
            old.loadPanel(damaged);
            check(helper, old.mimicCount() == MimicOps.MAX_PATHS_PER_BLOCK, "a hostile save cannot exceed the per block limit: " + old.mimicCount());
        });
    }

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void theNetworkFormCarriesPathsAndTheirValues(GameTestHelper helper) {
        Stage.run(helper, 8, s -> {
            s.wall(1, 1);
            PanelBlockEntity server = s.be(s.at(0, 0));
            check(helper, s.place(server, 2, 2, 12, 2).result() == Result.OK, "a path");
            MimicPath path = server.mimicPaths().get(0);
            // The client copy is made from the network form, as a full-state packet is applied.
            PanelBlockEntity client = s.copyOf(server);
            int revision = client.mimicRevision();
            client.applyNetworkState(server.savePanel(true));
            check(helper, client.mimicRevision() == revision && client.mimicCount() == 1,
                    "a full state that repeats the same paths does not rebuild the client's mesh");
            MimicPath shown = client.mimicPath(path.localId());
            check(helper, shown != null && Double.isNaN(shown.value()), "an unbound path reads no signal");
            // A value delta addresses a path as -localId.
            client.applyDelta(new PanelBlockEntity.ValueDelta(-path.localId(), false, 0, 3.5));
            check(helper, shown.value() == 3.5 && shown.lit(), "a value delta reaches the path (above the threshold 0)");
            client.applyDelta(new PanelBlockEntity.ValueDelta(-path.localId(), false, 0, -1.0));
            check(helper, !shown.lit(), "a value at or below the threshold is dim");
            client.applyDelta(new PanelBlockEntity.ValueDelta(-path.localId(), false, 0, Double.NaN));
            check(helper, !shown.lit(), "no signal is dim");
            client.applyDelta(new PanelBlockEntity.ValueDelta(-999, false, 0, 1.0));
            // A real change rebuilds.
            server.removeMimicPath(path.pathId());
            client.applyNetworkState(server.savePanel(true));
            check(helper, client.mimicCount() == 0 && client.mimicRevision() > revision, "a changed state is a new revision");
        });
    }

    // ---- limits and cost

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void limitsOfPointsPathsAndTapeAreEnforced(GameTestHelper helper) {
        Stage.run(helper, 9, s -> {
            s.wall(2, 1);
            PanelBlockEntity left = s.be(s.at(0, 0));
            PanelBlockEntity right = s.be(s.at(1, 0));

            // Sixty-four points are fine, the sixty-fifth is refused (by the item and by the placement alike).
            int[] sixtyFour = new int[128];
            int[] sixtyFive = new int[130];
            for (int i = 0; i < 65; i++) {
                int x = 1 + (i % 2) * 14;
                int y = 1 + (i / 2) % 14;
                if (i < 64) {
                    sixtyFour[i * 2] = x;
                    sixtyFour[i * 2 + 1] = y;
                }
                sixtyFive[i * 2] = x;
                sixtyFive[i * 2 + 1] = y;
            }
            check(helper, s.place(left, sixtyFive).result() == Result.BAD_ARGUMENTS, "65 points are refused");
            check(helper, left.mimicCount() == 0, "a refused path leaves nothing");
            ItemStack stack = s.tape(64);
            stack.set(BwrDataComponents.MIMIC_DRAFT.get(), new MimicDraft(left.getBlockPos(),
                    java.util.stream.IntStream.of(sixtyFour).boxed().toList()));
            check(helper, s.use(left, 5.0, 15.0) == Result.TOO_MANY_POINTS, "the 65th click is refused with a message");
            check(helper, draftPoints(stack).length == 128, "the draft is unchanged");
            s.useInAir(stack);
            check(helper, left.mimicCount() == 1 && left.mimicPaths().get(0).pointCount() == 64, "a 64 point path is placed");
            check(helper, right.mimicCount() == 1, "its wide end reaches into the neighbouring block, which holds a piece too");
            left.clearMimicPaths();
            right.clearMimicPaths();

            // 128 paths in a block; the 129th is refused whole, with no piece in the neighbour either.
            for (int i = 0; i < MimicOps.MAX_PATHS_PER_BLOCK; i++) {
                int y = 1 + i % 14;
                check(helper, s.place(left, 1, y, 3 + i % 6, y).result() == Result.OK, "path " + i + " fits");
            }
            check(helper, left.mimicCount() == 128 && right.mimicCount() == 0, "128 paths in the block");
            var refused = s.place(left, 4, 4, 30, 4);
            check(helper, refused.result() == Result.TOO_MANY_PATHS, "the 129th path touching the block is refused: " + refused);
            check(helper, left.mimicCount() == 128 && right.mimicCount() == 0, "the refusal left no partial piece in the neighbour");
            check(helper, s.place(right, 3, 3, 12, 3).result() == Result.OK, "the neighbour still has room for its own");
            left.clearMimicPaths();
            right.clearMimicPaths();

            // Tape: a 68 cell path costs three; short of tape the path stays in the stack and is not placed.
            ItemStack scarce = s.tape(2);
            Object[][] clicks = {{left, 2.0, 2.0}, {right, 14.0, 2.0}, {right, 14.0, 14.0}, {left, 2.0, 14.0}};
            for (Object[] click : clicks) {
                Result r = s.use((PanelBlockEntity) click[0], (Double) click[1], (Double) click[2]);
                check(helper, r == Result.POINT_ADDED, "drawing a long path: " + r);
            }
            check(helper, java.util.Arrays.equals(draftPoints(scarce), new int[] {2, 2, 30, 2, 30, 14, 2, 14}),
                    "the long path is in the draft: " + java.util.Arrays.toString(draftPoints(scarce)));
            s.useInAir(scarce);
            check(helper, left.mimicCount() == 0 && scarce.getCount() == 2 && draftPoints(scarce).length == 8,
                    "without enough tape nothing is placed and the draft is kept");
            scarce.setCount(3);
            s.useInAir(scarce);
            check(helper, left.mimicCount() == 1 && right.mimicCount() == 1 && scarce.isEmpty(),
                    "with three tapes it is placed and they are used up: " + scarce);

            // Creative costs nothing.
            left.clearMimicPaths();
            right.clearMimicPaths();
            s.player.setGameMode(GameType.CREATIVE);
            ItemStack free = s.tape(1);
            s.use(left, 2.0, 2.0);
            s.use(left, 14.0, 2.0);
            s.useInAir(free);
            check(helper, left.mimicCount() == 1 && free.getCount() == 1, "creative mode costs no tape");
            s.player.setGameMode(GameType.SURVIVAL);
        });
    }

    // ---- modes

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void modesEraseAndEditPickThePathUnderTheCursor(GameTestHelper helper) {
        Stage.run(helper, 10, s -> {
            s.wall(1, 1);
            PanelBlockEntity block = s.be(s.at(0, 0));
            check(helper, s.place(block, 2, 4, 14, 4).result() == Result.OK && s.place(block, 2, 10, 14, 10).result() == Result.OK,
                    "two paths");
            ItemStack stack = s.tape(1);
            check(helper, MimicOps.cycleMode(s.player, InteractionHand.MAIN_HAND, 1)
                    && MimicTapeItem.mode(stack) == MimicMode.ERASE, "sneak-scroll up: erase");
            check(helper, s.use(block, 8.0, 10.3) == Result.OK, "erase takes the path under the cursor");
            check(helper, block.mimicCount() == 1 && points(block.mimicPaths().get(0)).equals("[2, 4, 14, 4]"),
                    "the other path stays: " + block.mimicPaths());
            check(helper, MimicOps.cycleMode(s.player, InteractionHand.MAIN_HAND, 1)
                    && MimicTapeItem.mode(stack) == MimicMode.EDIT, "and on: edit");
            check(helper, s.use(block, 8.0, 4.0) == Result.OPENED, "edit asks the client to open the path's screen");
            check(helper, s.use(block, 8.0, 13.0) == Result.NO_PATH, "edit away from a path opens nothing");
            // Switching the mode drops a half drawn path.
            check(helper, MimicOps.cycleMode(s.player, InteractionHand.MAIN_HAND, 1) && MimicTapeItem.mode(stack) == MimicMode.DRAW,
                    "and back to draw");
            s.use(block, 3.0, 3.0);
            check(helper, draftPoints(stack).length == 2, "a point");
            MimicOps.cycleMode(s.player, InteractionHand.MAIN_HAND, 1);
            check(helper, draftPoints(stack).length == 0, "changing the mode dropped the unfinished path");
            // Erase respects permission: an item stack that is not the tape cannot do it.
            s.player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(BwrItems.PANEL_WRENCH.get()));
            check(helper, MimicOps.use(s.player, InteractionHand.MAIN_HAND, block.getBlockPos(), 8.0, 4.0) == Result.NOT_HOLDING_TAPE,
                    "only the tape draws and erases");
        });
    }

    // ---- indication only

    @GameTest(template = "empty", timeoutTicks = 500)
    public static void aBoundPathDimsAndLightsFromTheSignalAndChangesNothingInThePlant(GameTestHelper helper) {
        Stage.run(helper, 11, s -> {
            s.wall(1, 1);
            PanelBlockEntity block = s.be(s.at(0, 0));
            var valve = s.msiv(s.devices);
            check(helper, s.place(block, 2, 8, 14, 8).result() == Result.OK, "a path");
            MimicPath path = block.mimicPaths().get(0);
            s.linker(s.devices);
            int events = outputEventsNear(s.origin);
            boolean demandBefore = valve.isDemandOpen();
            boolean controlledBefore = valve.isComputerControlled();
            check(helper, s.bindPath(block, path, WiringOps.BindKind.PLANT, "position") == WiringOps.Result.OK, "bind to the valve position");
            check(helper, path.value() == 0.0 && !path.lit(), "a closed valve reads 0: the path is dim");
            valve.energy().receiveEnergy(20_000, false);
            valve.setDemandOpen(true);
            for (int i = 0; i < 90; i++) {
                valve.tickValve(.05);
            }
            check(helper, valve.getPosition() == 1.0, "the valve is open: " + valve.getPosition());
            s.then(PanelSyncWait.ticks(), () -> {
                check(helper, path.value() == 1.0 && path.lit(), "an open valve lights the path: " + path.value());
                CompoundTag edit = new CompoundTag();
                edit.putDouble("Threshold", 1.5);
                s.tape(1);
                check(helper, MimicOps.configure(s.player, block.getBlockPos(), path.localId(), edit), "raise the threshold");
                check(helper, !path.lit(), "the same signal below a higher threshold is dim");
                edit.putDouble("Threshold", 0.5);
                MimicOps.configure(s.player, block.getBlockPos(), path.localId(), edit);
                check(helper, path.lit(), "and lit again above a lower one");
                valve.setDemandOpen(false);
                for (int i = 0; i < 90; i++) {
                    valve.tickValve(.05);
                }
            });
            s.then(PanelSyncWait.ticks(), () -> {
                check(helper, path.value() == 0.0 && !path.lit(), "a closed valve dims it again: " + path.value());
                valve.setDemandOpen(demandBefore);
                check(helper, valve.isComputerControlled() == controlledBefore,
                        "nothing in the plant was commanded by the path or its binding");
                check(helper, outputEventsNear(s.origin) == events, "no panel output was operated, so nothing could be wired onward");
                check(helper, path.source().isLive() && block.mimicPaths().get(0).bound(), "it is an indication only: a live source and no sink");
            });
        });
    }

    // ---- shapes and waypoints (owner's change 2026-10-16: draw.io-like connectors, static)

    private static MimicStyle shape(String route, String corners) {
        MimicStyle style = new MimicStyle();
        style.route = route;
        style.corners = corners;
        return style;
    }

    @GameTest(template = "empty")
    public static void elbowRoundedAndCurvedShapesFollowTheirRules(GameTestHelper helper) {
        // Elbow: two right-angle turns through the middle of the longer axis.
        double[] elbow = MimicRoute.expand(new int[] {0, 0, 10, 6}, shape(MimicStyle.ROUTE_ELBOW, MimicStyle.CORNERS_SHARP), 1.0);
        check(helper, java.util.Arrays.equals(elbow, new double[] {0, 0, 5, 0, 5, 6, 10, 6}),
                "elbow route " + java.util.Arrays.toString(elbow));
        double[] tall = MimicRoute.expand(new int[] {0, 0, 4, 12}, shape(MimicStyle.ROUTE_ELBOW, MimicStyle.CORNERS_SHARP), 1.0);
        check(helper, java.util.Arrays.equals(tall, new double[] {0, 0, 0, 6, 4, 6, 4, 12}),
                "a taller step turns at its vertical middle " + java.util.Arrays.toString(tall));
        double[] straight = MimicRoute.expand(new int[] {0, 0, 10, 6}, new MimicStyle(), 1.0);
        check(helper, java.util.Arrays.equals(straight, new double[] {0, 0, 10, 6}), "straight and sharp leaves the points alone");

        // Rounded: the ends stay, the corner is cut by an arc that keeps to the inside of the corner.
        MimicStyle rounded = shape(MimicStyle.ROUTE_STRAIGHT, MimicStyle.CORNERS_ROUNDED);
        rounded.radius = 3;
        double[] fillet = MimicRoute.expand(new int[] {0, 0, 10, 0, 10, 10}, rounded, 1.0);
        int n = fillet.length / 2;
        check(helper, n > 5 && fillet[0] == 0 && fillet[1] == 0 && fillet[n * 2 - 2] == 10 && fillet[n * 2 - 1] == 10,
                "a rounded corner keeps both ends and adds the arc: " + n + " points");
        double nearestToCorner = Double.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            double x = fillet[i * 2];
            double y = fillet[i * 2 + 1];
            check(helper, x >= -1e-9 && x <= 10 + 1e-9 && y >= -1e-9 && y <= 10 + 1e-9, "the fillet stays inside the corner");
            nearestToCorner = Math.min(nearestToCorner, Math.hypot(10 - x, y));
        }
        // A radius-3 fillet of a right angle passes 3 * (sqrt 2 - 1) = 1.24 cells from the corner.
        check(helper, Math.abs(nearestToCorner - 3 * (Math.sqrt(2) - 1)) < 0.05, "the corner is cut by the radius: " + nearestToCorner);

        // Curved: one smooth curve through every clicked point.
        int[] clicked = {0, 0, 8, 8, 16, 0, 24, 8};
        double[] curve = MimicRoute.expand(clicked, shape(MimicStyle.ROUTE_STRAIGHT, MimicStyle.CORNERS_CURVED), 1.0);
        check(helper, curve.length / 2 > 20, "a curve is tessellated finely: " + curve.length / 2);
        for (int i = 0; i < clicked.length; i += 2) {
            boolean through = false;
            for (int k = 0; k < curve.length; k += 2) {
                through |= Math.abs(curve[k] - clicked[i]) < 1e-6 && Math.abs(curve[k + 1] - clicked[i + 1]) < 1e-6;
            }
            check(helper, through, "the curve passes through clicked point " + clicked[i] + "," + clicked[i + 1]);
        }
        // No two neighbouring curve pieces turn sharply (it is smooth, not a polyline with corners).
        for (int k = 4; k < curve.length; k += 2) {
            double ax = curve[k - 2] - curve[k - 4];
            double ay = curve[k - 1] - curve[k - 3];
            double bx = curve[k] - curve[k - 2];
            double by = curve[k + 1] - curve[k - 1];
            double cos = (ax * bx + ay * by) / Math.max(1e-12, Math.hypot(ax, ay) * Math.hypot(bx, by));
            check(helper, cos > 0.8, "a curve has no sharp kink (cos " + cos + " at piece " + k / 2 + ")");
        }

        // Picking follows the drawn shape: an elbow path is picked on its vertical leg, which the straight line between
        // its two points misses.
        MimicPath elbowPath = new MimicPath(7L, 1, new int[] {2, 2, 14, 14}, shape(MimicStyle.ROUTE_ELBOW, MimicStyle.CORNERS_SHARP));
        MimicPath straightPath = new MimicPath(8L, 2, new int[] {2, 2, 14, 14}, new MimicStyle());
        check(helper, MimicGeometry.distance(elbowPath, 8, 12, 1.0) < 1e-6, "the elbow's vertical leg is under (8, 12)");
        check(helper, MimicGeometry.distance(straightPath, 8, 12, 1.0) > MimicGeometry.pickTolerance(straightPath),
                "while the straight diagonal is not");
        check(helper, MimicGeometry.pick(java.util.List.of(elbowPath), 8, 12, 1.0) == elbowPath, "so the elbow path is picked there");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void waypointsAreMovedInsertedAndDeletedInEditModeKeepingStyleAndBinding(GameTestHelper helper) {
        Stage.run(helper, 12, s -> {
            s.wall(2, 1);
            PanelBlockEntity left = s.be(s.at(0, 0));
            PanelBlockEntity right = s.be(s.at(1, 0));
            s.msiv(s.devices);
            check(helper, s.place(left, 2, 4, 12, 4).result() == Result.OK, "a path in the left block");
            MimicPath path = left.mimicPaths().get(0);
            s.tape(1);
            CompoundTag edit = new CompoundTag();
            edit.putString("Corners", MimicStyle.CORNERS_CURVED);
            edit.putInt("ArrowSize", 5);
            check(helper, MimicOps.configure(s.player, left.getBlockPos(), path.localId(), edit), "make it curved with big arrows");
            s.linker(s.devices);
            check(helper, s.bindPath(left, path, WiringOps.BindKind.PLANT, "position") == WiringOps.Result.OK, "bind it");
            ItemStack stack = s.tape(1);
            MimicOps.cycleMode(s.player, InteractionHand.MAIN_HAND, 2);
            check(helper, MimicTapeItem.mode(stack) == MimicMode.EDIT, "edit mode");

            // Pick up the end waypoint and put it down in the right-hand block: the path now spans both blocks.
            check(helper, s.use(left, 12.3, 4.2) == Result.OK, "a click on a waypoint picks it up");
            check(helper, left.mimicPaths().get(0).pointCount() == 2, "picking it up changes nothing yet");
            check(helper, s.use(right, 6.0, 10.0) == Result.OK, "the next click puts it down");
            MimicPath moved = left.mimicPaths().get(0);
            check(helper, points(moved).equals("[2, 4, 22, 10]"), "the waypoint moved, in the left block's cells: " + points(moved));
            MimicPath twin = right.mimicByPathId(moved.pathId());
            check(helper, twin != null && points(twin).equals("[-14, 4, 6, 10]"), "and the right block got its copy: " + twin);
            for (MimicPath piece : new MimicPath[] {moved, twin}) {
                check(helper, MimicStyle.CORNERS_CURVED.equals(piece.style().corners) && piece.style().arrowSize == 5,
                        "the style came along: " + piece.style().toTag());
                check(helper, piece.source() instanceof PlantSource p && p.key().equals("position"), "and the binding");
            }

            // Sneak-click on the line inserts a waypoint; sneak-click on a waypoint removes it.
            s.player.setShiftKeyDown(true);
            try {
                check(helper, s.use(left, 8.0, 5.8) == Result.OK && left.mimicPaths().get(0).pointCount() == 3,
                        "sneak-click on the line adds a waypoint: " + left.mimicPaths().get(0));
                MimicPath three = left.mimicPaths().get(0);
                check(helper, three.x(1) == 8 && three.y(1) == 6, "where the cursor was, on the grid: " + points(three));
                check(helper, s.use(left, 8.2, 6.1) == Result.OK && left.mimicPaths().get(0).pointCount() == 2,
                        "sneak-click on a waypoint removes it");
                check(helper, s.use(left, 2.0, 4.0) == Result.BAD_ARGUMENTS && left.mimicPaths().get(0).pointCount() == 2,
                        "the last two points cannot be removed");
            } finally {
                s.player.setShiftKeyDown(false);
            }

            // A move that would stack two waypoints is refused and leaves the path as it was.
            String before = points(left.mimicPaths().get(0));
            check(helper, s.use(left, 2.0, 4.0) == Result.OK, "pick up the first waypoint");
            check(helper, s.use(right, 6.0, 10.0) == Result.SAME_POINT, "dropping it on the other one is refused");
            MimicPath kept = left.mimicPaths().get(0);
            check(helper, points(kept).equals(before) && kept.source() instanceof PlantSource
                    && right.mimicByPathId(kept.pathId()) != null, "the path, its copy and its binding are untouched");

            // A style that reaches further (wider line, bigger arrows) gives the neighbouring block its copy, and a
            // path that stays inside its block gives the neighbour nothing.
            check(helper, s.place(left, 13, 4, 13, 12).result() == Result.OK, "a short path near the edge");
            MimicPath near = left.mimicPaths().get(left.mimicPaths().size() - 1);
            check(helper, right.mimicByPathId(near.pathId()) == null, "at width 2 it does not reach the right block");
            s.linker(s.devices);
            check(helper, s.bindPath(left, near, WiringOps.BindKind.PLANT, "position") == WiringOps.Result.OK, "bind it");
            s.tape(1);
            CompoundTag wider = new CompoundTag();
            wider.putInt("Width", 4);
            wider.putInt("ArrowSize", 8);
            check(helper, MimicOps.configure(s.player, left.getBlockPos(), near.localId(), wider), "widen it");
            MimicPath widened = null;
            for (MimicPath candidate : left.mimicPaths()) {
                if (points(candidate).equals("[13, 4, 13, 12]")) {
                    widened = candidate;
                }
            }
            check(helper, widened != null && widened.style().width == 4 && widened.style().arrowSize == 8, "the wider path is there");
            MimicPath reached = right.mimicByPathId(widened.pathId());
            check(helper, reached != null && reached.style().width == 4, "and now the right block holds its copy");
            check(helper, widened.source() instanceof PlantSource && reached.source() instanceof PlantSource, "bound in both");
        });
    }

    /** The sampler runs every {@code sampleTicks} ticks. */
    private static final class PanelSyncWait {
        static int ticks() {
            return dev.bwr.mod.panel.PanelConfig.sampleTicks() + 3;
        }
    }
}
