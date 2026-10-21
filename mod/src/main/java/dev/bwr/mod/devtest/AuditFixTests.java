package dev.bwr.mod.devtest;

import com.mojang.authlib.GameProfile;
import dev.bwr.mod.BwrMod;
import dev.bwr.mod.panel.PanelBlock;
import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.PanelBus;
import dev.bwr.mod.panel.PanelGroup;
import dev.bwr.mod.panel.PanelOps;
import dev.bwr.mod.panel.PanelPlayerCleanup;
import dev.bwr.mod.panel.PanelRateLimit;
import dev.bwr.mod.panel.PanelType;
import dev.bwr.mod.panel.component.ComponentTypes;
import dev.bwr.mod.panel.component.ControlSwitchData;
import dev.bwr.mod.panel.component.Footprint;
import dev.bwr.mod.panel.component.JoystickData;
import dev.bwr.mod.panel.component.OperateAction;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.mimic.MimicDraft;
import dev.bwr.mod.panel.mimic.MimicOps;
import dev.bwr.mod.panel.mimic.MimicTapeItem;
import dev.bwr.mod.panel.net.PanelPayloads;
import dev.bwr.mod.panel.networking.NetworkShare;
import dev.bwr.mod.panel.networking.NetworkingOps;
import dev.bwr.mod.panel.networking.NetworkingPayloads;
import dev.bwr.mod.panel.wiring.PanelNetworkIndex;
import dev.bwr.mod.panel.wiring.WiringOps;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.registry.BwrItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The fixes of the 2026-10-21 audit (AUDIT-2026-10-21.md): game modes on the panel, mimic, wiring and networking
 * packets, the per-player rate limits and cleanup, the Spring re-centre reaching the sinks, the group size cap of a
 * piece of two blocks, the mimic draft codec, the client panel index, and (with CC:Tweaked) the CC port machine cache
 * after an empty answer. Fixtures at 230,000 / 230,000 and east, 232,000 and 234,000.
 */
@GameTestHolder("bwr")
@PrefixGameTestTemplate(false)
public final class AuditFixTests {
    private AuditFixTests() {
    }

    private static final int ORIGIN_X = 230_000;
    private static final int ORIGIN_Z = 230_000;

    private static String outputs(PanelComponent sw) {
        return (int) sw.output(0) + "" + (int) sw.output(1) + (int) sw.output(2) + (int) sw.output(3) + "/" + (int) sw.output(4);
    }

    private static FakePlayer fake(ServerLevel level, String name) {
        FakePlayer player = FakePlayerFactory.get(level, new GameProfile(
                UUID.nameUUIDFromBytes(("audit-fix-" + name).getBytes(StandardCharsets.UTF_8)), "audit-" + name));
        player.setGameMode(GameType.SURVIVAL);
        return player;
    }

