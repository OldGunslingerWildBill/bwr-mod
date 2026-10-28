package dev.bwr.mod.devtest;

import com.mojang.authlib.GameProfile;
import dev.bwr.mod.panel.DeskHalf;
import dev.bwr.mod.panel.DeskPanelBlock;
import dev.bwr.mod.panel.PanelBlock;
import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.PanelFinish;
import dev.bwr.mod.panel.PanelFinishes;
import dev.bwr.mod.panel.PanelGeometry;
import dev.bwr.mod.panel.PanelGroup;
import dev.bwr.mod.panel.PanelOps;
import dev.bwr.mod.panel.PanelType;
import dev.bwr.mod.panel.component.ComponentItem;
import dev.bwr.mod.panel.component.ComponentTypes;
import dev.bwr.mod.panel.component.Footprint;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.registry.BwrItems;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
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

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * The owner's two control desks ({@link PanelType#DESK}): the level top as a mounting frame 16 cells wide and 6 deep,
 * the outline that holds it, placing the 1 m desk and the two-block 2 m desk ({@link DeskPanelBlock}) through the real
 * item for every facing, refusal when the second block is taken, breaking either half (one drop, every mounted
 * component dropped, the finish kept), mounting on both halves and across the seam through the real player path, which
 * neighbours join, the finishes, and save/load. Fixtures live around 137,000 / 137,000.
 */
@GameTestHolder("bwr")
@PrefixGameTestTemplate(false)
public final class ControlDeskTests {
    private ControlDeskTests() {
    }

    private static final int ORIGIN_X = 137_000;
    private static final int ORIGIN_Z = 137_000;
    private static final int FLOOR_Y = 100;

    private interface Body {
        void run(ServerLevel level, BlockPos floor);
    }

    /** A cleared 13 x 13 stone floor with air above; the body runs once drops can be counted (see EntityLookupProbe). */
    private static void fixture(GameTestHelper h, int slot, Body body) {
        ServerLevel level = h.getLevel();
        BlockPos floor = new BlockPos(ORIGIN_X + slot * 32, FLOOR_Y, ORIGIN_Z);
        AABB bounds = new AABB(floor.getX() - 8, FLOOR_Y, floor.getZ() - 8, floor.getX() + 8, FLOOR_Y + 7, floor.getZ() + 8);
        EntityLookupProbe.runWhenLive(h, bounds, () -> {
            clear(level, floor);
            try {
                body.run(level, floor);
            } finally {
                clear(level, floor);
            }
            h.succeed();
        });
    }

    private static void clear(ServerLevel level, BlockPos floor) {
        for (BlockPos pos : BlockPos.betweenClosed(floor.offset(-6, 0, -6), floor.offset(6, 6, 6))) {
            BlockState want = pos.getY() == floor.getY() ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
            if (level.getBlockState(pos) != want) {
                level.setBlock(pos, want, 2);
            }
        }
        clearItems(level, floor);
    }

    private static AABB area(BlockPos floor) {
        return new AABB(floor).inflate(8);
    }

    private static void clearItems(ServerLevel level, BlockPos floor) {
        level.getEntitiesOfClass(ItemEntity.class, area(floor)).forEach(ItemEntity::discard);
    }

    private static int dropped(ServerLevel level, BlockPos floor, Item item) {
        return level.getEntitiesOfClass(ItemEntity.class, area(floor)).stream()
                .filter(e -> e.getItem().is(item)).mapToInt(e -> e.getItem().getCount()).sum();
    }

    private static int droppedComponents(ServerLevel level, BlockPos floor) {
        return level.getEntitiesOfClass(ItemEntity.class, area(floor)).stream()
                .filter(e -> e.getItem().getItem() instanceof ComponentItem).mapToInt(e -> e.getItem().getCount()).sum();
    }

