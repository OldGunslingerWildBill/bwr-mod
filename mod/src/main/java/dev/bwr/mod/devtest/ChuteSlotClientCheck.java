package dev.bwr.mod.devtest;

import com.mojang.logging.LogUtils;
import dev.bwr.mod.BwrMod;
import dev.bwr.mod.refuelling.CanalExchange;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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

import java.nio.file.Files;

/**
 * Disposable real-client check of the cattle chute cut as a slot (run with {@code -PbwrChuteSlotCheck} through run-mc.ps1): the
 * {@link ChuteSlotTests.Rig} (a reactor with its head off in a flooded stone well, a pool with the slot in its wall, the canal between)
 * with a refuel floor round it, in a flat world, and 1920 x 1080 pictures in {@code run/turbineModelCheck/chute-slot-check/}: the slot
 * from the refuel floor with the gate open and the pool and the well levelled through it, from over the pool and from over the well, and
 * with the gate closed holding the pool above the well. The water moves by the pool controller's own ticks. Not shipped.
 */
@EventBusSubscriber(modid = BwrMod.MOD_ID, value = Dist.CLIENT)
public final class ChuteSlotClientCheck {

    private static final BlockPos O = new BlockPos(400, 100, 400);
    private static final String[] NAMES = {"slot-open-from-floor", "slot-open-from-pool", "slot-open-from-well", "slot-gate-closed",
            "crane-lifting-gate-plug", "gate-on-laydown-stand"};

    private static boolean started, requested, standing, built;
    private static volatile Throwable failure;
    private static volatile Vec3 aim;
    private static volatile Vec3[] shot;
    private static int age, joined, stage, shown;
    private static ChuteSlotTests.Rig rig;

    private ChuteSlotClientCheck() {
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post ignored) {
        if (!Boolean.getBoolean("bwr.chuteSlotCheck")) {
            return;
        }
        try {
            step();
        } catch (Throwable t) {
            LogUtils.getLogger().error("CHUTE SLOT CLIENT CHECK FAILED at stage {}", stage < NAMES.length ? NAMES[stage] : "end", t);
            Minecraft.getInstance().stop();
        }
    }

