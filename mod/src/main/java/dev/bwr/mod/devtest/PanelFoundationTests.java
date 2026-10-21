package dev.bwr.mod.devtest;

import com.mojang.authlib.GameProfile;
import dev.bwr.mod.panel.PanelBlock;
import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.PanelGeometry;
import dev.bwr.mod.panel.PanelGroup;
import dev.bwr.mod.panel.PanelOps;
import dev.bwr.mod.panel.PanelType;
import dev.bwr.mod.panel.component.ComponentItem;
import dev.bwr.mod.panel.component.ComponentType;
import dev.bwr.mod.panel.component.ComponentTypes;
import dev.bwr.mod.panel.component.Footprint;
import dev.bwr.mod.panel.component.InputSource;
import dev.bwr.mod.panel.component.InputSources;
import dev.bwr.mod.panel.component.LampData;
import dev.bwr.mod.panel.component.NameplateData;
import dev.bwr.mod.panel.component.OperateAction;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.net.PanelPayloads;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.registry.BwrItems;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

/**
 * Control panel foundation: placement and collision, straddling, group
 * formation and split, wrench removal and drops, breaking a covered block,
 * validation, and save/network round trips for the nameplate and the lamp.
 *
 * <p>Every test builds at its own far-away fixture, force-loads the chunks it
 * needs, empties the volume first (so a second run on a persistent world starts
 * clean) and releases everything in {@code close()}, pass or fail.
 */
@GameTestHolder("bwr")
@PrefixGameTestTemplate(false)
public final class PanelFoundationTests {
    private PanelFoundationTests() {
    }

    private static final Direction FACING = Direction.NORTH;

    /** A cleared, force-loaded stage with a survival fake player standing in front of the panels. */
    private static final class Stage {
        final ServerLevel level;
        /** Top-left block of the panel wall. Columns run toward -X (the right of a north-facing panel). */
        final BlockPos origin;
        final FakePlayer player;

        private Runnable afterCheck;
        private final EntityLookupProbe probe;
        private int polls;
        private boolean released;

        private Stage(GameTestHelper helper, int slot) {
            level = helper.getLevel();
            origin = new BlockPos(44_900 + slot * 64, 110, 44_900);
            probe = new EntityLookupProbe(level, origin.getY() + 4.0);
            helper.testInfo.addListener(new GameTestListener() {
                @Override public void testStructureLoaded(GameTestInfo info) { }
                @Override public void testPassed(GameTestInfo info, GameTestRunner runner) { release(); }
                @Override public void testFailed(GameTestInfo info, GameTestRunner runner) { release(); }
                @Override public void testAddedForRerun(GameTestInfo before, GameTestInfo after, GameTestRunner runner) { release(); }
            });
            hold(true);
            player = FakePlayerFactory.get(level, new GameProfile(
                    UUID.nameUUIDFromBytes(("panel-test-" + slot).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    "panel-test-" + slot));
        }

        /**
         * Runs {@code body} on a clean, force-loaded fixture. The chunks are forced at once but the fixture is only
         * reset and built once entity lookups work in them (see {@link EntityLookupProbe}; until then item entities
         * left by an earlier run, the GameTest world persists, cannot be found and removed, and items the body
         * spawns cannot be counted). If the body registered an {@link #after} check it runs three ticks later. The
         * chunks are released in every outcome.
         */
        static void run(GameTestHelper helper, int slot, java.util.function.Consumer<Stage> body) {
            Stage s = new Stage(helper, slot);
            s.whenLive(helper, () -> {
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
                if (s.afterCheck == null) {
                    s.release();
                    helper.succeed();
                } else {
                    helper.runAfterDelay(3, () -> {
                        try {
                            s.afterCheck.run();
                        } finally {
                            s.release();
                        }
                        helper.succeed();
                    });
                }
            });
        }

        /** Waits a tick at a time, with no fixed count, until the chunks the wall and its drops can be in answer entity lookups. */
        private void whenLive(GameTestHelper helper, Runnable then) {
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
                whenLive(helper, then);
            });
        }

        /** Checks to run after a delay (see {@link #run}). */
        void after(Runnable check) {
            afterCheck = check;
        }

        private AABB area() {
            return new AABB(origin).inflate(24, 12, 12);
        }

        private void hold(boolean force) {
            for (int x = (origin.getX() - 40) >> 4; x <= (origin.getX() + 40) >> 4; x++) {
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
            for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, area())) {
                item.discard();
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
                case DESK -> BwrBlocks.CONTROL_PANEL_DESK.get();
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

        PanelGroup.Result place(Item item, int variant, BlockPos pos, int cx, int cy) {
            ItemStack stack = new ItemStack(item);
            if (variant > 0) {
                stack.set(dev.bwr.mod.fuel.BwrDataComponents.COMPONENT_VARIANT.get(), variant);
            }
            player.setItemInHand(InteractionHand.MAIN_HAND, stack);
            return PanelOps.place(player, InteractionHand.MAIN_HAND, pos, cx, cy);
        }

        void wrench() {
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(BwrItems.PANEL_WRENCH.get()));
        }

        List<ItemEntity> drops(Item item) {
            return level.getEntitiesOfClass(ItemEntity.class, area(), e -> e.getItem().is(item));
        }

