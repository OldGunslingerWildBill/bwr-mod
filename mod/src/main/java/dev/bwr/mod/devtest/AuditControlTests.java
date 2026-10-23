package dev.bwr.mod.devtest;

import com.mojang.authlib.GameProfile;
import dev.bwr.mod.panel.PanelBlock;
import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.PanelConfig;
import dev.bwr.mod.panel.PanelOps;
import dev.bwr.mod.panel.PanelRateLimit;
import dev.bwr.mod.panel.PanelType;
import dev.bwr.mod.panel.DuplexCell;
import dev.bwr.mod.panel.DuplexPanelBlock;
import dev.bwr.mod.panel.component.ComponentItem;
import dev.bwr.mod.panel.component.ComponentTypes;
import dev.bwr.mod.panel.component.Footprint;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.mimic.MimicMode;
import dev.bwr.mod.panel.mimic.MimicOps;
import dev.bwr.mod.panel.mimic.MimicPath;
import dev.bwr.mod.panel.mimic.MimicStyle;
import dev.bwr.mod.panel.mimic.MimicTapeItem;
import dev.bwr.mod.panel.MachineNames;
import dev.bwr.mod.panel.net.PanelNetwork;
import dev.bwr.mod.panel.net.PanelPayloads;
import dev.bwr.mod.panel.networking.NetworkShare;
import dev.bwr.mod.panel.networking.NetworkingNet;
import dev.bwr.mod.panel.networking.NetworkingPayloads;
import dev.bwr.mod.panel.networking.NetworkingScreenPayloads;
import dev.bwr.mod.panel.wiring.WiringOps;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.registry.BwrItems;
import dev.bwr.mod.steam.TurbineSteamOutletBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Negative-control additions for the 2026-10-21 audit fixes (see CHANGELOG, "audit fixes"). {@code AuditFixTests} covers
 * the fixes through the operations themselves; these tests cover the places a temporary mutation of the fix did not
 * make that class fail: the game-mode gate of the packet families it does not exercise (recorder message, CC port
 * lookup, the tape's edit and mode, Use machine name, the size picker), the rate limits as wired into the real packet
 * handlers, the duplex column against the group size cap, the mimic edits refused next to an unloaded chunk, the
 * steam outlet taking more exhaust than it has room for, the logout cleanup as the event bus runs it, and the
 * refund of tank blocks into a full inventory. Fixtures at 240,000 / 240,000 and east, 242,000, 250,000 and net slot 110.
 */
@GameTestHolder("bwr")
@PrefixGameTestTemplate(false)
public final class AuditControlTests {
    private AuditControlTests() {
    }

    private static final int X = 240_000;
    private static final int Z = 240_000;

    private static FakePlayer fake(ServerLevel level, String name) {
        FakePlayer player = FakePlayerFactory.get(level, new GameProfile(
                UUID.nameUUIDFromBytes(("audit-control-" + name).getBytes(StandardCharsets.UTF_8)), "ac-" + name));
        player.setGameMode(GameType.SURVIVAL);
        return player;
    }

    private static IPayloadContext context(ServerPlayer player) {
        return (IPayloadContext) java.lang.reflect.Proxy.newProxyInstance(IPayloadContext.class.getClassLoader(),
                new Class<?>[] {IPayloadContext.class}, (proxy, method, args) -> {
                    if (method.getName().equals("player")) {
                        return player;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private static void invoke(Class<?> owner, String name, Class<?> payloadType, Object payload, ServerPlayer player) {
        try {
            Method method = owner.getDeclaredMethod(name, payloadType, IPayloadContext.class);
            method.setAccessible(true);
            method.invoke(null, payload, context(player));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(owner.getSimpleName() + "." + name + " could not be driven: " + e, e);
        }
    }

    // ---- finding 1, the packet families AuditFixTests does not reach

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void gameModesAlsoGateTheRecorderMessageTheCcPortLookupTheTapeEditAndModeAndTheSizePicker(GameTestHelper helper) {
        HardwareStage.run(helper, X, Z, "audit-modes2", s -> {
            PanelComponent recorder = s.place(ComponentTypes.PAPERLESS_RECORDER, 0, 0, Footprint.of(16, 16));
            MimicOps.Placed placed = MimicOps.place(s.level, null, s.origin, new int[] {2, 4, 14, 4}, new MimicStyle());
            helper.assertTrue(placed.result() == MimicOps.Result.OK, "fixture: a mimic path on the board, got " + placed.result());
            MimicPath path = s.be().mimicPaths().get(0);
            int colourIndex = 0;
            for (GameType mode : new GameType[] {GameType.SPECTATOR, GameType.ADVENTURE, GameType.SURVIVAL}) {
                s.player.setGameMode(mode);
                String m = mode.getName();
                boolean operate = mode != GameType.SPECTATOR; // adventure maps keep working
                boolean edit = mode == GameType.SURVIVAL;

                helper.assertTrue(PanelOps.recorderMessage(s.player, s.origin, recorder.id(), "audit " + m) == operate,
                        m + ": a recorder message is " + (operate ? "taken" : "refused"));

                s.player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(BwrItems.PANEL_LINKER.get()));
                helper.assertTrue((WiringOps.ccPortScreenFor(s.player, s.origin, recorder.id(), 0) != null) == edit,
                        m + ": the CC port lookup answers " + (edit ? "a player who may edit" : "nobody who may not edit"));

                ItemStack tape = new ItemStack(BwrItems.MIMIC_TAPE.get());
                s.player.setItemInHand(InteractionHand.MAIN_HAND, tape);
                boolean cycled = MimicOps.cycleMode(s.player, InteractionHand.MAIN_HAND, 1);
                helper.assertTrue(cycled == operate && (MimicTapeItem.mode(tape) != MimicMode.DRAW) == operate,
                        m + ": the tape's mode switch is " + (operate ? "taken" : "refused") + ", mode " + MimicTapeItem.mode(tape));

                int colour = 0x102030 + 0x10 * ++colourIndex;
                CompoundTag colourEdit = new CompoundTag();
                colourEdit.putInt("Color", colour);
                s.player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(BwrItems.MIMIC_TAPE.get()));
                boolean configured = MimicOps.configure(s.player, s.origin, path.localId(), colourEdit);
                helper.assertTrue(configured == edit && (path.style().color == colour) == edit,
                        m + ": the tape's edit is " + (edit ? "taken" : "refused") + ", colour " + Integer.toHexString(path.style().color));

                ItemStack gauge = new ItemStack(BwrItems.DIAL_GAUGE.get());
                s.player.setItemInHand(InteractionHand.MAIN_HAND, gauge);
                List<Footprint> sizes = ComponentTypes.DIAL_GAUGE.footprints();
                Footprint before = ComponentItem.footprint(gauge);
                int other = sizes.indexOf(before) == 0 ? 1 : 0;
                PanelNetwork.handleSetSize(new PanelPayloads.SetSize(false, other), context(s.player));
                boolean resized = !ComponentItem.footprint(s.player.getMainHandItem()).equals(before);
                helper.assertTrue(resized == operate, m + ": the size picker is " + (operate ? "taken" : "refused"));
            }
        });
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void useMachineNameIsRefusedToSpectatorsAndAdventurePlayers(GameTestHelper helper) {
        NetStage.run(helper, 110, s -> {
            s.wall(1, 1);
            PanelBlockEntity block = s.be(s.at(0, 0));
            PanelComponent gauge = s.gauge(block.getBlockPos(), 1, 1);
            var valve = s.msiv(s.devices);
            MachineNames.setName(valve, "RHR-1");
            s.wrench(s.devices);
            s.wire(s.plantIn(block, gauge, 0, s.devices, "position", 100, 0));
            s.check(gauge.source(0) instanceof dev.bwr.mod.panel.wiring.PlantSource, "fixture: the gauge is line-linked to the valve");
            s.wrench(s.devices);
            s.standAtPanels();
            for (GameType mode : new GameType[] {GameType.SPECTATOR, GameType.ADVENTURE}) {
                s.player.setGameMode(mode);
                s.wire(NetworkingPayloads.UseMachineName.STREAM_CODEC,
                        new NetworkingPayloads.UseMachineName(block.getBlockPos(), gauge.id()), NetworkingNet::handleUseMachineName);
                s.check(gauge.caption().isEmpty(), mode.getName() + ": Use machine name changes nothing, got '" + gauge.caption() + "'");
            }
            s.player.setGameMode(GameType.SURVIVAL);
            s.wire(NetworkingPayloads.UseMachineName.STREAM_CODEC,
                    new NetworkingPayloads.UseMachineName(block.getBlockPos(), gauge.id()), NetworkingNet::handleUseMachineName);
            s.check(gauge.caption().equals("RHR-1"), "survival: Use machine name names the component, got '" + gauge.caption() + "'");
            s.removeMsiv(s.devices);
        });
    }

    // ---- finding 5, the limits as wired into the handlers

    private record RateCase(String name, PanelRateLimit.Kind kind, int burst, Consumer<ServerPlayer> send) {
    }

    @GameTest(template = "empty")
    public static void theRateLimitsSitInTheRealPacketHandlers(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos nowhere = new BlockPos(0, 64, 0);
        List<RateCase> cases = List.of(
                new RateCase("panel_configure", PanelRateLimit.Kind.CONFIGURE, 10, p -> invoke(PanelNetwork.class, "handleConfigure",
                        PanelPayloads.Configure.class, new PanelPayloads.Configure(nowhere, 0, new CompoundTag()), p)),
                new RateCase("panel_operate", PanelRateLimit.Kind.OPERATE, 200, p -> invoke(PanelNetwork.class, "handleOperate",
                        PanelPayloads.Operate.class, new PanelPayloads.Operate(nowhere, 0, 0, 0.0, 0), p)),
                new RateCase("panel_linker_open", PanelRateLimit.Kind.OPEN_SCREEN, 6, p -> invoke(PanelNetwork.class, "handleLinkerOpen",
                        PanelPayloads.LinkerOpen.class, new PanelPayloads.LinkerOpen(nowhere, 0), p)),
                new RateCase("network_name_linked", PanelRateLimit.Kind.NAME_LINKED, 6,
                        p -> NetworkingNet.handleNameLinked(new NetworkingPayloads.NameLinked(nowhere), context(p))),
                new RateCase("network_open", PanelRateLimit.Kind.OPEN_SCREEN, 6,
                        p -> NetworkingNet.handleOpen(new NetworkingScreenPayloads.Open(nowhere), context(p))),
                new RateCase("network_cc_port_lookup", PanelRateLimit.Kind.OPEN_SCREEN, 6,
                        p -> NetworkingNet.handleCcPortLookup(new NetworkingScreenPayloads.CcPortLookup(nowhere, 0, 0), context(p))));
        for (RateCase c : cases) {
            FakePlayer player = fake(level, "rate-" + c.name());
            UUID id = player.getUUID();
            PanelRateLimit.forget(id);
            long tick = player.server.getTickCount();
            for (int i = 0; i < c.burst() + 5; i++) {
                c.send().accept(player);
            }
            helper.assertTrue(!PanelRateLimit.tryAcquire(id, c.kind(), tick),
                    c.name() + ": a burst of " + (c.burst() + 5) + " through the real handler drains the " + c.kind() + " bucket");
            PanelRateLimit.forget(id);
        }
        helper.succeed();
    }

    // ---- finding 9, the duplex column

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void aDuplexColumnIsMeasuredWithBothBoardBlocksAgainstTheGroupCap(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = new BlockPos(X + 128, 100, Z);
        Direction facing = Direction.NORTH;
        int cx0 = (origin.getX() - 24) >> 4;
        int cx1 = (origin.getX() + 24) >> 4;
        int cz = origin.getZ() >> 4;
        for (int cx = cx0; cx <= cx1; cx++) {
            for (int z = cz - 1; z <= cz + 1; z++) {
                FixtureChunks.force(level, cx, z, true);
                level.getChunk(cx, z);
            }
        }
        FakePlayer player = fake(level, "duplex");
        try {
            int cap = PanelConfig.maxColumns();
            BlockPos upper = DuplexCell.BOARD_UPPER.at(origin, facing);
            // Raw upper board blocks on both sides of where the new column's upper board block goes, at that height only:
            // the lower board block of the new column joins nothing, the upper one joins both runs.
            int left = cap / 2;
            for (int right : new int[] {cap - left - 1, cap - left}) {
                clearDuplex(level, origin);
                BlockPos at = upper;
                for (int i = 0; i < left; i++) {
                    at = PanelType.nextColumn(at, facing);
                    level.setBlock(at, rawDuplexBoard(facing), 2);
                }
                at = upper;
                for (int i = 0; i < right; i++) {
                    at = PanelType.previousColumn(at, facing);
                    level.setBlock(at, rawDuplexBoard(facing), 2);
                }
                boolean fits = left + right + 1 <= cap;
                player.setPos(Vec3.atBottomCenterOf(origin.relative(facing.getOpposite(), 3)));
                player.setYRot(facing.getOpposite().toYRot());
                player.setXRot(0);
                ItemStack stack = new ItemStack(BwrItems.CONTROL_PANEL_DUPLEX.get());
                player.setItemInHand(InteractionHand.MAIN_HAND, stack);
                BlockPos floor = origin.below();
                BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(floor).add(0, 0.5, 0), Direction.UP, floor, false);
                stack.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, hit));
                boolean placed = level.getBlockState(origin).is(BwrBlocks.CONTROL_PANEL_DUPLEX.get());
                helper.assertTrue(placed == fits, "a duplex column joining runs of " + left + " and " + right + " upper board blocks (cap "
                        + cap + "): placed=" + placed + ", wanted " + fits);
            }
        } finally {
            clearDuplex(level, origin);
            for (int cx = cx0; cx <= cx1; cx++) {
                for (int z = cz - 1; z <= cz + 1; z++) {
                    FixtureChunks.force(level, cx, z, false);
                }
            }
        }
        helper.succeed();
    }

    private static net.minecraft.world.level.block.state.BlockState rawDuplexBoard(Direction facing) {
        return BwrBlocks.CONTROL_PANEL_DUPLEX.get().defaultBlockState().setValue(PanelBlock.FACING, facing)
                .setValue(DuplexPanelBlock.CELL, DuplexCell.BOARD_UPPER);
    }

    private static void clearDuplex(ServerLevel level, BlockPos origin) {
        for (int x = -20; x <= 20; x++) {
            for (int y = -1; y <= 5; y++) {
                for (int z = -1; z <= 2; z++) {
                    BlockPos at = origin.offset(x, y, z);
                    level.setBlock(at, y == -1 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), 2);
                }
            }
        }
    }

    // ---- finding 8, mimic edits next to an unloaded chunk

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void aMimicPathNextToAnUnloadedChunkCannotBeErasedOrRestyledUntilItLoads(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        // Chunk P is forced (ticket level 31), P+2 is loaded but not ticking (33), P+3 is not loaded (34). The board sits
        // on the east edge of P+2, so the block it would go on to is in a chunk that is not there.
        int cx = (X + 256) >> 4;
        int cz = Z >> 4;
        BlockPos board = new BlockPos((cx + 2) * 16 + 15, 110, Z + 8);
        BlockPos beyond = board.east();
        FakePlayer player = fake(level, "edge");
        FixtureChunks.force(level, cx, cz, true);
        try {
            level.getChunk(cx, cz);
            level.getChunk(cx + 2, cz);
            helper.assertTrue(level.isLoaded(board) && !level.isLoaded(beyond),
                    "fixture: the board's chunk is loaded and the next one east is not (loaded=" + level.isLoaded(beyond) + ")");
            level.setBlock(board, BwrBlocks.CONTROL_PANEL_VERTICAL.get().defaultBlockState().setValue(PanelBlock.FACING, Direction.NORTH), 18);
            helper.assertTrue(!level.isLoaded(beyond), "fixture: placing the board did not load the chunk east of it");
            PanelBlockEntity block = (PanelBlockEntity) level.getBlockEntity(board);
            MimicOps.Placed placed = MimicOps.place(level, null, board, new int[] {2, 4, 14, 4}, new MimicStyle());
            helper.assertTrue(block != null && placed.result() == MimicOps.Result.OK, "fixture: a path on the board, got " + placed.result());
            helper.assertTrue(block.group().bordersUnloaded(level), "the board's group borders an unloaded chunk");
            MimicPath path = block.mimicPaths().get(0);
            player.setPos(Vec3.atBottomCenterOf(board.below(2)).add(0, 0, -1.5));
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(BwrItems.MIMIC_TAPE.get()));

            helper.assertTrue(MimicOps.erasePath(player, block, path.pathId()) == MimicOps.Result.NOT_LOADED && block.mimicCount() == 1,
                    "an erase next to an unloaded chunk is refused and the path stays");
            CompoundTag edit = new CompoundTag();
            edit.putInt("Color", 0x445566);
            helper.assertTrue(!MimicOps.configure(player, board, path.localId(), edit) && path.style().color != 0x445566,
                    "a restyle next to an unloaded chunk is refused");

            // Moving a waypoint re-places the path, which also has to wait: pick the first waypoint up, put it down elsewhere.
            ItemStack editTape = new ItemStack(BwrItems.MIMIC_TAPE.get());
            MimicTapeItem.setMode(editTape, MimicMode.EDIT);
            player.setItemInHand(InteractionHand.MAIN_HAND, editTape);
            helper.assertTrue(MimicOps.use(player, InteractionHand.MAIN_HAND, board, 2.0, 4.0) == MimicOps.Result.OK,
                    "fixture: the first waypoint is picked up");
            helper.assertTrue(MimicOps.use(player, InteractionHand.MAIN_HAND, board, 2.0, 10.0) == MimicOps.Result.NOT_LOADED
                            && java.util.Arrays.equals(path.points(), new int[] {2, 4, 14, 4}),
                    "moving a waypoint next to an unloaded chunk is refused and the path stays: " + java.util.Arrays.toString(path.points()));
            MimicOps.dropGrab(player);
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(BwrItems.MIMIC_TAPE.get()));

            // The same edits go through once the neighbour is loaded.
            FixtureChunks.force(level, cx + 3, cz, true);
            try {
                level.getChunk(cx + 3, cz);
                helper.assertTrue(level.isLoaded(beyond) && !block.group().bordersUnloaded(level), "control: the neighbour is loaded now");
                helper.assertTrue(MimicOps.configure(player, board, path.localId(), edit) && path.style().color == 0x445566,
                        "control: the restyle is taken with the neighbour loaded");
                helper.assertTrue(MimicOps.erasePath(player, block, path.pathId()) == MimicOps.Result.OK && block.mimicCount() == 0,
                        "control: the erase is taken with the neighbour loaded");
            } finally {
                FixtureChunks.force(level, cx + 3, cz, false);
            }
        } finally {
            level.setBlock(board, Blocks.AIR.defaultBlockState(), 2);
            FixtureChunks.force(level, cx, cz, false);
        }
        helper.succeed();
    }

    // ---- finding 12, the steam outlet

    @GameTest(template = "empty")
    public static void aSteamOutletClampsPumpExhaustItHasNoRoomForInsteadOfThrowing(GameTestHelper helper) {
        TurbineSteamOutletBlockEntity outlet = new TurbineSteamOutletBlockEntity(BlockPos.ZERO,
                BwrBlocks.TURBINE_STEAM_OUTLET.get().defaultBlockState());
        long tick = 10;
        helper.assertTrue(outlet.pumpExhaustCapacityKgPerS(tick) == 0.0, "fixture: nothing is commanded, so there is no room");
        try {
            outlet.receivePumpExhaustKgPerS(tick, 5.0);
            outlet.receivePumpExhaustKgPerS(tick, 5.0);
        } catch (IllegalStateException e) {
            helper.fail("a ticker must not throw: " + e.getMessage());
            return;
        }
        helper.assertTrue(outlet.getBufferedMilliBuckets() == 0L, "nothing is buffered beyond the room there was, got " + outlet.getBufferedMilliBuckets());
        helper.succeed();
    }

    // ---- finding 13, logout cleanup as the event bus runs it

    @SuppressWarnings("unchecked")
    private static Map<UUID, Object> staticMap(Class<?> owner, String field) {
        try {
            Field f = owner.getDeclaredField(field);
            f.setAccessible(true);
            return (Map<UUID, Object>) f.get(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(owner.getSimpleName() + "." + field + " is not readable: " + e, e);
        }
    }

    @GameTest(template = "empty")
    public static void everyPerPlayerMapOfThePanelLayerIsDroppedByTheLogoutEvent(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        FakePlayer leaver = fake(level, "logout");
        UUID id = leaver.getUUID();
        Map<UUID, Object> share = staticMap(NetworkShare.class, "LAST");
        Map<UUID, Object> values = staticMap(dev.bwr.mod.panel.networking.NetworkingOps.class, "LAST_VALUES");
        Map<UUID, Object> grabs = staticMap(MimicOps.class, "GRABS");
        share.put(id, 1L);
        values.put(id, 1L);
        grabs.put(id, Boolean.TRUE);
        PanelRateLimit.tryAcquire(id, PanelRateLimit.Kind.OPERATE, 1);
        helper.assertTrue(share.containsKey(id) && values.containsKey(id) && grabs.containsKey(id), "fixture: the player has state in every map");
        NeoForge.EVENT_BUS.post(new PlayerEvent.PlayerLoggedOutEvent(leaver));
        helper.assertTrue(!share.containsKey(id), "the hover share throttle is dropped by the logout event");
        helper.assertTrue(!values.containsKey(id), "the value request throttle is dropped by the logout event");
        helper.assertTrue(!grabs.containsKey(id), "the grabbed waypoint is dropped by the logout event");
        PanelRateLimit.forget(id);
        helper.succeed();
    }

    // ---- finding 2, the refund of tank blocks

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void tankBlocksRefundedIntoAFullInventoryAreNotLost(GameTestHelper h) {
        var pos = h.absolutePos(new BlockPos(1, 1, 1));
        EntityLookupProbe.runWhenLive(h, new AABB(pos).inflate(4), () -> checkTankRefund(h));
    }

    private static void checkTankRefund(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        FakePlayer player = fake(level, "tank");
        var pos = h.absolutePos(new BlockPos(1, 1, 1));
        AABB bounds = new AABB(pos).inflate(4);
        var item = BwrItems.CONDENSATE_STORAGE_TANK.get();
        try {
            player.setPos(pos.getX() + .5, pos.getY(), pos.getZ() + .5);
            player.getInventory().clearContent();
            for (int i = 0; i < 36; i++) {
                player.getInventory().setItem(i, new ItemStack(Items.COBBLESTONE, 64));
            }
            player.getInventory().setItem(1, new ItemStack(item, 60));
            Method give = dev.bwr.mod.eccs.CondensateTankAssembly.class.getDeclaredMethod("give", ServerPlayer.class,
                    net.minecraft.world.item.Item.class, int.class);
            give.setAccessible(true);
            give.invoke(null, player, item, 130);
            int dropped = level.getEntitiesOfClass(ItemEntity.class, bounds).stream().filter(e -> e.getItem().is(item))
                    .mapToInt(e -> e.getItem().getCount()).sum();
            int held = player.getInventory().countItem(item);
            h.assertTrue(dropped > 0, "the overflow path was not exercised");
            h.assertTrue(held + dropped == 190, "a refund of 130 tank blocks to a full inventory lost or duplicated some: held " + held
                    + " + dropped " + dropped + " (wanted 190)");
            h.succeed();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("CondensateTankAssembly.give could not be driven: " + e, e);
        } finally {
            level.getEntitiesOfClass(ItemEntity.class, bounds).forEach(ItemEntity::discard);
            player.getInventory().clearContent();
        }
    }
}
