package dev.bwr.mod.devtest;

import com.mojang.logging.LogUtils;
import dev.bwr.mod.BwrMod;
import dev.bwr.mod.fuel.BwrDataComponents;
import dev.bwr.mod.panel.MachineNames;
import dev.bwr.mod.panel.PanelBlock;
import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.PanelOps;
import dev.bwr.mod.panel.client.NetworkLines;
import dev.bwr.mod.panel.client.NetworkShareClient;
import dev.bwr.mod.panel.client.NetworkingScreen;
import dev.bwr.mod.panel.component.ComponentTypes;
import dev.bwr.mod.panel.component.Footprint;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.networking.NetworkingOps;
import dev.bwr.mod.panel.wiring.CcSource;
import dev.bwr.mod.panel.wiring.PanelSampler;
import dev.bwr.mod.panel.wiring.PlantSink;
import dev.bwr.mod.panel.wiring.PlantSource;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.registry.BwrItems;
import dev.bwr.mod.steam.MainSteamIsolationValveBlock;
import dev.bwr.mod.steam.MainSteamIsolationValveBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
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

import java.io.IOException;
import java.nio.file.Files;

/**
 * Disposable real-client check of the Networking Wrench (run with -PbwrNetworkingClientCheck through run-mc.ps1): one
 * board of gauges, a display and a button wired to two valves, photographed with the wrench pointed at a gauge (the
 * line to its valve), at a valve (the fan-out to its components), at an unlinked valve (the linkable glow, with the
 * selected valve yellow), the machine Networking screen, and the panel before and after "Name linked components" is
 * pressed on that screen. It does not run in ordinary play or ship in the release jar.
 */
@EventBusSubscriber(modid = BwrMod.MOD_ID, value = Dist.CLIENT)
public final class NetworkingClientCheck {
    private static final BlockPos BOARD = new BlockPos(7100, 127, 7100);
    private static final BlockPos VALVE_A = new BlockPos(7103, 124, 7098);
    private static final BlockPos VALVE_B = new BlockPos(7095, 124, 7098);
    /** A valve nothing is wired to: the linkable one. */
    private static final BlockPos VALVE_C = new BlockPos(7099, 123, 7099);
    /** An FE meter (power in FE per tick) for the Power units screen. */
    private static final BlockPos METER = new BlockPos(7107, 124, 7098);
    private static final Vec3 STAND = new Vec3(7099.5, 125.4, 7096.0);
    private static final String[] NAMES = {"net-hover-gauge-line", "net-hover-device-fanout", "net-linkable-glow",
            "net-networking-screen", "net-name-before", "net-name-after", "net-config-screen", "net-config-power"};

    private static boolean started, built, requested;
    private static volatile Throwable buildFailure;
    private static volatile Vec3 aim;
    private static volatile boolean standing;
    private static int age, joined, stage, shown, captures;
    private static BlockPos gaugeOwner;
    private static int gaugeId;
    private static final int[] IDS = new int[6];
    private static final BlockPos[] OWNERS = new BlockPos[6];

