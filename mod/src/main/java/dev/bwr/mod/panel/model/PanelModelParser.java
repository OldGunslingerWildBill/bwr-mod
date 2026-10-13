package dev.bwr.mod.panel.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Reads and validates the JSON written by {@code art/models/panel/panel_export.py} (format 1) into a
 * {@link BakedPanelModel}. Anything that does not match the format, or that lies outside the footprint, is
 * rejected with a {@link ModelFormatException} naming the problem: the loader logs it and skips the file, so a
 * bad model can never crash the client.
 *
 * <p>Limits (see the constants) bound the memory a hostile or broken resource pack can make us allocate; they
 * sit well above the modelling budget of 800 faces so legitimate models never come near them.
 */
public final class PanelModelParser {
    private PanelModelParser() {
    }

    /** The only format version understood. */
    public static final int FORMAT = 1;
    public static final int MAX_PARTS = 64;
    public static final int MAX_FACES = 4000;
    public static final int MAX_LABELS = 64;
    public static final int MAX_FILE_BYTES = 4 * 1024 * 1024;
    /** Largest footprint side, inches (a 4 block component is 4 * 16 cells; the real hardware is a few inches). */
    public static final float MAX_FOOTPRINT = 64.0f;
    /** Slack outside the footprint rectangle, inches. The exporter itself allows 0.0001. */
    public static final float BOUNDS_TOLERANCE = 0.02f;
    /** How far behind the face (z = 0) geometry may reach; the exporter allows 0.05. */
    public static final float MIN_Z = -0.06f;
    /** How far in front of the face geometry may reach, inches (a domed meter window bulges about 0.25). */
    public static final float MAX_Z = 8.0f;
    /** Largest slide, inches. */
    public static final float MAX_TRAVEL = 64.0f;

    /** Light legend ink for dark surfaces, dark ink for light ones. */
    static final int INK_LIGHT = 0xE8E8E8;
    static final int INK_DARK = 0x141414;

    /** Parses model text. */
    public static BakedPanelModel parse(String id, String json) {
        if (json.length() > MAX_FILE_BYTES) {
            throw new ModelFormatException(id + ": file larger than " + MAX_FILE_BYTES + " bytes");
        }
        JsonElement root;
        try {
            root = JsonParser.parseString(json);
        } catch (RuntimeException e) {
            throw new ModelFormatException(id + ": not valid JSON (" + e.getMessage() + ")");
        }
        if (!root.isJsonObject()) {
            throw new ModelFormatException(id + ": top level is not an object");
        }
        return parse(id, root.getAsJsonObject());
    }

    /** Parses an already-read document. */
    public static BakedPanelModel parse(String id, JsonObject root) {
        try {
            return parseChecked(id, root);
        } catch (ModelFormatException e) {
            throw e;
        } catch (RuntimeException e) {
            // Gson throws ClassCast / IllegalState / NumberFormat for the wrong JSON type in a field.
            throw new ModelFormatException(id + ": malformed (" + e.getClass().getSimpleName() + ": " + e.getMessage() + ")");
        }
    }

