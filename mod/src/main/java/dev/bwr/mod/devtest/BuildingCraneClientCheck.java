package dev.bwr.mod.devtest;

import com.mojang.logging.LogUtils;
import dev.bwr.core.refuelling.BridgeMotion.Phase;
import dev.bwr.core.refuelling.BridgeMotion.Point;
import dev.bwr.mod.BwrMod;
import dev.bwr.mod.client.MachineMeshCache;
import dev.bwr.mod.gui.CraneMenu;
import dev.bwr.mod.gui.StudTensionerMenu;
import dev.bwr.mod.reactor.VesselInternal;
import dev.bwr.mod.registry.BwrBlocks;
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
 * Disposable real-client check of the reactor building crane (run with {@code -PbwrBuildingCraneCheck} through run-mc.ps1): builds the
 * refuel floor of {@link CraneFixture} in a flat world as a player would and saves 1920 x 1080 pictures to
 * {@code run/turbineModelCheck/building-crane-check/}: the crane spanning the refuel floor, the stud tensioner's tool on the flange part
 * way round and its screen, the studs bare with every nut off, the head hanging from the hook, the head on its stand, the dryer on the
 * hook, and the crane's pendant screen. The moves are real (the block entities' own scheduled tick logic, stepped from the server
 * thread); the development hook {@code devHold} stops a lift where the picture is wanted. Not shipped: the devtest package is stripped
 * from the release jar.
 */
@EventBusSubscriber(modid = BwrMod.MOD_ID, value = Dist.CLIENT)
public final class BuildingCraneClientCheck {

    private static final BlockPos O = new BlockPos(200, 200, 200);
    private static final String[] NAMES = {"crane-over-the-refuel-floor", "tensioner-on-the-flange", "tensioner-screen", "studs-detensioned-nuts-off",
            "head-on-the-hook", "head-on-its-stand", "dryer-on-the-hook", "crane-pendant-screen"};

    private record Shot(Vec3 stand, Vec3 target, int fov) {
    }

    private static Vec3 v(double x, double y, double z) {
        return new Vec3(O.getX() + x, O.getY() + y, O.getZ() + z);
    }

    private static final Shot[] SHOTS = {
            new Shot(v(-7.0, 15.5, -15.0), v(5.0, 11.5, -2.0), 72),
            new Shot(v(-0.3, 9.8, 6.9), v(0.65, 8.35, 5.24), 55),
            new Shot(v(0.0, 12.0, 8.0), v(0.65, 8.3, 5.24), 70),
            new Shot(v(-0.1, 10.0, 6.7), v(0.65, 8.2, 5.2), 55),
            new Shot(v(-10.0, 15.0, -3.5), v(2.5, 12.0, -3.5), 72),
            new Shot(v(-4.3, 9.2, -12.2), v(2.5, 9.9, -8.5), 70),
            new Shot(v(-7.0, 16.5, 9.0), v(4.0, 12.5, 3.0), 70),
            // the pendant is used from beside the console (a menu closes when the player is more than 8 blocks from its block)
            new Shot(v(-3.0, 15.5, -10.5), v(-4.0, 14.0, -14.0), 70)};

    private static long uploadsAtStart;
    private static boolean started, requested, standing, built;
    private static volatile Throwable failure;
    private static volatile Vec3 aim;
    private static int age, joined, stage, shown;
    private static CraneFixture fixture;

    private BuildingCraneClientCheck() {
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post ignored) {
        if (!Boolean.getBoolean("bwr.buildingCraneCheck")) {
            return;
        }
        try {
            step();
        } catch (Throwable t) {
            LogUtils.getLogger().error("BUILDING CRANE CLIENT CHECK FAILED at stage {}", stage < NAMES.length ? NAMES[stage] : "end", t);
            Minecraft.getInstance().stop();
        }
    }

    private static boolean screenStage(int s) {
        return s == 2 || s == 7;
    }

