package dev.bwr.mod.devtest;

import com.mojang.logging.LogUtils;
import dev.bwr.mod.BwrMod;
import dev.bwr.mod.eccs.AssemblyPort;
import dev.bwr.mod.eccs.PumpAssemblyBlock;
import dev.bwr.mod.registry.BwrBlocks;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
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
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Disposable real-client pictures of the remodelled motor-driven pumps ({@code -PbwrMotorPumpShots} through run-mc.ps1),
 * 1920 x 1080, in {@code run/turbineModelCheck/motor-pump-shots/}, on the superflat ground (its sky light is already settled): LPCS, HPCS, RHR/LPCI, motor feedwater, SLC pump and SLC
 * tank, each placed facing north with three water-pipe segments on every port, from three angles. Not shipped.
 */
@EventBusSubscriber(modid = BwrMod.MOD_ID, value = Dist.CLIENT)
public final class MotorPumpShots {
    private static final BlockPos O = new BlockPos(600, -60, 600);
    private static final String[] IDS = {"lpcs", "hpcs", "rhr", "motor-feed", "slc-pump", "slc-tank"};
    private static final int[] X = {0, 26, 52, 78, 104, 110};
    private static final int[][] SIZE = {{5, 5, 3}, {9, 4, 3}, {7, 3, 3}, {7, 3, 3}, {3, 2, 3}, {3, 4, 3}};

    private record Shot(String name, Vec3 stand, Vec3 target) {
    }

    private static final List<Shot> SHOTS = new ArrayList<>();

    static {
        double[][] views = {{-.62, .42, .66}, {.72, .32, .62}, {.35, .45, -.82}};
        for (int i = 0; i < IDS.length; i++) {
            int[] s = SIZE[i];
            Vec3 target = new Vec3(O.getX() + X[i] + .5, O.getY() + s[1] * .42, O.getZ() + .5);
            double distance = Math.max(s[0] * .55, s[1] * .9) + 2.4;
            for (int k = 0; k < views.length; k++) {
                Vec3 dir = new Vec3(views[k][0], views[k][1], views[k][2]).normalize();
                SHOTS.add(new Shot(IDS[i] + "-" + "abc".charAt(k), target.add(dir.scale(distance)), target));
            }
        }
    }

    private static boolean started, requested, standing, built;
    private static volatile Throwable failure;
    private static volatile Vec3 aim;
    private static int age, joined, stage, shown;

    private MotorPumpShots() {
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post ignored) {
        if (!Boolean.getBoolean("bwr.motorPumpShots")) return;
        try {
            step();
        } catch (Throwable t) {
            LogUtils.getLogger().error("MOTOR PUMP SHOTS FAILED at {}", stage < SHOTS.size() ? SHOTS.get(stage).name : "end", t);
            Minecraft.getInstance().stop();
        }
    }