    private static BakedPanelModel parseChecked(String id, JsonObject root) {
        int format = intField(id, root, "format");
        if (format != FORMAT) {
            throw new ModelFormatException(id + ": unsupported format " + format + " (expected " + FORMAT + ")");
        }
        if (root.has("units") && !"inch".equals(string(id, root.get("units"), "units"))) {
            throw new ModelFormatException(id + ": units must be \"inch\"");
        }
        JsonObject footprint = object(id, root, "footprint");
        float fw = finite(id, footprint, "w", "footprint");
        float fh = finite(id, footprint, "h", "footprint");
        if (!(fw > 0 && fh > 0 && fw <= MAX_FOOTPRINT && fh <= MAX_FOOTPRINT)) {
            throw new ModelFormatException(id + ": footprint " + fw + " x " + fh + " must be positive and at most "
                    + MAX_FOOTPRINT + " inches");
        }
        String source = root.has("source") && root.get("source").isJsonPrimitive()
                ? root.get("source").getAsString() : "";

        JsonArray partArray = array(id, root, "parts");
        if (partArray.isEmpty()) {
            throw new ModelFormatException(id + ": no parts");
        }
        if (partArray.size() > MAX_PARTS) {
            throw new ModelFormatException(id + ": " + partArray.size() + " parts exceeds " + MAX_PARTS);
        }
        List<BakedPanelModel.Part> parts = new ArrayList<>();
        int faces = 0;
        for (int i = 0; i < partArray.size(); i++) {
            String where = "parts[" + i + "]";
            JsonObject partObject = asObject(id, partArray.get(i), where);
            BakedPanelModel.Part part = parsePart(id, where, partObject, fw, fh);
            faces += part.faceCount;
            if (faces > MAX_FACES) {
                throw new ModelFormatException(id + ": more than " + MAX_FACES + " faces");
            }
            parts.add(part);
        }
        if (faces == 0) {
            throw new ModelFormatException(id + ": no faces");
        }

        List<BakedPanelModel.Label> labels = new ArrayList<>();
        if (root.has("labels") && !root.get("labels").isJsonNull()) {
            JsonArray labelArray = array(id, root, "labels");
            if (labelArray.size() > MAX_LABELS) {
                throw new ModelFormatException(id + ": " + labelArray.size() + " labels exceeds " + MAX_LABELS);
            }
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < labelArray.size(); i++) {
                String where = "labels[" + i + "]";
                JsonObject labelObject = asObject(id, labelArray.get(i), where);
                BakedPanelModel.Label label = parseLabel(id, where, labelObject, fw, fh, parts);
                if (!seen.add(label.id)) {
                    throw new ModelFormatException(id + ": " + where + " repeats label id \"" + label.id + "\"");
                }
                labels.add(label);
            }
        }
        return new BakedPanelModel(id, source, fw, fh, parts.toArray(new BakedPanelModel.Part[0]),
                labels.toArray(new BakedPanelModel.Label[0]));
    }

    private static BakedPanelModel.Part parsePart(String id, String where, JsonObject o, float fw, float fh) {
        String name = o.has("name") && !o.get("name").isJsonNull() ? string(id, o.get("name"), where + ".name") : "body";
        if (name.isEmpty() || name.length() > 64) {
            throw new ModelFormatException(id + ": " + where + " has an empty or overlong name");
        }
        String roleName = o.has("role") && !o.get("role").isJsonNull() ? string(id, o.get("role"), where + ".role") : "paint";
        BakedPanelModel.Role role = BakedPanelModel.Role.parse(roleName);
        if (role == null) {
            throw new ModelFormatException(id + ": " + where + " has unknown role \"" + roleName + "\"");
        }
        String tint = o.has("tint") && !o.get("tint").isJsonNull() ? string(id, o.get("tint"), where + ".tint") : null;
        if (tint != null) {
            tint = tint.toLowerCase(Locale.ROOT);
            if (tint.isEmpty() || tint.length() > 32) {
                throw new ModelFormatException(id + ": " + where + " has an empty or overlong tint");
            }
        }
        float[] pivot = o.has("pivot") && !o.get("pivot").isJsonNull() ? vector(id, o.get("pivot"), where + ".pivot") : null;
        float[] axis = o.has("axis") && !o.get("axis").isJsonNull() ? vector(id, o.get("axis"), where + ".axis") : null;
        boolean hasTravel = o.has("travel") && !o.get("travel").isJsonNull();
        float travel = 0.0f;
        if (hasTravel) {
            travel = number(id, o.get("travel"), where + ".travel");
            if (Math.abs(travel) > MAX_TRAVEL) {
                throw new ModelFormatException(id + ": " + where + ".travel " + travel + " is out of range");
            }
        }
        if (pivot != null) {
            checkInside(id, where + ".pivot", pivot[0], pivot[1], pivot[2], fw, fh);
        }
        if (axis != null) {
            float length = (float) Math.sqrt(axis[0] * axis[0] + axis[1] * axis[1] + axis[2] * axis[2]);
            if (length < 1.0e-6f) {
                throw new ModelFormatException(id + ": " + where + ".axis has zero length");
            }
            axis = new float[] {axis[0] / length, axis[1] / length, axis[2] / length};
        } else if (pivot != null) {
            axis = new float[] {0.0f, 0.0f, 1.0f};
        } else if (hasTravel) {
            axis = new float[] {0.0f, 0.0f, -1.0f};
        } else {
            axis = new float[] {0.0f, 0.0f, 1.0f};
        }

        JsonArray faceArray = array(id, o, "faces", where);
        int count = faceArray.size();
        float[] positions = new float[count * 12];
        float[] normals = new float[count * 3];
        int[] colors = new int[count];
        float[] shades = new float[count];
        double[] luminance = new double[count];
        double lumSum = 0.0;
        for (int f = 0; f < count; f++) {
            String at = where + ".faces[" + f + "]";
            JsonObject face = asObject(id, faceArray.get(f), at);
            JsonArray verts = array(id, face, "v", at);
            if (verts.size() != 3 && verts.size() != 4) {
                throw new ModelFormatException(id + ": " + at + " has " + verts.size() + " vertices (3 or 4 expected)");
            }
            int base = f * 12;
            for (int v = 0; v < verts.size(); v++) {
                float[] p = vector(id, verts.get(v), at + ".v[" + v + "]");
                checkInside(id, at + ".v[" + v + "]", p[0], p[1], p[2], fw, fh);
                positions[base + v * 3] = p[0];
                positions[base + v * 3 + 1] = p[1];
                positions[base + v * 3 + 2] = p[2];
            }
            if (verts.size() == 3) {
                System.arraycopy(positions, base + 6, positions, base + 9, 3);
            }
            float[] normal = null;
            if (face.has("n") && !face.get("n").isJsonNull()) {
                normal = vector(id, face.get("n"), at + ".n");
            }
            normalise(positions, base, normal, normals, f * 3);
            int rgb = colour(id, face, at);
            colors[f] = rgb;
            luminance[f] = luminance(rgb);
            lumSum += luminance[f];
        }
        double mean = count == 0 ? 1.0 : Math.max(1.0e-3, lumSum / count);
        for (int f = 0; f < count; f++) {
            shades[f] = (float) Math.max(0.35, Math.min(1.4, luminance[f] / mean));
        }
        return new BakedPanelModel.Part(name, role, tint, pivot, axis, travel, hasTravel, positions, normals, colors, shades);
    }

    /** Writes the unit normal of face {@code f}: the given one, or one derived from the corners if it is missing or zero. */
    private static void normalise(float[] positions, int base, float[] given, float[] out, int at) {
        if (given != null) {
            float length = (float) Math.sqrt(given[0] * given[0] + given[1] * given[1] + given[2] * given[2]);
            if (length > 1.0e-4f) {
                out[at] = given[0] / length;
                out[at + 1] = given[1] / length;
                out[at + 2] = given[2] / length;
                return;
            }
        }
        // The corners are in y-down space, a mirror image of the modeller's, so the outward normal is the
        // negated cross product of two edges.
        float e1x = positions[base + 3] - positions[base];
        float e1y = positions[base + 4] - positions[base + 1];
        float e1z = positions[base + 5] - positions[base + 2];
        float e2x = positions[base + 6] - positions[base];
        float e2y = positions[base + 7] - positions[base + 1];
        float e2z = positions[base + 8] - positions[base + 2];
        float cx = -(e1y * e2z - e1z * e2y);
        float cy = -(e1z * e2x - e1x * e2z);
        float cz = -(e1x * e2y - e1y * e2x);
        float length = (float) Math.sqrt(cx * cx + cy * cy + cz * cz);
        if (length < 1.0e-9f) {
            out[at] = 0.0f;
            out[at + 1] = 0.0f;
            out[at + 2] = 1.0f; // a degenerate sliver: any normal will do
        } else {
            out[at] = cx / length;
            out[at + 1] = cy / length;
            out[at + 2] = cz / length;
        }
    }

    private static BakedPanelModel.Label parseLabel(String id, String where, JsonObject o, float fw, float fh,
                                                    List<BakedPanelModel.Part> parts) {
        String labelId = string(id, o.get("id"), where + ".id");
        if (labelId.isEmpty() || labelId.length() > 32) {
            throw new ModelFormatException(id + ": " + where + " has an empty or overlong id");
        }
        float[] pos = vector(id, o.get("pos"), where + ".pos");
        checkInside(id, where + ".pos", pos[0], pos[1], pos[2], fw, fh);
        float height = number(id, o.get("h"), where + ".h");
        if (!(height > 0.0f && height <= Math.max(fw, fh))) {
            throw new ModelFormatException(id + ": " + where + ".h " + height + " must be positive and within the footprint");
        }
        BakedPanelModel.Align align = BakedPanelModel.Align.CENTER;
        if (o.has("align") && !o.get("align").isJsonNull()) {
            String text = string(id, o.get("align"), where + ".align");
            align = BakedPanelModel.Align.parse(text);
            if (align == null) {
                throw new ModelFormatException(id + ": " + where + ".align \"" + text + "\" is not left, center or right");
            }
        }
        float rot = 0.0f;
        if (o.has("rot") && !o.get("rot").isJsonNull()) {
            rot = number(id, o.get("rot"), where + ".rot");
        }
        return new BakedPanelModel.Label(labelId, pos[0], pos[1], pos[2], height, align, rot,
                inkAt(parts, pos[0], pos[1], pos[2]));
    }

    // ---- legend colour: dark ink on a light surface, light ink on a dark one

    /**
     * The ink that reads on the front-most static surface under the anchor (x, y): the highest face that faces
     * the operator, contains the anchor in plan and is not above the anchor's own height. Light ink when nothing
     * is found (a legend printed straight onto the panel).
     */
    static int inkAt(List<BakedPanelModel.Part> parts, float x, float y, float z) {
        float bestZ = -Float.MAX_VALUE;
        int bestColor = -1;
        for (BakedPanelModel.Part part : parts) {
            if (part.moving() || part.role == BakedPanelModel.Role.GLASS) {
                continue;
            }
            for (int f = 0; f < part.faceCount; f++) {
                if (part.normal(f, 2) < 0.3f) {
                    continue;
                }
                float top = Math.max(Math.max(part.corner(f, 0, 2), part.corner(f, 1, 2)),
                        Math.max(part.corner(f, 2, 2), part.corner(f, 3, 2)));
                if (top > z + 0.04f || top < bestZ) {
                    continue;
                }
                if (inside(part, f, x, y)) {
                    bestZ = top;
                    bestColor = part.faceColor(f);
                }
            }
        }
        if (bestColor < 0) {
            return INK_LIGHT;
        }
        return luminance(bestColor) > 0.45 ? INK_DARK : INK_LIGHT;
    }

    private static boolean inside(BakedPanelModel.Part part, int face, float x, float y) {
        return inTriangle(part, face, 0, 1, 2, x, y) || inTriangle(part, face, 0, 2, 3, x, y);
    }

    private static boolean inTriangle(BakedPanelModel.Part part, int face, int a, int b, int c, float x, float y) {
        float ax = part.corner(face, a, 0);
        float ay = part.corner(face, a, 1);
        float bx = part.corner(face, b, 0);
        float by = part.corner(face, b, 1);
        float cx = part.corner(face, c, 0);
        float cy = part.corner(face, c, 1);
        float d1 = (x - bx) * (ay - by) - (ax - bx) * (y - by);
        float d2 = (x - cx) * (by - cy) - (bx - cx) * (y - cy);
        float d3 = (x - ax) * (cy - ay) - (cx - ax) * (y - ay);
        boolean negative = d1 < 0 || d2 < 0 || d3 < 0;
        boolean positive = d1 > 0 || d2 > 0 || d3 > 0;
        return !(negative && positive);
    }

    /** Relative luminance of an sRGB colour, 0..1 (good enough to choose between black and white ink). */
    static double luminance(int rgb) {
        double r = ((rgb >> 16) & 0xFF) / 255.0;
        double g = ((rgb >> 8) & 0xFF) / 255.0;
        double b = (rgb & 0xFF) / 255.0;
        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    }

    // ---- bounds

    private static void checkInside(String id, String where, float x, float y, float z, float fw, float fh) {
        float t = BOUNDS_TOLERANCE;
        if (x < -t || x > fw + t || y < -t || y > fh + t || z < MIN_Z || z > MAX_Z) {
            throw new ModelFormatException(id + ": " + where + " (" + x + ", " + y + ", " + z + ") is outside the "
                    + fw + " x " + fh + " inch footprint (z " + MIN_Z + " to " + MAX_Z + ")");
        }
    }

    // ---- JSON helpers: every type error becomes a ModelFormatException with a path

    private static JsonObject asObject(String id, JsonElement e, String where) {
        if (e == null || !e.isJsonObject()) {
            throw new ModelFormatException(id + ": " + where + " is not an object");
        }
        return e.getAsJsonObject();
    }

    private static JsonObject object(String id, JsonObject o, String key) {
        return asObject(id, o.get(key), key);
    }

    private static JsonArray array(String id, JsonObject o, String key) {
        return array(id, o, key, "");
    }

    private static JsonArray array(String id, JsonObject o, String key, String where) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonArray()) {
            throw new ModelFormatException(id + ": " + (where.isEmpty() ? "" : where + ".") + key + " is not an array");
        }
        return e.getAsJsonArray();
    }

    private static String string(String id, JsonElement e, String where) {
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
            throw new ModelFormatException(id + ": " + where + " is not a string");
        }
        return e.getAsString();
    }

    private static float number(String id, JsonElement e, String where) {
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
            throw new ModelFormatException(id + ": " + where + " is not a number");
        }
        double value = e.getAsDouble();
        if (!Double.isFinite(value) || Math.abs(value) > 1.0e6) {
            throw new ModelFormatException(id + ": " + where + " is not a usable number");
        }
        return (float) value;
    }

    private static int intField(String id, JsonObject o, String key) {
        JsonElement e = o.get(key);
        float value = number(id, e, key);
        if (value != Math.rint(value)) {
            throw new ModelFormatException(id + ": " + key + " is not an integer");
        }
        return (int) value;
    }

    private static float finite(String id, JsonObject o, String key, String where) {
        return number(id, o.get(key), where + "." + key);
    }

    private static float[] vector(String id, JsonElement e, String where) {
        if (e == null || !e.isJsonArray() || e.getAsJsonArray().size() != 3) {
            throw new ModelFormatException(id + ": " + where + " is not a list of 3 numbers");
        }
        JsonArray a = e.getAsJsonArray();
        return new float[] {number(id, a.get(0), where + "[0]"), number(id, a.get(1), where + "[1]"),
                number(id, a.get(2), where + "[2]")};
    }

    private static int colour(String id, JsonObject face, String at) {
        String text = string(id, face.get("c"), at + ".c");
        String hex = text.startsWith("#") ? text.substring(1) : text;
        if (hex.length() != 6) {
            throw new ModelFormatException(id + ": " + at + ".c \"" + text + "\" is not RRGGBB");
        }
        try {
            return Integer.parseInt(hex, 16);
        } catch (NumberFormatException e) {
            throw new ModelFormatException(id + ": " + at + ".c \"" + text + "\" is not RRGGBB");
        }
    }
}
