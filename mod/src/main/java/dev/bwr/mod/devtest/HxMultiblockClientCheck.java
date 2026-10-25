package dev.bwr.mod.devtest;

import dev.bwr.mod.BwrMod;
import dev.bwr.mod.gui.RhrHeatExchangerMenu;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.suppression.RhrHeatExchangerBlock;
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
 * Opt-in disposable client fixture (-PbwrHxMultiblockCheck, :mod:runTurbineModelCheck): the full-size RHR heat exchanger
 * alone from the front, the back, the channel head and the top, the river loop through it (intake, makeup pump, exchanger,
 * outlet chute) running in real server ticks, and its live panel opened from a top cell. 1920 x 1080 pictures go to
 * run/turbineModelCheck/hx-multiblock-check/. Excluded from the released JAR with the rest of the devtest package.
 */
@EventBusSubscriber(modid = BwrMod.MOD_ID, value = Dist.CLIENT)
public final class HxMultiblockClientCheck {
    private static final BlockPos O = new BlockPos(160, 190, 160), HERO = O.offset(-28, 0, 12);
    private static final String[] NAMES = {null, "hx-front", "hx-back", "hx-channel-head", "hx-top", "river-loop", "river-loop-chute", "hx-screen"};
    private static final int WARM = 0, LOOP = 5, CHUTE = 6, SCREEN = 7;
    private static boolean started, requested, warming;
    private static HxMultiblockTests.Loop loop;
    private static int age, joined, stage, shown, warmTicks;

    private static boolean enabled() { return Boolean.getBoolean("bwr.hxMultiblockCheck"); }

    @SubscribeEvent public static void supply(net.neoforged.neoforge.event.tick.ServerTickEvent.Post e) {
        if (!enabled() || loop == null) return;
        loop.rhr().energy().setStored(loop.rhr().energy().getMaxEnergyStored());
        loop.makeup().power().receiveEnergy(Integer.MAX_VALUE, false);
        loop.makeup().setTarget(1);
    }

    private static Vec3 c(BlockPos p, double x, double y, double z) { return new Vec3(p.getX() + x, p.getY() + y, p.getZ() + z); }

