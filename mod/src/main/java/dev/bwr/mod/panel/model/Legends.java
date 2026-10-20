package dev.bwr.mod.panel.model;

import dev.bwr.mod.panel.component.ControlSwitchData;
import dev.bwr.mod.panel.component.GaugeData;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.component.PanelStyles;
import dev.bwr.mod.panel.component.PushTurnData;
import dev.bwr.mod.panel.component.RotarySelectorData;
import dev.bwr.mod.panel.component.RpsTripData;
import dev.bwr.mod.panel.component.SetpointKnobData;
import dev.bwr.mod.panel.display.PanelFormat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Which text goes at which label anchor of a baked model, worked out once per component revision. Models only
 * mark where legends go; the game supplies the words so players can edit them (the component's label, the
 * control switch captions, the selector's position names, a meter's scale and units).
 *
 * <p>Anchor ids understood; anything else ({@code maker}, {@code top}, {@code lower_left}, {@code bottom} ...
 * printed text the game has no setting for) is not drawn:
 * <ul>
 *   <li>{@code caption}: the component's label; {@code legend} too on a pushbutton or light, whose plate has
 *       nothing else.</li>
 *   <li>Control switch: {@code right} the CLOSE caption (the grip turns clockwise to CLOSE), {@code left} the
 *       OPEN caption (TRIP on a breaker), {@code center}/{@code normal} the NORMAL caption.</li>
 *   <li>Setpoint knob: {@code min}, {@code mid} and {@code max} are 0, 50 and 100 (percent).</li>
 *   <li>Rotary selector: {@code pos1}..{@code pos8} the position names (only as many as it has positions).</li>
 *   <li>Key switch: OFF and ON at the two anchors its style table row names ({@code PanelStyles.KeyPose}).</li>
 *   <li>Meters: {@code units} the units; {@code scale0}, {@code scale1} ... one anchor per numeral from the low
 *       end of the scale to the high end (the end numerals of a strip meter are nudged half a cap height
 *       inward so the bezel does not cover them); or, when a model has no numbered anchors, a single
 *       {@code scale} anchor whose numerals are spread along the pointer's sweep or travel, or
 *       {@code scale_min} and {@code scale_max}, between which they are spread in a straight line.</li>
 * </ul>
 */
public final class Legends {
    private Legends() {
    }

    /** Pixels from baseline to top of a capital in the default font; the anchor's cap height maps onto this. */
    public static final float CAP_PIXELS = 7.0f;
    /** Numerals on a round dial and on an edgewise strip. */
    public static final int DIAL_NUMERALS = 5;
    public static final int STRIP_NUMERALS = 3;
    /** Most numbered scale anchors read ({@code scale0} .. {@code scale15}). */
    static final int MAX_NUMERALS = 16;

    /**
     * One piece of text to draw.
     *
     * @param x,y,z   anchor in component cells and elevation
     * @param capCells cap height in cells
     * @param rotation clockwise degrees
     * @param tilt     degrees the text leans back about the anchor's row line, for text printed on a surface that is
     *                 curved along its length (the bar gauge's face): the angle of the surface at the anchor, positive
     *                 where it faces up (above the crown of the curve), 0 for text lying flat on the panel
     */
    public record Item(String text, float x, float y, float z, float capCells, float rotation,
                       BakedPanelModel.Align align, int ink, boolean onLamp, int surfaceRgb, float tilt) {
        /** Text lying flat on the panel. */
        public Item(String text, float x, float y, float z, float capCells, float rotation,
                    BakedPanelModel.Align align, int ink, boolean onLamp, int surfaceRgb) {
            this(text, x, y, z, capCells, rotation, align, ink, onLamp, surfaceRgb, 0.0f);
        }
    }

    /** Choose ink from the actual cap colour and state, including player recolouring. */
    public static int lampInk(int capRgb, boolean lit) {
        int surface = lit ? capRgb : dev.bwr.mod.panel.client.QuadSink.shade(capRgb, 0.22f);
        return PanelModelParser.luminance(surface) > 0.45 ? PanelModelParser.INK_DARK : PanelModelParser.INK_LIGHT;
    }

