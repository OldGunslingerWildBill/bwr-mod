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
    private static final String[] NAMES = {"1-detonation-before", "2-detonation-after", "3-head-failure-before",
            "4-head-failure-after"};
    private static boolean started, requested;
    private static int age, joined, stage, shown;
    private static ReactorControllerBlockEntity roomReactor, hallReactor;

    private AccidentBlastClientCheck() {}

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post ignored) {
        if (!Boolean.getBoolean("bwr.accidentBlastClientCheck")) return;
        Minecraft mc = Minecraft.getInstance();
        if (++age > 3600) throw new IllegalStateException("Accident blast client check timed out at " + stage);
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
                } catch (Exception e) {
                    throw new IllegalStateException("Accident blast scene failed at stage " + selected, e);
                }
                Vec3 camera = switch (selected) {
                    case 0, 1 -> new Vec3(ROOM.getX() + 40, ROOM.getY() + 9, ROOM.getZ() + 6);
                    default -> new Vec3(HALL.getX() + 40, HALL.getY() + 24, HALL.getZ() + 34);
                };
                Vec3 target = switch (selected) {
                    case 0, 1 -> new Vec3(ROOM.getX() + 17, ROOM.getY() + 7, ROOM.getZ() + 4);
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
        if (++shown < (stage == 0 ? 160 : 130)) return;
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
        BlockPos groundHi = new BlockPos(HALL.getX() + 60, ROOM.getY() - 7, HALL.getZ() + 60);
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
    }

    private static void detonate(ServerLevel level) {
        var gas = roomReactor.core().getContainment();
        var room = (ContainmentControllerBlockEntity) level.getBlockEntity(ROOM.offset(-6, 0, -6));
        gas.setFreeVolumeM3(room.freeVolume());
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
