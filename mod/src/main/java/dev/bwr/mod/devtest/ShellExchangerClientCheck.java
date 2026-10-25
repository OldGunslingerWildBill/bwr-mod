package dev.bwr.mod.devtest;

import dev.bwr.mod.BwrMod;
import dev.bwr.mod.gui.RhrHeatExchangerMenu;
import dev.bwr.mod.gui.ServiceMenu;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.suppression.RhrHeatExchangerBlock;
import dev.bwr.mod.suppression.RhrHeatExchangerBlockEntity;
import dev.bwr.mod.water.WaterOutletChuteBlockEntity;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
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
import net.neoforged.neoforge.client.model.data.ModelData;

/**
 * Opt-in disposable client fixture (-PbwrShellExchangerCheck, :mod:runTurbineModelCheck): the shell-and-tube exchanger alone
 * (hero view and nozzle/saddle close-up), the full river loop (intake, makeup pump, exchanger, outlet chute) running in real
 * server ticks, the chute at work, and the two live panels. 1920 x 1080 pictures go to run/turbineModelCheck/shell-exchanger-check/.
 * Excluded from the released JAR with the rest of the devtest package.
 */
@EventBusSubscriber(modid = BwrMod.MOD_ID, value = Dist.CLIENT)
public final class ShellExchangerClientCheck {
    private static final BlockPos O = new BlockPos(160, 190, 160), HERO = O.offset(-26, 5, 8);
    /** The first stage builds the fixture and is not photographed (the first view after the build is not settled); then the pictures. */
    private static final String[] NAMES = {null, "exchanger", "nozzles-and-saddles", "river-loop", "outlet-chute", "exchanger-screen", "chute-screen"};
    private static final int WARM = 0, NOZZLES = 2, LOOP = 3, CHUTE = 4, EXCHANGER_SCREEN = 5, CHUTE_SCREEN = 6;
    private static boolean started, requested, fixture, warming;
    private static ShellExchangerTests.Loop loop;
    private static volatile WaterOutletChuteBlockEntity chute;
    private static int age, joined, stage, shown, warmTicks;

    private static boolean enabled() { return Boolean.getBoolean("bwr.shellExchangerCheck"); }

    /** The plant runs in ordinary server ticks; only the two pumps' power and the pool's starting heat are supplied. */
    @SubscribeEvent public static void supply(net.neoforged.neoforge.event.tick.ServerTickEvent.Post e) {
        if (!enabled() || loop == null) return;
        loop.rhr().energy().setStored(loop.rhr().energy().getMaxEnergyStored());
        loop.makeup().power().receiveEnergy(Integer.MAX_VALUE, false);
        loop.makeup().setTarget(1);
    }

