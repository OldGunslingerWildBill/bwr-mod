package dev.bwr.mod.panel.board;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.bwr.mod.panel.PanelGeometry;
import dev.bwr.mod.panel.board.BoardTemplate.Comp;
import dev.bwr.mod.panel.board.BoardTemplate.Frame;
import dev.bwr.mod.panel.board.BoardTemplate.Kind;
import dev.bwr.mod.panel.board.BoardTemplate.Mimic;
import dev.bwr.mod.panel.board.BoardTemplate.Reserved;
import dev.bwr.mod.panel.board.BoardTemplate.Surface;
import dev.bwr.mod.panel.component.ComponentType;
import dev.bwr.mod.panel.component.ComponentTypes;
import dev.bwr.mod.panel.component.ConfigField;
import dev.bwr.mod.panel.component.ConfigSchema;
import dev.bwr.mod.panel.component.Footprint;
import dev.bwr.mod.panel.mimic.MimicOps;
import dev.bwr.mod.panel.mimic.MimicStyle;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reads and validates a board template ({@link BoardTemplate}). Nothing is trusted: every problem is collected with
 * the entry it belongs to, and a template with any problem is refused as a whole (the loader logs the list and skips
 * the file), so a board that exists is a board that can be placed. Pure data and the component type registry; no
 * world, no config values (the limits are passed in, and the placer checks the live ones again).
 *
 * <h2>Format (version 1)</h2>
 * <pre>{@code
 * {
 *   "format": 1,
 *   "title": "...", "description": "...", "source": "...", "order": 1,
 *   "todo": ["comments for whoever extends the board"],
 *   "frames": [ {"id": "main", "kind": "duplex", "columns": 4, "at": [0, 0, 0]} ],
 *   "components": [
 *     {"on": "main.bench", "type": "bwr:lamp", "at": [3, 4], "size": [2, 2],      // size is required
 *      "label": "RHR-A-RUN", "caption": "RUN",
 *      "config": {"Color": "20C040", "Style": "ge", "Variant": "round"},
 *      "repeat": {"count": 7, "step": [3, 0]}, "names": ["A","B","C","D","E","F","G"]}   // optional
 *   ],
 *   "reserved": [ {"on": "main.board", "at": [0, 0], "size": [8, 6], "for": "bwr:controller_station", "note": "..."} ],
 *   "mimic": [ {"on": "main.board", "points": [[2, 2], [30, 2]], "style": {"Color": "2E7DE0", "FlowEvery": 16}} ]
 * }
 * }</pre>
 * {@code kind} is one of duplex, vertical, benchboard, console, back_panel, desk (1 m control desks, one block each) and
 * desk_wide (2 m control desks, two blocks each). {@code at} of a frame is
 * {@code [right, up, forward]} in blocks from the origin of the board. A repeat gives {@code count}, or {@code cols}
 * and {@code rows}, and a {@code step} in cells; in {@code label}, {@code caption} and the text values of
 * {@code config}, {@code {n}} is the 1-based number of the copy, {@code {i}} the 0-based one, {@code {col}} and
 * {@code {row}} its place in the grid and {@code {name}} the entry of {@code names}. Colours are numbers or
 * {@code "RRGGBB"} strings.
 */
public final class BoardTemplateParser {
    private BoardTemplateParser() {
    }

    public static final int FORMAT = 1;
    /** The most components one template may hold (a sanity bound, not a game rule). */
    public static final int MAX_COMPONENTS = 4096;

    /** What the live config limits a template must fit; {@link #DEFAULT} is the shipped default of {@code bwr-panels.toml}. */
    public record Limits(int maxColumns, int maxRows, int maxComponentsPerBlock, int maxLabel) {
        public static final Limits DEFAULT = new Limits(16, 8, 256, 16);
    }

    /** The outcome: the template, or null with the problems that stopped it. */
    public record Result(BoardTemplate template, List<String> problems) {
        public boolean ok() {
            return template != null && problems.isEmpty();
        }
    }

