package dev.bwr.mod.devtest;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import dev.bwr.mod.BwrMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.sounds.JOrbisAudioStream;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.util.RandomSource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;

/** Opt-in resource/decoder integration check. Creates no world and plays no sounds. */
@EventBusSubscriber(modid = BwrMod.MOD_ID, value = Dist.CLIENT)
public final class SoundClientCheck {
    private static final int SAMPLE_RATE = 48_000;
    // Calibrated on all 16 files with the pinned Minecraft JOrbis decoder: its 12,000
    // output frames equal each EOS granule. Other decoders may handle priming differently.
    private static final long MIN_FRAMES = 12_000;
    private static final long MAX_FRAMES = 12_000;
    private static boolean checked;
    private static int age;

    private SoundClientCheck() { }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post ignored) throws Exception {
        if (!Boolean.getBoolean("bwr.soundClientCheck") || checked) return;
        Minecraft mc = Minecraft.getInstance();
        if (++age > 1800) throw new AssertionError("Sound client check timed out waiting for resource reload");
        if (mc.screen == null || mc.getOverlay() != null) return;
        checked = true;
        var output = mc.gameDirectory.toPath().resolve("sound-check");
        Files.createDirectories(output);
        JsonObject report = new JsonObject();
        report.addProperty("decoder", "Minecraft 1.21.1 JOrbisAudioStream");
        report.addProperty("playback_requested", false);
        report.addProperty("expected_sample_rate_hz", SAMPLE_RATE);
        report.addProperty("minimum_frames_per_file", MIN_FRAMES);
        report.addProperty("maximum_frames_per_file", MAX_FRAMES);
        JsonArray entries = new JsonArray();
        report.add("sounds", entries);
        long totalFrames = 0;
        try {
            var resolvedFiles = new HashSet<>();
            for (String path : PhaseLSoundTests.PATHS) {
                var id = BwrMod.id(path);
                require(BuiltInRegistries.SOUND_EVENT.containsKey(id), "unregistered event " + id);
                var event = mc.getSoundManager().getSoundEvent(id);
                require(event != null && event.getWeight() == 1, "missing/non-single sound resource event " + id);
                String subtitleKey = "subtitle.bwr." + path;
                var subtitle = event.getSubtitle();
                require(subtitle != null && subtitle.getContents() instanceof TranslatableContents translated
                                && translated.getKey().equals(subtitleKey), "incorrect subtitle key " + id);
                require(!subtitle.getString().isBlank() && !subtitle.getString().equals(subtitleKey),
                        "unresolved subtitle translation " + id);
                var sound = event.getSound(RandomSource.create(0));
                require(sound.getType() == Sound.Type.FILE && sound.getLocation().equals(id),
                        "event resolved an incorrect sound " + id + ": " + sound.getLocation());
                var resource = sound.getPath();
                require(resource.equals(BwrMod.id("sounds/" + path + ".ogg")) && resolvedFiles.add(resource),
                        "placeholder is not a distinct expected resource " + id);
                JsonObject entry = new JsonObject();
                entry.addProperty("event", id.toString());
                entry.addProperty("resource", resource.toString());
                entry.addProperty("subtitle", subtitle.getString());
                entries.add(entry);
                try (var input = mc.getResourceManager().open(resource);
                     var decoded = new JOrbisAudioStream(input)) {
                    var format = decoded.getFormat();
                    entry.addProperty("channels", format.getChannels());
                    entry.addProperty("sample_rate_hz", format.getSampleRate());
                    require(format.getChannels() == 1 && format.getSampleRate() == SAMPLE_RATE,
                            "unexpected decoded format " + id + ": " + format);
                    long[] frames = {0};
                    int chunks = 0;
                    while (decoded.readChunk(sample -> {
                        ++frames[0];
                        require(Float.isFinite(sample) && sample == 0.0f,
                                "nonzero/nonfinite PCM in " + id + " at frame " + frames[0] + ": " + sample);
                        require(frames[0] <= SAMPLE_RATE * 2L, "unexpectedly long placeholder " + id);
                    })) {
                        require(++chunks <= 4096, "unbounded decoder packet stream " + id);
                    }
                    entry.addProperty("decoded_frames", frames[0]);
                    entry.addProperty("decoded_seconds", frames[0] / (double) SAMPLE_RATE);
                    entry.addProperty("all_samples_exact_zero", true);
                    require(frames[0] >= MIN_FRAMES && frames[0] <= MAX_FRAMES,
                            "decoded frame count outside calibrated bounds for " + id + ": " + frames[0]
                                    + " (expected " + MIN_FRAMES + ".." + MAX_FRAMES + ")");
                    totalFrames += frames[0];
                    LogUtils.getLogger().info("SOUND CLIENT PASS {}: {} mono frames at {} Hz; exact-zero PCM",
                            id, frames[0], SAMPLE_RATE);
                }
            }
            report.addProperty("status", "PASS");
            report.addProperty("decoded_total_frames", totalFrames);
            report.addProperty("verified_events", entries.size());
            Files.writeString(output.resolve("results.json"), new GsonBuilder().setPrettyPrinting().create().toJson(report),
                    StandardCharsets.UTF_8);
            LogUtils.getLogger().info("SOUND CLIENT COMPLETE: {} events, {} decoded frames, no playback",
                    entries.size(), totalFrames);
            mc.stop();
        } catch (Exception | AssertionError failure) {
            report.addProperty("status", "FAIL");
            report.addProperty("failure", failure.toString());
            report.addProperty("decoded_total_frames_before_failure", totalFrames);
            Files.writeString(output.resolve("results.json"), new GsonBuilder().setPrettyPrinting().create().toJson(report),
                    StandardCharsets.UTF_8);
            throw failure;
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
