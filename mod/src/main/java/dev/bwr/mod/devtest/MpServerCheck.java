package dev.bwr.mod.devtest;

import com.mojang.logging.LogUtils;
import dev.bwr.mod.BwrMod;
import dev.bwr.mod.fuel.BwrDataComponents;
import dev.bwr.mod.panel.PanelBlock;
import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.PanelOps;
import dev.bwr.mod.panel.component.ComponentTypes;
import dev.bwr.mod.panel.component.ControlSwitchData;
import dev.bwr.mod.panel.component.CoreDisplayData;
import dev.bwr.mod.panel.component.Footprint;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.component.RodSelectMatrixData;
import dev.bwr.mod.panel.wiring.PlantSink;
import dev.bwr.mod.panel.wiring.PlantSource;
import dev.bwr.mod.reactor.ReactorControllerBlockEntity;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.registry.BwrItems;
import dev.bwr.mod.steam.MainSteamIsolationValveBlock;
import dev.bwr.mod.steam.MainSteamIsolationValveBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.BooleanSupplier;

/**
 * The server half of the multiplayer check (C3), run on a real dedicated server with two real clients
 * ({@code :mod:runMultiplayerCheck}, see TESTING.md). It builds the fixture when the first player joins (a formed
 * 15 x 15 reactor with its drives supplied, an MSIV, and a panel wall with a full core display, a rod select matrix,
 * switches, meters and pushbuttons, all bound to the plant), then conducts the scenarios by writing step files that the
 * two clients ({@link MpClientCheck}) act on through the real connection, reads back what each client saw, and compares
 * it with the server's own state. It logs {@code MP CHECK PASS}, or {@code MP CHECK FAIL <reason>}, and writes the
 * verdict to {@code result.properties}. The server is stopped after phase 1 and started again by the driver for phase 2
 * (persistence across a restart). Inert without {@code -Dbwr.mpCheck=server}; stripped from the release jar.
 */
@EventBusSubscriber(modid = BwrMod.MOD_ID)
public final class MpServerCheck {
    private static final boolean ON = "server".equals(System.getProperty("bwr.mpCheck"));
    private static final org.slf4j.Logger LOG = LogUtils.getLogger();

    static final BlockPos MIN = new BlockPos(20, 120, 70);
    /** Top-left block of the wall; columns run toward -X, rows downward, facing north. */
    static final BlockPos WALL = new BlockPos(25, 128, 60);
    static final int COLS = 10, ROWS = 6;
    static final BlockPos MSIV = new BlockPos(22, 126, 64);
    static final BlockPos REACTOR = MIN.offset(-1, 3, 7);
    static final double[][] STAND = {{18.5, 123.0, 56.5}, {17.5, 123.0, 56.5}};
    /** Ticks a client gets to settle after a join before the first step is given to it. */
    private static final int SETTLE = 120;
    /** Ticks the server waits after the last action for the clients to have received everything. */
    private static final int SYNC = 50;

    private static int age;
    private static boolean built, started, finished;
    private static int phase;
    private static final Map<String, Integer> joinedAt = new HashMap<>();
    private static final Map<String, ServerPlayer> online = new LinkedHashMap<>();
    private static final Map<String, Map<String, Integer>> offlineCounts = new HashMap<>();
    private static int seq;
    private static MinecraftServer theServer;

    private MpServerCheck() {
    }

    // ---- fixture

    private static ServerLevel level(MinecraftServer server) {
        return server.overworld();
    }

    private static BlockPos wallAt(int col, int row) {
        return WALL.relative(Direction.WEST, col).below(row);
    }

    private static PanelComponent put(ServerLevel level, int col, int row, int cx, int cy,
                                      dev.bwr.mod.panel.component.ComponentType type, int w, int h, String label) {
        PanelComponent component = PanelOps.placeDirect(level, wallAt(col, row), cx, cy, type, Footprint.of(w, h));
        if (component == null) throw new AssertionError("MP fixture: " + label + " does not fit");
        component.setLabel(label);
        return component;
    }