    /**
     * Sizes a board may use that are not (yet) on a type's footprint list: the exact real sizes proved too small, so boards are
     * laid out at about twice the real size (a dial 4 x 4, a switch 2 x 4, a lamp 2 x 2, a bar gauge 2 x 6), the sizes the
     * defaults are being doubled to. Accepting them here keeps a board valid whether or not the type's list has them yet. The
     * bar gauge also keeps its exact real size (1 x 3), which its tests do not allow on the list. A template must write a
     * size for every component (see {@link #sizeKnown}), so a change of a type's default size never changes a board.
     */
    private static final Map<String, List<Footprint>> EXTRA_SIZES = Map.ofEntries(
            Map.entry("bwr:bar_gauge", List.of(Footprint.of(1, 3), Footprint.of(2, 6))),
            Map.entry("bwr:dial_gauge", List.of(Footprint.of(4, 4), Footprint.of(6, 6))),
            Map.entry("bwr:edgewise_gauge_vertical", List.of(Footprint.of(2, 6))),
            Map.entry("bwr:edgewise_gauge_horizontal", List.of(Footprint.of(6, 2))),
            Map.entry("bwr:control_switch", List.of(Footprint.of(2, 4), Footprint.of(4, 6))),
            Map.entry("bwr:rotary_selector", List.of(Footprint.of(2, 4), Footprint.of(4, 6))),
            Map.entry("bwr:key_switch", List.of(Footprint.of(2, 4))),
            Map.entry("bwr:setpoint_knob", List.of(Footprint.of(2, 2), Footprint.of(4, 4))),
            Map.entry("bwr:toggle_switch", List.of(Footprint.of(2, 2))),
            Map.entry("bwr:pushbutton", List.of(Footprint.of(2, 2))),
            Map.entry("bwr:lamp", List.of(Footprint.of(2, 2))),
            Map.entry("bwr:rps_trip", List.of(Footprint.of(4, 2))),
            Map.entry("bwr:push_turn_switch", List.of(Footprint.of(2, 2))),
            Map.entry("bwr:annunciator", List.of(Footprint.of(2, 2), Footprint.of(4, 4))),
            Map.entry("bwr:bar_graph", List.of(Footprint.of(2, 6), Footprint.of(6, 2))));

    /** Whether {@code size} is a size of the type, or one of the off-list real sizes. */
    public static boolean sizeKnown(ComponentType type, Footprint size) {
        return type.footprints().contains(size) || EXTRA_SIZES.getOrDefault(type.id().toString(), List.of()).contains(size);
    }

    public static Result parse(ResourceLocation id, JsonElement json) {
        return parse(id, json, Limits.DEFAULT);
    }

    public static Result parse(ResourceLocation id, JsonElement json, Limits limits) {
        Parser parser = new Parser(id, limits);
        BoardTemplate template = null;
        try {
            template = parser.run(json);
        } catch (RuntimeException failure) {
            parser.problem("unreadable: " + failure);
        }
        return parser.problems.isEmpty() ? new Result(template, List.of()) : new Result(null, List.copyOf(parser.problems));
    }

    // ---- the parser

    private static final class Parser {
        final ResourceLocation id;
        final Limits limits;
        final List<String> problems = new ArrayList<>();

        Parser(ResourceLocation id, Limits limits) {
            this.id = id;
            this.limits = limits;
        }

        void problem(String text) {
            problems.add(text);
        }

        BoardTemplate run(JsonElement json) {
            if (json == null || !json.isJsonObject()) {
                problem("the file is not a JSON object");
                return null;
            }
            JsonObject root = json.getAsJsonObject();
            int format = integer(root, "format", "template", FORMAT);
            if (format != FORMAT) {
                problem("format " + format + " is not supported (this game reads format " + FORMAT + ")");
            }
            String title = text(root, "title", "template", prettify(id.getPath()), 64);
            String description = text(root, "description", "template", "", 512);
            String source = text(root, "source", "template", "", 256);
            int order = integer(root, "order", "template", 1000);
            List<String> todo = new ArrayList<>();
            if (root.has("todo")) {
                if (root.get("todo").isJsonArray()) {
                    for (JsonElement line : root.getAsJsonArray("todo")) {
                        todo.add(line.isJsonPrimitive() ? line.getAsString() : line.toString());
                    }
                } else {
                    problem("todo must be a list of strings");
                }
            }
            List<Frame> frames = frames(root);
            Map<String, Surface> surfaces = new LinkedHashMap<>();
            for (Frame frame : frames) {
                for (Surface surface : frame.surfaces()) {
                    surfaces.put(surface.id(), surface);
                }
            }
            Map<String, boolean[][]> taken = new HashMap<>();
            for (Surface surface : surfaces.values()) {
                taken.put(surface.id(), new boolean[surface.width()][surface.height()]);
            }
            List<Comp> components = components(root, surfaces, taken);
            List<Reserved> reserved = reserved(root, surfaces, taken);
            List<Mimic> mimic = mimic(root, surfaces);
            perBlockLimits(components, surfaces);
            if (frames.isEmpty()) {
                problem("the template has no frames");
            }
            return problems.isEmpty()
                    ? new BoardTemplate(id, title, description, source, order, List.copyOf(frames), List.copyOf(components),
                    List.copyOf(reserved), List.copyOf(mimic), List.copyOf(todo))
                    : null;
        }

