package dev.bwr.mod.devtest;

import com.mojang.logging.LogUtils;
import dev.bwr.mod.BwrMod;
import dev.bwr.mod.lighting.IndustrialLampBlock;
import dev.bwr.mod.lighting.WallPackLampBlock;
import dev.bwr.mod.registry.BwrBlocks;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Disposable real-client showroom of the industrial lamps (LIGHTING.md): a windowless 16 x 12 hall at night with a high bay
 * of each lamp type hung from the ceiling and a wall pack of each on the west wall. It photographs the warm-up, the full
 * lamps from the hall and close, and the lamps switched off (the hot ones cooling). Each picture logs the light level the
 * client sees at the lamps. Run with -PbwrLampClientCheck (optionally -PbwrRenderMods=newest -PbwrShaderPack=...).
 * Does not run in ordinary play.
 */
@EventBusSubscriber(modid = BwrMod.MOD_ID, value = Dist.CLIENT)
public final class LampClientCheck {
    /** The hall fills one chunk (5392 .. 5407) so one light section answers. The floor is at FLOOR, the ceiling 8 above. */
    private static final int X0 = 5392;
    private static final int Z0 = 5392;
    private static final int FLOOR = 119;

    /** The lamps: high bays hang under the ceiling at y 7 with a redstone block in the ceiling above each; wall packs on the
     *  west wall with a redstone block in the wall behind each. The power blocks sit behind the lamps, out of the room. */
    private static final int[][] HIGH_BAYS = {{4, 4}, {8, 4}, {12, 4}};
    private static final int[][] WALL_PACKS = {{1, 2}, {1, 6}, {1, 10}};
    private static final BlockPos[] POWER = powerPositions();

    /** Eye then target, in hall coordinates (x east, y above the floor, z south). */
    private static final double[][] VIEWS = {
            {14.3, 4.4, 6.0, 1.0, 3.2, 6.0},   // the whole hall from the east end, looking west
            {8.0, 2.2, 7.0, 8.0, 6.6, 4.0},    // the high bay in the middle, from below
            {6.2, 2.6, 6.0, 1.0, 4.4, 6.5},    // the wall packs close, from the middle of the hall
            {14.3, 4.4, 6.0, 1.0, 3.2, 6.0},   // the whole hall again, after switch-off
    };
    /** Client ticks after the hall is built at which each picture is taken, and which view it uses. */
    private static final int[] SHOT_AT = {60, 900, 960, 1020, 1160};
    private static final int[] SHOT_VIEW = {0, 0, 1, 2, 3};
    private static final String[] NAMES = {"lamps-warmup-early", "lamps-full-hall", "lamps-full-high-bays",
            "lamps-full-wall-packs", "lamps-switched-off"};
    /** When the power is taken away, after the build. */
    private static final int SWITCH_OFF_AT = 1100;
    private static final int FOV = 75;

    private static boolean started;
    private static boolean built;
    private static boolean switched;
    private static int age;
    private static int joined;
    private static int ticks;
    private static int stage;
    private static volatile Vec3 wantedCamera;
    private static volatile float wantedYaw;
    private static volatile float wantedPitch;

    private LampClientCheck() {
    }

    private static BlockPos[] powerPositions() {
        BlockPos[] pos = new BlockPos[HIGH_BAYS.length + WALL_PACKS.length];
        for (int i = 0; i < HIGH_BAYS.length; i++) {
            pos[i] = at(HIGH_BAYS[i][0], 8, HIGH_BAYS[i][1]);
        }
        for (int i = 0; i < WALL_PACKS.length; i++) {
            pos[HIGH_BAYS.length + i] = at(0, 4, WALL_PACKS[i][1]);
        }
        return pos;
    }

