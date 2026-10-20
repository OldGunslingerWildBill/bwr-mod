package dev.bwr.mod.devtest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import dev.bwr.mod.furniture.AssemblyBlock;
import dev.bwr.mod.panel.DuplexCell;
import dev.bwr.mod.panel.PanelBlock;
import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.PanelFinish;
import dev.bwr.mod.panel.PanelFinishes;
import dev.bwr.mod.panel.PanelGeometry;
import dev.bwr.mod.panel.PanelGroup;
import dev.bwr.mod.panel.PanelOps;
import dev.bwr.mod.panel.PanelType;
import dev.bwr.mod.panel.component.Footprint;
import dev.bwr.mod.panel.trim.PanelTrimBlock;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.registry.BwrItems;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The paint finishes of the control-room blocks ({@link PanelFinish}): the blockstate property on every panel body, trim
 * and furniture block and its saved form (an old save, which has no {@code finish}, reads as seafoam); the panel
 * wrench changing the finish of a whole assembly; grouping and mounting that never look at the finish; the finish
 * surviving a drop and a placement; and the shipped models and textures (only the painted materials carry a tint, the
 * painted textures are grey). Fixtures live around 193,700 / 193,700, 32 blocks apart.
 */
@GameTestHolder("bwr")
@PrefixGameTestTemplate(false)
public final class PanelFinishTests {
    private PanelFinishTests() {
    }

    private static final int ORIGIN_X = 193_700;
    private static final int ORIGIN_Z = 193_700;
    private static final int FLOOR_Y = 100;
    private static final int EXPECTED_FINISH_BLOCKS = 32;

    // ---- fixture helpers (the entity-safe fixture of RoomFurnitureTests, with a wrench)

