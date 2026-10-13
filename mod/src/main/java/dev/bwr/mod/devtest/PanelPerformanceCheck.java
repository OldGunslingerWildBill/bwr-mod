package dev.bwr.mod.devtest;

import com.mojang.logging.LogUtils;
import dev.bwr.mod.BwrMod;
import dev.bwr.mod.panel.PanelBlock;
import dev.bwr.mod.panel.PanelOps;
import dev.bwr.mod.panel.component.ComponentType;
import dev.bwr.mod.panel.component.ComponentTypes;
import dev.bwr.mod.panel.component.Footprint;
import dev.bwr.mod.registry.BwrBlocks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
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

/** Same camera, empty baseline then a loaded 16x8 wall with 256 rendered instruments. */
@EventBusSubscriber(modid = BwrMod.MOD_ID, value = Dist.CLIENT)
public final class PanelPerformanceCheck {
    private static final BlockPos ROOT = new BlockPos(5032, 125, 5040);
    private static boolean started, requested;
    private static int age, joined, stage;
    private static long began, lastFrame;
    private static final ArrayList<Double> FRAME_MS = new ArrayList<>();

    private PanelPerformanceCheck() {}

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post ignored) {
        if (!Boolean.getBoolean("bwr.panelPerformanceCheck")) return;
        Minecraft mc = Minecraft.getInstance();
        if (++age > 1800) throw new IllegalStateException("Panel benchmark timed out at " + stage);
        if (!started && mc.screen != null && mc.getOverlay() == null) {
            started = true;
            mc.options.pauseOnLostFocus = false;
            mc.options.enableVsync().set(false);
            mc.options.framerateLimit().set(260);
            mc.options.renderDistance().set(8);
            mc.options.fov().set(60);
            mc.createWorldOpenFlows().createFreshLevel("bwr-panel-performance-" + System.currentTimeMillis(),
                    new LevelSettings("Panel benchmark", GameType.CREATIVE, false, Difficulty.PEACEFUL,
                            true, new GameRules(), WorldDataConfiguration.DEFAULT),
                    new WorldOptions(0, false, false),
                    r -> r.registryOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).createWorldDimensions(), mc.screen);
            return;
        }
        if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null || ++joined < 40) return;
        if (!requested) {
            requested = true;
            lastFrame = 0;
            FRAME_MS.clear();
            began = System.nanoTime();
            int selected = stage;
            var server = mc.getSingleplayerServer();
            var uuid = mc.player.getUUID();
            server.execute(() -> {
                var player = server.getPlayerList().getPlayer(uuid);
                var level = player.serverLevel();
                int offset = selected == 0 ? 0 : 5000;
                for (BlockPos pos : BlockPos.betweenClosed(new BlockPos(10 + offset, 117, 18 + offset),
                        new BlockPos(38 + offset, 117, 45 + offset)))
                    level.setBlock(pos, Blocks.SMOOTH_STONE.defaultBlockState(), 2);
                if (selected == 1) build(level);
                player.setNoGravity(true);
                player.getAbilities().flying = true;
                player.onUpdateAbilities();
                level.setDayTime(6000);
                // Keep every block inside the 24-block full-detail range for the wall measurement.
                player.connection.teleport(24 + offset, 124, 25 + offset, 0, 0);
            });
            mc.options.hideGui = true;
        }
        if (System.nanoTime() - began < 11_000_000_000L) return;
        if (FRAME_MS.size() < 100) throw new AssertionError("Too few frames for panel benchmark: " + FRAME_MS.size());
        Collections.sort(FRAME_MS);
        double sum = 0;
        for (double ms : FRAME_MS) sum += ms;
        int n = FRAME_MS.size();
        double mean = sum / n;
        LogUtils.getLogger().info("PANEL PERFORMANCE stage={} frames={} meanMs={} p50Ms={} p95Ms={} fps={} active={}",
                stage == 0 ? "empty" : "16x8-wall", n, mean, FRAME_MS.get(n / 2), FRAME_MS.get((int) (n * .95)),
                1000 / mean, mc.isWindowActive());
        try {
            var directory = mc.gameDirectory.toPath().resolve("panel-performance");
            Files.createDirectories(directory);
            try (var shot = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
                shot.writeToFile(directory.resolve(stage == 0 ? "empty.png" : "wall-16x8.png"));
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Panel benchmark screenshot failed", ex);
        }
        requested = false;
        if (++stage == 2) {
            LogUtils.getLogger().info("PANEL PERFORMANCE COMPLETE");
            mc.stop();
        }
    }

    @SubscribeEvent
    public static void frame(RenderFrameEvent.Post ignored) {
        if (!Boolean.getBoolean("bwr.panelPerformanceCheck") || !requested) return;
        long now = System.nanoTime();
        if (now - began > 3_000_000_000L && lastFrame != 0) {
            double delta = (now - lastFrame) / 1_000_000.0;
            if (delta > 0 && delta < 500) FRAME_MS.add(delta);
        }
        lastFrame = now;
    }

    private static void build(net.minecraft.server.level.ServerLevel level) {
        ComponentType[] types = {ComponentTypes.PUSHBUTTON, ComponentTypes.TOGGLE_SWITCH,
                ComponentTypes.DIAL_GAUGE, ComponentTypes.ANNUNCIATOR};
        for (int row = 0; row < 8; row++) for (int col = 0; col < 16; col++) {
            var pos = ROOT.relative(Direction.WEST, col).below(row);
            level.setBlock(pos, BwrBlocks.CONTROL_PANEL_VERTICAL.get().defaultBlockState()
                    .setValue(PanelBlock.FACING, Direction.NORTH), 3);
        }
        for (int row = 0; row < 8; row++) for (int col = 0; col < 16; col++) {
            var pos = ROOT.relative(Direction.WEST, col).below(row);
            for (int i = 0; i < 2; i++) {
                ComponentType type = types[(col + row + i) % types.length];
                Footprint size = type.footprints().get(0);
                int x = i == 0 ? 1 : 9;
                if (PanelOps.placeDirect(level, pos, x, 2, type, size) == null)
                    throw new AssertionError("Panel benchmark placement failed at " + pos + " for " + type.id());
            }
        }
    }
}