        // ---- frames

        List<Frame> frames(JsonObject root) {
            List<Frame> frames = new ArrayList<>();
            if (!root.has("frames") || !root.get("frames").isJsonArray()) {
                problem("frames must be a list");
                return frames;
            }
            Set<String> names = new HashSet<>();
            Set<String> blocks = new HashSet<>();
            int index = 0;
            for (JsonElement element : root.getAsJsonArray("frames")) {
                String where = "frames[" + index++ + "]";
                if (!element.isJsonObject()) {
                    problem(where + " is not an object");
                    continue;
                }
                JsonObject object = element.getAsJsonObject();
                String name = text(object, "id", where, "", 24);
                if (name.isEmpty() || !name.matches("[a-z0-9_]+")) {
                    problem(where + ": id must be lower case letters, digits and underscores");
                    continue;
                }
                where = "frame '" + name + "'";
                if (!names.add(name)) {
                    problem(where + ": the id is used twice");
                    continue;
                }
                Kind kind = Kind.byId(text(object, "kind", where, "", 24));
                if (kind == null) {
                    problem(where + ": kind must be one of duplex, vertical, benchboard, console, back_panel, desk, desk_wide");
                    continue;
                }
                int columns = integer(object, "columns", where, 1);
                int rows = integer(object, "rows", where, 1);
                if (kind == Kind.DUPLEX || kind == Kind.CONSOLE || kind == Kind.DESK || kind == Kind.DESK_WIDE) {
                    if (object.has("rows") && rows != 1) {
                        problem(where + ": a " + kind.id() + " frame has one row of blocks (rows must be 1 or left out)");
                    }
                    rows = 1;
                }
                int perColumn = kind == Kind.DESK_WIDE ? 2 : 1; // a 2 m desk is two blocks wide
                if (columns < 1 || columns * perColumn > limits.maxColumns()) {
                    problem(where + ": columns must be 1 to " + limits.maxColumns() / perColumn + " (the widest panel group)");
                }
                if (rows < 1 || rows > limits.maxRows()) {
                    problem(where + ": rows must be 1 to " + limits.maxRows() + " (the tallest panel group)");
                }
                int[] at = ints(object, "at", where, 3, new int[] {0, 0, 0});
                if (at == null) {
                    continue;
                }
                Frame frame = new Frame(name, kind, Math.max(1, columns), Math.max(1, rows), at[0], at[1], at[2]);
                // No two frames may want the same block.
                for (int[] block : blocksOf(frame)) {
                    if (!blocks.add(block[0] + "," + block[1] + "," + block[2])) {
                        problem(where + ": block (" + block[0] + ", " + block[1] + ", " + block[2]
                                + ") is also used by another frame");
                        break;
                    }
                }
                frames.add(frame);
            }
            return frames;
        }

        /** The (right, up, forward) block offsets a frame occupies, with the duplex body cells. */
        static List<int[]> blocksOf(Frame frame) {
            List<int[]> list = new ArrayList<>();
            for (int c = 0; c < frame.blockColumns(); c++) {
                if (frame.kind() == Kind.DUPLEX) {
                    for (dev.bwr.mod.panel.DuplexCell cell : dev.bwr.mod.panel.DuplexCell.values()) {
                        list.add(new int[] {frame.right() + c, frame.up() + cell.up(), frame.forward() - cell.back()});
                    }
                } else {
                    for (int r = 0; r < frame.rows(); r++) {
                        list.add(new int[] {frame.right() + c, frame.up() - r,
                                frame.forward() + (frame.kind() == Kind.BENCHBOARD ? r : 0)});
                    }
                }
            }
            return list;
        }

