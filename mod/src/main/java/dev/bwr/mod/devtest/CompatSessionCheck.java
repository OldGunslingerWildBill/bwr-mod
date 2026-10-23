package dev.bwr.mod.devtest;

import com.mojang.logging.LogUtils;
import dev.bwr.mod.BwrMod;
import dev.bwr.mod.gui.RefuellingMenu;
import dev.bwr.mod.gui.SpentFuelPoolMenu;
import dev.bwr.mod.radiation.AreaRadiationMonitorBlock;
import dev.bwr.mod.radiation.AreaRadiationMonitorBlockEntity;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.registry.BwrItems;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.nio.file.Files;

/**
 * One client session for the mod-compatibility pass ({@code -PbwrCompatSessionCheck}): a flat creative world in which the new
 * hardware is built and USED the way a player uses it, with whatever other mods are in the run's {@code mods/} folder (the pass ran
 * it beside Essential and Effortless Structure). The refuelling floor (reactor, spent fuel pool, canal, bridge console) goes up,
 * the player uses the bridge console and the pool controller (the real {@code useWithoutItem}, so the real menus open and are
 * checked on the client), an area radiation monitor is placed and driven past its alarm, and the plant computer showcase is
 * built and looked at. A picture of each lands in {@code compat-session-check/}. The check only logs {@code COMPAT SESSION CHECK PASS}
 * when every step happened; the caller reads the log for errors and mixin conflicts. Not shipped: the devtest package is stripped
 * from the release jar.
 */
@EventBusSubscriber(modid = BwrMod.MOD_ID, value = Dist.CLIENT)
public final class CompatSessionCheck {
    private static final String[] NAMES = {"1-bridge-console-menu", "2-pool-menu", "3-radiation-monitor-alarm", "4-plant-computer"};
    private static final BlockPos O = new BlockPos(200, 200, 200);
    private static final BlockPos MONITOR = new BlockPos(7700, 110, 7700);
    private static final Object SOURCE = new Object();

    private static boolean started, built, requested, ready;
    private static volatile Throwable failure;
    private static volatile boolean serverStepDone;
    private static RefuellingBridgeFixture fixture;
    private static int age, joined, stage, shown;

