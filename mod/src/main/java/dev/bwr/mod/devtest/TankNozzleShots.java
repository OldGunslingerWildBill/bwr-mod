package dev.bwr.mod.devtest;

import com.mojang.logging.LogUtils;
import dev.bwr.mod.BwrMod;
import dev.bwr.mod.eccs.CondensateStorageTankBlockEntity;
import dev.bwr.mod.eccs.CondensateTankShape;
import dev.bwr.mod.reactor.ReactorControllerBlockEntity;
import dev.bwr.mod.reactor.RpvWaterInjectionPortBlock;
import dev.bwr.mod.registry.BwrBlocks;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.PipeBlock;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.nio.file.Files;

/**
 * Before/after pictures of the condensate storage tank and the vessel's recirculation nozzles (test harness only, not shipped):
 * {@code :mod:runTurbineModelCheck -PbwrTankNozzleShots=<label>} through run-mc.ps1 saves 1920 x 1080 pictures to
 * {@code run/turbineModelCheck/tank-nozzle-shots/<label>/}. A disposable flat world holds the smallest (3 x 3), a small (5 x 6) and
 * the largest (15 x 24) tank with two water pipes on every flange, and a formed 17 x 17 x 28 vessel with recirculation inlets and
 * outlets in its north wall, each with three water pipes. The check fails if a first pipe does not join its flange.
 */
@EventBusSubscriber(modid = BwrMod.MOD_ID, value = Dist.CLIENT)
public final class TankNozzleShots {
    private static final BlockPos T_MIN = new BlockPos(296, 115, 300), T_SMALL = new BlockPos(305, 115, 300), T_LARGE = new BlockPos(324, 115, 300);
    /** Interior minimum corner of the 15 x 15 x 25 (17 x 17 x 28 envelope) vessel. */
    private static final BlockPos V = new BlockPos(300, 121, 332);

    /** A loose (unformed) stretch of vessel wall with an outlet, an inlet and a water port in it, as a player sees them before forming. */
    private static final BlockPos WALL = new BlockPos(286, 115, 322);

    private record Shot(String name, Vec3 eye, Vec3 target) {
    }

    private static Vec3 at(BlockPos p, double x, double y, double z) {
        return new Vec3(p.getX() + x, p.getY() + y, p.getZ() + z);
    }

    private static final Shot[] SHOTS = {
            new Shot("cst-small-nw", at(T_SMALL, -7.5, 6, -8.5), at(T_SMALL, .5, 3, .5)),
            new Shot("cst-small-se", at(T_SMALL, 8.5, 2.5, 7.5), at(T_SMALL, .5, 2.6, .5)),
            new Shot("cst-small-group", at(T_SMALL, 2, 9, -15), at(T_SMALL, 0, 2, .5)),
            new Shot("cst-large-n", at(T_LARGE, -6, 9, -27), at(T_LARGE, .5, 10, .5)),
            new Shot("cst-large-ne-roof", at(T_LARGE, 15, 31, -15), at(T_LARGE, .5, 21, .5)),
            new Shot("cst-large-base", at(T_LARGE, -9, 3.2, -12.5), at(T_LARGE, 0, 2.2, -4)),
            new Shot("nozzles-close", at(V, 2.5, 3.8, -8.5), at(V, 7, 1.2, -1)),
            new Shot("nozzles-low-ne", at(V, 14, .2, -6.5), at(V, 7, 1.6, -1)),
            new Shot("nozzles-vessel", at(V, -6, 9, -26), at(V, 7.5, 9, 0)),
            new Shot("nozzle-outlet-high", at(V, 3.5, 16.5, -6), at(V, 7.5, 14.2, -1)),
            new Shot("port-blocks", at(WALL, 1.0, 2.3, -4.2), at(WALL, 3.0, 1.4, 0.5)),
            new Shot("port-blocks-close", at(WALL, 2.2, 2.0, -2.1), at(WALL, 3.0, 1.5, 0.5)),
    };
    /** A label starting with "ports" takes only the port block pictures (for comparing the unformed block models alone). */
    private static boolean portsOnly() { return label().startsWith("ports"); }

    private static boolean started, requested, standing, built;
    private static volatile Throwable failure;
    private static volatile Vec3 aim;
    private static int age, joined, stage, shown;

    private TankNozzleShots() {
    }