        // ---- components

        List<Comp> components(JsonObject root, Map<String, Surface> surfaces, Map<String, boolean[][]> taken) {
            List<Comp> out = new ArrayList<>();
            if (!root.has("components")) {
                return out;
            }
            if (!root.get("components").isJsonArray()) {
                problem("components must be a list");
                return out;
            }
            Set<String> labels = new HashSet<>();
            int index = 0;
            for (JsonElement element : root.getAsJsonArray("components")) {
                String where = "components[" + index++ + "]";
                if (!element.isJsonObject()) {
                    problem(where + " is not an object");
                    continue;
                }
                component(element.getAsJsonObject(), where, surfaces, taken, labels, out);
                if (out.size() > MAX_COMPONENTS) {
                    problem("more than " + MAX_COMPONENTS + " components");
                    return out;
                }
            }
            return out;
        }

        void component(JsonObject object, String where, Map<String, Surface> surfaces, Map<String, boolean[][]> taken,
                       Set<String> labels, List<Comp> out) {
            String surfaceId = text(object, "on", where, "", 40);
            Surface surface = surfaces.get(surfaceId);
            if (surface == null) {
                problem(where + ": 'on' names no surface: '" + surfaceId + "' (surfaces: " + String.join(", ", surfaces.keySet()) + ")");
                return;
            }
            String typeName = text(object, "type", where, "", 64);
            ResourceLocation typeId = ResourceLocation.tryParse(typeName);
            ComponentType type = typeId == null ? null : ComponentTypes.get(typeId);
            if (type == null) {
                problem(where + ": unknown component type '" + typeName + "'");
                return;
            }
            if (type.item() == net.minecraft.world.item.Items.AIR) {
                problem(where + ": component type " + typeName + " has no item");
                return;
            }
            int[] at = ints(object, "at", where, 2, null);
            if (at == null) {
                if (!object.has("at")) {
                    problem(where + ": 'at' [x, y] is missing");
                }
                return;
            }
            if (!object.has("size")) {
                problem(where + ": 'size' [w, h] is missing; every component states its footprint, so a change of a type's "
                        + "default size never changes a board");
                return;
            }
            int[] given = ints(object, "size", where, 2, null);
            if (given == null) {
                return;
            }
            if (given[0] < 1 || given[1] < 1 || given[0] > Footprint.MAX_CELLS || given[1] > Footprint.MAX_CELLS) {
                problem(where + ": size " + given[0] + "x" + given[1] + " is out of range");
                return;
            }
            Footprint size = Footprint.of(given[0], given[1]);
            if (!sizeKnown(type, size)) {
                problem(where + ": " + typeName + " has no size " + size + " (sizes: " + type.footprints() + ")");
                return;
            }
            String labelTemplate = text(object, "label", where, "", 64);
            String captionTemplate = text(object, "caption", where, "", 64);
            JsonObject config = object.has("config") && object.get("config").isJsonObject() ? object.getAsJsonObject("config") : null;
            if (object.has("config") && config == null) {
                problem(where + ": config must be an object");
                return;
            }
            // repeat: count, or cols x rows, with a step in cells
            int cols = 1;
            int rows = 1;
            int[] step = {0, 0};
            if (object.has("repeat")) {
                if (!object.get("repeat").isJsonObject()) {
                    problem(where + ": repeat must be an object");
                    return;
                }
                JsonObject repeat = object.getAsJsonObject("repeat");
                if (repeat.has("count")) {
                    cols = integer(repeat, "count", where + " repeat", 1);
                } else {
                    cols = integer(repeat, "cols", where + " repeat", 1);
                    rows = integer(repeat, "rows", where + " repeat", 1);
                }
                step = ints(repeat, "step", where + " repeat", 2, new int[] {size.w(), 0});
                if (step == null) {
                    return;
                }
                if (cols < 1 || rows < 1 || (long) cols * rows > 1024) {
                    problem(where + ": repeat must make 1 to 1024 copies");
                    return;
                }
            }
            List<String> names = new ArrayList<>();
            if (object.has("names")) {
                if (!object.get("names").isJsonArray()) {
                    problem(where + ": names must be a list of strings");
                    return;
                }
                for (JsonElement name : object.getAsJsonArray("names")) {
                    names.add(name.isJsonPrimitive() ? name.getAsString() : name.toString());
                }
                if (names.size() != cols * rows) {
                    problem(where + ": names has " + names.size() + " entries for " + cols * rows + " copies");
                    return;
                }
            }
            int copies = cols * rows;
            for (int n = 0; n < copies; n++) {
                int col = n % cols;
                int row = n / cols;
                String name = names.isEmpty() ? "" : names.get(n);
                String here = copies == 1 ? where : where + "#" + (n + 1);
                int x = at[0] + col * step[0];
                int y = at[1] + row * step[1];
                String label = expand(labelTemplate, n, col, row, name);
                String caption = expand(captionTemplate, n, col, row, name);
                CompoundTag tag = config == null ? new CompoundTag() : config(type, config, here, n, col, row, name);
                if (tag == null) {
                    return;
                }
                if (!place(here, surface, type, x, y, size, taken.get(surface.id()))) {
                    continue;
                }
                if (!label.isEmpty()) {
                    if (label.length() > limits.maxLabel()) {
                        problem(here + ": label '" + label + "' is longer than " + limits.maxLabel() + " characters");
                    } else if (!label.equals(ConfigSchema.cleanText(label, limits.maxLabel()).strip()) || label.isBlank()) {
                        problem(here + ": label '" + label + "' has characters a label cannot hold");
                    } else if (!labels.add(label)) {
                        problem(here + ": label '" + label + "' is used by another component");
                    }
                }
                if (caption.length() > dev.bwr.mod.panel.MachineNames.MAX_CAPTION) {
                    problem(here + ": caption is longer than " + dev.bwr.mod.panel.MachineNames.MAX_CAPTION + " characters");
                }
                out.add(new Comp(surfaceId, type, x, y, size.w(), size.h(), tag, label, caption));
            }
        }

