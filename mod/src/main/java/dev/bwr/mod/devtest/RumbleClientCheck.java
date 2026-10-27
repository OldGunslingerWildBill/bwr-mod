package dev.bwr.mod.devtest;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import dev.bwr.core.rumble.RumbleLaw;
import dev.bwr.core.rumble.RumbleShake;
import dev.bwr.mod.BwrMod;
import dev.bwr.mod.client.VisualEffectsConfig;
import dev.bwr.mod.rumble.RumblePayload;
import dev.bwr.mod.rumble.client.RumbleClient;
import dev.bwr.mod.rumble.client.RumbleSoundInstance;
import net.minecraft.client.Minecraft;
import net.minecraft.client.sounds.JOrbisAudioStream;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Opt-in client check of the rumble ({@code -Dbwr.rumbleClientCheck=true}, run config {@code rumbleClientCheck}). Needs no world:
 * <ol>
 *   <li>both loops decode with the game's own Vorbis decoder, are mono 48 kHz, loud enough, deep (most of the energy under 70 Hz,
 *       none above 1.2 kHz) and loop without a step;</li>
 *   <li>the camera: a strong state shakes by degrees and tenths of a block, never past the limits, smoothly between frames; the camera
 *       event moves yaw, pitch and roll within those limits; {@code screenShake} 0 gives exactly nothing;</li>
 *   <li>the sound: the loops start through the game's sound engine when a state arrives, follow its volume, are not started with
 *       {@code rumbleVolume} 0, and stop when the state is gone.</li>
 * </ol>
 */
@EventBusSubscriber(modid = BwrMod.MOD_ID, value = Dist.CLIENT)
public final class RumbleClientCheck {
    private static final int SAMPLE_RATE = 48_000;
    private static boolean started, finished;
    private static int age, stage, wait;
    private static final JsonObject REPORT = new JsonObject();
    private static RumbleSoundInstance seenPool;