    private static void step() throws Exception {
        Minecraft mc = Minecraft.getInstance();
        if (failure != null) throw new IllegalStateException("server side of the motor pump shots failed", failure);
        if (++age > 20000) throw new IllegalStateException("timed out at stage " + stage);
        if (!started && mc.screen != null && mc.getOverlay() == null) {
            started = true;
            mc.options.pauseOnLostFocus = false;
            mc.options.cloudStatus().set(net.minecraft.client.CloudStatus.OFF);
            mc.options.renderDistance().set(10);
            mc.options.fov().set(60);
            mc.createWorldOpenFlows().createFreshLevel("bwr-motor-pumps-" + System.currentTimeMillis(),
                    new LevelSettings("Motor pump shots", GameType.CREATIVE, false, Difficulty.PEACEFUL, true, new GameRules(), WorldDataConfiguration.DEFAULT),
                    new WorldOptions(0, false, false),
                    r -> r.registryOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).createWorldDimensions(), mc.screen);
            return;
        }
        if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null || ++joined < 40) return;
        var server = mc.getSingleplayerServer();
        var uuid = mc.player.getUUID();
        if (!requested) {
            if (stage == SHOTS.size()) {
                LogUtils.getLogger().info("MOTOR PUMP SHOTS PASS: {} pictures", stage);
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
                    var player = server.getPlayerList().getPlayer(uuid);
                    ServerLevel level = player.serverLevel();
                    if (!built) {
                        build(level);
                        built = true;
                    }
                    Shot shot = SHOTS.get(current);
                    Vec3 delta = shot.target.subtract(shot.stand.add(0, player.getEyeHeight(), 0));
                    float yaw = (float) (Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90);
                    float pitch = (float) -Math.toDegrees(Math.atan2(delta.y, Math.hypot(delta.x, delta.z)));
                    aim = new Vec3(yaw, pitch, 0);
                    player.setNoGravity(true);
                    player.getAbilities().flying = true;
                    player.onUpdateAbilities();
                    level.setDayTime(6000);
                    level.setWeatherParameters(6000, 0, false, false);
                    player.connection.teleport(shot.stand.x, shot.stand.y, shot.stand.z, yaw, pitch);
                    standing = true;
                } catch (Throwable t) {
                    failure = t;
                }
            });
        }
        if (!standing || aim == null) return;
        Shot shot = SHOTS.get(stage);
        mc.player.setPos(shot.stand.x, shot.stand.y, shot.stand.z);
        mc.player.setDeltaMovement(Vec3.ZERO);
        mc.player.setYRot((float) aim.x);
        mc.player.setXRot((float) aim.y);
        mc.player.yRotO = (float) aim.x;
        mc.player.xRotO = (float) aim.y;
        if (mc.player.position().distanceToSqr(shot.stand) > 0.25) return;
        // The first picture waits for the platform's sky light to settle.
        if (++shown < (stage == 0 ? 600 : 100)) return;
        var dir = mc.gameDirectory.toPath().resolve("motor-pump-shots");
        Files.createDirectories(dir);
        CheckShots.save(mc, dir.resolve(shot.name + ".png"));
        LogUtils.getLogger().info("MOTOR PUMP SHOTS: captured {}", shot.name);
        requested = false;
        stage++;
    }

    private static void build(ServerLevel level) {
        for (int cx = (O.getX() - 24) >> 4; cx <= (O.getX() + 136) >> 4; cx++)
            for (int cz = (O.getZ() - 24) >> 4; cz <= (O.getZ() + 24) >> 4; cz++) {
                level.setChunkForced(cx, cz, true);
                level.getChunk(cx, cz);
            }
        for (var p : BlockPos.betweenClosed(O.offset(-24, -1, -20), O.offset(136, -1, 20))) level.setBlock(p, Blocks.SMOOTH_STONE.defaultBlockState(), 3);
        PumpAssemblyBlock[] blocks = {BwrBlocks.LPCS_PUMP.get(), BwrBlocks.HPCS_PUMP.get(), BwrBlocks.RHR_PUMP.get(), BwrBlocks.MOTOR_FEED_PUMP.get(),
                BwrBlocks.SLC_PUMP.get(), BwrBlocks.SLC_BORON_TANK.get()};
        for (int i = 0; i < blocks.length; i++) {
            var pump = blocks[i];
            BlockPos at = O.offset(X[i], 0, 0);
            var state = pump.placementState();
            level.setBlock(at, state, 3);
            pump.setPlacedBy(level, at, state, null, new ItemStack(pump));
            for (var port : pump.ports(state)) {
                var atPort = pump.portPosition(at, state, port.role());
                var face = pump.portFace(state, port.role());
                for (int k = 1; k <= 3; k++) {
                    var p = atPort.relative(face, k);
                    var pipe = BwrBlocks.HIGH_PRESSURE_WATER_PIPE.get().stateWithConnections(level, p);
                    if (port.role() == AssemblyPort.WATER_DISCHARGE)
                        pipe = pipe.setValue(dev.bwr.mod.piping.PaintedPipeBlock.PAINT, dev.bwr.mod.piping.PipePaint.RED);
                    level.setBlock(p, pipe, 3);
                }
            }
        }
    }
}
