package dev.bwr.mod.panel.board;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The premade boards a game knows: every valid {@code data/<ns>/panel_board/*.json}. Filled on the server by
 * {@link Loader} with every data pack reload (a pack adds a board by shipping a file), and on a client by the sync
 * packet ({@link BoardTemplateSync}) so the blueprint can draw its ghost and its tooltip. A template with a problem is
 * logged and left out; the rest still load.
 */
public final class BoardTemplates {
    private BoardTemplates() {
    }

    private static final Logger LOGGER = LogUtils.getLogger();
    /** The data folder: {@code data/<namespace>/panel_board/<name>.json}. */
    public static final String DIRECTORY = "panel_board";
    /** A template file larger than this is refused (it would not fit a sync packet either). */
    public static final int MAX_JSON_CHARS = 400_000;
    private static final Gson GSON = new Gson();

    private static volatile Map<ResourceLocation, BoardTemplate> templates = Map.of();
    /** The minified JSON of each loaded template, kept for the client sync. */
    private static volatile Map<ResourceLocation, String> sources = Map.of();

    public static Map<ResourceLocation, BoardTemplate> all() {
        return templates;
    }

    /** The loaded templates in blueprint order (by their order field, then id). */
    public static List<BoardTemplate> ordered() {
        List<BoardTemplate> list = new ArrayList<>(templates.values());
        list.sort(Comparator.comparingInt(BoardTemplate::order).thenComparing(template -> template.id().toString()));
        return list;
    }

    public static BoardTemplate get(ResourceLocation id) {
        return id == null ? null : templates.get(id);
    }

    /** The template after {@code current} in blueprint order (the one before it when {@code backwards}); the first if none is current. */
    public static BoardTemplate cycle(ResourceLocation current, boolean backwards) {
        List<BoardTemplate> list = ordered();
        if (list.isEmpty()) {
            return null;
        }
        int at = -1;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id().equals(current)) {
                at = i;
            }
        }
        if (at < 0) {
            return list.get(0);
        }
        return list.get((at + (backwards ? list.size() - 1 : 1)) % list.size());
    }

    public static Map<ResourceLocation, String> sources() {
        return sources;
    }

    /** Installs a set of templates (a reload, or a sync from the server). */
    static void install(Map<ResourceLocation, BoardTemplate> loaded, Map<ResourceLocation, String> json) {
        templates = Collections.unmodifiableMap(new LinkedHashMap<>(loaded));
        sources = Collections.unmodifiableMap(new LinkedHashMap<>(json));
    }

    /** Parses one template from its JSON text; the problems are logged with {@code origin} and null is returned for a bad one. */
    static BoardTemplate parseLogged(ResourceLocation id, String json, String origin) {
        try {
            BoardTemplateParser.Result result = BoardTemplateParser.parse(id, GSON.fromJson(json, JsonElement.class));
            if (result.ok()) {
                return result.template();
            }
            LOGGER.error("Skipping premade board {} ({}): {} problem(s)", id, origin, result.problems().size());
            for (String problem : result.problems()) {
                LOGGER.error("  {}: {}", id, problem);
            }
        } catch (RuntimeException bad) {
            LOGGER.error("Skipping premade board {} ({}): not valid JSON: {}", id, origin, bad.toString());
        }
        return null;
    }

    /** Reads the panel_board folder of every data pack. */
    public static final class Loader extends SimpleJsonResourceReloadListener {
        public Loader() {
            super(GSON, DIRECTORY);
        }

        @Override
        protected void apply(Map<ResourceLocation, JsonElement> entries, ResourceManager manager, ProfilerFiller profiler) {
            Map<ResourceLocation, BoardTemplate> loaded = new LinkedHashMap<>();
            Map<ResourceLocation, String> json = new LinkedHashMap<>();
            List<ResourceLocation> ids = new ArrayList<>(entries.keySet());
            ids.sort(Comparator.comparing(ResourceLocation::toString));
            for (ResourceLocation id : ids) {
                String text = GSON.toJson(entries.get(id));
                if (text.length() > MAX_JSON_CHARS) {
                    LOGGER.error("Skipping premade board {}: the file is {} characters, at most {}", id, text.length(), MAX_JSON_CHARS);
                    continue;
                }
                BoardTemplate template = parseLogged(id, text, "data pack");
                if (template != null) {
                    loaded.put(id, template);
                    json.put(id, text);
                }
            }
            install(loaded, json);
            LOGGER.info("Loaded {} premade board(s) ({} file(s) read)", loaded.size(), entries.size());
        }
    }
}
