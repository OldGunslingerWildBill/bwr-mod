package dev.bwr.mod.devtest;

import com.mojang.logging.LogUtils;
import dev.bwr.mod.grid.FeMeterBlock;
import dev.bwr.mod.registry.BwrBlocks;
import net.minecraft.client.Minecraft;
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
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Opt-in real-client picture of the Grid Tie and FE Meter placeholder blocks ({@code -PbwrGridClientCheck}): one
 * stage with the tie and four meters facing north, east, south and west (the arrow on each meter's top face points
 * toward its output), one close-up of the four meters from above. Screenshots go to
 * {@code run/turbineModelCheck/grid-check/}. Not shipped: the whole devtest package is excluded from the release jar.
 */
@EventBusSubscriber(modid = "bwr", value = Dist.CLIENT)
public final class GridClientCheck {
    private static final String[] NAMES = {"grid-tie-and-meters", "meters-arrows-close"};
    /** Row of blocks on the platform: z = 32, meters at x = 28, 30, 32, 34 and the tie at x = 38. */
    private static final int Z = 32, Y = 110;
    private static boolean started, requested;
    private static int age, joined, stage, shown;

    private GridClientCheck() {}

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) throws Exception {
        if (!Boolean.getBoolean("bwr.gridClientCheck")) return;
        Minecraft mc = Minecraft.getInstance();
        if (++age > 2400) throw new IllegalStateException("Grid client check timed out at stage " + stage);
        if (!started && mc.screen != null && mc.getOverlay() == null) {
            started = true;
            mc.options.pauseOnLostFocus = false;
            mc.options.guiScale().set(2);
            mc.options.enableVsync().set(false);
            mc.createWorldOpenFlows().createFreshLevel("bwr-grid-check-" + System.currentTimeMillis(),
                    new LevelSettings("Grid check", GameType.CREATIVE, false, Difficulty.PEACEFUL, true, new GameRules(),
                            WorldDataConfiguration.DEFAULT),
                    new WorldOptions(0, false, false),
                    r -> r.registryOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).createWorldDimensions(), mc.screen);
            return;
        }
        if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null || ++joined < 100) return;
        if (!requested) {
            requested = true;
            shown = 0;
            mc.setScreen(null);
            mc.options.hideGui = true;
            int current = stage;
            var server = mc.getSingleplayerServer();
            var uuid = mc.player.getUUID();
            server.execute(() -> {
                var player = server.getPlayerList().getPlayer(uuid);
                var level = player.serverLevel();
                level.setDayTime(6000);
                level.setWeatherParameters(6000, 0, false, false);
                if (current == 0) {
                    for (BlockPos p : BlockPos.betweenClosed(new BlockPos(24, Y - 1, Z - 6), new BlockPos(44, Y - 1, Z + 6))) {
                        level.setBlock(p, Blocks.SMOOTH_STONE.defaultBlockState(), 2);
                    }
                    Direction[] facing = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
                    for (int i = 0; i < facing.length; i++) {
                        level.setBlock(new BlockPos(28 + 2 * i, Y, Z),
                                BwrBlocks.FE_METER.get().defaultBlockState().setValue(FeMeterBlock.FACING, facing[i]), 3);
                    }
                    level.setBlock(new BlockPos(38, Y, Z), BwrBlocks.GRID_TIE.get().defaultBlockState(), 3);
                }
                // Stage 0: the whole row from the south, high and angled so every top face is seen.
                // Stage 1: close above the row of meters.
                Vec3 camera = current == 0 ? new Vec3(33.5, Y + 6.5, Z + 8.5) : new Vec3(30.5, Y + 3.2, Z + 1.6);
                Vec3 target = current == 0 ? new Vec3(33.5, Y + 0.5, Z + 0.5) : new Vec3(30.5, Y + 0.9, Z + 0.5);
                Vec3 delta = target.subtract(camera.add(0, player.getEyeHeight(), 0));
                float yaw = (float) (Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90);
                float pitch = (float) -Math.toDegrees(Math.atan2(delta.y, Math.hypot(delta.x, delta.z)));
                player.setNoGravity(true);
                player.getAbilities().flying = true;
                player.onUpdateAbilities();
                player.connection.teleport(camera.x, camera.y, camera.z, yaw, pitch);
            });
        }
        boolean ready = mc.screen == null && mc.player.getY() > Y + 2 && mc.level.getBlockState(new BlockPos(38, Y, Z)).is(BwrBlocks.GRID_TIE.get());
        if (ready && ++shown > 55) {
            var dir = mc.gameDirectory.toPath().resolve("grid-check");
            java.nio.file.Files.createDirectories(dir);
            CheckShots.save(mc,dir.resolve(NAMES[stage] + ".png"));
            LogUtils.getLogger().info("GRID CLIENT PASS {}", NAMES[stage]);
            if (++stage == NAMES.length) {
                LogUtils.getLogger().info("GRID CLIENT COMPLETE");
                mc.stop();
            } else {
                requested = false;
            }
        }
    }
}
