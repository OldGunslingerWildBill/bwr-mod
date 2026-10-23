package dev.bwr.mod.devtest;

import com.mojang.logging.LogUtils;
import dev.bwr.core.accident.ContainmentAtmosphere;
import dev.bwr.core.boundary.BoundaryComponent;
import dev.bwr.mod.BwrMod;
import dev.bwr.mod.containment.ContainmentControllerBlockEntity;
import dev.bwr.mod.reactor.ReactorControllerBlockEntity;
import dev.bwr.mod.registry.BwrBlocks;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
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

import java.io.IOException;
import java.nio.file.Files;

/**
 * Disposable client showcase of the physical accident blasts: a hydrogen detonation breaching a stone containment
 * room at its weakest spot, and a vessel head failure blowing a hole in the refuelling floor above the vessel, each
 * before and after. Only active with {@code -Dbwr.accidentBlastClientCheck=true} ({@code -PbwrAccidentBlastCheck}).
 * The events are the real ones: the containment model is stepped until it detonates, and the head fails through
 * the boundary model, so the world does exactly what a plant would do.
 */
@EventBusSubscriber(modid = BwrMod.MOD_ID, value = Dist.CLIENT)
public final class AccidentBlastClientCheck {
    private static final int WIDTH = 9, DEPTH = 9, HEIGHT = 12;
    private static final BlockPos ROOM = new BlockPos(100, 102, 100);
    private static final BlockPos HALL = new BlockPos(220, 102, 100);
    /** The open-sky hall whose head is thrown, and the hall whose head a plug of obsidian holds. */
    private static final BlockPos HALL2 = new BlockPos(340, 102, 100);
    private static final BlockPos HALL3 = new BlockPos(460, 102, 100);
    private static final String[] NAMES = {"1-detonation-before", "2-detonation-after", "3-head-failure-before",
            "4-head-failure-after", "5-head-failure-side", "6-head-in-flight", "7-head-landed", "8-head-held-under-plug",
            "9-vessel-without-head", "10-shield-plug-tiers"};
    /** Ground-level row of the four plug materials (vanilla concrete, then the three shield plug tiers), 3 x 3 x 2 each. */
    private static final BlockPos TIERS = new BlockPos(HALL3.getX() + 16, ROOM.getY() - 6, HALL3.getZ() - 40);
    /** Ticks to wait before each picture. */
    private static final int[] WAIT = {160, 130, 130, 130, 130, 46, 230, 130, 40, 60};
    private static boolean started, requested;
    private static int age, joined, stage, shown;
    private static ReactorControllerBlockEntity roomReactor, hallReactor, hall2Reactor, hall3Reactor;

