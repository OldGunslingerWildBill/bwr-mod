package dev.bwr.mod.devtest;

import com.mojang.logging.LogUtils;
import dev.bwr.core.refuelling.BridgeMotion.Phase;
import dev.bwr.core.thermal.PressureVessel;
import dev.bwr.mod.BwrMod;
import dev.bwr.mod.reactor.ReactorControllerBlockEntity;
import dev.bwr.mod.reactor.VesselState;
import dev.bwr.mod.reactor.VesselWaterGeometry;
import dev.bwr.mod.refuelling.RefuellingBridgeBlockEntity.Loc;
import dev.bwr.mod.refuelling.RefuellingBridgeBlockEntity.Move;
import dev.bwr.mod.registry.BwrItems;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
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
 * Disposable real-client check of the cavity seal and the flooded well (run with {@code -PbwrCavityCheck} through run-mc.ps1). Builds,
 * as a player would, a vessel walled in with a seal (touching walls on two sides, a one block gap on the other two), the same with the
 * well flooded and the core glowing under the water, the reactor screen's INFO tab, a vessel whose wall is two blocks away on one side
 * (no seal, the water leaking at the flange), and the refuelling bridge with its mast in the core over a flooded well. Saves 1920 x 1080
 * pictures to {@code run/turbineModelCheck/cavity-check/}. Not shipped: the devtest package is stripped from the release jar.
 */
@EventBusSubscriber(modid = BwrMod.MOD_ID, value = Dist.CLIENT)
public final class CavityClientCheck {

    private static final BlockPos A = new BlockPos(200, 200, 200);
    private static final BlockPos B = new BlockPos(260, 200, 200);
    private static final BlockPos C = new BlockPos(320, 200, 200);
    private static final String[] NAMES = {"seal-ring-dry", "well-flooded-glow", "info-tab", "too-wide-no-seal", "bridge-over-flooded-well"};

    private record Shot(Vec3 stand, Vec3 target) {
    }

    private static Vec3 v(double x, double y, double z) {
        return new Vec3(x, y, z);
    }

    // Reactor A: interior 9 x 9 x 14 at (200,200,200); the shell box x,z 199..209, y 199..214; the flange row is y 212; the deck at y 218.
    private static final Shot[] SHOTS = {
            new Shot(v(211.5, 227.0, 196.0), v(204.5, 212.5, 204.5)),
            new Shot(v(211.5, 227.0, 196.0), v(204.5, 212.5, 204.5)),
            new Shot(v(193.0, 205.0, 204.5), v(199.0, 203.0, 204.5)), // within reach of the controller, or the menu closes itself
            new Shot(v(271.5, 227.0, 196.0), v(264.5, 212.5, 204.5)),
            new Shot(v(327.0, 215.5, 195.0), v(322.5, 205.0, 202.5))};

    private static boolean started, requested, standing, built;
    private static volatile Throwable failure;
    private static volatile Vec3 aim;
    private static int age, joined, stage, shown;
    private static ReactorControllerBlockEntity reactorA, reactorB;
    private static RefuellingBridgeFixture fixture;

    private CavityClientCheck() {
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post ignored) {
        if (!Boolean.getBoolean("bwr.cavityCheck")) {
            return;
        }
        try {
            step();
        } catch (Throwable t) {
            LogUtils.getLogger().error("CAVITY CLIENT CHECK FAILED at stage {}", stage < NAMES.length ? NAMES[stage] : "end", t);
            Minecraft.getInstance().stop();
        }
    }

