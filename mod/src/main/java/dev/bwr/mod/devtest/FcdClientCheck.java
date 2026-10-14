package dev.bwr.mod.devtest;

import com.mojang.logging.LogUtils;
import dev.bwr.mod.BwrMod;
import dev.bwr.mod.panel.PanelBlock;
import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.PanelOps;
import dev.bwr.mod.panel.client.PanelRenderer;
import dev.bwr.mod.panel.component.ComponentTypes;
import dev.bwr.mod.panel.component.CoreDisplayData;
import dev.bwr.mod.panel.component.Footprint;
import dev.bwr.mod.panel.component.FullCoreDisplayData;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.wiring.PlantSink;
import dev.bwr.mod.panel.wiring.PlantSource;
import dev.bwr.mod.reactor.ReactorControllerBlockEntity;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.rods.ControlRodDriveBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Real-client check of the full core display and the rod select matrix against a real formed 185-rod core: frame
 * cost and screenshots. {@code -PbwrFcdClientCheck} runs it in the turbine model check client; stages are chosen with
 * {@code -PbwrFcdClientStages=empty,columbia,browns,matrix,columbia+matrix,...}. Every stage builds a flat floor, a
 * formed 15 x 15 compact reactor (185 rods) with the drives supplied, puts the core in a recognisable state (rods
 * spread over the stroke, a few drifting, one selected, LPRM chambers in range, upscale, failed and bypassed), builds a
 * panel wall with the displays bound to the reactor through the same binding path the linker uses, and measures mean
 * frame time over eight seconds from a fixed camera. {@code PANEL PERFORMANCE}-style lines are logged as
 * {@code FCD PERFORMANCE}; screenshots go to {@code fcd-check/<stage>.png} in the run directory.
 * Timing starts only after construction, client snapshots, overview section compilation and the requested camera
 * are ready. {@code -PbwrFcdReadinessProbe} holds a real refuelling menu on the first display stage for twelve seconds
 * to prove the old eleven-second deadline cannot capture it or count its frames as benchmark samples.
 *
 * <p>{@code -PbwrPanelImmediate} draws every vertex per frame, {@code -PbwrFcdLegendsPerFrame} keeps the block's solid
 * vertex buffer but lays the legends out every frame (what the panel cache did before it could hold text), and
 * {@code -PbwrFcdProcedural} ignores baked models: the three ways to get the numbers the cache is measured against.
 * Inert without the system property, and stripped from the release jar with the rest of the devtest package.
 */
@EventBusSubscriber(modid = BwrMod.MOD_ID, value = Dist.CLIENT)
public final class FcdClientCheck {
    /** One stage: which displays it has, how far the camera stands, and whether it fires a scram before the picture. */
    private record Stage(String name, boolean columbia, boolean browns, boolean matrix, double distance, boolean scram,
                         double focusX, double focusY) {
        Stage(String name, boolean columbia, boolean browns, boolean matrix, double distance, boolean scram) {
            this(name, columbia, browns, matrix, distance, scram, 0.0, 0.0);
        }
    }

    private static final List<Stage> ALL = List.of(
            new Stage("empty", false, false, false, 9, false),
            new Stage("columbia", true, false, false, 9, false),
            new Stage("browns", false, true, false, 9, false),
            new Stage("matrix", false, false, true, 9, false),
            new Stage("columbia+matrix", true, false, true, 11, false),
            new Stage("browns+matrix", false, true, true, 13, false),
            new Stage("columbia+matrix-far", true, false, true, 38, false),
            new Stage("browns+matrix-far", false, true, true, 38, false),
            new Stage("columbia-scram", true, false, false, 9, true),
            new Stage("columbia-close", true, false, false, 3, false),
            new Stage("browns-close", false, true, false, 3, false),
            new Stage("matrix-close", false, false, true, 3, false),
            new Stage("columbia-labels", true, false, false, 3, false),
            new Stage("nameplates", false, false, false, 3, false),
            // reading distance: a player standing a block and a half from the wall, looking at the core's middle and at a
            // spot beside it (focus offsets are blocks from the middle of the wall, x to the player's left, y up)
            new Stage("columbia-legends", true, false, false, 1.6, false),
            new Stage("columbia-legends-edge", true, false, false, 1.6, false, 1.4, 1.6),
            new Stage("matrix-legends", false, false, true, 1.6, false),
            new Stage("browns-legends", false, true, false, 1.6, false));

