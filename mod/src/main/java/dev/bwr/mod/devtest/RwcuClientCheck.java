package dev.bwr.mod.devtest;

import com.mojang.logging.LogUtils;
import dev.bwr.core.chemistry.CoolantChemistry;
import dev.bwr.mod.BwrMod;
import dev.bwr.mod.gui.PumpControlMenu;
import dev.bwr.mod.gui.ReactorPanelMenu;
import dev.bwr.mod.gui.RwcuUnitMenu;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.nio.file.Files;

/**
 * Disposable real-client check of the reactor water cleanup skid (run with {@code -PbwrRwcuClientCheck} through run-mc.ps1): the
 * pump, regenerative exchanger, cooler and demineraliser piped to the vessel and to its feedwater port, the pump's screen with the
 * loop running, the demineraliser's screen with a loaded bed, and the reactor screen's INFO tab with its water chemistry section.
 * Pictures go to {@code run/turbineModelCheck/rwcu-check/}. Not shipped: the devtest package is stripped from the release jar.
 */
@EventBusSubscriber(modid = BwrMod.MOD_ID, value = Dist.CLIENT)
public final class RwcuClientCheck {
    private static final int X0 = 200;
    private static final String[] NAMES = {"skid", "skid-closeup", "pump-screen", "demineralizer-screen", "info-tab-chemistry"};
    private static final boolean[] SCREEN = {false, false, true, true, true};

    private record Shot(Vec3 stand, Vec3 target) {}

    private static Vec3 v(double x, double y, double z) { return new Vec3(x, y, z); }

    // The vessel's interior is 9 x 9 x 12 from (200,190,200); the skid runs north (z falls) at y 196 (outlet row 6).
    private static final Shot[] SHOTS = {
            new Shot(v(214.0, 203.0, 187.5), v(205.5, 196.5, 193.0)),
            new Shot(v(209.5, 199.5, 195.5), v(205.5, 196.5, 192.5)),
            new Shot(v(209.0, 197.5, 195.5), v(205.5, 196.5, 195.5)),
            new Shot(v(209.0, 197.5, 191.0), v(205.5, 196.5, 191.0)),
            new Shot(v(192.0, 195.0, 204.5), v(199.5, 193.5, 204.5))};

    private static boolean started, requested, standing, built;
    private static volatile Throwable failure;
    private static volatile Vec3 aim;
    private static int age, joined, stage, shown;
    private static RwcuTests.Skid skid;