    private NetworkingClientCheck() {}

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post ignored) {
        if (!Boolean.getBoolean("bwr.networkingClientCheck")) return;
        try {
            step();
        } catch (Throwable failure) {
            // A failed check ends the run (and the game) with the reason, instead of leaving a window open.
            LogUtils.getLogger().error("NETWORKING CLIENT CHECK FAILED at stage {}", stage < NAMES.length ? NAMES[stage] : "end", failure);
            Minecraft.getInstance().stop();
        }
    }

    private static void step() {
        Minecraft mc = Minecraft.getInstance();
        if (buildFailure != null) throw new IllegalStateException("Networking showcase build failed", buildFailure);
        if (++age > 3000) throw new IllegalStateException("Networking client check timed out at stage " + stage);
        if (!started && mc.screen != null && mc.getOverlay() == null) {
            started = true;
            mc.options.pauseOnLostFocus = false;
            mc.options.cloudStatus().set(net.minecraft.client.CloudStatus.OFF);
            mc.options.renderDistance().set(10);
            mc.options.fov().set(100);
            mc.options.guiScale().set(2);
            mc.createWorldOpenFlows().createFreshLevel("bwr-networking-client-" + System.currentTimeMillis(),
                    new LevelSettings("Networking client check", GameType.CREATIVE, false, Difficulty.PEACEFUL,
                            true, new GameRules(), WorldDataConfiguration.DEFAULT),
                    new WorldOptions(0, false, false),
                    r -> r.registryOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).createWorldDimensions(), mc.screen);
            return;
        }
        if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null || ++joined < 35) return;
        if (!requested) {
            if (stage == NAMES.length) {
                LogUtils.getLogger().info("NETWORKING CLIENT CHECK PASS: {} captures, {} lines, {} outlines", captures,
                        NetworkLines.debugLines(), NetworkLines.debugGlows());
                mc.stop();
                return;
            }
            requested = true;
            shown = 0;
            standing = false;
            mc.setScreen(null);
            mc.options.hideGui = stage != 3;
            int selected = stage;
            var server = mc.getSingleplayerServer();
            var uuid = mc.player.getUUID();
            server.execute(() -> {
                var player = server.getPlayerList().getPlayer(uuid);
                if (player == null) throw new IllegalStateException("Networking check player missing");
                ServerLevel level = player.serverLevel();
                if (!built) {
                    try {
                        build(level);
                        built = true;
                    } catch (Throwable failure) {
                        buildFailure = failure;
                        return;
                    }
                }
                Vec3 eye = STAND.add(0, player.getEyeHeight(), 0);
                Vec3 target = switch (selected) {
                    case 0 -> faceCenter(level, 0);
                    case 1 -> Vec3.atCenterOf(VALVE_A.above());
                    case 2 -> Vec3.atCenterOf(VALVE_C.above());
                    case 3 -> Vec3.atCenterOf(VALVE_A.above());
                    default -> new Vec3(7099.5, 124.0, 7100.5); // below the board: nothing to point at
                };
                Vec3 delta = target.subtract(eye);
                float yaw = (float) (Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90);
                float pitch = (float) -Math.toDegrees(Math.atan2(delta.y, Math.hypot(delta.x, delta.z)));
                aim = new Vec3(yaw, pitch, 0);
                player.setNoGravity(true);
                player.getAbilities().flying = true;
                player.onUpdateAbilities();
                level.setDayTime(6_000);
                level.setWeatherParameters(6000, 0, false, false);
                ItemStack wrench = new ItemStack(BwrItems.NETWORKING_WRENCH.get());
                if (selected == 2) wrench.set(BwrDataComponents.LINKED_DEVICE.get(), GlobalPos.of(level.dimension(), VALVE_A));
                player.setItemInHand(InteractionHand.MAIN_HAND, wrench);
                player.connection.teleport(STAND.x, STAND.y, STAND.z, yaw, pitch);
                standing = true;
            });
        }
        if (!standing || aim == null) return;
        // Hold the camera: the picture is of the crosshair target, so nothing may drift.
        if (mc.screen == null || stage != 3 && stage != 5) {
            mc.player.setPos(STAND.x, STAND.y, STAND.z);
            mc.player.setDeltaMovement(Vec3.ZERO);
            mc.player.setYRot((float) aim.x);
            mc.player.setXRot((float) aim.y);
            mc.player.yRotO = (float) aim.x;
            mc.player.xRotO = (float) aim.y;
        }
        if (mc.player.position().distanceToSqr(STAND) > 0.25) return;
        ++shown;
        var server = mc.getSingleplayerServer();
        var uuid = mc.player.getUUID();
        if (stage == 3 && shown == 30) {
            // The real path: the server answers the wrench with the screen.
            server.execute(() -> {
                var player = server.getPlayerList().getPlayer(uuid);
                if (!NetworkingOps.openScreen(player, VALVE_A)) throw new IllegalStateException("The Networking screen did not open");
            });
        }
        if (stage == 5 && shown == 20) {
            server.execute(() -> {
                var player = server.getPlayerList().getPlayer(uuid);
                if (!NetworkingOps.openScreen(player, VALVE_A)) throw new IllegalStateException("The Networking screen did not open");
            });
        }
        if (stage == 5 && shown == 50) {
            // Name the machine on its screen and press the real "Name linked components" button.
            if (!(mc.screen instanceof NetworkingScreen screen)) throw new AssertionError("The Networking screen is not open");
            EditBox name = null;
            Button push = null;
            for (var child : screen.children()) {
                if (child instanceof EditBox box && name == null) name = box;
                if (child instanceof Button button && button.getMessage().getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents tr
                        && tr.getKey().equals("screen.bwr.networking.name_linked")) push = button;
            }
            if (name == null || push == null) throw new AssertionError("The screen has no name box or no Name linked components button");
            name.setValue("RHR-1");
            push.onPress();
        }
        if (stage == 5 && shown == 56) mc.setScreen(null);
        if (stage == 6 || stage == 7) {
            // The wrench screen of a dial (caption, Measures, Use machine name) and of a dial on a power signal (Power units).
            int index = stage == 6 ? 0 : 5;
            if (shown == 10 && stage == 7) {
                // Pick kW as the screen's Done would: through the server's configure path, with the real panel wrench.
                server.execute(() -> {
                    var player = server.getPlayerList().getPlayer(uuid);
                    player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(BwrItems.PANEL_WRENCH.get()));
                    var edit = new net.minecraft.nbt.CompoundTag();
                    var config = new net.minecraft.nbt.CompoundTag();
                    config.putString("PowerUnits", "kW");
                    edit.put("Config", config);
                    if (!PanelOps.configure(player, OWNERS[5], IDS[5], edit)) throw new IllegalStateException("configure refused");
                });
            }
            if (shown == 30) {
                var be = (PanelBlockEntity) mc.level.getBlockEntity(OWNERS[index]);
                mc.setScreen(new dev.bwr.mod.panel.client.ConfigScreen(be, be.component(IDS[index])));
            }
            if (shown == 45 && mc.screen != null) {
                for (int i = 0; i < 6; i++) mc.screen.mouseScrolled(10, 10, 0, -3); // to the bottom rows
            }
            if (shown < 70) return;
            if (!(mc.screen instanceof dev.bwr.mod.panel.client.ConfigScreen)) throw new AssertionError("The wrench screen is not open");
        } else if (stage == 3 && shown < 90 || stage == 5 && shown < 100 || stage != 3 && stage != 5 && shown < 60) return;
        if (stage == 3 && !(mc.screen instanceof NetworkingScreen)) throw new AssertionError("The Networking screen is not open");
        if (stage == 0 && NetworkLines.debugLines() == 0) throw new AssertionError("No line was drawn from the hovered gauge: hit="
                + mc.hitResult + " hover=" + dev.bwr.mod.panel.client.NetworkHover.current(mc) + " main=" + mc.player.getMainHandItem()
                + " pos=" + mc.player.position() + " yaw=" + mc.player.getYRot() + " pitch=" + mc.player.getXRot()
                + " aim=" + aim + " screen=" + mc.screen);
        try {
            var directory = mc.gameDirectory.toPath().resolve("networking-client-check");
            Files.createDirectories(directory);
            try (var shot = CheckShots.saveKeeping(mc, directory.resolve(NAMES[stage] + ".png"))) {
                int cyan = 0, orange = 0, yellow = 0, white = 0;
                for (int y = 0; y < shot.getHeight(); y++) for (int x = 0; x < shot.getWidth(); x++) {
                    int abgr = shot.getPixelRGBA(x, y);
                    int r = abgr & 0xFF, g = abgr >> 8 & 0xFF, b = abgr >> 16 & 0xFF;
                    if (Math.abs(r - 77) < 30 && Math.abs(g - 230) < 25 && b > 225) cyan++;
                    if (r > 235 && Math.abs(g - 153) < 25 && Math.abs(b - 51) < 30) orange++;
                    if (r > 235 && Math.abs(g - 217) < 20 && b < 60) yellow++;
                    if (r > 250 && g > 250 && b > 250) white++;
                }
                LogUtils.getLogger().info("NETWORKING CLIENT CHECK {}: cyan={} orange={} yellow={} white={}", NAMES[stage], cyan, orange, yellow, white);
                if (stage == 0 && cyan < 60) throw new AssertionError("The hover line to the valve is not visible: cyan=" + cyan);
                if (stage == 1 && (cyan < 60 || orange < 30)) throw new AssertionError("The fan-out is not visible: cyan=" + cyan + " orange=" + orange);
                if (stage == 2 && (yellow < 25 || white < 100 || NetworkLines.debugGlows() == 0)) throw new AssertionError("The selected valve is not yellow or the linkable one not white: yellow=" + yellow + " white=" + white);
            }
            if (stage == 5) {
                var level = mc.level;
                int named = 0;
                for (int i = 0; i < 5; i++) {
                    var be = (PanelBlockEntity) level.getBlockEntity(OWNERS[i]);
                    var component = be.component(IDS[i]);
                    boolean linkedToA = i == 0 || i == 1 || i == 3;
                    if (linkedToA && !component.caption().equals("RHR-1"))
                        throw new AssertionError("Component " + i + " was not named: '" + component.caption() + "'");
                    if (!linkedToA && !component.caption().isEmpty())
                        throw new AssertionError("Component " + i + " must not be named: '" + component.caption() + "'");
                    if (linkedToA) named++;
                }
                LogUtils.getLogger().info("NETWORKING CLIENT CHECK name push: {} components carry RHR-1 on the client", named);
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Networking screenshot failed", ex);
        }
        LogUtils.getLogger().info("NETWORKING CLIENT CHECK: {}", NAMES[stage]);
        captures++;
        requested = false;
        stage++;
    }

    private static Vec3 faceCenter(ServerLevel level, int index) {
        var be = (PanelBlockEntity) level.getBlockEntity(OWNERS[index]);
        return NetworkLines.faceCenter(be, be.component(IDS[index]));
    }

    private static BlockPos blockAt(int col, int row) {
        return BOARD.relative(Direction.WEST, col).below(row);
    }

    private static MainSteamIsolationValveBlockEntity valve(ServerLevel level, BlockPos root) {
        var block = BwrBlocks.MSIV.get();
        var state = block.placementState().setValue(MainSteamIsolationValveBlock.FACING, Direction.NORTH);
        level.setBlock(root, state, 3);
        block.setPlacedBy(level, root, state, null, new ItemStack(block));
        var valve = (MainSteamIsolationValveBlockEntity) level.getBlockEntity(root);
        if (valve == null) throw new AssertionError("Showcase valve did not place");
        valve.energy().receiveEnergy(20_000, false);
        valve.setDemandOpen(true);
        for (int i = 0; i < 90; i++) valve.tickValve(.05);
        return valve;
    }

    private static void build(ServerLevel level) {
        for (BlockPos pos : BlockPos.betweenClosed(new BlockPos(7088, 121, 7088), new BlockPos(7112, 121, 7106)))
            level.setBlock(pos, Blocks.SMOOTH_STONE.defaultBlockState(), 2);
        for (int row = 0; row < 2; row++) for (int col = 0; col < 4; col++)
            level.setBlock(blockAt(col, row), BwrBlocks.CONTROL_PANEL_VERTICAL.get().defaultBlockState()
                    .setValue(PanelBlock.FACING, Direction.NORTH), 3);
        var a = valve(level, VALVE_A);
        valve(level, VALVE_B);
        valve(level, VALVE_C);
        level.setBlock(METER, BwrBlocks.FE_METER.get().defaultBlockState(), 3);
        MachineNames.setName(level.getBlockEntity(VALVE_B), "FWP-2");

        // 0: a dial on valve A, 1: a dial on valve A, 2: a segment display on valve B, 3: a button that commands valve A,
        // 4: a dial wired through the CC network (never named).
        PanelComponent dialA = PanelOps.placeDirect(level, blockAt(0, 0), 2, 2, ComponentTypes.DIAL_GAUGE, Footprint.of(12, 12));
        dialA.setSource(0, new PlantSource(VALVE_A, "position", 100, 0));
        PanelComponent dialB = PanelOps.placeDirect(level, blockAt(1, 0), 2, 2, ComponentTypes.DIAL_GAUGE, Footprint.of(12, 12));
        dialB.setSource(0, new PlantSource(VALVE_A, "energy_fe", 0.0001, 0));
        PanelComponent digits = PanelOps.placeDirect(level, blockAt(2, 0), 0, 4, ComponentTypes.SEVEN_SEGMENT_DISPLAY, Footprint.of(20, 8));
        digits.setSource(0, new PlantSource(VALVE_B, "position", 100, 0));
        PanelComponent button = PanelOps.placeDirect(level, blockAt(3, 0), 6, 4, ComponentTypes.PUSHBUTTON, Footprint.of(6, 6));
        button.addSink(0, new PlantSink(VALVE_A, "open", 1, PlantSink.Mode.MOMENTARY, 1));
        PanelComponent cc = PanelOps.placeDirect(level, blockAt(0, 1), 2, 2, ComponentTypes.DIAL_GAUGE, Footprint.of(12, 12));
        cc.setSource(0, new CcSource());
        PanelComponent powerDial = PanelOps.placeDirect(level, blockAt(1, 1), 2, 2, ComponentTypes.DIAL_GAUGE, Footprint.of(12, 12));
        powerDial.setSource(0, new PlantSource(METER, "fe_per_tick_now", 1, 0));
        PanelComponent[] all = {dialA, dialB, digits, button, cc, powerDial};
        BlockPos[] owners = {blockAt(0, 0), blockAt(1, 0), blockAt(2, 0), blockAt(3, 0), blockAt(0, 1), blockAt(1, 1)};
        for (int i = 0; i < all.length; i++) {
            IDS[i] = all[i].id();
            OWNERS[i] = owners[i];
        }
        for (int row = 0; row < 2; row++) for (int col = 0; col < 4; col++) {
            if (level.getBlockEntity(blockAt(col, row)) instanceof PanelBlockEntity be) {
                PanelSampler.track(be);
                be.sampleLive();
                be.syncFull();
            }
        }
        if (a == null) throw new AssertionError("valve A missing");
    }
}
