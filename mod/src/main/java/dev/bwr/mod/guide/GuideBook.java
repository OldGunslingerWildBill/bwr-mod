package dev.bwr.mod.guide;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The in-game guide as data: a book is an index of chapters and each chapter is a JSON file of pages, under
 * {@code assets/bwr/guide/<language>/} (see GUIDE.md). Nothing about the guide's content is in Java, so a translator or an
 * editor changes the text without touching code.
 *
 * <p>This class is pure (no screen, no resource manager): it parses what a {@link Source} hands it. The screen reads the
 * files through the client's resource manager; the GameTest audit and the Python audit read the same files from the jar
 * or the source tree, and every one of them runs {@link #load} and {@link #problems}.
 *
 * <p>A language that has no file falls back to {@value #DEFAULT_LANG} file by file, so a partial translation still works.
 */
public final class GuideBook {
    private GuideBook() {
    }

    public static final String DEFAULT_LANG = "en_us";
    public static final String INDEX = "index.json";
    /** Most characters of text on one page; the screen is laid out so that this fits at the smallest GUI size without scrolling. */
    public static final int MAX_PAGE_CHARS = 600;
    /** Most pages in one chapter (a chapter is a short read; split it instead). */
    public static final int MAX_PAGES_PER_CHAPTER = 12;
    /** Most pixels (a side) of a page image. */
    public static final int MAX_IMAGE_SIZE = 32;
    /** Most item icons on one page. */
    public static final int MAX_ICONS = 12;

    /** An optional small picture drawn from a texture of the mod: {@code textureSize} is the texture's side, {@code size} what it is drawn at. */
    public record Image(String texture, int textureSize, int size) {
    }

    public record Page(String title, String text, List<String> icons, Image image) {
    }

    /** {@code docs} are file names in the repository, named at the end of the chapter as "More: ...". */
    public record Chapter(String id, String title, List<String> docs, List<Page> pages) {
    }

    public record Book(String lang, List<Chapter> chapters) {
        public int pageCount() {
            int n = 0;
            for (Chapter c : chapters) {
                n += c.pages().size();
            }
            return n;
        }

        /** Index of the chapter with this id, or -1. */
        public int indexOf(String id) {
            for (int i = 0; i < chapters.size(); i++) {
                if (chapters.get(i).id().equals(id)) {
                    return i;
                }
            }
            return -1;
        }
    }

    /** Where the files come from. Returns the text of {@code guide/<lang>/<file>}, or null when there is no such file. */
    public interface Source {
        String read(String lang, String file) throws IOException;
    }

    /** A guide file that is missing or malformed; the message names the file. */
    public static final class GuideException extends RuntimeException {
        public GuideException(String message) {
            super(message);
        }
    }

    /** Reads {@code file} in {@code lang}, else in the default language; throws if neither has it. */
    private static String read(Source source, String lang, String file) {
        try {
            String text = source.read(lang, file);
            if (text == null && !DEFAULT_LANG.equals(lang)) {
                text = source.read(DEFAULT_LANG, file);
            }
            if (text == null) {
                throw new GuideException("guide/" + lang + "/" + file + " does not exist");
            }
            return text;
        } catch (IOException e) {
            throw new GuideException("guide/" + lang + "/" + file + " could not be read: " + e.getMessage());
        }
    }

    private static JsonObject object(String file, String text) {
        try {
            JsonElement root = JsonParser.parseString(text);
            if (!root.isJsonObject()) {
                throw new GuideException(file + ": the top level must be an object");
            }
            return root.getAsJsonObject();
        } catch (JsonParseException e) {
            throw new GuideException(file + ": not valid JSON: " + e.getMessage());
        }
    }

    private static String string(JsonObject o, String key, String file) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString() || e.getAsString().isBlank()) {
            throw new GuideException(file + ": \"" + key + "\" must be a non-empty string");
        }
        return e.getAsString();
    }

    /** The page text: one string, or an array of lines joined with newlines (an empty string is a blank line). */
    private static String text(JsonObject o, String where) {
        JsonElement e = o.get("text");
        if (e != null && e.isJsonArray()) {
            List<String> lines = new ArrayList<>();
            for (JsonElement item : e.getAsJsonArray()) {
                if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()) {
                    throw new GuideException(where + ": \"text\" lines must be strings");
                }
                lines.add(item.getAsString());
            }
            String joined = String.join("\n", lines);
            if (joined.isBlank()) {
                throw new GuideException(where + ": \"text\" must not be empty");
            }
            return joined;
        }
        return string(o, "text", where);
    }

    private static List<String> strings(JsonObject o, String key, String file, boolean required) {
        JsonElement e = o.get(key);
        if (e == null) {
            if (required) {
                throw new GuideException(file + ": \"" + key + "\" is missing");
            }
            return List.of();
        }
        if (!e.isJsonArray()) {
            throw new GuideException(file + ": \"" + key + "\" must be an array of strings");
        }
        List<String> out = new ArrayList<>();
        for (JsonElement item : e.getAsJsonArray()) {
            if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString() || item.getAsString().isBlank()) {
                throw new GuideException(file + ": \"" + key + "\" must hold only non-empty strings");
            }
            out.add(item.getAsString());
        }
        return out;
    }

    /** Loads the whole book in {@code lang} (the default language fills any file it lacks). Throws {@link GuideException} on the first fault. */
    public static Book load(Source source, String lang) {
        JsonObject index = object(INDEX, read(source, lang, INDEX));
        List<String> ids = strings(index, "chapters", INDEX, true);
        if (ids.isEmpty()) {
            throw new GuideException(INDEX + ": the book has no chapters");
        }
        Set<String> seen = new LinkedHashSet<>();
        List<Chapter> chapters = new ArrayList<>();
        for (String id : ids) {
            if (!seen.add(id)) {
                throw new GuideException(INDEX + ": chapter \"" + id + "\" is listed twice");
            }
            chapters.add(chapter(source, lang, id));
        }
        return new Book(lang, List.copyOf(chapters));
    }

    private static Chapter chapter(Source source, String lang, String id) {
        String file = id + ".json";
        JsonObject o = object(file, read(source, lang, file));
        String title = string(o, "title", file);
        List<String> docs = strings(o, "docs", file, true);
        if (docs.isEmpty()) {
            throw new GuideException(file + ": \"docs\" must name at least one repository document");
        }
        for (String doc : docs) {
            if (!doc.endsWith(".md") || doc.contains("\\") || doc.contains("..") || doc.startsWith("/")) {
                throw new GuideException(file + ": document \"" + doc + "\" must be a path inside the repository ending in .md");
            }
        }
        JsonElement pagesElement = o.get("pages");
        if (pagesElement == null || !pagesElement.isJsonArray() || pagesElement.getAsJsonArray().isEmpty()) {
            throw new GuideException(file + ": \"pages\" must be a non-empty array");
        }
        JsonArray pagesJson = pagesElement.getAsJsonArray();
        List<Page> pages = new ArrayList<>();
        for (int i = 0; i < pagesJson.size(); i++) {
            String where = file + " page " + (i + 1);
            if (!pagesJson.get(i).isJsonObject()) {
                throw new GuideException(where + ": must be an object");
            }
            pages.add(page(pagesJson.get(i).getAsJsonObject(), where));
        }
        return new Chapter(id, title, List.copyOf(docs), List.copyOf(pages));
    }

    private static Page page(JsonObject o, String where) {
        String title = string(o, "title", where);
        String text = text(o, where);
        List<String> icons = strings(o, "icons", where, false);
        for (String icon : icons) {
            if (ResourceLocation.tryParse(icon) == null) {
                throw new GuideException(where + ": icon \"" + icon + "\" is not a valid item id");
            }
        }
        Image image = null;
        JsonElement imageElement = o.get("image");
        if (imageElement != null) {
            if (!imageElement.isJsonObject()) {
                throw new GuideException(where + ": \"image\" must be an object");
            }
            JsonObject io = imageElement.getAsJsonObject();
            String texture = string(io, "texture", where + " image");
            if (ResourceLocation.tryParse(texture) == null || !texture.endsWith(".png")) {
                throw new GuideException(where + ": image texture \"" + texture + "\" must be a resource location ending in .png");
            }
            image = new Image(texture, integer(io, "textureSize", where + " image"), integer(io, "size", where + " image"));
        }
        return new Page(title, text, List.copyOf(icons), image);
    }

    private static int integer(JsonObject o, String key, String where) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
            throw new GuideException(where + ": \"" + key + "\" must be a whole number");
        }
        int v = e.getAsInt();
        if (v <= 0) {
            throw new GuideException(where + ": \"" + key + "\" must be above zero");
        }
        return v;
    }

    /**
     * The limits the screen is laid out for, as a list of faults (empty when the book is within them). The audits require none:
     * a page that is too long would need scrolling at the smallest GUI size.
     */
    public static List<String> problems(Book book) {
        List<String> out = new ArrayList<>();
        for (Chapter c : book.chapters()) {
            if (c.pages().size() > MAX_PAGES_PER_CHAPTER) {
                out.add(c.id() + ": " + c.pages().size() + " pages (at most " + MAX_PAGES_PER_CHAPTER + ")");
            }
            for (int i = 0; i < c.pages().size(); i++) {
                Page p = c.pages().get(i);
                String where = c.id() + " page " + (i + 1) + " (" + p.title() + ")";
                if (p.text().length() > MAX_PAGE_CHARS) {
                    out.add(where + ": " + p.text().length() + " characters of text (at most " + MAX_PAGE_CHARS + ")");
                }
                if (p.icons().size() > MAX_ICONS) {
                    out.add(where + ": " + p.icons().size() + " icons (at most " + MAX_ICONS + ")");
                }
                if (p.image() != null && p.image().size() > MAX_IMAGE_SIZE) {
                    out.add(where + ": image drawn " + p.image().size() + " px (at most " + MAX_IMAGE_SIZE + ")");
                }
            }
        }
        return out;
    }
}
