package dev.bwr.mod.panel.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Loads a whole directory's worth of model texts, one id at a time, and survives anything in them: a file that
 * is not valid JSON, has the wrong format, lies outside its footprint or is simply enormous is reported in
 * {@link Result#problems} and skipped, and the rest still load. Pure (text in, models out), so the same code
 * serves the client resource reload and the tests.
 */
public final class PanelModelSet {
    private PanelModelSet() {
    }

    /** Model ids are file names: lower case letters, digits and underscores. */
    private static final Pattern VALID_ID = Pattern.compile("[a-z0-9_]{1,64}");

    public record Result(Map<String, BakedPanelModel> models, List<String> problems) {
    }

    /** Whether {@code id} is acceptable as a model id. */
    public static boolean validId(String id) {
        return VALID_ID.matcher(id).matches();
    }

    /** Parses every text; never throws. */
    public static Result loadAll(Map<String, String> textsById) {
        Map<String, BakedPanelModel> models = new TreeMap<>();
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, String> entry : textsById.entrySet()) {
            String id = entry.getKey();
            if (!validId(id)) {
                problems.add(id + ": not a valid model id (lower case letters, digits and underscores)");
                continue;
            }
            try {
                models.put(id, PanelModelParser.parse(id, entry.getValue()));
            } catch (ModelFormatException e) {
                problems.add(e.getMessage());
            } catch (RuntimeException | StackOverflowError e) {
                // Deeply nested JSON can overflow the parser's stack; nothing in a resource may crash the client.
                problems.add(id + ": could not be read (" + e.getClass().getSimpleName() + ")");
            }
        }
        return new Result(models, problems);
    }
}