        /** Marks the cells and checks the component lies on the surface, within the span, and over nothing else. */
        boolean place(String where, Surface surface, ComponentType type, int x, int y, Footprint size, boolean[][] taken) {
            if (x < 0 || y < 0 || x + size.w() > surface.width() || y + size.h() > surface.height()) {
                problem(where + ": " + type.id() + " " + size + " at " + x + "," + y + " is not inside " + surface.id()
                        + " (" + surface.width() + " x " + surface.height() + " cells)");
                return false;
            }
            int spanX = (x % PanelGeometry.GRID + size.w() + PanelGeometry.GRID - 1) / PanelGeometry.GRID;
            int spanY = (y % PanelGeometry.GRID + size.h() + PanelGeometry.GRID - 1) / PanelGeometry.GRID;
            if (spanX > type.maxSpanBlocks() || spanY > type.maxSpanBlocks()) {
                problem(where + ": " + type.id() + " " + size + " at " + x + "," + y + " spans " + spanX + " x " + spanY
                        + " blocks; " + type.id() + " may span " + type.maxSpanBlocks());
                return false;
            }
            for (int i = x; i < x + size.w(); i++) {
                for (int j = y; j < y + size.h(); j++) {
                    if (taken[i][j]) {
                        problem(where + ": " + type.id() + " " + size + " at " + x + "," + y + " on " + surface.id()
                                + " overlaps another component or a reserved space at " + i + "," + j);
                        return false;
                    }
                }
            }
            for (int i = x; i < x + size.w(); i++) {
                for (int j = y; j < y + size.h(); j++) {
                    taken[i][j] = true;
                }
            }
            return true;
        }

        CompoundTag config(ComponentType type, JsonObject config, String where, int n, int col, int row, String name) {
            Map<String, ConfigField> fields = new LinkedHashMap<>();
            for (ConfigField field : type.configSchema()) {
                fields.put(field.key(), field);
            }
            CompoundTag tag = new CompoundTag();
            boolean ok = true;
            for (Map.Entry<String, JsonElement> entry : config.entrySet()) {
                ConfigField field = fields.get(entry.getKey());
                if (field == null) {
                    problem(where + ": " + type.id() + " has no config field '" + entry.getKey() + "'");
                    ok = false;
                    continue;
                }
                if (!convert(field, entry.getValue(), tag, where, n, col, row, name)) {
                    ok = false;
                }
            }
            return ok ? tag : null;
        }

