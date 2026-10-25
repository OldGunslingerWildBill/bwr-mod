package dev.bwr.mod.devtest;

import com.mojang.logging.LogUtils;
import dev.bwr.mod.BwrMod;
import dev.bwr.mod.gui.PowerModuleMenu;
import dev.bwr.mod.gui.TurbineAuxMenu;
import dev.bwr.mod.gui.client.PowerModuleScreen;
import dev.bwr.mod.gui.client.TurbineAuxScreen;
import dev.bwr.mod.power.PowerModuleBlockEntity;
import dev.bwr.mod.power.TurbineAuxBlockEntity;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.water.ThermalWater;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

/**
 * Opt-in pictures of the turbine auxiliaries (-PbwrTurbineAuxCheck): a generator and an HP section with a Main Oil Tank, the
 * shaft-driven pump on the generator end and a Generator Cooling Skid, in the world and in their screens, in a fresh flat world.
 * Saves 1920 x 1080 pictures under run/.../turbine-aux-check and checks that the screens read the running machines.
 */
@EventBusSubscriber(modid = BwrMod.MOD_ID, value = Dist.CLIENT)
public final class TurbineAuxClientCheck {
    private TurbineAuxClientCheck() {}

    private static boolean started, requested;
    private static int age, stage, shown, joined;
    private static final BlockPos ROOT = new BlockPos(260, 215, 260);
    private static PowerModuleBlockEntity gen, hp;
    private static TurbineAuxBlockEntity tank, skid, pump;
    private static final String[] NAMES = {"aux-overview", "aux-oil-tank", "aux-shaft-pump", "aux-cooling-skid", "gui-generator-aux", "gui-hp-aux",
            "gui-oil-tank", "gui-cooling-skid", "gui-shaft-pump", "gui-generator-turning-gear"};
    /** x, y, z, yaw, pitch of the camera relative to the root for the four pictures of the world. */
    private static final double[][] CAMERA = {{15, 7, 15, 135, 16}, {7, 3, 10, 140, 12}, {11, 3, 6, 125, 10}, {7, 3, -10, 40, 12}};