    /** {@link #items(BakedPanelModel, Placement, String, PanelComponent, PanelStyles.KeyPose)} for a model that is not a key switch. */
    public static List<Item> items(BakedPanelModel model, Placement place, String type, PanelComponent component) {
        return items(model, place, type, component, null);
    }

    /** {@link #text(String, String, PanelComponent, PanelStyles.KeyPose)} for a model that is not a key switch. */
    public static String text(String type, String id, PanelComponent component) {
        return text(type, id, component, null);
    }

    /** All the text items of {@code model} on {@code component} (type path {@code type}). Never null. */
    public static List<Item> items(BakedPanelModel model, Placement place, String type, PanelComponent component,
                                   PanelStyles.KeyPose key) {
        List<Item> items = new ArrayList<>();
        boolean outer = component.data() instanceof RotarySelectorData selector && Detents.outerPair(model, selector.positions);
        for (int i = 0; i < model.labelCount(); i++) {
            BakedPanelModel.Label label = model.label(i);
            String id = label.id;
            if (outer && id.equals("pos2")) {
                continue; // a two position selector has no middle legend ...
            }
            if (outer && id.equals("pos3")) {
                id = "pos2"; // ... and its second name goes at the right
            }
            String text = text(type, id, component, key);
            if (text != null && !text.isEmpty()) {
                items.add(item(text, label, place, label.x, label.y, label.z));
            }
        }
        if (component.data() instanceof GaugeData data) {
            int numeralsFrom = items.size();
            BakedPanelModel.Part needle = needle(model);
            BakedPanelModel.Label min = model.label("scale_min");
            BakedPanelModel.Label max = model.label("scale_max");
            BakedPanelModel.Label scale = model.label("scale");
            boolean radial = needle != null && needle.hasPivot;
            int count = radial || type.equals("dial_gauge") ? DIAL_NUMERALS : STRIP_NUMERALS;
            int numbered = numberedScaleCount(model);
            if (numbered >= 2) {
                // scale0 .. scaleN-1: one anchor per numeral, low end first, each with its own alignment.
                for (int i = 0; i < numbered; i++) {
                    BakedPanelModel.Label label = model.label("scale" + i);
                    float t = i / (float) (numbered - 1);
                    float[] at = {label.x, label.y, label.z};
                    if (!radial && (i == 0 || i == numbered - 1)) {
                        insetEnd(model, label, numbered, at);
                    }
                    items.add(item(numeral(data, t), label, place, at[0], at[1], at[2]));
                }
            } else if (min != null && max != null) {
                for (int i = 0; i < count; i++) {
                    float t = i / (float) (count - 1);
                    items.add(item(numeral(data, t), min, place, lerp(min.x, max.x, t), lerp(min.y, max.y, t),
                            lerp(min.z, max.z, t)));
                }
            } else if (scale != null && needle != null) {
                for (int i = 0; i < count; i++) {
                    float t = i / (float) (count - 1);
                    float[] at = scalePoint(needle, scale, t, radial);
                    items.add(item(numeral(data, t), scale, place, at[0], at[1], at[2]));
                }
            }
            textSize(items, numeralsFrom, data.textFactor(), place, model);
        }
        return items;
    }

