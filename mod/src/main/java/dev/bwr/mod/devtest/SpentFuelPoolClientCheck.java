package dev.bwr.mod.devtest;

import com.mojang.logging.LogUtils;
import dev.bwr.mod.BwrMod;
import dev.bwr.mod.gui.SpentFuelPoolMenu;
import dev.bwr.mod.spentfuel.SpentFuelPoolBlockEntity;
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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

/**
 * Builds a spent fuel pool in a disposable world, as a player would (lined box, racks, ports), fills it with makeup water
 * and with fresh and long-cooled assemblies, and saves 1920 x 1080 pictures: the whole pool, the racks under the water with
 * the Cherenkov glow of the fresh fuel, the ports, the panel of a running pool, and the pool after the water has dropped below the fuel.
 * Run with {@code :mod:runTurbineModelCheck -PbwrSpentFuelPoolCheck}; the pictures land in {@code spent-fuel-pool-check/}
 * of the run directory.
 */
@EventBusSubscriber(modid = BwrMod.MOD_ID, value = Dist.CLIENT)
public final class SpentFuelPoolClientCheck {

    private static final BlockPos ORIGIN = new BlockPos(140, 210, 140);
    private static final String[] NAMES = {"pool-built", "pool-racks-glow", "pool-ports", "pool-panel", "pool-uncovered", "pool-panel-uncovered",
            "elevator-on-pool", "elevator-lowering", "elevator-carriage-low", "elevator-screen", "pool-underwater", "pool-night-glow"};
    private static final double[][] CAMERAS = {
            {156, 226, 125, 32, 27}, {144.5, 224, 135, 0, 52}, {144.5, 217.5, 131, 0, 6},
            {137, 216, 140, -59, 19}, {156, 226, 125, 32, 27}, {137, 216, 140, -59, 19},
            {153, 222.5, 139, 42, 24}, {141.5, 224.5, 139.5, -57, 55}, {141.5, 224.5, 139.5, -57, 55}, {150.5, 219.5, 140.5, 30, 10},
            {141.6, 216.4, 141.6, -45, 6}, {144.5, 224, 135, 0, 52}};
    /** The elevator stands on the middle of the east wall of the pool: ORIGIN + (8, 8, 4). The screen camera is within 8 blocks of it (a menu closes beyond that). */
    private static final BlockPos ELEVATOR = ORIGIN.offset(8, 8, 4);
    private static boolean started;
    private static boolean requested;
    private static int age;
    private static int joined;
    private static int stage;
    private static int shown;

