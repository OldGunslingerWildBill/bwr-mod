package dev.bwr.mod.devtest;

import com.mojang.authlib.GameProfile;
import dev.bwr.mod.containment.ContainmentAirlockBlock;
import dev.bwr.mod.containment.ContainmentAirlockBlockEntity;
import dev.bwr.mod.furniture.DeskBase;
import dev.bwr.mod.fuel.BwrDataComponents;
import dev.bwr.mod.power.LubeOilValveBlock;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.registry.BwrItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The owner's rule: with the Panel Wrench or the Networking Wrench in a hand, a click on a plant block never operates it (a valve
 * does not turn, a door does not move, a menu does not open, a desk's monitor does not flip), and with an empty hand it does.
 * Every click goes through the real game mode ({@code ServerPlayerGameMode.useItemOn}, which fires the interaction event) the
 * way the client sends it: the main hand first, the off hand when the main hand's click is not consumed. The tool is tried in
 * the main hand and in the off hand (the main hand then empty, the case that used to reach the block). The tool's own action
 * still runs: the Networking Wrench selects the device it is used on.
 */
@GameTestHolder("bwrtools")
@PrefixGameTestTemplate(false)
public final class ToolBlockInteractionTests {
    private ToolBlockInteractionTests() {
    }

    private static List<Item> tools() {
        return List.of(BwrItems.PANEL_WRENCH.get(), BwrItems.NETWORKING_WRENCH.get());
    }

    /** A fake player cannot open a screen, so it counts the menus a block tried to open instead. */
    private static final class Player extends FakePlayer {
        int menus;

        Player(ServerLevel level, String name) {
            super(level, new GameProfile(UUID.nameUUIDFromBytes(("toolblocks-" + name).getBytes(StandardCharsets.UTF_8)), "tb-" + name));
            setGameMode(GameType.SURVIVAL);
        }

        @Override
        public OptionalInt openMenu(MenuProvider provider, Consumer<RegistryFriendlyByteBuf> extraData) {
            menus++;
            return OptionalInt.empty();
        }

        @Override
        public OptionalInt openMenu(MenuProvider provider) {
            menus++;
            return OptionalInt.empty();
        }
    }

    private static Player player(ServerLevel level, String name) {
        return new Player(level, name);
    }