    /** The test supplies what a plant would: FE on the buses and water at the flanges, and holds the train at the speed of the stage. */
    @SubscribeEvent
    public static void supply(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        if (!Boolean.getBoolean("bwr.turbineAuxCheck") || gen == null || gen.isRemoved()) return;
        tank.acEnergy.receiveEnergy(100_000, false);
        tank.dcEnergy.receiveEnergy(1_000_000, false);
        skid.acEnergy.receiveEnergy(100_000, false);
        gen.gearEnergy.receiveEnergy(200_000, false);
        for (var s : new TurbineAuxBlockEntity[] {tank, skid}) s.coldInlet().fill(ThermalWater.atTemperature(30, 13), IFluidHandler.FluidAction.EXECUTE);
        if (stage < 9) {
            gen.rpm = 1500;
            hp.rpm = 1500;
        }
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        if (!Boolean.getBoolean("bwr.turbineAuxCheck")) return;
        Minecraft mc = Minecraft.getInstance();
        if (++age > 4000) throw new IllegalStateException("Turbine auxiliary client check timeout, stage " + stage);
        if (!started && mc.screen != null && mc.getOverlay() == null) {
            started = true;
            mc.options.pauseOnLostFocus = false;
            mc.options.cloudStatus().set(CloudStatus.OFF);
            mc.options.fov().set(65);
            mc.createWorldOpenFlows().createFreshLevel("bwr-aux-check-" + System.currentTimeMillis(),
                    new LevelSettings("Turbine auxiliary check", GameType.CREATIVE, false, Difficulty.PEACEFUL, true, new GameRules(), WorldDataConfiguration.DEFAULT),
                    new WorldOptions(0, false, false), r -> r.registryOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).createWorldDimensions(), mc.screen);
            return;
        }
        if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null) return;
        if (++joined < 40) return;
        boolean world = stage < 4;
        if (!requested) {
            requested = true;
            shown = 0;
            var server = mc.getSingleplayerServer();
            var uuid = mc.player.getUUID();
            int selected = stage;
            server.execute(() -> {
                var player = server.getPlayerList().getPlayer(uuid);
                var l = player.serverLevel();
                player.getAbilities().flying = true;
                player.setNoGravity(true);
                player.onUpdateAbilities();
                if (selected == 0) {
                    for (int x = 12; x <= 19; x++) for (int z = 12; z <= 19; z++) { l.setChunkForced(x, z, true); l.getChunk(x, z); }
                    for (var p : BlockPos.betweenClosed(ROOT.offset(-20, -1, -16), ROOT.offset(20, -1, 16))) l.setBlock(p, net.minecraft.world.level.block.Blocks.SMOOTH_STONE.defaultBlockState(), 3);
                    gen = PowerModuleRuntimeCheck.place(l, BwrBlocks.NUCLEAR_GENERATOR.get(), ROOT, Direction.EAST);
                    hp = PowerModuleRuntimeCheck.place(l, BwrBlocks.HP_TURBINE.get(), ROOT.offset(-9, 0, 0), Direction.EAST);
                    gen.markAuxRequired();
                    hp.markAuxRequired();
                    var b = gen.block();
                    var s = gen.getBlockState();
                    var end = b.shaft(ROOT, s, true).relative(b.shaftFace(s, true));
                    l.setBlock(end, BwrBlocks.SHAFT_OIL_PUMP.get().defaultBlockState().setValue(dev.bwr.mod.power.TurbineAuxBlock.FACING, b.shaftFace(s, true)), 3);
                    var tankAt = ROOT.offset(0, 0, 3);
                    l.setBlock(tankAt, BwrBlocks.MAIN_OIL_TANK.get().defaultBlockState().setValue(dev.bwr.mod.power.TurbineAuxBlock.FACING, Direction.NORTH), 3);
                    var skidAt = ROOT.offset(0, 0, -3);
                    l.setBlock(skidAt, BwrBlocks.GENERATOR_COOLING_SKID.get().defaultBlockState().setValue(dev.bwr.mod.power.TurbineAuxBlock.FACING, Direction.SOUTH), 3);
                    for (var p : new BlockPos[] {end, tankAt, skidAt}) dev.bwr.mod.power.TurbineAuxBlock.attach(l, p);
                    pump = (TurbineAuxBlockEntity) l.getBlockEntity(end);
                    tank = (TurbineAuxBlockEntity) l.getBlockEntity(tankAt);
                    skid = (TurbineAuxBlockEntity) l.getBlockEntity(skidAt);
                    tank.addOil(13_000);
                    tank.setAcPump(true);
                    skid.addHydrogen(20);
                    skid.setStatorPumps(true);
                    // Cooling water pipes into the flanges of the tank (east-west) and of the skid (facing south: cold in on the east).
                    for (int d = 1; d <= 4; d++) {
                        var west = tankAt.offset(-d, 0, 0);
                        var east = tankAt.offset(d, 0, 0);
                        l.setBlock(west, BwrBlocks.HIGH_PRESSURE_WATER_PIPE.get().stateWithConnections(l, west), 3);
                        l.setBlock(east, BwrBlocks.HIGH_PRESSURE_WATER_PIPE.get().stateWithConnections(l, east), 3);
                        var sw = skidAt.offset(-d, 0, 0);
                        var se = skidAt.offset(d, 0, 0);
                        l.setBlock(sw, BwrBlocks.HIGH_PRESSURE_WATER_PIPE.get().stateWithConnections(l, sw), 3);
                        l.setBlock(se, BwrBlocks.HIGH_PRESSURE_WATER_PIPE.get().stateWithConnections(l, se), 3);
                    }
                    var tag = hp.saveWithoutMetadata(l.registryAccess());
                    tag.putDouble("RotorC", 268);
                    hp.loadWithComponents(tag, l.registryAccess());
                    l.setDayTime(6000);
                    l.setWeatherParameters(6000, 0, false, false);
                }
                if (selected == 9) {
                    gen.rpm = 5;
                    hp.rpm = 5;
                    gen.setTurningGear(true);
                }
                if (selected < 4) {
                    player.closeContainer();
                    var c = CAMERA[selected];
                    player.connection.teleport(ROOT.getX() + .5 + c[0], ROOT.getY() + c[1], ROOT.getZ() + .5 + c[2], (float) c[3], (float) c[4]);
                    return;
                }
                player.closeContainer();
                switch (selected) {
                    case 4, 9 -> { player.connection.teleport(ROOT.getX() + 5.5, ROOT.getY() + 3, ROOT.getZ() - 4.5, 0, 12); PowerModuleMenu.open(player, ROOT, ROOT); }
                    case 5 -> { player.connection.teleport(ROOT.getX() - 8.5, ROOT.getY() + 3, ROOT.getZ() - 4.5, 0, 12); PowerModuleMenu.open(player, ROOT.offset(-9, 0, 0), ROOT.offset(-9, 0, 0)); }
                    case 6 -> TurbineAuxMenu.open(player, tank.getBlockPos());
                    case 7 -> TurbineAuxMenu.open(player, skid.getBlockPos());
                    default -> TurbineAuxMenu.open(player, pump.getBlockPos());
                }
            });
        }
        if (world && requested && mc.screen == null) {
            var c = CAMERA[stage];
            mc.player.getAbilities().flying = true;
            mc.player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
            mc.player.setPos(ROOT.getX() + .5 + c[0], ROOT.getY() + c[1], ROOT.getZ() + .5 + c[2]);
            mc.player.setYRot((float) c[3]);
            mc.player.setXRot((float) c[4]);
        }
        boolean ready;
        if (world) {
            ready = mc.screen == null && mc.level.getBlockState(ROOT).is(BwrBlocks.NUCLEAR_GENERATOR.get())
                    && mc.level.getBlockState(ROOT.offset(0, 0, 3)).is(BwrBlocks.MAIN_OIL_TANK.get());
        } else if (stage == 4 || stage == 5 || stage == 9) {
            ready = mc.screen instanceof PowerModuleScreen && mc.player.containerMenu instanceof PowerModuleMenu m && m.present && m.lubeGov
                    && (stage == 9 ? m.gearRolling : m.oilPsig > 20 && (stage != 4 || m.statorFlow > 0.9));
        } else {
            ready = mc.screen instanceof TurbineAuxScreen && mc.player.containerMenu instanceof TurbineAuxMenu m && m.present && m.attached && m.psig > 20
                    && (stage != 7 || m.statorFlow > 0.9);
        }
        if (!ready && age % 200 == 0) LogUtils.getLogger().info("AUX CLIENT waiting: stage={} screen={}", stage, mc.screen == null ? "world" : mc.screen.getClass().getSimpleName());
        if (!ready || ++shown < 80) return;
        mc.options.hideGui = world;
        mc.getToasts().clear();
        if (shown < 85) return;
        // The screens read the running machines.
        if (mc.player.containerMenu instanceof PowerModuleMenu m) {
            if (!m.lubeGov || (stage != 9 && m.oilPsig < 20) || m.bearingC < 30) throw new IllegalStateException("The power module screen does not read the oil system: " + m.oilPsig + " psig " + m.bearingC + " C");
            if (stage == 9 && (!m.gearOn || Math.abs(m.rpm - 3) > 0.5)) throw new IllegalStateException("The turning gear is not rolling the shaft: " + m.rpm);
        }
        if (mc.player.containerMenu instanceof TurbineAuxMenu m && stage == 6 && (!m.acRun || m.oilFraction < 0.5)) throw new IllegalStateException("The tank screen does not read the pumps");
        try {
            var dir = mc.gameDirectory.toPath().resolve("turbine-aux-check");
            java.nio.file.Files.createDirectories(dir);
            CheckShots.save(mc, dir.resolve(NAMES[stage] + ".png"));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        LogUtils.getLogger().info("AUX CLIENT CHECK: rendered {}", NAMES[stage]);
        mc.player.closeContainer();
        requested = false;
        mc.options.hideGui = false;
        if (++stage == NAMES.length) {
            LogUtils.getLogger().info("AUX CLIENT CHECK PASS: the oil tank, the shaft pump and the cooling skid in the world, and their screens reading the running machines");
            mc.stop();
        }
    }
}