        boolean convert(ConfigField field, JsonElement value, CompoundTag tag, String where, int n, int col, int row, String name) {
            String key = field.key();
            String at = where + " config '" + key + "'";
            if (!value.isJsonPrimitive()) {
                problem(at + " must be a single value");
                return false;
            }
            JsonPrimitive primitive = value.getAsJsonPrimitive();
            switch (field.kind()) {
                case TEXT -> {
                    String text = expand(primitive.getAsString(), n, col, row, name);
                    if (text.length() > field.maxLength()) {
                        problem(at + " is " + text.length() + " characters, at most " + field.maxLength() + ": '" + text + "'");
                        return false;
                    }
                    if (!text.equals(ConfigSchema.cleanText(text, field.maxLength()))) {
                        problem(at + " has characters a text field cannot hold: '" + text + "'");
                        return false;
                    }
                    tag.putString(key, text);
                }
                case COLOR -> {
                    Integer rgb = colour(primitive);
                    if (rgb == null) {
                        problem(at + " must be a colour: a number or an \"RRGGBB\" string, not " + primitive);
                        return false;
                    }
                    tag.putInt(key, rgb);
                }
                case BOOL -> {
                    if (!primitive.isBoolean()) {
                        problem(at + " must be true or false");
                        return false;
                    }
                    tag.putBoolean(key, primitive.getAsBoolean());
                }
                case INT, SLIDER -> {
                    if (!primitive.isNumber() || primitive.getAsDouble() != Math.rint(primitive.getAsDouble())) {
                        problem(at + " must be a whole number");
                        return false;
                    }
                    long number = primitive.getAsLong();
                    if (number < field.min() || number > field.max()) {
                        problem(at + " is " + number + ", allowed " + (long) field.min() + " to " + (long) field.max());
                        return false;
                    }
                    tag.putInt(key, (int) number);
                }
                case DOUBLE -> {
                    if (!primitive.isNumber() || !Double.isFinite(primitive.getAsDouble())) {
                        problem(at + " must be a number");
                        return false;
                    }
                    double number = primitive.getAsDouble();
                    if (number < field.min() || number > field.max()) {
                        problem(at + " is " + number + ", allowed " + field.min() + " to " + field.max());
                        return false;
                    }
                    tag.putDouble(key, number);
                }
                case ENUM -> {
                    String text = primitive.getAsString();
                    if (!field.options().contains(text)) {
                        problem(at + " is '" + text + "', allowed: " + String.join(", ", field.options()));
                        return false;
                    }
                    tag.putString(key, text);
                }
            }
            return true;
        }

        // ---- reserved spaces

        List<Reserved> reserved(JsonObject root, Map<String, Surface> surfaces, Map<String, boolean[][]> taken) {
            List<Reserved> out = new ArrayList<>();
            if (!root.has("reserved")) {
                return out;
            }
            if (!root.get("reserved").isJsonArray()) {
                problem("reserved must be a list");
                return out;
            }
            int index = 0;
            for (JsonElement element : root.getAsJsonArray("reserved")) {
                String where = "reserved[" + index++ + "]";
                if (!element.isJsonObject()) {
                    problem(where + " is not an object");
                    continue;
                }
                JsonObject object = element.getAsJsonObject();
                Surface surface = surfaces.get(text(object, "on", where, "", 40));
                int[] at = ints(object, "at", where, 2, null);
                int[] size = ints(object, "size", where, 2, null);
                if (surface == null || at == null || size == null) {
                    problem(where + ": needs a surface ('on'), 'at' and 'size'");
                    continue;
                }
                if (size[0] < 1 || size[1] < 1 || at[0] < 0 || at[1] < 0 || at[0] + size[0] > surface.width()
                        || at[1] + size[1] > surface.height()) {
                    problem(where + ": the space is not inside " + surface.id());
                    continue;
                }
                boolean[][] grid = taken.get(surface.id());
                boolean clash = false;
                for (int i = at[0]; i < at[0] + size[0] && !clash; i++) {
                    for (int j = at[1]; j < at[1] + size[1]; j++) {
                        if (grid[i][j]) {
                            clash = true;
                            break;
                        }
                    }
                }
                if (clash) {
                    problem(where + ": the reserved space overlaps a component or another reserved space");
                    continue;
                }
                for (int i = at[0]; i < at[0] + size[0]; i++) {
                    for (int j = at[1]; j < at[1] + size[1]; j++) {
                        grid[i][j] = true;
                    }
                }
                out.add(new Reserved(surface.id(), at[0], at[1], size[0], size[1], text(object, "for", where, "", 64),
                        text(object, "note", where, "", 512)));
            }
            return out;
        }

