package dev.bwr.mod.devtest;

import com.mojang.authlib.GameProfile;
import dev.bwr.mod.panel.DuplexCell;
import dev.bwr.mod.panel.DuplexPanelBlock;
import dev.bwr.mod.panel.PanelBlock;
import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.PanelGeometry;
import dev.bwr.mod.panel.PanelGroup;
import dev.bwr.mod.panel.PanelOps;
import dev.bwr.mod.panel.PanelType;
import dev.bwr.mod.panel.component.ComponentItem;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.registry.BwrItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
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
import java.util.List;
import java.util.UUID;

/**
 * The duplex board ({@link DuplexPanelBlock}): its three mounting frames, placing a whole column through the real item
 * for every facing, refusal when any of the six blocks is taken, breaking any block (one drop, every mounted component
 * dropped), mounting on the board, the bench and the header through the real player path, components spanning two
 * columns on every surface, which neighbours join a surface, and save/load. Fixtures live around 136,000 / 136,000.
 */
@GameTestHolder("bwr")
@PrefixGameTestTemplate(false)
public final class DuplexPanelTests {
    private DuplexPanelTests() {
    }

    private static final int ORIGIN_X = 136_000;
    private static final int ORIGIN_Z = 136_000;
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
     * Uses a duplex item on the top of the floor block under {@code bench}, the player four blocks out on the operator's
     * side looking back, so the column faces {@code facing} with its bench at {@code bench}.
     */
    private static boolean place(FakePlayer player, BlockPos bench, Direction facing) {
        player.setPos(Vec3.atBottomCenterOf(bench.relative(facing, 4)));
        player.setYRot(facing.getOpposite().toYRot());
        player.setXRot(0);
        ItemStack stack = new ItemStack(BwrItems.CONTROL_PANEL_DUPLEX.get());
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        BlockPos floor = bench.below();
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(floor).add(0, 0.5, 0), Direction.UP, floor, false);
        return stack.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, hit)).consumesAction();
    }

    private static int duplexBlocks(ServerLevel level, BlockPos floor) {
        int n = 0;
        for (BlockPos pos : BlockPos.betweenClosed(floor.offset(-6, 1, -6), floor.offset(6, 6, 6))) {
            if (level.getBlockState(pos).is(BwrBlocks.CONTROL_PANEL_DUPLEX.get())) {
                n++;
            }
        }
        return n;
    }

    private static PanelBlockEntity be(ServerLevel level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof PanelBlockEntity be ? be : null;
    }

    /**
     * Mounts a component through the real player path: the item in hand, the player in reach, the cell chosen. The
     * stack is set to the type's first size (lamp 4 x 4, gauge 12 x 12, switch 8 x 12, annunciator 12 x 8); a new stack
     * otherwise takes the size its preferred model wants.
     */
    private static PanelGroup.Result mount(FakePlayer player, BlockPos bench, Direction facing, Item item, BlockPos at,
                                           int cx, int cy) {
        player.setPos(Vec3.atBottomCenterOf(bench.relative(facing, 1)));
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

    // ---- the frames

    @GameTest(template = "empty")
    public static void duplexFramesPutTheGridOnTheOwnersSurfaces(GameTestHelper h) {
        // North-facing, the operator at -Z. The board is the back panel's frame: upright, at the front of the block.
        PanelGeometry.Frame board = PanelGeometry.frame(PanelType.DUPLEX_BOARD, Direction.NORTH);
        PanelGeometry.Frame back = PanelGeometry.frame(PanelType.BACK_PANEL, Direction.NORTH);
        check(h, near(board.ox, back.ox) && near(board.oy, back.oy) && near(board.oz, back.oz) && near(board.nz, back.nz)
                && near(board.vy, back.vy), "the duplex board has the back panel's frame");
        check(h, near(board.oz, 0) && near(board.nz, -1) && near(board.vLength, 1), "the board face is at the block front");
        // The bench: back edge 1.0 high at the board, front edge 0.75 high at the operator; the normal up and forward.
        PanelGeometry.Frame bench = PanelGeometry.frame(PanelType.DUPLEX_BENCH, Direction.NORTH);
        check(h, near(bench.y(0, 0, 0), 1.0) && near(bench.z(0, 0, 0), 1.0) && near(bench.y(0, 16, 0), 0.75)
                && near(bench.z(0, 16, 0), 0.0), "bench face from (1.0 at the board) to (0.75 at the front)");
        check(h, bench.ny > 0 && bench.nz < 0 && near(Math.toDegrees(Math.atan2(-bench.nz, bench.ny)), Math.toDegrees(Math.atan(0.25))),
                "the bench slopes about 14 degrees toward the operator: n=" + bench.ny + "," + bench.nz);
        // The header: from the board's top edge (y 0 of its block, at the board plane) up to half a block forward at its
        // top; it faces the operator and down.
        PanelGeometry.Frame header = PanelGeometry.frame(PanelType.DUPLEX_HEADER, Direction.NORTH);
        check(h, near(header.y(0, 0, 0), 1.0) && near(header.z(0, 0, 0), 0.5) && near(header.y(16, 16, 0), 0.0)
                && near(header.z(16, 16, 0), 1.0), "header face from the board's top edge to half a block out one block up");
        check(h, header.ny < 0 && header.nz < 0 && near(Math.toDegrees(Math.atan2(-header.ny, -header.nz)), Math.toDegrees(Math.atan(0.5))),
                "the header faces forward and down, 26.6 degrees from vertical: n=" + header.ny + "," + header.nz);
        check(h, !PanelType.DUPLEX_BENCH.hasRows() && !PanelType.DUPLEX_HEADER.hasRows() && PanelType.DUPLEX_BOARD.hasRows(),
                "the bench and the header are single rows, the board is not");
        // A look straight at any point of each face hits the block's outline first (the outline holds the face).
        for (PanelType type : new PanelType[] {PanelType.DUPLEX_BOARD, PanelType.DUPLEX_BENCH, PanelType.DUPLEX_HEADER}) {
            for (Direction facing : Direction.Plane.HORIZONTAL) {
                PanelGeometry.Frame f = PanelGeometry.frame(type, facing);
                var shape = PanelGeometry.shape(type, facing);
                for (double[] cell : new double[][] {{0.5, 0.5}, {8, 8}, {15.5, 15.5}, {2, 14}, {14, 2}}) {
                    Vec3 point = new Vec3(f.x(cell[0], cell[1], 0), f.y(cell[0], cell[1], 0), f.z(cell[0], cell[1], 0));
                    Vec3 eye = point.add(f.nx * 2, f.ny * 2, f.nz * 2);
                    var hit = shape.clip(eye, point.subtract(f.nx * 0.01, f.ny * 0.01, f.nz * 0.01), BlockPos.ZERO);
                    check(h, hit != null && hit.getLocation().distanceTo(eye) <= 2.0 + 1.0e-6,
                            type + "/" + facing + " cell " + cell[0] + "," + cell[1] + " is not inside the outline");
                }
            }
        }
        h.succeed();
    }

    // ---- placing

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void oneItemPlacesTheWholeColumnForEveryFacing(GameTestHelper h) {
        fixture(h, 0, (level, floor) -> {
            FakePlayer player = player(level, "DuplexPlace", GameType.SURVIVAL);
            BlockPos bench = floor.above();
            for (Direction facing : Direction.Plane.HORIZONTAL) {
                clear(level, floor);
                check(h, place(player, bench, facing), "the column was not placed facing " + facing);
                check(h, player.getMainHandItem().isEmpty(), "the item was not used up");
                check(h, duplexBlocks(level, floor) == 6, "placed " + duplexBlocks(level, floor) + " blocks, not 6, facing " + facing);
                for (DuplexCell cell : DuplexCell.values()) {
                    BlockPos at = cell.at(bench, facing);
                    BlockState state = level.getBlockState(at);
                    check(h, state.is(BwrBlocks.CONTROL_PANEL_DUPLEX.get()) && state.getValue(DuplexPanelBlock.CELL) == cell
                            && state.getValue(PanelBlock.FACING) == facing, cell + " is not at " + at + " facing " + facing + ": " + state);
                    check(h, ((PanelBlock) state.getBlock()).panelType(state) == cell.panelType(), cell + " has the wrong style");
                    check(h, (be(level, at) != null) == (cell.panelType() != null),
                            cell + (cell.panelType() != null ? " has no panel block entity" : " must not have a block entity"));
                    check(h, DuplexPanelBlock.origin(at, state).equals(bench), cell + " does not find its bench");
                }
                // the back blocks are behind the bench, away from the player; the operator's space above the bench is air
                check(h, DuplexCell.BASE.at(bench, facing).equals(bench.relative(facing.getOpposite())), "the base is behind the bench");
                check(h, level.getBlockState(bench.above()).isAir() && level.getBlockState(bench.above(2)).isAir(),
                        "the space above the bench stays open");
            }
        });
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void nothingIsPlacedWhenAnyBlockOfTheColumnIsTaken(GameTestHelper h) {
        fixture(h, 1, (level, floor) -> {
            FakePlayer player = player(level, "DuplexBlocked", GameType.SURVIVAL);
            BlockPos bench = floor.above();
            for (Direction facing : new Direction[] {Direction.NORTH, Direction.EAST}) {
                for (DuplexCell blocked : DuplexCell.values()) {
                    clear(level, floor);
                    BlockPos obstacle = blocked.at(bench, facing);
                    level.setBlock(obstacle, Blocks.STONE.defaultBlockState(), 3);
                    check(h, !place(player, bench, facing), "placed over a block at the " + blocked + " facing " + facing);
                    check(h, duplexBlocks(level, floor) == 0, "left blocks behind when refused (" + blocked + ")");
                    check(h, level.getBlockState(obstacle).is(Blocks.STONE), "replaced the obstacle at the " + blocked);
                    check(h, player.getMainHandItem().getCount() == 1, "used up the item when refused (" + blocked + ")");
                }
                // the two open blocks above the bench are not part of the column: something there does not stop it
                clear(level, floor);
                level.setBlock(bench.above(), Blocks.STONE.defaultBlockState(), 3);
                check(h, place(player, bench, facing), "a block above the bench must not stop the column");
            }
        });
    }

    // ---- breaking

    @GameTest(template = "empty", timeoutTicks = 600)
    public static void breakingAnyBlockRemovesTheColumnWithOneDropAndItsComponents(GameTestHelper h) {
        fixture(h, 2, (level, floor) -> {
            FakePlayer player = player(level, "DuplexBreak", GameType.SURVIVAL);
            BlockPos bench = floor.above();
            for (GameType mode : new GameType[] {GameType.SURVIVAL, GameType.CREATIVE}) {
                for (DuplexCell broken : DuplexCell.values()) {
                    Direction facing = broken.ordinal() % 2 == 0 ? Direction.NORTH : Direction.WEST;
                    clear(level, floor);
                    player.setGameMode(GameType.SURVIVAL);
                    check(h, place(player, bench, facing), "column not placed");
                    expect(h, mount(player, bench, facing, BwrItems.DIAL_GAUGE.get(), DuplexCell.BOARD_UPPER.at(bench, facing), 2, 2), PanelGroup.Result.OK, "gauge on the board");
                    expect(h, mount(player, bench, facing, BwrItems.CONTROL_SWITCH.get(), bench, 4, 2), PanelGroup.Result.OK,
                            "switch on the bench");
                    expect(h, mount(player, bench, facing, BwrItems.ANNUNCIATOR.get(), DuplexCell.HEADER.at(bench, facing), 2, 4), PanelGroup.Result.OK, "annunciator on the header");
                    clearItems(level, floor);
                    player.setGameMode(mode);
                    player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                    check(h, player.gameMode.destroyBlock(broken.at(bench, facing)), broken + " would not break");
                    check(h, duplexBlocks(level, floor) == 0, "breaking the " + broken + " left " + duplexBlocks(level, floor) + " blocks");
                    int items = dropped(level, floor, BwrItems.CONTROL_PANEL_DUPLEX.get());
                    check(h, items == (mode == GameType.SURVIVAL ? 1 : 0),
                            mode + ": breaking the " + broken + " dropped " + items + " duplex items");
                    check(h, droppedComponents(level, floor) == 3,
                            mode + ": breaking the " + broken + " dropped " + droppedComponents(level, floor) + " of the 3 components");
                }
            }
            // A column that loses a block another way (a command, an explosion) falls apart and gives its item back.
            clear(level, floor);
            player.setGameMode(GameType.SURVIVAL);
            check(h, place(player, bench, Direction.SOUTH), "column not placed");
            level.setBlock(DuplexCell.TOP.at(bench, Direction.SOUTH), Blocks.AIR.defaultBlockState(), 3);
            check(h, duplexBlocks(level, floor) == 0, "a broken column left " + duplexBlocks(level, floor) + " blocks");
            check(h, dropped(level, floor, BwrItems.CONTROL_PANEL_DUPLEX.get()) == 1, "a column that fell apart gives one item back");
        });
    }

    // ---- mounting

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void theBoardTheBenchAndTheHeaderEachTakeAComponent(GameTestHelper h) {
        fixture(h, 3, (level, floor) -> {
            FakePlayer player = player(level, "DuplexMount", GameType.SURVIVAL);
            BlockPos bench = floor.above();
            Direction facing = Direction.EAST;
            check(h, place(player, bench, facing), "column not placed");
            Object[][] mounts = {
                    {DuplexCell.BOARD_LOWER, BwrItems.DIAL_GAUGE.get(), PanelType.DUPLEX_BOARD},
                    {DuplexCell.BOARD_UPPER, BwrItems.LAMP.get(), PanelType.DUPLEX_BOARD},
                    {DuplexCell.BENCH, BwrItems.CONTROL_SWITCH.get(), PanelType.DUPLEX_BENCH},
                    {DuplexCell.HEADER, BwrItems.ANNUNCIATOR.get(), PanelType.DUPLEX_HEADER}};
            for (Object[] m : mounts) {
                BlockPos at = ((DuplexCell) m[0]).at(bench, facing);
                expect(h, mount(player, bench, facing, (Item) m[1], at, 2, 2), PanelGroup.Result.OK, m[0] + " refused " + m[1]);
                PanelBlockEntity panel = be(level, at);
                check(h, panel.panelType() == m[2] && panel.owned().size() == 1 && panel.claimAt(3, 3) != null,
                        m[0] + " does not hold its component");
                check(h, player.getMainHandItem().isEmpty(), m[0] + ": the component item was not used up");
            }
            for (DuplexCell body : new DuplexCell[] {DuplexCell.BASE, DuplexCell.TOP}) {
                expect(h, mount(player, bench, facing, BwrItems.LAMP.get(), body.at(bench, facing), 2, 2), PanelGroup.Result.NOT_A_PANEL,
                        "nothing mounts on the " + body);
            }
        });
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void componentsSpanTwoColumnsOnEverySurface(GameTestHelper h) {
        fixture(h, 4, (level, floor) -> {
            FakePlayer player = player(level, "DuplexSpan", GameType.SURVIVAL);
            Direction facing = Direction.NORTH;
            BlockPos left = floor.above();
            BlockPos right = PanelType.nextColumn(left, facing);
            check(h, place(player, left, facing) && place(player, right, facing), "two columns not placed");
            // the board: a 12 x 12 gauge on the corner of four blocks, two columns and both rows
            BlockPos upperLeft = DuplexCell.BOARD_UPPER.at(left, facing);
            expect(h, mount(player, left, facing, BwrItems.DIAL_GAUGE.get(), upperLeft, 10, 10), PanelGroup.Result.OK,
                    "gauge over four board blocks");
            int gauge = be(level, upperLeft).owned().get(0).id();
            check(h, be(level, DuplexCell.BOARD_UPPER.at(right, facing)).claimAt(0, 10).id() == gauge
                    && be(level, DuplexCell.BOARD_LOWER.at(left, facing)).claimAt(10, 0).id() == gauge
                    && be(level, DuplexCell.BOARD_LOWER.at(right, facing)).claimAt(0, 0).id() == gauge,
                    "the gauge covers both columns and both rows of the board");
            // the bench and the header: across the two columns, and not down past their single row
            for (Object[] m : new Object[][] {{DuplexCell.BENCH, BwrItems.CONTROL_SWITCH.get()}, {DuplexCell.HEADER, BwrItems.ANNUNCIATOR.get()}}) {
                DuplexCell cell = (DuplexCell) m[0];
                BlockPos a = cell.at(left, facing);
                BlockPos b = cell.at(right, facing);
                expect(h, mount(player, left, facing, (Item) m[1], a, 12, 2), PanelGroup.Result.OK, cell + ": span refused");
                PanelComponent spanning = be(level, a).owned().get(0);
                check(h, be(level, b).claimAt(0, 2) != null && be(level, b).claimAt(0, 2).id() == spanning.id()
                        && be(level, b).claimAt(0, 2).owner().equals(a), cell + ": the component does not reach the next column");
                expect(h, mount(player, left, facing, (Item) m[1], a, 0, 12), PanelGroup.Result.OUTSIDE_GROUP,
                        cell + " is one row: a component cannot hang off its bottom edge");
            }
        });
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void eachSurfaceJoinsTheSameSurfaceOfTheNextColumnOnly(GameTestHelper h) {
        fixture(h, 5, (level, floor) -> {
            FakePlayer player = player(level, "DuplexGroups", GameType.SURVIVAL);
            Direction facing = Direction.NORTH;
            BlockPos a = floor.above();
            BlockPos b = PanelType.nextColumn(a, facing);
            BlockPos c = PanelType.nextColumn(b, facing);
            check(h, place(player, a, facing) && place(player, b, facing) && place(player, c, facing), "three columns not placed");
            PanelGroup board = be(level, DuplexCell.BOARD_LOWER.at(b, facing)).group();
            PanelGroup bench = be(level, b).group();
            PanelGroup header = be(level, DuplexCell.HEADER.at(b, facing)).group();
            check(h, board.type() == PanelType.DUPLEX_BOARD && board.columns() == 3 && board.rows() == 2 && board.members().size() == 6,
                    "the board of three columns is one 3 x 2 group: " + board.columns() + " x " + board.rows());
            check(h, bench.type() == PanelType.DUPLEX_BENCH && bench.columns() == 3 && bench.rows() == 1, "the benches are one row of three");
            check(h, header.type() == PanelType.DUPLEX_HEADER && header.columns() == 3 && header.rows() == 1, "the headers are one row of three");
            check(h, board.columnOf(DuplexCell.BOARD_UPPER.at(a, facing)) == 0 && board.rowOf(DuplexCell.BOARD_UPPER.at(a, facing)) == 0
                    && board.columnOf(DuplexCell.BOARD_LOWER.at(c, facing)) == 2 && board.rowOf(DuplexCell.BOARD_LOWER.at(c, facing)) == 1,
                    "columns run to the operator's right, rows down the board");
            for (BlockPos pos : bench.members()) {
                check(h, !board.contains(pos) && !header.contains(pos), "a bench block is in another surface's group");
            }
            for (BlockPos pos : header.members()) {
                check(h, !board.contains(pos), "a header block is in the board's group");
            }
            check(h, !board.contains(DuplexCell.BASE.at(b, facing)) && !board.contains(DuplexCell.TOP.at(b, facing)),
                    "the body blocks are in no group");
            // A back panel beside the board has a coplanar face but is another surface; a column facing another way, and
            // a column with a gap, do not join either.
            BlockPos backPanel = PanelType.previousColumn(DuplexCell.BOARD_LOWER.at(a, facing), facing);
            level.setBlock(backPanel, BwrBlocks.CONTROL_PANEL_BACK.get().defaultBlockState().setValue(PanelBlock.FACING, facing), 3);
            check(h, !be(level, DuplexCell.BOARD_LOWER.at(a, facing)).group().contains(backPanel)
                    && be(level, backPanel).group().members().size() == 1, "a back panel joined the duplex board");
            BlockPos turned = PanelType.nextColumn(c, facing);
            check(h, place(player, turned, Direction.EAST), "the turned column was not placed");
            check(h, !be(level, b).group().contains(turned) && be(level, turned).group().members().size() == 1,
                    "a column facing another way joined the bench");
            BlockPos gapped = PanelType.previousColumn(PanelType.previousColumn(a, facing), facing);
            check(h, place(player, gapped, facing), "the column with a gap was not placed");
            check(h, !be(level, a).group().contains(gapped) && be(level, gapped).group().members().size() == 1
                    && be(level, DuplexCell.BOARD_LOWER.at(gapped, facing)).group().members().size() == 2,
                    "a column with a gap joined the next one");
        });
    }

    // ---- saving

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void aDuplexColumnSurvivesSaveAndLoad(GameTestHelper h) {
        fixture(h, 6, (level, floor) -> {
            FakePlayer player = player(level, "DuplexSave", GameType.SURVIVAL);
            BlockPos bench = floor.above();
            Direction facing = Direction.SOUTH;
            check(h, place(player, bench, facing), "column not placed");
            expect(h, mount(player, bench, facing, BwrItems.DIAL_GAUGE.get(), DuplexCell.BOARD_UPPER.at(bench, facing), 2, 10), PanelGroup.Result.OK, "a gauge over both board rows");
            expect(h, mount(player, bench, facing, BwrItems.CONTROL_SWITCH.get(), bench, 4, 2), PanelGroup.Result.OK, "bench switch");
            expect(h, mount(player, bench, facing, BwrItems.ANNUNCIATOR.get(), DuplexCell.HEADER.at(bench, facing), 2, 4), PanelGroup.Result.OK, "header annunciator");
            var registries = level.registryAccess();
            for (DuplexCell cell : DuplexCell.values()) {
                BlockPos at = cell.at(bench, facing);
                BlockState state = level.getBlockState(at);
                BlockState restored = NbtUtils.readBlockState(level.holderLookup(Registries.BLOCK), NbtUtils.writeBlockState(state));
                check(h, restored == state, cell + " state " + state + " did not survive a save");
                if (cell.panelType() == null) {
                    continue;
                }
                PanelBlockEntity panel = be(level, at);
                CompoundTag saved = panel.saveWithoutMetadata(registries);
                PanelBlockEntity reloaded = new PanelBlockEntity(at, state);
                reloaded.loadWithComponents(saved, registries);
                check(h, reloaded.savePanel(false).equals(panel.savePanel(false)), cell + ": the save is not a fixed point of load");
                check(h, reloaded.panelType() == cell.panelType(), cell + " reloaded as " + reloaded.panelType());
            }
            PanelBlockEntity upper = be(level, DuplexCell.BOARD_UPPER.at(bench, facing));
            PanelBlockEntity lower = be(level, DuplexCell.BOARD_LOWER.at(bench, facing));
            PanelBlockEntity copy = new PanelBlockEntity(lower.getBlockPos(), lower.getBlockState());
            copy.loadWithComponents(lower.saveWithoutMetadata(registries), registries);
            check(h, copy.claimAt(2, 0) != null && copy.claimAt(2, 0).owner().equals(upper.getBlockPos()),
                    "the lower board's reservation for the gauge above survives its save");
            check(h, List.of(DuplexCell.values()).size() == BwrBlocks.CONTROL_PANEL_DUPLEX.get().getStateDefinition()
                    .getPossibleStates().size() / (4 * 5), "six cells, four facings, five finishes");
        });
    }
}