    @SubscribeEvent
    public static void tick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
        if (!Boolean.getBoolean("bwr.spentFuelPoolCheck")) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (++age > 6000) {
            throw new IllegalStateException("Spent fuel pool client check timeout at stage " + stage);
        }
        if (!started && mc.screen != null && mc.getOverlay() == null) {
            started = true;
            mc.options.pauseOnLostFocus = false;
            mc.options.cloudStatus().set(CloudStatus.OFF);
            mc.options.fov().set(60);
            mc.createWorldOpenFlows().createFreshLevel("bwr-pool-check-" + System.currentTimeMillis(),
                    new LevelSettings("Pool check", GameType.CREATIVE, false, Difficulty.PEACEFUL, true, new GameRules(), WorldDataConfiguration.DEFAULT),
                    new WorldOptions(0, false, false),
                    r -> r.registryOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).createWorldDimensions(), mc.screen);
            return;
        }
        if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null || ++joined < 40) {
            return;
        }
        var server = mc.getSingleplayerServer();
        RenderEnvironment.log("spent-fuel-pool");
        if (!requested) {
            requested = true;
            shown = 0;
            int selected = stage;
            var uuid = mc.player.getUUID();
            server.execute(() -> {
                var p = server.getPlayerList().getPlayer(uuid);
                var l = p.serverLevel();
                p.getAbilities().flying = true;
                p.setNoGravity(true);
                p.onUpdateAbilities();
                p.connection.teleport(CAMERAS[selected][0], CAMERAS[selected][1], CAMERAS[selected][2], (float) CAMERAS[selected][3], (float) CAMERAS[selected][4]);
                if (selected == 0) {
                    for (int x = 6; x <= 11; x++) {
                        for (int z = 6; z <= 11; z++) {
                            l.setChunkForced(x, z, true);
                            l.getChunk(x, z);
                        }
                    }
                    for (var at : BlockPos.betweenClosed(new BlockPos(128, 209, 124), new BlockPos(168, 209, 160))) {
                        l.setBlock(at, Blocks.SMOOTH_STONE.defaultBlockState(), 3);
                    }
                    var f = SpentFuelPoolFixture.build(l, ORIGIN, 9, 9, 8, 14);
                    if (!f.be.isFormed()) {
                        throw new AssertionError("the pool did not form: " + f.be.problem());
                    }
                    l.getCapability(Capabilities.FluidHandler.BLOCK, f.makeup, Direction.NORTH)
                            .fill(ThermalWater.atTemperature(294_000, 31.0), IFluidHandler.FluidAction.EXECUTE);
                    long now = l.getGameTime();
                    var handler = f.be.itemHandler();
                    for (int slot = 0; slot < 24; slot++) {
                        // the first racks hold fuel that has just come out of the core; the rest is long-cooled (no discharge record)
                        var stack = slot < 12 ? SpentFuelPoolFixture.assembly(45000.0, 0, now)
                                : dev.bwr.mod.fuel.FuelAssemblyItem.stackOf(dev.bwr.mod.registry.BwrItems.FUEL_ASSEMBLY.get(),
                                new dev.bwr.mod.fuel.FuelAssemblyData("leu", 0.035, 180.0, 45000.0, 1.0));
                        handler.insertItem(slot, stack, false);
                    }
                    LogUtils.getLogger().info("SPENT FUEL POOL CLIENT CHECK server: formed {}, {} racks, {} assemblies, {} kg of water",
                            f.be.isFormed(), f.be.layout() == null ? 0 : f.be.layout().racks().size(), f.be.assemblyCount(), f.be.pool().massKg());
                    f.be.setCoolingEnabled(true);
                    f.be.energy().receiveEnergy(20_000, false);
                    l.getCapability(Capabilities.FluidHandler.BLOCK, f.coolingIn, Direction.NORTH)
                            .fill(ThermalWater.atTemperature(8000, 13.0), IFluidHandler.FluidAction.EXECUTE);
                }
                if (selected == 6) {
                    // a full pool (the elevator needs 6.5 m over the 4 m racks to lower) and the elevator on the rim of its east wall
                    var be = (SpentFuelPoolBlockEntity) l.getBlockEntity(ORIGIN.offset(0, 2, 4));
                    be.pool().restore(343_000.0, 25.0);
                    be.setCoolingEnabled(false);
                    be.simulateSeconds(1, l.getGameTime());
                    l.setBlock(ELEVATOR, dev.bwr.mod.registry.BwrBlocks.NEW_FUEL_ELEVATOR.get().defaultBlockState(), 3);
                }
                if (selected == 7) {
                    var elevator = dev.bwr.mod.spentfuel.NewFuelElevatorBlockEntity.at(l, ELEVATOR);
                    var why = elevator.offer(dev.bwr.mod.fuel.FuelAssemblyItem.stackOf(dev.bwr.mod.registry.BwrItems.FUEL_ASSEMBLY.get(),
                            new dev.bwr.mod.fuel.FuelAssemblyData("leu", 0.035, 180.0, 0.0, 1.0)), false);
                    if (why != null) {
                        throw new AssertionError("the elevator refused: " + why.english());
                    }
                    LogUtils.getLogger().info("NEW FUEL ELEVATOR CLIENT CHECK server: lowering, trip {} ticks", elevator.currentTrip().totalTicks());
                }
                if (selected == 8) {
                    // the carriage is far down its guide: let it run on until it is at the transfer height
                    var elevator = dev.bwr.mod.spentfuel.NewFuelElevatorBlockEntity.at(l, ELEVATOR);
                    if (elevator.currentTrip() != null) {
                        // half way across the racks, at the transfer height
                        long want = elevator.currentTrip().legTicks()[0] + elevator.currentTrip().legTicks()[1] / 2;
                        elevator.advanceForTest(l, want - (l.getGameTime() - elevator.tripStart()));
                    }
                }
                if (selected == 9) {
                    var elevator = dev.bwr.mod.spentfuel.NewFuelElevatorBlockEntity.at(l, ELEVATOR);
                    elevator.advanceForTest(l, 100_000);
                    elevator.raise(null);
                    elevator.advanceForTest(l, elevator.currentTrip() == null ? 0 : elevator.currentTrip().handoffTicks() + elevator.currentTrip().legTicks()[1] + 30);
                    dev.bwr.mod.gui.NewFuelElevatorMenu.open(p, elevator);
                }
                if (selected == 3 || selected == 5) {
                    var be = (SpentFuelPoolBlockEntity) l.getBlockEntity(ORIGIN.offset(0, 2, 4));
                    p.connection.teleport(CAMERAS[selected][0], CAMERAS[selected][1], CAMERAS[selected][2], (float) CAMERAS[selected][3], (float) CAMERAS[selected][4]);
                    SpentFuelPoolMenu.open(p, be);
                }
                if (selected == 4) {
                    var be = (SpentFuelPoolBlockEntity) l.getBlockEntity(ORIGIN.offset(0, 2, 4));
                    // the water falls to 0.6 m, far under the 4 m racks: the fuel stands in air
                    be.pool().restore(0.6 * 49_000.0, 60.0);
                    be.setCoolingEnabled(false);
                    be.simulateSeconds(1, l.getGameTime());
                    be.simulateSeconds(300, l.getGameTime());
                }
                l.setDayTime(selected == 11 ? 18000 : 6000);
                l.setWeatherParameters(6000, 0, false, false);
            });
        }
        if (!(mc.level.getBlockEntity(ORIGIN.offset(0, 2, 4)) instanceof SpentFuelPoolBlockEntity be) || !be.visFormed) {
            if (age % 200 == 0) {
                var serverBe = server.overworld().getBlockEntity(ORIGIN.offset(0, 2, 4));
                LogUtils.getLogger().info("SPENT FUEL POOL CLIENT CHECK waiting at stage {}: client block {} entity {} screen {}; server entity {} formed {} problem {}",
                        stage, mc.level.getBlockState(ORIGIN.offset(0, 2, 4)), mc.level.getBlockEntity(ORIGIN.offset(0, 2, 4)), mc.screen,
                        serverBe, serverBe instanceof SpentFuelPoolBlockEntity sb ? sb.isFormed() : "-",
                        serverBe instanceof SpentFuelPoolBlockEntity sb2 && sb2.problem() != null ? dev.bwr.mod.text.BwrText.english(sb2.problem()) : "-");
            }
            return;
        }
        boolean panel = stage == 3 || stage == 5 || stage == 9;
        if (stage == 9 ? !(mc.player.containerMenu instanceof dev.bwr.mod.gui.NewFuelElevatorMenu em && em.present && em.linked)
                : panel ? !(mc.player.containerMenu instanceof SpentFuelPoolMenu m && m.present && m.formed) : mc.screen != null) {
            return;
        }
        var c = CAMERAS[stage];
        mc.player.getAbilities().flying = true;
        mc.player.setDeltaMovement(Vec3.ZERO);
        mc.player.setPos(c[0], c[1], c[2]);
        mc.player.setYRot((float) c[3]);
        mc.player.setXRot((float) c[4]);
        shown++;
        if (stage == 1 && shown == 5) {
            boolean glowing = false;
            for (byte b : be.visGlow) {
                glowing |= b > 3;
            }
            if (!glowing) {
                throw new AssertionError("no cell glows in the picture the controller sent: " + java.util.Arrays.toString(be.visGlow));
            }
        }
        if (stage == 1 && shown == 5 && dev.bwr.mod.client.DeferredLiquids.replayedLastFrame() < 1) {
            // the water and the glow are replayed after the translucent terrain (shader packs route that pass to their water program)
            throw new AssertionError("the pool water and glow were not replayed after the translucent terrain");
        }
        if (shown < 100) {
            return;
        }
        mc.options.hideGui = !panel;
        mc.getToasts().clear();
        if (shown < 106) {
            return;
        }
        try {
            var dir = mc.gameDirectory.toPath().resolve("spent-fuel-pool-check");
            java.nio.file.Files.createDirectories(dir);
            CheckShots.save(mc, dir.resolve(NAMES[stage] + ".png"));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        LogUtils.getLogger().info("SPENT FUEL POOL CLIENT CHECK: captured {}", NAMES[stage]);
        requested = false;
        mc.options.hideGui = false;
        if (panel) {
            mc.player.closeContainer();
        }
        if (++stage == NAMES.length) {
            LogUtils.getLogger().info("SPENT FUEL POOL CLIENT CHECK PASS: a formed pool with racks, water and glow, its ports, its panel and the uncovered fuel");
            mc.stop();
        }
    }
}