    private static final String OPTION = System.getProperty("bwr.fcdClientStages", "");
    private static final List<Stage> STAGES = chosen();
    private static boolean started, requested;
    private static int age, joined, stage;
    private static long began, lastFrame;
    private static final ArrayList<Double> FRAME_MS = new ArrayList<>();
    private static volatile Throwable failure;
    private static volatile boolean scrammed;
    private static long scramAt;
    /** Published only after the server finishes construction and sends the final teleport. */
    private record View(BlockPos wall, int cols, int rows, Vec3 feet, Vec3 overviewFeet) { }
    private static volatile View builtView, probeView;
    private static boolean warmed, sceneReady;
    private static int readyFrames;
    private static long requestedAt, probeOpenedAt;
    private static String waitingFor = "server fixture";
    /** Server-thread ownership: only this stage's previously unforced chunks are released. */
    private static final Set<ChunkPos> ownedChunks = new LinkedHashSet<>();
    private static ServerLevel fixtureLevel;
    /** Cache counters when the measured window began: nothing may be rebuilt while the picture is static. */
    private static int rebuildsAtWindow = -1, legendsAtWindow = -1;

    private FcdClientCheck() {}

    private static List<Stage> chosen() {
        if (OPTION.isBlank()) return ALL;
        List<Stage> picked = new ArrayList<>();
        for (String name : OPTION.split(",")) {
            Stage found = null;
            for (Stage candidate : ALL) if (candidate.name().equals(name.trim())) found = candidate;
            if (found == null) throw new IllegalArgumentException("Unknown FCD stage '" + name + "'");
            picked.add(found);
        }
        return picked;
    }

    /** Blocks the wall of a stage is high: the displays use the whole 8 block span they are given. */
    private static int rows(Stage stage) {
        return stage.name().equals("nameplates") ? 2 : stage.columbia() || stage.matrix() ? 8 : stage.browns() ? 5 : 4;
    }