        /** For failure messages: every item entity near the fixture. */
        String items() {
            StringBuilder out = new StringBuilder("items[");
            for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, area().inflate(40))) {
                out.append(item.getItem()).append('@').append(item.blockPosition().toShortString()).append(' ');
            }
            return out.append("] origin=").append(origin.toShortString()).toString();
        }

        void release() {
            if (released) return;
            released = true;
            try {
                probe.forget();
                clear();
            } finally {
                hold(false);
            }
        }
    }

    // ---- registry and pure pieces

    @GameTest(template = "empty")
    public static void everyComponentTypeHasItsItemAndFootprints(GameTestHelper helper) {
        helper.assertTrue(ComponentTypes.all().size() >= 2, "nameplate and lamp are registered");
        for (ComponentType type : ComponentTypes.all()) {
            Item item = type.item();
            helper.assertTrue(item instanceof ComponentItem component && component.type() == type,
                    type.id() + " has no ComponentItem under its own id");
            helper.assertTrue(BuiltInRegistries.ITEM.getKey(item).equals(type.id()), type.id() + " item id differs");
            helper.assertTrue(!type.footprints().isEmpty(), type.id() + " has no footprint");
            for (Footprint footprint : type.footprints()) {
                int maxCells = PanelGeometry.GRID * type.maxSpanBlocks();
                helper.assertTrue(footprint.w() <= maxCells && footprint.h() <= maxCells,
                        type.id() + " footprint exceeds its " + type.maxSpanBlocks() + "x" + type.maxSpanBlocks() + " block span");
            }
            helper.assertTrue(ComponentTypes.get(type.id()) == type, type.id() + " is not found by id");
            helper.assertTrue(!type.labelPrefix().isEmpty(), type.id() + " has no label prefix");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void mountingPlaneRayRoundTripsOnEveryStyleAndFacing(GameTestHelper helper) {
        double[] cell = new double[2];
        for (PanelType type : PanelType.values()) {
            for (Direction facing : Direction.Plane.HORIZONTAL) {
                PanelGeometry.Frame frame = PanelGeometry.frame(type, facing);
                for (double[] probe : new double[][] {{0.5, 0.5}, {7.5, 3.5}, {15.5, 15.5}, {20.25, 9.75}}) {
                    double px = frame.x(probe[0], probe[1], 0);
                    double py = frame.y(probe[0], probe[1], 0);
                    double pz = frame.z(probe[0], probe[1], 0);
                    // Look straight at the face from 2 blocks out, and from a 30 degree angle.
                    for (double lean : new double[] {0.0, 0.5}) {
                        double ex = px + frame.nx * 2 + frame.ux * 16 * lean * 2;
                        double ey = py + frame.ny * 2 + frame.uy * 16 * lean * 2;
                        double ez = pz + frame.nz * 2 + frame.uz * 16 * lean * 2;
                        Vec3 dir = new Vec3(px - ex, py - ey, pz - ez).normalize();
                        helper.assertTrue(PanelGeometry.rayToCell(frame, 0, 0, 0, ex, ey, ez, dir.x, dir.y, dir.z, cell),
                                type + "/" + facing + " ray missed the plane");
                        helper.assertTrue(Math.abs(cell[0] - probe[0]) < 1e-6 && Math.abs(cell[1] - probe[1]) < 1e-6,
                                type + "/" + facing + " cell " + cell[0] + "," + cell[1] + " != " + probe[0] + "," + probe[1]);
                    }
                }
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void inputSourcesRoundTripIncludingUnknownKinds(GameTestHelper helper) {
        InputSource constant = InputSources.constant(12.5);
        helper.assertTrue(InputSources.load(InputSources.save(constant)).sample(null) == 12.5, "constant round trip");
        helper.assertTrue(Double.isNaN(InputSources.load(InputSources.save(InputSources.NONE)).sample(null)),
                "none reads no signal");
        helper.assertTrue(InputSources.constant(Double.NaN) == InputSources.NONE, "a non-finite constant is no signal");
        helper.assertTrue(InputSources.constant(1e30).sample(null) == InputSources.CONSTANT_LIMIT, "constant is clamped");
        CompoundTag future = new CompoundTag();
        future.putString("Kind", "plant");
        future.putString("Device", "somewhere");
        future.putLong("Pos", 12345L);
        CompoundTag saved = InputSources.save(InputSources.load(future));
        helper.assertTrue(saved.equals(future), "an unknown source kind must survive a load/save cycle: " + saved);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void payloadCodecsRoundTripAndBoundSizes(GameTestHelper helper) {
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
        var delta = new PanelPayloads.Delta(List.of(
                new PanelPayloads.Entry(new BlockPos(1, 2, 3), 4, false, 0, 1.5),
                new PanelPayloads.Entry(new BlockPos(-5, 70, 9), 7, true, 2, Double.NaN)));
        PanelPayloads.Delta.STREAM_CODEC.encode(buffer, delta);
        var decoded = PanelPayloads.Delta.STREAM_CODEC.decode(buffer);
        helper.assertTrue(decoded.entries().size() == 2 && decoded.entries().get(0).equals(delta.entries().get(0))
                && decoded.entries().get(1).output() && Double.isNaN(decoded.entries().get(1).value()), "delta round trip");
        var huge = new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
        huge.writeVarInt(PanelPayloads.MAX_DELTAS + 1);
        boolean rejected = false;
        try {
            PanelPayloads.Delta.STREAM_CODEC.decode(huge);
        } catch (RuntimeException expected) {
            rejected = true;
        }
        helper.assertTrue(rejected, "an oversized delta packet must be refused");
        var out = new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
        CompoundTag edit = new CompoundTag();
        edit.putString("Label", "X");
        PanelPayloads.Configure.STREAM_CODEC.encode(out, new PanelPayloads.Configure(new BlockPos(9, 9, 9), 3, edit));
        var configure = PanelPayloads.Configure.STREAM_CODEC.decode(out);
        helper.assertTrue(configure.id() == 3 && configure.edit().getString("Label").equals("X"), "configure round trip");
        helper.succeed();
    }

    // ---- placement, collision, footprints

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void placementSnapsCollidesAndConsumesItems(GameTestHelper helper) {
        Stage.run(helper, 0, s -> {
            s.wall(1, 1);
            BlockPos pos = s.at(0, 0);
            PanelBlockEntity be = s.be(pos);
            helper.assertTrue(be != null && be.owned().isEmpty(), "a fresh panel owns nothing");

            var result = s.place(BwrItems.NAMEPLATE.get(), 1, pos, 2, 3);
            helper.assertTrue(result == PanelGroup.Result.OK, "nameplate 8x4 at (2,3): " + result);
            helper.assertTrue(s.player.getMainHandItem().isEmpty(), "placing consumes the item in survival");
            PanelComponent plate = be.owned().get(0);
            helper.assertTrue(plate.x() == 2 && plate.y() == 3 && plate.w() == 8 && plate.h() == 4, "anchor and footprint");
            helper.assertTrue(plate.label().equals("NP-1"), "first nameplate label: " + plate.label());

            // Collision: every cell of the plate is taken, the cell next to it is not.
            helper.assertTrue(s.place(BwrItems.LAMP.get(), 0, pos, 2, 3) == PanelGroup.Result.BLOCKED, "same cell");
            helper.assertTrue(s.place(BwrItems.LAMP.get(), 0, pos, 9, 3) == PanelGroup.Result.BLOCKED, "last cell of the plate");
            helper.assertTrue(s.place(BwrItems.LAMP.get(), 1, pos, 4, 0) == PanelGroup.Result.BLOCKED, "6x6 overlapping from above");
            helper.assertTrue(s.place(BwrItems.LAMP.get(), 0, pos, 10, 3) == PanelGroup.Result.OK, "cell right of the plate is free");
            helper.assertTrue(be.component(2).label().equals("LP-1"), "lamp label: " + be.component(2).label());
            helper.assertTrue(be.claimAt(10, 3).id() == 2 && be.claimAt(2, 3).id() == 1 && be.claimAt(14, 3) == null,
                    "claims follow the footprints");

            // Edges: nothing next door to extend into.
            helper.assertTrue(s.place(BwrItems.NAMEPLATE.get(), 0, pos, 14, 0) == PanelGroup.Result.OUTSIDE_GROUP,
                    "a footprint that leaves the group is refused");
            helper.assertTrue(s.place(BwrItems.LAMP.get(), 0, pos, 16, 0) == PanelGroup.Result.BAD_ARGUMENTS, "cell 16");
            helper.assertTrue(s.place(BwrItems.LAMP.get(), 0, pos, 0, -1) == PanelGroup.Result.BAD_ARGUMENTS, "cell -1");
            helper.assertTrue(PanelGroup.checkPlacement(s.level, pos, 15, 15, new Footprint(64, 2)).result()
                    == PanelGroup.Result.TOO_LARGE, "5 blocks wide exceeds the 4x4 block limit");
            helper.assertTrue(be.owned().size() == 2, "failed placements add nothing");

            // Second nameplate is numbered, a lamp variant carries its footprint.
            helper.assertTrue(s.place(BwrItems.NAMEPLATE.get(), 1, pos, 0, 8) == PanelGroup.Result.OK, "8x4 nameplate");
            helper.assertTrue(be.component(3).label().equals("NP-2") && be.component(3).w() == 8, "second label and width");
            helper.assertTrue(s.place(BwrItems.LAMP.get(), 1, pos, 10, 10) == PanelGroup.Result.OK
                    && be.component(4).w() == 6 && be.component(4).h() == 6, "6x6 lamp variant");

            // Reach and permission: a player on the other side of the world places nothing.
            s.player.setPos(s.origin.getX() + 300, s.origin.getY(), s.origin.getZ());
            helper.assertTrue(s.place(BwrItems.LAMP.get(), 0, pos, 12, 12) == PanelGroup.Result.BAD_ARGUMENTS,
                    "out of reach is refused");
            helper.assertTrue(be.owned().size() == 4, "no component from a distant player");

            // Non-component items never place.
            s.player.setPos(s.origin.getX() - 1.5, s.origin.getY() - 1.0, s.origin.getZ() - 2.5);
            s.player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Blocks.STONE));
            helper.assertTrue(PanelOps.place(s.player, InteractionHand.MAIN_HAND, pos, 12, 12) == PanelGroup.Result.BAD_ARGUMENTS,
                    "stone is not a component");
        });
    }

    // ---- straddling

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void componentsStraddleBlocksAndReserveTheirCells(GameTestHelper helper) {
        Stage.run(helper, 1, s -> {
            s.wall(3, 2);
            PanelBlockEntity first = s.be(s.at(0, 0));
            PanelBlockEntity second = s.be(s.at(1, 0));
            PanelBlockEntity third = s.be(s.at(2, 0));

            // 24 wide from cell 10: columns 0 (10..16), 1 (all 16) and 2 (0..2).
            helper.assertTrue(s.place(BwrItems.NAMEPLATE.get(), 2, s.at(0, 0), 10, 5) == PanelGroup.Result.OK, "24x4 straddle");
            PanelComponent plate = first.owned().get(0);
            helper.assertTrue(plate.w() == 24 && second.owned().isEmpty() && third.owned().isEmpty(),
                    "only the anchor block owns the component");
            helper.assertTrue(first.claimAt(10, 5).owner().equals(first.getBlockPos()), "own cells claimed");
            helper.assertTrue(second.claimAt(0, 5).owner().equals(first.getBlockPos())
                            && second.claimAt(15, 5).id() == plate.id() && second.claimAt(0, 4) == null,
                    "the middle block is reserved across its whole width");
            helper.assertTrue(third.claimAt(1, 5) != null && third.claimAt(2, 5) == null, "the last block is reserved to cell 1");

            // Clicks anywhere on the reserved cells reach the owner; collisions are detected in the neighbour.
            helper.assertTrue(s.place(BwrItems.LAMP.get(), 0, s.at(1, 0), 7, 5) == PanelGroup.Result.BLOCKED,
                    "a lamp on a reserved neighbour cell collides");
            helper.assertTrue(s.place(BwrItems.LAMP.get(), 0, s.at(1, 0), 7, 9) == PanelGroup.Result.OK, "the row below is free");
            helper.assertTrue(second.owned().size() == 1, "the lamp belongs to the second block");
            helper.assertTrue(second.owned().get(0).label().equals("LP-1"), "labels are numbered per type across the group");
            helper.assertTrue(s.place(BwrItems.NAMEPLATE.get(), 0, s.at(2, 0), 0, 10) == PanelGroup.Result.OK
                            && third.owned().get(0).label().equals("NP-2"),
                    "a nameplate in another block is numbered after the first: " + third.owned().get(0).label());

            // Forwarded use: a click on a reserved cell reaches the owner (nameplates ignore it, so it passes).
            helper.assertTrue(PanelOps.forwardUse(s.player, second, 4, 5, 4.5, 5.5) == net.minecraft.world.InteractionResult.PASS,
                    "forwarding resolves to a passive component");

            // A component can straddle both ways: a 6x6 lamp on a corner covers four blocks.
            helper.assertTrue(s.place(BwrItems.LAMP.get(), 1, s.at(0, 0), 15, 15) == PanelGroup.Result.OK, "6x6 corner straddle");
            PanelComponent corner = first.component(first.owned().get(first.owned().size() - 1).id());
            helper.assertTrue(second.claimAt(0, 15).id() == corner.id() && s.be(s.at(0, 1)).claimAt(15, 0).id() == corner.id()
                    && s.be(s.at(1, 1)).claimAt(0, 0).id() == corner.id(), "all four blocks reserve the corner");

            // A hole in the group stops a straddle.
            s.level.setBlock(s.at(1, 1), Blocks.AIR.defaultBlockState(), 3);
            helper.assertTrue(first.component(corner.id()) == null, "losing one of the four covered blocks removes the corner lamp");
            helper.assertTrue(s.place(BwrItems.LAMP.get(), 1, s.at(0, 0), 15, 15) == PanelGroup.Result.OUTSIDE_GROUP,
                    "no placement through a missing block");
            s.after(() -> helper.assertTrue(s.drops(BwrItems.LAMP.get()).size() == 1,
                    "the corner lamp item dropped: " + s.items()));
        });
    }

    /** The owner's 2 x 2 x 1 m back panel is four full-depth blocks: one board, face at the block front. */
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void backPanelsAreFullDepthAndJoinIntoOneBoard(GameTestHelper helper) {
        PanelGeometry.Frame frame = PanelGeometry.frame(PanelType.BACK_PANEL, Direction.NORTH);
        helper.assertTrue(Math.abs(frame.oz) < 1e-9 && Math.abs(frame.nz + 1) < 1e-9 && Math.abs(frame.vLength - 1) < 1e-9,
                "the face is upright at the operator side of the block: oz " + frame.oz + ", nz " + frame.nz);
        Stage.run(helper, 10, s -> {
            for (int r = 0; r < 2; r++) {
                for (int c = 0; c < 2; c++) {
                    s.panel(s.at(c, r), PanelType.BACK_PANEL, FACING);
                }
            }
            helper.assertTrue(s.level.getBlockState(s.at(0, 0)).getCollisionShape(s.level, s.at(0, 0)).bounds()
                    .equals(new net.minecraft.world.phys.AABB(0, 0, 0, 1, 1, 1)), "a back panel block is a full block");
            // A 6 x 6 lamp on the corner covers all four blocks, so they are one board.
            helper.assertTrue(s.place(BwrItems.LAMP.get(), 1, s.at(0, 0), 15, 15) == PanelGroup.Result.OK, "corner straddle");
            PanelBlockEntity first = s.be(s.at(0, 0));
            int id = first.owned().get(0).id();
            helper.assertTrue(s.be(s.at(1, 0)).claimAt(0, 15).id() == id && s.be(s.at(0, 1)).claimAt(15, 0).id() == id
                    && s.be(s.at(1, 1)).claimAt(0, 0).id() == id, "all four back panel blocks reserve the lamp");
        });
    }

    // ---- groups

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void groupsFormMergeAndSplit(GameTestHelper helper) {
        Stage.run(helper, 2, s -> {
            s.panel(s.at(0, 0), PanelType.VERTICAL, FACING);
            s.panel(s.at(2, 0), PanelType.VERTICAL, FACING);
            PanelGroup lone = s.be(s.at(0, 0)).group();
            helper.assertTrue(lone.members().size() == 1 && s.be(s.at(2, 0)).group() != lone, "separate blocks, separate groups");

            // Filling the gap merges them, and everyone sees the same cached object.
            s.panel(s.at(1, 0), PanelType.VERTICAL, FACING);
            PanelGroup merged = s.be(s.at(0, 0)).group();
            helper.assertTrue(!lone.isValid(), "the old group is invalidated, not edited");
            helper.assertTrue(merged.members().size() == 3 && merged.columns() == 3 && merged.rows() == 1, "three in a row");
            helper.assertTrue(s.be(s.at(1, 0)).group() == merged && s.be(s.at(2, 0)).group() == merged, "one shared group object");
            helper.assertTrue(merged.columnOf(s.at(0, 0)) == 0 && merged.columnOf(s.at(2, 0)) == 2, "column 0 is the operator's left");
            helper.assertTrue(s.be(s.at(0, 0)).group() == merged, "the group is cached between calls");

            // A row below joins; a block behind it (different plane) does not.
            s.panel(s.at(1, 1), PanelType.VERTICAL, FACING);
            s.panel(s.at(0, 0).relative(FACING.getOpposite()), PanelType.VERTICAL, FACING);
            PanelGroup tall = s.be(s.at(0, 0)).group();
            helper.assertTrue(tall.members().size() == 4 && tall.rows() == 2 && tall.rowOf(s.at(1, 1)) == 1, "second row joins");
            helper.assertTrue(!tall.contains(s.at(0, 0).relative(FACING.getOpposite())), "a block behind the plane is its own group");

            // Facing and style are part of the identity.
            s.panel(s.at(3, 0), PanelType.VERTICAL, Direction.SOUTH);
            s.panel(s.at(0, 1), PanelType.CONSOLE, FACING);
            helper.assertTrue(!s.be(s.at(0, 0)).group().contains(s.at(3, 0)) && !s.be(s.at(0, 0)).group().contains(s.at(0, 1)),
                    "different facing or style never joins");

            // Removing the middle splits the row.
            s.level.setBlock(s.at(1, 0), Blocks.AIR.defaultBlockState(), 3);
            helper.assertTrue(!tall.isValid(), "removal invalidates the group");
            PanelGroup left = s.be(s.at(0, 0)).group();
            PanelGroup right = s.be(s.at(2, 0)).group();
            helper.assertTrue(left != right && left.members().size() == 1 && right.members().size() == 1,
                    "the row splits in two");
            helper.assertTrue(s.be(s.at(1, 1)).group().members().size() == 1, "the block below the gap is on its own");

            // Benchboards continue down the slope: one row down is also one block toward the operator.
            BlockPos bench = s.origin.above(4).west(8);
            s.panel(bench, PanelType.BENCHBOARD, FACING);
            s.panel(bench.below().relative(FACING), PanelType.BENCHBOARD, FACING);
            s.panel(bench.below(), PanelType.BENCHBOARD, FACING);
            PanelGroup slope = s.be(bench).group();
            helper.assertTrue(slope.members().size() == 2 && slope.rows() == 2 && slope.contains(bench.below().relative(FACING)),
                    "the next benchboard row is down and forward, not straight down");
            // Consoles never form rows.
            BlockPos desk = s.origin.above(8).west(8);
            s.panel(desk, PanelType.CONSOLE, FACING);
            s.panel(desk.below().relative(FACING), PanelType.CONSOLE, FACING);
            s.panel(desk.below(), PanelType.CONSOLE, FACING);
            helper.assertTrue(s.be(desk).group().members().size() == 1, "console groups are a single row");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void groupSizeIsCappedByConfig(GameTestHelper helper) {
        Stage.run(helper, 3, s -> {
            int columns = dev.bwr.mod.panel.PanelConfig.maxColumns();
            for (int c = 0; c < columns; c++) {
                s.panel(s.col(c), PanelType.VERTICAL, FACING);
            }
            // The cap is 16 columns by default, so the corridor of chunks around the fixture must reach that far.
            helper.assertTrue(!s.be(s.col(0)).group().oversize(), "exactly the cap is fine");
            helper.assertTrue(!PanelGroup.placementAllowed(s.level, s.col(columns), PanelType.VERTICAL, FACING),
                    "one more column is refused at placement");
            helper.assertTrue(PanelGroup.placementAllowed(s.level, s.at(0, 1), PanelType.VERTICAL, FACING),
                    "a second row still fits");
            helper.assertTrue(PanelGroup.placementAllowed(s.level, s.col(columns), PanelType.VERTICAL, Direction.SOUTH),
                    "a different facing is a different group");
            // Forced past the cap (command, structure) the group still works but takes no components.
            s.panel(s.col(columns), PanelType.VERTICAL, FACING);
            helper.assertTrue(s.be(s.col(0)).group().oversize(), "forced oversize is flagged");
            helper.assertTrue(s.place(BwrItems.LAMP.get(), 0, s.col(0), 0, 0) == PanelGroup.Result.OVERSIZE_GROUP,
                    "an oversize group refuses components");
        });
    }

    // ---- removal and breaking

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void wrenchRemovalDropsTheItemAndFreesTheCells(GameTestHelper helper) {
        Stage.run(helper, 4, s -> {
            s.wall(2, 1);
            PanelBlockEntity first = s.be(s.at(0, 0));
            PanelBlockEntity second = s.be(s.at(1, 0));
            helper.assertTrue(s.place(BwrItems.LAMP.get(), 1, s.at(0, 0), 15, 4) == PanelGroup.Result.OK, "6x6 lamp across two blocks");
            int id = first.owned().get(0).id();

            // No wrench, no removal; and not from out of reach.
            s.player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            helper.assertTrue(!PanelOps.remove(s.player, first.getBlockPos(), id), "removal needs the wrench");
            s.wrench();
            s.player.setPos(s.origin.getX() + 200, s.origin.getY(), s.origin.getZ());
            helper.assertTrue(!PanelOps.remove(s.player, first.getBlockPos(), id), "removal needs reach");
            s.player.setPos(s.origin.getX() - 1.5, s.origin.getY() - 1.0, s.origin.getZ() - 2.5);
            helper.assertTrue(!PanelOps.remove(s.player, first.getBlockPos(), id + 99), "an unknown component id removes nothing");
            helper.assertTrue(first.owned().size() == 1, "nothing happened so far");

            helper.assertTrue(PanelOps.remove(s.player, first.getBlockPos(), id), "wrench removal");
            helper.assertTrue(first.owned().isEmpty(), "component gone");
            helper.assertTrue(second.claimAt(0, 4) == null && first.claimAt(15, 4) == null, "the cells are free again");
            helper.assertTrue(s.place(BwrItems.NAMEPLATE.get(), 1, s.at(0, 0), 12, 4) == PanelGroup.Result.OK,
                    "the freed cells accept a new component");
            s.after(() -> {
                List<ItemEntity> drops = s.drops(BwrItems.LAMP.get());
                helper.assertTrue(drops.size() == 1 && drops.get(0).getItem().getCount() == 1,
                        "exactly one lamp item dropped: " + s.items());
                helper.assertTrue(ComponentItem.footprint(drops.get(0).getItem()).equals(new Footprint(6, 6)),
                        "the dropped item keeps its footprint");
            });
        });
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void breakingACoveredBlockDropsTheComponent(GameTestHelper helper) {
        Stage.run(helper, 5, s -> {
            s.wall(3, 1);
            PanelBlockEntity first = s.be(s.at(0, 0));
            PanelBlockEntity third = s.be(s.at(2, 0));
            helper.assertTrue(s.place(BwrItems.NAMEPLATE.get(), 2, s.at(0, 0), 10, 5) == PanelGroup.Result.OK, "straddling plate");
            helper.assertTrue(s.place(BwrItems.LAMP.get(), 0, s.at(0, 0), 1, 1) == PanelGroup.Result.OK, "bystander lamp");

            // Break the block in the middle: not the owner, covered only by the claim.
            s.level.destroyBlock(s.at(1, 0), false);
            helper.assertTrue(first.component(1) == null, "the straddling component is gone");
            helper.assertTrue(first.component(2) != null, "other components on the owner are untouched");
            helper.assertTrue(third.claimAt(0, 5) == null && first.claimAt(12, 5) == null, "claims on both sides are released");
            helper.assertTrue(first.group().members().size() == 1 && third.group().members().size() == 1, "the wall split");

            // Rebuild, then break the owner: the straddling component drops, neighbours' claims go.
            s.level.setBlock(s.at(1, 0), BwrBlocks.CONTROL_PANEL_VERTICAL.get().defaultBlockState(), 3);
            helper.assertTrue(s.place(BwrItems.NAMEPLATE.get(), 2, s.at(0, 0), 10, 5) == PanelGroup.Result.OK, "straddling plate again");
            s.level.destroyBlock(s.at(0, 0), false);
            helper.assertTrue(s.be(s.at(1, 0)).claimAt(0, 5) == null, "no claim outlives its owner");
            helper.assertTrue(s.be(s.at(0, 0)) == null, "the owner block is gone");

            // Breaking the far covered block works the same way.
            helper.assertTrue(s.place(BwrItems.NAMEPLATE.get(), 0, s.at(1, 0), 10, 5) == PanelGroup.Result.OK, "plate over blocks 1 and 2");
            s.level.destroyBlock(s.at(2, 0), false);
            helper.assertTrue(s.be(s.at(1, 0)).owned().isEmpty() && s.be(s.at(1, 0)).claimAt(12, 5) == null,
                    "breaking the last covered block removes it as well");
            s.after(() -> {
                helper.assertTrue(s.drops(BwrItems.NAMEPLATE.get()).size() == 3,
                        "one nameplate per broken block scenario: " + s.items());
                helper.assertTrue(s.drops(BwrItems.LAMP.get()).size() == 1, "the lamp on the broken owner dropped too");
            });
        });
    }

    // ---- configuration, operation, saves

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void configureValidatesEveryField(GameTestHelper helper) {
        Stage.run(helper, 6, s -> {
            s.wall(1, 1);
            PanelBlockEntity be = s.be(s.at(0, 0));
            helper.assertTrue(s.place(BwrItems.NAMEPLATE.get(), 0, s.at(0, 0), 0, 0) == PanelGroup.Result.OK, "plate");
            helper.assertTrue(s.place(BwrItems.LAMP.get(), 0, s.at(0, 0), 0, 4) == PanelGroup.Result.OK, "lamp");
            PanelComponent plate = be.component(1);
            PanelComponent lamp = be.component(2);
            BlockPos pos = s.at(0, 0);

            CompoundTag edit = new CompoundTag();
            edit.putString("Label", "MAIN");
            CompoundTag config = new CompoundTag();
            config.putString("Text", "x".repeat(100));
            config.putInt("Plate", 0x7F123456);
            config.putString("Bogus", "dropped");
            edit.put("Config", config);

            s.player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            helper.assertTrue(!PanelOps.configure(s.player, pos, 1, edit), "configuring needs the wrench");
            s.wrench();
            helper.assertTrue(PanelOps.configure(s.player, pos, 1, edit), "configure with the wrench");
            NameplateData data = (NameplateData) plate.data();
            helper.assertTrue(plate.label().equals("MAIN"), "label applied: " + plate.label());
            helper.assertTrue(data.text.length() == NameplateData.MAX_TEXT, "text is truncated to " + NameplateData.MAX_TEXT);
            helper.assertTrue(data.plate == 0x123456, "colours are masked to 24 bits: " + Integer.toHexString(data.plate));
            helper.assertTrue(!data.toTag().contains("Bogus"), "unknown keys are dropped");
            helper.assertTrue(data.ink == NameplateData.DEFAULT_INK, "fields not sent keep their value");

            // Labels are unique in the group, trimmed and length-limited.
            CompoundTag clash = new CompoundTag();
            clash.putString("Label", "MAIN");
            PanelOps.configure(s.player, pos, 2, clash);
            helper.assertTrue(lamp.label().equals("LP-1"), "a duplicate label is ignored: " + lamp.label());
            clash.putString("Label", "  A very long label that cannot fit  ");
            PanelOps.configure(s.player, pos, 2, clash);
            helper.assertTrue(lamp.label().length() <= 16 && !lamp.label().isBlank() && !lamp.label().startsWith(" "),
                    "labels are cleaned: '" + lamp.label() + "'");

            // Enumerations accept only declared options; sources accept only none/constant with finite values.
            CompoundTag lampEdit = new CompoundTag();
            CompoundTag lampConfig = new CompoundTag();
            lampConfig.putString("Flash", "strobe");
            lampConfig.putInt("Color", 0x00FF00);
            lampEdit.put("Config", lampConfig);
            ListTag inputs = new ListTag();
            CompoundTag constant = new CompoundTag();
            constant.putString("Kind", "constant");
            constant.putDouble("Value", 1.0);
            inputs.add(constant);
            lampEdit.put("Inputs", inputs);
            helper.assertTrue(PanelOps.configure(s.player, pos, 2, lampEdit), "lamp configure");
            LampData lampData = (LampData) lamp.data();
            helper.assertTrue(lampData.flash.equals(LampData.STEADY) && lampData.color == 0x00FF00, "bad enum ignored, colour applied");
            helper.assertTrue(lamp.input(0) == 1.0 && LampData.active(lamp.input(0)), "a constant source lights the lamp");
            lampConfig.putString("Flash", "fast");
            lampEdit.put("Config", lampConfig);
            CompoundTag plant = new CompoundTag();
            plant.putString("Kind", "plant");
            plant.putDouble("Value", 5.0);
            CompoundTag nan = new CompoundTag();
            nan.putString("Kind", "constant");
            nan.putDouble("Value", Double.NaN);
            ListTag badInputs = new ListTag();
            badInputs.add(plant);
            lampEdit.put("Inputs", badInputs);
            PanelOps.configure(s.player, pos, 2, lampEdit);
            helper.assertTrue(lampData.flash.equals(LampData.FAST), "valid enum applied");
            helper.assertTrue(lamp.input(0) == 1.0, "a client cannot set a source kind that only the wiring layer may set");
            badInputs.clear();
            badInputs.add(nan);
            PanelOps.configure(s.player, pos, 2, lampEdit);
            helper.assertTrue(lamp.input(0) == 1.0, "a non-finite constant is ignored");
            ListTag clear = new ListTag();
            CompoundTag none = new CompoundTag();
            none.putString("Kind", "none");
            clear.add(none);
            lampEdit.put("Inputs", clear);
            PanelOps.configure(s.player, pos, 2, lampEdit);
            helper.assertTrue(Double.isNaN(lamp.input(0)) && !LampData.active(lamp.input(0)), "none reads no signal");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void operateIsValidatedBeforeItReachesTheType(GameTestHelper helper) {
        Stage.run(helper, 7, s -> {
            s.wall(1, 1);
            helper.assertTrue(s.place(BwrItems.LAMP.get(), 0, s.at(0, 0), 0, 0) == PanelGroup.Result.OK, "lamp");
            BlockPos pos = s.at(0, 0);
            helper.assertTrue(!PanelOps.operate(s.player, pos, 1, OperateAction.PRESS, 0), "lamps are not operable");
            helper.assertTrue(!PanelOps.operate(s.player, pos, 1, null, 0), "unknown action");
            helper.assertTrue(!PanelOps.operate(s.player, pos, 1, OperateAction.SET, Double.NaN), "NaN");
            helper.assertTrue(!PanelOps.operate(s.player, pos, 1, OperateAction.SET, Double.POSITIVE_INFINITY), "infinity");
            helper.assertTrue(!PanelOps.operate(s.player, pos, 77, OperateAction.PRESS, 0), "unknown component");
            s.player.setPos(s.origin.getX() + 200, s.origin.getY(), s.origin.getZ());
            helper.assertTrue(!PanelOps.operate(s.player, pos, 1, OperateAction.PRESS, 0), "out of reach");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void nameplateAndLampSurviveSaveReloadAndNetworkSync(GameTestHelper helper) {
        Stage.run(helper, 8, s -> {
            s.wall(2, 1);
            PanelBlockEntity owner = s.be(s.at(0, 0));
            helper.assertTrue(s.place(BwrItems.NAMEPLATE.get(), 0, s.at(0, 0), 10, 2) == PanelGroup.Result.OK, "straddling plate");
            helper.assertTrue(s.place(BwrItems.LAMP.get(), 1, s.at(0, 0), 2, 6) == PanelGroup.Result.OK, "6x6 lamp");
            s.wrench();
            CompoundTag plateEdit = new CompoundTag();
            plateEdit.putString("Label", "SPEC-PLATE");
            CompoundTag plateConfig = new CompoundTag();
            plateConfig.putString("Text", "REACTOR PRESSURE");
            plateConfig.putInt("Plate", 0x101820);
            plateConfig.putInt("Ink", 0xFFB000);
            plateEdit.put("Config", plateConfig);
            helper.assertTrue(PanelOps.configure(s.player, s.at(0, 0), 1, plateEdit), "configure plate");
            CompoundTag lampEdit = new CompoundTag();
            lampEdit.putString("Label", "SCRAM");
            CompoundTag lampConfig = new CompoundTag();
            lampConfig.putInt("Color", 0xFF2200);
            lampConfig.putString("Flash", "slow");
            lampEdit.put("Config", lampConfig);
            ListTag inputs = new ListTag();
            CompoundTag constant = new CompoundTag();
            constant.putString("Kind", "constant");
            constant.putDouble("Value", 2.5);
            inputs.add(constant);
            lampEdit.put("Inputs", inputs);
            helper.assertTrue(PanelOps.configure(s.player, s.at(0, 0), 2, lampEdit), "configure lamp");

            // World save: write the block entity the way a chunk does and load it into a fresh instance.
            var registries = s.level.registryAccess();
            CompoundTag saved = owner.saveWithoutMetadata(registries);
            helper.assertTrue(saved.getCompound("bwr_panel").getInt("Version") == PanelBlockEntity.SAVE_VERSION, "versioned");
            PanelBlockEntity reloaded = new PanelBlockEntity(owner.getBlockPos(), owner.getBlockState());
            reloaded.loadWithComponents(saved, registries);
            helper.assertTrue(reloaded.savePanel(false).equals(owner.savePanel(false)), "save is a fixed point of load");
            PanelComponent plate = reloaded.component(1);
            PanelComponent lamp = reloaded.component(2);
            NameplateData plateData = (NameplateData) plate.data();
            LampData lampData = (LampData) lamp.data();
            helper.assertTrue(plate.label().equals("SPEC-PLATE") && plate.x() == 10 && plate.y() == 2 && plate.w() == 16,
                    "plate placement and label");
            helper.assertTrue(plateData.text.equals("REACTOR PRESSURE") && plateData.plate == 0x101820 && plateData.ink == 0xFFB000,
                    "plate config");
            helper.assertTrue(lamp.label().equals("SCRAM") && lamp.w() == 6 && lamp.h() == 6 && lampData.color == 0xFF2200
                    && lampData.flash.equals(LampData.SLOW), "lamp config");
            helper.assertTrue(lamp.source(0).sample(null) == 2.5, "lamp source is the constant 2.5");
            helper.assertTrue(reloaded.claimAt(11, 2).id() == 1 && reloaded.claimAt(3, 7).id() == 2 && reloaded.claimAt(0, 0) == null,
                    "own cells are reserved again after load");

            // The neighbour's claim for the straddling plate survives its own save.
            PanelBlockEntity neighbour = s.be(s.at(1, 0));
            PanelBlockEntity neighbourCopy = new PanelBlockEntity(neighbour.getBlockPos(), neighbour.getBlockState());
            neighbourCopy.loadWithComponents(neighbour.saveWithoutMetadata(registries), registries);
            helper.assertTrue(neighbourCopy.claimAt(0, 2) != null && neighbourCopy.claimAt(0, 2).owner().equals(owner.getBlockPos()),
                    "a reservation saves with absolute owner position");
            helper.assertTrue(neighbourCopy.claimAt(9, 2) != null && neighbourCopy.claimAt(10, 2) == null, "reservation rectangle");

            // Network form: the client also gets the live values.
            PanelBlockEntity clientCopy = new PanelBlockEntity(owner.getBlockPos(), owner.getBlockState());
            clientCopy.applyNetworkState(owner.savePanel(true));
            helper.assertTrue(clientCopy.component(2).input(0) == 2.5 && LampData.active(clientCopy.component(2).input(0)),
                    "the client sees the lamp's live input");
            clientCopy.applyDelta(new PanelBlockEntity.ValueDelta(2, false, 0, 0.0));
            helper.assertTrue(!LampData.active(clientCopy.component(2).input(0)), "a delta switches the lamp off on the client");

            // A component type this build does not know is kept and still reserves its cells.
            CompoundTag withUnknown = owner.saveWithoutMetadata(registries);
            CompoundTag unknown = new CompoundTag();
            unknown.putInt("Id", 9);
            unknown.putString("Type", "bwr:future_gauge");
            unknown.putInt("X", 0);
            unknown.putInt("Y", 12);
            unknown.putInt("W", 4);
            unknown.putInt("H", 4);
            unknown.putString("Label", "FUT-1");
            CompoundTag secret = new CompoundTag();
            secret.putString("Needle", "kept");
            unknown.put("Data", secret);
            withUnknown.getCompound("bwr_panel").getList("Components", 10).add(unknown);
            PanelBlockEntity future = new PanelBlockEntity(owner.getBlockPos(), owner.getBlockState());
            future.loadWithComponents(withUnknown, registries);
            helper.assertTrue(future.claimAt(2, 13) != null && future.component(9).type() == null, "unknown type keeps its cells");
            helper.assertTrue(future.savePanel(false).getList("Components", 10).get(2).equals(unknown),
                    "unknown type is written back unchanged");
            helper.assertTrue(future.owned().size() == 3 && future.savePanel(false).getInt("NextId") > 9, "ids continue past the unknown one");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void settingInputsStoresValuesAndNotifiesTheType(GameTestHelper helper) {
        Stage.run(helper, 9, s -> {
            s.wall(1, 1);
            helper.assertTrue(s.place(BwrItems.LAMP.get(), 0, s.at(0, 0), 0, 0) == PanelGroup.Result.OK, "lamp");
            PanelBlockEntity be = s.be(s.at(0, 0));
            PanelComponent lamp = be.component(1);
            helper.assertTrue(Double.isNaN(lamp.input(0)), "a new lamp has no signal");
            helper.assertTrue(be.setInput(lamp, 0, 1.0) && lamp.input(0) == 1.0, "pushing a value stores it");
            helper.assertTrue(!be.setInput(lamp, 0, 1.0), "an unchanged value is not a change");
            helper.assertTrue(!be.setInput(lamp, 5, 1.0), "a missing input is ignored");
            helper.assertTrue(be.setInput(lamp, 0, Double.NaN) && Double.isNaN(lamp.input(0)), "no signal can be pushed too");
            helper.assertTrue(!be.setInput(lamp, 0, Double.NaN), "NaN equals NaN");
            // Output API: lamps have none, so the call is a no-op rather than an error.
            helper.assertTrue(!be.setOutput(lamp, 0, 1.0), "components without outputs ignore setOutput");
        });
    }
}
