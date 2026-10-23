package dev.bwr.mod.devtest;

import com.mojang.logging.LogUtils;
import dev.bwr.core.refuelling.BridgeMotion;
import dev.bwr.core.refuelling.BridgeMotion.Phase;
import dev.bwr.mod.BwrMod;
import dev.bwr.mod.gui.RefuellingMenu;
import dev.bwr.mod.radiation.RadiationReadout;
import dev.bwr.mod.radiation.client.RadiationClient;
import dev.bwr.mod.refuelling.BridgeSite;
import dev.bwr.mod.refuelling.RefuellingBridgeBlockEntity;
import dev.bwr.mod.refuelling.RefuellingBridgeBlockEntity.Loc;
import dev.bwr.mod.refuelling.RefuellingBridgeBlockEntity.Move;
import dev.bwr.mod.registry.BwrItems;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
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
 * Disposable real-client check of the refuelling bridge (run with {@code -PbwrRefuellingBridgeCheck} through run-mc.ps1): builds the
 * whole refuelling floor of {@link RefuellingBridgeFixture} in a flat world as a player would and saves 1920 x 1080 pictures to
 * {@code run/turbineModelCheck/refuelling-bridge-check/}: the cattle chute with its gate open and closed from the refuel floor and
 * from the side, the bridge spanning the cavity and the pool, the mast lowered into the core, an assembly carried through the chute
 * under water, the assembly set into a rack, the control screen, and the survey meter's HUD with an assembly lifted out of the water.
 * The moves are real (the bridge's own scheduled tick logic, stepped from the server thread); the development hook
 * {@code devHold} stops a move where the picture is wanted. Not shipped: the devtest package is stripped from the release jar.
 */
@EventBusSubscriber(modid = BwrMod.MOD_ID, value = Dist.CLIENT)
public final class RefuellingBridgeClientCheck {

    private static final BlockPos O = new BlockPos(200, 200, 200);
    private static final String[] NAMES = {"chute-open-floor", "chute-closed-floor", "chute-open-side", "chute-closed-side", "bridge-span",
            "mast-in-core", "assembly-through-chute", "assembly-in-rack", "control-screen", "survey-meter-spike"};

    private record Shot(Vec3 stand, Vec3 target) {
    }

    private static Vec3 v(double x, double y, double z) {
        return new Vec3(O.getX() + x, O.getY() + y, O.getZ() + z);
    }

    private static final Shot[] SHOTS = {
            new Shot(v(4.5, 10.4, -1.0), v(7.5, 8.6, 2.5)),
            new Shot(v(4.5, 10.4, -1.0), v(7.5, 8.6, 2.5)),
            new Shot(v(7.5, 10.9, -1.2), v(7.5, 8.6, 2.5)),
            new Shot(v(7.5, 10.9, -1.2), v(7.5, 8.6, 2.5)),
            new Shot(v(-6.0, 15.0, -9.0), v(7.5, 9.0, 2.5)),
            new Shot(v(7.0, 15.5, -5.0), v(2.5, 6.0, 2.5)),
            new Shot(v(6.1, 1.6, 2.5), v(8.7, 3.2, 2.5)),
            new Shot(v(14.8, 11.0, 5.75), v(11.75, 6.6, 5.75)),
            new Shot(v(8.0, 11.0, 0.0), v(8.0, 8.0, 2.5)),
            new Shot(v(19.5, 11.5, 5.25), v(10.75, 9.2, 5.25))};

    private static boolean started, requested, standing, built;
    private static volatile Throwable failure;
    private static volatile Vec3 aim;
    private static int age, joined, stage, shown;
    private static RefuellingBridgeFixture fixture;

    private RefuellingBridgeClientCheck() {
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post ignored) {
        if (!Boolean.getBoolean("bwr.refuellingBridgeCheck")) {
            return;
        }
        try {
            step();
        } catch (Throwable t) {
            LogUtils.getLogger().error("REFUELLING BRIDGE CLIENT CHECK FAILED at stage {}", stage < NAMES.length ? NAMES[stage] : "end", t);
            Minecraft.getInstance().stop();
        }
    }

    private static void step() throws Exception {
        Minecraft mc = Minecraft.getInstance();
        if (failure != null) {
            throw new IllegalStateException("server side of the refuelling bridge check failed", failure);
        }
        if (++age > 9000) {
            throw new IllegalStateException("timed out at stage " + stage);
        }
        if (!started && mc.screen != null && mc.getOverlay() == null) {
            started = true;
            mc.options.pauseOnLostFocus = false;
            mc.options.cloudStatus().set(net.minecraft.client.CloudStatus.OFF);
            mc.options.renderDistance().set(10);
            mc.options.fov().set(70);
            mc.options.guiScale().set(3);
            mc.createWorldOpenFlows().createFreshLevel("bwr-bridge-check-" + System.currentTimeMillis(),
                    new LevelSettings("Refuelling bridge check", GameType.CREATIVE, false, Difficulty.PEACEFUL, true, new GameRules(), WorldDataConfiguration.DEFAULT),
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
                LogUtils.getLogger().info("REFUELLING BRIDGE CLIENT CHECK PASS: {} pictures", stage);
                mc.stop();
                return;
            }
            requested = true;
            shown = 0;
            standing = false;
            int current = stage;
            if (current != 8) {
                mc.setScreen(null);
            }
            mc.options.hideGui = current != 8 && current != 9;
            mc.options.guiScale().set(current == 9 ? 2 : 3);
            mc.options.fov().set(current == 6 ? 100 : 70);
            server.execute(() -> {
                try {
                    ServerPlayer player = server.getPlayerList().getPlayer(uuid);
                    ServerLevel level = player.serverLevel();
                    if (!built) {
                        build(level);
                        built = true;
                    }
                    act(level, player, current);
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
        if (!standing || aim == null) {
            return;
        }
        Shot shot = SHOTS[stage];
        mc.player.setPos(shot.stand.x, shot.stand.y, shot.stand.z);
        mc.player.setDeltaMovement(Vec3.ZERO);
        mc.player.setYRot((float) aim.x);
        mc.player.setXRot((float) aim.y);
        mc.player.yRotO = (float) aim.x;
        mc.player.xRotO = (float) aim.y;
        if (mc.player.position().distanceToSqr(shot.stand) > 0.25) {
            return;
        }
        if (stage == 8 && !(mc.player.containerMenu instanceof RefuellingMenu m && m.bridgeMode && m.map != null && m.bridgeBusy)) {
            return;
        }
        if (++shown < 110) {
            return;
        }
        if (stage == 9) {
            if (!RadiationClient.showing()) {
                throw new AssertionError("The survey meter readout is not showing");
            }
            double rate = RadiationReadout.doseRate();
            if (!(rate > 1000.0)) {
                throw new AssertionError("The meter reads too little with the assembly out of the water: " + rate + " mSv/h");
            }
            LogUtils.getLogger().info("REFUELLING BRIDGE CLIENT CHECK meter HUD reads {} mSv/h", rate);
        }
        var dir = mc.gameDirectory.toPath().resolve("refuelling-bridge-check");
        Files.createDirectories(dir);
        dev.bwr.mod.devtest.CheckShots.save(mc, dir.resolve(NAMES[stage] + ".png"));
        LogUtils.getLogger().info("REFUELLING BRIDGE CLIENT CHECK: captured {}", NAMES[stage]);
        if (stage == 8) {
            mc.player.closeContainer();
        }
        requested = false;
        stage++;
    }

    private static void build(ServerLevel level) throws Exception {
        for (int x = 4; x <= 14; x++) {
            for (int z = 4; z <= 14; z++) {
                level.setChunkForced(x, z, true);
                level.getChunk(x, z);
            }
        }
        for (var at : BlockPos.betweenClosed(new BlockPos(O.getX() - 12, O.getY() - 4, O.getZ() - 12), new BlockPos(O.getX() + 32, O.getY() - 4, O.getZ() + 20))) {
            level.setBlock(at, Blocks.SMOOTH_STONE.defaultBlockState(), 3);
        }
        fixture = RefuellingBridgeFixture.build(level, O, 6);
        // the refuel floor: deck wherever the reactor roof, the pool and the chute do not already stand (the pool stays open)
        for (var at : BlockPos.betweenClosed(new BlockPos(O.getX() - 8, O.getY() + 8, O.getZ() - 8), new BlockPos(O.getX() + 26, O.getY() + 8, O.getZ() + 14))) {
            int dx = at.getX() - O.getX();
            int dz = at.getZ() - O.getZ();
            boolean poolWater = dx >= 9 && dx <= 17 && dz >= -2 && dz <= 6;
            if (!poolWater && level.getBlockState(at).isAir()) {
                level.setBlock(at, Blocks.SMOOTH_STONE.defaultBlockState(), 3);
            }
        }
        fixture.fillPool(7.0);
        for (int i = 0; i < 60; i++) {
            fixture.reactor.loadAssembly(fixture.corePosition(i), RefuellingBridgeFixture.fresh(18000.0 + 500.0 * i));
        }
        long now = level.getGameTime();
        var racks = fixture.pool.racks;
        for (int k = 0; k < 6; k++) {
            // three just discharged in the first rack, three a month old in the second
            fixture.pool.be.insertAt(racks.get(k < 3 ? 0 : 1), k < 3 ? k : k - 3, SpentFuelPoolFixture.assembly(45000.0, k < 3 ? 0.0 : 86400.0 * 30, now));
        }
        fixture.pool.be.simulateSeconds(1, now);
        LogUtils.getLogger().info("REFUELLING BRIDGE CLIENT CHECK server: bridge surveyed {}, chute {}, {} assemblies",
                fixture.bridge.refreshSite().ok(), fixture.bridge.chute().state(), fixture.assemblies());
    }

    private static void stepUntil(RefuellingBridgeBlockEntity bridge, ServerLevel level, java.util.function.BooleanSupplier done) {
        int n = 0;
        while (bridge.busy() && !done.getAsBoolean() && n++ < 60_000) {
            bridge.scheduledTick(level);
        }
    }

    private static void act(ServerLevel level, ServerPlayer player, int stage) {
        RefuellingBridgeFixture f = fixture;
        RefuellingBridgeBlockEntity bridge = f.bridge;
        switch (stage) {
            case 0, 2 -> {
                f.openGate();
                bridge.refreshSite();
            }
            case 1, 3 -> {
                f.closeGate();
                bridge.refreshSite();
            }
            case 4 -> {
                f.openGate();
                bridge.refreshSite();
                // the gantry over the chute, mast retracted
                bridge.devPlace(new BridgeMotion.Point(O.getX() + 7.5, bridge.site().homeY(), O.getZ() + 2.5));
            }
            case 5 -> {
                bridge.devPlace(bridge.site().home());
                f.openGate();
                bridge.refreshSite();
                check(bridge.queue(new Move(Loc.core(f.corePosition(0)), Loc.rack(f.pool.racks.get(1), 3))) == null, "queue core to pool");
                stepUntil(bridge, level, () -> bridge.phase() == Phase.LATCH);
                bridge.devHold(true);
            }
            case 6 -> {
                bridge.devHold(false);
                stepUntil(bridge, level, () -> bridge.phase() == Phase.TRANSIT
                        && bridge.site().bodyAt(level, bridge.tip().x(), bridge.tip().z(), bridge.chute()) == BridgeSite.Body.CHUTE
                        && bridge.progress() > 0.75);
                check(!bridge.carried().isEmpty(), "carrying");
                bridge.devHold(true);
            }
            case 7 -> {
                bridge.devHold(false);
                stepUntil(bridge, level, () -> bridge.phase() == Phase.RAISE_EMPTY);
                check(!f.pool.be.stackAt(f.pool.racks.get(1), 3).isEmpty(), "set into the rack");
                bridge.devHold(true);
            }
            case 8 -> {
                bridge.devHold(false);
                stepUntil(bridge, level, () -> false);
                check(bridge.queue(new Move(Loc.core(f.corePosition(1)), Loc.rack(f.pool.racks.get(2), 0))) == null, "queue core to pool");
                check(bridge.queue(new Move(Loc.rack(f.pool.racks.get(0), 0), Loc.core(f.corePosition(61)))) == null, "queue pool to core");
                for (int i = 0; i < 700; i++) {
                    bridge.scheduledTick(level);
                }
                bridge.devHold(true);
                RefuellingMenu.openBridge(player, f.reactor, bridge);
            }
            case 9 -> {
                bridge.devHold(false);
                stepUntil(bridge, level, () -> false);
                bridge.setOverride(true);
                f.fillPool(7.0);
                var rack = f.pool.racks.get(0);
                check(bridge.queue(new Move(Loc.rack(rack, 1), Loc.rack(rack, 3))) == null, "queue pool to pool, overridden");
                stepUntil(bridge, level, () -> bridge.phase() == Phase.TRANSIT);
                bridge.devHold(true);
                player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(BwrItems.SURVEY_METER.get()));
            }
            default -> {
            }
        }
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }
}
