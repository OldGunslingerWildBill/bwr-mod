package dev.bwr.mod.rumble.client;

import com.mojang.logging.LogUtils;
import dev.bwr.core.rumble.RumbleLaw;
import dev.bwr.core.rumble.RumbleShake;
import dev.bwr.mod.BwrMod;
import dev.bwr.mod.client.VisualEffectsConfig;
import dev.bwr.mod.rumble.RumblePayload;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;

import java.lang.reflect.Method;

/**
 * The client half of the rumble (RUMBLE.md): keeps the state the server last sent, smooths it, plays the two rumble loops and shakes
 * the rendered camera. Nothing here reaches the server or the physics, and nothing moves the player's own look direction: the shake is
 * added to the camera that draws the frame, after the player's aim is settled, and picking and aiming use the player's real
 * rotation.
 */
@EventBusSubscriber(modid = BwrMod.MOD_ID, value = Dist.CLIENT)
public final class RumbleClient {
    private RumbleClient() {}

    /** Ticks without a packet after which the client lets the rumble die (the server repeats the state every 40 ticks). */
    public static final int STALE_TICKS = 140;
    /** Smoothed intensity under which the sound stops. */
    public static final double SILENT = 0.5 * RumbleLaw.PERCEPTION;

    private static float targetPool, targetTurbine, dirX, dirY, dirZ, distance;
    private static double pool, turbine, prevPool, prevTurbine;
    private static int stale;
    private static net.minecraft.client.multiplayer.ClientLevel lastLevel;
    private static RumbleSoundInstance poolSound, turbineSound;
    private static final RumbleShake.Offsets OFFSETS = new RumbleShake.Offsets();
    private static Method setPosition;
    private static boolean positionTried;

    // ---- state

    /** A packet from the server. */
    public static void accept(RumblePayload payload) {
        targetPool = finite(payload.pool());
        targetTurbine = finite(payload.turbine());
        dirX = finite(payload.dx());
        dirY = finite(payload.dy());
        dirZ = finite(payload.dz());
        distance = finite(payload.distance());
        stale = 0;
    }

    private static float finite(float v) { return Float.isFinite(v) && v > -1e9f && v < 1e9f ? v : 0f; }

    /** Smoothed intensity of the ground-borne loop. */
    public static double pool() { return pool; }

    /** Smoothed intensity of the machine loop. */
    public static double turbine() { return turbine; }

    /** Unit direction to the strongest source, world axes. */
    public static Vec3 direction() { return new Vec3(dirX, dirY, dirZ); }

    /** Distance to the strongest source, blocks. */
    public static double distance() { return distance; }

    /** Forget everything (disconnect, tests). */
    public static void reset() {
        targetPool = targetTurbine = dirX = dirY = dirZ = distance = 0;
        pool = turbine = prevPool = prevTurbine = 0;
        stale = 0;
        stopSounds();
    }

    private static double approach(double cur, double target) {
        double k = target > cur ? 0.25 : 0.07;
        double next = cur + (target - cur) * k;
        return Math.abs(next - target) < 1e-4 ? target : next;
    }

    /** One client tick of smoothing and sound upkeep. Public so a check can drive it. */
    public static void advance() {
        prevPool = pool;
        prevTurbine = turbine;
        if (++stale > STALE_TICKS) {
            targetPool = 0;
            targetTurbine = 0;
        }
        pool = approach(pool, targetPool);
        turbine = approach(turbine, targetTurbine);
        poolSound = keep(poolSound, pool, RumbleSoundInstance.Kind.POOL);
        turbineSound = keep(turbineSound, turbine, RumbleSoundInstance.Kind.TURBINE);
    }

    private static RumbleSoundInstance keep(RumbleSoundInstance sound, double intensity, RumbleSoundInstance.Kind kind) {
        if (sound != null && sound.isStopped()) sound = null;
        if (intensity >= SILENT) {
            if (sound == null && VisualEffectsConfig.rumbleVolume() > 0) {
                sound = new RumbleSoundInstance(kind);
                Minecraft.getInstance().getSoundManager().play(sound);
            }
        } else if (sound != null) {
            sound.release();
            sound = null;
        }
        return sound;
    }