    /**
     * A meter's Text size ({@link GaugeScale}): every legend's cap height times {@code factor}. Smaller is always allowed;
     * bigger only as far as a legend stays inside the model's footprint and clear of every legend it was clear of at
     * the model's own size, the numerals (from {@code numeralsFrom}) all by the same amount so they stay one size. At
     * 100 % the model's sizes stand untouched.
     */
    static void textSize(List<Item> items, int numeralsFrom, float factor, Placement place, BakedPanelModel model) {
        if (factor == 1.0f || items.isEmpty()) {
            return;
        }
        int n = items.size();
        float[] grow = new float[n];
        java.util.Arrays.fill(grow, factor);
        if (factor > 1.0f) {
            float rowAspect = place.scale / place.rowScale;
            float[] footprint = {place.x(0.0f), place.y(0.0f), place.x(model.footprintW), place.y(model.footprintH)};
            float[] window = window(model, place);
            for (int i = 0; i < n; i++) {
                Item own = items.get(i);
                // Text printed on the scale stays inside the window (the bezel's lip covers its edge); anything else
                // (a caption on the case) inside the footprint.
                float[] bounds = window != null && inside(box(own, 1.0f, rowAspect), window) ? window : footprint;
                grow[i] = Math.min(grow[i], largest(factor, f -> inside(box(own, f, rowAspect), bounds)));
                for (int j = i + 1; j < n; j++) {
                    Item a = items.get(i);
                    Item b = items.get(j);
                    if (overlap(box(a, 1.0f, rowAspect), box(b, 1.0f, rowAspect))) {
                        continue; // the model drew them touching: not this setting's business
                    }
                    // Kept a narrow space apart, so "80" and "100" never run together into "80100".
                    float pair = largest(factor, f -> !overlap(pad(box(a, f, rowAspect), a, f), pad(box(b, f, rowAspect), b, f)));
                    grow[i] = Math.min(grow[i], pair);
                    grow[j] = Math.min(grow[j], pair);
                }
            }
            float numerals = factor;
            for (int i = numeralsFrom; i < n; i++) {
                numerals = Math.min(numerals, grow[i]);
            }
            for (int i = numeralsFrom; i < n; i++) {
                grow[i] = numerals;
            }
        }
        for (int i = 0; i < n; i++) {
            Item it = items.get(i);
            items.set(i, new Item(it.text(), it.x(), it.y(), it.z(), it.capCells() * grow[i], it.rotation(), it.align(),
                    it.ink(), it.onLamp(), it.surfaceRgb(), it.tilt()));
        }
    }

    /** How far inside the edge of a meter's window its text stays when it grows, model inches. */
    private static final float WINDOW_MARGIN = 0.05f;
    /** Half the space kept between two legends that grow, in cap heights. */
    private static final float SPACE = 0.3f;

