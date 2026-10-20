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
import dev.bwr.mod.panel.component.RecorderScreen;
import dev.bwr.mod.panel.wiring.PlantSource;
import dev.bwr.mod.registry.BwrBlocks;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * The recorder performance gate of {@link RecorderClientCheck}: four DX1000s at 12 channels and one DX2000 at 48
 * channels on one wall, every trend at 5 s per division (a new dot, a patch and a repaint every 3.3 ticks), every
 * input changing every tick. It measures the same wall with no recorders on it first (the frame time of the world and
 * the bare panel blocks), then with them, and also the CPU time the recorders' own render path and screen painting
 * take (counters in the model, enabled by {@code -Dbwr.recorderTiming=true}, which the Gradle flag sets). The gate is
 * under 1 ms per frame of recorder cost.
 */
final class RecorderPerformance {
    private RecorderPerformance() {
    }

    private static final BlockPos ROOT = new BlockPos(45, 112, 100);
    private static final long WARM_NANOS = 3_000_000_000L;
    private static final long MEASURE_NANOS = 9_000_000_000L;
    private static int phase;
    private static long began;
    private static long lastFrame;
    private static final List<Double> FRAME_MS = new ArrayList<>();
    private static final List<PanelComponent> SERVER = new ArrayList<>();
    private static final List<PanelBlockEntity> SERVER_BE = new ArrayList<>();
    private static final List<BlockPos> POSITIONS = new ArrayList<>();
    private static final List<Integer> IDS = new ArrayList<>();
    private static final Random NOISE = new Random(9);
    private static double baseline;
    private static boolean driving;
    private static boolean resynced;
    private static int[] paintsAtStart = new int[5];

    private static BlockPos panel(int column, int row) {
        return ROOT.relative(Direction.WEST, column).below(row);
    }