    private RwcuClientCheck() {}

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post ignored) {
        if (!Boolean.getBoolean("bwr.rwcuClientCheck")) return;
        try {
            step();
        } catch (Throwable t) {
            LogUtils.getLogger().error("RWCU CLIENT CHECK FAILED at stage {}", stage < NAMES.length ? NAMES[stage] : "end", t);
            Minecraft.getInstance().stop();
        }
    }

    private static void step() throws Exception {
        Minecraft mc = Minecraft.getInstance();
        if (failure != null) throw new IllegalStateException("server side of the check failed", failure);
        if (++age > 12000) throw new IllegalStateException("timed out at stage " + stage);
        if (!started && mc.screen != null && mc.getOverlay() == null) {
            started = true;
            mc.options.pauseOnLostFocus = false;
            mc.options.cloudStatus().set(net.minecraft.client.CloudStatus.OFF);
            mc.options.renderDistance().set(10);
            mc.options.fov().set(70);
            mc.options.guiScale().set(3);
            mc.createWorldOpenFlows().createFreshLevel("bwr-rwcu-check-" + System.currentTimeMillis(),
                    new LevelSettings("RWCU check", GameType.CREATIVE, false, Difficulty.PEACEFUL, true, new GameRules(), WorldDataConfiguration.DEFAULT),
                    new WorldOptions(0, false, false),
                    r -> r.registryOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).createWorldDimensions(), mc.screen);
            return;
        }
        if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null || ++joined < 40) return;
        var server = mc.getSingleplayerServer();
        var uuid = mc.player.getUUID();
        if (!requested) {
            if (stage == NAMES.length) {
                LogUtils.getLogger().info("RWCU CLIENT CHECK PASS: {} pictures", stage);
                mc.stop();
                return;
            }
            requested = true;
            shown = 0;
            standing = false;
            int current = stage;
            if (!SCREEN[current]) mc.setScreen(null);
            mc.options.hideGui = !SCREEN[current];
            mc.options.guiScale().set(3);
            server.execute(() -> {
                try {
                    ServerPlayer player = server.getPlayerList().getPlayer(uuid);
                    ServerLevel level = player.serverLevel();
                    if (!built) {
                        skid = RwcuTests.skid(level, X0, false, true);
                        skid.pump.energy().receiveEnergy(1_000_000, false);
                        RwcuTests.openForCheck(level, skid);
                        built = true;
                    }
                    act(player, current);
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
        if (!standing || aim == null) return;
        Shot shot = SHOTS[stage];
        mc.player.setPos(shot.stand.x, shot.stand.y, shot.stand.z);
        mc.player.setDeltaMovement(Vec3.ZERO);
        mc.player.setYRot((float) aim.x);
        mc.player.setXRot((float) aim.y);
        mc.player.yRotO = (float) aim.x;
        mc.player.xRotO = (float) aim.y;
        if (mc.player.position().distanceToSqr(shot.stand) > 0.25) return;
        if (SCREEN[stage]) {
            boolean ready = switch (stage) {
                case 2 -> mc.screen instanceof dev.bwr.mod.gui.client.PumpControlScreen && mc.player.containerMenu instanceof PumpControlMenu m && m.present && m.actual > 0.2;
                case 3 -> mc.screen instanceof dev.bwr.mod.gui.client.RwcuUnitScreen && mc.player.containerMenu instanceof RwcuUnitMenu m && !m.lines.isEmpty();
                default -> mc.screen instanceof dev.bwr.mod.gui.client.ReactorPanelScreen && mc.player.containerMenu instanceof ReactorPanelMenu m && m.formed && m.crudG > 0;
            };
            if (!ready) return;
            if (stage == 4 && mc.screen instanceof dev.bwr.mod.gui.client.ReactorPanelScreen screen) {
                var w = mc.getWindow();
                double midX = w.getGuiScaledWidth() / 2.0, midY = w.getGuiScaledHeight() / 2.0;
                if (shown == 0) {
                    setInfo(screen);
                    mouseTo(mc, midX, midY);
                } else if (shown == 3) {
                    screen.mouseScrolled(midX, midY, 0, -Integer.getInteger("bwr.rwcuScroll", 20));
                } else if (shown == 8) {
                    mouseTo(mc, 2, 2);
                }
            }
        }
        if (++shown < 110) return;
        var dir = mc.gameDirectory.toPath().resolve("rwcu-check");
        Files.createDirectories(dir);
        CheckShots.save(mc, dir.resolve(NAMES[stage] + ".png"));
        LogUtils.getLogger().info("RWCU CLIENT CHECK: captured {}", NAMES[stage]);
        if (SCREEN[stage]) mc.player.closeContainer();
        requested = false;
        stage++;
    }

    private static void act(ServerPlayer player, int stage) {
        var s = skid;
        switch (stage) {
            case 0, 1 -> {
                // running, with the vessel water dirty: the picture of a working skid
                s.pump.setRunning(true);
            }
            case 2 -> PumpControlMenu.open(player, s.pumpPos, s.pumpPos);
            case 3 -> {
                var c = s.core().getChemistry();
                c.fromArray(new double[]{CoolantChemistry.ionicGramsFor(2.2, s.core().getLiquidMassKg()), 6.0, 6.0 * 0.4e12, 0.0004, 0.0001});
                s.demin.bed().fromArray(new double[]{1, 62.5, 91.0, 2.5e13, 0});
                RwcuUnitMenu.open(player, s.demin.getBlockPos());
            }
            case 4 -> ReactorPanelMenu.open(player, s.be);
            default -> {
            }
        }
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
}