    private static void step() throws Exception {
        Minecraft mc = Minecraft.getInstance();
        if (failure != null) {
            throw new IllegalStateException("server side of the building crane check failed", failure);
        }
        if (++age > 12000) {
            throw new IllegalStateException("timed out at stage " + stage);
        }
        if (!started && mc.screen != null && mc.getOverlay() == null) {
            started = true;
            mc.options.pauseOnLostFocus = false;
            mc.options.cloudStatus().set(net.minecraft.client.CloudStatus.OFF);
            mc.options.renderDistance().set(10);
            mc.options.fov().set(70);
            mc.options.guiScale().set(3);
            mc.createWorldOpenFlows().createFreshLevel("bwr-crane-check-" + System.currentTimeMillis(),
                    new LevelSettings("Building crane check", GameType.CREATIVE, false, Difficulty.PEACEFUL, true, new GameRules(), WorldDataConfiguration.DEFAULT),
                    new WorldOptions(0, false, false),
                    r -> r.registryOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).createWorldDimensions(), mc.screen);
            return;
        }
        if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null || ++joined < 40) {
            return;
        }
        var server = mc.getSingleplayerServer();
        var uuid = mc.player.getUUID();
        RenderEnvironment.log("building-crane");
        if (!requested) {
            if (stage == NAMES.length) {
                LogUtils.getLogger().info("BUILDING CRANE CLIENT CHECK PASS: {} pictures", stage);
                mc.stop();
                return;
            }
            requested = true;
            shown = 0;
            standing = false;
            int current = stage;
            if (!screenStage(current)) {
                mc.setScreen(null);
            }
            mc.options.hideGui = !screenStage(current);
            mc.options.guiScale().set(screenStage(current) ? 2 : 3);
            mc.options.fov().set(SHOTS[current].fov());
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
        if (stage == 2 && !(mc.player.containerMenu instanceof StudTensionerMenu m && m.present && m.reactorFound && m.mode != 0)) {
            return;
        }
        if (stage == 7 && !(mc.player.containerMenu instanceof CraneMenu m && m.present && m.ready && m.busy)) {
            return;
        }
        if (shown == 40) {
            uploadsAtStart = MachineMeshCache.uploadCount();
        }
        if (++shown < 110) {
            return;
        }
        if (stage == 0 || stage == 4 || stage == 5 || stage == 6) {
            // the parts are cached meshes: once drawn they are never built again, however the pose changes
            long rebuilt = MachineMeshCache.uploadCount() - uploadsAtStart;
            if (rebuilt != 0) {
                throw new AssertionError("The crane's meshes were uploaded again while standing still: " + rebuilt);
            }
            LogUtils.getLogger().info("BUILDING CRANE CLIENT CHECK {}: {} cached meshes, {} bytes, no upload while still", NAMES[stage],
                    MachineMeshCache.cachedMeshes(), MachineMeshCache.cachedBytes());
        }
        var dir = mc.gameDirectory.toPath().resolve("building-crane-check");
        Files.createDirectories(dir);
        dev.bwr.mod.devtest.CheckShots.save(mc, dir.resolve(NAMES[stage] + ".png"));
        LogUtils.getLogger().info("BUILDING CRANE CLIENT CHECK: captured {}", NAMES[stage]);
        if (screenStage(stage)) {
            mc.player.closeContainer();
        }
        requested = false;
        stage++;
    }

    private static void build(ServerLevel level) throws Exception {
        for (int x = 3; x <= 17; x++) {
            for (int z = 3; z <= 15; z++) {
                level.setChunkForced(x, z, true);
                level.getChunk(x, z);
            }
        }
        for (var at : BlockPos.betweenClosed(new BlockPos(O.getX() - 12, O.getY() - 4, O.getZ() - 18), new BlockPos(O.getX() + 32, O.getY() - 4, O.getZ() + 14))) {
            level.setBlock(at, Blocks.SMOOTH_STONE.defaultBlockState(), 3);
        }
        fixture = CraneFixture.build(level, O);
        // the refuelling bridge of the fixture is not wanted in these pictures: its rails and console go
        for (int x = -1; x <= 18; x++) {
            for (int z : new int[]{-3, 7}) {
                level.setBlock(O.offset(x, 9, z), Blocks.AIR.defaultBlockState(), 2);
            }
        }
        level.setBlock(fixture.base.console, Blocks.AIR.defaultBlockState(), 2);
        // the refuel floor: deck wherever the reactor roof, the pool and the chute do not already stand (the pool stays open)
        for (var at : BlockPos.betweenClosed(new BlockPos(O.getX() - 8, O.getY() + 8, O.getZ() - 14), new BlockPos(O.getX() + 22, O.getY() + 8, O.getZ() + 9))) {
            int dx = at.getX() - O.getX();
            int dz = at.getZ() - O.getZ();
            boolean poolWater = dx >= 9 && dx <= 17 && dz >= -2 && dz <= 6;
            boolean pit = dx >= 5 && dx <= 7 && dz >= -2 && dz <= 6;
            if (!poolWater && !pit && level.getBlockState(at).isAir()) {
                level.setBlock(at, Blocks.SMOOTH_STONE.defaultBlockState(), 3);
            }
        }
        // the two pads stand in a pit of water between the reactor and the chute, as the storage pool is built
        for (BlockPos pad : fixture.pads) {
            for (int y = 1; y <= 7; y++) {
                level.setBlock(pad.above(y), Blocks.WATER.defaultBlockState(), 3);
            }
        }
        fixture.crane.refreshSite();
        LogUtils.getLogger().info("BUILDING CRANE CLIENT CHECK server: crane surveyed {}, reactor formed {}, {} studs",
                fixture.crane.refreshSite().ok(), fixture.reactor().isFormed(), fixture.reactor().studTotal());
    }

    private static void stepUntil(ServerLevel level, java.util.function.BooleanSupplier done) {
        int n = 0;
        var crane = fixture.crane;
        while (crane.busy() && !done.getAsBoolean() && n++ < 80_000) {
            crane.scheduledTick(level);
        }
    }

    private static void act(ServerLevel level, ServerPlayer player, int stage) {
        CraneFixture f = fixture;
        var crane = f.crane;
        var r = f.reactor();
        switch (stage) {
            case 0 -> {
                // the crane out over the well, its hook down a few metres
                crane.refreshSite();
                crane.devPlace(new Point(O.getX() + 2.5, crane.site().homeY() - 4.5, O.getZ() + 2.5));
            }
            case 1 -> {
                crane.devPlace(crane.site().home());
                f.tensioner.fill(dev.bwr.mod.reactor.StudTensionerBlockEntity.FE_CAPACITY);
                check(f.tensioner.start(true) == null, "the tensioner starts");
                // twenty-five studs done at once; the real scheduler goes on from there, a stud every four seconds
                for (int i = 0; i < 25 * 80 + 20; i++) {
                    while (f.tensioner.energyFe() < 6000) {
                        f.tensioner.energy().receiveEnergy(dev.bwr.mod.reactor.StudTensionerBlockEntity.FE_CAPACITY, false);
                    }
                    f.tensioner.scheduledTick(level);
                }
                check(r.studsOff() == 25 && f.tensioner.running(), "25 studs off and the tool still working: " + r.studsOff());
            }
            case 2 -> {
                f.tensioner.fill(dev.bwr.mod.reactor.StudTensionerBlockEntity.FE_CAPACITY);
                dev.bwr.mod.gui.StudTensionerMenu.open(player, f.tensioner);
            }
            case 3 -> {
                f.tensioner.stop();
                f.tensioner.fill(dev.bwr.mod.reactor.StudTensionerBlockEntity.FE_CAPACITY);
                check(f.tensioner.start(true) == null, "the tensioner starts again");
                f.runTensioner(30_000, true);
                check(r.studsOff() == r.studTotal(), "every stud off");
            }
            case 4 -> {
                check(crane.queueHead(true) == null, "queue the head's lift");
                stepUntil(level, () -> crane.phase() == Phase.TRANSIT && crane.progress() > 0.55);
                check(crane.carriedHead(), "the hook holds the head");
                crane.devHold(true);
            }
            case 5 -> {
                crane.devHold(false);
                stepUntil(level, () -> false);
                check(f.standBe.holdsHead(), "the head is on its stand");
            }
            case 6 -> {
                f.base.floodCavity(0.0);
                r.core().getPressureVessel().setCollapsedLevelIn(60.0);
                check(crane.queueInternal(VesselInternal.DRYER, true) == null, "queue the dryer");
                stepUntil(level, () -> crane.phase() == Phase.TRANSIT && crane.progress() > 0.45);
                check(crane.carriedInternal() == VesselInternal.DRYER, "the hook holds the dryer");
                crane.devHold(true);
            }
            case 7 -> {
                CraneMenu.open(player, crane);
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