    private static void buildWall(ServerLevel level, boolean recorders) {
        for (BlockPos p : BlockPos.betweenClosed(new BlockPos(20, ROOT.getY() - 6, 80), new BlockPos(70, ROOT.getY() - 6, 120))) {
            level.setBlock(p, Blocks.SMOOTH_STONE.defaultBlockState(), 2);
        }
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 11; column++) {
                level.setBlock(panel(column, row), BwrBlocks.CONTROL_PANEL_VERTICAL.get().defaultBlockState()
                        .setValue(PanelBlock.FACING, Direction.NORTH), 3);
            }
        }
        if (!recorders) {
            return;
        }
        for (int i = 0; i < 5; i++) {
            boolean big = i == 4;
            BlockPos at = big ? panel(5, 0) : panel(i, 0);
            PanelBlockEntity be = (PanelBlockEntity) level.getBlockEntity(at);
            PanelComponent rec = PanelOps.placeDirect(level, at, 0, 0, ComponentTypes.PAPERLESS_RECORDER,
                    big ? Footprint.of(32, 32) : Footprint.of(16, 16));
            if (rec == null) {
                throw new AssertionError("performance recorder " + i + " did not fit");
            }
            PaperlessRecorderData data = (PaperlessRecorderData) rec.data();
            int channels = big ? 48 : 12;
            RecorderScenario.configure(data, channels, "white", 0); // 5 s per division
            data.setScreen(RecorderScreen.TREND);
            RecorderScenario.fill(data, channels, level.getGameTime() - 1);
            // one live plant binding keeps the panel in the sampler, as a real wired recorder is
            rec.setSource(0, new PlantSource(new BlockPos(48, 112, 46), "position", 100.0, 0.0));
            be.resample(rec, true);
            SERVER.add(rec);
            SERVER_BE.add(be);
            POSITIONS.add(at);
            IDS.add(rec.id());
        }
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 11; column++) {
                ((PanelBlockEntity) level.getBlockEntity(panel(column, row))).syncFull();
            }
        }
    }

    /** One server tick of changing inputs on every channel of every recorder. */
    private static void drive() {
        for (int i = 0; i < SERVER.size(); i++) {
            PanelComponent rec = SERVER.get(i);
            for (int c = 1; c < rec.inputCount(); c++) {
                SERVER_BE.get(i).setInput(rec, c, RecorderScenario.value(c, 440, NOISE));
            }
        }
    }

    static void frame() {
        long now = System.nanoTime();
        if (lastFrame != 0 && now - began > WARM_NANOS) {
            double delta = (now - lastFrame) / 1_000_000.0;
            if (delta > 0 && delta < 500) {
                FRAME_MS.add(delta);
            }
        }
        lastFrame = now;
    }

    private static void camera(Minecraft mc) {
        var server = mc.getSingleplayerServer();
        var uuid = mc.player.getUUID();
        server.execute(() -> {
            var player = server.getPlayerList().getPlayer(uuid);
            player.setNoGravity(true);
            player.getAbilities().flying = true;
            player.onUpdateAbilities();
            player.connection.teleport(41.5, 112.0 - player.getEyeHeight() + 0.0, 92.0, 0.0f, 0.0f);
        });
    }

    /** Called every client tick during the performance stage; returns true when it is finished. */
    static boolean step(Minecraft mc, MinecraftServer server) {
        if (driving) {
            server.execute(RecorderPerformance::drive);
        }
        switch (phase) {
            case 0 -> {
                server.execute(() -> buildWall(server.overworld(), false));
                camera(mc);
                began = System.nanoTime();
                lastFrame = 0;
                FRAME_MS.clear();
                phase = 1;
            }
            case 1 -> {
                if (System.nanoTime() - began > WARM_NANOS + MEASURE_NANOS) {
                    baseline = report("baseline (wall of bare panel blocks)");
                    server.execute(() -> buildWall(server.overworld(), true));
                    driving = true;
                    began = System.nanoTime();
                    lastFrame = 0;
                    FRAME_MS.clear();
                    phase = 2;
                }
            }
            case 2 -> {
                long elapsed = System.nanoTime() - began;
                if (!resynced && elapsed > 1_000_000_000L) {
                    // the blocks and components were placed in one tick: send the full states again once the client has the blocks
                    resynced = true;
                    server.execute(() -> {
                        for (int row = 0; row < 3; row++) {
                            for (int column = 0; column < 11; column++) {
                                ((PanelBlockEntity) server.overworld().getBlockEntity(panel(column, row))).syncFull();
                            }
                        }
                    });
                }
                if (elapsed > WARM_NANOS && paintsAtStart[0] == 0) {
                    RecorderClientStats.resetTiming();
                    for (int i = 0; i < 5; i++) {
                        PanelComponent c = client(mc, i);
                        paintsAtStart[i] = c == null ? 0 : RecorderClientStats.paints(c) + 1;
                    }
                }
                if (elapsed > WARM_NANOS + MEASURE_NANOS) {
                    double withRecorders = report("5 recorders (4 x DX1000 12 ch, 1 x DX2000 48 ch), 5 s/div");
                    long[] render = RecorderClientStats.renderTotals();
                    long[] paint = RecorderClientStats.paintTotals();
                    int frames = FRAME_MS.size();
                    double renderPerFrame = render[0] / 1.0e6 / Math.max(1, frames);
                    LogUtils.getLogger().info("RECORDER PERFORMANCE recorder render path: {} ms per frame over {} frames ({} calls), "
                            + "screen paints: {} in {} s, {} ms each, {} ms per frame; frame time {} ms against {} ms without recorders (delta {} ms)",
                            renderPerFrame, frames, render[1], paint[1], MEASURE_NANOS / 1e9, paint[1] == 0 ? 0 : paint[0] / 1.0e6 / paint[1],
                            paint[0] / 1.0e6 / Math.max(1, frames), withRecorders, baseline, withRecorders - baseline);
                    for (int i = 0; i < 5; i++) {
                        PanelComponent c = client(mc, i);
                        int paints = c == null ? -1 : RecorderClientStats.paints(c) + 1 - paintsAtStart[i];
                        double perSecond = paints / (MEASURE_NANOS / 1e9);
                        LogUtils.getLogger().info("RECORDER PERFORMANCE recorder {} repainted {} times in {} s ({} per second, frames {})",
                                i, paints, MEASURE_NANOS / 1e9, perSecond, frames);
                        if (c == null || perSecond > 12.0) {
                            throw new AssertionError("recorder " + i + " repainted " + perSecond + " times a second: it must repaint on a new dot or the 1 s refresh only, never per frame");
                        }
                    }
                    if (renderPerFrame + paint[0] / 1.0e6 / Math.max(1, frames) >= 1.0) {
                        throw new AssertionError("the recorders cost " + (renderPerFrame + paint[0] / 1.0e6 / Math.max(1, frames)) + " ms per frame (gate: under 1 ms)");
                    }
                    try {
                        var dir = mc.gameDirectory.toPath().resolve("recorder-check");
                        java.nio.file.Files.createDirectories(dir);
                        CheckShots.save(mc,dir.resolve("performance-wall.png"));
                    } catch (java.io.IOException e) {
                        throw new IllegalStateException(e);
                    }
                    driving = false;
                    return true;
                }
            }
            default -> {
            }
        }
        return false;
    }

    private static PanelComponent client(Minecraft mc, int i) {
        if (mc.level != null && i < POSITIONS.size() && mc.level.getBlockEntity(POSITIONS.get(i)) instanceof PanelBlockEntity be) {
            return be.component(IDS.get(i));
        }
        return null;
    }

    private static double report(String name) {
        if (FRAME_MS.size() < 100) {
            throw new AssertionError("Too few frames for the recorder benchmark: " + FRAME_MS.size());
        }
        Collections.sort(FRAME_MS);
        double sum = 0;
        for (double ms : FRAME_MS) {
            sum += ms;
        }
        int n = FRAME_MS.size();
        double mean = sum / n;
        LogUtils.getLogger().info("RECORDER PERFORMANCE stage={} frames={} meanMs={} p50Ms={} p95Ms={} fps={}", name, n, mean,
                FRAME_MS.get(n / 2), FRAME_MS.get((int) (n * .95)), 1000 / mean);
        return mean;
    }
}
