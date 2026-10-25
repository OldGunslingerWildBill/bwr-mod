package dev.bwr.mod.devtest;

import com.mojang.logging.LogUtils;
import dev.bwr.mod.BwrMod;
import dev.bwr.mod.eccs.AssemblyPort;
import dev.bwr.mod.eccs.PumpAssemblyBlock;
import dev.bwr.mod.registry.BwrBlocks;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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

/**
 * Disposable real-client pictures of the recirculation equipment ({@code :mod:runRecircRemodelCheck} through run-mc.ps1),
 * 1920 x 1080, in {@code run/turbineModelCheck/recirc-remodel-check/}: the external recirculation pump from four sides with
 * high-pressure water pipe on both flanges (the pipes must meet the flanges), a second pump turned east, a matched pair of
 * jet pumps plus a third turned east, and a reactor internal pump. Every machine is placed the way a player places it
 * ({@code placementState} and {@code setPlacedBy}). Not shipped; the devtest package is stripped from the jar.
 */
@EventBusSubscriber(modid = BwrMod.MOD_ID, value = Dist.CLIENT)
public final class RecircRemodelClientCheck {
    // On the superflat grass level (top at y -61), so no light has to be computed for new sky sections.
    private static final BlockPos O = new BlockPos(600, -60, 600);
    private static final String[] NAMES = {"1-recirc-pump-front", "2-recirc-pump-back", "3-recirc-pump-stand-close", "4-recirc-pump-flanges-low",
            "5-recirc-pump-side", "6-jet-pumps-front", "7-jet-pumps-back", "8-rip-front", "9-rip-back", "10-row-overview"};

    private record Shot(Vec3 stand, Vec3 target) {
    }

    private static Vec3 v(double x, double y, double z) {
        return new Vec3(O.getX() + x, O.getY() + y, O.getZ() + z);
    }

    private static final Shot[] SHOTS = {
            new Shot(v(-5.5, 6.5, -8.5), v(.5, 4.2, .5)),
            new Shot(v(7, 6.5, 9.5), v(.5, 4.2, .5)),
            new Shot(v(-1.2, 4.2, -4.2), v(.5, 4.4, .5)),
            new Shot(v(5.5, 1.2, -6.5), v(.5, 1.6, .5)),
            new Shot(v(9.5, 5, -1.5), v(.5, 4.2, .5)),
            new Shot(v(-11, 3.6, -3.6), v(-9, 3.2, .5)),
            new Shot(v(-7, 3.6, 4.6), v(-9, 3.2, .5)),
            new Shot(v(-13, 3.2, 5.2), v(-8.5, 2.6, 9)),
            new Shot(v(-4.5, 3.6, 12.5), v(-8.5, 2.6, 9)),
            new Shot(v(-2, 9, -16), v(1, 3.5, 2))};

    private static boolean started, requested, standing, built;
    private static volatile Throwable failure;
    private static volatile Vec3 aim;
    private static int age, joined, stage, shown;

    private RecircRemodelClientCheck() {
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post ignored) {
        if (!Boolean.getBoolean("bwr.recircRemodelCheck")) return;
        try {
            step();
        } catch (Throwable t) {
            LogUtils.getLogger().error("RECIRC REMODEL CLIENT CHECK FAILED at stage {}", stage < NAMES.length ? NAMES[stage] : "end", t);
            Minecraft.getInstance().stop();
        }
    }

