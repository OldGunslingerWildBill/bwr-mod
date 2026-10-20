package dev.bwr.mod.devtest;

import com.mojang.authlib.GameProfile;
import dev.bwr.mod.furniture.AssemblyBlock;
import dev.bwr.mod.furniture.CornerDeskBlock;
import dev.bwr.mod.furniture.DeskBase;
import dev.bwr.mod.furniture.DeskBlock;
import dev.bwr.mod.furniture.DeskMonitor;
import dev.bwr.mod.furniture.DeskPart;
import dev.bwr.mod.furniture.FurnitureHalf;
import dev.bwr.mod.furniture.MimicBandBlock;
import dev.bwr.mod.furniture.RelayCabinetBlock;
import dev.bwr.mod.furniture.RoundDeskBlock;
import dev.bwr.mod.furniture.SupervisorDeskBlock;
import dev.bwr.mod.furniture.WallDisplayBlock;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.registry.BwrItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The control-room furniture (ROOM-FURNITURE.md): registration, placing and breaking the multi-block pieces through the
 * real item and the real player game mode (both blocks placed, all blocks removed, exactly one drop, nothing placed when
 * the space is blocked), facing, the desk monitors, shapes and saved states. Fixtures live around 110,000 / 110,000.
 */
@GameTestHolder("bwr")
@PrefixGameTestTemplate(false)
public final class RoomFurnitureTests {
    private RoomFurnitureTests() {
    }

    private static final int ORIGIN_X = 110_000;
    private static final int ORIGIN_Z = 110_000;
    private static final int FLOOR_Y = 100;

    /** Every furniture block by registry name, with the supplier of its block. */
    private record Piece(String name, Supplier<? extends Block> block, int cells) {
    }

    private static final List<Piece> PIECES = List.of(
            new Piece("relay_cabinet", BwrBlocks.RELAY_CABINET, 2),
            new Piece("relay_cabinet_plain", BwrBlocks.RELAY_CABINET_PLAIN, 2),
            new Piece("operator_desk", BwrBlocks.OPERATOR_DESK, 1),
            new Piece("operator_desk_round", BwrBlocks.OPERATOR_DESK_ROUND, 1),
            new Piece("operator_desk_corner", BwrBlocks.OPERATOR_DESK_CORNER, 1),
            new Piece("supervisor_desk", BwrBlocks.SUPERVISOR_DESK, 2),
            new Piece("mimic_band_cream", BwrBlocks.MIMIC_BAND_CREAM, 1),
            new Piece("mimic_band_black", BwrBlocks.MIMIC_BAND_BLACK, 1),
            new Piece("wall_display", BwrBlocks.WALL_DISPLAY, 4),
            new Piece("wall_display_wide", BwrBlocks.WALL_DISPLAY_WIDE, 6),
            new Piece("control_stand", BwrBlocks.CONTROL_STAND, 1));

    // ---- fixture helpers