    @SubscribeEvent public static void tick(net.neoforged.neoforge.client.event.ClientTickEvent.Post e) {
        if (!enabled()) return;
        var mc = Minecraft.getInstance();
        if (++age > 6000) throw new IllegalStateException("Shell exchanger client timeout at stage " + stage);
        if (!started && mc.screen != null && mc.getOverlay() == null) {
            started = true;
            mc.options.pauseOnLostFocus = false;
            mc.options.cloudStatus().set(CloudStatus.OFF);
            mc.options.fov().set(65);
            mc.options.guiScale().set(2);
            mc.options.renderDistance().set(16);
            mc.createWorldOpenFlows().createFreshLevel("bwr-shell-exchanger-check-" + System.currentTimeMillis(),
                    new LevelSettings("Shell exchanger check", GameType.CREATIVE, false, Difficulty.PEACEFUL, true, new GameRules(), WorldDataConfiguration.DEFAULT),
                    new WorldOptions(0, false, false), r -> r.registryOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).createWorldDimensions(), mc.screen);
            return;
        }
        if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null || ++joined < 40) return;
        if (!warming) {
            warming = true;
            mc.getSingleplayerServer().execute(() -> {
                var l = mc.getSingleplayerServer().overworld();
                for (int x = (O.getX() - 40) >> 4; x <= (O.getX() + 40) >> 4; x++)
                    for (int z = (O.getZ() - 40) >> 4; z <= (O.getZ() + 20) >> 4; z++) { l.setChunkForced(x, z, true); l.getChunk(x, z); }
            });
            return;
        }
        if (++warmTicks < 100) return;
        // Camera and target per picture.
        Vec3[][] views = {
                {new Vec3(HERO.getX() + 1.8, HERO.getY() + .95, HERO.getZ() - 1.3), new Vec3(HERO.getX() + .5, HERO.getY() + .45, HERO.getZ() + .5)},
                {new Vec3(HERO.getX() + 1.8, HERO.getY() + .95, HERO.getZ() - 1.3), new Vec3(HERO.getX() + .5, HERO.getY() + .45, HERO.getZ() + .5)},
                {new Vec3(HERO.getX() - .75, HERO.getY() + .45, HERO.getZ() - .75), new Vec3(HERO.getX() + .3, HERO.getY() + .3, HERO.getZ() + .45)},
                {new Vec3(O.getX() - 8, O.getY() + 13, O.getZ() - 27), new Vec3(O.getX() + 4, O.getY() + 4, O.getZ() - 8)},
                {new Vec3(O.getX() + 10, O.getY() + 9.5, O.getZ() - 6.5), new Vec3(O.getX() + 7.8, O.getY() + 6.0, O.getZ() - 3.0)},
                {new Vec3(O.getX() + 4.5, O.getY() + 8, O.getZ() - 8), Vec3.atCenterOf(O.offset(6, 6, -3))},
                {new Vec3(O.getX() + 4.5, O.getY() + 8, O.getZ() - 8), Vec3.atCenterOf(O.offset(7, 6, -3))}};
        // The views are eye positions; the player's feet are 1.62 below.
        var eye = views[stage][0];
        var camera = eye.subtract(0, 1.62, 0);
        var delta = views[stage][1].subtract(eye);
        float yaw = (float) (Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90), pitch = (float) -Math.toDegrees(Math.atan2(delta.y, Math.hypot(delta.x, delta.z)));
        if (!requested) {
            requested = true;
            shown = 0;
            int selected = stage;
            var server = mc.getSingleplayerServer();
            var uuid = mc.player.getUUID();
            server.execute(() -> {
                var player = server.getPlayerList().getPlayer(uuid);
                var l = player.serverLevel();
                player.setNoGravity(true);
                player.getAbilities().flying = true;
                player.onUpdateAbilities();
                try {
                    if (selected == WARM) {
                        loop = ShellExchangerTests.build(l, O);
                        loop.pool().pool().fromArray(new dev.bwr.core.pool.SuppressionPool(105_000, 80).toArray());
                        for (var p : BlockPos.betweenClosed(HERO.offset(-3, -1, -3), HERO.offset(3, -1, 3))) l.setBlock(p, Blocks.SMOOTH_STONE.defaultBlockState(), 3);
                        l.setBlock(HERO, BwrBlocks.RHR_HEAT_EXCHANGER.get().defaultBlockState().setValue(RhrHeatExchangerBlock.FACING, Direction.NORTH), 3);
                        chute = (WaterOutletChuteBlockEntity) l.getBlockEntity(loop.chutePos());
                        fixture = true;
                    }
                    player.closeContainer();
                    player.connection.teleport(camera.x, camera.y, camera.z, yaw, pitch);
                    l.setDayTime(6000);
                    l.setWeatherParameters(6000, 0, false, false);
                    if (selected == EXCHANGER_SCREEN) RhrHeatExchangerMenu.open(player, loop.hx());
                    if (selected == CHUTE_SCREEN) ServiceMenu.open(player, loop.chutePos());
                } catch (Exception ex) { throw new IllegalStateException(ex); }
            });
        }
        // Hold the camera every tick (gravity would otherwise move the client player while the fixture builds).
        mc.player.noPhysics = true; // the close views put the camera inside the display slab
        mc.player.getAbilities().flying = true;
        mc.player.moveTo(camera.x, camera.y, camera.z, yaw, pitch);
        mc.player.yRotO = yaw;
        mc.player.xRotO = pitch;
        mc.player.yHeadRot = yaw;
        mc.player.yHeadRotO = yaw;
        mc.player.setDeltaMovement(Vec3.ZERO);
        ++shown;
        if (stage == WARM && shown == 40) {
            var uuid = mc.player.getUUID();
            var server = mc.getSingleplayerServer();
            server.execute(() -> {
                var player = server.getPlayerList().getPlayer(uuid);
                var l = player.serverLevel();
                // The bulk fixture is built before teleportation: resend settled chunk and light data before photographing.
                for (int x = (O.getX() - 40) >> 4; x <= (O.getX() + 40) >> 4; x++)
                    for (int z = (O.getZ() - 40) >> 4; z <= (O.getZ() + 20) >> 4; z++)
                        player.connection.send(new net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket(l.getChunk(x, z), l.getChunkSource().getLightEngine(), null, null));
            });
        }
        if (shown < 160) return;
        // The loop must really be running by now.
        if (stage == LOOP || stage == CHUTE) {
            if (loop.hx().plant().heatMW() <= 0) throw new AssertionError("Exchanger not working at stage " + stage);
        }
        if (stage == EXCHANGER_SCREEN && (!(mc.player.containerMenu instanceof RhrHeatExchangerMenu m) || !m.present || !m.connected || m.heat <= 0 || m.secondaryFlow <= 0
                || m.primaryIn <= m.primaryOut || m.hotTemperature <= m.coldTemperature))
            throw new AssertionError("Exchanger panel does not show a working river loop: " + (mc.player.containerMenu instanceof RhrHeatExchangerMenu m
                    ? "present=" + m.present + " connected=" + m.connected + " heat=" + m.heat + " flow=" + m.secondaryFlow + " tubeIn=" + m.primaryIn + " tubeOut=" + m.primaryOut
                    + " shellIn=" + m.coldTemperature + " shellOut=" + m.hotTemperature : "menu is " + mc.player.containerMenu));
        if (stage == CHUTE_SCREEN && (!(mc.player.containerMenu instanceof ServiceMenu m) || m.kind != 4 || m.lines.isEmpty() || !m.lines.get(0).startsWith("Chute faces open water")))
            throw new AssertionError("Chute panel does not report open water");
        // The block entity is read through the reference taken on the server thread: Level.getBlockEntity answers null off it.
        if (stage == CHUTE && (chute == null || chute.totalKg() <= 0))
            throw new AssertionError("Chute has returned no water" + (chute == null ? "" : ": " + chute.waterState() + " hxHot=" + loop.hx().plant().hot()
                    + " hxCold=" + loop.hx().plant().cold() + " heat=" + loop.hx().plant().heatMW()));
        for (var block : new net.minecraft.world.level.block.Block[]{BwrBlocks.RHR_HEAT_EXCHANGER.get(), BwrBlocks.WATER_OUTLET_CHUTE.get()})
            for (var state : block.getStateDefinition().getPossibleStates()) {
                // Owner-approved 2026-10-25: the full-size unit's cell states are checked by HxMultiblockClientCheck; single blocks as before.
                if (state.getBlock() instanceof RhrHeatExchangerBlock && RhrHeatExchangerBlock.isAssembled(state)) continue;
                var model = mc.getModelManager().getModel(BlockModelShaper.stateToModelLocation(state));
                int count = 0;
                for (int i = 0; i < 7; i++) {
                    var quads = model.getQuads(state, i == 6 ? null : Direction.values()[i], net.minecraft.util.RandomSource.create(42), ModelData.EMPTY, null);
                    count += quads.size();
                    for (var q : quads) if (q.getSprite().contents().name().getPath().equals("missingno")) throw new AssertionError("Missing texture: " + state);
                }
                if (model == mc.getModelManager().getMissingModel() || count < 500) throw new AssertionError("Missing or thin model: " + state + " (" + count + " quads)");
            }
        mc.options.hideGui = stage < EXCHANGER_SCREEN;
        mc.getToasts().clear();
        if (shown < 170) return;
        if (NAMES[stage] != null) {
            try {
                var dir = mc.gameDirectory.toPath().resolve("shell-exchanger-check");
                java.nio.file.Files.createDirectories(dir);
                CheckShots.save(mc, dir.resolve(NAMES[stage] + ".png"));
            } catch (java.io.IOException ex) { throw new IllegalStateException(ex); }
            com.mojang.logging.LogUtils.getLogger().info("SHELL EXCHANGER CLIENT CHECK: {}", NAMES[stage]);
        }
        requested = false;
        if (++stage == NAMES.length) {
            com.mojang.logging.LogUtils.getLogger().info("SHELL EXCHANGER CLIENT CHECK PASS: shell-and-tube models baked, river loop running, chute returning water, both panels live");
            mc.stop();
        }
    }
}