    private static void step() throws Exception {
        Minecraft mc = Minecraft.getInstance();
        if (failure != null) {
            throw new IllegalStateException("server side of the cavity check failed", failure);
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
            mc.createWorldOpenFlows().createFreshLevel("bwr-cavity-check-" + System.currentTimeMillis(),
                    new LevelSettings("Cavity check", GameType.CREATIVE, false, Difficulty.PEACEFUL, true, new GameRules(), WorldDataConfiguration.DEFAULT),
                    new WorldOptions(0, false, false),
                    r -> r.registryOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).createWorldDimensions(), mc.screen);
            return;
        }
        if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null || ++joined < 40) {
            return;
        }
        var server = mc.getSingleplayerServer();
        var uuid = mc.player.getUUID();
        RenderEnvironment.log("cavity");
        if (!requested) {
            if (stage == NAMES.length) {
                LogUtils.getLogger().info("CAVITY CLIENT CHECK PASS: {} pictures", stage);
                mc.stop();
                return;
            }
            requested = true;
            shown = 0;
            standing = false;
            int current = stage;
            if (current != 2) {
                mc.setScreen(null);
            }
            mc.options.hideGui = current != 2;
            mc.options.guiScale().set(3);
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
        if (stage == 2) {
            if (!(mc.screen instanceof dev.bwr.mod.gui.client.ReactorPanelScreen screen)
                    || !(mc.player.containerMenu instanceof dev.bwr.mod.gui.ReactorPanelMenu menu) || !menu.formed || !menu.cavity.known()) {
                if (age % 100 == 0) {
                    LogUtils.getLogger().info("CAVITY CLIENT CHECK waiting for the reactor screen: screen {} menu {} formed {} known {}", mc.screen,
                            mc.player.containerMenu.getClass().getSimpleName(),
                            mc.player.containerMenu instanceof dev.bwr.mod.gui.ReactorPanelMenu m && m.formed,
                            mc.player.containerMenu instanceof dev.bwr.mod.gui.ReactorPanelMenu m2 && m2.cavity.known());
                }
                return;
            }
            var w = mc.getWindow();
            double midX = w.getGuiScaledWidth() / 2.0;
            double midY = w.getGuiScaledHeight() / 2.0;
            if (shown == 0) {
                setInfo(screen);
                mouseTo(mc, midX, midY);
            } else if (shown == 3) {
                screen.mouseScrolled(midX, midY, 0, -Integer.getInteger("bwr.cavityScroll", 7));
            } else if (shown == 8) {
                mouseTo(mc, 2, 2); // no tooltip over the rows
            }
        }
        if (++shown < 110) {
            return;
        }
        verify(mc, stage);
        var dir = mc.gameDirectory.toPath().resolve("cavity-check");
        Files.createDirectories(dir);
        CheckShots.save(mc, dir.resolve(NAMES[stage] + ".png"));
        LogUtils.getLogger().info("CAVITY CLIENT CHECK: captured {}", NAMES[stage]);
        if (stage == 2) {
            mc.player.closeContainer();
        }
        requested = false;
        stage++;
    }

    private static void mouseTo(Minecraft mc, double guiX, double guiY) {
        var w = mc.getWindow();
        setDouble(mc.mouseHandler, "xpos", guiX * w.getScreenWidth() / w.getGuiScaledWidth());
        setDouble(mc.mouseHandler, "ypos", guiY * w.getScreenHeight() / w.getGuiScaledHeight());
    }

    private static void setDouble(Object o, String name, double v) {
        try {
            var f = o.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.setDouble(o, v);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static void setInfo(dev.bwr.mod.gui.client.ReactorPanelScreen screen) {
        try {
            Class<?> type = Class.forName("dev.bwr.mod.gui.client.ReactorPanelScreen$Overlay");
            for (Object c : type.getEnumConstants()) {
                if (c.toString().equals("INFO")) {
                    var m = screen.getClass().getDeclaredMethod("setOverlay", type);
                    m.setAccessible(true);
                    m.invoke(screen, c);
                }
            }
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** What the client itself holds, read off the actual block entities in the client world. */
    private static void verify(Minecraft mc, int stage) {
        BlockPos controllerA = A.offset(-1, 3, 4);
        BlockPos controllerB = B.offset(-1, 3, 4);
        var a = mc.level.getBlockEntity(controllerA) instanceof ReactorControllerBlockEntity r ? r : null;
        var b = mc.level.getBlockEntity(controllerB) instanceof ReactorControllerBlockEntity r ? r : null;
        switch (stage) {
            case 0 -> {
                check(a != null && a.clientVesselEnvelope() != null && a.clientVesselEnvelope().cavity() != null, "client A has no seal in its picture");
                check(a.clientWellSurface() == 0.0, "the well is dry in the dry picture: " + a.clientWellSurface());
            }
            case 2 -> check(a != null && a.clientVesselEnvelope() != null && a.clientVesselEnvelope().cavity() != null, "client A has no seal in its picture");
            case 1 -> {
                check(a != null && a.clientVesselEnvelope() != null && a.clientVesselEnvelope().cavity() != null, "client A has no seal in its picture");
                double rim = VesselWaterGeometry.rim(a.clientVesselEnvelope());
                check(a.clientWellSurface() > rim + 2.0, "the client's well surface is over the lip: " + a.clientWellSurface() + " lip " + rim);
                check(a.clientHasFuel() && a.clientPowerFractionOfRated() > 0.001, "the core is hot enough to glow: " + a.clientPowerFractionOfRated());
            }
            case 3 -> {
                check(b != null && b.clientVesselEnvelope() != null && b.clientVesselEnvelope().cavity() == null, "client B draws a seal it should not have");
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

    private static void wallsAndDeck(ServerLevel level, ReactorControllerBlockEntity be, int n, int s, int w, int e, int topRow) {
        var env = be.vesselEnvelope();
        CavitySealTests.placeRing(level, env, n, s, w, e, 5, topRow, Blocks.LIGHT_GRAY_CONCRETE);
        // the refuel floor: deck at the top row everywhere round the shaft
        int row = env.min().getY() + topRow;
        int x0 = env.min().getX() - w;
        int x1 = env.max().getX() + e;
        int z0 = env.min().getZ() - n;
        int z1 = env.max().getZ() + s;
        for (int x = x0 - 9; x <= x1 + 9; x++) {
            for (int z = z0 - 9; z <= z1 + 9; z++) {
                boolean inside = x >= x0 && x <= x1 && z >= z0 && z <= z1;
                if (!inside && level.getBlockState(new BlockPos(x, row, z)).isAir()) {
                    level.setBlock(new BlockPos(x, row, z), Blocks.SMOOTH_STONE.defaultBlockState(), 3);
                }
            }
        }
    }

    private static ReactorControllerBlockEntity reactor(ServerLevel level, BlockPos min) throws Exception {
        for (int x = (min.getX() - 20) >> 4; x <= (min.getX() + 30) >> 4; x++) {
            for (int z = (min.getZ() - 20) >> 4; z <= (min.getZ() + 30) >> 4; z++) {
                level.setChunkForced(x, z, true);
                level.getChunk(x, z);
            }
        }
        for (var at : BlockPos.betweenClosed(min.offset(-14, -4, -14), min.offset(24, -4, 24))) {
            level.setBlock(at, Blocks.SMOOTH_STONE.defaultBlockState(), 3);
        }
        var be = CompactCoreRegressionTests.build(level, min, 9, 9, 14, false);
        for (int slot : be.corePositions()) {
            be.loadAssembly(slot, new ItemStack(BwrItems.FUEL_ASSEMBLY.get()));
        }
        be.core().getDecayHeat().setToSaturatedInventory(1);
        be.setVesselState(VesselState.REFUELING);
        be.core().getPressureVessel().setCollapsedLevelIn(55.0);
        return be;
    }

    private static void build(ServerLevel level) throws Exception {
        reactorA = reactor(level, A);
        // touching walls north and east, a block of gap south and west
        wallsAndDeck(level, reactorA, 1, 2, 2, 1, 19);
        reactorA.markStructureDirty();
        reactorB = reactor(level, B);
        // a wall two blocks away on the west
        wallsAndDeck(level, reactorB, 1, 1, 3, 1, 19);
        reactorB.markStructureDirty();
        reactorB.core().getPressureVessel().setCollapsedLevelIn(PressureVessel.FLANGE_LEVEL_IN - 0.2);
        reactorB.core().setFeedwaterFlowKgPerS(1500.0);
        // the bridge floor
        for (var at : BlockPos.betweenClosed(new BlockPos(C.getX() - 12, C.getY() - 4, C.getZ() - 12), new BlockPos(C.getX() + 32, C.getY() - 4, C.getZ() + 20))) {
            level.setBlock(at, Blocks.SMOOTH_STONE.defaultBlockState(), 3);
        }
        fixture = RefuellingBridgeFixture.build(level, C, 6);
        for (var at : BlockPos.betweenClosed(new BlockPos(C.getX() - 8, C.getY() + 8, C.getZ() - 8), new BlockPos(C.getX() + 26, C.getY() + 8, C.getZ() + 14))) {
            int dx = at.getX() - C.getX();
            int dz = at.getZ() - C.getZ();
            boolean poolWater = dx >= 9 && dx <= 17 && dz >= -2 && dz <= 6;
            if (!poolWater && level.getBlockState(at).isAir()) {
                level.setBlock(at, Blocks.SMOOTH_STONE.defaultBlockState(), 3);
            }
        }
        CavitySealTests.placeRing(level, fixture.reactor.vesselEnvelope(), 1, 1, 1, 1, 3, 9, Blocks.LIGHT_GRAY_CONCRETE);
        fixture.reactor.markStructureDirty();
        fixture.fillPool(7.0);
        for (int i = 0; i < 60; i++) {
            fixture.reactor.loadAssembly(fixture.corePosition(i), RefuellingBridgeFixture.fresh(18000.0 + 500.0 * i));
        }
        fixture.reactor.core().getDecayHeat().setToSaturatedInventory(1);
        LogUtils.getLogger().info("CAVITY CLIENT CHECK server: built A, B and the bridge floor");
    }

    private static void act(ServerLevel level, ServerPlayer player, int stage) {
        switch (stage) {
            case 1 -> {
                var vessel = reactorA.core().getPressureVessel();
                vessel.setCollapsedLevelIn(PressureVessel.FLANGE_LEVEL_IN + 2.5 / PressureVessel.METRES_PER_INCH);
                LogUtils.getLogger().info("CAVITY CLIENT CHECK A: {} / well {} m3 / surface {} / spill {}", reactorA.cavity().english(),
                        vessel.getWellWaterM3(), reactorA.wellSurfaceLocal(), vessel.getWellSpillKgPerS());
            }
            case 2 -> dev.bwr.mod.gui.ReactorPanelMenu.open(player, reactorA);
            case 3 -> LogUtils.getLogger().info("CAVITY CLIENT CHECK B: {} / spill {} kg/s", reactorB.cavity().english(),
                    reactorB.core().getPressureVessel().getWellSpillKgPerS());
            case 4 -> {
                var f = fixture;
                var vessel = f.reactor.core().getPressureVessel();
                vessel.setCollapsedLevelIn(PressureVessel.FLANGE_LEVEL_IN + 1.0 / PressureVessel.METRES_PER_INCH);
                f.openGate();
                f.bridge.refreshSite();
                LogUtils.getLogger().info("CAVITY CLIENT CHECK bridge floor: {} / cover {} m / chute {}", f.reactor.cavity().english(),
                        dev.bwr.mod.refuelling.BridgeSite.cavityWaterM(f.reactor), f.bridge.chute().state());
                check(f.bridge.queue(new Move(Loc.core(f.corePosition(0)), Loc.rack(f.pool.racks.get(1), 3))) == null, "queue core to pool");
                int n = 0;
                while (f.bridge.busy() && f.bridge.phase() != Phase.LATCH && n++ < 60_000) {
                    f.bridge.scheduledTick(level);
                }
                f.bridge.devHold(true);
            }
            default -> {
            }
        }
    }
}
