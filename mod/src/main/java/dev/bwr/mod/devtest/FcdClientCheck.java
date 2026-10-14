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
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Real-client check of the full core display and the rod select matrix against a real formed 185-rod core: frame
 * cost and screenshots. {@code -PbwrFcdClientCheck} runs it in the turbine model check client; stages are chosen with
 * {@code -PbwrFcdClientStages=empty,columbia,browns,matrix,columbia+matrix,...}. Every stage builds a flat floor, a
 * formed 15 x 15 compact reactor (185 rods) with the drives supplied, puts the core in a recognisable state (rods
 * spread over the stroke, a few drifting, one selected, LPRM chambers in range, upscale, failed and bypassed), builds a
 * panel wall with the displays bound to the reactor through the same binding path the linker uses, and measures mean
 * frame time over eight seconds from a fixed camera. {@code PANEL PERFORMANCE}-style lines are logged as
 * {@code FCD PERFORMANCE}; screenshots go to {@code fcd-check/<stage>.png} in the run directory.
 *
 * <p>{@code -PbwrPanelImmediate} draws every vertex per frame, {@code -PbwrFcdLegendsPerFrame} keeps the block's solid
 * vertex buffer but lays the legends out every frame (what the panel cache did before it could hold text), and
 * {@code -PbwrFcdProcedural} ignores baked models: the three ways to get the numbers the cache is measured against.
 * Inert without the system property, and stripped from the release jar with the rest of the devtest package.
 */
@EventBusSubscriber(modid = BwrMod.MOD_ID, value = Dist.CLIENT)
public final class FcdClientCheck {
    /** One stage: which displays it has, how far the camera stands, and whether it fires a scram before the picture. */
    private record Stage(String name, boolean columbia, boolean browns, boolean matrix, int distance, boolean scram) {
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
            new Stage("nameplates", false, false, false, 3, false));

    private static final String OPTION = System.getProperty("bwr.fcdClientStages", "");
    private static final List<Stage> STAGES = chosen();
    private static boolean started, requested;
    private static int age, joined, stage;
    private static long began, lastFrame;
    private static final ArrayList<Double> FRAME_MS = new ArrayList<>();
    private static volatile Throwable failure;
    private static volatile boolean scrammed;
    private static long scramAt;
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

    private static int offset(int index) {
        return (index + 1) * 5000;
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post ignored) {
        if (!Boolean.getBoolean("bwr.fcdClientCheck")) return;
        Minecraft mc = Minecraft.getInstance();
        if (failure != null) throw new IllegalStateException("FCD check build failed", failure);
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
            began = System.nanoTime();
            int offset = offset(stage);
            var server = mc.getSingleplayerServer();
            var uuid = mc.player.getUUID();
            server.execute(() -> {
                try {
                    var player = server.getPlayerList().getPlayer(uuid);
                    var level = player.serverLevel();
                    // the reactor stands south of the wall, the wall faces north toward the camera
                    BlockPos min = new BlockPos(20 + offset, 120, 70 + offset);
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
                    int rows = current.name().equals("nameplates") ? 2 : current.columbia() ? 6 : current.browns() ? 5 : 4;
                    double cameraY = wall.getY() - rows / 2.0 + 0.5 - 1.62;
                    player.connection.teleport(centreX, cameraY, wall.getZ() - current.distance(), 0, 0);
                } catch (Throwable t) {
                    failure = t;
                }
            });
            mc.options.hideGui = true;
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
            if (System.nanoTime() - scramAt < 900_000_000L) return;
        } else if (elapsed < 11_000_000_000L) {
            return;
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
        try {
            var directory = mc.gameDirectory.toPath().resolve("fcd-check");
            Files.createDirectories(directory);
            try (var shot = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
                shot.writeToFile(directory.resolve(current.name() + ".png"));
            }
        } catch (IOException ex) {
            throw new IllegalStateException("FCD check screenshot failed", ex);
        }
        requested = false;
        if (++stage == STAGES.size()) {
            LogUtils.getLogger().info("FCD PERFORMANCE COMPLETE");
            mc.stop();
        }
    }

    @SubscribeEvent
    public static void frame(RenderFrameEvent.Post ignored) {
        if (!Boolean.getBoolean("bwr.fcdClientCheck") || !requested) return;
        long now = System.nanoTime();
        if (now - began > 3_000_000_000L && lastFrame != 0 && !STAGES.get(stage).scram()) {
            if (rebuildsAtWindow < 0) {
                rebuildsAtWindow = PanelRenderer.debugRebuilds();
                legendsAtWindow = PanelRenderer.debugLegendRebuilds();
            }
            double delta = (now - lastFrame) / 1_000_000.0;
            if (delta > 0 && delta < 500) FRAME_MS.add(delta);
        }
        lastFrame = now;
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
            x += 6;
            rows = Math.max(rows, 6);
        }
        if (stage.browns()) {
            brownsAt = x;
            x += 8;
            rows = Math.max(rows, 5);
        }
        if (stage.matrix()) {
            matrixAt = x;
            x += 4;
            rows = Math.max(rows, 4);
        }
        cols = x;
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                level.setBlock(wall.relative(Direction.WEST, col).below(row), BwrBlocks.CONTROL_PANEL_VERTICAL.get().defaultBlockState()
                        .setValue(PanelBlock.FACING, Direction.NORTH), 3);
            }
        }
        if (columbiaAt >= 0) place(level, wall.relative(Direction.WEST, columbiaAt), ComponentTypes.FULL_CORE_DISPLAY, 96, 96, reactor,
                FullCoreDisplayData.COLUMBIA, stage.name().equals("columbia-labels"));
        if (brownsAt >= 0) place(level, wall.relative(Direction.WEST, brownsAt), ComponentTypes.FULL_CORE_DISPLAY, 128, 80, reactor,
                FullCoreDisplayData.BROWNS_FERRY, true);
        if (matrixAt >= 0) place(level, wall.relative(Direction.WEST, matrixAt), ComponentTypes.ROD_SELECT_MATRIX, 64, 64, reactor, null, true);
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
