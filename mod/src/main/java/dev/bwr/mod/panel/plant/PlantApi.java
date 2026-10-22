package dev.bwr.mod.panel.plant;

import dev.bwr.mod.panel.component.PlantComputerData;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The program-facing calls of a plant computer, in plain Java (no CC types), so every bound is tested without
 * ComputerCraft. {@code PanelPeripheral} turns a {@link PlantState.Rejected} into a Lua error. Pages and columns and rows
 * are 1-based here, as in Lua.
 *
 * <p>Every call that can change the display first takes a token from the display's rate limit
 * ({@link PlantLimits#CALL_BURST}); a program that calls faster than {@link PlantLimits#CALL_REFILL_PER_TICK} a tick for long
 * gets an error, not a queue. Text is cleaned (printable characters only) and cut to its limit; numbers, positions, sizes
 * and counts outside their range are errors. Nothing here reads or writes the plant.
 */
public final class PlantApi {
    private PlantApi() {
    }

    private static final Set<String> TEXT_KEYS = Set.of("type", "col", "row", "text", "color");
    private static final Set<String> FIELD_KEYS = Set.of("type", "col", "row", "w", "text", "caption", "label", "unit", "decimals",
            "value", "color", "warnLow", "warnHigh", "alarmLow", "alarmHigh");
    private static final Set<String> BAR_KEYS = Set.of("type", "col", "row", "w", "text", "caption", "label", "unit", "decimals",
            "value", "min", "max", "color", "warnLow", "warnHigh", "alarmLow", "alarmHigh", "showValue");
    private static final Set<String> TREND_KEYS = Set.of("type", "col", "row", "w", "h", "text", "caption", "label", "unit",
            "decimals", "min", "max", "history", "color");
    private static final Set<String> TILE_KEYS = Set.of("type", "col", "row", "w", "h", "text", "caption", "label", "state");
    private static final Set<String> KEY_KEYS = Set.of("type", "key", "text", "caption", "label");

    private static PlantState.Rejected no(String message) {
        return new PlantState.Rejected(message);
    }

    // ---- info

    /** What a program needs to lay a page out, and the limits it must keep to. */
    public static Map<String, Object> info(PlantComputerData data) {
        PlantLayout layout = data.layout();
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("cols", layout.cols());
        info.put("rows", layout.rows());
        info.put("mode", data.mode);
        info.put("page", data.program().selected() + 1);
        info.put("pages", data.program().definedPages());
        info.put("elements", data.program().elementCount());
        info.put("trends", data.program().trendCount());
        Map<String, Object> limits = new LinkedHashMap<>();
        limits.put("pages", PlantLimits.MAX_PAGES);
        limits.put("elementsPerPage", PlantLimits.MAX_ELEMENTS_PER_PAGE);
        limits.put("elements", PlantLimits.MAX_ELEMENTS);
        limits.put("trends", PlantLimits.MAX_TRENDS);
        limits.put("points", PlantLimits.MAX_POINTS);
        limits.put("keys", PlantLimits.MAX_KEYS);
        limits.put("text", PlantLimits.MAX_TEXT);
        info.put("limits", limits);
        return info;
    }

    // ---- page calls

    private static PlantState program(PlantComputerData data) {
        if (!data.programMode()) {
            throw no("this display shows a built-in page (" + data.mode + "); set its Default page to program in the wrench to draw on it");
        }
        data.takeCall();
        return data.program();
    }

    private static int page(int page) {
        if (page < 1 || page > PlantLimits.MAX_PAGES) {
            throw no("page must be 1 to " + PlantLimits.MAX_PAGES);
        }
        return page - 1;
    }

    /** Clears every page and shows page 1. */
    public static boolean clear(PlantComputerData data) {
        return data.changed(program(data).clearAll());
    }

    public static boolean clearPage(PlantComputerData data, int page) {
        int index = page(page);
        return data.changed(program(data).clearPage(index));
    }

    /** Shows page {@code page}. */
    public static boolean setPage(PlantComputerData data, int page) {
        int index = page(page);
        return data.changed(program(data).select(index));
    }

    public static boolean title(PlantComputerData data, int page, String title) {
        int index = page(page);
        return data.changed(program(data).title(index, clean(title, PlantLimits.MAX_TITLE)));
    }

    public static boolean remove(PlantComputerData data, int page, String id) {
        int index = page(page);
        return data.changed(program(data).remove(index, id(id)));
    }

    // ---- elements

    /** Defines (or redefines) an element from a Lua table. */
    public static boolean set(PlantComputerData data, int page, String id, Map<?, ?> spec) {
        int index = page(page);
        PlantState state = program(data);
        PlantLayout layout = data.layout();
        PlantElement element = parse(id(id), spec, layout);
        return data.changed(state.put(index, element));
    }

    /** Updates the number of a field or bar (nil: no data), the state of a tile, or the text of a text line or key. */
    public static boolean value(PlantComputerData data, int page, String id, Object value) {
        int index = page(page);
        PlantState state = program(data);
        String key = id(id);
        PlantElement element = state.element(index, key);
        if (element == null) {
            throw no("no element " + key + " on page " + page);
        }
        boolean changed;
        switch (element.kind) {
            case FIELD, BAR -> {
                if (value != null && !(value instanceof Number) && !Boolean.FALSE.equals(value)) {
                    throw no(key + " takes a number, or nil for no data");
                }
                float v = value instanceof Number n ? (float) n.doubleValue() : Float.NaN;
                changed = state.setValue(index, key, v);
            }
            case TILE -> {
                int s = value instanceof String text ? PlantColors.stateByName(text) : -1;
                if (s < 0) {
                    throw no(key + " takes a state: ok, warn, alarm or off");
                }
                changed = state.setTile(index, key, s);
            }
            case TEXT, KEY -> {
                if (!(value instanceof String text)) {
                    throw no(key + " takes a string");
                }
                changed = state.setText(index, key, clean(text, element.kind == PlantElement.Kind.TEXT ? PlantLimits.MAX_TEXT : PlantLimits.MAX_LABEL));
            }
            default -> throw no(key + " is a trend: push points to it");
        }
        return data.changed(changed);
    }

    /** Adds a point to a trend. */
    public static boolean push(PlantComputerData data, int page, String id, double value) {
        int index = page(page);
        PlantState state = program(data);
        if (!Double.isFinite(value)) {
            throw no("a trend point must be a finite number");
        }
        return data.changed(state.push(index, id(id), (float) value));
    }

    // ---- parsing

    /** Printable ASCII only (so every text is a known number of bytes on the wire), at most {@code max} characters. */
    static String clean(String text, int max) {
        if (text == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(Math.min(text.length(), max));
        for (int i = 0; i < text.length() && out.length() < max; i++) {
            char c = text.charAt(i);
            out.append(c >= 32 && c < 127 ? c : '?');
        }
        return out.toString();
    }

    /** An element id: 1 to {@link PlantLimits#MAX_ID} letters, digits, underscores or dashes. */
    static String id(String id) {
        if (id == null || id.isEmpty() || id.length() > PlantLimits.MAX_ID) {
            throw no("an element id is 1 to " + PlantLimits.MAX_ID + " characters");
        }
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (!(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '_' || c == '-')) {
                throw no("an element id has letters, digits, _ and - only: " + id);
            }
        }
        return id;
    }

    private static Object field(Map<?, ?> spec, String key) {
        return spec.get(key);
    }

    private static String text(Map<?, ?> spec, int max) {
        String found = null;
        for (String key : new String[] {"text", "caption", "label"}) {
            Object v = spec.get(key);
            if (v != null) {
                if (!(v instanceof String s)) {
                    throw no(key + " must be a string");
                }
                if (found != null) {
                    throw no("give text, caption or label, not several");
                }
                found = s;
            }
        }
        return clean(found, max);
    }

    private static int integer(Map<?, ?> spec, String key, int def, int min, int max) {
        Object v = field(spec, key);
        if (v == null) {
            return def;
        }
        if (!(v instanceof Number n) || !Double.isFinite(n.doubleValue()) || n.doubleValue() != Math.rint(n.doubleValue())) {
            throw no(key + " must be a whole number");
        }
        double d = n.doubleValue();
        if (d < min || d > max) {
            throw no(key + " must be " + min + " to " + max);
        }
        return (int) d;
    }

    private static float number(Map<?, ?> spec, String key, float def) {
        Object v = field(spec, key);
        if (v == null) {
            return def;
        }
        if (!(v instanceof Number n) || !Double.isFinite(n.doubleValue()) || Math.abs(n.doubleValue()) > 1.0e9) {
            throw no(key + " must be a finite number");
        }
        return (float) n.doubleValue();
    }

    private static int colour(Map<?, ?> spec) {
        Object v = field(spec, "color");
        if (v == null) {
            return PlantColors.WHITE;
        }
        int c = v instanceof String s ? PlantColors.byName(s) : -1;
        if (c < 0) {
            throw no("color must be one of " + String.join(", ", PlantColors.NAMES));
        }
        return c;
    }

    private static void only(Map<?, ?> spec, Set<String> allowed, String kind) {
        for (Object key : spec.keySet()) {
            if (!(key instanceof String s) || !allowed.contains(s)) {
                throw no(kind + " has no setting " + key + "; it takes " + String.join(", ", new java.util.TreeSet<>(allowed)));
            }
        }
    }

    private static void fits(PlantElement e, PlantLayout layout) {
        if (e.col + e.w - 1 > layout.cols() || e.row + e.h - 1 > layout.rows()) {
            throw no("does not fit the " + layout.cols() + " x " + layout.rows() + " character grid (col " + e.col + ", row " + e.row
                    + ", " + e.w + " wide, " + e.h + " high)");
        }
    }

    /** Builds an element from a table and checks it against the grid. */
    static PlantElement parse(String id, Map<?, ?> spec, PlantLayout layout) {
        Object type = spec.get("type");
        if (!(type instanceof String kind)) {
            throw no("type is required: text, field, bar, trend, tile or key");
        }
        PlantElement e;
        switch (kind.toLowerCase(Locale.ROOT)) {
            case "text" -> {
                only(spec, TEXT_KEYS, "text");
                e = new PlantElement(PlantElement.Kind.TEXT, id);
                e.col = integer(spec, "col", -1, 1, layout.cols());
                e.row = integer(spec, "row", -1, 1, layout.rows());
                if (e.col < 0 || e.row < 0) {
                    throw no("col and row are required");
                }
                e.text = text(spec, PlantLimits.MAX_TEXT);
                if (e.text.length() > layout.cols() - e.col + 1) {
                    e.text = e.text.substring(0, layout.cols() - e.col + 1);
                }
                e.w = Math.max(1, e.text.length());
                e.colour = colour(spec);
            }
            case "field" -> {
                only(spec, FIELD_KEYS, "field");
                e = placed(PlantElement.Kind.FIELD, id, spec, layout, 22, 1);
                e.text = text(spec, PlantLimits.MAX_LABEL);
                e.unit = unit(spec);
                e.decimals = integer(spec, "decimals", 1, 0, 4);
                e.value = number(spec, "value", Float.NaN);
                limits(e, spec);
                e.colour = colour(spec);
            }
            case "bar" -> {
                only(spec, BAR_KEYS, "bar");
                e = placed(PlantElement.Kind.BAR, id, spec, layout, 24, 1);
                e.text = text(spec, PlantLimits.MAX_LABEL);
                e.unit = unit(spec);
                e.decimals = integer(spec, "decimals", 1, 0, 4);
                e.value = number(spec, "value", Float.NaN);
                e.min = number(spec, "min", 0.0f);
                e.max = number(spec, "max", 100.0f);
                if (!(e.max > e.min)) {
                    throw no("max must be above min");
                }
                limits(e, spec);
                e.colour = colour(spec);
                Object show = spec.get("showValue");
                if (show != null && !(show instanceof Boolean)) {
                    throw no("showValue must be true or false");
                }
                e.state = Boolean.FALSE.equals(show) ? 1 : 0;
            }
            case "trend" -> {
                only(spec, TREND_KEYS, "trend");
                e = placed(PlantElement.Kind.TREND, id, spec, layout, 20, 6);
                e.text = text(spec, PlantLimits.MAX_LABEL);
                e.unit = unit(spec);
                e.decimals = integer(spec, "decimals", 1, 0, 4);
                e.min = number(spec, "min", 0.0f);
                e.max = number(spec, "max", 100.0f);
                if (!(e.max > e.min)) {
                    throw no("max must be above min");
                }
                e.len = integer(spec, "history", PlantLimits.DEFAULT_POINTS, PlantLimits.MIN_POINTS, PlantLimits.MAX_POINTS);
                e.points = new char[e.len];
                e.colour = colour(spec);
                if (e.h < 3) {
                    throw no("a trend needs at least 3 rows");
                }
            }
            case "tile" -> {
                only(spec, TILE_KEYS, "tile");
                e = placed(PlantElement.Kind.TILE, id, spec, layout, 10, 3);
                e.text = text(spec, PlantLimits.MAX_LABEL);
                Object state = spec.get("state");
                if (state != null) {
                    int s = state instanceof String name ? PlantColors.stateByName(name) : -1;
                    if (s < 0) {
                        throw no("state must be ok, warn, alarm or off");
                    }
                    e.state = s;
                }
            }
            case "key" -> {
                only(spec, KEY_KEYS, "key");
                e = new PlantElement(PlantElement.Kind.KEY, id);
                e.state = integer(spec, "key", -1, 1, PlantLimits.MAX_KEYS);
                if (e.state < 0) {
                    throw no("key (1 to " + PlantLimits.MAX_KEYS + ") is required");
                }
                e.text = text(spec, PlantLimits.MAX_LABEL);
                e.col = Math.min(PlantLimits.MAX_COLS, 1 + layout.keyStart(e.state));
                e.row = layout.rows();
            }
            default -> throw no("type must be text, field, bar, trend, tile or key");
        }
        if (e.kind != PlantElement.Kind.KEY) {
            fits(e, layout);
        }
        return e;
    }

    private static PlantElement placed(PlantElement.Kind kind, String id, Map<?, ?> spec, PlantLayout layout, int defaultW,
                                       int defaultH) {
        PlantElement e = new PlantElement(kind, id);
        e.col = integer(spec, "col", -1, 1, layout.cols());
        e.row = integer(spec, "row", -1, 1, layout.rows());
        if (e.col < 0 || e.row < 0) {
            throw no("col and row are required");
        }
        e.w = integer(spec, "w", Math.max(1, Math.min(defaultW, layout.cols() - e.col + 1)), 1, layout.cols());
        e.h = kind == PlantElement.Kind.TREND || kind == PlantElement.Kind.TILE
                ? integer(spec, "h", Math.max(1, Math.min(defaultH, layout.rows() - e.row + 1)), 1, layout.rows()) : defaultH;
        return e;
    }

    private static String unit(Map<?, ?> spec) {
        Object v = spec.get("unit");
        if (v == null) {
            return "";
        }
        if (!(v instanceof String s)) {
            throw no("unit must be a string");
        }
        return clean(s, PlantLimits.MAX_UNIT);
    }

    private static void limits(PlantElement e, Map<?, ?> spec) {
        e.warnLow = number(spec, "warnLow", Float.NaN);
        e.warnHigh = number(spec, "warnHigh", Float.NaN);
        e.alarmLow = number(spec, "alarmLow", Float.NaN);
        e.alarmHigh = number(spec, "alarmHigh", Float.NaN);
    }
}