    @SubscribeEvent public static void tick(net.neoforged.neoforge.client.event.ClientTickEvent.Post e) {
        if (!enabled()) return;
        var mc = Minecraft.getInstance();
        if (++age > 7000) throw new IllegalStateException("HX multiblock client timeout at stage " + stage);
        if (!started && mc.screen != null && mc.getOverlay() == null) {
            started = true;
            mc.options.pauseOnLostFocus = false;
            mc.options.cloudStatus().set(CloudStatus.OFF);
            mc.options.fov().set(65);
            mc.options.guiScale().set(2);
            mc.options.renderDistance().set(16);
            mc.createWorldOpenFlows().createFreshLevel("bwr-hx-multiblock-check-" + System.currentTimeMillis(),
                    new LevelSettings("HX multiblock check", GameType.CREATIVE, false, Difficulty.PEACEFUL, true, new GameRules(), WorldDataConfiguration.DEFAULT),
                    new WorldOptions(0, false, false), r -> r.registryOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).createWorldDimensions(), mc.screen);
            return;
        }
        if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null || ++joined < 40) return;
        if (!warming) {
            warming = true;
            mc.getSingleplayerServer().execute(() -> {
                var l = mc.getSingleplayerServer().overworld();
                for (int x = (O.getX() - 44) >> 4; x <= (O.getX() + 40) >> 4; x++)
                    for (int z = (O.getZ() - 44) >> 4; z <= (O.getZ() + 24) >> 4; z++) { l.setChunkForced(x, z, true); l.getChunk(x, z); }
            });
            return;
        }
        if (++warmTicks < 100) return;
        Vec3 mid = c(HERO, .5, 4.0, .5);
        Vec3[][] views = {
                {mid.add(-5.0, 1.2, -6.3), mid},
                {mid.add(-5.0, 1.2, -6.3), mid},
                {mid.add(6.2, 0.8, 5.4), mid},
                {c(HERO, -2.4, 2.1, -3.0), c(HERO, .5, 1.3, .6)},
                {c(HERO, 4.2, 10.8, -3.4), c(HERO, .5, 7.1, .5)},
                {c(O, -17, 17, -38), c(O, 3, 3, -8)},
                {c(O, 12.5, 9.5, -14.5), c(O, 5.5, 4.5, -8.0)},
                {c(O, 1.5, 4.5, -11.5), c(O, 4.5, 3.0, -8.0)}};
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
                        loop = HxMultiblockTests.build(l, O);
                        loop.pool().pool().fromArray(new dev.bwr.core.pool.SuppressionPool(105_000, 80).toArray());
                        // A concrete floor under the plant and the display unit, only where there is air.
                        for (var p : BlockPos.betweenClosed(O.offset(-40, -1, -40), O.offset(30, -1, 16)))
                            if (l.getBlockState(p).isAir()) l.setBlock(p, Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState(), 2);
                        for (var p : BlockPos.betweenClosed(HERO.offset(-6, -1, -6), HERO.offset(6, -1, 6))) l.setBlock(p, Blocks.SMOOTH_STONE.defaultBlockState(), 2);
                        for (var p : BlockPos.betweenClosed(HERO.offset(-3, 0, -3), HERO.offset(3, 9, 3))) l.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
                        if (!HxMultiblockTests.placeItem(l, HERO, Direction.NORTH).consumesAction()) throw new IllegalStateException("display exchanger refused");
                    }
                    player.closeContainer();
                    player.connection.teleport(camera.x, camera.y, camera.z, yaw, pitch);
                    l.setDayTime(6000);
                    l.setWeatherParameters(6000, 0, false, false);
                    if (selected == SCREEN) {
                        // Opened as a click on the top cell of the shell-outlet column would open it: through the cell's controller lookup.
                        var top = loop.root().offset(1, 7, 0);
                        var hx = RhrHeatExchangerBlock.controller(l, top, l.getBlockState(top));
                        if (hx == null) throw new IllegalStateException("top cell has no controller");
                        RhrHeatExchangerMenu.open(player, hx);
                    }
                } catch (Exception ex) { throw new IllegalStateException(ex); }
            });
        }
        mc.player.noPhysics = true;
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
                for (int x = (O.getX() - 44) >> 4; x <= (O.getX() + 40) >> 4; x++)
                    for (int z = (O.getZ() - 44) >> 4; z <= (O.getZ() + 24) >> 4; z++)
                        player.connection.send(new net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket(l.getChunk(x, z), l.getChunkSource().getLightEngine(), null, null));
            });
        }
        if (shown < 160) return;
        if ((stage == LOOP || stage == CHUTE) && loop.hx().plant().heatMW() <= 0)
            throw new AssertionError("Full-size exchanger not working at stage " + stage + ": " + loop.rhr().connectionStatus());
        if (stage == SCREEN && (!(mc.player.containerMenu instanceof RhrHeatExchangerMenu m) || !m.present || !m.connected || m.heat <= 0 || m.secondaryFlow <= 0
                || m.primaryIn <= m.primaryOut || m.hotTemperature <= m.coldTemperature))
            throw new AssertionError("Exchanger panel does not show a working river loop: " + mc.player.containerMenu);
        if (stage == WARM + 1 && shown == 160) {
            // Every cell model of the full-size unit is baked with real textures, in every facing.
            int total = 0;
            for (var state : BwrBlocks.RHR_HEAT_EXCHANGER.get().getStateDefinition().getPossibleStates()) {
                if (!RhrHeatExchangerBlock.isAssembled(state)) continue;
                var model = mc.getModelManager().getModel(BlockModelShaper.stateToModelLocation(state));
                if (model == mc.getModelManager().getMissingModel()) throw new AssertionError("Missing model: " + state);
                for (int i = 0; i < 7; i++) {
                    var quads = model.getQuads(state, i == 6 ? null : Direction.values()[i], net.minecraft.util.RandomSource.create(42), ModelData.EMPTY, null);
                    total += quads.size();
                    for (var q : quads) if (q.getSprite().contents().name().getPath().equals("missingno")) throw new AssertionError("Missing texture: " + state);
                }
            }
            if (total < 4 * 26_000) throw new AssertionError("Full-size exchanger cell models too thin: " + total + " quads over four facings");
            com.mojang.logging.LogUtils.getLogger().info("HX MULTIBLOCK CLIENT CHECK: {} quads over the 4 x 72 assembled cell states", total);
        }
        mc.options.hideGui = stage < SCREEN;
        mc.getToasts().clear();
        if (shown < 170) return;
        if (NAMES[stage] != null) {
            try {
                var dir = mc.gameDirectory.toPath().resolve("hx-multiblock-check");
                java.nio.file.Files.createDirectories(dir);
                CheckShots.save(mc, dir.resolve(NAMES[stage] + ".png"));
            } catch (java.io.IOException ex) { throw new IllegalStateException(ex); }
            com.mojang.logging.LogUtils.getLogger().info("HX MULTIBLOCK CLIENT CHECK: {}", NAMES[stage]);
        }
        requested = false;
        if (++stage == NAMES.length) {
            com.mojang.logging.LogUtils.getLogger().info("HX MULTIBLOCK CLIENT CHECK PASS: full-size exchanger baked in all facings, river loop running through it, panel live from a top cell");
            mc.stop();
        }
    }
}
