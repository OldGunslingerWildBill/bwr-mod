package dev.bwr.mod.devtest;

import com.mojang.logging.LogUtils;
import dev.bwr.core.refuelling.BridgeMotion.Phase;
import dev.bwr.mod.BwrMod;
import dev.bwr.mod.reactor.ReactorControllerBlockEntity;
import dev.bwr.mod.reactor.VesselInternal;
import dev.bwr.mod.reactor.VesselState;
import dev.bwr.mod.refuelling.EquipmentPadBlockEntity;
import dev.bwr.mod.refuelling.RefuellingBridgeBlockEntity;
import dev.bwr.mod.refuelling.RefuellingRailBlock;
import dev.bwr.mod.registry.BwrBlocks;
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
 * Disposable real-client pictures of the v2 reactor vessel ({@code -PbwrRpvRemodelCheck} through run-mc.ps1), 1920 x 1080, in
 * {@code run/turbineModelCheck/rpv-remodel-check/}: the 17 x 17 x 28 reference vessel outside, head off on the dryer, the dryer
 * lifted by the refuelling bridge and set on its equipment pad in a flooded pit, the separators on the core, the separators lifted
 * and on their pad, the core with both out, and the smallest, largest and a rectangular vessel. The lifts are the bridge's own
 * moves, stepped from the server thread and held with {@code devHold} where the picture is wanted. Not shipped.
 */
@EventBusSubscriber(modid = BwrMod.MOD_ID, value = Dist.CLIENT)
public final class RpvRemodelClientCheck {
    private static final BlockPos O = new BlockPos(400, 150, 400);
    private static final String[] NAMES = {"1-exterior-17x17x28", "2-head-off-dryer", "3-dryer-lifted", "4-dryer-on-pad", "5-separators-on-core",
            "6-separators-lifted", "7-core-both-out", "8-both-on-pads", "9-size-7x7x10", "10-size-23x23x40", "11-size-15x11x22",
            "12-size-23x23x40-head-off", "13-size-7x7x10-head-off"};

    private record Shot(Vec3 stand, Vec3 target) {
    }

    private static Vec3 v(double x, double y, double z) {
        return new Vec3(O.getX() + x, O.getY() + y, O.getZ() + z);
    }

    private static final Shot[] SHOTS = {
            new Shot(v(7.5, 17, -24), v(7.5, 12, 7.5)),
            new Shot(v(1.5, 41, -4.5), v(7.5, 22, 7.5)),
            new Shot(v(17, 29, -21), v(17, 27, 7.5)),
            new Shot(v(30.5, 36, 0), v(30.5, 12, 7.5)),
            new Shot(v(2.5, 38, -3.5), v(7.5, 18, 7.5)),
            new Shot(v(26, 28, -21), v(26, 26, 7.5)),
            new Shot(v(3.5, 34, -2.5), v(7.5, 10, 7.5)),
            new Shot(v(39.5, 42, 1), v(39.5, 12, 7.5)),
            new Shot(v(-37.5, 8, -11), v(-37.5, 4, 2.5)),
            new Shot(v(-29.5, 24, 14), v(-29.5, 18, 50.5)),
            new Shot(v(-34.5, 12, -48), v(-34.5, 9, -30)),
            new Shot(v(-36, 58, 42), v(-29.5, 30, 50.5)),
            new Shot(v(-38.5, 16, -0.5), v(-37.5, 6, 2.5))};

    private static boolean started, requested, standing, built;
    private static volatile Throwable failure;
    private static volatile Vec3 aim;
    private static int age, joined, stage, shown;
    private static ReactorControllerBlockEntity main, small, large, rectangular;
    private static RefuellingBridgeBlockEntity bridge;

    private RpvRemodelClientCheck() {
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post ignored) {
        if (!Boolean.getBoolean("bwr.rpvRemodelCheck")) return;
        try {
            step();
        } catch (Throwable t) {
            LogUtils.getLogger().error("RPV REMODEL CLIENT CHECK FAILED at stage {}", stage < NAMES.length ? NAMES[stage] : "end", t);
            Minecraft.getInstance().stop();
        }
    }

