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
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
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
 * Disposable real-client showroom of the industrial lamps (LIGHTING.md): a windowless 20 x 8 x 20 hall at night, with a dark
 * floor and walls. Six high bays hang from the ceiling (a low-pressure, a high-pressure and a mercury lamp in each of two
 * rows) and three wall packs sit on the west wall. Each lamp is switched by a lever beside it, so no bright redstone block
 * shows. It photographs the warm-up, the full hall, the high bays close and from the side (do they hang straight?), the wall
 * packs, and the switched-off hall. Each picture logs the light level the client sees at the lamps. Run with
 * -PbwrLampClientCheck (optionally -PbwrRenderMods=newest -PbwrShaderPack=ComplementaryReimagined_r5.9.3.zip). Does not run
 * in ordinary play.
 */
@EventBusSubscriber(modid = BwrMod.MOD_ID, value = Dist.CLIENT)
public final class LampClientCheck {
    /** The hall starts at X0, Z0 and its floor is at FLOOR; the ceiling is H blocks above it. */
    private static final int X0 = 5392;
    private static final int Z0 = 5392;
    private static final int FLOOR = 119;
    private static final int W = 20;
    private static final int D = 20;
    private static final int H = 8;

    /** Lamps as {x, z, type}: type 0 low-pressure sodium, 1 high-pressure sodium, 2 mercury. */
    private static final int[][] HIGH_BAYS = {{5, 6, 0}, {10, 6, 1}, {15, 6, 2}, {5, 14, 0}, {10, 14, 1}, {15, 14, 2}};
    private static final int[][] WALL_PACKS = {{4, 0}, {10, 1}, {16, 2}};

    /** Eye then target, in hall coordinates (x east, y above the floor, z south). */
    private static final double[][] VIEWS = {
            {18.3, 4.6, 10.0, 1.0, 3.2, 10.0},  // the whole hall from the east end (inside the wall at x 19), looking west
            {10.0, 2.6, 9.0, 10.0, 6.4, 6.0},   // the middle high bay, close, from below and the south
            {10.0, 3.0, 2.0, 10.0, 6.0, 6.0},   // the same row, side on from the north: do they hang straight?
            {6.0, 2.8, 10.0, 1.0, 4.4, 10.0},   // the wall packs close, from the middle of the hall
    };
    /** Client ticks after the hall is built at which each picture is taken, and which view it uses. */
    private static final int[] SHOT_AT = {60, 900, 960, 1020, 1080, 1200};
    private static final int[] SHOT_VIEW = {0, 0, 1, 2, 3, 0};
    private static final String[] NAMES = {"lamps-warmup-early", "lamps-full-hall", "lamps-full-high-bays",
            "lamps-full-high-bays-side", "lamps-full-wall-packs", "lamps-switched-off"};
    /** When the levers are switched off: after the wall-pack picture, before the last one. */
    private static final int SWITCH_OFF_AT = 1140;
    private static final int FOV = 75;

    private static boolean started;
    private static boolean built;
    private static boolean switched;
    private static int age;
    private static int joined;
    private static int ticks;
    private static int stage;