    private static FakePlayer player(ServerLevel level, String name, GameType mode) {
        FakePlayer player = FakePlayerFactory.get(level,
                new GameProfile(UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)), name));
        player.setGameMode(mode);
        player.getInventory().clearContent();
        player.setShiftKeyDown(false);
        return player;
    }

    /** A fixture body: runs once the fixture's chunks answer entity lookups (see {@link EntityLookupProbe}). */
    private interface Body {
        void run(ServerLevel level, BlockPos floor) throws Exception;
    }

    /**
     * Runs the body on a cleared 13 x 13 floor of stone, with the clicked block (the floor's centre) passed in. The chunks
     * are forced and released by the probe, and the body only starts when drops can be counted, so counts of 0 or 1 are
     * real. {@code slot} spreads the cases out.
     */
    private static void fixture(GameTestHelper h, int slot, Body body) {
        ServerLevel level = h.getLevel();
        BlockPos floor = new BlockPos(ORIGIN_X + slot * 32, FLOOR_Y, ORIGIN_Z);
        AABB bounds = new AABB(floor.getX() - 8, FLOOR_Y, floor.getZ() - 8, floor.getX() + 8, FLOOR_Y + 6, floor.getZ() + 8);
        EntityLookupProbe.runWhenLive(h, bounds, () -> {
            for (BlockPos pos : BlockPos.betweenClosed(floor.offset(-6, 0, -6), floor.offset(6, 5, 6))) {
                level.setBlock(pos, pos.getY() == floor.getY() ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), 2);
            }
            clearItems(level, floor);
            try {
                body.run(level, floor);
            } finally {
                clearItems(level, floor);
            }
            h.succeed();
        });
    }

    private static AABB area(BlockPos floor) {
        return new AABB(floor).inflate(8);
    }

    private static void clearItems(ServerLevel level, BlockPos floor) {
        level.getEntitiesOfClass(ItemEntity.class, area(floor)).forEach(ItemEntity::discard);
    }

    private static int dropped(ServerLevel level, BlockPos floor, Block block) {
        return level.getEntitiesOfClass(ItemEntity.class, area(floor)).stream()
                .filter(e -> e.getItem().is(block.asItem())).mapToInt(e -> e.getItem().getCount()).sum();
    }

    /** Uses the block's item on top of the floor block, the player looking so that the piece faces {@code facing}. */
    private static boolean place(ServerLevel level, FakePlayer player, Block block, BlockPos floor, Direction facing) {
        player.setPos(Vec3.atBottomCenterOf(floor.above().relative(facing, 4)));
        player.setYRot(facing.getOpposite().toYRot());
        ItemStack stack = new ItemStack(block);
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(floor).add(0, 0.5, 0), Direction.UP, floor, false);
        return stack.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, hit)).consumesAction();
    }

    private static boolean isAir(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).isAir();
    }

    private static void check(GameTestHelper h, boolean condition, String message) {
        h.assertTrue(condition, message);
    }

    // ---- registration

    @GameTest(template = "empty")
    public static void furnitureIsRegisteredWithItemRecipeLootLangAndCreativeTab(GameTestHelper h) throws IOException {
        ServerLevel level = h.getLevel();
        String lang;
        try (InputStream in = RoomFurnitureTests.class.getResourceAsStream("/assets/bwr/lang/en_us.json")) {
            check(h, in != null, "en_us.json is missing");
            lang = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        CreativeModeTabs.tryRebuildTabContents(level.enabledFeatures(), true, level.registryAccess());
        var tabItems = BwrItems.TAB.get().getDisplayItems();
        for (Piece piece : PIECES) {
            ResourceLocation id = ResourceLocation.fromNamespaceAndPath("bwr", piece.name());
            check(h, BuiltInRegistries.BLOCK.containsKey(id), piece.name() + " block is not registered");
            Block block = BuiltInRegistries.BLOCK.get(id);
            check(h, block == piece.block().get(), piece.name() + " registry entry differs from BwrBlocks");
            check(h, block instanceof AssemblyBlock, piece.name() + " is not furniture");
            check(h, ((AssemblyBlock) block).cellCount() == piece.cells(), piece.name() + " cell count");
            check(h, BuiltInRegistries.ITEM.containsKey(id) && BuiltInRegistries.ITEM.get(id) == block.asItem(),
                    piece.name() + " item is not registered");
            check(h, !((AssemblyBlock) block).defaultBlockState().hasBlockEntity(), piece.name() + " must not have a block entity");
            check(h, level.getRecipeManager().byKey(id).isPresent(), piece.name() + " has no recipe");
            var result = level.getRecipeManager().byKey(id).get().value().getResultItem(level.registryAccess());
            check(h, result.is(block.asItem()) && result.getCount() >= 1, piece.name() + " recipe yields the wrong item");
            ResourceKey<LootTable> table = ResourceKey.create(Registries.LOOT_TABLE,
                    ResourceLocation.fromNamespaceAndPath("bwr", "blocks/" + piece.name()));
            check(h, level.getServer().reloadableRegistries().getLootTable(table) != LootTable.EMPTY,
                    piece.name() + " has no loot table");
            check(h, lang.contains("\"block.bwr." + piece.name() + "\""), piece.name() + " has no lang name");
            check(h, tabItems.stream().anyMatch(s -> s.is(block.asItem())), piece.name() + " is not in the creative tab");
        }
        h.succeed();
    }

    // ---- placing and breaking

    @GameTest(template = "empty", timeoutTicks = 600)
    public static void everyPieceIsPlacedWhole_facingAndFootprintFollowThePlayer(GameTestHelper h) {
        fixture(h, 0, (level, floor) -> {
            FakePlayer player = player(level, "FurniturePlace", GameType.SURVIVAL);
            for (Piece piece : PIECES) {
                Block block = piece.block().get();
                for (Direction facing : Direction.Plane.HORIZONTAL) {
                    for (BlockPos pos : BlockPos.betweenClosed(floor.offset(-6, 1, -6), floor.offset(6, 5, 6))) {
                        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
                    }
                    check(h, place(level, player, block, floor, facing), piece.name() + " was not placed facing " + facing);
                    check(h, player.getMainHandItem().isEmpty(), piece.name() + " item was not consumed");
                    BlockPos origin = floor.above();
                    int found = 0;
                    for (BlockPos pos : BlockPos.betweenClosed(floor.offset(-6, 1, -6), floor.offset(6, 5, 6))) {
                        BlockState state = level.getBlockState(pos);
                        if (!state.isAir()) {
                            found++;
                            check(h, state.is(block), piece.name() + " placed something else at " + pos);
                            check(h, state.getValue(AssemblyBlock.FACING) == facing,
                                    piece.name() + " is not facing " + facing + " at " + pos);
                        }
                    }
                    check(h, found == piece.cells(), piece.name() + " placed " + found + " blocks, not " + piece.cells());
                    expectFootprint(h, level, piece, block, origin, facing);
                }
            }
        });
    }

    private static void expectFootprint(GameTestHelper h, ServerLevel level, Piece piece, Block block, BlockPos origin,
                                        Direction facing) {
        Direction left = facing.getClockWise();   // the operator's left, in front of the piece
        switch (piece.name()) {
            case "relay_cabinet", "relay_cabinet_plain" -> {
                check(h, level.getBlockState(origin).getValue(RelayCabinetBlock.HALF) == FurnitureHalf.LOWER, "lower half");
                check(h, level.getBlockState(origin.above()).is(block)
                        && level.getBlockState(origin.above()).getValue(RelayCabinetBlock.HALF) == FurnitureHalf.UPPER, "upper half");
            }
            case "supervisor_desk" -> {
                check(h, level.getBlockState(origin).getValue(SupervisorDeskBlock.PART) == DeskPart.DRAWERS, "drawers block");
                BlockState knee = level.getBlockState(origin.relative(left));
                check(h, knee.is(block) && knee.getValue(SupervisorDeskBlock.PART) == DeskPart.KNEE,
                        "knee block must be one block to the operator's left (" + left + ") of the drawers");
            }
            case "wall_display", "wall_display_wide" -> {
                int width = ((WallDisplayBlock) block).width();
                for (int row = 0; row < 2; row++) {
                    for (int col = 0; col < width; col++) {
                        BlockState state = level.getBlockState(origin.relative(left, col).above(row));
                        check(h, state.is(block) && state.getValue(WallDisplayBlock.COL) == col
                                && state.getValue(WallDisplayBlock.ROW) == row, piece.name() + " cell " + col + "," + row);
                    }
                }
            }
            default -> check(h, level.getBlockState(origin).is(block), piece.name() + " origin block");
        }
    }

    @GameTest(template = "empty", timeoutTicks = 600)
    public static void breakingAnyBlockRemovesTheWholePieceWithExactlyOneDrop(GameTestHelper h) {
        fixture(h, 1, (level, floor) -> {
            FakePlayer player = player(level, "FurnitureBreak", GameType.SURVIVAL);
            for (Piece piece : PIECES) {
                Block block = piece.block().get();
                for (int broken = 0; broken < piece.cells(); broken++) {
                    for (Direction facing : new Direction[] {Direction.NORTH, Direction.EAST}) {
                        clearItems(level, floor);
                        player.setGameMode(GameType.SURVIVAL);
                        check(h, place(level, player, block, floor, facing), piece.name() + " not placed");
                        List<BlockPos> cells = cellsOf(level, floor, block);
                        check(h, cells.size() == piece.cells(), piece.name() + " cells before breaking: " + cells.size());
                        BlockPos target = cells.get(broken);
                        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                        check(h, player.gameMode.destroyBlock(target), piece.name() + " cell " + broken + " would not break");
                        check(h, cellsOf(level, floor, block).isEmpty(), piece.name() + " left blocks behind after breaking cell " + broken);
                        check(h, dropped(level, floor, block) == 1,
                                piece.name() + " dropped " + dropped(level, floor, block) + " items breaking cell " + broken + " facing " + facing);
                    }
                }
                // creative mode drops nothing, and still removes everything
                clearItems(level, floor);
                player.setGameMode(GameType.CREATIVE);
                check(h, place(level, player, block, floor, Direction.SOUTH), piece.name() + " not placed in creative");
                List<BlockPos> cells = cellsOf(level, floor, block);
                check(h, player.gameMode.destroyBlock(cells.get(cells.size() - 1)), piece.name() + " would not break in creative");
                check(h, cellsOf(level, floor, block).isEmpty(), piece.name() + " left blocks behind in creative");
                check(h, dropped(level, floor, block) == 0, piece.name() + " dropped items in creative");
            }
        });
    }

    /** Every block of the given kind in the fixture, lowest and nearest the origin cell first. */
    private static List<BlockPos> cellsOf(ServerLevel level, BlockPos floor, Block block) {
        List<BlockPos> cells = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(floor.offset(-6, 1, -6), floor.offset(6, 5, 6))) {
            if (level.getBlockState(pos).is(block)) {
                cells.add(pos.immutable());
            }
        }
        AssemblyBlock assembly = (AssemblyBlock) block;
        cells.sort((a, b) -> Integer.compare(cellIndex(level, assembly, a), cellIndex(level, assembly, b)));
        return cells;
    }

    private static int cellIndex(ServerLevel level, AssemblyBlock block, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        BlockPos origin = block.origin(pos, state);
        Direction facing = state.getValue(AssemblyBlock.FACING);
        for (int i = 0; i < block.cellCount(); i++) {
            if (block.cellPos(origin, facing, i).equals(pos)) {
                return i;
            }
        }
        return -1;
    }

    @GameTest(template = "empty", timeoutTicks = 600)
    public static void nothingIsPlacedWhenAnyBlockOfThePieceIsBlocked(GameTestHelper h) {
        fixture(h, 2, (level, floor) -> {
            FakePlayer player = player(level, "FurnitureBlocked", GameType.SURVIVAL);
            for (Piece piece : PIECES) {
                if (piece.cells() == 1) {
                    continue;
                }
                Block block = piece.block().get();
                for (Direction facing : Direction.Plane.HORIZONTAL) {
                    // place it once to learn the footprint, then block each non-origin block in turn
                    check(h, place(level, player, block, floor, facing), piece.name() + " not placed");
                    List<BlockPos> cells = cellsOf(level, floor, block);
                    player.gameMode.destroyBlock(cells.get(0));
                    for (int blocked = 1; blocked < cells.size(); blocked++) {
                        for (BlockPos pos : BlockPos.betweenClosed(floor.offset(-6, 1, -6), floor.offset(6, 5, 6))) {
                            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
                        }
                        level.setBlock(cells.get(blocked), Blocks.STONE.defaultBlockState(), 3);
                        clearItems(level, floor);
                        ItemStack stack = new ItemStack(block);
                        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
                        boolean placed = place(level, player, block, floor, facing);
                        check(h, !placed, piece.name() + " was placed over a block at cell " + blocked + " facing " + facing);
                        check(h, cellsOf(level, floor, block).isEmpty(), piece.name() + " left blocks when it was refused");
                        check(h, level.getBlockState(cells.get(blocked)).is(Blocks.STONE), piece.name() + " replaced the obstacle");
                        check(h, player.getMainHandItem().getCount() == 1, piece.name() + " item was used up when refused");
                    }
                    for (BlockPos pos : BlockPos.betweenClosed(floor.offset(-6, 1, -6), floor.offset(6, 5, 6))) {
                        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
                    }
                }
            }
            // a replaceable block (grass) in the way is fine
            Block cabinet = BwrBlocks.RELAY_CABINET.get();
            level.setBlock(floor.above(2), Blocks.SHORT_GRASS.defaultBlockState(), 3);
            check(h, place(level, player, cabinet, floor, Direction.NORTH), "a cabinet should replace tall grass above it");
            check(h, level.getBlockState(floor.above(2)).is(cabinet), "the upper half did not replace the grass");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 600)
    public static void aTallPieceIsRefusedAtTheTopOfTheWorld(GameTestHelper h) {
        fixture(h, 3, (level, base) -> {
            FakePlayer player = player(level, "FurnitureCeiling", GameType.SURVIVAL);
            BlockPos floor = base.atY(level.getMaxBuildHeight() - 2);
            level.setBlock(floor, Blocks.STONE.defaultBlockState(), 2);
            check(h, !place(level, player, BwrBlocks.RELAY_CABINET.get(), floor, Direction.NORTH),
                    "a cabinet was placed with its upper half above the build limit");
            check(h, isAir(level, floor.above()), "the lower half was left behind");
            level.setBlock(floor, Blocks.AIR.defaultBlockState(), 2);
        });
    }

    @GameTest(template = "empty", timeoutTicks = 600)
    public static void aPieceBrokenByAnythingButAPlayerStillGoesWholeAndDropsOnce(GameTestHelper h) {
        fixture(h, 4, (level, floor) -> {
            FakePlayer player = player(level, "FurnitureOther", GameType.SURVIVAL);
            for (Piece piece : PIECES) {
                if (piece.cells() == 1) {
                    continue;
                }
                Block block = piece.block().get();
                for (int removed = 0; removed < piece.cells(); removed++) {
                    clearItems(level, floor);
                    check(h, place(level, player, block, floor, Direction.WEST), piece.name() + " not placed");
                    List<BlockPos> cells = cellsOf(level, floor, block);
                    // a command, a piston or an explosion removes one block; the others must follow
                    level.setBlock(cells.get(removed), Blocks.AIR.defaultBlockState(), 3);
                    check(h, cellsOf(level, floor, block).isEmpty(), piece.name() + " kept blocks after cell " + removed + " vanished");
                    int items = dropped(level, floor, block);
                    // removing the origin block by command is not a break and drops nothing; losing any other block
                    // makes the origin block go, and the origin block's loot is the one item
                    int expected = removed == 0 ? 0 : 1;
                    check(h, items == expected, piece.name() + " dropped " + items + " items, expected " + expected
                            + ", when block " + removed + " vanished");
                }
            }
        });
    }

    // ---- desks

    @GameTest(template = "empty", timeoutTicks = 600)
    public static void aBareHandCyclesTheMonitorAndASneakFlipsARoundDesk(GameTestHelper h) {
        fixture(h, 5, (level, floor) -> {
            FakePlayer player = player(level, "FurnitureMonitor", GameType.SURVIVAL);
            for (Block block : List.<Block>of(BwrBlocks.OPERATOR_DESK.get(), BwrBlocks.OPERATOR_DESK_ROUND.get(),
                    BwrBlocks.OPERATOR_DESK_CORNER.get(), BwrBlocks.SUPERVISOR_DESK.get())) {
                level.setBlock(floor.above(), Blocks.AIR.defaultBlockState(), 2);
                check(h, place(level, player, block, floor, Direction.NORTH), block + " not placed");
                BlockPos pos = floor.above();
                BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
                player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                DeskMonitor[] order = {DeskMonitor.FLAT, DeskMonitor.CRT, DeskMonitor.NONE, DeskMonitor.FLAT};
                check(h, level.getBlockState(pos).getValue(DeskBase.MONITOR) == DeskMonitor.NONE, block + " starts with a monitor");
                for (DeskMonitor expected : order) {
                    var result = level.getBlockState(pos).useWithoutItem(level, player, hit);
                    check(h, result.consumesAction(), block + ": a bare hand did nothing");
                    check(h, level.getBlockState(pos).getValue(DeskBase.MONITOR) == expected,
                            block + ": monitor should be " + expected + " but is " + level.getBlockState(pos).getValue(DeskBase.MONITOR));
                }
                // a held item is never intercepted: blocks can still be placed against a desk
                player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Blocks.STONE));
                DeskMonitor before = level.getBlockState(pos).getValue(DeskBase.MONITOR);
                check(h, !level.getBlockState(pos).useWithoutItem(level, player, hit).consumesAction(), block + " intercepted a held item");
                check(h, level.getBlockState(pos).getValue(DeskBase.MONITOR) == before, block + " changed under a held item");
                // sneaking with a bare hand
                player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                player.setShiftKeyDown(true);
                BlockState sneaked = level.getBlockState(pos);
                var result = sneaked.useWithoutItem(level, player, hit);
                if (block instanceof RoundDeskBlock) {
                    check(h, result.consumesAction() && level.getBlockState(pos).getValue(RoundDeskBlock.MIRRORED), "round desk did not mirror");
                    sneaked = level.getBlockState(pos);
                    sneaked.useWithoutItem(level, player, hit);
                    check(h, !level.getBlockState(pos).getValue(RoundDeskBlock.MIRRORED), "round desk did not flip back");
                } else {
                    check(h, !result.consumesAction() && level.getBlockState(pos) == sneaked, block + " reacted to a sneak");
                }
                player.setShiftKeyDown(false);
                player.gameMode.destroyBlock(pos);
            }
        });
    }

    @GameTest(template = "empty", timeoutTicks = 600)
    public static void aSupervisorDeskKeepsAMonitorPerHalfAndOnlyTheOriginDrops(GameTestHelper h) {
        fixture(h, 6, (level, floor) -> {
            FakePlayer player = player(level, "FurnitureSupervisor", GameType.SURVIVAL);
            Block block = BwrBlocks.SUPERVISOR_DESK.get();
            check(h, place(level, player, block, floor, Direction.SOUTH), "supervisor desk not placed");
            BlockPos drawers = floor.above();
            BlockPos knee = drawers.relative(Direction.SOUTH.getClockWise());
            check(h, Direction.SOUTH.getClockWise() == Direction.WEST && knee.equals(drawers.west()), "knee side for a south facing desk");
            level.setBlock(knee, level.getBlockState(knee).setValue(DeskBase.MONITOR, DeskMonitor.CRT), 3);
            check(h, level.getBlockState(drawers).getValue(DeskBase.MONITOR) == DeskMonitor.NONE, "monitors are per half");
            player.gameMode.destroyBlock(knee);
            check(h, isAir(level, drawers) && isAir(level, knee), "supervisor desk left a half");
            check(h, dropped(level, floor, block) == 1, "supervisor desk must drop one item");
        });
    }

    // ---- shapes

    private static AABB bounds(VoxelShape shape) {
        return shape.bounds();
    }

    private static boolean close(double a, double b) {
        return Math.abs(a - b) < 1.0e-6;
    }

    @GameTest(template = "empty")
    public static void shapesFollowTheModelsAndTurnWithTheFacing(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        // relay cabinet: front doors proud of the carcass, back flush; facing north has its back at z = 1
        AABB[] cabinet = new AABB[4];
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            BlockState state = BwrBlocks.RELAY_CABINET.get().defaultBlockState().setValue(AssemblyBlock.FACING, facing);
            cabinet[facing.get2DDataValue()] = bounds(state.getShape(level, BlockPos.ZERO));
            check(h, state.getShape(level, BlockPos.ZERO) == state.getCollisionShape(level, BlockPos.ZERO), "collision follows the shape");
        }
        AABB north = cabinet[Direction.NORTH.get2DDataValue()];
        check(h, close(north.minZ, 0.2) && close(north.maxZ, 1.0) && close(north.minX, 0) && close(north.maxX, 1) && close(north.maxY, 1.0),
                "north cabinet bounds " + north);
        check(h, close(cabinet[Direction.SOUTH.get2DDataValue()].minZ, 0) && close(cabinet[Direction.SOUTH.get2DDataValue()].maxZ, 0.8), "south cabinet bounds");
        check(h, close(cabinet[Direction.EAST.get2DDataValue()].minX, 0) && close(cabinet[Direction.EAST.get2DDataValue()].maxX, 0.8), "east cabinet bounds");
        check(h, close(cabinet[Direction.WEST.get2DDataValue()].minX, 0.2) && close(cabinet[Direction.WEST.get2DDataValue()].maxX, 1.0), "west cabinet bounds");

        // desks are 0.75 m high with a free knee and kick space under the top (at least 100 mm in every direction)
        for (Block block : List.<Block>of(BwrBlocks.OPERATOR_DESK.get(), BwrBlocks.OPERATOR_DESK_ROUND.get(),
                BwrBlocks.OPERATOR_DESK_CORNER.get(), BwrBlocks.SUPERVISOR_DESK.get())) {
            for (Direction facing : Direction.Plane.HORIZONTAL) {
                BlockState bare = block.defaultBlockState().setValue(AssemblyBlock.FACING, facing);
                AABB top = bounds(bare.getShape(level, BlockPos.ZERO));
                double expectedTop = block == BwrBlocks.SUPERVISOR_DESK.get() ? 0.7625 : 0.75;
                check(h, Math.abs(top.maxY - expectedTop) < 1.0e-3, block + " desk top height " + top.maxY);
                check(h, top.minY == 0.0, block + " desk must reach the floor");
                for (DeskMonitor monitor : DeskMonitor.values()) {
                    BlockState state = bare.setValue(DeskBase.MONITOR, monitor);
                    AABB b = bounds(state.getShape(level, BlockPos.ZERO));
                    check(h, monitor == DeskMonitor.NONE ? close(b.maxY, top.maxY) : b.maxY > 1.0 && b.maxY < 1.3,
                            block + " " + monitor + " desk height " + b.maxY);
                }
            }
        }
        // the knee space of the straight desk facing north: x 0.1..0.9, y 0..0.55, z 0.15..0.70 holds no shape
        VoxelShape deskNorth = BwrBlocks.OPERATOR_DESK.get().defaultBlockState().getShape(level, BlockPos.ZERO);
        check(h, !Shapes.joinIsNotEmpty(deskNorth, Shapes.box(0.1, 0.0, 0.15, 0.9, 0.55, 0.70), BooleanOp.AND),
                "the straight desk has no free knee and kick space");
        check(h, Shapes.joinIsNotEmpty(deskNorth, Shapes.box(0.1, 0.70, 0.15, 0.9, 0.74, 0.70), BooleanOp.AND), "the desk top is missing");
        // the L corner desk: arms along the back (+z) and the east side (+x) when facing north; turned east, arms along +x... rotated
        AABB cornerNorth = bounds(BwrBlocks.OPERATOR_DESK_CORNER.get().defaultBlockState().getShape(level, BlockPos.ZERO));
        check(h, close(cornerNorth.minX, 0) && close(cornerNorth.maxX, 14.7 / 16) && close(cornerNorth.minZ, 1.3 / 16) && close(cornerNorth.maxZ, 14.7 / 16),
                "corner desk bounds " + cornerNorth);
        AABB cornerEast = bounds(BwrBlocks.OPERATOR_DESK_CORNER.get().defaultBlockState()
                .setValue(AssemblyBlock.FACING, Direction.EAST).getShape(level, BlockPos.ZERO));
        check(h, close(cornerEast.minX, 1.3 / 16) && close(cornerEast.maxX, 14.7 / 16) && close(cornerEast.minZ, 0) && close(cornerEast.maxZ, 14.7 / 16),
                "corner desk turned east " + cornerEast);
        // the round end desk is mirrored by its property: the top reaches x = 1 on one side only
        BlockState round = BwrBlocks.OPERATOR_DESK_ROUND.get().defaultBlockState();
        AABB roundBounds = bounds(round.getShape(level, BlockPos.ZERO));
        AABB mirroredBounds = bounds(round.setValue(RoundDeskBlock.MIRRORED, true).getShape(level, BlockPos.ZERO));
        check(h, close(roundBounds.maxX, 1.0) && close(mirroredBounds.minX, 0.0), "round desk and its mirror");
        VoxelShape plain = round.getShape(level, BlockPos.ZERO);
        VoxelShape flipped = round.setValue(RoundDeskBlock.MIRRORED, true).getShape(level, BlockPos.ZERO);
        // the east corner of the top is cut away by the bullnose, but is there on the mirrored desk's straight end
        check(h, !Shapes.joinIsNotEmpty(plain, Shapes.box(0.95, 0.70, 0.12, 1.0, 0.75, 0.20), BooleanOp.AND)
                && Shapes.joinIsNotEmpty(flipped, Shapes.box(0.95, 0.70, 0.12, 1.0, 0.75, 0.20), BooleanOp.AND), "bullnose side");
        // mimic bands and displays are thin boards against the back of the block
        for (Block board : List.<Block>of(BwrBlocks.MIMIC_BAND_CREAM.get(), BwrBlocks.MIMIC_BAND_BLACK.get(),
                BwrBlocks.WALL_DISPLAY.get(), BwrBlocks.WALL_DISPLAY_WIDE.get())) {
            AABB b = bounds(board.defaultBlockState().getShape(level, BlockPos.ZERO));
            check(h, b.getZsize() < 0.2 && close(b.maxZ, 1.0) && close(b.getXsize(), 1) && close(b.getYsize(), 1), board + " board shape " + b);
        }
        h.succeed();
    }

    // ---- saves

    @GameTest(template = "empty")
    public static void everyFurnitureStateSurvivesSavingAndReloading(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        int states = 0;
        for (Piece piece : PIECES) {
            Block block = piece.block().get();
            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                BlockState restored = NbtUtils.readBlockState(level.holderLookup(Registries.BLOCK), NbtUtils.writeBlockState(state));
                check(h, restored == state, piece.name() + " state " + state + " did not survive a save");
                states++;
            }
        }
        check(h, states == 5 * (8 + 8 + 12 + 24 + 12 + 24 + 4 + 4 + 24 + 24 + 4), "unexpected state count " + states);
        // the panel bodies are untouched
        check(h, BwrBlocks.CONTROL_PANEL_VERTICAL.get().getStateDefinition().getPossibleStates().size() == 4, "panel states changed");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void furnitureClassesCarryNoBlockEntitiesAndAreNotPanels(GameTestHelper h) {
        for (Piece piece : PIECES) {
            Block block = piece.block().get();
            check(h, !(block instanceof net.minecraft.world.level.block.EntityBlock), piece.name() + " must have no block entity");
            check(h, block instanceof DeskBase || block instanceof RelayCabinetBlock || block instanceof MimicBandBlock
                    || block instanceof WallDisplayBlock || block instanceof dev.bwr.mod.furniture.ControlStandBlock,
                    piece.name() + " unexpected class");
        }
        check(h, BwrBlocks.OPERATOR_DESK.get() instanceof DeskBlock && BwrBlocks.OPERATOR_DESK_CORNER.get() instanceof CornerDeskBlock,
                "desk classes");
        h.succeed();
    }
}