    private static void step() throws Exception {
        Minecraft mc = Minecraft.getInstance();
        if (failure != null) throw new IllegalStateException("server side of the RPV remodel check failed", failure);
        if (++age > 12000) throw new IllegalStateException("timed out at stage " + stage);
        if (!started && mc.screen != null && mc.getOverlay() == null) {
            started = true;
            mc.options.pauseOnLostFocus = false;
            mc.options.cloudStatus().set(net.minecraft.client.CloudStatus.OFF);
            mc.options.renderDistance().set(12);
            mc.options.fov().set(70);
            mc.createWorldOpenFlows().createFreshLevel("bwr-rpv-check-" + System.currentTimeMillis(),
                    new LevelSettings("RPV remodel check", GameType.CREATIVE, false, Difficulty.PEACEFUL, true, new GameRules(), WorldDataConfiguration.DEFAULT),
                    new WorldOptions(0, false, false),
                    r -> r.registryOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).createWorldDimensions(), mc.screen);
            return;
        }
        if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null || ++joined < 40) return;
        var server = mc.getSingleplayerServer();
        var uuid = mc.player.getUUID();
        if (!requested) {
            if (stage == NAMES.length) {
                LogUtils.getLogger().info("RPV REMODEL CLIENT CHECK PASS: {} pictures", stage);
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
                    act(level, current);
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
        if (++shown < 120) return;
        var dir = mc.gameDirectory.toPath().resolve("rpv-remodel-check");
        Files.createDirectories(dir);
        CheckShots.save(mc, dir.resolve(NAMES[stage] + ".png"));
        LogUtils.getLogger().info("RPV REMODEL CLIENT CHECK: captured {}", NAMES[stage]);
        requested = false;
        stage++;
    }

    private static ReactorControllerBlockEntity reactor(ServerLevel level, BlockPos at, int w, int d, int h) throws Exception {
        var be = CompactCoreRegressionTests.build(level, at, w, d, h, false);
        int[] slots = be.corePositions();
        for (int i = 0; i < slots.length; i++) be.loadAssembly(slots[i], RefuellingBridgeFixture.fresh(9000.0 + 31 * i));
        return be;
    }

    private static void build(ServerLevel level) throws Exception {
        for (int cx = (O.getX() - 70) >> 4; cx <= (O.getX() + 72) >> 4; cx++) {
            for (int cz = (O.getZ() - 56) >> 4; cz <= (O.getZ() + 80) >> 4; cz++) {
                level.setChunkForced(cx, cz, true);
                level.getChunk(cx, cz);
            }
        }
        for (var p : BlockPos.betweenClosed(O.offset(-70, -6, -56), O.offset(72, -6, 80))) level.setBlock(p, Blocks.SMOOTH_STONE.defaultBlockState(), 2);
        main = reactor(level, O, 15, 15, 25);
        small = reactor(level, O.offset(-40, 0, 0), 5, 5, 8);
        large = reactor(level, O.offset(-40, 0, 40), 21, 21, 38);
        rectangular = reactor(level, O.offset(-40, 0, -34), 13, 9, 20);
        // the refuel floor: rails high enough over the 17 x 17 x 28 vessel that a hanging dryer clears its lip, a console, and a
        // flooded pit (the dryer/separator storage pool) with two equipment pads east of the vessel
        int ry = O.getY() + 32;
        for (int x = -3; x <= 57; x++) {
            for (int z : new int[]{-3, 17}) {
                level.setBlock(new BlockPos(O.getX() + x, ry, O.getZ() + z), BwrBlocks.REFUELLING_RAIL.get().defaultBlockState()
                        .setValue(RefuellingRailBlock.AXIS, Direction.Axis.X), 2);
                for (int dz = -1; dz <= 1; dz++) level.setBlock(new BlockPos(O.getX() + x, ry - 1, O.getZ() + z + dz), Blocks.SMOOTH_STONE.defaultBlockState(), 2);
            }
        }
        BlockPos console = new BlockPos(O.getX() - 3, ry, O.getZ() - 4);
        level.setBlock(console, BwrBlocks.REFUELLING_BRIDGE_CONSOLE.get().defaultBlockState(), 2);
        bridge = (RefuellingBridgeBlockEntity) level.getBlockEntity(console);
        for (int x = 20; x <= 57; x++) {
            for (int z = -2; z <= 16; z++) {
                for (int y = 8; y <= 19; y++) {
                    boolean wall = x == 20 || x == 57 || z == -2 || z == 16 || y == 8;
                    level.setBlock(O.offset(x, y, z), wall ? Blocks.SMOOTH_STONE.defaultBlockState() : Blocks.WATER.defaultBlockState(), 2);
                }
            }
        }
        for (int x : new int[]{30, 48}) level.setBlock(O.offset(x, 9, 7), BwrBlocks.EQUIPMENT_PAD.get().defaultBlockState(), 3);
        check(bridge.refreshSite().ok() && bridge.site().reactor(level) == main, "the bridge finds the reactor");
    }

    private static void level(ReactorControllerBlockEntity be, double inches) {
        be.core().getPressureVessel().setCollapsedLevelIn(inches);
    }

    private static void stepUntil(ServerLevel level, java.util.function.BooleanSupplier done) {
        int n = 0;
        while (bridge.busy() && !done.getAsBoolean() && n++ < 80_000) bridge.scheduledTick(level);
    }

    private static void act(ServerLevel level, int stage) {
        switch (stage) {
            case 1 -> {
                check(main.setVesselState(VesselState.REFUELING) == null, "head off");
                level(main, 30);
            }
            case 2 -> {
                level(main, 60);
                bridge.refreshSite();
                var why = bridge.queueInternal(VesselInternal.DRYER, true);
                check(why == null, "dryer lift: " + (why == null ? "" : why.getString()));
                stepUntil(level, () -> bridge.phase() == Phase.TRANSIT && bridge.progress() > .45);
                bridge.devHold(true);
            }
            case 3 -> {
                bridge.devHold(false);
                stepUntil(level, () -> false);
                var at = main.internalLocation(VesselInternal.DRYER);
                check(at != null && level.getBlockEntity(at) instanceof EquipmentPadBlockEntity pad && pad.stored() == VesselInternal.DRYER, "the dryer is on a pad");
                bridge.devPlace(bridge.site().home()); // the gantry parked out of the picture
            }
            case 4 -> level(main, 30);
            case 5 -> {
                level(main, 60);
                var why = bridge.queueInternal(VesselInternal.SEPARATOR, true);
                check(why == null, "separator lift: " + (why == null ? "" : why.getString()));
                stepUntil(level, () -> bridge.phase() == Phase.TRANSIT && bridge.progress() > .5);
                bridge.devHold(true);
            }
            case 6 -> {
                bridge.devHold(false);
                stepUntil(level, () -> false);
                check(main.internalsOutMask() == 3 && main.refuellingBlockedMessage() == null, "both out, fuel free to move");
                bridge.devPlace(bridge.site().home());
                level(main, -100);
            }
            case 11 -> {
                check(large.setVesselState(VesselState.REFUELING) == null, "head off the large vessel");
                level(large, 30);
            }
            case 12 -> {
                check(small.setVesselState(VesselState.REFUELING) == null, "head off the small vessel");
                level(small, 30);
            }
            default -> {
            }
        }
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }
}