    private AccidentBlastClientCheck() {}

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post ignored) {
        if (!Boolean.getBoolean("bwr.accidentBlastClientCheck")) return;
        Minecraft mc = Minecraft.getInstance();
        if (++age > 7000) throw new IllegalStateException("Accident blast client check timed out at " + stage);
        if (!started && mc.screen != null && mc.getOverlay() == null) {
            started = true;
            mc.options.pauseOnLostFocus = false;
            mc.options.cloudStatus().set(net.minecraft.client.CloudStatus.OFF);
            mc.options.renderDistance().set(12);
            mc.createWorldOpenFlows().createFreshLevel("bwr-accident-blast-" + System.currentTimeMillis(),
                    new LevelSettings("Accident blast client check", GameType.CREATIVE, false, Difficulty.PEACEFUL,
                            true, new GameRules(), WorldDataConfiguration.DEFAULT),
                    new WorldOptions(0, false, false),
                    r -> r.registryOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).createWorldDimensions(), mc.screen);
            return;
        }
        if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null || ++joined < 35) return;
        RenderEnvironment.log("accident-blast");
        if (!requested) {
            requested = true;
            shown = 0;
            mc.options.hideGui = true;
            int selected = stage;
            var server = mc.getSingleplayerServer();
            var uuid = mc.player.getUUID();
            server.execute(() -> {
                var player = server.getPlayerList().getPlayer(uuid);
                if (player == null) throw new IllegalStateException("Client check player missing");
                var level = player.serverLevel();
                try {
                    if (selected == 0) build(level);
                    if (selected == 1) detonate(level);
                    if (selected == 3) failHead();
                    if (selected == 5) hall2Reactor.core().getBoundaryStress().forceFailure(BoundaryComponent.REACTOR_VESSEL_HEAD, 1400);
                    if (selected == 7) hall3Reactor.core().getBoundaryStress().forceFailure(BoundaryComponent.REACTOR_VESSEL_HEAD, 1400);
                } catch (Exception e) {
                    throw new IllegalStateException("Accident blast scene failed at stage " + selected, e);
                }
                Vec3 camera = switch (selected) {
                    case 0, 1 -> new Vec3(ROOM.getX() + 40, ROOM.getY() + 9, ROOM.getZ() + 6);
                    case 4 -> new Vec3(HALL.getX() + 4, HALL.getY() + 15, HALL.getZ() + 50);
                    case 5 -> new Vec3(HALL2.getX() + 4, HALL2.getY() + 38, HALL2.getZ() + 96);
                    case 6 -> new Vec3(HALL2.getX() + 4, HALL2.getY() + 40, HALL2.getZ() + 60);
                    case 7 -> new Vec3(HALL3.getX() + 16, HALL3.getY() + HEIGHT - 1, HALL3.getZ() + 22);
                    case 8 -> new Vec3(HALL2.getX() + 26, HALL2.getY() + HEIGHT + 9, HALL2.getZ() + 28);
                    case 9 -> new Vec3(TIERS.getX() + 7.5, TIERS.getY() + 3.2, TIERS.getZ() - 7);
                    default -> new Vec3(HALL.getX() + 40, HALL.getY() + 24, HALL.getZ() + 34);
                };
                Vec3 target = switch (selected) {
                    case 0, 1 -> new Vec3(ROOM.getX() + 17, ROOM.getY() + 7, ROOM.getZ() + 4);
                    case 4 -> new Vec3(HALL.getX() + 4, HALL.getY() + 16, HALL.getZ() + 4);
                    case 5 -> new Vec3(HALL2.getX() + 4, HALL2.getY() + 30, HALL2.getZ() + 4);
                    case 6 -> new Vec3(HALL2.getX() + 4, HALL2.getY() + 12, HALL2.getZ() + 4);
                    case 7 -> new Vec3(HALL3.getX() + 4, HALL3.getY() + HEIGHT + 0.5, HALL3.getZ() + 4);
                    case 8 -> new Vec3(HALL2.getX() + 4, HALL2.getY() + HEIGHT - 2, HALL2.getZ() + 4);
                    case 9 -> new Vec3(TIERS.getX() + 7.5, TIERS.getY() + 1.2, TIERS.getZ() + 1);
                    default -> new Vec3(HALL.getX() + 4, HALL.getY() + 14, HALL.getZ() + 4);
                };
                Vec3 delta = target.subtract(camera);
                float yaw = (float) (Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90);
                float pitch = (float) -Math.toDegrees(Math.atan2(delta.y, Math.hypot(delta.x, delta.z)));
                player.setNoGravity(true);
                player.getAbilities().flying = true;
                player.onUpdateAbilities();
                level.setDayTime(6_000);
                level.setWeatherParameters(6000, 0, false, false);
                player.connection.teleport(camera.x, camera.y, camera.z, yaw, pitch);
            });
        }
        if (stage == 6 && shown == WAIT[6] - 6) aimAtHead(mc);
        if (++shown < WAIT[stage]) return;
        try {
            var directory = mc.gameDirectory.toPath().resolve("accident-blast-client-check");
            Files.createDirectories(directory);
            CheckShots.save(mc, directory.resolve(NAMES[stage] + ".png"));
        } catch (IOException ex) {
            throw new IllegalStateException("Accident blast screenshot failed", ex);
        }
        LogUtils.getLogger().info("ACCIDENT BLAST CLIENT CHECK: {}", NAMES[stage]);
        requested = false;
        if (++stage == NAMES.length) {
            LogUtils.getLogger().info("ACCIDENT BLAST CLIENT CHECK PASS: {} captures", NAMES.length);
            mc.stop();
        }
    }

    private static void load(ServerLevel level, BlockPos lo, BlockPos hi) {
        for (int x = lo.getX() >> 4; x <= hi.getX() >> 4; x++)
            for (int z = lo.getZ() >> 4; z <= hi.getZ() >> 4; z++) level.getChunk(x, z);
    }

    private static void build(ServerLevel level) throws Exception {
        // A smooth stone ground over the whole show.
        BlockPos groundLo = new BlockPos(ROOM.getX() - 40, ROOM.getY() - 7, ROOM.getZ() - 40);
        BlockPos groundHi = new BlockPos(HALL3.getX() + 70, ROOM.getY() - 7, HALL.getZ() + 70);
        load(level, groundLo, groundHi);
        for (BlockPos p : BlockPos.betweenClosed(groundLo, groundHi)) level.setBlock(p, Blocks.SMOOTH_STONE.defaultBlockState(), 2);

        // The room: one layer of stone brick, a weak plank column in the east wall.
        BlockPos lo = ROOM.offset(-9, -5, -9), hi = ROOM.offset(17, 17, 17);
        for (int x = lo.getX(); x <= hi.getX(); x++)
            for (int z = lo.getZ(); z <= hi.getZ(); z++)
                for (int y = lo.getY(); y <= hi.getY(); y++) {
                    boolean shell = x == lo.getX() || x == hi.getX() || y == lo.getY() || y == hi.getY()
                            || z == lo.getZ() || z == hi.getZ();
                    level.setBlock(new BlockPos(x, y, z), (shell ? Blocks.STONE_BRICKS : Blocks.AIR).defaultBlockState(), 2);
                }
        level.setBlock(new BlockPos(hi.getX(), ROOM.getY() + 7, ROOM.getZ() + 4), Blocks.OAK_PLANKS.defaultBlockState(), 2);
        roomReactor = CompactCoreRegressionTests.build(level, ROOM, WIDTH, DEPTH, HEIGHT, false);
        BlockPos at = ROOM.offset(-6, 0, -6);
        level.setBlock(at, BwrBlocks.CONTAINMENT_CONTROLLER.get().defaultBlockState(), 3);
        var room = (ContainmentControllerBlockEntity) level.getBlockEntity(at);
        room.markDirty();
        for (int i = 0; i < 64; i++) {
            ContainmentControllerBlockEntity.serverTick(level, at, level.getBlockState(at), room);
            if (!room.isScanning() && (!room.isSealed() || room.strengthReady())) break;
        }
        if (!room.isSealed() || !room.linkedReactors().contains(roomReactor.getBlockPos()))
            throw new AssertionError("room did not seal and link: " + room.statusLines());

        // The hall: a vessel, a refuelling floor four rows above it and a crane over it.
        hallReactor = CompactCoreRegressionTests.build(level, HALL, WIDTH, DEPTH, HEIGHT, false);
        int top = HALL.getY() + HEIGHT;
        for (int x = -14; x <= 22; x++)
            for (int z = -14; z <= 22; z++) {
                level.setBlock(new BlockPos(HALL.getX() + x, top + 5, HALL.getZ() + z),
                        ((x + z) & 1) == 0 ? Blocks.STONE_BRICKS.defaultBlockState() : Blocks.POLISHED_ANDESITE.defaultBlockState(), 2);
            }
        for (int y = top + 6; y <= top + 11; y++) {
            level.setBlock(new BlockPos(HALL.getX() - 12, y, HALL.getZ() - 12), Blocks.IRON_BLOCK.defaultBlockState(), 2);
            level.setBlock(new BlockPos(HALL.getX() + 20, y, HALL.getZ() - 12), Blocks.IRON_BLOCK.defaultBlockState(), 2);
        }
        for (int x = -12; x <= 20; x++) level.setBlock(new BlockPos(HALL.getX() + x, top + 12, HALL.getZ() - 12), Blocks.IRON_BLOCK.defaultBlockState(), 2);
        for (int z = -12; z <= 20; z++) level.setBlock(new BlockPos(HALL.getX() + 4, top + 11, HALL.getZ() + z), Blocks.IRON_BARS.defaultBlockState(), 2);
        level.setBlock(new BlockPos(HALL.getX() + 4, top + 6, HALL.getZ() + 4), Blocks.IRON_BLOCK.defaultBlockState(), 2);

        // Two more vessels far enough apart not to disturb the first. Their chunks are kept loaded (this is a disposable world)
        // so the heads they throw are ticked wherever the camera is.
        for (BlockPos hall : new BlockPos[] {HALL2, HALL3}) {
            for (int x = (hall.getX() - 70) >> 4; x <= (hall.getX() + 80) >> 4; x++)
                for (int z = (hall.getZ() - 70) >> 4; z <= (hall.getZ() + 110) >> 4; z++) level.setChunkForced(x, z, true);
        }
        hall2Reactor = CompactCoreRegressionTests.build(level, HALL2, WIDTH, DEPTH, HEIGHT, false);
        hall3Reactor = CompactCoreRegressionTests.build(level, HALL3, WIDTH, DEPTH, HEIGHT, false);
        buildTiers(level);
        // A plug of three rows of obsidian over the third vessel, wider than its head.
        for (int r = 0; r < 3; r++)
            for (int x = -3; x < WIDTH + 3; x++)
                for (int z = -3; z < DEPTH + 3; z++)
                    level.setBlock(new BlockPos(HALL3.getX() + x, HALL3.getY() + HEIGHT + 1 + r, HALL3.getZ() + z), Blocks.OBSIDIAN.defaultBlockState(), 2);
        // The plug shades the vessel top; four sea lanterns beside it let the camera see what is under it.
        for (int[] corner : new int[][] {{-2, -2}, {WIDTH + 1, -2}, {-2, DEPTH + 1}, {WIDTH + 1, DEPTH + 1}})
            level.setBlock(new BlockPos(HALL3.getX() + corner[0], HALL3.getY() + HEIGHT, HALL3.getZ() + corner[1]), Blocks.SEA_LANTERN.defaultBlockState(), 2);
        for (int x = -3; x < WIDTH + 3; x++)
            for (int z = -3; z < DEPTH + 3; z++)
                level.setBlock(new BlockPos(HALL3.getX() + x, HALL3.getY() + HEIGHT + 9, HALL3.getZ() + z), Blocks.STONE_BRICKS.defaultBlockState(), 2);
    }

    /** Vanilla concrete and the three shield plug tiers side by side on the ground, for the render-mod pictures. */
    private static void buildTiers(ServerLevel level) {
        net.minecraft.world.level.block.state.BlockState[] tiers = {Blocks.GRAY_CONCRETE.defaultBlockState(),
                BwrBlocks.SHIELD_PLUG_CONCRETE.get().defaultBlockState(),
                BwrBlocks.HEAVILY_REINFORCED_SHIELD_PLUG_CONCRETE.get().defaultBlockState(),
                BwrBlocks.ULTRA_REINFORCED_SHIELD_PLUG_CONCRETE.get().defaultBlockState()};
        for (int t = 0; t < tiers.length; t++)
            for (int x = 0; x < 3; x++)
                for (int y = 0; y < 3; y++)
                    for (int z = 0; z < 2; z++)
                        level.setBlock(TIERS.offset(t * 4 + x, y, z), tiers[t], 2);
    }

    /** Put the camera beside the resting head and look at it. */
    private static void aimAtHead(Minecraft mc) {
        var server = mc.getSingleplayerServer();
        var uuid = mc.player.getUUID();
        server.execute(() -> {
            var player = server.getPlayerList().getPlayer(uuid);
            if (player == null) return;
            var level = player.serverLevel();
            var heads = level.getEntitiesOfClass(dev.bwr.mod.accident.ReactorHeadEntity.class,
                    new net.minecraft.world.phys.AABB(HALL2.getX() - 90, HALL2.getY() - 20, HALL2.getZ() - 90,
                            HALL2.getX() + 100, HALL2.getY() + 90, HALL2.getZ() + 100));
            if (heads.isEmpty()) throw new IllegalStateException("No reactor head to photograph");
            var head = heads.get(0);
            Vec3 target = head.position().add(0, 1.0, 0);
            Vec3 camera = target.add(0, 14, 0).add(new Vec3(HALL2.getX() + 4 - target.x, 0, HALL2.getZ() + 4 - target.z).normalize().scale(-1).scale(34));
            Vec3 delta = target.subtract(camera);
            float yaw = (float) (Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90);
            float pitch = (float) -Math.toDegrees(Math.atan2(delta.y, Math.hypot(delta.x, delta.z)));
            player.connection.teleport(camera.x, camera.y, camera.z, yaw, pitch);
            LogUtils.getLogger().info("ACCIDENT BLAST CLIENT CHECK: head resting={} at {} tilt {}/{}", head.isResting(), head.position(),
                    head.tiltXDegrees(), head.tiltZDegrees());
        });
    }

    private static void detonate(ServerLevel level) {
        var gas = roomReactor.core().getContainment();
        var room = (ContainmentControllerBlockEntity) level.getBlockEntity(ROOM.offset(-6, 0, -6));
        BlockPos at = room.getBlockPos();
        // The world has been ticking the room since it was built: survey it again so the link is certain.
        room.markDirty();
        for (int i = 0; i < 64; i++) {
            ContainmentControllerBlockEntity.serverTick(level, at, level.getBlockState(at), room);
            if (!room.isScanning() && (!room.isSealed() || room.strengthReady())) break;
        }
        if (dev.bwr.mod.containment.ContainmentControllerRegistry.linkedReactor(level, roomReactor.getBlockPos()) == null)
            throw new AssertionError("the room is not linked to its reactor at the detonation: " + room.statusLines()
                    + " reactorFormed=" + roomReactor.isFormed() + " removed=" + roomReactor.isRemoved());
        gas.setFreeVolumeM3(room.freeVolume());
        gas.setFailurePressurePsig(140.0); // the plank column rates 10 psig; the hydrogen has to detonate first
        gas.initialiseInerted();
        for (int i = 0; i < 1200; i++) gas.step(0.5, 0.0, 0.0, 1.0);
        gas.setInertingCommanded(false);
        for (int i = 0; i < 4 * 3600 && !gas.isFailed(); i++) gas.step(0.0, 0.0, 0.0, 1.0);
        if (!gas.isFailed() || gas.getFailureMode() != ContainmentAtmosphere.FailureMode.DETONATION)
            throw new AssertionError("hydrogen did not detonate: " + gas);
        // The reactor's own tick delivers the event and runs the blast from here.
        roomReactor.severeEffects().afterStep(roomReactor);
    }

    private static void failHead() {
        hallReactor.core().getBoundaryStress().forceFailure(BoundaryComponent.REACTOR_VESSEL_HEAD, 1400);
    }
}