    private static String label() {
        return System.getProperty("bwr.tankNozzleShots", "");
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post ignored) {
        if (label().isEmpty()) return;
        try {
            step();
        } catch (Throwable t) {
            LogUtils.getLogger().error("TANK NOZZLE SHOTS FAILED at stage {}", stage, t);
            Minecraft.getInstance().stop();
        }
    }

    private static void step() throws Exception {
        Minecraft mc = Minecraft.getInstance();
        if (failure != null) throw new IllegalStateException("server side of the tank/nozzle shots failed", failure);
        if (++age > 12000) throw new IllegalStateException("timed out at stage " + stage);
        if (!started && mc.screen != null && mc.getOverlay() == null) {
            started = true;
            mc.options.pauseOnLostFocus = false;
            mc.options.cloudStatus().set(CloudStatus.OFF);
            mc.options.renderDistance().set(12);
            mc.options.fov().set(60);
            mc.createWorldOpenFlows().createFreshLevel("bwr-tank-nozzle-shots-" + System.currentTimeMillis(),
                    new LevelSettings("Tank and nozzle shots", GameType.CREATIVE, false, Difficulty.PEACEFUL, true, new GameRules(), WorldDataConfiguration.DEFAULT),
                    new WorldOptions(0, false, false),
                    r -> r.registryOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).createWorldDimensions(), mc.screen);
            return;
        }
        if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null || ++joined < 40) return;
        var server = mc.getSingleplayerServer();
        var uuid = mc.player.getUUID();
        if (portsOnly() && stage < SHOTS.length - 2) stage = SHOTS.length - 2;
        if (!requested) {
            if (stage == SHOTS.length) {
                LogUtils.getLogger().info("TANK NOZZLE SHOTS PASS: {} pictures, every tank flange and recirculation nozzle joined to its first pipe", stage);
                mc.options.hideGui = false;
                mc.stop();
                return;
            }
            requested = true;
            shown = 0;
            standing = false;
            int current = stage;
            mc.setScreen(null);
            mc.options.hideGui = true;
            server.execute(() -> {
                try {
                    ServerPlayer player = server.getPlayerList().getPlayer(uuid);
                    ServerLevel level = player.serverLevel();
                    if (!built) {
                        build(level, player);
                        built = true;
                    }
                    Shot shot = SHOTS[current];
                    Vec3 delta = shot.target.subtract(shot.eye);
                    float yaw = (float) (Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90);
                    float pitch = (float) -Math.toDegrees(Math.atan2(delta.y, Math.hypot(delta.x, delta.z)));
                    aim = new Vec3(yaw, pitch, 0);
                    player.setNoGravity(true);
                    player.getAbilities().flying = true;
                    player.onUpdateAbilities();
                    level.setDayTime(6000);
                    level.setWeatherParameters(6000, 0, false, false);
                    player.connection.teleport(shot.eye.x, shot.eye.y - player.getEyeHeight(), shot.eye.z, yaw, pitch);
                    standing = true;
                } catch (Throwable t) {
                    failure = t;
                }
            });
        }
        if (!standing || aim == null) return;
        Shot shot = SHOTS[stage];
        var stand = shot.eye.subtract(0, mc.player.getEyeHeight(), 0);
        mc.player.getAbilities().flying = true;
        mc.player.setPos(stand.x, stand.y, stand.z);
        mc.player.setDeltaMovement(Vec3.ZERO);
        mc.player.setYRot((float) aim.x);
        mc.player.setXRot((float) aim.y);
        mc.player.yRotO = (float) aim.x;
        mc.player.xRotO = (float) aim.y;
        mc.getToasts().clear();
        if (++shown < (stage == 0 ? 220 : 110)) return;
        var dir = mc.gameDirectory.toPath().resolve("tank-nozzle-shots").resolve(label());
        Files.createDirectories(dir);
        var file = dir.resolve(shot.name + ".png");
        CheckShots.save(mc, file);
        LogUtils.getLogger().info("TANK NOZZLE SHOT: {}", file.toAbsolutePath());
        requested = false;
        stage++;
    }

    private static void build(ServerLevel level, ServerPlayer player) throws Exception {
        for (int cx = 280 >> 4; cx <= 350 >> 4; cx++) {
            for (int cz = 285 >> 4; cz <= 360 >> 4; cz++) {
                level.setChunkForced(cx, cz, true);
                level.getChunk(cx, cz);
            }
        }
        for (var p : BlockPos.betweenClosed(new BlockPos(280, 114, 285), new BlockPos(350, 114, 315))) level.setBlock(p, Blocks.SMOOTH_STONE.defaultBlockState(), 2);
        // the vessel stands on a floor just under its control rod drive layer (envelope floor - 1)
        for (var p : BlockPos.betweenClosed(new BlockPos(280, 114, 316), new BlockPos(350, 114, 360))) level.setBlock(p, Blocks.SMOOTH_STONE.defaultBlockState(), 2);
        for (var p : BlockPos.betweenClosed(new BlockPos(292, V.getY() - 3, 320), new BlockPos(323, V.getY() - 3, 355))) level.setBlock(p, Blocks.SMOOTH_STONE.defaultBlockState(), 2);
        int[][] sizes = {{3, 3}, {5, 6}, {15, 24}};
        BlockPos[] roots = {T_MIN, T_SMALL, T_LARGE};
        for (int i = 0; i < 3; i++) {
            CondensateStorageTankBlockEntity be = CondensateTankRuntimeCheck.buildShell(level, player, roots[i], sizes[i][0], sizes[i][1]);
            if (!be.assembled()) throw new AssertionError("tank " + sizes[i][0] + "x" + sizes[i][1] + " did not form");
            be.fillKg(Math.min(100000, be.capacityKg() / 2));
            for (var face : Direction.Plane.HORIZONTAL) pipes(level, roots[i].offset(CondensateTankShape.port(be.diameter, face)), face, 2);
        }
        for (int x = 0; x < 7; x++) for (int y = 0; y < 3; y++) level.setBlock(WALL.offset(x, y, 0), BwrBlocks.REACTOR_VESSEL.get().defaultBlockState(), 3);
        port(level, WALL.offset(2, 1, 0), BwrBlocks.RECIRCULATION_OUTLET.get());
        port(level, WALL.offset(4, 1, 0), BwrBlocks.RECIRCULATION_INLET.get());
        port(level, WALL.offset(6, 1, 0), BwrBlocks.RPV_WATER_INJECTION_PORT.get());
        pipes(level, WALL.offset(4, 1, 0), Direction.NORTH, 2);
        ReactorControllerBlockEntity vessel = CompactCoreRegressionTests.build(level, V, 15, 15, 25, false);
        // recirculation riser inlets low on the north wall beside the outlet, as on a real vessel; a second outlet in the upper half,
        // where the mod's loop rule asks for one
        int[][] inlets = {{3, 1}, {5, 1}, {9, 1}, {11, 1}};
        for (int[] n : inlets) port(level, V.offset(n[0], n[1], -1), BwrBlocks.RECIRCULATION_INLET.get());
        port(level, V.offset(7, 0, -1), BwrBlocks.RECIRCULATION_OUTLET.get());
        port(level, V.offset(7, 14, -1), BwrBlocks.RECIRCULATION_OUTLET.get());
        SpargerRegressionTests.validate(vessel);
        if (!vessel.isFormed()) throw new AssertionError("vessel with recirculation nozzles did not form: " + vessel.statusLines());
        for (int[] n : inlets) pipes(level, V.offset(n[0], n[1], -1), Direction.NORTH, 3);
        pipes(level, V.offset(7, 0, -1), Direction.NORTH, 3);
        pipes(level, V.offset(7, 14, -1), Direction.NORTH, 3);
    }

    private static void port(ServerLevel level, BlockPos at, Block block) {
        level.setBlock(at, block.defaultBlockState().setValue(RpvWaterInjectionPortBlock.FACING, Direction.NORTH), 3);
    }

    /** Water pipes outward from a flange; the first must join back to it. */
    private static void pipes(ServerLevel level, BlockPos port, Direction face, int count) {
        for (int n = 1; n <= count; n++) {
            var p = port.relative(face, n);
            level.setBlock(p, BwrBlocks.HIGH_PRESSURE_WATER_PIPE.get().stateWithConnections(level, p), 3);
        }
        var first = level.getBlockState(port.relative(face));
        var property = PipeBlock.PROPERTY_BY_DIRECTION.get(face.getOpposite());
        if (!first.hasProperty(property) || !first.getValue(property))
            throw new IllegalStateException("pipe does not join the flange at " + port + " " + face + ": " + first);
    }
}
