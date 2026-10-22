package dev.bwr.mod.devtest;

import com.mojang.logging.LogUtils;
import dev.bwr.core.radiation.DoseModel;
import dev.bwr.mod.BwrMod;
import dev.bwr.mod.panel.PanelBlock;
import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.PanelOps;
import dev.bwr.mod.panel.component.AnnunciatorData;
import dev.bwr.mod.panel.component.ComponentTypes;
import dev.bwr.mod.panel.component.Footprint;
import dev.bwr.mod.panel.component.GaugeData;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.wiring.PanelSampler;
import dev.bwr.mod.panel.wiring.PlantSource;
import dev.bwr.mod.radiation.AreaRadiationMonitorBlock;
import dev.bwr.mod.radiation.AreaRadiationMonitorBlockEntity;
import dev.bwr.mod.radiation.RadiationReadout;
import dev.bwr.mod.radiation.RadiationSources;
import dev.bwr.mod.radiation.client.RadiationClient;
import dev.bwr.mod.reactor.ReactorControllerBlockEntity;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.registry.BwrItems;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
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
 * Disposable real-client check of the radiation tools (run with {@code -PbwrRadiationClientCheck} through run-mc.ps1), three
 * 1920 x 1080 pictures in {@code run/turbineModelCheck/radiation-client-check/}: the survey meter held beside a reactor with
 * a 5 % fission product release (the HUD readout), and a control board with a dial gauge and an annunciator wired to an area
 * radiation monitor mounted beside it, before and during an alarm. Not shipped: the devtest package is stripped from the
 * release jar.
 */
@EventBusSubscriber(modid = BwrMod.MOD_ID, value = Dist.CLIENT)
public final class RadiationClientCheck {
    private static final String[] NAMES = {"radiation-meter-hud", "radiation-monitor-normal", "radiation-monitor-alarm"};
    private static final BlockPos PLANT_MIN = new BlockPos(7300, 104, 7300);
    private static final BlockPos BOARD = new BlockPos(7400, 110, 7400);
    /** Stage 0: outside the vessel's west wall, looking east at it. */
    private static final Vec3 STAND_PLANT = new Vec3(PLANT_MIN.getX() - 7.0, PLANT_MIN.getY() + 2.0, PLANT_MIN.getZ() + 7.5);
    /** Stages 1 and 2: in front of the board, looking at it. */
    private static final Vec3 STAND_BOARD = new Vec3(7398.8, 109.2, 7396.2);
    private static final Object SOURCE = new Object();

    private static boolean started, built, requested, standing;
    private static volatile Throwable buildFailure;
    private static volatile Vec3 aim;
    private static int age, joined, stage, shown;
    private static BlockPos gaugeOwner, annunciatorOwner;
    private static int gaugeId, annunciatorId;

