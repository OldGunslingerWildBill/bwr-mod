package dev.bwr.mod.devtest;

import com.mojang.logging.LogUtils;
import dev.bwr.mod.panel.PanelBlock;
import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.PanelOps;
import dev.bwr.mod.panel.client.RecorderClientStats;
import dev.bwr.mod.panel.component.ComponentTypes;
import dev.bwr.mod.panel.component.Footprint;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.component.PaperlessRecorderData;
import dev.bwr.mod.panel.component.RecorderKey;
import dev.bwr.mod.panel.component.RecorderScreen;
import dev.bwr.mod.panel.component.RecorderStyle;
import dev.bwr.mod.panel.component.RecorderUi;
import dev.bwr.mod.panel.display.RecorderCanvas;
import dev.bwr.mod.panel.model.RecorderLayout;
import dev.bwr.mod.panel.wiring.PlantSource;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.steam.MainSteamIsolationValveBlock;
import dev.bwr.mod.steam.MainSteamIsolationValveBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.storage.ServerLevelData;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Opt-in real-client check of the paperless recorder ({@code -PbwrRecorderClientCheck}): a DX1000 and a DX2000 on a
 * panel wall in a disposable creative world, with the plant's signals (two main steam isolation valves) bound to their
 * first channels and a recorded history behind them, and a screenshot of every screen in white and black, the menu, the
 * FUNC strip, an alarm before and after ACK, both bezels (the DX2000's cover closed and open), written to
 * {@code run/turbineModelCheck/recorder-check/}. It also clicks the face keys through the same picking maths a player's
 * crosshair uses, and measures what four DX1000s at 12 channels and one DX2000 at 48 channels, 5 s per division, cost
 * per frame. Stage groups with {@code -PbwrRecorderClientStages=shots,keys,performance}. Not shipped: the devtest
 * package is excluded from the release jar.
 */
@EventBusSubscriber(modid = "bwr", value = Dist.CLIENT)
public final class RecorderClientCheck {
    private static final int FACE_Z = 40;
    private static final int TOP_Y = 113;
    private static final BlockPos ROOT = new BlockPos(40, 112, FACE_Z);
    private static final String STAGES = System.getProperty("bwr.recorderClientStages", "shots,keys,performance");

    private record Shot(String name, int target, boolean close, Consumer<Rig> server, Consumer<Rig> client, Predicate<Rig> ready) {
    }

    /** The server's recorders and valves, and the client's view of the same components. */
    static final class Rig {
        ServerLevel level;
        PanelBlockEntity be1;
        PanelBlockEntity be2;
        PanelComponent s1;
        PanelComponent s2;
        MainSteamIsolationValveBlockEntity valveA;
        MainSteamIsolationValveBlockEntity valveB;
        Random noise = new Random(3);
        long driven;

        PaperlessRecorderData d1() {
            return (PaperlessRecorderData) s1.data();
        }

        PaperlessRecorderData d2() {
            return (PaperlessRecorderData) s2.data();
        }

        PanelComponent c(Minecraft mc, int which) {
            BlockPos pos = which == 1 ? be1.getBlockPos() : be2.getBlockPos();
            int id = which == 1 ? s1.id() : s2.id();
            return mc.level != null && mc.level.getBlockEntity(pos) instanceof PanelBlockEntity be ? be.component(id) : null;
        }
    }

    private static final Rig RIG = new Rig();
    private static final List<Shot> SHOTS = new ArrayList<>();
    private static boolean started, built, requested, resynced;
    private static int age, joined, shot, frames, waiting;
    private static boolean performancePhase;

    private RecorderClientCheck() {
    }

    static Rig rig() {
        return RIG;
    }

    private static boolean wants(String group) {
        for (String g : STAGES.split(",")) {
            if (g.trim().equals(group)) {
                return true;
            }
        }
        return false;
    }

    // ---- the shots

    private static Shot screen(String name, int target, boolean close, RecorderScreen screen, String background, String layout) {
        return new Shot(name, target, close, rig -> {
            PaperlessRecorderData d = target == 1 ? rig.d1() : rig.d2();
            d.background = background;
            d.layout = layout;
            d.setScreen(screen);
            (target == 1 ? rig.be1 : rig.be2).syncFull();
        }, null, rig -> {
            PanelComponent c = rig.c(Minecraft.getInstance(), target);
            if (c == null) {
                return false;
            }
            PaperlessRecorderData d = (PaperlessRecorderData) c.data();
            return d.screen == screen && d.background.equals(background) && d.layout.equals(layout);
        });
    }

    private static Shot quad(String name, int quad, String background) {
        return new Shot(name, 2, true, rig -> {
            rig.d2().background = background;
            rig.d2().setQuad(quad);
            rig.be2.syncFull();
        }, null, rig -> {
            PanelComponent c = rig.c(Minecraft.getInstance(), 2);
            return c != null && ((PaperlessRecorderData) c.data()).screen == RecorderScreen.FOUR_PANEL
                    && ((PaperlessRecorderData) c.data()).quad == quad && ((PaperlessRecorderData) c.data()).background.equals(background);
        });
    }

    private static void buildShots() {
        if (wants("shots")) {
            for (String bg : new String[] {"white", "black"}) {
                SHOTS.add(screen("dx1000-trend-" + bg, 1, true, RecorderScreen.TREND, bg, "horizontal"));
                SHOTS.add(screen("dx1000-digital-" + bg, 1, true, RecorderScreen.DIGITAL, bg, "horizontal"));
                SHOTS.add(screen("dx1000-bar-" + bg, 1, true, RecorderScreen.BAR, bg, "horizontal"));
                SHOTS.add(screen("dx1000-overview-" + bg, 1, true, RecorderScreen.OVERVIEW, bg, "horizontal"));
                SHOTS.add(screen("dx1000-alarmsummary-" + bg, 1, true, RecorderScreen.ALARM_SUMMARY, bg, "horizontal"));
            }
            SHOTS.add(screen("dx1000-trend-vertical-white", 1, true, RecorderScreen.TREND, "white", "vertical"));
            SHOTS.add(screen("dx1000-trend-wide-white", 1, true, RecorderScreen.TREND, "white", "wide"));
            SHOTS.add(new Shot("dx1000-menu-white", 1, true, rig -> rig.d1().setScreen(RecorderScreen.TREND), rig -> {
                RecorderUi ui = RecorderClientStats.ui(rig.c(Minecraft.getInstance(), 1));
                ui.mode = RecorderUi.Mode.NONE;
                ui.press(RecorderKey.DISP, (PaperlessRecorderData) rig.c(Minecraft.getInstance(), 1).data());
            }, rig -> true));
            SHOTS.add(new Shot("dx1000-func-white", 1, true, null, rig -> {
                RecorderUi ui = RecorderClientStats.ui(rig.c(Minecraft.getInstance(), 1));
                PaperlessRecorderData d = (PaperlessRecorderData) rig.c(Minecraft.getInstance(), 1).data();
                ui.press(RecorderKey.ESC, d);
                ui.press(RecorderKey.FUNC, d);
            }, rig -> true));
            SHOTS.add(new Shot("dx1000-alarm-unack-lit", 1, true, null, rig -> {
                RecorderUi ui = RecorderClientStats.ui(rig.c(Minecraft.getInstance(), 1));
                ui.press(RecorderKey.ESC, (PaperlessRecorderData) rig.c(Minecraft.getInstance(), 1).data());
            }, rig -> blinkPhase(true) && rig.c(Minecraft.getInstance(), 1) != null
                    && ((PaperlessRecorderData) rig.c(Minecraft.getInstance(), 1).data()).unacknowledgedCount() > 0));
            SHOTS.add(new Shot("dx1000-alarm-unack-dark", 1, true, null, null, rig -> blinkPhase(false)));
            SHOTS.add(new Shot("dx1000-alarm-acked", 1, true, rig -> {
                rig.d1().acknowledgeAll();
            }, null, rig -> rig.c(Minecraft.getInstance(), 1) != null
                    && ((PaperlessRecorderData) rig.c(Minecraft.getInstance(), 1).data()).unacknowledgedCount() == 0));
            for (String bg : new String[] {"white", "black"}) {
                for (RecorderScreen screen : new RecorderScreen[] {RecorderScreen.TREND, RecorderScreen.DIGITAL, RecorderScreen.BAR,
                        RecorderScreen.OVERVIEW, RecorderScreen.ALARM_SUMMARY, RecorderScreen.CIRCULAR}) {
                    if (bg.equals("black") && (screen == RecorderScreen.OVERVIEW || screen == RecorderScreen.ALARM_SUMMARY)) {
                        continue;
                    }
                    SHOTS.add(screen("dx2000-" + screen.id().replace("_", "") + "-" + bg, 2, true, screen, bg, "horizontal"));
                }
                SHOTS.add(quad("dx2000-fourpanel-mix-" + bg, 0, bg));
                if (bg.equals("white")) {
                    SHOTS.add(quad("dx2000-fourpanel-trend", 1, bg));
                    SHOTS.add(quad("dx2000-fourpanel-digital", 2, bg));
                    SHOTS.add(quad("dx2000-fourpanel-bar", 3, bg));
                }
            }
            SHOTS.add(new Shot("dx2000-menu-white", 2, true, rig -> {
                rig.d2().background = "white";
                rig.d2().setScreen(RecorderScreen.TREND);
                rig.d2().coverOpen = true;
                rig.be2.syncFull();
            }, rig -> {
                PaperlessRecorderData d = (PaperlessRecorderData) rig.c(Minecraft.getInstance(), 2).data();
                RecorderUi ui = RecorderClientStats.ui(rig.c(Minecraft.getInstance(), 2));
                ui.mode = RecorderUi.Mode.NONE;
                ui.press(RecorderKey.DISP, d);
                ui.cursor = 6;
            }, rig -> rig.c(Minecraft.getInstance(), 2) != null
                    && ((PaperlessRecorderData) rig.c(Minecraft.getInstance(), 2).data()).screen == RecorderScreen.TREND
                    && ((PaperlessRecorderData) rig.c(Minecraft.getInstance(), 2).data()).background.equals("white")));
            SHOTS.add(new Shot("dx2000-func-white", 2, true, null, rig -> {
                PaperlessRecorderData d = (PaperlessRecorderData) rig.c(Minecraft.getInstance(), 2).data();
                RecorderUi ui = RecorderClientStats.ui(rig.c(Minecraft.getInstance(), 2));
                ui.press(RecorderKey.ESC, d);
                ui.press(RecorderKey.FUNC, d);
            }, rig -> true));
            // the bezels
            SHOTS.add(new Shot("dx1000-bezel", 1, false, null, rig -> {
                RecorderUi ui = RecorderClientStats.ui(rig.c(Minecraft.getInstance(), 1));
                ui.mode = RecorderUi.Mode.NONE;
            }, rig -> true));
            SHOTS.add(new Shot("dx2000-bezel-cover-closed", 2, false, rig -> {
                rig.d2().coverOpen = false;
                rig.d2().setScreen(RecorderScreen.OVERVIEW);
                rig.be2.syncFull();
            }, rig -> RecorderClientStats.ui(rig.c(Minecraft.getInstance(), 2)).mode = RecorderUi.Mode.NONE, rig -> rig.c(Minecraft.getInstance(), 2) != null
                    && !((PaperlessRecorderData) rig.c(Minecraft.getInstance(), 2).data()).coverOpen));
            SHOTS.add(new Shot("dx2000-bezel-cover-open", 2, false, rig -> {
                rig.d2().toggleCover();
            }, null, rig -> rig.c(Minecraft.getInstance(), 2) != null
                    && ((PaperlessRecorderData) rig.c(Minecraft.getInstance(), 2).data()).coverOpen));
        }
    }

    private static boolean blinkPhase(boolean on) {
        Minecraft mc = Minecraft.getInstance();
        long t = mc.level == null ? 0 : mc.level.getGameTime();
        return ((t / 10 & 1) == 0) == on;
    }

    // ---- fixture

    private static BlockPos panel(int column, int row) {
        return ROOT.relative(Direction.WEST, column).below(row);
    }

    /** Builds the platform, the wall, both recorders with their history, and the valves. Server thread. */
    private static void build(ServerLevel level, int dx1000Count) {
        ((ServerLevelData) level.getLevelData()).setGameTime(200_000L);
        level.setDayTime(6000);
        for (BlockPos p : BlockPos.betweenClosed(new BlockPos(20, ROOT.getY() - 6, 20), new BlockPos(70, ROOT.getY() - 6, 60))) {
            level.setBlock(p, Blocks.SMOOTH_STONE.defaultBlockState(), 2);
        }
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 7; column++) {
                level.setBlock(panel(column, row), BwrBlocks.CONTROL_PANEL_VERTICAL.get().defaultBlockState()
                        .setValue(PanelBlock.FACING, Direction.NORTH), 3);
            }
        }
        RIG.level = level;
        RIG.be1 = (PanelBlockEntity) level.getBlockEntity(panel(0, 0));
        RIG.s1 = PanelOps.placeDirect(level, panel(0, 0), 0, 0, ComponentTypes.PAPERLESS_RECORDER, Footprint.of(32, 32));
        RIG.be2 = (PanelBlockEntity) level.getBlockEntity(panel(3, 0));
        RIG.s2 = PanelOps.placeDirect(level, panel(3, 0), 0, 0, ComponentTypes.PAPERLESS_RECORDER, Footprint.of(48, 48));
        if (RIG.s1 == null || RIG.s2 == null) {
            throw new AssertionError("recorders did not fit");
        }
        RIG.valveA = valve(level, new BlockPos(48, 112, 46));
        RIG.valveB = valve(level, new BlockPos(52, 112, 46));
        fillRecorder(RIG.be1, RIG.s1, 12, 4);
        fillRecorder(RIG.be2, RIG.s2, 48, 4);
        // the plant's signals on the first channels: the valves' positions and demands (0 to 100 percent)
        bind(RIG.be1, RIG.s1, 0, new BlockPos(48, 112, 46), "position");
        bind(RIG.be1, RIG.s1, 1, new BlockPos(48, 112, 46), "demand_open");
        bind(RIG.be1, RIG.s1, 2, new BlockPos(52, 112, 46), "position");
        bind(RIG.be1, RIG.s1, 3, new BlockPos(52, 112, 46), "demand_open");
        bind(RIG.be2, RIG.s2, 0, new BlockPos(48, 112, 46), "position");
        bind(RIG.be2, RIG.s2, 1, new BlockPos(52, 112, 46), "position");
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 7; column++) {
                ((PanelBlockEntity) level.getBlockEntity(panel(column, row))).syncFull();
            }
        }
    }

    private static MainSteamIsolationValveBlockEntity valve(ServerLevel level, BlockPos root) {
        var block = BwrBlocks.MSIV.get();
        var state = block.placementState().setValue(MainSteamIsolationValveBlock.FACING, Direction.NORTH);
        level.setBlock(root, state, 3);
        block.setPlacedBy(level, root, state, null, new ItemStack(block));
        var valve = (MainSteamIsolationValveBlockEntity) level.getBlockEntity(root);
        valve.setDemandOpen(false);
        valve.tickValve(5);
        return valve;
    }

    private static void bind(PanelBlockEntity be, PanelComponent rec, int port, BlockPos device, String key) {
        rec.setSource(port, new PlantSource(device, key, 100.0, 0.0));
        be.resample(rec, true);
    }

    private static void fillRecorder(PanelBlockEntity be, PanelComponent rec, int channels, int interval) {
        PaperlessRecorderData data = (PaperlessRecorderData) rec.data();
        RecorderScenario.configure(data, channels, "white", interval);
        data.channel(0).tag = "MSIV A POS";
        data.channel(0).unit = "%";
        data.channel(0).alarms[0].type = "off";
        data.channel(1).tag = data.style == RecorderStyle.DX2000 ? "MSIV B POS" : "MSIV A DEM";
        data.channel(1).unit = "%";
        data.channel(1).hi = 100;
        data.channel(1).dec = 0;
        data.channel(1).alarms[0].type = "off";
        data.channel(0).hi = 100;
        // alarms on channels the pretend plant drives, so some are on and unacknowledged from the start
        data.channel(4).alarms[0].type = "L";
        data.channel(4).alarms[0].value = 9.0;
        data.channel(5).alarms[0].type = "H";
        data.channel(5).alarms[0].value = 60.0;
        if (data.style == RecorderStyle.DX1000) {
            data.channel(2).tag = "MSIV B POS";
            data.channel(2).unit = "%";
            data.channel(2).hi = 100;
            data.channel(3).tag = "MSIV B DEM";
            data.channel(3).unit = "%";
            data.channel(3).hi = 100;
            data.channel(3).alarms[0].type = "off";
            data.channel(2).alarms[0].type = "H";
            data.channel(2).alarms[0].value = 50.0;
        }
        long now = be.getLevel().getGameTime();
        long end = RecorderScenario.fill(data, channels, now - 1);
        be.syncFull();
    }

    /** One server tick of the pretend plant: valves toggle, the other channels follow the scenario. */
    private static void drive(Rig rig, long tick) {
        if (rig.level == null) {
            return;
        }
        if (tick % 200 == 0) {
            boolean open = (tick / 200 & 1) == 1;
            rig.valveA.setDemandOpen(open);
            rig.valveB.setDemandOpen(!open);
        }
        rig.valveA.energy().receiveEnergy(100_000, false);
        rig.valveB.energy().receiveEnergy(100_000, false);
        rig.valveA.tickValve(0.05);
        rig.valveB.tickValve(0.05);
        for (int pass = 0; pass < 2; pass++) {
            PanelBlockEntity be = pass == 0 ? rig.be1 : rig.be2;
            PanelComponent rec = pass == 0 ? rig.s1 : rig.s2;
            int channels = rec.inputCount();
            int first = pass == 0 ? 4 : 2;
            for (int c = first; c < channels; c++) {
                be.setInput(rec, c, RecorderScenario.value(c, 440, rig.noise));
            }
        }
    }

    // ---- camera

    static double[] face(int target, boolean close) {
        // world x/y of the screen centre (close) or the bezel centre, and the distance for each framing
        if (target == 1) {
            double x = 41 - (close ? 2.8345 : 2.8345) / RecorderLayout.DX1000_INCHES * 2.0;
            double y = TOP_Y - (close ? 2.380 : 2.8345) / RecorderLayout.DX1000_INCHES * 2.0;
            return new double[] {x, y, close ? 1.25 : 2.5};
        }
        double x = 38 - 5.6693 / RecorderLayout.DX2000_INCHES * 3.0;
        double y = TOP_Y - (close ? 4.666 : 5.6693) / RecorderLayout.DX2000_INCHES * 3.0;
        return new double[] {x, y, close ? 1.75 : 3.4};
    }

    static void teleport(Minecraft mc, double x, double y, double z) {
        var server = mc.getSingleplayerServer();
        var uuid = mc.player.getUUID();
        server.execute(() -> {
            var player = server.getPlayerList().getPlayer(uuid);
            player.setNoGravity(true);
            player.getAbilities().flying = true;
            player.onUpdateAbilities();
            player.connection.teleport(x, y - player.getEyeHeight(), z, 0.0f, 0.0f);
        });
    }

    // ---- driver

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) throws Exception {
        if (!Boolean.getBoolean("bwr.recorderClientCheck")) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (++age > 20_000) {
            throw new IllegalStateException("Recorder client check timed out at shot " + shot);
        }
        if (!started && mc.screen != null && mc.getOverlay() == null) {
            started = true;
            mc.options.pauseOnLostFocus = false;
            mc.options.guiScale().set(2);
            mc.options.enableVsync().set(false);
            mc.options.framerateLimit().set(260);
            mc.options.fov().set(60);
            mc.options.renderDistance().set(8);
            buildShots();
            mc.createWorldOpenFlows().createFreshLevel("bwr-recorder-check-" + System.currentTimeMillis(),
                    new LevelSettings("Recorder check", GameType.CREATIVE, false, Difficulty.PEACEFUL, true, new GameRules(),
                            WorldDataConfiguration.DEFAULT),
                    new WorldOptions(0, false, false),
                    r -> r.registryOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).createWorldDimensions(), mc.screen);
            return;
        }
        if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null || ++joined < 100) {
            return;
        }
        var server = mc.getSingleplayerServer();
        if (!built) {
            built = true;
            mc.setScreen(null);
            mc.options.hideGui = true;
            server.execute(() -> build(server.overworld(), 4));
            return;
        }
        if (RIG.level == null || mc.level.getBlockEntity(panel(3, 0)) == null) {
            return;
        }
        if (!resynced) {
            // The blocks were placed in the tick the components were, so their clients' block entities did not exist yet when the
            // full states went out: send them again now that the client has the wall (a player placing components never needs this).
            resynced = true;
            server.execute(() -> {
                for (int row = 0; row < 3; row++) {
                    for (int column = 0; column < 7; column++) {
                        ((PanelBlockEntity) RIG.level.getBlockEntity(panel(column, row))).syncFull();
                    }
                }
            });
            return;
        }
        long gameTime = server.overworld().getGameTime();
        server.execute(() -> drive(RIG, gameTime));
        if (performancePhase) {
            if (RecorderPerformance.step(mc, server)) {
                LogUtils.getLogger().info("RECORDER CLIENT COMPLETE");
                mc.stop();
            }
            return;
        }
        if (shot >= SHOTS.size()) {
            if (wants("performance")) {
                performancePhase = true;
                return;
            }
            LogUtils.getLogger().info("RECORDER CLIENT COMPLETE");
            mc.stop();
            return;
        }
        Shot current = SHOTS.get(shot);
        if (!requested) {
            requested = true;
            frames = 0;
            double[] view = face(current.target(), current.close());
            teleport(mc, view[0], view[1], FACE_Z - view[2]);
            if (current.server() != null) {
                Consumer<Rig> action = current.server();
                server.execute(() -> action.accept(RIG));
            }
            return;
        }
        if (current.client() != null && frames == 0) {
            current.client().accept(RIG);
        }
        PanelComponent client = RIG.c(mc, current.target());
        double[] view = face(current.target(), current.close());
        boolean placed = Math.abs(mc.player.getX() - view[0]) < 0.05 && Math.abs(mc.player.getEyeY() - view[1]) < 0.05
                && Math.abs(mc.player.getZ() - (FACE_Z - view[2])) < 0.05;
        boolean ready = client != null && placed && current.ready().test(RIG);
        if (!ready) {
            frames = 0;
            if (++waiting % 200 == 0) {
                PaperlessRecorderData d = client == null ? null : (PaperlessRecorderData) client.data();
                LogUtils.getLogger().warn("RECORDER CLIENT waiting on {}: client={} placed={} ({} {} {}) unack={} screen={} blink={}", current.name(),
                        client != null, placed, mc.player.getX(), mc.player.getEyeY(), mc.player.getZ(),
                        d == null ? -1 : d.unacknowledgedCount(), d == null ? null : d.screen, blinkPhase(true));
            }
            return;
        }
        waiting = 0;
        // client ticks, not frames; a blink phase lasts only 10 ticks, so those shots settle quickly (the picture is repainted at once)
        if (++frames < (current.name().contains("alarm-unack") ? 3 : 45)) {
            return;
        }
        var dir = mc.gameDirectory.toPath().resolve("recorder-check");
        Files.createDirectories(dir);
        try (var image = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
            image.writeToFile(dir.resolve(current.name() + ".png"));
        }
        int[] picture = RecorderClientStats.picture(client);
        PaperlessRecorderData data = (PaperlessRecorderData) client.data();
        boolean white = data.background.equals("white");
        int bgColour = RecorderCanvas.abgr(white ? 0xFFFFFF : 0x000000);
        int bgPixels = 0;
        if (picture != null) {
            for (int p : picture) {
                if (p == bgColour) {
                    bgPixels++;
                }
            }
        }
        boolean needsBackground = current.name().contains("trend") || current.name().contains("digital") || current.name().contains("bar-")
                || current.name().contains("alarmsummary");
        if (picture == null) {
            throw new AssertionError("Recorder shot " + current.name() + ": the screen was never painted");
        }
        if (needsBackground && !current.name().contains("dx2000-circular") && bgPixels < picture.length / 8) {
            throw new AssertionError("Recorder shot " + current.name() + ": only " + bgPixels + " of " + picture.length
                    + " pixels have the " + data.background + " background");
        }
        if (!(RecorderClientStats.usesBakedModel(client))) {
            LogUtils.getLogger().warn("RECORDER CLIENT {} drawn with the procedural bezel (the baked model is not loaded)", current.name());
        }
        LogUtils.getLogger().info("RECORDER CLIENT PASS {} baked={} paints={} background={}px/{}", current.name(),
                RecorderClientStats.usesBakedModel(client), RecorderClientStats.paints(client), bgPixels, picture.length);
        shot++;
        requested = false;
    }

    @SubscribeEvent
    public static void frame(RenderFrameEvent.Post ignored) {
        if (!Boolean.getBoolean("bwr.recorderClientCheck") || !performancePhase) {
            return;
        }
        RecorderPerformance.frame();
    }

}