    private static void buildFixture(ServerLevel level) throws Exception {
        for (int x = (MIN.getX() - 40) >> 4; x <= (MIN.getX() + 30) >> 4; x++)
            for (int z = (WALL.getZ() - 20) >> 4; z <= (MIN.getZ() + 30) >> 4; z++) level.getChunk(x, z);
        // A platform to stand on, in front of the wall.
        for (BlockPos pos : BlockPos.betweenClosed(new BlockPos(10, 122, 50), new BlockPos(30, 122, 61)))
            level.setBlock(pos, Blocks.SMOOTH_STONE.defaultBlockState(), 2);
        ReactorControllerBlockEntity reactor = CompactCoreRegressionTests.build(level, MIN, 15, 15, false);
        if (!reactor.isFormed()) throw new AssertionError("MP fixture: reactor did not form: " + reactor.statusLines());
        if (!reactor.getBlockPos().equals(REACTOR)) throw new AssertionError("MP fixture: reactor controller is at " + reactor.getBlockPos());
        FcdTestSupport.supplyDrives(level, reactor);
        // Rods spread over the stroke so a step has room both ways: every rod at notch 12.
        for (int r = 0; r < reactor.core().getControlRodCount(); r++) reactor.rods().setNotchDemand(r, 12);
        for (int i = 0; i < 1300; i++) {
            reactor.rods().preStep(0.05);
            reactor.core().step();
            reactor.rods().postStep();
        }
        // The MSIV the switch drives.
        var msivBlock = BwrBlocks.MSIV.get();
        var state = msivBlock.placementState().setValue(MainSteamIsolationValveBlock.FACING, Direction.NORTH);
        level.setBlock(MSIV, state, 3);
        msivBlock.setPlacedBy(level, MSIV, state, null, new ItemStack(msivBlock));
        // The panel wall.
        for (int row = 0; row < ROWS; row++)
            for (int col = 0; col < COLS; col++)
                level.setBlock(wallAt(col, row), BwrBlocks.CONTROL_PANEL_VERTICAL.get().defaultBlockState()
                        .setValue(PanelBlock.FACING, Direction.NORTH), 3);
        // Columns run toward -X, rows downward. The display takes the left six columns; the matrix and the controls are in
        // the four columns on the right, where both players stand (within reach of every control).
        PanelComponent fcd = put(level, 0, 0, 0, 0, ComponentTypes.FULL_CORE_DISPLAY, 96, 96, "FCD-1");
        fcd.setSource(0, new PlantSource(REACTOR, "formed"));
        PanelComponent matrix = put(level, 6, 2, 0, 0, ComponentTypes.ROD_SELECT_MATRIX, 64, 64, "RSM-1");
        matrix.setSource(0, new PlantSource(REACTOR, "formed"));
        matrix.addSink(0, new PlantSink(REACTOR, "select_rod", 1.0));
        PanelComponent sw = put(level, 6, 0, 0, 2, ComponentTypes.CONTROL_SWITCH, 8, 12, "SW-1");
        ((ControlSwitchData) sw.data()).spring = false;
        sw.addSink(1, new PlantSink(MSIV, "open", 1, PlantSink.Mode.MOMENTARY, 1));
        sw.addSink(2, new PlantSink(MSIV, "open", 1, PlantSink.Mode.MOMENTARY, 0));
        put(level, 7, 0, 0, 0, ComponentTypes.DIAL_GAUGE, 12, 12, "DIAL-1").setSource(0, new PlantSource(MSIV, "position"));
        put(level, 8, 0, 0, 0, ComponentTypes.DIAL_GAUGE, 12, 12, "DIAL-3").setSource(0, new PlantSource(REACTOR, "selected_rod"));
        put(level, 9, 0, 0, 0, ComponentTypes.DIAL_GAUGE, 12, 12, "DIAL-2");
        String[][] buttons = {{"PB-WD", "selected_rod_withdraw", "6", "0"}, {"PB-IN", "selected_rod_insert", "6", "8"},
                {"PB-WDC", "selected_rod_withdraw_continuous", "7", "0"}, {"PB-INC", "selected_rod_insert_continuous", "7", "8"}};
        for (String[] b : buttons) {
            PanelComponent pb = put(level, Integer.parseInt(b[2]), 1, Integer.parseInt(b[3]), 0, ComponentTypes.PUSHBUTTON, 6, 6, b[0]);
            pb.addSink(0, new PlantSink(REACTOR, b[1], 1.0));
        }
        put(level, 8, 1, 0, 0, ComponentTypes.LAMP, 4, 4, "LAMP-A");
        put(level, 8, 1, 8, 0, ComponentTypes.LAMP, 4, 4, "LAMP-B");
        put(level, 9, 1, 0, 0, ComponentTypes.PUSHBUTTON, 6, 6, "PB-X");
        keepSupplied(level.getServer());
        for (int row = 0; row < ROWS; row++)
            for (int col = 0; col < COLS; col++)
                if (level.getBlockEntity(wallAt(col, row)) instanceof PanelBlockEntity be) {
                    dev.bwr.mod.panel.wiring.PanelSampler.track(be);
                    be.sampleLive();
                    be.syncFull();
                }
        if (matrix.data() instanceof RodSelectMatrixData data && !data.snapshot().formed())
            throw new AssertionError("MP fixture: the matrix did not bind to the reactor");
        MpProtocol.write(MpProtocol.dir().resolve("fixture.properties"), fixtureDescription());
        LOG.info("MP CHECK fixture built: reactor {} MSIV {} wall {} ({} x {} blocks)", REACTOR, MSIV, WALL, COLS, ROWS);
    }