    private LampClientCheck() {
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
                ServerPlayer player = server.getPlayerList().getPlayer(uuid);
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
                for (int[] bay : HIGH_BAYS) {
                    setLever(level, leverOfBay(bay), false);
                }
                for (int[] pack : WALL_PACKS) {
                    setLever(level, leverOfPack(pack), false);
                }
            });
        }
        double[] v = VIEWS[SHOT_VIEW[Math.min(stage, SHOT_VIEW.length - 1)]];
        Vec3 eye = new Vec3(X0 + v[0], FLOOR + v[1], Z0 + v[2]);
        Vec3 target = new Vec3(X0 + v[3], FLOOR + v[4], Z0 + v[5]);
        Vec3 delta = target.subtract(eye);
        float yaw = (float) (Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90);
        float pitch = (float) -Math.toDegrees(Math.atan2(delta.y, Math.hypot(delta.x, delta.z)));
        Vec3 feet = eye.subtract(0, mc.player.getEyeHeight(), 0);
        mc.player.getAbilities().flying = true;
        mc.player.setDeltaMovement(Vec3.ZERO);
        mc.player.setPos(feet.x, feet.y, feet.z);
        mc.player.setYRot(yaw);
        mc.player.setXRot(pitch);
        mc.player.yRotO = yaw;
        mc.player.xRotO = pitch;
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

    private static void aim(ServerPlayer player, double[] v) {
        Vec3 eye = new Vec3(X0 + v[0], FLOOR + v[1], Z0 + v[2]);
        Vec3 target = new Vec3(X0 + v[3], FLOOR + v[4], Z0 + v[5]);
        Vec3 delta = target.subtract(eye);
        float yaw = (float) (Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90);
        float pitch = (float) -Math.toDegrees(Math.atan2(delta.y, Math.hypot(delta.x, delta.z)));
        player.connection.teleport(eye.x, eye.y - player.getEyeHeight(), eye.z, yaw, pitch);
    }

    /** The lever beside a high bay: on the ceiling, one block to the east of the lamp. */
    private static BlockPos leverOfBay(int[] bay) {
        return at(bay[0] + 1, H - 1, bay[1]);
    }

    /** The lever above a wall pack: on the west wall, one block above the pack. */
    private static BlockPos leverOfPack(int[] pack) {
        return at(1, 5, pack[0]);
    }

    private static void setLever(ServerLevel level, BlockPos pos, boolean powered) {
        BlockState lever = level.getBlockState(pos);
        level.setBlock(pos, lever.setValue(LeverBlock.POWERED, powered), 3);
    }

    private static void build(ServerLevel level) {
        for (int x = 0; x < W; x++) {
            for (int z = 0; z < D; z++) {
                boolean shell = x == 0 || x == W - 1 || z == 0 || z == D - 1;
                level.setBlock(at(x, 0, z), Blocks.BLACK_CONCRETE.defaultBlockState(), 2);
                for (int y = 1; y <= H; y++) {
                    boolean wall = shell || y == H;
                    level.setBlock(at(x, y, z), wall ? Blocks.POLISHED_DEEPSLATE.defaultBlockState()
                            : Blocks.AIR.defaultBlockState(), 2);
                }
            }
        }
        Block[] bays = {BwrBlocks.LPS_HIGH_BAY.get(), BwrBlocks.HPS_HIGH_BAY.get(), BwrBlocks.MERCURY_HIGH_BAY.get()};
        Block[] packs = {BwrBlocks.LPS_WALL_PACK.get(), BwrBlocks.HPS_WALL_PACK.get(), BwrBlocks.MERCURY_WALL_PACK.get()};
        // The high bay hangs from the ceiling block (y = H), so it sits at y = H - 1.
        for (int[] bay : HIGH_BAYS) {
            level.setBlock(at(bay[0], H - 1, bay[1]), bays[bay[2]].defaultBlockState(), 2);
        }
        for (int[] pack : WALL_PACKS) {
            BlockState state = packs[pack[1]].defaultBlockState().setValue(WallPackLampBlock.FACING, Direction.EAST);
            level.setBlock(at(1, 4, pack[0]), state, 2);
        }
        // The levers go in last: a lever that is placed powered powers its lamp at once.
        for (int[] bay : HIGH_BAYS) {
            BlockState lever = Blocks.LEVER.defaultBlockState()
                    .setValue(LeverBlock.FACE, AttachFace.CEILING).setValue(LeverBlock.FACING, Direction.NORTH)
                    .setValue(LeverBlock.POWERED, true);
            level.setBlock(leverOfBay(bay), lever, 3);
        }
        for (int[] pack : WALL_PACKS) {
            BlockState lever = Blocks.LEVER.defaultBlockState()
                    .setValue(LeverBlock.FACE, AttachFace.WALL).setValue(LeverBlock.FACING, Direction.EAST)
                    .setValue(LeverBlock.POWERED, true);
            level.setBlock(leverOfPack(pack), lever, 3);
        }
    }

    private static void logLevels(Minecraft mc, String name) {
        StringBuilder line = new StringBuilder("LAMP CLIENT CHECK light for " + name + ":");
        for (int i = 0; i < HIGH_BAYS.length; i++) {
            BlockState s = mc.level.getBlockState(at(HIGH_BAYS[i][0], H - 1, HIGH_BAYS[i][1]));
            line.append(" bay").append(i).append('=').append(s.getLightEmission())
                    .append(s.getValue(IndustrialLampBlock.LIT) ? "(lit" : "(dark")
                    .append(",w").append(s.getValue(IndustrialLampBlock.WARMUP))
                    .append(",c").append(s.getValue(IndustrialLampBlock.COOLING)).append(')');
        }
        for (int i = 0; i < WALL_PACKS.length; i++) {
            BlockState s = mc.level.getBlockState(at(1, 4, WALL_PACKS[i][0]));
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