    private static FakePlayer player(ServerLevel level, String name, GameType mode) {
        FakePlayer player = FakePlayerFactory.get(level,
                new GameProfile(UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)), name));
        player.setGameMode(mode);
        player.getInventory().clearContent();
        player.setShiftKeyDown(false);
        return player;
    }

    /**
     * Uses {@code stack} on the top of the floor block under {@code at}, the player four blocks out on the operator's
     * side looking back, so the desk faces {@code facing} with its (origin) block at {@code at}.
     */
    private static boolean place(FakePlayer player, ItemStack stack, BlockPos at, Direction facing) {
        player.setPos(Vec3.atBottomCenterOf(at.relative(facing, 4)));
        player.setYRot(facing.getOpposite().toYRot());
        player.setXRot(0);
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        BlockPos floor = at.below();
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(floor).add(0, 0.5, 0), Direction.UP, floor, false);
        return stack.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, hit)).consumesAction();
    }

    private static boolean placeDesk(FakePlayer player, BlockPos at, Direction facing) {
        return place(player, new ItemStack(BwrItems.CONTROL_PANEL_DESK.get()), at, facing);
    }

    private static boolean placeWide(FakePlayer player, BlockPos at, Direction facing) {
        return place(player, new ItemStack(BwrItems.CONTROL_PANEL_DESK_WIDE.get()), at, facing);
    }

    private static int deskBlocks(ServerLevel level, BlockPos floor) {
        int n = 0;
        for (BlockPos pos : BlockPos.betweenClosed(floor.offset(-6, 1, -6), floor.offset(6, 6, 6))) {
            BlockState state = level.getBlockState(pos);
            if (state.is(BwrBlocks.CONTROL_PANEL_DESK.get()) || state.is(BwrBlocks.CONTROL_PANEL_DESK_WIDE.get())) {
                n++;
            }
        }
        return n;
    }

    private static PanelBlockEntity be(ServerLevel level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof PanelBlockEntity be ? be : null;
    }

    private static PanelComponent mountDirect(ServerLevel level, BlockPos at, int cx, int cy, int w, int h,
                                              dev.bwr.mod.panel.component.ComponentType type) {
        return PanelOps.placeDirect(level, at, cx, cy, type, Footprint.of(w, h));
    }

    private static PanelGroup.Result verdict(ServerLevel level, BlockPos at, int cx, int cy, int w, int h) {
        return PanelGroup.checkPlacement(level, at, cx, cy, Footprint.of(w, h)).result();
    }

    /** The real player path: the item in hand, the player in reach, the cell chosen. */
    private static PanelGroup.Result mount(FakePlayer player, BlockPos anchor, Direction facing, Item item, BlockPos at,
                                           int cx, int cy) {
        player.setPos(Vec3.atBottomCenterOf(anchor.relative(facing, 1)));
        ItemStack stack = new ItemStack(item);
        stack.set(dev.bwr.mod.fuel.BwrDataComponents.COMPONENT_VARIANT.get(), 0);
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        return PanelOps.place(player, InteractionHand.MAIN_HAND, at, cx, cy);
    }

    private static void check(GameTestHelper h, boolean condition, String message) {
        h.assertTrue(condition, message);
    }

    private static void expect(GameTestHelper h, PanelGroup.Result got, PanelGroup.Result want, String message) {
        h.assertTrue(got == want, message + ": " + got);
    }

    private static boolean near(double a, double b) {
        return Math.abs(a - b) < 1.0e-9;
    }

    // ---- the frame

    @GameTest(template = "empty")
    public static void deskFrameIsALevelTopSixRowsDeepStandingAtTheBackOfTheBlock(GameTestHelper h) {
        // North-facing, the operator at -Z: the grid starts at the foot of the riser (0.125 from the back of the block, so
        // z = 0.875) and runs 6 cells, 0.375 block, toward the operator to the front edge of the top (z = 0.5), 0.75 up.
        PanelGeometry.Frame north = PanelGeometry.frame(PanelType.DESK, Direction.NORTH);
        check(h, near(north.x(0, 0, 0), 1.0) && near(north.y(0, 0, 0), 0.75) && near(north.z(0, 0, 0), 0.875),
                "cell (0,0) is the operator's left corner of the back edge of the top: " + north.x(0, 0, 0) + ","
                        + north.y(0, 0, 0) + "," + north.z(0, 0, 0));
        check(h, near(north.x(16, 6, 0), 0.0) && near(north.y(16, 6, 0), 0.75) && near(north.z(16, 6, 0), 0.5),
                "cell (16,6) is the right corner of the front edge");
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            PanelGeometry.Frame f = PanelGeometry.frame(PanelType.DESK, facing);
            check(h, near(f.nx, 0) && near(f.ny, 1) && near(f.nz, 0), facing + ": the top is level, the normal straight up");
            check(h, near(f.vLength, 1.0), facing + ": a row is as long as a column is wide");
            // rows run toward the operator (the way the desk faces), columns to the operator's right
            check(h, near(f.downX, facing.getStepX()) && near(f.downY, 0) && near(f.downZ, facing.getStepZ()), facing + ": rows run toward the operator");
            Direction right = facing.getCounterClockWise();
            check(h, near(f.rightX, right.getStepX()) && near(f.rightZ, right.getStepZ()), facing + ": columns run to the operator's right");
            // the front edge (row 6) of the middle column is on the middle of the block; the back edge 0.375 behind it
            check(h, near(f.x(8, 6, 0), 0.5) && near(f.z(8, 6, 0), 0.5), facing + ": the front edge of the top is at the block's middle");
            check(h, near(f.x(8, 0, 0), 0.5 - facing.getStepX() * 0.375) && near(f.z(8, 0, 0), 0.5 - facing.getStepZ() * 0.375),
                    facing + ": the back edge of the top is 0.375 behind the front edge");
        }
        check(h, !PanelType.DESK.hasRows() && PanelType.DESK.hasColumns(), "a desk is a single row that joins sideways");
        check(h, PanelType.DESK.nextRow(BlockPos.ZERO, Direction.NORTH) == null && PanelType.DESK.previousRow(BlockPos.ZERO, Direction.NORTH) == null,
                "no block lies further down or up the face");
        for (int cy = 0; cy < 16; cy++) {
            for (int cx = 0; cx < 16; cx++) {
                check(h, PanelGeometry.cellUsable(PanelType.DESK, cx, cy) == (cy < 6), "cell " + cx + "," + cy + " of a desk");
            }
        }
        check(h, PanelGeometry.rows(PanelType.DESK) == 6 && PanelGeometry.rows(PanelType.VERTICAL) == 16, "rows of the grid");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void aLookAtAnyPointOfTheDeskTopHitsTheOutlineAndMapsBackToItsCell(GameTestHelper h) {
        double[] cell = new double[2];
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            PanelGeometry.Frame f = PanelGeometry.frame(PanelType.DESK, facing);
            var shape = PanelGeometry.shape(PanelType.DESK, facing);
            check(h, near(shape.max(Direction.Axis.Y), PanelGeometry.DESK_BACK_HEIGHT) && !shape.isEmpty(),
                    facing + ": the outline reaches the shelf at the back, 0.925 up");
            for (double[] probe : new double[][] {{0.5, 0.5}, {8, 3}, {15.5, 5.5}, {2, 5}, {14, 0.25}}) {
                Vec3 point = new Vec3(f.x(probe[0], probe[1], 0), f.y(probe[0], probe[1], 0), f.z(probe[0], probe[1], 0));
                // from straight above and from the operator's side, 30 degrees up
                for (double lean : new double[] {0.0, 1.0}) {
                    Vec3 eye = point.add(f.nx * 2 + f.downX * lean * 1.2, f.ny * 2, f.nz * 2 + f.downZ * lean * 1.2);
                    Vec3 dir = point.subtract(eye).normalize();
                    var hit = shape.clip(eye, point.add(dir.scale(0.02)), BlockPos.ZERO);
                    check(h, hit != null && hit.getLocation().distanceTo(point) < 1.0e-6,
                            facing + " cell " + probe[0] + "," + probe[1] + ": the outline does not hold the top, hit " + (hit == null ? null : hit.getLocation()));
                    check(h, PanelGeometry.rayToCell(f, 0, 0, 0, eye.x, eye.y, eye.z, dir.x, dir.y, dir.z, cell)
                                    && near(cell[0], probe[0]) && near(cell[1], probe[1]),
                            facing + " cell " + probe[0] + "," + probe[1] + " came back as " + cell[0] + "," + cell[1]);
                }
            }
        }
        h.succeed();
    }

    // ---- placing

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void aOneMetreDeskIsOnePanelBlockForEveryFacing(GameTestHelper h) {
        fixture(h, 0, (level, floor) -> {
            FakePlayer player = player(level, "DeskOne", GameType.SURVIVAL);
            BlockPos at = floor.above();
            for (Direction facing : Direction.Plane.HORIZONTAL) {
                clear(level, floor);
                check(h, placeDesk(player, at, facing), "the desk was not placed facing " + facing);
                check(h, deskBlocks(level, floor) == 1, "placed " + deskBlocks(level, floor) + " blocks, not 1");
                BlockState state = level.getBlockState(at);
                check(h, state.is(BwrBlocks.CONTROL_PANEL_DESK.get()) && state.getValue(PanelBlock.FACING) == facing, "wrong state " + state);
                PanelBlockEntity panel = be(level, at);
                check(h, panel != null && panel.panelType() == PanelType.DESK && panel.group().columns() == 1 && panel.group().rows() == 1,
                        "a desk is a panel block of its own style");
            }
        });
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void aTwoMetreDeskIsTwoBlocksTheSecondToTheOperatorsLeftForEveryFacing(GameTestHelper h) {
        fixture(h, 1, (level, floor) -> {
            FakePlayer player = player(level, "DeskWide", GameType.SURVIVAL);
            BlockPos at = floor.above();
            for (Direction facing : Direction.Plane.HORIZONTAL) {
                clear(level, floor);
                check(h, placeWide(player, at, facing), "the 2 m desk was not placed facing " + facing);
                check(h, player.getMainHandItem().isEmpty(), "the item was not used up");
                check(h, deskBlocks(level, floor) == 2, "placed " + deskBlocks(level, floor) + " blocks, not 2");
                BlockPos left = at.relative(leftOf(facing));
                for (Object[] part : new Object[][] {{at, DeskHalf.RIGHT}, {left, DeskHalf.LEFT}}) {
                    BlockState state = level.getBlockState((BlockPos) part[0]);
                    check(h, state.is(BwrBlocks.CONTROL_PANEL_DESK_WIDE.get()) && state.getValue(DeskPanelBlock.HALF) == part[1]
                            && state.getValue(PanelBlock.FACING) == facing, part[1] + " is not at " + part[0] + ": " + state);
                    check(h, be(level, (BlockPos) part[0]) != null && DeskPanelBlock.origin((BlockPos) part[0], state).equals(at),
                            part[1] + " has no panel block entity or does not find its origin");
                }
                // the two tops are one face: the left half is column 0 of a group of two, 32 cells wide
                PanelGroup group = be(level, at).group();
                check(h, group.type() == PanelType.DESK && group.columns() == 2 && group.rows() == 1 && group.columnOf(left) == 0
                        && group.columnOf(at) == 1, "the two halves are one group of two columns: " + group.columns());
            }
        });
    }

    /** The direction of the operator's left of a desk facing {@code facing}: the way the second block is placed. */
    private static Direction leftOf(Direction facing) {
        return facing.getClockWise();
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void theTwoMetreDeskIsRefusedWholeWhenItsSecondBlockIsTaken(GameTestHelper h) {
        fixture(h, 2, (level, floor) -> {
            FakePlayer player = player(level, "DeskBlocked", GameType.SURVIVAL);
            BlockPos at = floor.above();
            for (Direction facing : new Direction[] {Direction.NORTH, Direction.EAST}) {
                clear(level, floor);
                BlockPos obstacle = at.relative(leftOf(facing));
                level.setBlock(obstacle, Blocks.STONE.defaultBlockState(), 3);
                check(h, !placeWide(player, at, facing), "placed over a block facing " + facing);
                check(h, deskBlocks(level, floor) == 0, "left blocks behind when refused");
                check(h, level.getBlockState(obstacle).is(Blocks.STONE), "replaced the obstacle");
                check(h, player.getMainHandItem().getCount() == 1, "used up the item when refused");
                // a block on the other side is not part of the desk
                clear(level, floor);
                level.setBlock(at.relative(facing.getCounterClockWise()), Blocks.STONE.defaultBlockState(), 3);
                check(h, placeWide(player, at, facing), "a block to the operator's right must not stop the desk");
            }
        });
    }

    // ---- breaking

    @GameTest(template = "empty", timeoutTicks = 600)
    public static void breakingEitherHalfRemovesTheDeskWithOneDropTheFinishAndItsComponents(GameTestHelper h) {
        fixture(h, 3, (level, floor) -> {
            FakePlayer player = player(level, "DeskBreak", GameType.SURVIVAL);
            BlockPos at = floor.above();
            for (GameType mode : new GameType[] {GameType.SURVIVAL, GameType.CREATIVE}) {
                for (DeskHalf broken : DeskHalf.values()) {
                    Direction facing = broken == DeskHalf.RIGHT ? Direction.NORTH : Direction.WEST;
                    clear(level, floor);
                    player.setGameMode(GameType.SURVIVAL);
                    check(h, place(player, PanelFinish.withFinish(new ItemStack(BwrItems.CONTROL_PANEL_DESK_WIDE.get()), PanelFinish.CREAM), at, facing),
                            "desk not placed");
                    check(h, mountDirect(level, at, 2, 1, 4, 4, ComponentTypes.LAMP) != null
                            && mountDirect(level, DeskHalf.LEFT.at(at, facing), 9, 1, 4, 4, ComponentTypes.LAMP) != null,
                            "components not mounted");
                    clearItems(level, floor);
                    player.setGameMode(mode);
                    player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                    check(h, player.gameMode.destroyBlock(broken.at(at, facing)), broken + " would not break");
                    check(h, deskBlocks(level, floor) == 0, "breaking the " + broken + " left " + deskBlocks(level, floor) + " blocks");
                    int items = dropped(level, floor, BwrItems.CONTROL_PANEL_DESK_WIDE.get());
                    check(h, items == (mode == GameType.SURVIVAL ? 1 : 0), mode + ": breaking the " + broken + " dropped " + items + " desks");
                    check(h, droppedComponents(level, floor) == 2,
                            mode + ": breaking the " + broken + " dropped " + droppedComponents(level, floor) + " of the 2 components");
                    if (mode == GameType.SURVIVAL) {
                        check(h, level.getEntitiesOfClass(ItemEntity.class, area(floor)).stream()
                                .filter(e -> e.getItem().is(BwrItems.CONTROL_PANEL_DESK_WIDE.get()))
                                .allMatch(e -> PanelFinish.of(e.getItem()) == PanelFinish.CREAM), "the dropped desk lost its finish");
                    }
                }
            }
            // A desk that loses a half another way (a command, an explosion) falls apart and gives its item back.
            clear(level, floor);
            player.setGameMode(GameType.SURVIVAL);
            check(h, placeWide(player, at, Direction.SOUTH), "desk not placed");
            level.setBlock(DeskHalf.LEFT.at(at, Direction.SOUTH), Blocks.AIR.defaultBlockState(), 3);
            check(h, deskBlocks(level, floor) == 0, "a broken desk left " + deskBlocks(level, floor) + " blocks");
            check(h, dropped(level, floor, BwrItems.CONTROL_PANEL_DESK_WIDE.get()) == 1, "a desk that fell apart gives one item back");
            // the 1 m desk drops itself, with its components
            clear(level, floor);
            check(h, placeDesk(player, at, Direction.NORTH), "1 m desk not placed");
            check(h, mountDirect(level, at, 2, 1, 4, 4, ComponentTypes.LAMP) != null, "lamp not mounted");
            clearItems(level, floor);
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            check(h, player.gameMode.destroyBlock(at), "1 m desk would not break");
            check(h, dropped(level, floor, BwrItems.CONTROL_PANEL_DESK.get()) == 1 && droppedComponents(level, floor) == 1,
                    "a 1 m desk drops itself and its lamp");
        });
    }

    // ---- mounting

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void componentsMountOnTheTopThroughThePlayerPathAndNotBeyondItsSixRows(GameTestHelper h) {
        fixture(h, 4, (level, floor) -> {
            FakePlayer player = player(level, "DeskMount", GameType.SURVIVAL);
            Direction facing = Direction.EAST;
            BlockPos at = floor.above();
            check(h, placeWide(player, at, facing), "desk not placed");
            BlockPos left = DeskHalf.LEFT.at(at, facing);
            for (BlockPos half : new BlockPos[] {at, left}) {
                expect(h, mount(player, at, facing, BwrItems.LAMP.get(), half, 8, 0), PanelGroup.Result.OK, "lamp on " + half);
                PanelBlockEntity panel = be(level, half);
                check(h, panel.owned().size() == 1 && panel.claimAt(8, 0) != null, half + " does not hold its component");
                check(h, player.getMainHandItem().isEmpty(), "the component item was not used up");
            }
            // the two tops are one face: a component spans the seam
            PanelBlockEntity leftPanel = be(level, left);
            check(h, PanelGroup.checkPlacement(level, left, 12, 3, Footprint.of(8, 2)).ok(), "an 8 x 2 part over the seam is refused");
            PanelComponent across = mountDirect(level, left, 12, 3, 8, 2, ComponentTypes.LAMP);
            check(h, across != null && be(level, at).claimAt(3, 3) != null && be(level, at).claimAt(3, 3).id() == across.id()
                    && be(level, at).claimAt(3, 3).owner().equals(left), "the part does not reach the next block");
            // six rows only: row 6 and 7 are the front edge and the air, a part cannot stand there or hang off the edge
            expect(h, verdict(level, left, 0, 5, 2, 2), PanelGroup.Result.BLOCKED, "a part over the front edge of the top");
            expect(h, verdict(level, left, 0, 6, 2, 2), PanelGroup.Result.BLOCKED, "a part beyond the front edge of the top");
            expect(h, verdict(level, left, 0, 10, 2, 2), PanelGroup.Result.BLOCKED, "a part in row 10");
            expect(h, verdict(level, left, 4, 4, 2, 2), PanelGroup.Result.OK, "a part wholly on the top, rows 4 and 5");
            expect(h, verdict(level, left, 0, 4, 2, 14), PanelGroup.Result.BLOCKED, "a part reaching past the front edge toward the next row, which a desk has none of");
            expect(h, verdict(level, left, 0, 0, 12, 12), PanelGroup.Result.BLOCKED, "a 12 x 12 gauge is deeper than the top");
            check(h, leftPanel.cellsUsable(0, 0, 16, 6) && !leftPanel.cellsUsable(0, 0, 16, 7), "usable cells of a block entity");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void thePlayersLookOnTheTopFindsTheCellItIsAimedAt(GameTestHelper h) {
        fixture(h, 5, (level, floor) -> {
            FakePlayer player = player(level, "DeskAim", GameType.SURVIVAL);
            BlockPos at = floor.above();
            for (Direction facing : Direction.Plane.HORIZONTAL) {
                clear(level, floor);
                check(h, placeDesk(player, at, facing), "desk not placed");
                PanelBlockEntity panel = be(level, at);
                PanelGeometry.Frame f = PanelGeometry.frame(PanelType.DESK, facing);
                for (int[] want : new int[][] {{0, 0}, {8, 3}, {15, 5}, {4, 4}}) {
                    Vec3 target = new Vec3(at.getX() + f.x(want[0] + 0.5, want[1] + 0.5, 0), at.getY() + f.y(want[0] + 0.5, want[1] + 0.5, 0),
                            at.getZ() + f.z(want[0] + 0.5, want[1] + 0.5, 0));
                    // standing in front of the desk, looking down at the top from about a metre out and 0.9 up
                    Vec3 eye = target.add(facing.getStepX() * 1.0, 0.9, facing.getStepZ() * 1.0);
                    player.setPos(eye.x, eye.y - player.getEyeHeight(), eye.z);
                    player.lookAt(EntityAnchorArgument.Anchor.EYES, target);
                    int[] cell = new int[2];
                    check(h, PanelOps.cellAt(panel, player.getEyePosition(), player.getLookAngle(), cell) && cell[0] == want[0] && cell[1] == want[1],
                            facing + ": aimed at " + want[0] + "," + want[1] + ", found " + cell[0] + "," + cell[1]);
                }
            }
        });
    }

    // ---- joining

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void desksOfBothSizesJoinSidewaysIntoOneFaceAndNothingElseJoinsThem(GameTestHelper h) {
        fixture(h, 6, (level, floor) -> {
            FakePlayer player = player(level, "DeskGroups", GameType.SURVIVAL);
            Direction facing = Direction.NORTH;
            // right to left along the operator's left: a 1 m desk, a 2 m desk, a 1 m desk
            BlockPos a = floor.above().relative(facing.getCounterClockWise(), 2);
            BlockPos b = a.relative(facing.getClockWise());
            BlockPos c = b.relative(facing.getClockWise(), 2);
            check(h, placeDesk(player, a, facing) && placeWide(player, b, facing) && placeDesk(player, c, facing), "desks not placed");
            PanelGroup run = be(level, b).group();
            check(h, run.type() == PanelType.DESK && run.columns() == 4 && run.rows() == 1 && run.members().size() == 4,
                    "a 1 m, a 2 m and a 1 m desk are one run of four columns: " + run.columns() + " x " + run.rows());
            check(h, run.columnOf(c) == 0 && run.columnOf(a) == 3, "columns run to the operator's right");
            // a part spans the whole run
            expect(h, verdict(level, c, 4, 0, 16 * 3, 2), PanelGroup.Result.OK, "a part over three desks");
            // a console, a back panel and a desk facing another way beside the run do not join it
            BlockPos beyond = a.relative(facing.getCounterClockWise());
            level.setBlock(beyond, BwrBlocks.CONTROL_PANEL_CONSOLE.get().defaultBlockState().setValue(PanelBlock.FACING, facing), 3);
            check(h, be(level, beyond).group().members().size() == 1 && !be(level, a).group().contains(beyond), "a console joined the desks");
            level.setBlock(beyond, BwrBlocks.CONTROL_PANEL_BACK.get().defaultBlockState().setValue(PanelBlock.FACING, facing), 3);
            check(h, !be(level, a).group().contains(beyond), "a back panel joined the desks");
            level.setBlock(beyond, BwrBlocks.CONTROL_PANEL_DESK.get().defaultBlockState().setValue(PanelBlock.FACING, Direction.EAST), 3);
            check(h, !be(level, a).group().contains(beyond) && be(level, beyond).group().members().size() == 1,
                    "a desk facing another way joined the run");
            // a desk behind or in front is not in the face: rows never join
            BlockPos behind = b.relative(facing.getOpposite());
            level.setBlock(behind, BwrBlocks.CONTROL_PANEL_DESK.get().defaultBlockState().setValue(PanelBlock.FACING, facing), 3);
            check(h, !be(level, b).group().contains(behind) && run.rows() == 1, "a desk behind joined the run");
        });
    }

    // ---- finishes

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void theFinishChangesTheWholeTwoMetreDeskAndOnlyTheOneMetreBlockTouched(GameTestHelper h) {
        fixture(h, 7, (level, floor) -> {
            FakePlayer player = player(level, "DeskFinish", GameType.SURVIVAL);
            Direction facing = Direction.NORTH;
            BlockPos wide = floor.above();
            BlockPos one = floor.above().relative(facing.getCounterClockWise(), 3);
            BlockPos oneNext = one.relative(facing.getCounterClockWise());
            check(h, place(player, PanelFinish.withFinish(new ItemStack(BwrItems.CONTROL_PANEL_DESK_WIDE.get()), PanelFinish.GREY), wide, facing),
                    "grey 2 m desk not placed");
            for (DeskHalf half : DeskHalf.values()) {
                check(h, PanelFinish.of(level.getBlockState(half.at(wide, facing))) == PanelFinish.GREY, half + " did not take the item's finish");
            }
            check(h, PanelFinishes.cycle(level, DeskHalf.LEFT.at(wide, facing), player), "the wrench did nothing");
            for (DeskHalf half : DeskHalf.values()) {
                check(h, PanelFinish.of(level.getBlockState(half.at(wide, facing))) == PanelFinish.LIGHT_BLUE,
                        half + " is " + PanelFinish.of(level.getBlockState(half.at(wide, facing))) + " after one step from grey");
            }
            level.setBlock(one, BwrBlocks.CONTROL_PANEL_DESK.get().defaultBlockState().setValue(PanelBlock.FACING, facing), 3);
            level.setBlock(oneNext, BwrBlocks.CONTROL_PANEL_DESK.get().defaultBlockState().setValue(PanelBlock.FACING, facing), 3);
            check(h, PanelFinishes.cycle(level, one, player), "the wrench did nothing on the 1 m desk");
            check(h, PanelFinish.of(level.getBlockState(one)) == PanelFinish.CREAM && PanelFinish.of(level.getBlockState(oneNext)) == PanelFinish.SEAFOAM,
                    "a neighbouring 1 m desk changed with it");
            // grouping and mounting never look at the finish
            check(h, be(level, one).group().members().size() == 2, "desks of different finishes do not form a run");
            // old saves have no finish and no half: the block state of a desk with neither loads as seafoam, right half
            CompoundTag old = NbtUtils.writeBlockState(BwrBlocks.CONTROL_PANEL_DESK_WIDE.get().defaultBlockState());
            old.getCompound("Properties").remove("finish");
            BlockState loaded = NbtUtils.readBlockState(level.holderLookup(Registries.BLOCK), old);
            check(h, PanelFinish.of(loaded) == PanelFinish.SEAFOAM && loaded.getValue(DeskPanelBlock.HALF) == DeskHalf.RIGHT,
                    "a state with no finish loads as seafoam");
        });
    }

    // ---- registration and saving

    @GameTest(template = "empty")
    public static void theDesksAreRegisteredWithRecipesLootAndAnItemEach(GameTestHelper h) {
        var server = h.getLevel().getServer();
        for (String name : new String[] {"control_panel_desk", "control_panel_desk_wide"}) {
            ResourceLocation id = ResourceLocation.fromNamespaceAndPath("bwr", name);
            check(h, server.getRecipeManager().byKey(id).isPresent()
                    || !dev.bwr.mod.fabrication.FabricationCrafting.making(server.getRecipeManager(), h.getLevel().registryAccess(),
                            net.minecraft.core.registries.BuiltInRegistries.ITEM.get(id)).isEmpty(), name + " has no recipe");
            check(h, net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(id) && net.minecraft.core.registries.BuiltInRegistries.BLOCK.containsKey(id),
                    name + " is not registered as a block with an item");
        }
        check(h, BwrBlocks.CONTROL_PANEL_DESK.get().getStateDefinition().getPossibleStates().size() == 4 * 5, "desk: four facings, five finishes");
        check(h, BwrBlocks.CONTROL_PANEL_DESK_WIDE.get().getStateDefinition().getPossibleStates().size() == 4 * 2 * 5,
                "2 m desk: four facings, two halves, five finishes");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void aDeskSurvivesSaveAndLoad(GameTestHelper h) {
        fixture(h, 8, (level, floor) -> {
            FakePlayer player = player(level, "DeskSave", GameType.SURVIVAL);
            BlockPos at = floor.above();
            Direction facing = Direction.SOUTH;
            check(h, placeWide(player, at, facing), "desk not placed");
            check(h, mountDirect(level, DeskHalf.LEFT.at(at, facing), 12, 1, 8, 2, ComponentTypes.LAMP) != null, "a part over the seam");
            var registries = level.registryAccess();
            for (DeskHalf half : DeskHalf.values()) {
                BlockPos pos = half.at(at, facing);
                BlockState state = level.getBlockState(pos);
                BlockState restored = NbtUtils.readBlockState(level.holderLookup(Registries.BLOCK), NbtUtils.writeBlockState(state));
                check(h, restored == state, half + " state " + state + " did not survive a save");
                PanelBlockEntity panel = be(level, pos);
                CompoundTag saved = panel.saveWithoutMetadata(registries);
                PanelBlockEntity reloaded = new PanelBlockEntity(pos, state);
                reloaded.loadWithComponents(saved, registries);
                check(h, reloaded.savePanel(false).equals(panel.savePanel(false)), half + ": the save is not a fixed point of load");
                check(h, reloaded.panelType() == PanelType.DESK, half + " reloaded as " + reloaded.panelType());
            }
            PanelBlockEntity right = be(level, at);
            PanelBlockEntity copy = new PanelBlockEntity(right.getBlockPos(), right.getBlockState());
            copy.loadWithComponents(right.saveWithoutMetadata(registries), registries);
            check(h, copy.claimAt(0, 1) != null && copy.claimAt(0, 1).owner().equals(DeskHalf.LEFT.at(at, facing)),
                    "the right half's reservation for the part over the seam survives its save");
        });
    }
}