    private static Map<String, String> fixtureDescription() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("wall", WALL.getX() + "," + WALL.getY() + "," + WALL.getZ());
        m.put("cols", String.valueOf(COLS));
        m.put("rows", String.valueOf(ROWS));
        m.put("reactor", REACTOR.getX() + "," + REACTOR.getY() + "," + REACTOR.getZ());
        m.put("msiv", MSIV.getX() + "," + MSIV.getY() + "," + MSIV.getZ());
        return m;
    }

    private static void keepSupplied(MinecraftServer server) {
        ServerLevel level = level(server);
        if (level.getBlockEntity(REACTOR) instanceof ReactorControllerBlockEntity reactor && reactor.isFormed())
            FcdTestSupport.supplyDrives(level, reactor);
        if (level.getBlockEntity(MSIV) instanceof MainSteamIsolationValveBlockEntity valve) valve.energy().receiveEnergy(20_000, false);
    }

    private static ReactorControllerBlockEntity reactor(MinecraftServer server) {
        if (!(level(server).getBlockEntity(REACTOR) instanceof ReactorControllerBlockEntity r) || !r.isFormed())
            throw new AssertionError("the reactor is not formed");
        return r;
    }

    /** The panel component with this label (searching the whole wall), or null. */
    private static PanelComponent find(MinecraftServer server, String label) {
        for (int row = 0; row < ROWS; row++)
            for (int col = 0; col < COLS; col++)
                if (level(server).getBlockEntity(wallAt(col, row)) instanceof PanelBlockEntity be)
                    for (PanelComponent c : be.owned()) if (label.equals(c.label())) return c;
        return null;
    }

    private static PanelComponent need(MinecraftServer server, String label) {
        PanelComponent c = find(server, label);
        if (c == null) throw new AssertionError("component " + label + " is missing from the panel");
        return c;
    }

    private static int countOf(MinecraftServer server, dev.bwr.mod.panel.component.ComponentType type) {
        int count = 0;
        for (int row = 0; row < ROWS; row++)
            for (int col = 0; col < COLS; col++)
                if (level(server).getBlockEntity(wallAt(col, row)) instanceof PanelBlockEntity be)
                    for (PanelComponent c : be.owned()) if (c.type() == type) count++;
        return count;
    }

    private static List<String> allLabels(MinecraftServer server) {
        List<String> labels = new ArrayList<>();
        for (int row = 0; row < ROWS; row++)
            for (int col = 0; col < COLS; col++)
                if (level(server).getBlockEntity(wallAt(col, row)) instanceof PanelBlockEntity be)
                    for (PanelComponent c : be.owned()) labels.add(c.label());
        return labels;
    }

    // ---- players

    private static String who(ServerPlayer player) {
        return player.getGameProfile().getName().endsWith("A") ? MpProtocol.A : MpProtocol.B;
    }

    @SubscribeEvent
    public static void joined(PlayerEvent.PlayerLoggedInEvent event) {
        if (!ON || !(event.getEntity() instanceof ServerPlayer player)) return;
        MinecraftServer server = player.getServer();
        String who = who(player);
        try {
            if (!built) {
                phase = Files.exists(MpProtocol.dir().resolve("phase1.expect")) ? 2 : 1;
                ServerLevel level = level(server);
                server.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
                server.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(false, server);
                level.setDayTime(6000);
                if (phase == 1) buildFixture(level);
                built = true;
                LOG.info("MP CHECK phase {} on a dedicated server, fixture {}", phase, phase == 1 ? "built" : "loaded from disk");
            }
            player.setGameMode(GameType.SURVIVAL);
            if (!player.getPersistentData().getBoolean("bwr_mp_kit")) {
                player.getInventory().clearContent();
                player.getInventory().setItem(0, new ItemStack(BwrItems.PANEL_WRENCH.get()));
                ItemStack linker = new ItemStack(BwrItems.PANEL_LINKER.get());
                linker.set(BwrDataComponents.LINKED_DEVICE.get(), GlobalPos.of(level(server).dimension(), REACTOR));
                player.getInventory().setItem(1, linker);
                player.getInventory().setItem(2, new ItemStack(BwrItems.LAMP.get(), 2));
                player.getInventory().setItem(3, new ItemStack(BwrItems.PUSHBUTTON.get(), 2));
                player.getPersistentData().putBoolean("bwr_mp_kit", true);
            }
            double[] at = STAND[who.equals(MpProtocol.A) ? 0 : 1];
            player.teleportTo(level(server), at[0], at[1], at[2], -20f, -8f);
            online.put(who, player);
            offlineCounts.remove(who);
            joinedAt.put(who, age);
            LOG.info("MP CHECK player {} ({}) joined at tick {}", player.getGameProfile().getName(), who, age);
        } catch (Exception e) {
            LOG.error("MP CHECK fixture failure", e);
            fail(server, "fixture: " + e);
        }
    }

    @SubscribeEvent
    public static void left(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!ON || !(event.getEntity() instanceof ServerPlayer player)) return;
        String who = who(player);
        offlineCounts.put(who, inventoryCounts(player));
        online.remove(who);
        LOG.info("MP CHECK player {} ({}) left at tick {}", player.getGameProfile().getName(), who, age);
    }

    // ---- the item ledger

    private static boolean ours(ItemStack stack) {
        return !stack.isEmpty() && BuiltInRegistries.ITEM.getKey(stack.getItem()).getNamespace().equals(BwrMod.MOD_ID);
    }

    private static void add(Map<String, Integer> counts, ItemStack stack) {
        if (ours(stack)) counts.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath(), stack.getCount(), Integer::sum);
    }

    private static Map<String, Integer> inventoryCounts(Player player) {
        Map<String, Integer> counts = new TreeMap<>();
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) add(counts, player.getInventory().getItem(i));
        add(counts, player.containerMenu.getCarried());
        return counts;
    }

    /**
     * Every bwr item there is: both players' inventories (or their last snapshot while offline), the item entities in the
     * world, and the panel, where every component counts as the one item it would drop. The fixture's own components are in
     * the baseline too, so a component that is taken off the panel moves from one place of the ledger to another and the
     * total stays the same: a duplicated or a lost item changes it.
     */
    private static Map<String, Integer> ledger(MinecraftServer server) {
        Map<String, Integer> total = new TreeMap<>();
        for (String who : List.of(MpProtocol.A, MpProtocol.B)) {
            Map<String, Integer> part = online.containsKey(who) ? inventoryCounts(online.get(who)) : offlineCounts.getOrDefault(who, Map.of());
            part.forEach((k, v) -> total.merge(k, v, Integer::sum));
        }
        ServerLevel level = level(server);
        for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, new AABB(MIN).inflate(200, 200, 200))) add(total, item.getItem());
        for (int row = 0; row < ROWS; row++)
            for (int col = 0; col < COLS; col++)
                if (level.getBlockEntity(wallAt(col, row)) instanceof PanelBlockEntity be)
                    for (PanelComponent c : be.owned()) add(total, PanelBlockEntity.stackFor(c));
        return total;
    }

    // ---- the conductor

    private interface Verify {
        void check(MinecraftServer server, Map<String, String> a, Map<String, String> b);
    }

    private static final class Step {
        final String name;
        final int timeout;
        Runnable begin = () -> { };
        BooleanSupplier ready = () -> true;
        Runnable end = () -> { };
        int age;
        boolean begun;

        Step(String name, int timeout) {
            this.name = name;
            this.timeout = timeout;
        }
    }

    private static final ArrayDeque<Step> QUEUE = new ArrayDeque<>();
    private static Step current;
    private static final Map<String, Object> MEMO = new HashMap<>();

    private static void action(String name, Runnable run) {
        Step s = new Step(name, 10);
        s.begin = run;
        QUEUE.add(s);
    }

    private static void pause(String name, int ticks) {
        Step s = new Step(name, ticks + 10);
        int[] left = {ticks};
        s.ready = () -> --left[0] <= 0;
        QUEUE.add(s);
    }

    private static void until(String name, int timeout, BooleanSupplier condition) {
        Step s = new Step(name, timeout);
        s.ready = condition;
        QUEUE.add(s);
    }

    /** Gives each client its command line, waits for both acks and runs {@code verify} on what they saw. */
    private static void command(String name, String a, String b, String shot, int timeout, Verify verify) {
        Step s = new Step(name, timeout);
        int[] mine = {0};
        s.begin = () -> {
            mine[0] = ++seq;
            Map<String, String> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("A.cmd", a);
            m.put("B.cmd", b);
            m.put("shot", shot == null ? "" : shot);
            MpProtocol.write(MpProtocol.step(mine[0]), m);
            LOG.info("MP CHECK step {} '{}': A [{}] B [{}]", mine[0], name, a, b);
        };
        s.ready = () -> MpProtocol.read(MpProtocol.ack(mine[0], MpProtocol.A)) != null
                && MpProtocol.read(MpProtocol.ack(mine[0], MpProtocol.B)) != null;
        s.end = () -> {
            Map<String, String> ackA = MpProtocol.read(MpProtocol.ack(mine[0], MpProtocol.A));
            Map<String, String> ackB = MpProtocol.read(MpProtocol.ack(mine[0], MpProtocol.B));
            // A client that could not carry out its command line says so; that is a failure of the check itself.
            check(ackA.getOrDefault("failure", "").isEmpty(), "client A could not do its command: " + ackA.get("failure"));
            check(ackB.getOrDefault("failure", "").isEmpty(), "client B could not do its command: " + ackB.get("failure"));
            verify.check(theServer, ackA, ackB);
        };
        QUEUE.add(s);
    }

    /** Every rod has reached its demand (a moving rod changes between the clients' look and the server's). */
    private static void settle(String name) {
        until(name + " (rods at rest)", 1200, () -> reactor(theServer).rods().getRodsNotAtDemandCount() == 0);
        pause(name + " (sync)", SYNC);
    }

    /** Waits for the rods to stop and everything to have reached the clients, then has both report what they see. */
    private static void observe(String name, String shot, Verify verify) {
        settle(name);
        command(name, "", "", shot, 400, verify);
    }

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        if (!ON || finished) return;
        MinecraftServer server = event.getServer();
        theServer = server;
        age++;
        if (age > 20 * 60 * 20) fail(server, "the whole check timed out (20 minutes)");
        if (age % 100 == 0 && built) keepSupplied(server);
        try {
            if (!started) {
                if (built && online.size() == 2 && age - joinedAt.get(MpProtocol.A) >= SETTLE && age - joinedAt.get(MpProtocol.B) >= SETTLE) {
                    started = true;
                    if (phase == 1) planPhase1(); else planPhase2();
                    LOG.info("MP CHECK both players are in: {} steps planned for phase {}", QUEUE.size(), phase);
                } else if (built && age % 600 == 0) {
                    LOG.info("MP CHECK waiting for both players (online: {})", online.keySet());
                }
                return;
            }
            if (current == null) {
                current = QUEUE.poll();
                if (current == null) return;
            }
            if (!current.begun) {
                current.begun = true;
                current.begin.run();
            }
            if (current.ready.getAsBoolean()) {
                Step done = current;
                current = null;
                done.end.run();
            } else if (++current.age > current.timeout) {
                fail(server, "step '" + current.name + "' timed out after " + current.timeout + " ticks");
            }
        } catch (AssertionError | RuntimeException e) {
            LOG.error("MP CHECK step failure", e);
            fail(server, (current != null ? "step '" + current.name + "': " : "") + e.getMessage());
        }
    }

    private static void result(String text) {
        if (finished) return;
        finished = true;
        LOG.info(text);
        MpProtocol.write(MpProtocol.dir().resolve("result.properties"), Map.of("result", text, "phase", String.valueOf(phase)));
    }

    private static void fail(MinecraftServer server, String reason) {
        result("MP CHECK FAIL " + reason);
        server.halt(false);
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    // ---- truth

    private static String serverDigest(MinecraftServer server, String label) {
        return MpProtocol.digest(((CoreDisplayData) need(server, label).data()).snapshot());
    }

    private static String reactorDigest(MinecraftServer server) {
        ReactorControllerBlockEntity r = reactor(server);
        StringBuilder sb = new StringBuilder("formed=true selected=").append(r.selectedRod()).append(" rods=")
                .append(r.core().getControlRodCount()).append(" notches=");
        for (int i = 0; i < r.core().getControlRodCount(); i++) sb.append(i > 0 ? "," : "").append(r.core().getRodNotchIndex(i));
        return sb.toString();
    }

    /** What both clients see on the FCD and the matrix must be exactly what the server's displays hold and the reactor is. */
    private static void sameDisplays(MinecraftServer server, String when, Map<String, String> a, Map<String, String> b) {
        String truth = reactorDigest(server);
        check(truth.equals(serverDigest(server, "FCD-1")), when + ": the server's FCD does not show the reactor: " + diff(truth, serverDigest(server, "FCD-1")));
        check(truth.equals(serverDigest(server, "RSM-1")), when + ": the server's matrix does not show the reactor");
        for (Map<String, String> client : List.of(a, b)) {
            String who = client == a ? "A" : "B";
            check("true".equals(client.get("online")), when + ": client " + who + " is not online");
            check(truth.equals(client.get("fcd")), when + ": client " + who + " FCD differs from the reactor: " + diff(truth, client.get("fcd")));
            check(truth.equals(client.get("matrix")), when + ": client " + who + " matrix differs from the reactor: " + diff(truth, client.get("matrix")));
        }
    }

    private static String diff(String expected, String seen) {
        if (seen == null) return "client reported nothing";
        String[] e = expected.split("notches=", 2), s = seen.split("notches=", 2);
        if (!e[0].equals(s[0])) return "'" + e[0] + "' vs '" + s[0] + "'";
        if (e.length < 2 || s.length < 2) return "no notches";
        String[] en = e[1].split(","), sn = s[1].split(",");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.max(en.length, sn.length) && sb.length() < 200; i++)
            if (i >= en.length || i >= sn.length || !en[i].equals(sn[i]))
                sb.append("rod ").append(i + 1).append(": ").append(i < en.length ? en[i] : "-").append(" vs ").append(i < sn.length ? sn[i] : "-").append("; ");
        return sb.length() == 0 ? "identical" : sb.toString();
    }

    private static int notch(MinecraftServer server, int rod) {
        return reactor(server).core().getRodNotchIndex(rod - 1);
    }

    private static int demand(MinecraftServer server, int rod) {
        return reactor(server).rods().getCommandedNotchIndex(rod - 1);
    }

    private static double switchPosition(MinecraftServer server) {
        return need(server, "SW-1").output(0);
    }

    private static void sameSwitch(MinecraftServer server, String when, Map<String, String> a, Map<String, String> b) {
        double truth = switchPosition(server);
        double dial = need(server, "DIAL-1").input(0);
        double selected = need(server, "DIAL-3").input(0);
        // The valve's stroke is slow and the meter follows a sampled signal, so the first meter may be a little behind the server
        // while it moves; the second one follows the selection, which is not moving at all.
        MainSteamIsolationValveBlockEntity valve = (MainSteamIsolationValveBlockEntity) level(server).getBlockEntity(MSIV);
        double slack = valve.getPosition() > 0.0 && valve.getPosition() < 1.0 ? 0.3 : 1e-6;
        for (Map<String, String> client : List.of(a, b)) {
            String who = client == a ? "A" : "B";
            check(Double.parseDouble(client.get("switch")) == truth, when + ": client " + who + " sees switch " + client.get("switch") + ", the server has " + truth);
            check(Math.abs(Double.parseDouble(client.get("dial1")) - dial) <= slack, when + ": client " + who + " meter DIAL-1 " + client.get("dial1") + " vs server " + dial);
            check(Double.parseDouble(client.get("dial3")) == selected, when + ": client " + who + " meter DIAL-3 (selected rod) " + client.get("dial3") + " vs server " + selected);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Integer> memoLedger() {
        return (Map<String, Integer>) MEMO.get("ledger");
    }

    private static void sameLedger(MinecraftServer server, String when) {
        Map<String, Integer> after = ledger(server);
        check(after.equals(memoLedger()), when + ": the bwr items changed: before " + memoLedger() + " after " + after);
    }

    private static int count(Map<String, Integer> ledger, String item) {
        return ledger.getOrDefault(item, 0);
    }

    // ---- scenarios

    private static void planPhase1() {
        MinecraftServer server = theServer;
        action("baseline ledger", () -> {
            MEMO.put("ledger", ledger(server));
            LOG.info("MP CHECK baseline item ledger (players and placed components) {}", memoLedger());
        });
        // 0. Both look at the fixture.
        observe("S0 both see the fixture", "s0-fixture", (s, a, b) -> {
            sameDisplays(s, "S0", a, b);
            sameSwitch(s, "S0", a, b);
            check(!a.get("labels").isEmpty() && a.get("labels").equals(b.get("labels")),
                    "S0: the two clients list different panel components: " + a.get("labels") + " vs " + b.get("labels"));
            check(reactor(s).selectedRod() == 0, "S0: a rod is selected before anyone selected one");
        });

        // 1. A selects a rod on the matrix and steps it twice, while B operates the switch: three timings.
        String[] orderName = {"same moment", "A first", "B first"};
        for (int variant = 0; variant < 3; variant++) {
            int v = variant;
            int rod = 88 + v;
            action("S1." + v + " record", () -> MEMO.put("d" + v, demand(server, rod)));
            String aWait = v == 2 ? "wait 8;" : "";
            String bWait = v == 1 ? "wait 8;" : v == 0 ? "wait 6;" : "";
            command("S1." + v + " A selects and steps, B operates the switch (" + orderName[v] + ")",
                    aWait + "matrix " + rod + ";wait 6;click PB-WD;wait 6;click PB-WD", bWait + "switch SW-1 " + (v % 2 == 0 ? 1 : -1),
                    v == 0 ? "s1-a-steps-b-switch" : null, 600, (s, a, b) -> { });
            observe("S1." + v + " observe", null, (s, a, b) -> {
                check(reactor(s).selectedRod() == rod, "S1." + v + ": selected rod is " + reactor(s).selectedRod() + ", A selected " + rod);
                check(demand(s, rod) == (Integer) MEMO.get("d" + v) + 2, "S1." + v + ": rod " + rod + " demand " + demand(s, rod)
                        + " after two steps from " + MEMO.get("d" + v) + " (a step was lost or doubled)");
                double want = v % 2 == 0 ? 1 : -1;
                check(switchPosition(s) == want, "S1." + v + ": the switch is at " + switchPosition(s) + ", B turned it to " + want + " (B's action was lost)");
                sameDisplays(s, "S1." + v, a, b);
                sameSwitch(s, "S1." + v, a, b);
                check(a.get("selectedSeen").equals(String.valueOf(rod)) && b.get("selectedSeen").equals(String.valueOf(rod)),
                        "S1." + v + ": A sees selection " + a.get("selectedSeen") + " and B sees " + b.get("selectedSeen") + ", expected " + rod);
            });
        }
        // 1b. The other way round: B selects and steps, A operates the switch.
        action("S1.3 record", () -> MEMO.put("d3", demand(server, 140)));
        command("S1.3 B selects and steps, A operates the switch", "wait 4;switch SW-1 0",
                "matrix 140;wait 6;click PB-WD;wait 6;click PB-IN;wait 6;click PB-WD", "s1-b-steps-a-switch", 600, (s, a, b) -> { });
        observe("S1.3 observe", null, (s, a, b) -> {
            check(reactor(s).selectedRod() == 140, "S1.3: selected rod is " + reactor(s).selectedRod());
            check(demand(s, 140) == (Integer) MEMO.get("d3") + 1, "S1.3: demand " + demand(s, 140) + " expected " + ((Integer) MEMO.get("d3") + 1));
            check(switchPosition(s) == 0, "S1.3: the switch is at " + switchPosition(s) + ", A turned it to 0");
            sameDisplays(s, "S1.3", a, b);
            sameSwitch(s, "S1.3", a, b);
        });

        // 2. Both bind the same component with the linker at once.
        for (int variant = 0; variant < 3; variant++) {
            int v = variant;
            String aKey = "pressure", bKey = "aprm_1_percent";
            String aWait = v == 2 ? "wait 8;" : "", bWait = v == 1 ? "wait 8;" : "";
            command("S2." + v + " both bind DIAL-2 (" + orderName[v] + ")", aWait + "bind DIAL-2 " + aKey, bWait + "bind DIAL-2 " + bKey,
                    v == 0 ? "s2-bind-race" : null, 400, (s, a, b) -> { });
            observe("S2." + v + " observe", null, (s, a, b) -> {
                PanelComponent dial = need(s, "DIAL-2");
                check(dial.source(0) instanceof PlantSource, "S2." + v + ": DIAL-2 is not bound at all");
                String key = ((PlantSource) dial.source(0)).key();
                check(key.equals(aKey) || key.equals(bKey), "S2." + v + ": DIAL-2 is bound to '" + key + "', neither player's choice");
                if (v == 1) check(key.equals(bKey), "S2.1: B's bind arrived later and must win, but DIAL-2 has '" + key + "'");
                if (v == 2) check(key.equals(aKey), "S2.2: A's bind arrived later and must win, but DIAL-2 has '" + key + "'");
                check(key.equals(a.get("dial2key")) && key.equals(b.get("dial2key")), "S2." + v + ": the clients see DIAL-2 bound to '"
                        + a.get("dial2key") + "' and '" + b.get("dial2key") + "', the server has '" + key + "'");
                check(dial.sinks(0).isEmpty(), "S2." + v + ": DIAL-2 grew an output sink");
                sameLedger(s, "S2." + v);
            });
        }

        // 3. Take and place components with the wrench and the panel packets.
        command("S3.a both remove PB-X at once", "remove PB-X;wait 10;walk fwd 14;wait 30;walk back 14", "remove PB-X;wait 10;walk fwd 14;wait 30;walk back 14", null, 600, (s, a, b) -> { });
        observe("S3.a observe", "s3-both-remove", (s, a, b) -> {
            check(find(s, "PB-X") == null, "S3.a: PB-X is still on the panel");
            Map<String, Integer> now = ledger(s);
            // The panel lost one component (it was counted in the baseline); the ledger must not have grown or shrunk:
            // that one pushbutton is now an item in a hand or on the ground, exactly once.
            check(now.equals(memoLedger()), "S3.a: removing one component with two players changed the item count: before " + memoLedger() + " after " + now);
        });
        command("S3.b A removes LAMP-A, B removes LAMP-B", "remove LAMP-A;wait 10;walk fwd 14;wait 30;walk back 14", "remove LAMP-B;wait 10;walk fwd 14;wait 30;walk back 14", null, 600, (s, a, b) -> { });
        observe("S3.b observe", null, (s, a, b) -> {
            check(find(s, "LAMP-A") == null && find(s, "LAMP-B") == null, "S3.b: a removed lamp is still on the panel");
            sameLedger(s, "S3.b");
        });
        command("S3.c both place a lamp on the same spot at once", "place lamp 8 1 0 0", "place lamp 8 1 0 0", "s3-both-place", 400, (s, a, b) -> { });
        observe("S3.c observe", null, (s, a, b) -> {
            sameLedger(s, "S3.c");
            int lamps = countOf(s, ComponentTypes.LAMP);
            check(lamps == 1, "S3.c: " + lamps + " lamps stand on the panel after two players placed on the same spot, expected 1");
            check(a.get("labels").equals(b.get("labels")), "S3.c: the clients disagree about the panel: " + a.get("labels") + " vs " + b.get("labels"));
        });
        command("S3.d A places, B places and renames", "place lamp 8 1 8 0;wait 10;renameat 8 1 8 0 LAMP-ZA",
                "place lamp 9 1 0 0;wait 10;renameat 9 1 0 0 LAMP-ZB", null, 400, (s, a, b) -> { });
        observe("S3.d observe", "s3-place-and-config", (s, a, b) -> {
            sameLedger(s, "S3.d");
            List<String> labels = allLabels(s);
            check(labels.size() == new java.util.HashSet<>(labels).size(), "S3.d: duplicate labels on the panel: " + labels);
            check(a.get("labels").equals(b.get("labels")), "S3.d: the clients disagree about the panel: " + a.get("labels") + " vs " + b.get("labels"));
        });

        // 4. A disconnects in the middle of a continuous withdraw and rejoins.
        action("S4 record", () -> MEMO.put("d4", demand(server, 100)));
        command("S4 A selects rod 100, holds withdraw and drops off the network while holding", "matrix 100;wait 6;holdnr PB-WDC 60;disconnect",
                "wait 2", "s4-before-drop", 600, (s, a, b) -> { });
        until("S4 A is gone", 400, () -> !online.containsKey(MpProtocol.A));
        pause("S4 the hold fail-safe fires", 60);
        action("S4 check stopped", () -> {
            ReactorControllerBlockEntity r = reactor(server);
            check(!r.selectedRodHeld(1) && !r.selectedRodHeld(-1), "S4: a continuous rod motion control is still held after its player disconnected");
            MEMO.put("n4b", demand(server, 100));
        });
        pause("S4 nothing auto-continues", 60);
        action("S4 check still stopped", () -> {
            check(demand(server, 100) == (Integer) MEMO.get("n4b"), "S4: rod 100's demand moved from " + MEMO.get("n4b") + " to " + demand(server, 100)
                    + " after the player was gone (it must not continue)");
            check((Integer) MEMO.get("n4b") > (Integer) MEMO.get("d4"), "S4: the hold never moved the rod (demand " + MEMO.get("n4b") + " from "
                    + MEMO.get("d4") + "): the scenario did not exercise a held control");
            check(reactor(server).selectedRod() == 100, "S4: the selection was " + reactor(server).selectedRod() + " after A left, expected 100 (it is reactor state)");
        });
        settle("S4 rod 100 comes to rest");
        command("S4 B looks while A is away, A rejoins", "reconnect", "wait 2", "s4-b-sees-rod-stopped", 1500, (s, a, b) -> {
            check(reactorDigest(s).equals(b.get("fcd")), "S4: B's FCD while A was away differs from the reactor: " + diff(reactorDigest(s), b.get("fcd")));
        });
        observe("S4 after A rejoined", "s4-a-rejoined", (s, a, b) -> {
            sameDisplays(s, "S4", a, b);
            check(a.get("selectedSeen").equals("100"), "S4: A sees selection " + a.get("selectedSeen") + " after rejoining, expected 100");
            sameLedger(s, "S4 rejoin");
        });

        // 5. B leaves and rejoins while A keeps operating.
        action("S5 record", () -> MEMO.put("d5", demand(server, 60)));
        command("S5 A keeps stepping, B leaves and rejoins",
                "matrix 60;wait 6;click PB-WD;wait 20;click PB-WD;wait 20;click PB-WD;wait 20;click PB-IN;wait 20;click PB-WD;wait 20;click PB-WD;wait 40;click PB-WD",
                "wait 15;disconnect;wait 40;reconnect", "s5-b-rejoined", 1800, (s, a, b) -> { });
        observe("S5 observe", null, (s, a, b) -> {
            check(demand(s, 60) == (Integer) MEMO.get("d5") + 5, "S5: rod 60 demand " + demand(s, 60) + " expected " + ((Integer) MEMO.get("d5") + 5)
                    + " (a step was lost while B was away)");
            sameDisplays(s, "S5", a, b);
            sameLedger(s, "S5");
        });

        // 6. Stop the server with both players on, and start it again (phase 2).
        action("S6 write what must survive", () -> writeExpectation(server));
        command("S6 both players learn the server is stopping", "expectrestart", "expectrestart", null, 200, (s, a, b) -> { });
        action("S6 stop", () -> {
            result("MP CHECK PHASE 1 DONE");
            server.halt(false);
        });
    }

    // ---- persistence

    /** What a restart must give back: labels, bindings, switch, selection, matrix, MSIV demand, rod demands, item ledger. */
    private static Map<String, String> expectation(MinecraftServer server) {
        Map<String, String> m = new LinkedHashMap<>();
        List<String> components = new ArrayList<>();
        for (int row = 0; row < ROWS; row++)
            for (int col = 0; col < COLS; col++)
                if (level(server).getBlockEntity(wallAt(col, row)) instanceof PanelBlockEntity be)
                    for (PanelComponent c : be.owned()) {
                        StringBuilder d = new StringBuilder(c.label()).append('|').append(c.type().id().getPath());
                        for (int i = 0; i < c.inputCount(); i++) d.append("|in").append(i).append('=').append(describe(c.source(i)));
                        for (int i = 0; i < c.outputCount(); i++)
                            for (var sink : c.sinks(i)) d.append("|out").append(i).append('=').append(describeSink(sink));
                        components.add(d.toString());
                    }
        java.util.Collections.sort(components);
        m.put("components", String.join(";", components));
        m.put("selected", String.valueOf(reactor(server).selectedRod()));
        m.put("switch", String.valueOf(switchPosition(server)));
        m.put("matrixLast", String.valueOf(((RodSelectMatrixData) need(server, "RSM-1").data()).lastPressed));
        m.put("msivDemand", String.valueOf(((MainSteamIsolationValveBlockEntity) level(server).getBlockEntity(MSIV)).isDemandOpen()));
        m.put("ledger", ledger(server).toString());
        StringBuilder demand = new StringBuilder();
        for (int r = 0; r < reactor(server).core().getControlRodCount(); r++)
            demand.append(r > 0 ? "," : "").append(reactor(server).rods().getCommandedNotchIndex(r));
        m.put("demand", demand.toString());
        return m;
    }

    private static String describe(dev.bwr.mod.panel.component.InputSource source) {
        return source instanceof PlantSource p ? "plant:" + p.device().toShortString() + ":" + p.key() : source.kind();
    }

    private static String describeSink(dev.bwr.mod.panel.component.OutputSink sink) {
        return sink instanceof PlantSink p ? "plant:" + p.device().toShortString() + ":" + p.key() + ":" + p.mode() + ":" + p.fixed() : sink.kind();
    }

    private static void writeExpectation(MinecraftServer server) {
        Map<String, String> m = expectation(server);
        m.put("seq", String.valueOf(seq + 1));
        MpProtocol.write(MpProtocol.dir().resolve("phase1.expect"), m);
        LOG.info("MP CHECK phase 1 expectation written: {} components, selected {}, switch {}", m.get("components").split(";").length,
                m.get("selected"), m.get("switch"));
    }

    private static void planPhase2() {
        MinecraftServer server = theServer;
        Map<String, String> expected = MpProtocol.read(MpProtocol.dir().resolve("phase1.expect"));
        seq = Integer.parseInt(expected.get("seq"));
        action("S7 what the restarted server loaded", () -> {
            Map<String, String> now = expectation(server);
            for (String key : List.of("components", "selected", "switch", "matrixLast", "msivDemand", "demand", "ledger"))
                check(expected.get(key).equals(now.get(key)), "S7: after the restart " + key + " is\n" + now.get(key) + "\nbut before it was\n" + expected.get(key));
            check(reactor(server).isFormed(), "S7: the reactor did not re-form after the restart");
        });
        observe("S7 both rejoined", "s7-after-restart", (s, a, b) -> {
            sameDisplays(s, "S7", a, b);
            sameSwitch(s, "S7", a, b);
            check(a.get("labels").equals(b.get("labels")), "S7: the clients disagree about the panel: " + a.get("labels") + " vs " + b.get("labels"));
            for (Map<String, String> client : List.of(a, b))
                check(client.get("selectedSeen").equals(expected.get("selected")), "S7: a client sees selection " + client.get("selectedSeen")
                        + ", before the restart it was " + expected.get("selected"));
            check(a.get("dial2key").equals(b.get("dial2key")) && !a.get("dial2key").isEmpty(), "S7: DIAL-2's binding did not come back on the clients");
        });
        command("S7 the clients can still operate after the restart", "matrix 33;wait 6;click PB-WD", "wait 40;switch SW-1 -1", "s7-operating", 400, (s, a, b) -> { });
        observe("S7 observe operating", null, (s, a, b) -> {
            check(reactor(s).selectedRod() == 33, "S7: A's selection after the restart did not take: " + reactor(s).selectedRod());
            check(switchPosition(s) == -1, "S7: B's switch after the restart did not take: " + switchPosition(s));
            sameDisplays(s, "S7 operating", a, b);
            sameSwitch(s, "S7 operating", a, b);
        });
        command("S7 clients finish", "finish", "finish", null, 200, (s, a, b) -> { });
        action("PASS", () -> {
            result("MP CHECK PASS");
            server.halt(false);
        });
    }
}