    private RadiationClientCheck() {}

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post ignored) {
        if (!Boolean.getBoolean("bwr.radiationClientCheck")) return;
        try {
            step();
        } catch (Throwable failure) {
            LogUtils.getLogger().error("RADIATION CLIENT CHECK FAILED at stage {}", stage < NAMES.length ? NAMES[stage] : "end", failure);
            Minecraft.getInstance().stop();
        }
    }

    private static BlockPos blockAt(int col, int row) {
        return BOARD.relative(Direction.WEST, col).below(row);
    }

    private static BlockPos monitorPos() {
        return blockAt(2, 0).north();
    }

    private static void step() {
        Minecraft mc = Minecraft.getInstance();
        if (buildFailure != null) throw new IllegalStateException("Radiation showcase build failed", buildFailure);
        if (++age > 3600) throw new IllegalStateException("Radiation client check timed out at stage " + stage);
        if (!started && mc.screen != null && mc.getOverlay() == null) {
            started = true;
            mc.options.pauseOnLostFocus = false;
            mc.options.cloudStatus().set(net.minecraft.client.CloudStatus.OFF);
            mc.options.renderDistance().set(10);
            mc.options.fov().set(70);
            mc.options.guiScale().set(2);
            mc.createWorldOpenFlows().createFreshLevel("bwr-radiation-client-" + System.currentTimeMillis(),
                    new LevelSettings("Radiation client check", GameType.CREATIVE, false, Difficulty.PEACEFUL,
                            true, new GameRules(), WorldDataConfiguration.DEFAULT),
                    new WorldOptions(0, false, false),
                    r -> r.registryOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).createWorldDimensions(), mc.screen);
            return;
        }
        if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null || ++joined < 40) return;
        var server = mc.getSingleplayerServer();
        var uuid = mc.player.getUUID();
        if (!requested) {
            if (stage == NAMES.length) {
                LogUtils.getLogger().info("RADIATION CLIENT CHECK PASS: {} captures", stage);
                mc.stop();
                return;
            }
            requested = true;
            shown = 0;
            standing = false;
            mc.setScreen(null);
            mc.options.hideGui = false;
            int current = stage;
            server.execute(() -> {
                var player = server.getPlayerList().getPlayer(uuid);
                if (player == null) throw new IllegalStateException("Radiation check player missing");
                ServerLevel level = player.serverLevel();
                try {
                    if (!built) {
                        build(level);
                        built = true;
                    }
                    Vec3 stand = current == 0 ? STAND_PLANT : STAND_BOARD;
                    Vec3 target = current == 0
                            ? new Vec3(PLANT_MIN.getX() + 0.5, PLANT_MIN.getY() + 3.0, PLANT_MIN.getZ() + 7.5)
                            : new Vec3(7399.4, 109.1, 7401.0);
                    if (current >= 1) {
                        // Stage 1: a weak source (below the alert setpoint); stage 2: 1.6 mSv/h, above the 1 mSv/h alarm.
                        double mSv = current == 1 ? 0.04 : 1.6;
                        RadiationSources.emit(level, SOURCE, Vec3.atCenterOf(monitorPos()).add(0, 0, -3.0),
                                mSv / DoseModel.pointSourceMsvPerH(1.0, 3.0, 1.0), 1.0);
                        var monitor = (AreaRadiationMonitorBlockEntity) level.getBlockEntity(monitorPos());
                        monitor.invalidate();
                        monitor.refresh();
                        for (int col = 0; col < 2; col++) {
                            if (level.getBlockEntity(blockAt(col, 0)) instanceof PanelBlockEntity be) {
                                PanelSampler.track(be);
                                be.sampleLive();
                                if (current == 2 && col == 1) {
                                    // Steady (acknowledged) rather than flashing, so the picture does not depend on the flash phase.
                                    ((AnnunciatorData) be.component(annunciatorId).data()).logic.acknowledge();
                                }
                                be.syncFull();
                            }
                        }
                    }
                    Vec3 delta = target.subtract(stand.add(0, player.getEyeHeight(), 0));
                    float yaw = (float) (Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90);
                    float pitch = (float) -Math.toDegrees(Math.atan2(delta.y, Math.hypot(delta.x, delta.z)));
                    aim = new Vec3(yaw, pitch, 0);
                    player.setNoGravity(true);
                    player.getAbilities().flying = true;
                    player.onUpdateAbilities();
                    level.setDayTime(6_000);
                    level.setWeatherParameters(6000, 0, false, false);
                    // The survey meter is held in every stage (the board shots show the hotbar and HUD the way a player sees them).
                    player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(BwrItems.SURVEY_METER.get()));
                    player.connection.teleport(stand.x, stand.y, stand.z, yaw, pitch);
                    standing = true;
                } catch (Throwable failure) {
                    buildFailure = failure;
                }
            });
        }
        if (!standing || aim == null) return;
        Vec3 stand = stage == 0 ? STAND_PLANT : STAND_BOARD;
        mc.player.setPos(stand.x, stand.y, stand.z);
        mc.player.setDeltaMovement(Vec3.ZERO);
        mc.player.setYRot((float) aim.x);
        mc.player.setXRot((float) aim.y);
        mc.player.yRotO = (float) aim.x;
        mc.player.xRotO = (float) aim.y;
        if (mc.player.position().distanceToSqr(stand) > 0.25) return;
        if (++shown < 90) return;

        if (stage == 0) {
            if (!RadiationClient.showing()) throw new AssertionError("The survey meter readout is not showing: fresh=" + RadiationReadout.fresh()
                    + " held=" + mc.player.getMainHandItem());
            double rate = RadiationReadout.doseRate();
            if (!(rate > 1000.0)) throw new AssertionError("The meter reads too little beside a 5 % release: " + rate + " mSv/h");
            LogUtils.getLogger().info("RADIATION CLIENT CHECK meter HUD reads {} ({} mSv/h), your dose {}", DoseModel.formatRate(rate), rate,
                    DoseModel.formatDose(RadiationReadout.personalDose()));
        } else {
            var monitorState = mc.level.getBlockState(monitorPos());
            int lamp = monitorState.getValue(AreaRadiationMonitorBlock.LAMP);
            if (lamp != (stage == 1 ? 0 : 2)) throw new AssertionError("The monitor lamp is " + lamp + " at stage " + stage);
            var gaugeBlock = (PanelBlockEntity) mc.level.getBlockEntity(gaugeOwner);
            var gauge = gaugeBlock.component(gaugeId);
            double expected = stage == 1 ? 0.04 : 1.6;
            if (Math.abs(gauge.input(0) - expected) > 0.05 * expected + 1e-6)
                throw new AssertionError("The gauge reads " + gauge.input(0) + ", not " + expected);
            var annunciator = (AnnunciatorData) ((PanelBlockEntity) mc.level.getBlockEntity(annunciatorOwner)).component(annunciatorId).data();
            if ((stage == 2) != (annunciator.logic.display() != dev.bwr.mod.panel.component.AnnunciatorLogic.Display.OFF))
                throw new AssertionError("The annunciator is " + annunciator.logic.display() + " at stage " + stage);
            LogUtils.getLogger().info("RADIATION CLIENT CHECK {}: lamp {} gauge {} annunciator {}", NAMES[stage], lamp, gauge.input(0),
                    annunciator.logic.display());
        }
        try {
            var directory = mc.gameDirectory.toPath().resolve("radiation-client-check");
            Files.createDirectories(directory);
            CheckShots.save(mc, directory.resolve(NAMES[stage] + ".png"));
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("Radiation screenshot failed", ex);
        }
        LogUtils.getLogger().info("RADIATION CLIENT CHECK: {}", NAMES[stage]);
        requested = false;
        stage++;
    }

    private static void build(ServerLevel level) throws Exception {
        for (BlockPos pos : BlockPos.betweenClosed(PLANT_MIN.offset(-14, -4, -4), PLANT_MIN.offset(24, -4, 24)))
            level.setBlock(pos, Blocks.SMOOTH_STONE.defaultBlockState(), 2);
        ReactorControllerBlockEntity reactor = CompactCoreRegressionTests.build(level, PLANT_MIN, 15, 15, false);
        reactor.core().getContainment().step(0.0, 0.0, 0.05, 1.0); // a 5 % gap release, airborne in containment

        for (BlockPos pos : BlockPos.betweenClosed(new BlockPos(7388, 108, 7388), new BlockPos(7412, 108, 7406)))
            level.setBlock(pos, Blocks.SMOOTH_STONE.defaultBlockState(), 2);
        for (int row = 0; row < 2; row++) {
            for (int col = 0; col < 2; col++) {
                level.setBlock(blockAt(col, row), BwrBlocks.CONTROL_PANEL_VERTICAL.get().defaultBlockState()
                        .setValue(PanelBlock.FACING, Direction.NORTH), 3);
            }
            level.setBlock(blockAt(2, row), Blocks.POLISHED_ANDESITE.defaultBlockState(), 3);
        }
        level.setBlock(monitorPos(), BwrBlocks.AREA_RADIATION_MONITOR.get().defaultBlockState()
                .setValue(AreaRadiationMonitorBlock.FACING, Direction.NORTH), 3);

        gaugeOwner = blockAt(0, 0);
        annunciatorOwner = blockAt(1, 0);
        PanelComponent gauge = PanelOps.placeDirect(level, gaugeOwner, 2, 2, ComponentTypes.DIAL_GAUGE, Footprint.of(12, 12));
        PanelComponent annunciator = PanelOps.placeDirect(level, annunciatorOwner, 2, 2, ComponentTypes.ANNUNCIATOR, Footprint.of(12, 8));
        gaugeId = gauge.id();
        annunciatorId = annunciator.id();
        GaugeData scale = (GaugeData) gauge.data();
        scale.min = 0.0;
        scale.max = 2.0;
        scale.units = "mSv/h";
        scale.measures = dev.bwr.mod.signal.Quantity.DOSE_RATE;
        scale.bandLow[0] = 0.1;
        scale.bandHigh[0] = 1.0;
        scale.bandLow[1] = 1.0;
        scale.bandHigh[1] = 2.0;
        gauge.setSource(0, new PlantSource(monitorPos(), "dose_rate", 1, 0));
        AnnunciatorData legend = (AnnunciatorData) annunciator.data();
        legend.line1 = "HIGH";
        legend.line2 = "RADIATION";
        annunciator.setSource(0, new PlantSource(monitorPos(), "high_radiation", 1, 0));
        ((PanelBlockEntity) level.getBlockEntity(gaugeOwner)).setChanged();
    }
}