        // ---- mimic paths

        List<Mimic> mimic(JsonObject root, Map<String, Surface> surfaces) {
            List<Mimic> out = new ArrayList<>();
            if (!root.has("mimic")) {
                return out;
            }
            if (!root.get("mimic").isJsonArray()) {
                problem("mimic must be a list");
                return out;
            }
            int index = 0;
            for (JsonElement element : root.getAsJsonArray("mimic")) {
                String where = "mimic[" + index++ + "]";
                if (!element.isJsonObject()) {
                    problem(where + " is not an object");
                    continue;
                }
                JsonObject object = element.getAsJsonObject();
                Surface surface = surfaces.get(text(object, "on", where, "", 40));
                if (surface == null) {
                    problem(where + ": 'on' names no surface");
                    continue;
                }
                if (!object.has("points") || !object.get("points").isJsonArray()) {
                    problem(where + ": points must be a list of [x, y]");
                    continue;
                }
                JsonArray list = object.getAsJsonArray("points");
                if (list.size() < 2 || list.size() > MimicOps.MAX_POINTS) {
                    problem(where + ": a path has 2 to " + MimicOps.MAX_POINTS + " points");
                    continue;
                }
                int[] points = new int[list.size() * 2];
                boolean ok = true;
                for (int i = 0; i < list.size() && ok; i++) {
                    JsonElement point = list.get(i);
                    if (!point.isJsonArray() || point.getAsJsonArray().size() != 2) {
                        problem(where + ": point " + (i + 1) + " is not [x, y]");
                        ok = false;
                        break;
                    }
                    for (int k = 0; k < 2; k++) {
                        JsonElement number = point.getAsJsonArray().get(k);
                        if (!number.isJsonPrimitive() || !number.getAsJsonPrimitive().isNumber()
                                || number.getAsDouble() != Math.rint(number.getAsDouble())) {
                            problem(where + ": point " + (i + 1) + " must be whole numbers");
                            ok = false;
                            break;
                        }
                        points[i * 2 + k] = number.getAsInt();
                    }
                    if (ok && (points[i * 2] < 0 || points[i * 2 + 1] < 0 || points[i * 2] > surface.width()
                            || points[i * 2 + 1] > surface.height())) {
                        problem(where + ": point " + (i + 1) + " is off " + surface.id());
                        ok = false;
                    }
                    if (ok && i > 0 && points[i * 2] == points[i * 2 - 2] && points[i * 2 + 1] == points[i * 2 - 1]) {
                        problem(where + ": points " + i + " and " + (i + 1) + " are the same spot");
                        ok = false;
                    }
                }
                if (!ok) {
                    continue;
                }
                CompoundTag style = new CompoundTag();
                if (object.has("style")) {
                    if (!object.get("style").isJsonObject()) {
                        problem(where + ": style must be an object");
                        continue;
                    }
                    Map<String, ConfigField> fields = new HashMap<>();
                    for (ConfigField field : MimicStyle.SCHEMA) {
                        fields.put(field.key(), field);
                    }
                    for (Map.Entry<String, JsonElement> entry : object.getAsJsonObject("style").entrySet()) {
                        ConfigField field = fields.get(entry.getKey());
                        if (field == null) {
                            problem(where + ": a mimic path has no style field '" + entry.getKey() + "'");
                            ok = false;
                        } else if (!convert(field, entry.getValue(), style, where + " style", 0, 0, 0, "")) {
                            ok = false;
                        }
                    }
                }
                if (ok) {
                    out.add(new Mimic(surface.id(), points, style));
                }
            }
            return out;
        }