    private static void step() throws Exception {
        Minecraft mc = Minecraft.getInstance();
        if (failure != null) throw new IllegalStateException("server side of the recirculation remodel check failed", failure);
        if (++age > 9000) throw new IllegalStateException("timed out at stage " + stage);
        if (!started && mc.screen != null && mc.getOverlay() == null) {
            started = true;
            mc.options.pauseOnLostFocus = false;
            mc.options.cloudStatus().set(net.minecraft.client.CloudStatus.OFF);
            mc.options.renderDistance().set(10);
            mc.options.fov().set(70);
            mc.createWorldOpenFlows().createFreshLevel("bwr-recirc-check-" + System.currentTimeMillis(),
                    new LevelSettings("Recirc remodel check", GameType.CREATIVE, false, Difficulty.PEACEFUL, true, new GameRules(), WorldDataConfiguration.DEFAULT),
                    new WorldOptions(0, false, false),
                    r -> r.registryOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).createWorldDimensions(), mc.screen);
            return;
        }
        if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null || ++joined < 40) return;
        var server = mc.getSingleplayerServer();
        var uuid = mc.player.getUUID();
        if (!requested) {
            if (stage == NAMES.length) {
                LogUtils.getLogger().info("RECIRC REMODEL CLIENT CHECK PASS: {} pictures", stage);
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
                        build(level);
                        built = true;
                    }
                    Shot shot = SHOTS[current];
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
        Shot shot = SHOTS[stage];
        mc.player.setPos(shot.stand.x, shot.stand.y, shot.stand.z);
        mc.player.setDeltaMovement(Vec3.ZERO);
        mc.player.setYRot((float) aim.x);
        mc.player.setXRot((float) aim.y);
        mc.player.yRotO = (float) aim.x;
        mc.player.xRotO = (float) aim.y;
        if (mc.player.position().distanceToSqr(shot.stand) > 0.25) return;
        if (++shown < (stage == 0 ? 400 : 100)) return;
        var dir = mc.gameDirectory.toPath().resolve("recirc-remodel-check");
        Files.createDirectories(dir);
        CheckShots.save(mc, dir.resolve(NAMES[stage] + ".png"));
        LogUtils.getLogger().info("RECIRC REMODEL CLIENT CHECK: captured {}", NAMES[stage]);
        requested = false;
        stage++;
    }

    private static void place(ServerLevel level, PumpAssemblyBlock block, BlockPos root, Direction facing) {
        var state = block.placementState().setValue(PumpAssemblyBlock.FACING, facing);
        level.setBlock(root, state, 3);
        block.setPlacedBy(level, root, state, null, new ItemStack(block));
        check(block.complete(level, root, state), "assembled " + block + " at " + root);
    }

    private static void pipes(ServerLevel level, BlockPos root) {
        var pump = BwrBlocks.RECIRCULATION_PUMP.get();
        var state = level.getBlockState(root);
        for (var role : new AssemblyPort[]{AssemblyPort.WATER_SUCTION, AssemblyPort.WATER_DISCHARGE}) {
            var port = pump.portPosition(root, state, role);
            var face = pump.portFace(state, role);
            for (int i = 1; i <= 3; i++) {
                var p = port.relative(face, i);
                level.setBlock(p, BwrBlocks.HIGH_PRESSURE_WATER_PIPE.get().stateWithConnections(level, p), 3);
            }
            for (int i = 1; i <= 3; i++) {
                var p = port.relative(face, i);
                level.setBlock(p, BwrBlocks.HIGH_PRESSURE_WATER_PIPE.get().stateWithConnections(level, p), 3);
            }
        }
    }

    private static void build(ServerLevel level) {
        for (int cx = (O.getX() - 40) >> 4; cx <= (O.getX() + 40) >> 4; cx++) {
            for (int cz = (O.getZ() - 40) >> 4; cz <= (O.getZ() + 40) >> 4; cz++) {
                level.setChunkForced(cx, cz, true);
                level.getChunk(cx, cz);
            }
        }
        for (var p : BlockPos.betweenClosed(O.offset(-40, -1, -40), O.offset(40, -1, 40))) level.setBlock(p, Blocks.SMOOTH_STONE.defaultBlockState(), 3);
        var rcp = BwrBlocks.RECIRCULATION_PUMP.get();
        place(level, rcp, O, Direction.NORTH);
        pipes(level, O);
        place(level, rcp, O.offset(12, 0, 2), Direction.EAST);
        pipes(level, O.offset(12, 0, 2));
        var jet = BwrBlocks.JET_PUMP.get();
        place(level, jet, O.offset(-10, 0, 0), Direction.NORTH);
        place(level, jet, O.offset(-8, 0, 0), Direction.SOUTH);
        place(level, jet, O.offset(-12, 0, 0), Direction.EAST);
        place(level, BwrBlocks.RIP_PUMP.get(), O.offset(-9, 3, 8), Direction.NORTH);
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }
}