    private static FakePlayer player(ServerLevel level, String name) {
        FakePlayer player = FakePlayerFactory.get(level,
                new GameProfile(UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)), name));
        player.setGameMode(GameType.SURVIVAL);
        player.getInventory().clearContent();
        player.setShiftKeyDown(false);
        return player;
    }

    private interface Body {
        void run(ServerLevel level, BlockPos floor) throws Exception;
    }

    private static AABB area(BlockPos floor) {
        return new AABB(floor).inflate(8);
    }

    private static void clearItems(ServerLevel level, BlockPos floor) {
        level.getEntitiesOfClass(ItemEntity.class, area(floor)).forEach(ItemEntity::discard);
    }

    /** Runs the body on a cleared stone floor once drops can be counted; {@code slot} spreads the cases out. */
    private static void fixture(GameTestHelper h, int slot, Body body) {
        ServerLevel level = h.getLevel();
        BlockPos floor = new BlockPos(ORIGIN_X + slot * 32, FLOOR_Y, ORIGIN_Z);
        AABB bounds = new AABB(floor.getX() - 8, FLOOR_Y, floor.getZ() - 8, floor.getX() + 8, FLOOR_Y + 6, floor.getZ() + 8);
        EntityLookupProbe.runWhenLive(h, bounds, () -> {
            clearArea(level, floor);
            clearItems(level, floor);
            try {
                body.run(level, floor);
            } finally {
                clearItems(level, floor);
            }
            h.succeed();
        });
    }

    private static void clearArea(ServerLevel level, BlockPos floor) {
        for (BlockPos pos : BlockPos.betweenClosed(floor.offset(-6, 0, -6), floor.offset(6, 5, 6))) {
            level.setBlock(pos, pos.getY() == floor.getY() ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), 2);
        }
    }

    private static void check(GameTestHelper h, boolean condition, String message) {
        h.assertTrue(condition, message);
    }

    /** Every block of this mod that has a finish, by registry name. */
    private static Map<String, Block> finishBlocks() {
        Map<String, Block> blocks = new HashMap<>();
        for (Block block : BuiltInRegistries.BLOCK) {
            var key = BuiltInRegistries.BLOCK.getKey(block);
            if (key.getNamespace().equals("bwr") && block.getStateDefinition().getProperties().contains(PanelFinish.FINISH)) {
                blocks.put(key.getPath(), block);
            }
        }
        return blocks;
    }

    private static ItemStack stackWith(Block block, PanelFinish finish) {
        return PanelFinish.withFinish(new ItemStack(block), finish);
    }

    /** Uses {@code stack} on the top of the floor block, the player looking so that the piece faces {@code facing}. */
    private static boolean place(ServerLevel level, FakePlayer player, ItemStack stack, BlockPos floor, Direction facing) {
        player.setPos(Vec3.atBottomCenterOf(floor.above().relative(facing, 4)));
        player.setYRot(facing.getOpposite().toYRot());
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(floor).add(0, 0.5, 0), Direction.UP, floor, false);
        return stack.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, hit)).consumesAction();
    }

    /** The wrench in the main hand, sneaking or not, used on the block at {@code pos} through the real game mode. */
    private static void wrench(ServerLevel level, FakePlayer player, BlockPos pos, Vec3 hitAt, Direction side, boolean sneak) {
        ItemStack wrench = new ItemStack(BwrItems.PANEL_WRENCH.get());
        player.setItemInHand(InteractionHand.MAIN_HAND, wrench);
        player.setShiftKeyDown(sneak);
        player.gameMode.useItemOn(player, level, wrench, InteractionHand.MAIN_HAND, new BlockHitResult(hitAt, side, pos, false));
        player.setShiftKeyDown(false);
    }

    private static void wrench(ServerLevel level, FakePlayer player, BlockPos pos, boolean sneak) {
        player.setPos(Vec3.atBottomCenterOf(pos.above(2)));
        wrench(level, player, pos, Vec3.atCenterOf(pos).add(0, 0.5, 0), Direction.UP, sneak);
    }

    /** Aims the player at the middle of cell (cu, cv) of the panel block at {@code pos}, from in front of its face. */
    private static Vec3 aimAtCell(ServerLevel level, FakePlayer player, BlockPos pos, double cu, double cv) {
        PanelBlockEntity be = (PanelBlockEntity) level.getBlockEntity(pos);
        PanelGeometry.Frame frame = PanelGeometry.frame(be.panelType(), be.facing());
        Vec3 target = new Vec3(pos.getX() + frame.x(cu, cv, 0), pos.getY() + frame.y(cu, cv, 0), pos.getZ() + frame.z(cu, cv, 0));
        Vec3 eye = target.add(be.facing().getStepX() * 1.6, 0.4, be.facing().getStepZ() * 1.6);
        player.setPos(eye.x, eye.y - player.getEyeHeight(), eye.z);
        player.lookAt(EntityAnchorArgument.Anchor.EYES, target);
        return target;
    }

    private static PanelFinish finishAt(ServerLevel level, BlockPos pos) {
        return PanelFinish.of(level.getBlockState(pos));
    }

    // ---- the property and its saved form

    @GameTest(template = "empty")
    public static void everyPanelTrimAndFurnitureBlockHasAFinish_defaultSeafoam(GameTestHelper h) {
        Map<String, Block> blocks = finishBlocks();
        check(h, blocks.size() == EXPECTED_FINISH_BLOCKS, "blocks with a finish: " + blocks.size() + " (expected "
                + EXPECTED_FINISH_BLOCKS + "): " + new java.util.TreeSet<>(blocks.keySet()));
        for (Block block : BuiltInRegistries.BLOCK) {
            var key = BuiltInRegistries.BLOCK.getKey(block);
            boolean expected = block instanceof PanelBlock || block instanceof PanelTrimBlock || block instanceof AssemblyBlock;
            if (key.getNamespace().equals("bwr") && expected) {
                check(h, blocks.containsKey(key.getPath()), key + " is a panel, trim or furniture block with no finish");
            }
        }
        for (Map.Entry<String, Block> entry : blocks.entrySet()) {
            Block block = entry.getValue();
            check(h, block.defaultBlockState().getValue(PanelFinish.FINISH) == PanelFinish.SEAFOAM,
                    entry.getKey() + " does not default to seafoam");
        }
        check(h, PanelFinish.values()[0] == PanelFinish.SEAFOAM, "seafoam must be the first value: it is every state's default");
        check(h, PanelFinish.values().length == 5, "five finishes");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void aFinishRoundTripsThroughTheSavedBlockState(GameTestHelper h) {
        for (Map.Entry<String, Block> entry : finishBlocks().entrySet()) {
            for (BlockState state : entry.getValue().getStateDefinition().getPossibleStates()) {
                CompoundTag tag = NbtUtils.writeBlockState(state);
                BlockState back = NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), tag);
                check(h, back == state, entry.getKey() + ": " + state + " came back as " + back);
                check(h, tag.getCompound("Properties").getString("finish").equals(state.getValue(PanelFinish.FINISH).getSerializedName()),
                        entry.getKey() + " does not save its finish by name");
            }
        }
        // the panel block entity keeps its content next to a finished block state
        BlockPos pos = new BlockPos(ORIGIN_X, FLOOR_Y + 3, ORIGIN_Z - 64);
        BlockState tan = BwrBlocks.CONTROL_PANEL_VERTICAL.get().defaultBlockState().setValue(PanelFinish.FINISH, PanelFinish.TAN);
        PanelBlockEntity be = new PanelBlockEntity(pos, tan);
        CompoundTag saved = be.saveWithFullMetadata(h.getLevel().registryAccess());
        PanelBlockEntity loaded = (PanelBlockEntity) net.minecraft.world.level.block.entity.BlockEntity.loadStatic(pos, tan, saved,
                h.getLevel().registryAccess());
        check(h, loaded != null && PanelFinish.of(loaded.getBlockState()) == PanelFinish.TAN, "block entity reloads beside a tan state");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void anOldSaveWithNoFinishLoadsAsSeafoam(GameTestHelper h) {
        for (Map.Entry<String, Block> entry : finishBlocks().entrySet()) {
            for (BlockState state : entry.getValue().getStateDefinition().getPossibleStates()) {
                CompoundTag tag = NbtUtils.writeBlockState(state);
                // what a chunk saved before the property existed holds: the same state without a finish
                tag.getCompound("Properties").remove("finish");
                BlockState old = NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), tag);
                check(h, old.getValue(PanelFinish.FINISH) == PanelFinish.SEAFOAM,
                        entry.getKey() + ": an old save reads as " + old.getValue(PanelFinish.FINISH));
                check(h, old == state.setValue(PanelFinish.FINISH, PanelFinish.SEAFOAM),
                        entry.getKey() + ": an old save lost its other properties: " + old + " from " + state);
            }
            // a block that never had properties at all (the oldest saves of the single-property pieces) is the default
            CompoundTag bare = new CompoundTag();
            bare.putString("Name", "bwr:" + entry.getKey());
            BlockState bareState = NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), bare);
            check(h, bareState == entry.getValue().defaultBlockState() && PanelFinish.of(bareState) == PanelFinish.SEAFOAM,
                    entry.getKey() + ": a state with no properties is not the default");
        }
        check(h, PanelFinish.of(new ItemStack(BwrBlocks.CONTROL_PANEL_VERTICAL.get())) == PanelFinish.SEAFOAM, "an item with no state reads seafoam");
        check(h, PanelFinish.of(Blocks.STONE.defaultBlockState()) == PanelFinish.SEAFOAM, "a state without the property reads seafoam");
        h.succeed();
    }

    // ---- the wrench

    @GameTest(template = "empty", timeoutTicks = 800)
    public static void wrenchSneakClickChangesEveryBlockOfAnAssembly(GameTestHelper h) {
        fixture(h, 0, (level, floor) -> {
            FakePlayer player = player(level, "FinishWrenchAssembly");
            Map<String, Block> blocks = finishBlocks();
            List<String> pieces = List.of("relay_cabinet", "relay_cabinet_plain", "supervisor_desk", "wall_display",
                    "wall_display_wide", "control_panel_duplex", "operator_desk", "operator_desk_round", "operator_desk_corner",
                    "mimic_band_cream", "mimic_band_black", "control_stand");
            for (String name : pieces) {
                Block block = blocks.get(name);
                for (Direction facing : new Direction[] {Direction.NORTH, Direction.WEST}) {
                    clearArea(level, floor);
                    check(h, place(level, player, new ItemStack(block), floor, facing), name + " was not placed facing " + facing);
                    BlockPos origin = floor.above();
                    List<BlockPos> cells = PanelFinishes.pieceOf(level, origin, level.getBlockState(origin));
                    int expectedCells = switch (name) {
                        case "relay_cabinet", "relay_cabinet_plain", "supervisor_desk" -> 2;
                        case "wall_display" -> 4;
                        case "wall_display_wide" -> 6;
                        case "control_panel_duplex" -> DuplexCell.values().length;
                        default -> 1;
                    };
                    check(h, cells.size() == expectedCells, name + ": piece of " + cells.size() + " cells, not " + expectedCells);
                    for (BlockPos cell : cells) {
                        check(h, level.getBlockState(cell).is(block), name + " has no block at " + cell);
                    }
                    // click each cell in turn: whichever block is clicked, the whole piece steps together
                    PanelFinish expected = PanelFinish.SEAFOAM;
                    for (int click = 0; click < cells.size() * 2 + 3; click++) {
                        BlockPos clicked = cells.get(click % cells.size());
                        expected = expected.next();
                        wrench(level, player, clicked, true);
                        for (BlockPos cell : cells) {
                            check(h, finishAt(level, cell) == expected, name + " facing " + facing + " click " + click + " on " + clicked
                                    + ": the block at " + cell + " is " + finishAt(level, cell) + ", expected " + expected);
                        }
                        check(h, PanelFinishes.pieceOf(level, origin, level.getBlockState(origin)).size() == expectedCells,
                                name + " fell apart");
                    }
                    check(h, level.getEntitiesOfClass(ItemEntity.class, area(floor)).isEmpty(), name + " dropped something while being painted");
                    // without sneaking the wrench does not change a finish
                    PanelFinish before = finishAt(level, origin);
                    wrench(level, player, cells.get(0), false);
                    check(h, finishAt(level, origin) == before, name + " changed finish without sneaking");
                }
            }
            // five clicks come all the way round
            clearArea(level, floor);
            check(h, place(level, player, new ItemStack(blocks.get("operator_desk")), floor, Direction.NORTH), "desk placed");
            for (int i = 0; i < 5; i++) {
                wrench(level, player, floor.above(), true);
            }
            check(h, finishAt(level, floor.above()) == PanelFinish.SEAFOAM, "five clicks come back to seafoam");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 800)
    public static void wrenchSneakClickOnABareSpotOfAPanelOrTrimChangesOnlyThatBlock(GameTestHelper h) {
        fixture(h, 1, (level, floor) -> {
            FakePlayer player = player(level, "FinishWrenchPanel");
            Map<String, Block> blocks = finishBlocks();
            // a run of three panels of each body: the middle one is painted, its neighbours are not
            for (String name : List.of("control_panel_vertical", "control_panel_benchboard", "control_panel_console", "control_panel_back")) {
                clearArea(level, floor);
                Block block = blocks.get(name);
                BlockPos middle = floor.above().south(2);
                for (int i = -1; i <= 1; i++) {
                    level.setBlock(middle.east(i), block.defaultBlockState().setValue(PanelBlock.FACING, Direction.NORTH), 3);
                }
                Vec3 aim = aimAtCell(level, player, middle, 8.5, 8.5);
                int[] cell = new int[2];
                check(h, PanelOps.cellAt((PanelBlockEntity) level.getBlockEntity(middle), player.getEyePosition(), player.getLookAngle(), cell),
                        name + ": the aim does not land on the mounting plane");
                wrench(level, player, middle, aim, Direction.NORTH, true);
                check(h, finishAt(level, middle) == PanelFinish.CREAM, name + ": the clicked panel is " + finishAt(level, middle));
                check(h, finishAt(level, middle.east()) == PanelFinish.SEAFOAM && finishAt(level, middle.west()) == PanelFinish.SEAFOAM,
                        name + ": a neighbour changed with it");
                // a click that misses the face (the top of the body) does the same
                wrench(level, player, middle, true);
                check(h, finishAt(level, middle) == PanelFinish.TAN, name + ": a click off the face is " + finishAt(level, middle));
                // not sneaking: nothing
                wrench(level, player, middle, aim, Direction.NORTH, false);
                check(h, finishAt(level, middle) == PanelFinish.TAN, name + ": a plain click changed the finish");
            }
            // every trim block, single block
            for (Map.Entry<String, Block> entry : blocks.entrySet()) {
                if (!(entry.getValue() instanceof PanelTrimBlock)) {
                    continue;
                }
                clearArea(level, floor);
                BlockPos pos = floor.above().south(2);
                level.setBlock(pos, entry.getValue().defaultBlockState(), 3);
                level.setBlock(pos.east(), entry.getValue().defaultBlockState(), 3);
                wrench(level, player, pos, true);
                check(h, finishAt(level, pos) == PanelFinish.CREAM && finishAt(level, pos.east()) == PanelFinish.SEAFOAM,
                        entry.getKey() + ": a trim block changes alone");
            }
        });
    }

    @GameTest(template = "empty", timeoutTicks = 800)
    public static void wrenchSneakClickOnAComponentStillRemovesItAndKeepsTheFinish(GameTestHelper h) {
        fixture(h, 2, (level, floor) -> {
            FakePlayer player = player(level, "FinishWrenchRemove");
            BlockPos pos = floor.above().south(2);
            level.setBlock(pos, BwrBlocks.CONTROL_PANEL_VERTICAL.get().defaultBlockState().setValue(PanelBlock.FACING, Direction.NORTH)
                    .setValue(PanelFinish.FINISH, PanelFinish.GREY), 3);
            PanelBlockEntity be = (PanelBlockEntity) level.getBlockEntity(pos);
            aimAtCell(level, player, pos, 8.5, 8.5);
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(BwrItems.LAMP.get()));
            check(h, PanelOps.place(player, InteractionHand.MAIN_HAND, pos, 8, 8) == PanelGroup.Result.OK, "the lamp mounts");
            check(h, be.owned().size() == 1, "one component mounted");
            Vec3 aim = aimAtCell(level, player, pos, 8.5, 8.5);
            wrench(level, player, pos, aim, Direction.NORTH, true);
            check(h, be.owned().isEmpty(), "sneak-click on a component removes it, as before");
            check(h, finishAt(level, pos) == PanelFinish.GREY, "removing a component does not change the finish");
            check(h, level.getEntitiesOfClass(ItemEntity.class, area(floor)).stream().anyMatch(e -> e.getItem().is(BwrItems.LAMP.get())),
                    "the removed lamp drops");
            // the same click on the now bare spot steps the finish
            wrench(level, player, pos, aim, Direction.NORTH, true);
            check(h, finishAt(level, pos) == PanelFinish.LIGHT_BLUE, "the next click on the bare spot paints it");
            // a mounted component survives a repaint
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(BwrItems.LAMP.get()));
            check(h, PanelOps.place(player, InteractionHand.MAIN_HAND, pos, 4, 4) == PanelGroup.Result.OK, "the lamp mounts again");
            wrench(level, player, pos, aim, Direction.NORTH, true); // a bare spot: repaint
            check(h, finishAt(level, pos) == PanelFinish.SEAFOAM && ((PanelBlockEntity) level.getBlockEntity(pos)).owned().size() == 1,
                    "a repaint keeps the mounted component");
        });
    }

    // ---- grouping and mounting

    @GameTest(template = "empty", timeoutTicks = 800)
    public static void groupingAndMountingDoNotLookAtTheFinish(GameTestHelper h) {
        fixture(h, 3, (level, floor) -> {
            FakePlayer player = player(level, "FinishGrouping");
            Block vertical = BwrBlocks.CONTROL_PANEL_VERTICAL.get();
            BlockPos start = floor.above().south(2);
            List<PanelFinish> mixed = List.of(PanelFinish.SEAFOAM, PanelFinish.CREAM, PanelFinish.TAN, PanelFinish.GREY,
                    PanelFinish.LIGHT_BLUE);
            for (int i = 0; i < mixed.size(); i++) {
                level.setBlock(start.east(i), vertical.defaultBlockState().setValue(PanelBlock.FACING, Direction.NORTH)
                        .setValue(PanelFinish.FINISH, mixed.get(i)), 3);
            }
            PanelGroup group = PanelGroup.compute(level, start, PanelType.VERTICAL, Direction.NORTH, null);
            check(h, group.members().size() == 5 && group.columns() == 5 && group.rows() == 1,
                    "a run of five panels in five finishes is one group of " + group.members().size());
            PanelGroup.Placement across = PanelGroup.checkPlacement(level, start.east(2), 10, 4, Footprint.of(30, 4));
            check(h, across.ok() && across.rects().size() == 3, "a component spans the boundaries between finishes: " + across);
            // the same run in one finish gives the same answers
            for (int i = 0; i < mixed.size(); i++) {
                level.setBlock(start.east(i), level.getBlockState(start.east(i)).setValue(PanelFinish.FINISH, PanelFinish.SEAFOAM), 3);
            }
            PanelGroup uniform = PanelGroup.compute(level, start, PanelType.VERTICAL, Direction.NORTH, null);
            check(h, uniform.members().equals(group.members()), "the group differs between a uniform and a mixed run");
            check(h, PanelGroup.checkPlacement(level, start.east(2), 10, 4, Footprint.of(30, 4)).toString().equals(across.toString()),
                    "the placement differs between a uniform and a mixed run");
            // a component survives its block being repainted
            level.setBlock(start.east(1), level.getBlockState(start.east(1)).setValue(PanelFinish.FINISH, PanelFinish.TAN), 3);
            player.setPos(Vec3.atBottomCenterOf(start.east(1).south(2)));
            ItemStack lamp = new ItemStack(BwrItems.LAMP.get());
            player.setItemInHand(InteractionHand.MAIN_HAND, lamp);
            check(h, PanelOps.place(player, InteractionHand.MAIN_HAND, start.east(1), 4, 4) == PanelGroup.Result.OK, "mount a lamp");
            PanelBlockEntity be = (PanelBlockEntity) level.getBlockEntity(start.east(1));
            for (PanelFinish finish : PanelFinish.values()) {
                level.setBlock(start.east(1), level.getBlockState(start.east(1)).setValue(PanelFinish.FINISH, finish), 3);
                check(h, be == level.getBlockEntity(start.east(1)) && be.owned().size() == 1, "the block entity and its component survive " + finish);
            }
            check(h, PanelGroup.compute(level, start, PanelType.VERTICAL, Direction.NORTH, null).members().size() == 5, "still one group");

            // two duplex columns side by side in different finishes: one board group
            clearArea(level, floor);
            Block duplex = BwrBlocks.CONTROL_PANEL_DUPLEX.get();
            check(h, place(level, player, stackWith(duplex, PanelFinish.CREAM), floor, Direction.NORTH), "first column");
            BlockPos second = floor.east();
            check(h, place(level, player, stackWith(duplex, PanelFinish.LIGHT_BLUE), second, Direction.NORTH), "second column");
            BlockPos firstBoard = DuplexCell.BOARD_LOWER.at(floor.above(), Direction.NORTH);
            PanelGroup board = PanelGroup.compute(level, firstBoard, PanelType.DUPLEX_BOARD, Direction.NORTH, null);
            check(h, board.members().size() == 4, "two duplex columns of two finishes form one board of " + board.members().size());
            check(h, finishAt(level, firstBoard) == PanelFinish.CREAM && finishAt(level, firstBoard.east()) == PanelFinish.LIGHT_BLUE,
                    "each column keeps its own finish");
            PanelGroup bench = PanelGroup.compute(level, floor.above(), PanelType.DUPLEX_BENCH, Direction.NORTH, null);
            check(h, bench.members().size() == 2, "the benches join across finishes: " + bench.members().size());
        });
    }

    // ---- drops and placing

    @GameTest(template = "empty", timeoutTicks = 1600)
    public static void aDroppedBlockKeepsItsFinishOnTheItem_andPlacesBackWithIt(GameTestHelper h) {
        fixture(h, 4, (level, floor) -> {
            FakePlayer player = player(level, "FinishDrops");
            for (Map.Entry<String, Block> entry : finishBlocks().entrySet()) {
                Block block = entry.getValue();
                for (PanelFinish finish : PanelFinish.values()) {
                    clearArea(level, floor);
                    clearItems(level, floor);
                    player.setGameMode(GameType.SURVIVAL);
                    ItemStack held = stackWith(block, finish);
                    check(h, place(level, player, held, floor, Direction.NORTH), entry.getKey() + " " + finish + " was not placed");
                    BlockPos origin = floor.above();
                    List<BlockPos> piece = PanelFinishes.pieceOf(level, origin, level.getBlockState(origin));
                    for (BlockPos cell : piece) {
                        check(h, finishAt(level, cell) == finish, entry.getKey() + ": the item of finish " + finish + " placed a "
                                + finishAt(level, cell) + " block at " + cell);
                    }
                    // break a block that is not the origin where the piece has one: the drop is the origin's loot all the same
                    BlockPos broken = piece.get(piece.size() - 1);
                    player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                    check(h, player.gameMode.destroyBlock(broken), entry.getKey() + " " + finish + " would not break");
                    List<ItemEntity> drops = level.getEntitiesOfClass(ItemEntity.class, area(floor)).stream()
                            .filter(e -> e.getItem().is(block.asItem())).toList();
                    check(h, drops.size() == 1 && drops.get(0).getItem().getCount() == 1,
                            entry.getKey() + " " + finish + " dropped " + drops.size() + " stacks");
                    ItemStack dropped = drops.get(0).getItem();
                    check(h, PanelFinish.of(dropped) == finish, entry.getKey() + ": the drop is " + PanelFinish.of(dropped) + ", not " + finish);
                    if (finish == PanelFinish.SEAFOAM) {
                        check(h, ItemStack.isSameItemSameComponents(dropped, new ItemStack(block)),
                                entry.getKey() + ": a seafoam drop must stack with a crafted one");
                    } else {
                        check(h, !ItemStack.isSameItemSameComponents(dropped, new ItemStack(block)),
                                entry.getKey() + ": a " + finish + " drop is a seafoam item");
                    }
                    // and the dropped item places the same finish again, on every block of the piece
                    clearArea(level, floor);
                    check(h, place(level, player, dropped.copy(), floor, Direction.EAST), entry.getKey() + " could not be placed again");
                    for (BlockPos cell : PanelFinishes.pieceOf(level, origin, level.getBlockState(origin))) {
                        check(h, finishAt(level, cell) == finish, entry.getKey() + ": placed again as " + finishAt(level, cell) + ", not " + finish);
                    }
                    // pick block
                    ItemStack picked = level.getBlockState(origin).getBlock().getCloneItemStack(level.getBlockState(origin),
                            new BlockHitResult(Vec3.atCenterOf(origin), Direction.UP, origin, false), level, origin, player);
                    check(h, PanelFinish.of(picked) == finish, entry.getKey() + ": pick block gives " + PanelFinish.of(picked));
                }
            }
        });
    }

    @GameTest(template = "empty", timeoutTicks = 800)
    public static void aPanelDropsItsMountedComponentsAndKeepsItsFinish(GameTestHelper h) {
        fixture(h, 5, (level, floor) -> {
            FakePlayer player = player(level, "FinishPanelDrops");
            BlockPos pos = floor.above().south(2);
            level.setBlock(pos, BwrBlocks.CONTROL_PANEL_CONSOLE.get().defaultBlockState().setValue(PanelBlock.FACING, Direction.NORTH)
                    .setValue(PanelFinish.FINISH, PanelFinish.TAN), 3);
            player.setPos(Vec3.atBottomCenterOf(pos.south(2)));
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(BwrItems.LAMP.get()));
            check(h, PanelOps.place(player, InteractionHand.MAIN_HAND, pos, 8, 8) == PanelGroup.Result.OK, "lamp mounts");
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            check(h, player.gameMode.destroyBlock(pos), "the console breaks");
            List<ItemStack> stacks = level.getEntitiesOfClass(ItemEntity.class, area(floor)).stream().map(ItemEntity::getItem).toList();
            check(h, stacks.stream().anyMatch(s -> s.is(BwrItems.LAMP.get())), "the lamp drops");
            check(h, stacks.stream().anyMatch(s -> s.is(BwrBlocks.CONTROL_PANEL_CONSOLE.get().asItem()) && PanelFinish.of(s) == PanelFinish.TAN),
                    "the console drops as a tan console");
        });
    }

    // ---- the models and textures

    @GameTest(template = "empty")
    public static void finishTintsAreTheOldColoursForSeafoamAndTheGreyTextureTintsToTheColour(GameTestHelper h) {
        check(h, PanelFinish.SEAFOAM.paint() == 0xA7BCAE && PanelFinish.SEAFOAM.bezel() == 0x7D9284
                && PanelFinish.SEAFOAM.top() == 0xBCCDC1 && PanelFinish.SEAFOAM.frame() == 0x3A3F42,
                "seafoam keeps the colours the blocks always had");
        for (PanelFinish finish : PanelFinish.values()) {
            for (int[] pair : new int[][] {{PanelFinish.TINT_PANEL_PAINT, finish.paint()}, {PanelFinish.TINT_PANEL_BEZEL, finish.bezel()}}) {
                int tint = finish.tint(pair[0]);
                for (int shift = 16; shift >= 0; shift -= 8) {
                    int got = Math.round(PanelFinish.PANEL_NEUTRAL * ((tint >> shift) & 0xFF) / 255f);
                    int want = (pair[1] >> shift) & 0xFF;
                    check(h, Math.abs(got - want) <= 1, finish + " tint " + pair[0] + " channel " + shift + ": " + got + " vs " + want);
                }
            }
            check(h, finish.tint(PanelFinish.TINT_FURNITURE_PAINT) == finish.paint() && finish.tint(PanelFinish.TINT_FURNITURE_BEZEL) == finish.bezel()
                    && finish.tint(PanelFinish.TINT_FURNITURE_TOP) == finish.top() && finish.tint(PanelFinish.TINT_FURNITURE_FRAME) == finish.frame(),
                    finish + " furniture tints");
            check(h, finish.tint(2) == -1 && finish.tint(3) == -1 && finish.tint(-1) == -1 && finish.tint(8) == -1, "unused tint indices carry nothing");
            check(h, finish.next().next().next().next().next() == finish, finish + " does not come round in five steps");
        }
        // five different looks
        Set<Integer> paints = new HashSet<>();
        for (PanelFinish finish : PanelFinish.values()) {
            paints.add(finish.paint());
        }
        check(h, paints.size() == 5, "the finishes must differ");
        h.succeed();
    }

    private static Map<String, Integer> tintsOf(String resource) throws IOException {
        Map<String, Integer> tints = new HashMap<>();
        try (InputStream in = PanelFinishTests.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException(resource + " is missing");
            }
            String current = null;
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
                line = line.trim();
                if (line.startsWith("newmtl ")) {
                    current = line.substring(7).trim();
                    tints.put(current, -1);
                } else if (line.startsWith("neoforge_TintIndex ") && current != null) {
                    tints.put(current, Integer.parseInt(line.substring(19).trim()));
                }
            }
        }
        return tints;
    }

    @GameTest(template = "empty")
    public static void onlyThePaintedMaterialsCarryATint(GameTestHelper h) throws IOException {
        Map<String, Integer> panelTints = Map.of("face", 0, "paint", 0, "cheek", 0, "cabinet", 0, "bezel", 1, "back", 1);
        for (String folder : List.of("control_panel", "control_panel_trim")) {
            Map<String, Integer> mtl = tintsOf("/assets/bwr/models/block/" + folder + "/materials.mtl");
            for (Map.Entry<String, Integer> material : mtl.entrySet()) {
                int want = panelTints.getOrDefault(material.getKey(), -1);
                check(h, material.getValue() == want, folder + " material " + material.getKey() + " has tint index " + material.getValue()
                        + ", expected " + want + (want < 0 ? " (dark trim, kick, hood, screens and louvres keep their colour)" : ""));
            }
            for (String painted : panelTints.keySet()) {
                check(h, mtl.containsKey(painted), folder + " lost its " + painted + " material");
            }
        }
        Map<String, Integer> furnitureTints = Map.of("paint", 4, "bezel", 5, "top", 6, "frame", 7);
        Map<String, Integer> furniture = tintsOf("/assets/bwr/models/block/furniture/materials.mtl");
        for (Map.Entry<String, Integer> material : furniture.entrySet()) {
            int want = furnitureTints.getOrDefault(material.getKey(), -1);
            check(h, material.getValue() == want, "furniture material " + material.getKey() + " has tint index " + material.getValue()
                    + ", expected " + want + (want < 0 ? " (trim, dark, handles, casings, screens, louvres keep their colour)" : ""));
        }
        for (String painted : furnitureTints.keySet()) {
            check(h, furniture.containsKey(painted), "furniture lost its " + painted + " material");
        }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void thePaintedTexturesAreGreyAndTheHeadersHaveATextureForEveryFinish(GameTestHelper h) throws IOException {
        for (String name : List.of("face", "paint", "bezel", "back", "cheek", "cabinet")) {
            try (InputStream in = PanelFinishTests.class.getResourceAsStream("/assets/bwr/textures/block/control_panel/" + name + ".png")) {
                check(h, in != null, name + ".png is missing");
                BufferedImage image = ImageIO.read(in);
                for (int y = 0; y < image.getHeight(); y++) {
                    for (int x = 0; x < image.getWidth(); x++) {
                        int rgb = image.getRGB(x, y);
                        int r = (rgb >> 16) & 0xFF;
                        int g = (rgb >> 8) & 0xFF;
                        int b = rgb & 0xFF;
                        check(h, r == g && g == b, name + ".png is not grey at " + x + "," + y);
                    }
                }
            }
        }
        try (InputStream in = PanelFinishTests.class.getResourceAsStream("/assets/bwr/textures/block/control_panel/paint.png")) {
            int grey = ImageIO.read(in).getRGB(8, 8) & 0xFF;
            check(h, grey == PanelFinish.PANEL_NEUTRAL, "the paint texture is " + grey + ", the neutral level is " + PanelFinish.PANEL_NEUTRAL);
        }
        for (String header : List.of("header_strip", "header_canopy", "header_vent")) {
            String blockstate = "/assets/bwr/blockstates/control_panel_" + header + ".json";
            try (InputStream in = PanelFinishTests.class.getResourceAsStream(blockstate)) {
                check(h, in != null, blockstate + " is missing");
                JsonObject variants = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8))
                        .getAsJsonObject().getAsJsonObject("variants");
                for (PanelFinish finish : PanelFinish.values()) {
                    for (Direction facing : Direction.Plane.HORIZONTAL) {
                        String key = "facing=" + facing.getSerializedName() + ",finish=" + finish.getSerializedName();
                        check(h, variants.has(key), blockstate + " has no variant " + key);
                        String model = variants.getAsJsonObject(key).get("model").getAsString();
                        try (InputStream m = PanelFinishTests.class.getResourceAsStream("/assets/bwr/models/"
                                + model.substring("bwr:".length()) + ".json")) {
                            check(h, m != null, model + " is missing");
                            String text = new String(m.readAllBytes(), StandardCharsets.UTF_8);
                            String texture = header.equals("header_vent") ? "vent" : "annunciator";
                            String want = finish == PanelFinish.SEAFOAM ? "control_panel/" + texture + "\"" : "control_panel/" + texture + "_"
                                    + finish.getSerializedName() + "\"";
                            check(h, text.contains(want), model + " does not use the " + finish + " " + texture + " texture");
                        }
                    }
                }
            }
            for (PanelFinish finish : PanelFinish.values()) {
                if (finish != PanelFinish.SEAFOAM) {
                    String texture = header.equals("header_vent") ? "vent" : "annunciator";
                    try (InputStream t = PanelFinishTests.class.getResourceAsStream("/assets/bwr/textures/block/control_panel/" + texture + "_"
                            + finish.getSerializedName() + ".png")) {
                        check(h, t != null, texture + "_" + finish.getSerializedName() + ".png is missing");
                    }
                }
            }
        }
        h.succeed();
    }
}