    // ---- finding 1: game modes

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void gameModesGateThePanelPackets(GameTestHelper helper) {
        HardwareStage.run(helper, ORIGIN_X, ORIGIN_Z, "audit-modes", s -> {
            PanelComponent sw = s.place(ComponentTypes.JOYSTICK_SWITCH, 0, 0, Footprint.of(8, 8));
            BlockPos meter = s.origin.below(2);
            s.level.setBlock(meter, BwrBlocks.FE_METER.get().defaultBlockState(), 3);
            helper.assertTrue(s.level.getBlockEntity(meter) != null, "fixture: the device exists");
            UUID who = s.player.getUUID();
            int components = s.be().ownedCount();

            for (GameType mode : new GameType[] {GameType.SPECTATOR, GameType.ADVENTURE}) {
                s.player.setGameMode(mode);
                String m = mode.getName();
                helper.assertTrue(dev.bwr.mod.world.AssemblyAccess.mayOperate(s.player) == (mode == GameType.ADVENTURE)
                                && !dev.bwr.mod.world.AssemblyAccess.mayEdit(s.player),
                        m + ": operate is allowed in adventure only, editing in neither");
                // Operating: adventure yes (maps), spectator nothing.
                boolean operated = PanelOps.operate(s.player, s.origin, sw.id(), OperateAction.SET, 2.0);
                helper.assertTrue(operated == (mode == GameType.ADVENTURE), m + ": operate " + operated);
                helper.assertTrue(outputs(sw).equals(operated ? "0100/2" : "0000/0"), m + ": the lever " + outputs(sw));
                PanelOps.operate(s.player, s.origin, sw.id(), OperateAction.SET, 0.0);
                helper.assertTrue(outputs(sw).equals("0000/0"), m + ": back at the centre");

                // Editing: neither mode.
                ItemStack lamp = new ItemStack(BwrItems.LAMP.get());
                lamp.set(dev.bwr.mod.fuel.BwrDataComponents.COMPONENT_VARIANT.get(), 0);
                s.player.setItemInHand(InteractionHand.MAIN_HAND, lamp);
                helper.assertTrue(PanelOps.place(s.player, InteractionHand.MAIN_HAND, s.origin, 12, 12) != PanelGroup.Result.OK
                        && s.be().ownedCount() == components && lamp.getCount() == 1, m + ": no placing");
                s.wrench();
                CompoundTag edit = new CompoundTag();
                edit.putString("Caption", "HACK");
                helper.assertTrue(!PanelOps.configure(s.player, s.origin, sw.id(), edit) && sw.caption().isEmpty(),
                        m + ": no configuring");
                helper.assertTrue(!PanelOps.remove(s.player, s.origin, sw.id()) && s.be().component(sw.id()) != null,
                        m + ": no removing");
                s.player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(BwrItems.PANEL_LINKER.get()));
                var bind = new PanelPayloads.Bind(s.origin, sw.id(), false, 0, WiringOps.BindKind.NONE.ordinal(),
                        BlockPos.ZERO, "", BlockPos.ZERO, 0, 0, 1.0);
                helper.assertTrue(WiringOps.bind(s.player, bind) == WiringOps.Result.NOT_PERMITTED, m + ": no wiring");
                helper.assertTrue(WiringOps.screenFor(s.player, s.origin, sw.id()) == null, m + ": no binding screen");
                s.player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(BwrItems.MIMIC_TAPE.get()));
                helper.assertTrue(MimicOps.use(s.player, InteractionHand.MAIN_HAND, s.origin, 8.0, 4.0) == MimicOps.Result.NOT_PERMITTED
                                && MimicTapeItem.draft(s.player.getMainHandItem()) == null,
                        m + ": no mimic tape");
                s.player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(BwrItems.NETWORKING_WRENCH.get()));
                helper.assertTrue(NetworkingOps.checkedDevice(s.player, meter) == null, m + ": no networking edits");
                NetworkShare.forget(who);
                int shared = NetworkShare.handle(s.player, NetworkingPayloads.KIND_DEVICE, meter, 0);
                helper.assertTrue((shared >= 0) == (mode == GameType.ADVENTURE), m + ": lines are shared by adventure only, got " + shared);
            }