        // ---- limits that depend on the whole template

        void perBlockLimits(List<Comp> components, Map<String, Surface> surfaces) {
            Map<String, Integer> perBlock = new HashMap<>();
            for (Comp comp : components) {
                Surface surface = surfaces.get(comp.surface());
                String block = surface.id() + "/" + comp.x() / PanelGeometry.GRID + "," + comp.y() / PanelGeometry.GRID;
                int count = perBlock.merge(block, 1, Integer::sum);
                if (count == limits.maxComponentsPerBlock() + 1) {
                    problem("more than " + limits.maxComponentsPerBlock() + " components are anchored in the block " + block
                            + " (maxComponentsPerBlock)");
                }
            }
        }

        // ---- small readers

        String text(JsonObject object, String key, String where, String fallback, int max) {
            if (!object.has(key)) {
                return fallback;
            }
            JsonElement element = object.get(key);
            if (!element.isJsonPrimitive()) {
                problem(where + ": '" + key + "' must be text");
                return fallback;
            }
            String text = element.getAsString();
            if (text.length() > max) {
                problem(where + ": '" + key + "' is longer than " + max + " characters");
                return text.substring(0, max);
            }
            return text;
        }

        int integer(JsonObject object, String key, String where, int fallback) {
            if (!object.has(key)) {
                return fallback;
            }
            JsonElement element = object.get(key);
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()
                    || element.getAsDouble() != Math.rint(element.getAsDouble())) {
                problem(where + ": '" + key + "' must be a whole number");
                return fallback;
            }
            return element.getAsInt();
        }

        /** A list of {@code count} whole numbers, the fallback when absent (null when there is none), or null with a problem. */
        int[] ints(JsonObject object, String key, String where, int count, int[] fallback) {
            if (!object.has(key)) {
                return fallback;
            }
            JsonElement element = object.get(key);
            if (!element.isJsonArray() || element.getAsJsonArray().size() != count) {
                problem(where + ": '" + key + "' must be a list of " + count + " whole numbers");
                return null;
            }
            int[] values = new int[count];
            for (int i = 0; i < count; i++) {
                JsonElement number = element.getAsJsonArray().get(i);
                if (!number.isJsonPrimitive() || !number.getAsJsonPrimitive().isNumber()
                        || number.getAsDouble() != Math.rint(number.getAsDouble())) {
                    problem(where + ": '" + key + "' must be a list of " + count + " whole numbers");
                    return null;
                }
                values[i] = number.getAsInt();
            }
            return values;
        }
    }

    /** The text with {n} {i} {col} {row} {name} filled in. */
    static String expand(String text, int n, int col, int row, String name) {
        if (text.indexOf('{') < 0) {
            return text;
        }
        return text.replace("{n}", Integer.toString(n + 1)).replace("{i}", Integer.toString(n))
                .replace("{col}", Integer.toString(col)).replace("{row}", Integer.toString(row)).replace("{name}", name);
    }

    /** A colour from a number or from "RRGGBB", "#RRGGBB" or "0xRRGGBB"; null when it is neither. */
    static Integer colour(JsonPrimitive primitive) {
        if (primitive.isNumber()) {
            double number = primitive.getAsDouble();
            return number == Math.rint(number) && number >= 0 && number <= 0xFFFFFF ? (int) number : null;
        }
        if (!primitive.isString()) {
            return null;
        }
        String text = primitive.getAsString().strip();
        if (text.startsWith("#")) {
            text = text.substring(1);
        } else if (text.toLowerCase(Locale.ROOT).startsWith("0x")) {
            text = text.substring(2);
        }
        if (text.length() != 6) {
            return null;
        }
        try {
            return Integer.parseInt(text, 16);
        } catch (NumberFormatException bad) {
            return null;
        }
    }

    private static String prettify(String path) {
        StringBuilder out = new StringBuilder();
        for (String word : path.split("[_/]")) {
            if (!word.isEmpty()) {
                out.append(out.length() > 0 ? " " : "").append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
            }
        }
        return out.toString();
    }
}