    private static BlockPos at(int x, int y, int z) {
        return new BlockPos(X0 + x, FLOOR + y, Z0 + z);
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post ignored) {
        if (!Boolean.getBoolean("bwr.lampClientCheck")) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (++age > 4000) {
            throw new IllegalStateException("Lamp client check timed out at stage " + stage);
        }
        if (!started && mc.screen != null && mc.getOverlay() == null) {
            started = true;
            mc.options.pauseOnLostFocus = false;
            mc.options.cloudStatus().set(CloudStatus.OFF);
            mc.options.renderDistance().set(8);
            mc.options.guiScale().set(2);
            mc.options.gamma().set(1.0);
            mc.createWorldOpenFlows().createFreshLevel("bwr-lamp-check-" + System.currentTimeMillis(),
                    new LevelSettings("Lamp check", GameType.CREATIVE, false, Difficulty.PEACEFUL, true,
                            new GameRules(), WorldDataConfiguration.DEFAULT),
                    new WorldOptions(0, false, false),
                    r -> r.registryOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).createWorldDimensions(), mc.screen);
            return;
        }
        if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null || ++joined < 40) {
            return;
        }
        MinecraftServer server = mc.getSingleplayerServer();
        if (!built) {
            built = true;
            mc.setScreen(null);
            mc.options.hideGui = true;
            mc.options.fov().set(FOV);
            var uuid = mc.player.getUUID();
            server.execute(() -> {
                var player = server.getPlayerList().getPlayer(uuid);
                if (player == null) {
                    throw new IllegalStateException("Lamp check player missing");
                }
                ServerLevel level = player.serverLevel();
                build(level);
                level.setDayTime(18000);
                level.setWeatherParameters(6000, 0, false, false);
                player.setNoGravity(true);
                player.getAbilities().flying = true;
                player.onUpdateAbilities();
                aim(player, VIEWS[0]);
            });
            return;
        }
        ticks++;
        if (!switched && ticks == SWITCH_OFF_AT) {
            switched = true;
            server.execute(() -> {
                ServerLevel level = server.overworld();
                // The power blocks are also the lamps' supports (the ceiling and the wall): they go back to concrete, not air.
                for (BlockPos p : POWER) {
                    level.setBlock(p, Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState(), 3);
                }
            });
        }
        double[] v = VIEWS[SHOT_VIEW[Math.min(stage, SHOT_VIEW.length - 1)]];
        Vec3 eye = new Vec3(X0 + v[0], FLOOR + v[1], Z0 + v[2]);
        Vec3 target = new Vec3(X0 + v[3], FLOOR + v[4], Z0 + v[5]);
        Vec3 delta = target.subtract(eye);
        wantedYaw = (float) (Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90);
        wantedPitch = (float) -Math.toDegrees(Math.atan2(delta.y, Math.hypot(delta.x, delta.z)));
        wantedCamera = eye.subtract(0, mc.player.getEyeHeight(), 0);
        mc.player.getAbilities().flying = true;
        mc.player.setDeltaMovement(Vec3.ZERO);
        mc.player.setPos(wantedCamera.x, wantedCamera.y, wantedCamera.z);
        mc.player.setYRot(wantedYaw);
        mc.player.setXRot(wantedPitch);
        mc.player.yRotO = wantedYaw;
        mc.player.xRotO = wantedPitch;
        if (stage < SHOT_AT.length && ticks == SHOT_AT[stage]) {
            logLevels(mc, NAMES[stage]);
            shoot(mc, NAMES[stage]);
            stage++;
            if (stage == SHOT_AT.length) {
                LogUtils.getLogger().info("LAMP CLIENT CHECK PASS: {} pictures", SHOT_AT.length);
                mc.stop();
            }
        }
    }

    private static void aim(net.minecraft.server.level.ServerPlayer player, double[] v) {
        Vec3 eye = new Vec3(X0 + v[0], FLOOR + v[1], Z0 + v[2]);
        Vec3 target = new Vec3(X0 + v[3], FLOOR + v[4], Z0 + v[5]);
        Vec3 delta = target.subtract(eye);
        float yaw = (float) (Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90);
        float pitch = (float) -Math.toDegrees(Math.atan2(delta.y, Math.hypot(delta.x, delta.z)));
        player.connection.teleport(eye.x, eye.y - player.getEyeHeight(), eye.z, yaw, pitch);
    }

    private static void build(ServerLevel level) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 12; z++) {
                level.setBlock(at(x, 0, z), Blocks.GRAY_CONCRETE.defaultBlockState(), 2);
                for (int y = 1; y <= 8; y++) {
                    boolean shell = x == 0 || x == 15 || z == 0 || z == 11 || y == 8;
                    level.setBlock(at(x, y, z), shell ? Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState()
                            : Blocks.AIR.defaultBlockState(), 2);
                }
            }
        }
        Block[] bays = {BwrBlocks.LPS_HIGH_BAY.get(), BwrBlocks.HPS_HIGH_BAY.get(), BwrBlocks.MERCURY_HIGH_BAY.get()};
        Block[] packs = {BwrBlocks.LPS_WALL_PACK.get(), BwrBlocks.HPS_WALL_PACK.get(), BwrBlocks.MERCURY_WALL_PACK.get()};
        for (int i = 0; i < HIGH_BAYS.length; i++) {
            level.setBlock(at(HIGH_BAYS[i][0], 7, HIGH_BAYS[i][1]), bays[i].defaultBlockState(), 2);
        }
        for (int i = 0; i < WALL_PACKS.length; i++) {
            BlockState pack = packs[i].defaultBlockState().setValue(WallPackLampBlock.FACING, Direction.EAST);
            level.setBlock(at(WALL_PACKS[i][0], 4, WALL_PACKS[i][1]), pack, 2);
        }
        for (BlockPos p : POWER) {
            level.setBlock(p, Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
        }
    }

    private static void logLevels(Minecraft mc, String name) {
        StringBuilder line = new StringBuilder("LAMP CLIENT CHECK light for " + name + ":");
        for (int i = 0; i < HIGH_BAYS.length; i++) {
            BlockPos p = at(HIGH_BAYS[i][0], 7, HIGH_BAYS[i][1]);
            BlockState s = mc.level.getBlockState(p);
            line.append(" bay").append(i).append('=').append(s.getLightEmission())
                    .append(s.getValue(IndustrialLampBlock.LIT) ? "(lit" : "(dark")
                    .append(",w").append(s.getValue(IndustrialLampBlock.WARMUP))
                    .append(",c").append(s.getValue(IndustrialLampBlock.COOLING)).append(')');
        }
        for (int i = 0; i < WALL_PACKS.length; i++) {
            BlockPos p = at(WALL_PACKS[i][0], 4, WALL_PACKS[i][1]);
            BlockState s = mc.level.getBlockState(p);
            line.append(" pack").append(i).append('=').append(s.getLightEmission());
        }
        LogUtils.getLogger().info(line.toString());
    }

    private static void shoot(Minecraft mc, String name) {
        try {
            Path directory = mc.gameDirectory.toPath().resolve("lamp-check");
            Files.createDirectories(directory);
            try (var shot = CheckShots.saveKeeping(mc, directory.resolve(name + ".png"))) {
                int first = shot.getPixelRGBA(shot.getWidth() / 2, shot.getHeight() / 2);
                int same = 0;
                int total = 0;
                for (int y = 0; y < shot.getHeight(); y += 8) {
                    for (int x = 0; x < shot.getWidth(); x += 8) {
                        total++;
                        if (shot.getPixelRGBA(x, y) == first) {
                            same++;
                        }
                    }
                }
                if (same > total * 0.97) {
                    throw new AssertionError("Lamp screenshot " + name + " is a flat colour: nothing rendered");
                }
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Lamp screenshot failed", ex);
        }
        LogUtils.getLogger().info("LAMP CLIENT CHECK: {}", name);
    }
}
