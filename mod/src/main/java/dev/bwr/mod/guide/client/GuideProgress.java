package dev.bwr.mod.guide.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.bwr.mod.guide.GuideBook;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Where the reader stopped, kept on this computer only: one small file in the config folder, written when a page is
 * shown. It is a convenience: a missing, unreadable or out-of-date file simply means "start at the chapter list".
 */
public final class GuideProgress {
    private GuideProgress() {
    }

    /** Where the reader stopped: a chapter id and a zero-based page. */
    public record Place(String chapter, int page) {
    }

    /** Set by the GUI check, which opens screens in bulk and must not leave a bookmark behind. */
    public static boolean suspended;

    private static Place memory;

    /** Sets the place in memory only, for the GUI check, which shows the chapter list with and without a bookmark. */
    public static void rememberOnly(String chapter, int page) {
        memory = new Place(chapter, page);
    }

    /** Forgets the place in memory (the GUI check). */
    public static void forget() {
        memory = null;
    }

    static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve("bwr-guide-progress.json");
    }

    /** The saved place if it still points at a page of {@code book}, else null. */
    public static Place load(GuideBook.Book book) {
        Place place = memory;
        if (place == null && !suspended) {
            try {
                Path file = file();
                if (Files.isRegularFile(file)) {
                    JsonObject o = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
                    place = new Place(o.get("chapter").getAsString(), o.get("page").getAsInt());
                }
            } catch (IOException | RuntimeException e) {
                place = null;
            }
        }
        if (place == null) {
            return null;
        }
        int c = book.indexOf(place.chapter());
        if (c < 0 || place.page() < 0 || place.page() >= book.chapters().get(c).pages().size()) {
            return null;
        }
        return place;
    }

    public static void save(String chapter, int page) {
        if (suspended) {
            return;
        }
        Place place = new Place(chapter, page);
        if (place.equals(memory)) {
            return;
        }
        memory = place;
        JsonObject o = new JsonObject();
        o.addProperty("chapter", chapter);
        o.addProperty("page", page);
        try {
            Files.writeString(file(), o + "\n", StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            // a bookmark that cannot be written is not worth an error
        }
    }
}