            // Survival still does all of it.
            s.player.setGameMode(GameType.SURVIVAL);
            helper.assertTrue(dev.bwr.mod.world.AssemblyAccess.mayOperate(s.player) && dev.bwr.mod.world.AssemblyAccess.mayEdit(s.player),
                    "survival may operate and edit");
            helper.assertTrue(PanelOps.operate(s.player, s.origin, sw.id(), OperateAction.SET, 2.0) && outputs(sw).equals("0100/2"),
                    "survival operates");
            PanelOps.operate(s.player, s.origin, sw.id(), OperateAction.SET, 0.0);
            ItemStack lamp = new ItemStack(BwrItems.LAMP.get());
            lamp.set(dev.bwr.mod.fuel.BwrDataComponents.COMPONENT_VARIANT.get(), 0);
            s.player.setItemInHand(InteractionHand.MAIN_HAND, lamp);
            helper.assertTrue(PanelOps.place(s.player, InteractionHand.MAIN_HAND, s.origin, 12, 12) == PanelGroup.Result.OK
                    && s.be().ownedCount() == components + 1, "survival places");
            s.wrench();
            CompoundTag edit = new CompoundTag();
            edit.putString("Caption", "OK");
            helper.assertTrue(PanelOps.configure(s.player, s.origin, sw.id(), edit) && sw.caption().equals("OK"), "survival configures");
            s.player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(BwrItems.PANEL_LINKER.get()));
            var bind = new PanelPayloads.Bind(s.origin, sw.id(), false, 0, WiringOps.BindKind.NONE.ordinal(),
                    BlockPos.ZERO, "", BlockPos.ZERO, 0, 0, 1.0);
            helper.assertTrue(WiringOps.bind(s.player, bind) != WiringOps.Result.NOT_PERMITTED, "survival is not refused by game mode when wiring");
            s.player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(BwrItems.MIMIC_TAPE.get()));
            helper.assertTrue(MimicOps.use(s.player, InteractionHand.MAIN_HAND, s.origin, 8.0, 4.0) != MimicOps.Result.NOT_PERMITTED,
                    "survival is not refused by game mode with the tape");
            s.player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(BwrItems.NETWORKING_WRENCH.get()));
            helper.assertTrue(NetworkingOps.checkedDevice(s.player, meter) != null, "survival edits a device");
            NetworkShare.forget(who);
            helper.assertTrue(NetworkShare.handle(s.player, NetworkingPayloads.KIND_DEVICE, meter, 0) >= 0, "survival shares lines");
            NetworkShare.forget(who);
            s.wrench();
            helper.assertTrue(PanelOps.remove(s.player, s.origin, sw.id()) && s.be().component(sw.id()) == null, "survival removes");
        });
    }

    // ---- finding 5 and the per-player maps (finding 11)

    @GameTest(template = "empty")
    public static void rateLimitsAreGenerousForAHandAndStopAFlood(GameTestHelper helper) {
        UUID player = UUID.nameUUIDFromBytes("audit-rate".getBytes(StandardCharsets.UTF_8));
        PanelRateLimit.forget(player);
        long tick = 1_000;
        // A flood in one tick: the burst goes through, the rest is dropped.
        int through = 0;
        for (int i = 0; i < 500; i++) {
            if (PanelRateLimit.tryAcquire(player, PanelRateLimit.Kind.CONFIGURE, tick)) {
                through++;
            }
        }
        helper.assertTrue(through == 10, "a configure flood is cut at the burst of 10, got " + through);
        helper.assertTrue(!PanelRateLimit.tryAcquire(player, PanelRateLimit.Kind.CONFIGURE, tick), "and stays cut within the tick");
        helper.assertTrue(PanelRateLimit.tryAcquire(player, PanelRateLimit.Kind.OPERATE, tick),
                "kinds have their own buckets: operating is unaffected by a configure flood");
        // A steady hand: 4 configures a second for a minute, never refused.
        for (int i = 1; i <= 240; i++) {
            helper.assertTrue(PanelRateLimit.tryAcquire(player, PanelRateLimit.Kind.CONFIGURE, tick + 5L * i + 50),
                    "a configure every 5 ticks is never refused (" + i + ")");
        }
        // Fast legitimate pressing: a press and a release every other tick for a minute, plus a held control's repeat each tick.
        long start = 5_000;
        for (int i = 0; i < 1200; i++) {
            helper.assertTrue(PanelRateLimit.tryAcquire(player, PanelRateLimit.Kind.OPERATE, start + i), "one operate a tick is never refused");
            if (i % 2 == 0) {
                helper.assertTrue(PanelRateLimit.tryAcquire(player, PanelRateLimit.Kind.OPERATE, start + i), "nor two");
            }
        }
        // After a pause the burst is back.
        int again = 0;
        for (int i = 0; i < 50; i++) {
            if (PanelRateLimit.tryAcquire(player, PanelRateLimit.Kind.NAME_LINKED, 20_000)) {
                again++;
            }
        }
        helper.assertTrue(again == 6, "name linked allows a burst of 6, got " + again);
        helper.assertTrue(PanelRateLimit.tryAcquire(player, PanelRateLimit.Kind.NAME_LINKED, 20_000 + 10),
                "and one more a second later");
        PanelRateLimit.forget(player);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aFloodOfClearHoversIsCutButTargetsAndSingleClearsPass(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        FakePlayer sender = fake(level, "hover");
        sender.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY); // no wrench: every packet is a clear
        NetworkShare.forget(sender.getUUID());
        PanelRateLimit.forget(sender.getUUID());
        int relayed = 0;
        for (int i = 0; i < 300; i++) {
            if (NetworkShare.handle(sender, NetworkingPayloads.KIND_NONE, BlockPos.ZERO, 0) >= 0) {
                relayed++;
            }
        }
        helper.assertTrue(relayed == 10, "a flood of clears relays the burst only, got " + relayed);
        // The same through a state that is not a clear: still held to the 3 tick spacing.
        sender.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(BwrItems.NETWORKING_WRENCH.get()));
        NetworkShare.forget(sender.getUUID());
        helper.assertTrue(NetworkShare.handle(sender, NetworkingPayloads.KIND_DEVICE, new BlockPos(1, 2, 3), 0) >= 0, "a target relays");
        helper.assertTrue(NetworkShare.handle(sender, NetworkingPayloads.KIND_DEVICE, new BlockPos(1, 2, 3), 0) == -1, "a second at once does not");
        NetworkShare.forget(sender.getUUID());
        PanelRateLimit.forget(sender.getUUID());
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void perPlayerMapsAreDroppedWhenThePlayerLeaves(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        FakePlayer leaver = fake(level, "leaver");
        int before = PanelRateLimit.tracked();
        PanelRateLimit.tryAcquire(leaver.getUUID(), PanelRateLimit.Kind.OPERATE, 1);
        helper.assertTrue(PanelRateLimit.tracked() == before + 1, "the player has limiter state");
        PanelPlayerCleanup.onLogout(new PlayerEvent.PlayerLoggedOutEvent(leaver));
        helper.assertTrue(PanelRateLimit.tracked() == before, "logout drops it");
        helper.succeed();
    }

    // ---- finding 6: Spring re-centre reaches the sinks

    private static final List<double[]> FIRED = new ArrayList<>();
    private static volatile boolean listening;
    private static volatile int watched = -1;

    static {
        PanelBus.addOutputListener((level, owner, component, index, value) -> {
            if (listening && component.id() == watched) {
                FIRED.add(new double[] {index, value});
            }
        });
    }

    private static boolean fired(int index, double value) {
        return FIRED.stream().anyMatch(f -> (int) f[0] == index && f[1] == value);
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void switchingSpringOnRecentresALatchedJoystickAndTellsTheSinks(GameTestHelper helper) {
        HardwareStage.run(helper, ORIGIN_X + 64, ORIGIN_Z, "audit-spring-js", s -> {
            PanelComponent sw = s.place(ComponentTypes.JOYSTICK_SWITCH, 0, 0, Footprint.of(8, 8));
            helper.assertTrue(PanelOps.operate(s.player, s.origin, sw.id(), OperateAction.SET, JoystickData.LEFT)
                    && outputs(sw).equals("0010/3"), "fixture: latched LEFT, no spring: " + outputs(sw));
            FIRED.clear();
            watched = sw.id();
            listening = true;
            try {
                s.wrench();
                CompoundTag config = new CompoundTag();
                config.putBoolean("Spring", true);
                CompoundTag edit = new CompoundTag();
                edit.put("Config", config);
                helper.assertTrue(PanelOps.configure(s.player, s.origin, sw.id(), edit), "spring edit accepted");
                helper.assertTrue(((JoystickData) sw.data()).spring && ((JoystickData) sw.data()).position == JoystickData.CENTRE
                        && outputs(sw).equals("0000/0"), "re-centred: " + outputs(sw));
                helper.assertTrue(fired(2, 0.0) && fired(4, 0.0),
                        "the sinks heard LEFT let go and the position 0, got " + FIRED.size() + " events");
                helper.assertTrue(FIRED.get(0)[0] == 2 && FIRED.get(FIRED.size() - 1)[0] == 4,
                        "the direction lets go before the position changes");
                int heard = FIRED.size();
                // The centre pressed afterwards is a plain no-op: the plant already has it.
                helper.assertTrue(PanelOps.operate(s.player, s.origin, sw.id(), OperateAction.PRESS, 1 + JoystickData.CENTRE)
                        && FIRED.size() == heard && outputs(sw).equals("0000/0"), "pressing the centre changes and fires nothing more");
                // An edit that leaves the lever where it is fires nothing.
                edit = new CompoundTag();
                config = new CompoundTag();
                config.putString("LegendUp", "TOP");
                edit.put("Config", config);
                PanelOps.configure(s.player, s.origin, sw.id(), edit);
                helper.assertTrue(FIRED.size() == heard, "a legend edit fires nothing");
            } finally {
                listening = false;
            }
        });
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void switchingSpringOnReturnsALatchedControlSwitchAndTellsTheSinks(GameTestHelper helper) {
        HardwareStage.run(helper, ORIGIN_X + 128, ORIGIN_Z, "audit-spring-cs", s -> {
            PanelComponent sw = s.place(ComponentTypes.CONTROL_SWITCH, 0, 0, Footprint.of(8, 12));
            s.wrench();
            CompoundTag config = new CompoundTag();
            config.putBoolean("Spring", false);
            CompoundTag edit = new CompoundTag();
            edit.put("Config", config);
            helper.assertTrue(PanelOps.configure(s.player, s.origin, sw.id(), edit) && !((ControlSwitchData) sw.data()).spring,
                    "fixture: spring off");
            helper.assertTrue(PanelOps.operate(s.player, s.origin, sw.id(), OperateAction.SET, ControlSwitchData.OPEN)
                    && (int) sw.output(0) == ControlSwitchData.OPEN && (int) sw.output(1) == 1, "fixture: latched at OPEN");
            FIRED.clear();
            watched = sw.id();
            listening = true;
            try {
                config = new CompoundTag();
                config.putBoolean("Spring", true);
                edit = new CompoundTag();
                edit.put("Config", config);
                helper.assertTrue(PanelOps.configure(s.player, s.origin, sw.id(), edit), "spring edit accepted");
                helper.assertTrue(((ControlSwitchData) sw.data()).position == ControlSwitchData.NORMAL
                        && (int) sw.output(0) == 0 && (int) sw.output(1) == 0 && (int) sw.output(2) == 0,
                        "back at NORMAL: " + sw.output(0) + "," + sw.output(1) + "," + sw.output(2));
                helper.assertTrue(fired(0, 0.0) && fired(1, 0.0), "the sinks heard NORMAL and the OPEN flag let go, got "
                        + FIRED.size() + " events");
            } finally {
                listening = false;
            }
        });
    }

    // ---- finding 9: group size cap for a piece of two blocks

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void aTwoBlockDeskIsMeasuredWithBothHalvesAgainstTheGroupCap(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = new BlockPos(ORIGIN_X + 192, 100, ORIGIN_Z);
        int cx0 = (origin.getX() - 4) >> 4;
        int cx1 = (origin.getX() + 24) >> 4;
        int cz = origin.getZ() >> 4;
        for (int cx = cx0; cx <= cx1; cx++) {
            for (int z = cz - 1; z <= cz + 1; z++) {
                level.setChunkForced(cx, z, true);
                level.getChunk(cx, z);
            }
        }
        FakePlayer player = fake(level, "desk");
        try {
            int cap = dev.bwr.mod.panel.PanelConfig.maxColumns();
            Direction facing = Direction.NORTH;
            // A 2 m desk's left half lies at origin.east(1); the run starts right after it.
            for (int run : new int[] {cap - 2, cap - 1}) {
                clearStrip(level, origin);
                for (int i = 0; i < run; i++) {
                    level.setBlock(origin.east(2 + i), BwrBlocks.CONTROL_PANEL_DESK.get().defaultBlockState()
                            .setValue(PanelBlock.FACING, facing), 3);
                }
                boolean placed = placeWideDesk(player, origin, facing);
                boolean fits = run + 2 <= cap;
                helper.assertTrue(placed == fits, "a run of " + run + " beside a 2 m desk (cap " + cap + "): placed=" + placed
                        + ", wanted " + fits);
                helper.assertTrue(level.getBlockState(origin).is(BwrBlocks.CONTROL_PANEL_DESK_WIDE.get()) == fits
                        && level.getBlockState(origin.east()).is(BwrBlocks.CONTROL_PANEL_DESK_WIDE.get()) == fits,
                        "and the blocks agree for a run of " + run);
                if (fits) {
                    helper.assertTrue(!((PanelBlockEntity) level.getBlockEntity(origin)).group().oversize(),
                            "the group at exactly the cap is not oversize");
                }
            }

            // The shared check: two blocks stacked above seven rows, each alone fits, together they do not.
            BlockPos stack = origin.north(6);
            int rows = dev.bwr.mod.panel.PanelConfig.maxRows();
            for (int i = 0; i < rows - 1; i++) {
                level.setBlock(stack.above(i), BwrBlocks.CONTROL_PANEL_VERTICAL.get().defaultBlockState()
                        .setValue(PanelBlock.FACING, facing), 3);
            }
            BlockPos a = stack.above(rows - 1);
            BlockPos b = stack.above(rows);
            helper.assertTrue(PanelGroup.placementAllowed(level, a, PanelType.VERTICAL, facing)
                            && PanelGroup.placementAllowed(level, b, PanelType.VERTICAL, facing),
                    "each of two stacked blocks alone fits under the row cap");
            helper.assertTrue(!PanelGroup.placementAllowed(level, List.of(a, b), PanelType.VERTICAL, facing),
                    "both together do not");
            helper.assertTrue(PanelGroup.placementAllowed(level, List.of(a), PanelType.VERTICAL, facing),
                    "a single position through the list form is the old check");
            for (int i = 0; i < rows; i++) {
                level.setBlock(stack.above(i), Blocks.AIR.defaultBlockState(), 2);
            }
        } finally {
            clearStrip(level, origin);
            for (int cx = cx0; cx <= cx1; cx++) {
                for (int z = cz - 1; z <= cz + 1; z++) {
                    level.setChunkForced(cx, z, false);
                }
            }
        }
        helper.succeed();
    }

    private static void clearStrip(ServerLevel level, BlockPos origin) {
        for (int x = -2; x <= 24; x++) {
            level.setBlock(origin.east(x), Blocks.AIR.defaultBlockState(), 2);
            level.setBlock(origin.east(x).below(), Blocks.STONE.defaultBlockState(), 2);
        }
    }

    private static boolean placeWideDesk(FakePlayer player, BlockPos origin, Direction facing) {
        player.setPos(Vec3.atBottomCenterOf(origin.relative(facing.getOpposite(), 3)));
        player.setYRot(facing.getOpposite().toYRot());
        player.setXRot(0);
        ItemStack stack = new ItemStack(BwrItems.CONTROL_PANEL_DESK_WIDE.get());
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        BlockPos floor = origin.below();
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(floor).add(0, 0.5, 0), Direction.UP, floor, false);
        return stack.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, hit)).consumesAction();
    }

    // ---- finding 11: the mimic draft codec

    @GameTest(template = "empty")
    public static void aMimicDraftWithAnOddOrEmptyPointListIsRejected(GameTestHelper helper) {
        BlockPos anchor = new BlockPos(1, 2, 3);
        helper.assertTrue(parses(anchor, 1, 2), "one point is a draft");
        helper.assertTrue(parses(anchor, 16, 6, 38, 6, 38, 19), "three points are a draft");
        helper.assertTrue(!parses(anchor), "an empty list is refused");
        helper.assertTrue(!parses(anchor, 5), "an odd list is refused");
        helper.assertTrue(!parses(anchor, 1, 2, 3), "an odd list of three is refused");
        int[] tooMany = new int[MimicOps.MAX_POINTS * 2 + 2];
        helper.assertTrue(!parses(anchor, tooMany), "more points than a path may have are refused");
        // The tape ignores a hand-made stack, whatever the codec did.
        ItemStack tape = new ItemStack(BwrItems.MIMIC_TAPE.get());
        tape.set(dev.bwr.mod.fuel.BwrDataComponents.MIMIC_DRAFT.get(), new MimicDraft(anchor, List.of(1, 2, 3)));
        helper.assertTrue(MimicTapeItem.draft(tape) == null, "an odd draft on a stack is no draft");
        tape.set(dev.bwr.mod.fuel.BwrDataComponents.MIMIC_DRAFT.get(), new MimicDraft(anchor, List.of()));
        helper.assertTrue(MimicTapeItem.draft(tape) == null, "an empty draft on a stack is no draft");
        tape.set(dev.bwr.mod.fuel.BwrDataComponents.MIMIC_DRAFT.get(), new MimicDraft(anchor, List.of(4, 5)));
        helper.assertTrue(MimicTapeItem.draft(tape) != null, "a whole draft is read");
        helper.succeed();
    }

    private static boolean parses(BlockPos anchor, int... points) {
        CompoundTag tag = new CompoundTag();
        tag.put("anchor", net.minecraft.core.BlockPos.CODEC.encodeStart(NbtOps.INSTANCE, anchor).getOrThrow());
        ListTag list = new ListTag();
        for (int p : points) {
            list.add(IntTag.valueOf(p));
        }
        tag.put("points", list);
        return MimicDraft.CODEC.parse(NbtOps.INSTANCE, tag).result().isPresent();
    }

    // ---- finding 3 (the part that runs on a server): the client panel index can be emptied

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void theClientPanelIndexLetsGoOfEveryPanelWhenCleared(GameTestHelper helper) {
        HardwareStage.run(helper, ORIGIN_X + 256, ORIGIN_Z, "audit-index", s -> {
            int before = PanelNetworkIndex.tracked();
            PanelNetworkIndex.attached(s.be());
            helper.assertTrue(PanelNetworkIndex.tracked() == before + 1, "the panel is indexed");
            PanelNetworkIndex.clear();
            helper.assertTrue(PanelNetworkIndex.tracked() == 0, "clear drops every panel, tracked " + PanelNetworkIndex.tracked());
        });
    }

    // ---- finding 7: CC port machines after an empty answer

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void ccPortMachinesAppearWhenTheyBecomeReachableAfterAnEmptyAnswer(GameTestHelper helper) {
        if (!BwrMod.isComputerCraftPresent()) {
            helper.succeed();
            return;
        }
        AuditCcChecks.run(helper);
    }
}