    private CompatSessionCheck() {}

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post ignored) {
        if (!Boolean.getBoolean("bwr.compatSessionCheck")) return;
        try {
            step();
        } catch (Throwable t) {
            LogUtils.getLogger().error("COMPAT SESSION CHECK FAILED at stage {}", stage < NAMES.length ? NAMES[stage] : "end", t);
            Minecraft.getInstance().stop();
        }
    }

    private static void step() throws Exception {
        Minecraft mc = Minecraft.getInstance();
        if (failure != null) throw new IllegalStateException("server side of the compat session failed", failure);
        if (++age > 9000) throw new IllegalStateException("timed out at stage " + stage);
        if (!started && mc.screen != null && mc.getOverlay() == null) {
            started = true;
            mc.options.pauseOnLostFocus = false;
            mc.options.cloudStatus().set(net.minecraft.client.CloudStatus.OFF);
            mc.options.renderDistance().set(10);
            mc.options.guiScale().set(2);
            mc.createWorldOpenFlows().createFreshLevel("bwr-compat-session-" + System.currentTimeMillis(),
                    new LevelSettings("Compat session", GameType.CREATIVE, false, Difficulty.PEACEFUL, true, new GameRules(), WorldDataConfiguration.DEFAULT),
                    new WorldOptions(0, false, false),
                    r -> r.registryOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).createWorldDimensions(), mc.screen);
            return;
        }
        if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null || ++joined < 40) return;
        RenderEnvironment.log("compat-session");
        var server = mc.getSingleplayerServer();
        var uuid = mc.player.getUUID();
        if (!requested) {
            if (stage == NAMES.length) {
                LogUtils.getLogger().info("COMPAT SESSION CHECK PASS: bridge console, spent fuel pool, radiation monitor and plant computer built and used; {} pictures", stage);
                mc.stop();
                return;
            }
            requested = true;
            ready = false;
            shown = 0;
            serverStepDone = false;
            int current = stage;
            if (mc.screen != null) mc.player.closeContainer();
            mc.options.hideGui = false;
            server.execute(() -> {
                try {
                    ServerPlayer player = server.getPlayerList().getPlayer(uuid);
                    ServerLevel level = player.serverLevel();
                    player.setNoGravity(true);
                    player.getAbilities().flying = true;
                    player.onUpdateAbilities();
                    level.setDayTime(6000);
                    level.setWeatherParameters(6000, 0, false, false);
                    if (!built) {
                        build(level);
                        built = true;
                    }
                    switch (current) {
                        case 0 -> {
                            player.connection.teleport(O.getX() - 1.5, O.getY() + 10.5, O.getZ() - 6.5, 0f, 25f);
                            use(level, player, fixture.console);
                        }
                        case 1 -> {
                            var pool = fixture.pool;
                            player.connection.teleport(pool.controller.getX() - 3.0, pool.controller.getY() + 1.0, pool.controller.getZ() + 0.5, -90f, 10f);
                            use(level, player, pool.controller);
                        }
                        case 2 -> {
                            player.connection.teleport(MONITOR.getX() + 0.5, MONITOR.getY() + 0.5, MONITOR.getZ() - 6.0, 0f, 5f);
                            level.setBlock(MONITOR.below(), Blocks.SMOOTH_STONE.defaultBlockState(), 3);
                            level.setBlock(MONITOR, BwrBlocks.AREA_RADIATION_MONITOR.get().defaultBlockState()
                                    .setValue(AreaRadiationMonitorBlock.FACING, Direction.NORTH), 3);
                            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(BwrItems.SURVEY_METER.get()));
                            dev.bwr.mod.radiation.RadiationSources.emit(level, SOURCE, Vec3.atCenterOf(MONITOR).add(0, 0, -3.0),
                                    1.6 / dev.bwr.core.radiation.DoseModel.pointSourceMsvPerH(1.0, 3.0, 1.0), 1.0);
                            var monitor = (AreaRadiationMonitorBlockEntity) level.getBlockEntity(MONITOR);
                            monitor.invalidate();
                            monitor.refresh();
                        }
                        default -> {
                            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                            Vec3 camera = PanelPlantShowcase.camera(0);
                            Vec3 target = PanelPlantShowcase.target(0);
                            Vec3 delta = target.subtract(camera);
                            float yaw = (float) (Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90);
                            float pitch = (float) -Math.toDegrees(Math.atan2(delta.y, Math.hypot(delta.x, delta.z)));
                            player.connection.teleport(camera.x, camera.y, camera.z, yaw, pitch);
                        }
                    }
                    serverStepDone = true;
                } catch (Throwable t) {
                    failure = t;
                }
            });
        }
        if (!serverStepDone) return;
        boolean menu = stage <= 1;
        if (menu) {
            boolean open = stage == 0
                    ? mc.player.containerMenu instanceof RefuellingMenu rm && rm.bridgeMode
                    : mc.player.containerMenu instanceof SpentFuelPoolMenu pm && pm.present && pm.formed;
            if (!open) return;
        }
        if (stage == 2) {
            var state = mc.level.getBlockState(MONITOR);
            if (!state.is(BwrBlocks.AREA_RADIATION_MONITOR.get()) || state.getValue(AreaRadiationMonitorBlock.LAMP) != 2) return;
        }
        if (++shown < (stage == 3 ? 100 : 60)) return;
        var dir = mc.gameDirectory.toPath().resolve("compat-session-check");
        Files.createDirectories(dir);
        CheckShots.save(mc, dir.resolve(NAMES[stage] + ".png"));
        LogUtils.getLogger().info("COMPAT SESSION CHECK: captured {}", NAMES[stage]);
        requested = false;
        stage++;
    }

    /** What a right click does: the block's own use handler, from the player. */
    private static void use(ServerLevel level, ServerPlayer player, BlockPos pos) {
        var result = level.getBlockState(pos).useWithoutItem(level, player, new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
        if (!result.consumesAction()) throw new AssertionError("using " + level.getBlockState(pos) + " at " + pos + " did nothing: " + result);
    }

    private static void build(ServerLevel level) throws Exception {
        for (int x = 4; x <= 14; x++) {
            for (int z = 4; z <= 14; z++) {
                level.setChunkForced(x, z, true);
                level.getChunk(x, z);
            }
        }
        level.setChunkForced(7700 >> 4, 7700 >> 4, true);
        level.getChunk(7700 >> 4, 7700 >> 4);
        level.getChunk(7690 >> 4, 7690 >> 4);
        for (var at : BlockPos.betweenClosed(new BlockPos(O.getX() - 12, O.getY() - 4, O.getZ() - 12), new BlockPos(O.getX() + 32, O.getY() - 4, O.getZ() + 20))) {
            level.setBlock(at, Blocks.SMOOTH_STONE.defaultBlockState(), 3);
        }
        fixture = RefuellingBridgeFixture.build(level, O, 6);
        fixture.fillPool(7.0);
        for (int i = 0; i < 10; i++) {
            fixture.reactor.loadAssembly(fixture.corePosition(i), RefuellingBridgeFixture.fresh(18000.0 + 500.0 * i));
        }
        long now = level.getGameTime();
        for (int k = 0; k < 3; k++) {
            fixture.pool.be.insertAt(fixture.pool.racks.get(0), k, SpentFuelPoolFixture.assembly(45000.0, 0.0, now));
        }
        fixture.pool.be.simulateSeconds(1, now);
        PanelPlantShowcase.build(level);
        LogUtils.getLogger().info("COMPAT SESSION CHECK server: bridge surveyed {}, pool formed {}", fixture.bridge.refreshSite().ok(), fixture.pool.be.isFormed());
    }
}