    private static void step() throws Exception {
        Minecraft mc = Minecraft.getInstance();
        if (failure != null) {
            throw new IllegalStateException("server side of the chute slot check failed", failure);
        }
        if (++age > 6000) {
            throw new IllegalStateException("timed out at stage " + stage);
        }
        if (!started && mc.screen != null && mc.getOverlay() == null) {
            started = true;
            mc.options.pauseOnLostFocus = false;
            mc.options.cloudStatus().set(net.minecraft.client.CloudStatus.OFF);
            mc.options.renderDistance().set(8);
            mc.options.fov().set(70);
            mc.options.guiScale().set(3);
            mc.createWorldOpenFlows().createFreshLevel("bwr-chute-slot-check-" + System.currentTimeMillis(),
                    new LevelSettings("Chute slot check", GameType.CREATIVE, false, Difficulty.PEACEFUL, true, new GameRules(), WorldDataConfiguration.DEFAULT),
                    new WorldOptions(0, false, false),
                    r -> r.registryOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).createWorldDimensions(), mc.screen);
            return;
        }
        if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null || ++joined < 40) {
            return;
        }
        var server = mc.getSingleplayerServer();
        var uuid = mc.player.getUUID();
        if (!requested) {
            if (stage == NAMES.length) {
                LogUtils.getLogger().info("CHUTE SLOT CLIENT CHECK PASS: {} pictures", stage);
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
                    act(current);
                    Vec3[] s = shotFor(current);
                    Vec3 delta = s[1].subtract(s[0].add(0, player.getEyeHeight(), 0));
                    float yaw = (float) (Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90);
                    float pitch = (float) -Math.toDegrees(Math.atan2(delta.y, Math.hypot(delta.x, delta.z)));
                    shot = s;
                    aim = new Vec3(yaw, pitch, 0);
                    player.setNoGravity(true);
                    player.getAbilities().flying = true;
                    player.onUpdateAbilities();
                    level.setDayTime(6000);
                    level.setWeatherParameters(6000, 0, false, false);
                    player.connection.teleport(s[0].x, s[0].y, s[0].z, yaw, pitch);
                    standing = true;
                } catch (Throwable t) {
                    failure = t;
                }
            });
        }
        if (!standing || aim == null || shot == null) {
            return;
        }
        Vec3 stand = shot[0];
        mc.player.setPos(stand.x, stand.y, stand.z);
        mc.player.setDeltaMovement(Vec3.ZERO);
        mc.player.setYRot((float) aim.x);
        mc.player.setXRot((float) aim.y);
        mc.player.yRotO = (float) aim.x;
        mc.player.xRotO = (float) aim.y;
        if (mc.player.position().distanceToSqr(stand) > 0.25) {
            return;
        }
        if (++shown < 140) {
            return;
        }
        var ex = rig.exchange();
        LogUtils.getLogger().info("CHUTE SLOT CLIENT CHECK {}: {} pool y {} well y {} sill y {} flow {} kg/s", NAMES[stage], ex.state(),
                String.format("%.3f", ex.poolSurfaceY()), String.format("%.3f", ex.wellSurfaceY()), String.format("%.3f", ex.sillY()),
                String.format("%.0f", ex.flowKgPerS()));
        if (stage < 3 && ex.state() != CanalExchange.State.LEVEL && ex.state() != CanalExchange.State.TO_WELL && ex.state() != CanalExchange.State.TO_POOL) {
            throw new AssertionError("the open slot is not joined: " + ex.state());
        }
        if (stage == 3 && ex.state() != CanalExchange.State.GATE_CLOSED) {
            throw new AssertionError("the closed gate does not hold: " + ex.state());
        }
        if (stage == 4 && (crane == null || !crane.carriedGate())) {
            throw new AssertionError("the crane does not hold the gate plug");
        }
        if (stage == 5 && (!(mc.level.getBlockState(gateStand.above()).getBlock() instanceof dev.bwr.mod.refuelling.CanalGatePlugBlock)
                || ex.state() == CanalExchange.State.GATE_CLOSED)) {
            throw new AssertionError("the gate is not on its stand, or the slot is still closed: " + ex.state());
        }
        var dir = mc.gameDirectory.toPath().resolve("chute-slot-check");
        Files.createDirectories(dir);
        CheckShots.save(mc, dir.resolve(NAMES[stage] + ".png"));
        LogUtils.getLogger().info("CHUTE SLOT CLIENT CHECK: captured {}", NAMES[stage]);
        requested = false;
        stage++;
    }

    private static Vec3[] shotFor(int stage) {
        double x = rig.x1;
        double z = rig.zc;
        double t = rig.top;
        return switch (stage) {
            case 0, 3 -> new Vec3[]{new Vec3(x + 1.6, t + 4.0, z + 3.4), new Vec3(x + 2.5, t - 0.4, z + 0.5)};
            case 1 -> new Vec3[]{new Vec3(x + 8.2, t + 2.2, z + 0.5), new Vec3(x + 2.5, t - 0.8, z + 0.5)};
            case 4 -> new Vec3[]{new Vec3(x - 4.0, t + 7.5, z + 11.0), new Vec3(x + 3.5, t + 6.0, z + 0.5)};
            case 5 -> new Vec3[]{new Vec3(x + 8.0, t + 6.0, z + 3.5), new Vec3(x + 2.5, t + 3.0, z - 4.0)};
            default -> new Vec3[]{new Vec3(x - 3.0, t + 3.2, z + 0.5), new Vec3(x + 3.0, t - 0.6, z + 0.5)};
        };
    }

    private static void build(ServerLevel level) throws Exception {
        for (var at : BlockPos.betweenClosed(O.offset(-16, -4, -16), O.offset(28, -4, 16))) {
            level.setBlock(at, Blocks.SMOOTH_STONE.defaultBlockState(), 2);
        }
        rig = new ChuteSlotTests.Rig(level, O, 4, 10, 6);
        var env = rig.s.env();
        // the refuel floor round the well, the pool and the canal, level with their tops: the slot is a cut through it
        for (var at : BlockPos.betweenClosed(new BlockPos(env.min().getX() - 5, rig.top, rig.zc - 8), new BlockPos(rig.x1 + 13, rig.top, rig.zc + 8))) {
            boolean well = at.getX() >= env.min().getX() && at.getX() <= env.max().getX() && at.getZ() >= env.min().getZ() && at.getZ() <= env.max().getZ();
            boolean pool = at.getX() >= rig.x1 + 4 && at.getX() <= rig.x1 + 8 && at.getZ() >= rig.zc - 2 && at.getZ() <= rig.zc + 2;
            if (!well && !pool && level.getBlockState(at).isAir()) {
                level.setBlock(at, Blocks.SMOOTH_STONE.defaultBlockState(), 2);
            }
        }
        rig.s.survey();
        rig.pool.be.scanNow();
        LogUtils.getLogger().info("CHUTE SLOT CLIENT CHECK server: pool formed {}, slots {}, seal {}", rig.pool.be.isFormed(),
                rig.pool.be.layout() == null ? 0 : rig.pool.be.layout().slots().size(), rig.s.seal() == null ? "none" : rig.s.seal().english());
    }

    private static dev.bwr.mod.refuelling.BuildingCraneBlockEntity crane;
    private static BlockPos gateStand;

    /**
     * The crane over the hall (two runs of runway rails fourteen blocks over the floor, the console beside the first), the slot's gate made a two-block plug
     * (the plug the crane lifts), a Gate Laydown Stand on the floor beside the canal, and the pool and the well level: GATE OUT lifts the plug.
     */
    private static void craneAndPlug(ServerLevel level) {
        var env = rig.s.env();
        for (int y = rig.slotBottom; y <= rig.top; y++) {
            level.setBlock(new BlockPos(rig.columnsX[0], y, rig.zc), dev.bwr.mod.registry.BwrBlocks.CANAL_GATE_PLUG.get().defaultBlockState()
                    .setValue(dev.bwr.mod.refuelling.TransferCanalBlock.AXIS, net.minecraft.core.Direction.Axis.X)
                    .setValue(dev.bwr.mod.refuelling.TransferCanalBlock.TOP, y == rig.top)
                    .setValue(dev.bwr.mod.refuelling.TransferCanalBlock.FLOOR, y == rig.slotBottom), 3);
        }
        int railY = rig.top + 14;
        int lo = env.min().getX() - 4, hi = rig.x1 + 12;
        for (int x = lo; x <= hi; x++) {
            for (int z : new int[]{rig.zc - 9, rig.zc + 9}) {
                level.setBlock(new BlockPos(x, railY, z), dev.bwr.mod.registry.BwrBlocks.CRANE_RUNWAY_RAIL.get().defaultBlockState()
                        .setValue(dev.bwr.mod.refuelling.CraneRunwayRailBlock.AXIS, net.minecraft.core.Direction.Axis.X), 2);
            }
        }
        BlockPos console = new BlockPos(lo - 1, railY, rig.zc - 9);
        level.setBlock(console, dev.bwr.mod.registry.BwrBlocks.BUILDING_CRANE_CONSOLE.get().defaultBlockState(), 2);
        crane = (dev.bwr.mod.refuelling.BuildingCraneBlockEntity) level.getBlockEntity(console);
        gateStand = new BlockPos(rig.x1 + 2, rig.top + 1, rig.zc - 5);
        level.setBlock(gateStand, dev.bwr.mod.registry.BwrBlocks.GATE_LAYDOWN_STAND.get().defaultBlockState(), 2);
        crane.refreshSite();
        rig.poolAt(rig.top + 0.7);
        rig.wellAt(rig.top + 0.6);
    }

    private static void act(int stage) {
        switch (stage) {
            case 4 -> {
                var level = rig.l;
                craneAndPlug(level);
                var why = crane.queueGate(true, null);
                if (why != null) {
                    throw new IllegalStateException("GATE OUT refused: " + net.minecraft.network.chat.Component.Serializer.toJson(why, level.registryAccess()));
                }
                int n = 0;
                while (crane.busy() && crane.phase() != dev.bwr.core.refuelling.BridgeMotion.Phase.RAISE_LOADED && n++ < 100_000) {
                    crane.scheduledTick(level);
                }
                // half way up out of the slot
                for (int i = 0; i < Math.max(1, crane.phaseTicksLeft() * 2 / 3) && crane.busy(); i++) {
                    crane.scheduledTick(level);
                }
                crane.devHold(true);
                LogUtils.getLogger().info("CHUTE SLOT CLIENT CHECK crane: {} holding {} ({})", crane.phase(), crane.carriedGate(), crane.carriedSummary());
            }
            case 5 -> {
                crane.devHold(false);
                int n = 0;
                while (crane.busy() && n++ < 200_000) {
                    crane.scheduledTick(rig.l);
                }
                LogUtils.getLogger().info("CHUTE SLOT CLIENT CHECK crane: {} laid {}", crane.phase(), crane.gateLift().laid());
            }
            case 0 -> {
                rig.gate(true);
                rig.poolAt(rig.top + 0.8);
                rig.wellAt(rig.top + 0.2);
            }
            case 3 -> {
                rig.gate(false);
                rig.poolAt(rig.top + 0.8);
                rig.wellAt(rig.top - 1.2);
            }
            default -> {
            }
        }
    }
}