    private static int offset(int index) {
        return (index + 1) * 5000;
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post ignored) {
        if (!Boolean.getBoolean("bwr.fcdClientCheck")) return;
        Minecraft mc = Minecraft.getInstance();
        if (failure != null) throw new IllegalStateException("FCD check build failed", failure);
        if (stage == STAGES.size()) return;
        if (++age > 600 + 1100 * STAGES.size()) throw new IllegalStateException("FCD check timed out at " + stage);
        if (!started && mc.screen != null && mc.getOverlay() == null) {
            started = true;
            mc.options.pauseOnLostFocus = false;
            mc.options.enableVsync().set(false);
            mc.options.framerateLimit().set(260);
            mc.options.renderDistance().set(8);
            mc.options.fov().set(60);
            mc.createWorldOpenFlows().createFreshLevel("bwr-fcd-check-" + System.currentTimeMillis(),
                    new LevelSettings("FCD check", GameType.CREATIVE, false, Difficulty.PEACEFUL,
                            true, new GameRules(), WorldDataConfiguration.DEFAULT),
                    new WorldOptions(0, false, false),
                    r -> r.registryOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).createWorldDimensions(), mc.screen);
            return;
        }
        if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null || ++joined < 40) return;
        Stage current = STAGES.get(stage);
        if (!requested) {
            requested = true;
            scrammed = false;
            lastFrame = 0;
            rebuildsAtWindow = -1;
            legendsAtWindow = -1;
            FRAME_MS.clear();
            began = 0;
            requestedAt = System.nanoTime();
            builtView = probeView = null;
            readyFrames = 0;
            warmed = sceneReady = false;
            probeOpenedAt = 0;
            waitingFor = "server fixture";
            int offset = offset(stage);
            var server = mc.getSingleplayerServer();
            var uuid = mc.player.getUUID();
            server.execute(() -> {
                try {
                    var player = server.getPlayerList().getPlayer(uuid);
                    if (player == null) throw new AssertionError("FCD check player missing");
                    player.closeContainer();
                    var level = player.serverLevel();
                    // the reactor stands south of the wall, the wall faces north toward the camera
                    BlockPos min = new BlockPos(20 + offset, 120, 70 + offset);
                    holdFixture(level, min);
                    for (BlockPos pos : BlockPos.betweenClosed(new BlockPos(min.getX() - 30, 117, min.getZ() - 60),
                            new BlockPos(min.getX() + 30, 117, min.getZ() + 20))) {
                        level.setBlock(pos, Blocks.SMOOTH_STONE.defaultBlockState(), 2);
                    }
                    BlockPos wall = new BlockPos(min.getX() + 5, 128, min.getZ() - 10);
                    double centreX = build(level, min, wall, current);
                    player.setNoGravity(true);
                    player.getAbilities().flying = true;
                    player.onUpdateAbilities();
                    level.setDayTime(6000);
                    int rows = rows(current);
                    double cameraY = wall.getY() - rows / 2.0 + 0.5 - player.getEyeHeight() + current.focusY();
                    int cols = current.name().equals("nameplates") ? 4
                            : 8 * ((current.columbia() ? 1 : 0) + (current.browns() ? 1 : 0) + (current.matrix() ? 1 : 0));
                    View view = new View(wall, cols, rows,
                            new Vec3(centreX + current.focusX(), cameraY, wall.getZ() - current.distance()),
                            new Vec3(centreX, cameraY - current.focusY(), wall.getZ() - Math.max(9, current.distance())));
                    if (stage == 0 && Boolean.getBoolean("bwr.fcdReadinessProbe")) {
                        // Reproduce the reported stale menu/side-on view, without editing a GUI class or blocking a tick.
                        var reactor = (ReactorControllerBlockEntity) level.getBlockEntity(min.offset(-1, 3, 7));
                        if (reactor == null) throw new AssertionError("Readiness probe needs a display stage first");
                        String refused = reactor.setVesselState(dev.bwr.mod.reactor.VesselState.REFUELING);
                        if (refused != null) throw new AssertionError("Readiness probe refuelling refused: " + refused);
                        player.connection.teleport(min.getX() - 3, min.getY() + 3, min.getZ() + 7, -90, 0);
                        dev.bwr.mod.gui.RefuellingMenu.open(player, reactor);
                        probeView = view;
                    } else {
                        player.connection.teleport(view.overviewFeet.x, view.overviewFeet.y, view.overviewFeet.z, 0, 0);
                        builtView = view;
                    }
                } catch (Throwable t) {
                    try { releaseFixture(); }
                    catch (Throwable cleanup) { t.addSuppressed(cleanup); }
                    failure = t;
                }
            });
            mc.options.hideGui = true;
        }
        if (probeView != null) {
            if (!(mc.player.containerMenu instanceof dev.bwr.mod.gui.RefuellingMenu) || mc.screen == null) return;
            if (probeOpenedAt == 0) probeOpenedAt = System.nanoTime();
            // Hold the actual stale screen past the old unconditional eleven-second screenshot deadline.
            if (System.nanoTime() - probeOpenedAt < 12_000_000_000L) return;
            if (began != 0 || !FRAME_MS.isEmpty()) throw new AssertionError("FCD sampled frames before view readiness");
            saveScreenshot(mc, "readiness-probe-before");
            LogUtils.getLogger().info("FCD READINESS PROBE rejected stale screen={} camera={} afterMs={} samples={}",
                    mc.screen.getClass().getSimpleName(), mc.gameRenderer.getMainCamera().getPosition(),
                    (System.nanoTime() - requestedAt) / 1_000_000, FRAME_MS.size());
            View view = probeView;
            probeView = null;
            mc.player.closeContainer();
            var server = mc.getSingleplayerServer();
            var uuid = mc.player.getUUID();
            server.execute(() -> {
                try {
                    var player = server.getPlayerList().getPlayer(uuid);
                    if (player == null) throw new AssertionError("FCD probe player missing");
                    player.closeContainer();
                    player.connection.teleport(view.overviewFeet.x, view.overviewFeet.y, view.overviewFeet.z, 0, 0);
                    builtView = view;
                } catch (Throwable t) {
                    failure = t;
                }
            });
        }
        View view = builtView;
        if (view == null) return;
        if (mc.getOverlay() != null) return;
        String sceneProblem = sceneProblem(mc, view);
        sceneReady = sceneProblem == null;
        if (!warmed && sceneReady) {
            // Close-up frusta can exclude a display's top/anchor section. Compile the whole wall
            // from an overview first, then hold the requested camera; never photograph the warm-up.
            warmed = true;
            LogUtils.getLogger().info("FCD READINESS stage={} overview sections and snapshots complete", current.name());
        }
        if (!sceneReady) waitingFor = sceneProblem;
        Vec3 feet = warmed ? view.feet : view.overviewFeet;
        if (mc.player.containerMenu != mc.player.inventoryMenu) mc.player.closeContainer();
        if (mc.screen != null) mc.setScreen(null);
        KeyMapping.releaseAll();
        mc.options.setCameraType(CameraType.FIRST_PERSON);
        mc.setCameraEntity(mc.player);
        mc.player.getAbilities().flying = true;
        mc.player.setNoGravity(true);
        mc.player.setDeltaMovement(Vec3.ZERO);
        mc.player.setPos(feet.x, feet.y, feet.z);
        mc.player.xo = feet.x;
        mc.player.yo = feet.y;
        mc.player.zo = feet.z;
        mc.player.setYRot(0);
        mc.player.setXRot(0);
        mc.player.yRotO = mc.player.xRotO = 0;
        mc.options.hideGui = true;
        mc.getToasts().clear();
        if (began == 0) {
            if (age % 100 == 0) {
                LogUtils.getLogger().info("FCD READINESS stage={} waiting for {}", current.name(), waitingFor);
                var server = mc.getSingleplayerServer();
                server.execute(() -> diagnose(server.overworld(), view, current.name()));
            }
            return;
        }
        long elapsed = System.nanoTime() - began;
        if (current.scram()) {
            // settle for six seconds, fire a scram, and take the picture 0.9 s later with the rods mid stroke
            if (!scrammed) {
                if (elapsed < 6_000_000_000L) return;
                scrammed = true;
                scramAt = System.nanoTime();
                var server = mc.getSingleplayerServer();
                int offset = offset(stage);
                server.execute(() -> {
                    if (server.overworld().getBlockEntity(new BlockPos(20 + offset - 1, 123, 70 + offset + 7))
                            instanceof ReactorControllerBlockEntity be) {
                        dev.bwr.mod.signal.DeviceCommands.reactorScram(be);
                    }
                });
                return;
            }
        }
    }

    /** Called only after rendering a verified world frame, never from a client tick with an old framebuffer. */
    private static void capture(Minecraft mc, Stage current) {
        String problem = readinessProblem(mc, builtView);
        if (problem != null || readyFrames < 20 || began == 0) {
            throw new AssertionError("FCD capture attempted before readiness: " + problem);
        }
        if (!current.scram() && FRAME_MS.size() < 100) throw new AssertionError("Too few frames for the FCD benchmark: " + FRAME_MS.size());
        boolean displays = current.columbia() || current.browns() || current.matrix();
        boolean cached = !Boolean.getBoolean("bwr.panelImmediate") && !"false".equals(System.getProperty("bwr.fcdLegendCache"));
        if (displays && cached && !current.scram()) {
            // The cache must hold the static picture: legends captured once, no buffer rebuilt while nothing changes.
            boolean far = current.distance() > 24;
            if (!far && PanelRenderer.debugCachedLegendVertices() <= 0) {
                throw new AssertionError("FCD check " + current.name() + ": no legend text is cached");
            }
            if (rebuildsAtWindow >= 0 && PanelRenderer.debugLegendRebuilds() != legendsAtWindow) {
                throw new AssertionError("FCD check " + current.name() + ": legends were laid out again during the measured window ("
                        + (PanelRenderer.debugLegendRebuilds() - legendsAtWindow) + " captures)");
            }
            if (rebuildsAtWindow >= 0 && PanelRenderer.debugRebuilds() - rebuildsAtWindow > 2) {
                throw new AssertionError("FCD check " + current.name() + ": block buffers were rebuilt "
                        + (PanelRenderer.debugRebuilds() - rebuildsAtWindow) + " times while the picture was static");
            }
        }
        double mean = 0, p50 = 0, p95 = 0;
        int n = FRAME_MS.size();
        if (n > 0) {
            Collections.sort(FRAME_MS);
            double sum = 0;
            for (double ms : FRAME_MS) sum += ms;
            mean = sum / n;
            p50 = FRAME_MS.get(n / 2);
            p95 = FRAME_MS.get((int) (n * .95));
        }
        LogUtils.getLogger().info("FCD PERFORMANCE stage={} frames={} meanMs={} p50Ms={} p95Ms={} fps={} immediate={} legendCache={} kits={}",
                current.name(), n, mean, p50, p95, mean == 0 ? 0 : 1000 / mean, Boolean.getBoolean("bwr.panelImmediate"),
                !"false".equals(System.getProperty("bwr.fcdLegendCache")), !"false".equals(System.getProperty("bwr.fcdKits")));
        LogUtils.getLogger().info("FCD PERFORMANCE stage={} gpu cache: {} blocks hold {} quads and {} legend vertices; {} buffers built so far in {} ms, {} legend captures",
                current.name(), PanelRenderer.debugCachedBlocks(), PanelRenderer.debugCachedQuads(), PanelRenderer.debugCachedLegendVertices(),
                PanelRenderer.debugRebuilds(), PanelRenderer.debugRebuildMillis(), PanelRenderer.debugLegendRebuilds());
        saveScreenshot(mc, current.name());
        LogUtils.getLogger().info("FCD CAPTURE VERIFIED stage={} screen=none camera={} yaw={} pitch={} readyFrames={} panels={}",
                current.name(), mc.gameRenderer.getMainCamera().getPosition(), mc.gameRenderer.getMainCamera().getYRot(),
                mc.gameRenderer.getMainCamera().getXRot(), readyFrames, builtView.cols * builtView.rows);
        requested = false;
        mc.getSingleplayerServer().execute(FcdClientCheck::releaseFixture);
        if (++stage == STAGES.size()) {
            LogUtils.getLogger().info("FCD PERFORMANCE COMPLETE");
            mc.stop();
        }
    }

    @SubscribeEvent
    public static void frame(RenderFrameEvent.Post ignored) {
        if (!Boolean.getBoolean("bwr.fcdClientCheck") || !requested) return;
        Minecraft mc = Minecraft.getInstance();
        String problem = readinessProblem(mc, builtView);
        if (problem != null) {
            waitingFor = problem;
            readyFrames = 0;
            began = lastFrame = 0;
            rebuildsAtWindow = legendsAtWindow = -1;
            FRAME_MS.clear();
            return;
        }
        long now = System.nanoTime();
        if (++readyFrames < 20) return;
        if (began == 0) {
            began = now;
            LogUtils.getLogger().info("FCD READINESS stage={} ready afterMs={} panels={} camera={}", STAGES.get(stage).name(),
                    (now - requestedAt) / 1_000_000, builtView.cols * builtView.rows, mc.gameRenderer.getMainCamera().getPosition());
        }
        if (now - began > 3_000_000_000L && lastFrame != 0 && !STAGES.get(stage).scram()) {
            if (rebuildsAtWindow < 0) {
                rebuildsAtWindow = PanelRenderer.debugRebuilds();
                legendsAtWindow = PanelRenderer.debugLegendRebuilds();
            }
            double delta = (now - lastFrame) / 1_000_000.0;
            if (delta > 0 && delta < 500) FRAME_MS.add(delta);
        }
        lastFrame = now;
        Stage current = STAGES.get(stage);
        if (current.scram() ? scrammed && now - scramAt >= 900_000_000L : now - began >= 11_000_000_000L) capture(mc, current);
    }

    private static String readinessProblem(Minecraft mc, View view) {
        if (view == null) return "server fixture and teleport";
        if (mc.player == null || mc.level == null) return "client level";
        if (!warmed || !sceneReady) return waitingFor;
        if (mc.screen != null || mc.getOverlay() != null || mc.player.containerMenu != mc.player.inventoryMenu) return "world screen";
        var camera = mc.gameRenderer.getMainCamera();
        if (!camera.isInitialized() || mc.getCameraEntity() != mc.player || !mc.options.getCameraType().isFirstPerson()
                || camera.getPosition().distanceToSqr(view.feet.add(0, mc.player.getEyeHeight(), 0)) > 1e-4
                || Math.abs(camera.getYRot()) > .01 || Math.abs(camera.getXRot()) > .01) return "rendered camera pose";
        return null;
    }

    private static String sceneProblem(Minecraft mc, View view) {
        int components = 0;
        for (int row = 0; row < view.rows; row++) for (int col = 0; col < view.cols; col++) {
            BlockPos pos = view.wall.relative(Direction.WEST, col).below(row);
            if (!(mc.level.getBlockEntity(pos) instanceof PanelBlockEntity panel)
                    || panel.getBlockState().getValue(PanelBlock.FACING) != Direction.NORTH) return "panel block " + pos;
            if (!mc.levelRenderer.isSectionCompiled(pos)) return "panel section " + pos;
            for (PanelComponent component : panel.owned()) {
                if (component.data() instanceof CoreDisplayData data
                        && (!data.snapshot().formed() || data.snapshot().cells().rodCount() != 185)) {
                    return "display snapshot " + pos + " type=" + component.type().id() + " formed="
                            + data.snapshot().formed() + " rods=" + data.snapshot().rodCount() + " input=" + component.input(0);
                }
                components++;
            }
        }
        int expected = STAGES.get(stage).name().equals("nameplates") ? 4 : view.cols / 8;
        if (components != expected) return "display components " + components + "/" + expected;
        return null;
    }

    private static void holdFixture(ServerLevel level, BlockPos min) {
        releaseFixture();
        fixtureLevel = level;
        // Includes the existing floor, entire reactor/drive field, wall and both photographic positions.
        for (int x = (min.getX() - 30) >> 4; x <= (min.getX() + 30) >> 4; x++) {
            for (int z = (min.getZ() - 60) >> 4; z <= (min.getZ() + 20) >> 4; z++) {
                ChunkPos chunk = new ChunkPos(x, z);
                if (!level.getForcedChunks().contains(chunk.toLong())) {
                    ownedChunks.add(chunk);
                    level.setChunkForced(x, z, true);
                }
                level.getChunk(x, z);
            }
        }
        LogUtils.getLogger().info("FCD FIXTURE holding {} owned chunks before construction at {}", ownedChunks.size(), min);
    }

    private static void releaseFixture() {
        if (fixtureLevel == null) return;
        for (ChunkPos chunk : List.copyOf(ownedChunks)) {
            fixtureLevel.setChunkForced(chunk.x, chunk.z, false);
            ownedChunks.remove(chunk);
        }
        fixtureLevel = null;
    }

    @SubscribeEvent
    public static void stopping(ServerStoppingEvent ignored) {
        if (Boolean.getBoolean("bwr.fcdClientCheck")) releaseFixture();
    }

    private static void diagnose(ServerLevel level, View view, String name) {
        if (!(level.getBlockEntity(view.wall) instanceof PanelBlockEntity panel)) {
            LogUtils.getLogger().info("FCD READINESS SERVER stage={} panel missing at {}", name, view.wall);
            return;
        }
        for (PanelComponent component : panel.owned()) {
            if (!(component.data() instanceof CoreDisplayData data)) continue;
            var source = component.source(0);
            var reactor = CoreDisplayData.boundReactor(level, component);
            LogUtils.getLogger().info("FCD READINESS SERVER stage={} panel={} removed={} tracked={} live={} type={} input={} snapshotFormed={} rods={} source={} reactor={} status={}",
                    name, view.wall, panel.isRemoved(), dev.bwr.mod.panel.wiring.PanelSampler.isTracked(panel), panel.hasLiveInputs(),
                    component.type().id(), component.input(0), data.snapshot().formed(), data.snapshot().rodCount(),
                    source instanceof PlantSource plant ? plant.device() : source.kind(),
                    reactor == null ? "unavailable" : reactor.getBlockPos(), reactor == null ? "unavailable" : reactor.statusLines());
        }
    }

    private static void saveScreenshot(Minecraft mc, String name) {
        try {
            var directory = mc.gameDirectory.toPath().resolve("fcd-check");
            Files.createDirectories(directory);
            try (var shot = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
                shot.writeToFile(directory.resolve(name + ".png"));
            }
        } catch (IOException ex) {
            throw new IllegalStateException("FCD check screenshot failed", ex);
        }
    }

    /**
     * Builds the stage and returns the world X the camera should stand at (the middle of the panel wall). The reactor
     * is only built when a display is wanted; the wall is one group of vertical panels with the displays on it, each
     * bound to the reactor through the same source and sink classes the linker makes.
     */
    private static double build(ServerLevel level, BlockPos min, BlockPos wall, Stage stage) throws Exception {
        if (stage.name().equals("nameplates")) {
            return nameplates(level, wall);
        }
        if (!stage.columbia() && !stage.browns() && !stage.matrix()) {
            return wall.getX();
        }
        ReactorControllerBlockEntity reactor = CompactCoreRegressionTests.build(level, min, 15, 15, false);
        if (!reactor.isFormed()) throw new AssertionError("FCD check reactor did not form: " + reactor.statusLines());
        FcdTestSupport.supplyDrives(level, reactor);
        demonstrate(level, reactor);
        int cols = 0;
        int rows = 0;
        int x = 0;
        int columbiaAt = -1;
        int brownsAt = -1;
        int matrixAt = -1;
        if (stage.columbia()) {
            columbiaAt = x;
            x += 8;
            rows = Math.max(rows, 8);
        }
        if (stage.browns()) {
            brownsAt = x;
            x += 8;
            rows = Math.max(rows, 5);
        }
        if (stage.matrix()) {
            matrixAt = x;
            x += 8;
            rows = Math.max(rows, 8);
        }
        cols = x;
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                level.setBlock(wall.relative(Direction.WEST, col).below(row), BwrBlocks.CONTROL_PANEL_VERTICAL.get().defaultBlockState()
                        .setValue(PanelBlock.FACING, Direction.NORTH), 3);
            }
        }
        if (columbiaAt >= 0) place(level, wall.relative(Direction.WEST, columbiaAt), ComponentTypes.FULL_CORE_DISPLAY, 128, 128, reactor,
                FullCoreDisplayData.COLUMBIA, stage.name().equals("columbia-labels") || stage.name().startsWith("columbia-legends"));
        if (brownsAt >= 0) place(level, wall.relative(Direction.WEST, brownsAt), ComponentTypes.FULL_CORE_DISPLAY, 128, 80, reactor,
                FullCoreDisplayData.BROWNS_FERRY, true);
        if (matrixAt >= 0) place(level, wall.relative(Direction.WEST, matrixAt), ComponentTypes.ROD_SELECT_MATRIX, 128, 128, reactor, null, true);
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                if (level.getBlockEntity(wall.relative(Direction.WEST, col).below(row)) instanceof PanelBlockEntity be) be.syncFull();
            }
        }
        LogUtils.getLogger().info("FCD PERFORMANCE stage={} built: {} rods, wall {} x {} blocks", stage.name(),
                reactor.core().getControlRodCount(), cols, rows);
        return wall.getX() - (cols - 1) / 2.0 + 0.5;
    }

    /** Four valve nameplates like the Columbia SRV plates: two lines, dot, number, red tag, pressure, and a red stripe on the ADS ones. */
    private static double nameplates(ServerLevel level, BlockPos wall) {
        for (int row = 0; row < 2; row++) {
            for (int col = 0; col < 4; col++) {
                level.setBlock(wall.relative(Direction.WEST, col).below(row), BwrBlocks.CONTROL_PANEL_VERTICAL.get().defaultBlockState()
                        .setValue(PanelBlock.FACING, Direction.NORTH), 3);
            }
        }
        String[] names = {"MS-RV-3A", "MS-RV-2B", "MS-RV-1D", "MS-RV-4D"};
        String[] numbers = {"12", "9", "13", "4"};
        String[] pressure = {"1121 PSIG", "1101 PSIG", "1101 PSIG", "1131 PSIG"};
        for (int i = 0; i < 4; i++) {
            BlockPos anchor = wall.relative(Direction.WEST, (i % 2) * 2).below(i / 2);
            PanelComponent plate = PanelOps.placeDirect(level, anchor, 0, 3, ComponentTypes.NAMEPLATE, Footprint.of(32, 10));
            if (plate == null) throw new AssertionError("nameplate placement failed " + i);
            dev.bwr.mod.panel.component.NameplateData data = (dev.bwr.mod.panel.component.NameplateData) plate.data();
            data.plate = 0xF2F2EC;
            data.ink = 0x30343A;
            data.setField("line1", "SAFETY RELIEF");
            data.setField("line2", names[i]);
            data.setField("cornerLeft", numbers[i]);
            data.setField("cornerRight", pressure[i]);
            data.setField("tag", "PAM");
            data.dot = true;
            data.dotColor = 0x2040C0;
            data.stripe = i == 3;
            plate.touch();
        }
        for (int row = 0; row < 2; row++) {
            for (int col = 0; col < 4; col++) {
                if (level.getBlockEntity(wall.relative(Direction.WEST, col).below(row)) instanceof PanelBlockEntity be) be.syncFull();
            }
        }
        return wall.getX() - 1.5;
    }

    private static void place(ServerLevel level, BlockPos anchor, dev.bwr.mod.panel.component.ComponentType type, int w, int h,
                              ReactorControllerBlockEntity reactor, String style, boolean labels) {
        PanelComponent component = PanelOps.placeDirect(level, anchor, 0, 0, type, Footprint.of(w, h));
        if (component == null) throw new AssertionError("FCD check placement failed for " + type.id() + " at " + anchor);
        PanelBlockEntity owner = (PanelBlockEntity) level.getBlockEntity(anchor);
        if (component.data() instanceof FullCoreDisplayData data && style != null) data.style = style;
        if (labels && component.data() instanceof CoreDisplayData data) {
            data.setText(type == ComponentTypes.ROD_SELECT_MATRIX ? "ROD SELECT" : "FULL CORE DISPLAY");
            if (component.data() instanceof FullCoreDisplayData full && FullCoreDisplayData.COLUMBIA.equals(style)) full.edgeNumbers = "on";
        }
        component.setSource(0, new PlantSource(reactor.getBlockPos(), "formed"));
        if (type == ComponentTypes.ROD_SELECT_MATRIX) component.addSink(0, new PlantSink(reactor.getBlockPos(), "select_rod", 1.0));
        owner.resample(component, true);
        component.touch();
        if (!((CoreDisplayData) component.data()).snapshot().formed()) throw new AssertionError("FCD check display did not bind");
    }

    /**
     * Puts the core in a recognisable state: rods spread over the whole stroke (stepped by hand, 1,300 steps of 50 ms,
     * rather than waiting a minute of game time), a few drives starved with a demand they cannot follow so DRIFT lights,
     * rod 88 selected, and the LPRM chambers of the six APRM channels set to read in range, upscale, downscale, with a
     * failed and a bypassed one.
     */
    private static void demonstrate(ServerLevel level, ReactorControllerBlockEntity reactor) {
        var rods = reactor.rods();
        var core = reactor.core();
        int count = core.getControlRodCount();
        int[] pattern = {24, 24, 24, 24, 0, 0, 12, 8, 16, 4};
        for (int r = 0; r < count; r++) {
            rods.setNotchDemand(r, pattern[(r * 7 + r / 15 * 3) % pattern.length]);
        }
        for (int i = 0; i < 1300; i++) {
            rods.preStep(0.05);
            core.step();
            rods.postStep();
        }
        for (int r : new int[] {17, 40, 41, 63, 100, 133, 150}) {
            if (level.getBlockEntity(reactor.structure().crdPositions().get(r)) instanceof ControlRodDriveBlockEntity drive) {
                drive.hardware().setEnergyStoredFe(0.0);
                drive.hardware().setWaterStoredMb(0.0);
            }
            rods.setNotchDemand(r, Math.min(24, rods.getCommandedNotchIndex(r) + 1));
        }
        reactor.selectRod(88);
        double[] gamma = {40.0, 60.0, 30.0, 150.0, 45.0, 2.0};
        for (int channel = 0; channel < gamma.length; channel++) {
            core.getAveragePowerRangeMonitor(channel).setGammaBackgroundPercent(gamma[channel]);
        }
        core.getAveragePowerRangeMonitor(0).setLprmFailed(3, true);
        core.getAveragePowerRangeMonitor(1).setLprmBypassed(4, true);
        core.getAveragePowerRangeMonitor(2).setLprmFailed(9, true);
    }
}