    private static void stopSounds() {
        if (poolSound != null) poolSound.release();
        if (turbineSound != null) turbineSound.release();
        poolSound = null;
        turbineSound = null;
    }

    /** The loop that is playing for a kind, or null (checks). */
    public static RumbleSoundInstance sound(RumbleSoundInstance.Kind kind) {
        return kind == RumbleSoundInstance.Kind.POOL ? poolSound : turbineSound;
    }

    static double intensity(RumbleSoundInstance.Kind kind) {
        return kind == RumbleSoundInstance.Kind.POOL ? pool : turbine;
    }

    // ---- camera

    /**
     * The camera offsets for a time, seconds, with the screen shake option applied. {@code blend} places the smoothed state between the
     * last tick and this one. Pure of the world; the check calls it directly.
     */
    public static RumbleShake.Offsets offsets(double seconds, float blend) {
        double scale = VisualEffectsConfig.screenShake();
        OFFSETS.clear();
        if (scale <= 0) return OFFSETS;
        double p = prevPool + (pool - prevPool) * blend;
        double t = prevTurbine + (turbine - prevTurbine) * blend;
        double ground = p >= SILENT ? RumbleLaw.shakeAmplitude(p) : 0;
        double machine = t >= SILENT ? RumbleLaw.shakeAmplitude(t) : 0;
        if (ground <= 0 && machine <= 0) return OFFSETS;
        RumbleShake.compute(ground * scale, machine * scale, RumbleShake.nearness(distance), seconds, OFFSETS);
        return OFFSETS;
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.isPaused()) return;
        if (mc.level != lastLevel) {
            // A new world or a new dimension: whatever the old one was shaking is not this one. The server sends this level's state.
            lastLevel = mc.level;
            reset();
        }
        if (targetPool == 0 && targetTurbine == 0 && pool == 0 && turbine == 0 && poolSound == null && turbineSound == null) return;
        advance();
    }

    @SubscribeEvent
    public static void loggedOut(ClientPlayerNetworkEvent.LoggingOut event) {
        reset();
        lastLevel = null;
    }

    @SubscribeEvent
    public static void camera(ViewportEvent.ComputeCameraAngles event) {
        if (pool <= 0 && turbine <= 0) return;
        Minecraft mc = Minecraft.getInstance();
        // Game time in a world (so the shake stands still when the game is paused); the wall clock only where there is no world (the check).
        double seconds = mc.level != null ? (mc.level.getGameTime() + event.getPartialTick()) / 20.0 : net.minecraft.Util.getMillis() / 1000.0;
        var o = offsets(seconds, (float) event.getPartialTick());
        if (o.maxAngleDeg() == 0 && o.maxMoveBlocks() == 0) return;
        event.setYaw((float) (event.getYaw() + o.yawDeg));
        event.setPitch((float) Math.max(-90, Math.min(90, event.getPitch() + o.pitchDeg)));
        event.setRoll((float) (event.getRoll() + o.rollDeg));
        move(event.getCamera(), o.x, o.y, o.z);
    }

    /** Shifts the rendered camera. Camera#setPosition is protected; if it cannot be reached the angles alone shake the view. */
    private static void move(Camera camera, double x, double y, double z) {
        if (!positionTried) {
            positionTried = true;
            try {
                setPosition = Camera.class.getDeclaredMethod("setPosition", Vec3.class);
                setPosition.setAccessible(true);
            } catch (ReflectiveOperationException | RuntimeException e) {
                setPosition = null;
                LogUtils.getLogger().info("Rumble: the camera position cannot be moved ({}); the view shakes by angle only", e.toString());
            }
        }
        if (setPosition == null) return;
        try {
            setPosition.invoke(camera, camera.getPosition().add(x, y, z));
        } catch (ReflectiveOperationException | RuntimeException e) {
            setPosition = null;
        }
    }

    /** Whether the camera position can be moved (the check reports it). */
    public static boolean canMoveCamera() {
        if (!positionTried) move(Minecraft.getInstance().gameRenderer.getMainCamera(), 0, 0, 0);
        return setPosition != null;
    }
}