    /** The click as the client sends it: main hand, then the off hand if the main hand did not consume it. */
    private static void click(ServerLevel level, Player player, BlockPos pos) {
        player.setPos(Vec3.atBottomCenterOf(pos.south(2)));
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.NORTH, pos, false);
        for (InteractionHand hand : InteractionHand.values()) {
            if (player.gameMode.useItemOn(player, level, player.getItemInHand(hand), hand, hit).consumesAction()) {
                return;
            }
        }
    }

    private static void hold(Player player, ItemStack main, ItemStack off) {
        player.setItemInHand(InteractionHand.MAIN_HAND, main);
        player.setItemInHand(InteractionHand.OFF_HAND, off);
    }

    /**
     * Both tools in both hands leave {@code observe} unchanged; an empty hand changes it (so the probe really sees the block's
     * action). {@code reset} puts the block back to its starting state before every click.
     */
    private static void noToolOperates(GameTestHelper h, ServerLevel level, Player player, String what, BlockPos pos,
                                       Supplier<Object> observe, Runnable reset) {
        for (Item tool : tools()) {
            for (boolean offHand : new boolean[] {false, true}) {
                reset.run();
                hold(player, offHand ? ItemStack.EMPTY : new ItemStack(tool), offHand ? new ItemStack(tool) : ItemStack.EMPTY);
                Object before = observe.get();
                click(level, player, pos);
                h.assertTrue(before.equals(observe.get()), what + ": the " + tool + " in the " + (offHand ? "off" : "main")
                        + " hand operated it (" + before + " -> " + observe.get() + ")");
            }
        }
        reset.run();
        hold(player, ItemStack.EMPTY, ItemStack.EMPTY);
        Object before = observe.get();
        click(level, player, pos);
        h.assertTrue(!before.equals(observe.get()), what + ": an empty hand did not operate it (the probe is blind): " + observe.get());
        reset.run();
    }

    private static Object menu(Player player) {
        return player.menus;
    }

    private static void clearAround(ServerLevel level, BlockPos pos) {
        level.getChunk(pos);
        for (BlockPos p : BlockPos.betweenClosed(pos.offset(-3, -1, -3), pos.offset(8, 3, 3))) {
            level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
        }
    }

    private static void lubeValve(ServerLevel level, BlockPos pos) {
        level.setBlock(pos, BwrBlocks.LUBE_OIL_VALVE.get().defaultBlockState()
                .setValue(LubeOilValveBlock.FACING, Direction.EAST).setValue(LubeOilValveBlock.OPEN, true), 3);
    }

    @GameTest(template = "empty", templateNamespace = "bwrtools", timeoutTicks = 100)
    public static void aToolDoesNotTurnTheLubeOilValve(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Player player = player(level, "lube");
        BlockPos pos = new BlockPos(26610, 220, 26600);
        clearAround(level, pos);
        noToolOperates(h, level, player, "lube oil valve", pos, () -> level.getBlockState(pos).getValue(LubeOilValveBlock.OPEN),
                () -> lubeValve(level, pos));
        h.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "bwrtools", timeoutTicks = 100)
    public static void aToolDoesNotMoveAnAirlockLeaf(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Player player = player(level, "airlock");
        BlockPos root = new BlockPos(26630, 220, 26600);
        clearAround(level, root);
        ContainmentAirlockBlock block = BwrBlocks.CONTAINMENT_AIRLOCK.get();
        Runnable reset = () -> {
            for (int i = 0; i < 6; i++) {
                level.setBlock(block.cell(root, Direction.EAST, i), Blocks.AIR.defaultBlockState(), 2);
            }
            var state = block.defaultBlockState().setValue(ContainmentAirlockBlock.FACING, Direction.EAST);
            level.setBlock(root, state, 3);
            block.setPlacedBy(level, root, state, null, ItemStack.EMPTY);
        };
        noToolOperates(h, level, player, "airlock", root,
                () -> ((ContainmentAirlockBlockEntity) level.getBlockEntity(root)).innerOpen(), reset);
        h.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "bwrtools", timeoutTicks = 100)
    public static void aToolDoesNotFlipADesksMonitor(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Player player = player(level, "desk");
        BlockPos pos = new BlockPos(26650, 220, 26600);
        clearAround(level, pos);
        noToolOperates(h, level, player, "operator desk", pos, () -> level.getBlockState(pos).getValue(DeskBase.MONITOR),
                () -> level.setBlock(pos, BwrBlocks.OPERATOR_DESK.get().defaultBlockState(), 3));
        h.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "bwrtools", timeoutTicks = 100)
    public static void aToolDoesNotOpenAPumpOrASteamValveMenu(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Player player = player(level, "menus");
        BlockPos pump = new BlockPos(26670, 220, 26600);
        BlockPos valve = new BlockPos(26680, 220, 26600);
        clearAround(level, pump);
        clearAround(level, valve);
        noToolOperates(h, level, player, "RCIC pump", pump, () -> menu(player),
                () -> level.setBlock(pump, BwrBlocks.RCIC_TURBINE_PUMP.get().defaultBlockState(), 3));
        noToolOperates(h, level, player, "steam stop valve", valve, () -> menu(player),
                () -> level.setBlock(valve, BwrBlocks.STEAM_STOP_VALVE.get().defaultBlockState(), 3));
        h.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "bwrtools", timeoutTicks = 100)
    public static void aToolDoesNotOpenTheFabricationDesk(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Player player = player(level, "fab");
        BlockPos pos = new BlockPos(26700, 220, 26600);
        clearAround(level, pos);
        noToolOperates(h, level, player, "fabrication desk", pos, () -> menu(player),
                () -> level.setBlock(pos, BwrBlocks.FABRICATION_DESK.get().defaultBlockState(), 3));
        h.succeed();
    }

    /** The tool's own action is not lost: the Networking Wrench still selects the valve it is used on, in either hand. */
    @GameTest(template = "empty", templateNamespace = "bwrtools", timeoutTicks = 100)
    public static void theNetworkingWrenchStillSelectsTheDeviceItIsUsedOn(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Player player = player(level, "select");
        BlockPos pos = new BlockPos(26720, 220, 26600);
        clearAround(level, pos);
        for (boolean offHand : new boolean[] {false, true}) {
            lubeValve(level, pos);
            ItemStack wrench = new ItemStack(BwrItems.NETWORKING_WRENCH.get());
            hold(player, offHand ? ItemStack.EMPTY : wrench, offHand ? wrench : ItemStack.EMPTY);
            click(level, player, pos);
            var selected = wrench.get(BwrDataComponents.LINKED_DEVICE.get());
            h.assertTrue(selected != null && selected.pos().equals(pos), "the wrench in the " + (offHand ? "off" : "main")
                    + " hand did not select the valve: " + selected);
            h.assertTrue(level.getBlockState(pos).getValue(LubeOilValveBlock.OPEN), "selecting turned the valve");
        }
        h.succeed();
    }
}