    private RumbleClientCheck() {}

    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post ignored) throws Exception {
        if (!Boolean.getBoolean("bwr.rumbleClientCheck") || finished) return;
        Minecraft mc = Minecraft.getInstance();
        if (++age > 4000) throw new AssertionError("Rumble client check timed out in stage " + stage);
        if (mc.screen == null || mc.getOverlay() != null) return;
        if (wait > 0) {
            wait--;
            return;
        }
        var output = mc.gameDirectory.toPath().resolve("rumble-check");
        Files.createDirectories(output);
        try {
            if (!started) {
                started = true;
                require(VisualEffectsConfig.SPEC.isLoaded(), "the client config is not loaded");
                REPORT.addProperty("pool_ogg", analyse("rumble_pool"));
                REPORT.addProperty("turbine_ogg", analyse("rumble_turbine"));
                camera(mc);
                // Sound stages.
                RumbleClient.reset();
                VisualEffectsConfig.RUMBLE_VOLUME.set(0.0);
                RumbleClient.accept(new RumblePayload(0.4f, 0, 1, 0, 0, 6));
                stage = 1;
                wait = 40;
                return;
            }
            switch (stage) {
                case 1 -> {
                    require(RumbleClient.sound(RumbleSoundInstance.Kind.POOL) == null, "a loop was started with rumbleVolume 0");
                    VisualEffectsConfig.RUMBLE_VOLUME.set(1.0);
                    RumbleClient.accept(new RumblePayload(0.4f, 0, 1, 0, 0, 6));
                    stage = 2;
                    wait = 40;
                }
                case 2 -> {
                    var pool = RumbleClient.sound(RumbleSoundInstance.Kind.POOL);
                    require(pool != null, "no pool loop started for a strong state (smoothed " + RumbleClient.pool() + ")");
                    seenPool = pool;
                    require(mc.getSoundManager().isActive(pool), "the pool loop is not active in the sound engine");
                    require(pool.currentVolume() > 0.3f && pool.currentVolume() <= 1.0f, "pool loop volume " + pool.currentVolume());
                    require(pool.getPitch() >= 0.7f && pool.getPitch() <= 1.0f, "pool loop pitch " + pool.getPitch());
                    require(pool.isLooping() && pool.isRelative(), "pool loop must loop and be listener-relative");
                    REPORT.addProperty("pool_volume_strong", pool.currentVolume());
                    RumbleClient.accept(new RumblePayload(0.4f, 0.15f, 1, 0, 0, 6));
                    stage = 3;
                    wait = 40;
                }
                case 3 -> {
                    var turbine = RumbleClient.sound(RumbleSoundInstance.Kind.TURBINE);
                    require(turbine != null && mc.getSoundManager().isActive(turbine), "no active turbine loop");
                    REPORT.addProperty("turbine_volume", turbine.currentVolume());
                    // A faint, far state is quieter and deeper.
                    RumbleClient.accept(new RumblePayload(0.021f, 0, 1, 0, 0, 8000));
                    stage = 4;
                    wait = 120;
                }
                case 4 -> {
                    var pool = RumbleClient.sound(RumbleSoundInstance.Kind.POOL);
                    require(pool != null, "the pool loop stopped for a faint state");
                    require(pool.currentVolume() < 0.3f && pool.getPitch() < 0.8f, "a far faint rumble should be quiet and deep: " + pool.currentVolume() + " " + pool.getPitch());
                    REPORT.addProperty("pool_volume_at_perception", pool.currentVolume());
                    RumbleClient.accept(RumblePayload.NONE);
                    stage = 5;
                    wait = 160;
                }
                case 5 -> {
                    require(RumbleClient.sound(RumbleSoundInstance.Kind.POOL) == null && RumbleClient.sound(RumbleSoundInstance.Kind.TURBINE) == null,
                            "the loops did not stop when the state went");
                    require(seenPool != null && !mc.getSoundManager().isActive(seenPool), "the pool loop is still active in the sound engine");
                    require(RumbleClient.pool() == 0 || RumbleClient.pool() < RumbleClient.SILENT, "the smoothed intensity did not fall");
                    REPORT.addProperty("status", "PASS");
                    Files.writeString(output.resolve("results.json"), new GsonBuilder().setPrettyPrinting().create().toJson(REPORT), StandardCharsets.UTF_8);
                    LogUtils.getLogger().info("RUMBLE CLIENT COMPLETE: {}", REPORT);
                    finished = true;
                    mc.stop();
                }
                default -> throw new AssertionError("stage " + stage);
            }
        } catch (Exception | AssertionError failure) {
            REPORT.addProperty("status", "FAIL");
            REPORT.addProperty("failure", failure.toString());
            Files.writeString(output.resolve("results.json"), new GsonBuilder().setPrettyPrinting().create().toJson(REPORT), StandardCharsets.UTF_8);
            finished = true;
            throw failure;
        }
    }

    /** Decode a loop with the game's decoder and measure it. Returns a one-line summary. */
    private static String analyse(String name) throws Exception {
        var mc = Minecraft.getInstance();
        var id = BwrMod.id("sounds/" + name + ".ogg");
        List<Float> samples = new ArrayList<>();
        try (var in = mc.getResourceManager().open(id); var decoded = new JOrbisAudioStream(in)) {
            var format = decoded.getFormat();
            require(format.getChannels() == 1 && format.getSampleRate() == SAMPLE_RATE, name + " format " + format);
            int chunks = 0;
            while (decoded.readChunk(s -> { if (samples.size() < SAMPLE_RATE * 20) samples.add(s); })) require(++chunks < 100_000, "unbounded decode");
        }
        int n = samples.size();
        require(n >= SAMPLE_RATE * 3 && n <= SAMPLE_RATE * 8, name + " is " + n + " frames long");
        double sum = 0, peak = 0;
        for (float s : samples) {
            require(Float.isFinite(s), name + " has a non-finite sample");
            sum += (double) s * s;
            peak = Math.max(peak, Math.abs(s));
        }
        double rms = Math.sqrt(sum / n);
        require(rms > 0.08 && peak <= 1.0, name + " rms " + rms + " peak " + peak);
        require(Math.abs(samples.get(0) - samples.get(n - 1)) < 0.2, name + " does not loop cleanly: " + samples.get(0) + " vs " + samples.get(n - 1));
        // Energy by band over one second from the middle, by direct sums (about 5 Hz resolution up to 1.2 kHz).
        int start = n / 2 - SAMPLE_RATE / 2, len = SAMPLE_RATE;
        double deep = 0, mid = 0, top = 0;
        for (int f = 10; f <= 1200; f += 2) {
            double re = 0, im = 0, w = 2 * Math.PI * f / SAMPLE_RATE;
            for (int i = 0; i < len; i += 2) {
                double v = samples.get(start + i);
                re += v * Math.cos(w * i);
                im += v * Math.sin(w * i);
            }
            double e = re * re + im * im;
            if (f < 70) deep += e; else if (f < 400) mid += e; else top += e;
        }
        double total = deep + mid + top;
        require(deep / total > 0.55 && mid / total > 0.02 && top / total < 0.05,
                name + " spectrum: under 70 Hz " + deep / total + ", 70-400 Hz " + mid / total + ", over 400 Hz " + top / total);
        return String.format("%d frames, rms %.3f, peak %.3f, <70Hz %.2f, 70-400Hz %.2f, >400Hz %.3f", n, rms, peak, deep / total, mid / total, top / total);
    }

    private static void camera(Minecraft mc) {
        RumbleClient.reset();
        double saved = VisualEffectsConfig.SCREEN_SHAKE.get();
        try {
            VisualEffectsConfig.SCREEN_SHAKE.set(1.0);
            RumbleClient.accept(new RumblePayload(0.5f, 0, 1, 0, 0, 5));
            for (int i = 0; i < 80; i++) RumbleClient.advance();
            double maxAngle = 0, maxMove = 0, maxStep = 0, last = 0;
            for (int frame = 0; frame < 60 * 20; frame++) {
                var o = RumbleClient.offsets(frame / 60.0, 1f);
                maxAngle = Math.max(maxAngle, o.maxAngleDeg());
                maxMove = Math.max(maxMove, o.maxMoveBlocks());
                if (frame > 0) maxStep = Math.max(maxStep, Math.abs(o.rollDeg - last));
                last = o.rollDeg;
            }
            REPORT.addProperty("camera_max_angle_deg", maxAngle);
            REPORT.addProperty("camera_max_move_blocks", maxMove);
            REPORT.addProperty("camera_max_roll_step_deg_per_frame", maxStep);
            require(maxAngle > 3.5 && maxAngle <= RumbleShake.ROLL_DEG * RumbleShake.MAX_TOTAL, "strong shake angle " + maxAngle);
            require(maxMove > 0.05 && maxMove <= RumbleShake.BOB_Y * RumbleShake.MAX_TOTAL, "strong shake movement " + maxMove);
            require(maxStep < 2.5, "the view jumps " + maxStep + " degrees between frames");

            // The real camera event: yaw, pitch and roll are offset, within the limits, and nothing else is touched.
            var camera = mc.gameRenderer.getMainCamera();
            double worst = 0;
            boolean moved = false;
            for (int i = 0; i < 120; i++) {
                var event = new ViewportEvent.ComputeCameraAngles(camera, 0.5 + i * 0.37, 10f, 20f, 0f);
                NeoForge.EVENT_BUS.post(event);
                moved |= event.getYaw() != 10f || event.getPitch() != 20f || event.getRoll() != 0f;
                worst = Math.max(worst, Math.max(Math.abs(event.getYaw() - 10f), Math.max(Math.abs(event.getPitch() - 20f), Math.abs(event.getRoll()))));
            }
            require(moved && worst > 0.2 && worst <= RumbleShake.ROLL_DEG * RumbleShake.MAX_TOTAL + 1e-3, "camera event offset " + worst);
            REPORT.addProperty("camera_event_max_offset_deg", worst);
            REPORT.addProperty("camera_position_can_move", RumbleClient.canMoveCamera());

            // A faint, distant rumble is a faint sway.
            RumbleClient.reset();
            RumbleClient.accept(new RumblePayload((float) RumbleLaw.PERCEPTION, 0, 1, 0, 0, 8000));
            for (int i = 0; i < 80; i++) RumbleClient.advance();
            double faint = 0;
            for (int frame = 0; frame < 60 * 30; frame++) faint = Math.max(faint, RumbleClient.offsets(frame / 60.0, 1f).maxAngleDeg());
            REPORT.addProperty("camera_max_angle_deg_at_perception_limit", faint);
            require(faint > 0.02 && faint < 0.8, "at the perception limit the view sways " + faint + " degrees");

            // The accessibility option: zero is nothing at all.
            VisualEffectsConfig.SCREEN_SHAKE.set(0.0);
            RumbleClient.reset();
            RumbleClient.accept(new RumblePayload(0.5f, 0.5f, 1, 0, 0, 5));
            for (int i = 0; i < 80; i++) RumbleClient.advance();
            for (int frame = 0; frame < 600; frame++) {
                var o = RumbleClient.offsets(frame / 60.0, 1f);
                require(o.maxAngleDeg() == 0 && o.maxMoveBlocks() == 0, "screenShake 0 still moved the view");
            }
            var event = new ViewportEvent.ComputeCameraAngles(camera, 0.5, 10f, 20f, 0f);
            NeoForge.EVENT_BUS.post(event);
            require(event.getYaw() == 10f && event.getPitch() == 20f && event.getRoll() == 0f, "screenShake 0 still moved the camera event");
            // (one source: two together are held to 1.25 shakes, which is not linear in the option)
            RumbleClient.reset();
            RumbleClient.accept(new RumblePayload(0.5f, 0, 1, 0, 0, 5));
            for (int i = 0; i < 80; i++) RumbleClient.advance();
            VisualEffectsConfig.SCREEN_SHAKE.set(0.5);
            double half = 0, full;
            for (int frame = 0; frame < 600; frame++) half = Math.max(half, RumbleClient.offsets(frame / 60.0, 1f).maxAngleDeg());
            VisualEffectsConfig.SCREEN_SHAKE.set(1.0);
            full = 0;
            for (int frame = 0; frame < 600; frame++) full = Math.max(full, RumbleClient.offsets(frame / 60.0, 1f).maxAngleDeg());
            require(half > 0.3 * full && half < 0.7 * full, "screenShake 0.5 should give about half: " + half + " vs " + full);
        } finally {
            VisualEffectsConfig.SCREEN_SHAKE.set(saved);
            RumbleClient.reset();
        }
    }
}