    /** The box round a model's glass (its window), less {@link #WINDOW_MARGIN}, in component cells; null without glass. */
    private static float[] window(BakedPanelModel model, Placement place) {
        float[] out = {Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        boolean any = false;
        for (int p = 0; p < model.partCount(); p++) {
            BakedPanelModel.Part part = model.part(p);
            if (part.role != BakedPanelModel.Role.GLASS) {
                continue;
            }
            for (int f = 0; f < part.faceCount; f++) {
                for (int c = 0; c < 4; c++) {
                    out[0] = Math.min(out[0], part.corner(f, c, 0));
                    out[1] = Math.min(out[1], part.corner(f, c, 1));
                    out[2] = Math.max(out[2], part.corner(f, c, 0));
                    out[3] = Math.max(out[3], part.corner(f, c, 1));
                    any = true;
                }
            }
        }
        return any ? new float[] {place.x(out[0] + WINDOW_MARGIN), place.y(out[1] + WINDOW_MARGIN),
                place.x(out[2] - WINDOW_MARGIN), place.y(out[3] - WINDOW_MARGIN)} : null;
    }

    /** {@code box} grown by {@link #SPACE} of {@code item}'s cap height at scale {@code f} all round (rows as columns: a hair generous on a slope). */
    private static float[] pad(float[] box, Item item, double f) {
        float d = (float) (SPACE * item.capCells() * f);
        return new float[] {box[0] - d, box[1] - d, box[2] + d, box[3] + d};
    }

    /** The largest {@code f} in 1 .. {@code factor} for which {@code ok} holds, assuming it holds below some f and not above. */
    private static float largest(float factor, java.util.function.DoublePredicate ok) {
        if (ok.test(factor)) {
            return factor;
        }
        float low = 1.0f;
        float high = factor;
        for (int k = 0; k < 24; k++) {
            float mid = (low + high) / 2.0f;
            if (ok.test(mid)) {
                low = mid;
            } else {
                high = mid;
            }
        }
        return low;
    }

    /**
     * The box {@code item} covers, {x0, y0, x1, y1} in component columns and rows: its text ({@link GaugeScale#textCaps}
     * wide, a cap high, placed by its alignment and centred on its anchor's row, as the renderer draws it) turned by its
     * rotation, with its cap height times {@code scale}.
     */
    public static float[] box(Item item, double scale, float rowAspect) {
        float cap = (float) (item.capCells() * scale);
        float w = GaugeScale.textCaps(item.text()) * cap;
        float x0 = switch (item.align()) {
            case LEFT -> 0.0f;
            case CENTER -> -w / 2.0f;
            case RIGHT -> -w;
        };
        double r = Math.toRadians(item.rotation());
        float cos = (float) Math.cos(r);
        float sin = (float) Math.sin(r);
        float[] out = {Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (int c = 0; c < 4; c++) {
            float x = c == 0 || c == 3 ? x0 : x0 + w;
            float y = c < 2 ? -cap / 2.0f : cap / 2.0f;
            float px = item.x() + x * cos - y * sin;
            float py = item.y() + (x * sin + y * cos) / rowAspect;
            out[0] = Math.min(out[0], px);
            out[1] = Math.min(out[1], py);
            out[2] = Math.max(out[2], px);
            out[3] = Math.max(out[3], py);
        }
        return out;
    }

    /** Whether two {@link #box}es overlap (touching edges do not). */
    public static boolean overlap(float[] a, float[] b) {
        return a[0] < b[2] && b[0] < a[2] && a[1] < b[3] && b[1] < a[3];
    }

    /** Whether {@code box} lies inside {@code bounds} (same layout), allowing a hair for float noise. */
    public static boolean inside(float[] box, float[] bounds) {
        float eps = 1.0e-4f;
        return box[0] >= bounds[0] - eps && box[1] >= bounds[1] - eps && box[2] <= bounds[2] + eps && box[3] <= bounds[3] + eps;
    }

    /**
     * The legend for label {@code id} on this component, or null when the id means nothing for it (the maker's
     * name, "pull to lock" and the like are printed text of the model that the game has no setting for, so they
     * are simply not drawn). {@code key} gives the OFF and ON anchors of a key switch model.
     */
    public static String text(String type, String id, PanelComponent component, PanelStyles.KeyPose key) {
        String lower = id.toLowerCase(Locale.ROOT);
        if (lower.equals("caption")) {
            return component.label();
        }
        // A plate that has nothing but its legend (a pushbutton, an indicating light) shows the label there.
        if (lower.equals("legend") && (type.equals("pushbutton") || type.equals("lamp"))) {
            return component.label();
        }
        if (component.data() instanceof ControlSwitchData data) {
            // The grip turns clockwise to CLOSE, so CLOSE is the legend on the right and TRIP (OPEN) on the left.
            return switch (lower) {
                case "left" -> data.openText;
                case "right" -> data.closeText;
                case "center", "normal" -> data.normalText;
                default -> null;
            };
        }
        if (component.data() instanceof RotarySelectorData data) {
            int index = positionIndex(lower);
            return index >= 0 && index < data.positions ? data.nameAt(index) : null;
        }
        if (type.equals("key_switch") && key != null) {
            if (lower.equals(key.offLegend())) {
                return "OFF";
            }
            if (lower.equals(key.onLegend())) {
                return "ON";
            }
            return null;
        }
        if (component.data() instanceof GaugeData data && lower.equals("units")) {
            return data.units;
        }
        if (component.data() instanceof RpsTripData data) {
            // The two engraved legends of an RPS trip assembly, one over each button.
            return switch (lower) {
                case "legend_a" -> data.legendA;
                case "legend_b" -> data.legendB;
                default -> null;
            };
        }
        if (component.data() instanceof PushTurnData data) {
            // AUTO at the left, ARM at the right of a push-to-turn switch.
            return switch (lower) {
                case "pos1" -> data.legend1;
                case "pos2" -> data.legend2;
                default -> null;
            };
        }
        if (component.data() instanceof SetpointKnobData) {
            // The knob's scale is the 0 to 100 percent it outputs.
            return switch (lower) {
                case "min" -> "0";
                case "mid" -> "50";
                case "max" -> "100";
                default -> null;
            };
        }
        return null;
    }

    /** {@code pos3} to 2; -1 for anything else. */
    static int positionIndex(String id) {
        if (id.length() == 4 && id.startsWith("pos") && id.charAt(3) >= '1' && id.charAt(3) <= '8') {
            return id.charAt(3) - '1';
        }
        return -1;
    }

    /** Scale numeral at fraction {@code t} of the range. */
    public static String numeral(GaugeData data, float t) {
        return PanelFormat.formatNumber(data.min + (data.max - data.min) * t, "");
    }

    /**
     * Where the numeral at fraction {@code t} goes. Radial (a pivoted pointer): on the circle through the
     * anchor around the pointer's pivot, at the pointer angle for {@code t}. Straight (a sliding pointer): the
     * anchor is the low end and the numeral moves along the pointer's travel.
     */
    static float[] scalePoint(BakedPanelModel.Part needle, BakedPanelModel.Label scale, float t, boolean radial) {
        if (radial) {
            float dx = scale.x - needle.pivotX;
            float dy = scale.y - needle.pivotY;
            float radius = (float) Math.sqrt(dx * dx + dy * dy);
            double angle = Math.toRadians(Poses.needleAngle(t, Poses.DIAL_SWEEP_DEG));
            return new float[] {needle.pivotX + radius * (float) Math.sin(angle),
                    needle.pivotY - radius * (float) Math.cos(angle), scale.z};
        }
        float travel = needle.hasTravel ? needle.travel : 0.0f;
        return new float[] {scale.x + needle.axisX * travel * t, scale.y + needle.axisY * travel * t, scale.z};
    }

    /** How many consecutive {@code scale0}, {@code scale1} ... anchors the model has. */
    static int numberedScaleCount(BakedPanelModel model) {
        int count = 0;
        while (count < MAX_NUMERALS && model.label("scale" + count) != null) {
            count++;
        }
        return count;
    }

    /**
     * Moves an end numeral of a strip meter half a cap height toward the middle of the scale: the first and last
     * numerals sit against the ends of the window, where the bezel would otherwise cover part of them.
     */
    static void insetEnd(BakedPanelModel model, BakedPanelModel.Label end, int count, float[] at) {
        BakedPanelModel.Label first = model.label("scale0");
        BakedPanelModel.Label last = model.label("scale" + (count - 1));
        float dx = last.x - first.x;
        float dy = last.y - first.y;
        float length = (float) Math.sqrt(dx * dx + dy * dy);
        if (length < 1.0e-6f) {
            return;
        }
        float direction = end == first ? 1.0f : -1.0f;
        float inset = 0.5f * end.height * direction;
        at[0] += dx / length * inset;
        at[1] += dy / length * inset;
    }

    /** The pointer part of a meter model: the one with role needle, else the one called needle. */
    static BakedPanelModel.Part needle(BakedPanelModel model) {
        BakedPanelModel.Part byName = model.part("needle");
        if (byName != null) {
            return byName;
        }
        for (int i = 0; i < model.partCount(); i++) {
            if (model.part(i).role == BakedPanelModel.Role.NEEDLE) {
                return model.part(i);
            }
        }
        return null;
    }

    private static Item item(String text, BakedPanelModel.Label label, Placement place, float x, float y, float z) {
        return new Item(text, place.x(x), place.y(y), place.z(z), place.length(label.height), label.rotation,
                label.align, label.ink, label.onLamp, label.surfaceRgb);
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }
}
