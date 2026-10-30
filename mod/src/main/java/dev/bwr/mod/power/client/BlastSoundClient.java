package dev.bwr.mod.power.client;

import dev.bwr.mod.BwrMod;
import dev.bwr.mod.power.BlastSoundPayload;
import dev.bwr.mod.power.BlastSounds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * The client half of the turbine blast sounds (TURBINE-MAINTENANCE.md, "Sounds"): holds each blast the server sent until its travel time
 * has passed (client ticks, so a paused game waits), then starts its clear, distant and muffled variants at the gains it was given. Whatever
 * is waiting is dropped when the world or dimension changes.
 */
@EventBusSubscriber(modid = BwrMod.MOD_ID, value = Dist.CLIENT)
public final class BlastSoundClient {
    private BlastSoundClient() {}

    /** A variant under this gain is not started. */
    public static final double AUDIBLE = 0.003;
    private static final int MAX_WAITING = 64;

    private record Waiting(long due, BlastSoundPayload payload) {}

    private static final List<Waiting> WAITING = new ArrayList<>();
    private static long now;
    private static ClientLevel lastLevel;
    /** The instances started, for checks. */
    private static final List<BlastSoundInstance> STARTED = new ArrayList<>();

    /** A blast from the server. */
    public static void accept(BlastSoundPayload p) {
        if (!finite(p.x()) || !finite(p.y()) || !finite(p.z())) return;
        if (WAITING.size() >= MAX_WAITING) WAITING.remove(0);
        WAITING.add(new Waiting(now + Mth.clamp(p.delayTicks(), 0, BlastSounds.MAX_DELAY_TICKS), p));
    }

    private static boolean finite(double v) { return Double.isFinite(v) && Math.abs(v) < 3.0e7; }

    private static float gain(float v) { return Float.isFinite(v) ? Mth.clamp(v, 0f, 1f) : 0f; }

    /** One client tick. Public so a check can drive it. */
    public static void advance() {
        now++;
        for (var it = WAITING.iterator(); it.hasNext(); ) {
            Waiting w = it.next();
            if (w.due > now) continue;
            it.remove();
            start(w.payload);
        }
    }

    private static void start(BlastSoundPayload p) {
        BlastSounds.Kind[] kinds = BlastSounds.Kind.values();
        if (p.kind() < 0 || p.kind() >= kinds.length) return;
        BlastSounds.Kind kind = kinds[p.kind()];
        float pitch = Float.isFinite(p.pitch()) ? Mth.clamp(p.pitch(), 0.5f, 2.0f) : 1.0f;
        Vec3 at = new Vec3(p.x(), p.y(), p.z());
        float[] gains = {gain(p.clear()), gain(p.distant()), gain(p.muffled())};
        for (BlastSounds.Variant v : BlastSounds.Variant.values()) {
            if (gains[v.ordinal()] < AUDIBLE) continue;
            var instance = new BlastSoundInstance(kind.event(v), gains[v.ordinal()], pitch, at);
            STARTED.add(instance);
            if (STARTED.size() > 64) STARTED.remove(0);
            Minecraft.getInstance().getSoundManager().play(instance);
        }
    }

    /** Blasts still waiting for their sound to arrive (checks). */
    public static int waiting() { return WAITING.size(); }

    /** The instances started so far (checks). */
    public static List<BlastSoundInstance> started() { return List.copyOf(STARTED); }

    /** Forget everything (disconnect, checks). */
    public static void reset() {
        WAITING.clear();
        STARTED.clear();
        now = 0;
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.isPaused()) return;
        if (mc.level != lastLevel) {
            lastLevel = mc.level;
            reset();
        }
        advance();
    }

    @SubscribeEvent
    public static void loggedOut(ClientPlayerNetworkEvent.LoggingOut event) {
        reset();
        lastLevel = null;
    }
}
