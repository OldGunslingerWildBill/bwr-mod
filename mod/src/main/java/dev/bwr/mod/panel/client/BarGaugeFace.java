package dev.bwr.mod.panel.client;

import dev.bwr.mod.panel.component.GaugeData;
import dev.bwr.mod.panel.display.PanelFormat;
import dev.bwr.mod.panel.model.BakedPanelModel;
import dev.bwr.mod.panel.model.GaugeScale;
import dev.bwr.mod.panel.model.Legends;
import dev.bwr.mod.panel.model.Placement;

import java.util.ArrayList;
import java.util.List;

/**
 * The layout of a bar gauge and the scale printed on its curved face, worked out once per component revision.
 *
 * <h3>The gauge</h3>
 * Two models built from the owner's CAD part (art/models/panel/imported/build.py, bar-gauge.obj) and laid out like the
 * owner's simulator picture (references/gauges/owner-bar-gauge-target-look.png):
 * <ul>
 *   <li>{@value #CASE_MODEL}: the 5 x 20 cm case, its white face curved along its length on a circular arc about an
 *       axis 14 cm behind the panel (radius 17.0 cm, standing 3 cm proud at the middle), raised side rails on the CAD
 *       rim's arc, grey top and bottom caps (a dark dot on the bottom one) and the tag plate above;</li>
 *   <li>{@code bar_gauge}, the component's own model: the narrow black channel, a little right of the middle, that fills
 *       the slot in the face and follows the arc, and the grey pointer in it pointing left at the scale.</li>
 * </ul>
 * The case keeps the CAD's proportions and is centred in the component (the panel shows either side of it, as in the
 * picture); the channel sits in it where the build put it. Both share one scale, so the arcs meet. The numbers here
 * are the build's, converted to the model files' inches; BarGaugeTests checks the shipped files against them.
 *
 * <h3>The scale</h3>
 * The pointer is modelled at the scale minimum, and the model's travel is how far it rises to the maximum as seen
 * head-on; {@link #row} is linear between the two, as the old flat gauge was and as the owner's picture is (its
 * numbers are evenly spaced). The game moves the pointer along the arc by turning it about the arc's axis
 * ({@link #pointerTurn}) through exactly the angle that puts its tip on that row. Everything printed is placed the
 * same way: a mark for scale position {@code f} at radius {@code r} sits at the angle whose height on the panel is
 * {@code row(f)}, lies on the arc's tangent there, and stands a little above the face so it does not fight it:
 * <ul>
 *   <li>ticks: a minor tick every tenth of a numbered step (fewer when they would crowd), majors twice as long, against
 *       the channel's left edge;</li>
 *   <li>the colour bands as strips behind the ticks, in pieces of at most {@value #MAX_BAND_STEP_DEG} degrees so they
 *       follow the curve;</li>
 *   <li>the numbers left of the ticks at "nice" steps (1, 2, 2.5, 3 or 5 times a power of ten, at most
 *       {@link #MAX_MAJORS} of them), and the units down the face right of the channel, one letter per line, each
 *       leaning back with the face ({@link Legends.Item#tilt()});</li>
 *   <li>the component's label on the tag plate.</li>
 * </ul>
 *
 * <h3>Sizes</h3>
 * Everything printed follows {@link GaugeScale}: the text is a fixed share of the case (its real cap heights, shrunk to
 * the room on the face and the spacing of the numbers), the ticks' least thickness and crowding limit shrink with the
 * gauge below {@value #FULL_SIZE_CELLS} cells ({@link #sizeFactor}), and the wrench's Text size and Tick size multiply
 * both. Longer ticks push the numbers left rather than running into them.
 */
public final class BarGaugeFace {
    /** The case model, drawn under the component's own (channel and pointer) model when it is loaded. */
    public static final String CASE_MODEL = "bar_gauge_case";
    static final int FACE = 0xF6F5EE;
    static final int TAG = 0xE6E6E1;
    /** Ticks and numbers; nothing else on the gauge is drawn in it. */
    public static final int INK = 0x141414;
    /** Largest number of numbered ticks on the scale. */
    public static final int MAX_MAJORS = 8;

    private static final float CM = 1.0f / 2.54f;
    // ---- the gauge as art/models/panel/imported/build.py builds it (centimetres there), in the models' inches
    /** The case model's footprint. */
    public static final float CASE_W = 5.0f * CM;
    public static final float CASE_FOOTPRINT_H = 21.05f * CM;
    /** Where the channel model's footprint corner sits on the case model's. */
    public static final float CHANNEL_X = 2.52f * CM;
    public static final float CHANNEL_Y = 2.90f * CM;
    /** The arc's axis on the case model (down from its footprint's top edge; elevation) and the face's radius. */
    public static final float ARC_Y = 11.05f * CM;
    public static final float ARC_Z = -14.0f * CM;
    public static final float FACE_R = (float) ((Math.sqrt(10.0 * 10.0 + 14.0 * 14.0) - 0.2) * CM);
    /** The face between the rails (case model x). */
    static final float FACE_LEFT = 0.4f * CM;
    static final float FACE_RIGHT = 4.6f * CM;
    /** The tag plate: across, down, and its top's elevation. */
    static final float TAG_X0 = 0.35f * CM;
    static final float TAG_X1 = 4.65f * CM;
    static final float TAG_Y0 = 0.0f;
    static final float TAG_Y1 = 0.8f * CM;
    static final float TAG_Z = 0.15f * CM;
    // ---- the printed scale, across the face (case model x): the numbers' right end, the left ends of the major ticks,
    // the bands and the minor ticks; all of them run to the channel's left edge.
    static final float NUMBERS_RIGHT = 1.70f * CM;
    public static final float TICK_LEFT = 1.80f * CM;
    static final float BAND_LEFT = 1.90f * CM;
    static final float MINOR_LEFT = 2.12f * CM;
    /**
     * Largest cap heights of the numbers and the units letters, and the units' letter pitch (the picture's). The caps are
     * raised over the first layout's (0.52 and 0.42 cm: numbers 12 %, units 19 %) because, measured on a screenshot of the
     * game against the owner's reference (references/gauges/owner-bar-gauge-text-proportions.png), a number came out 11 %
     * and a units letter 17 % shorter as a share of the case: the game's font draws its digits 5 x 7 pixels, narrower and
     * lower than the picture's. (CONTROL-PANELS.md, Gauge text size.)
     */
    private static final float NUMBER_CAP = 0.58f * CM;
    private static final float UNIT_CAP = 0.50f * CM;
    private static final float UNIT_PITCH = 0.83f * CM;
    /** The units letters' cap height is at most the letter pitch over this (the picture has it near 1.4; the first layout 1.75). */
    private static final float UNIT_PITCH_PER_CAP = 1.6f;
    /** Real thickness of the ticks (major, minor), and the least they may be in cells so they never vanish. */
    private static final float MAJOR_THICK = 0.11f * CM;
    private static final float MINOR_THICK = 0.055f * CM;
    private static final float MAJOR_MIN_CELLS = 0.12f;
    private static final float MINOR_MIN_CELLS = 0.07f;
    /** Closest minor ticks may stand, cells, before fewer are drawn. */
    private static final float MINOR_MIN_SPACING = 0.14f;
    /** A gauge this many cells tall or taller uses the cell minimums above as they are; a smaller one scales them down. */
    private static final float FULL_SIZE_CELLS = 16.0f;
    /** The longest a major tick may grow (Tick size), as a share of the face from the left rail to the channel. */
    private static final float MAX_TICK_SHARE = 0.55f;
    /** The room grown numbers keep from the left rail and from the ends of the face, inches. */
    private static final float GROWN_MARGIN = 0.25f * CM;
    /** Gap between the numbers' right end and the major ticks' left end, inches. */
    private static final float NUMBER_GAP = 0.10f * CM;
    /** How far the bands, the ticks and the text stand off the face, cells (the renderer lifts text a little more). */
    public static final float BAND_LIFT = 0.02f;
    public static final float TICK_LIFT = 0.04f;
    public static final float TEXT_LIFT = 0.06f;
    /** Most degrees of arc one quad of a band strip spans. */
    public static final double MAX_BAND_STEP_DEG = 2.0;

    /** The channel model's placement in the component (the pointer and everything printed are placed through it). */
    public final Placement place;
    /** The case model's placement: the CAD's proportions, centred in the component. */
    public final Placement casePlace;
    private final GaugeData data;
    private final float rowAspect;
    /** Pointer centre at the scale minimum and maximum, channel model inches down from its top. */
    private final float lowY;
    private final float highY;
    /** The arc's axis in the channel model (inches down; elevation). */
    private final float arcY;
    private final float arcZ;
    /** The pointer's tip: its distance from the arc's axis and its angle there as modelled (radians, up positive). */
    private final double tipRadius;
    private final double tipRest;
    /** The channel's edges, cells. */
    private final float channelLeft;
    private final float channelRight;
    /** The channel model's width, inches (the units are centred between the channel and the right rail). */
    private final float channelW;
    /** The channel model's length, inches: the face runs from its top to its bottom. */
    private final float channelH;

    public BarGaugeFace(BakedPanelModel model, GaugeData data, float w, float h, float rowAspect) {
        this.data = data;
        this.rowAspect = rowAspect;
        this.casePlace = Placement.fit(CASE_W, CASE_FOOTPRINT_H, w, h, rowAspect);
        this.place = Placement.of(casePlace.scale, rowAspect, casePlace.x(CHANNEL_X), casePlace.y(CHANNEL_Y));
        BakedPanelModel.Part needle = model.part("needle");
        float minY = Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        float tipX = Float.MAX_VALUE;
        float tipY = 0.0f;
        float tipZ = 0.0f;
        for (int f = 0; f < needle.faceCount; f++) {
            for (int c = 0; c < 4; c++) {
                float x = needle.corner(f, c, 0);
                float y = needle.corner(f, c, 1);
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
                if (x < tipX) {
                    tipX = x;
                    tipY = y;
                    tipZ = needle.corner(f, c, 2);
                }
            }
        }
        this.lowY = (minY + maxY) / 2.0f;
        this.highY = lowY + needle.axisY * needle.travel;
        this.arcY = ARC_Y - CHANNEL_Y;
        this.arcZ = ARC_Z;
        this.tipRadius = Math.hypot(tipY - arcY, tipZ - arcZ);
        this.tipRest = Math.atan2(arcY - tipY, tipZ - arcZ);
        this.channelW = model.footprintW;
        this.channelH = model.footprintH;
        this.channelLeft = place.x(0.0f);
        this.channelRight = place.x(model.footprintW);
    }

    // ---- the arc

    /** Row of the scale position {@code fraction} (0 minimum .. 1 maximum). */
    public float row(double fraction) {
        return place.y(inchesAt(fraction));
    }

    /** Channel model inches (down) of the scale position {@code fraction}, as seen head-on. */
    private float inchesAt(double fraction) {
        return lowY + (highY - lowY) * (float) Math.max(0.0, Math.min(1.0, fraction));
    }

    /** The angle on the arc of radius {@code radius} (inches) whose height is {@code inches}: radians, up positive. */
    private double angleAt(float inches, double radius) {
        return Math.asin(Math.max(-1.0, Math.min(1.0, (arcY - inches) / radius)));
    }

    /** The angle of scale position {@code fraction} on the arc of radius {@code radius}. */
    public double angle(double fraction, double radius) {
        return angleAt(inchesAt(fraction), radius);
    }

    /**
     * Degrees the pointer turns about the arc's axis from where it is modelled to read {@code fraction}: the turn that
     * puts its tip on {@link #row}. Positive turns it up the face (see {@code PartDraw.emitTurnedAboutX}).
     */
    public float pointerTurn(double fraction) {
        return (float) Math.toDegrees(angle(fraction, tipRadius) - tipRest);
    }

    /** The arc's axis in the channel model: inches down from its top, and elevation (behind the panel). */
    public float arcY() {
        return arcY;
    }

    public float arcZ() {
        return arcZ;
    }

    /** The face's radius plus a lift of {@code liftCells} cells, inches. */
    public double radius(float liftCells) {
        return FACE_R + liftCells / place.scale;
    }

    /** Row of the point at {@code angle} on the arc of radius {@code radius}. */
    public float rowOnArc(double radius, double angle) {
        return place.y((float) (arcY - radius * Math.sin(angle)));
    }

    /** Elevation (cells) of the point at {@code angle} on the arc of radius {@code radius}. */
    public float zOnArc(double radius, double angle) {
        return place.z((float) (arcZ + radius * Math.cos(angle)));
    }

    // ---- the printed scale

    /** Records the bands and the ticks on the face. */
    public void emitStatic(QuadSink sink) {
        float bandLeft = casePlace.x(BAND_LEFT);
        double bandRadius = radius(BAND_LIFT);
        for (int i = 0; i < data.bandCount(); i++) {
            if (data.bandHigh[i] > data.bandLow[i]) {
                double low = data.fraction(data.bandLow[i]);
                double high = data.fraction(data.bandHigh[i]);
                if (high > low) {
                    strip(sink, bandLeft, low, high, bandRadius, data.bandColor[i]);
                }
            }
        }
        double tickRadius = radius(TICK_LIFT);
        float lengthFactor = tickLengthFactor();
        float majorLeft = casePlace.x(CHANNEL_X - (CHANNEL_X - TICK_LEFT) * lengthFactor);
        float minorLeft = casePlace.x(CHANNEL_X - (CHANNEL_X - MINOR_LEFT) * lengthFactor);
        float tick = data.tickFactor();
        float majorHalf = Math.max(GaugeScale.least(MAJOR_MIN_CELLS, sizeFactor()), MAJOR_THICK * place.scale) * tick
                / place.scale / 2.0f;
        float minorHalf = Math.max(GaugeScale.least(MINOR_MIN_CELLS, sizeFactor()), MINOR_THICK * place.scale) * tick
                / place.scale / 2.0f;
        double step = majorStep();
        int subdivisions = subdivisions(step);
        double minor = step / subdivisions;
        long first = (long) Math.ceil(data.min / minor - 1.0e-9);
        long last = (long) Math.floor(data.max / minor + 1.0e-9);
        for (long k = first; k <= last; k++) {
            boolean major = k % subdivisions == 0;
            double centre = angle(data.fraction(k * minor), tickRadius);
            double half = (major ? majorHalf : minorHalf) / tickRadius;
            onArc(sink, major ? majorLeft : minorLeft, channelLeft, centre - half, centre + half, tickRadius, INK);
        }
    }

    /** A band from scale position {@code low} to {@code high}: quads of at most {@link #MAX_BAND_STEP_DEG} degrees each. */
    private void strip(QuadSink sink, float left, double low, double high, double radius, int rgb) {
        double from = angle(low, radius);
        double to = angle(high, radius);
        int pieces = Math.max(1, (int) Math.ceil((to - from) / Math.toRadians(MAX_BAND_STEP_DEG) - 1.0e-9));
        double previous = from;
        for (int i = 1; i <= pieces; i++) {
            double next = i == pieces ? to : from + (to - from) * i / pieces;
            onArc(sink, left, channelLeft, previous, next, radius, rgb);
            previous = next;
        }
    }

    /** One quad on the arc of radius {@code radius} from column {@code x0} to {@code x1} and from angle {@code a0} to {@code a1}. */
    private void onArc(QuadSink sink, float x0, float x1, double a0, double a1, double radius, int rgb) {
        float y0 = rowOnArc(radius, a0);
        float z0 = zOnArc(radius, a0);
        float y1 = rowOnArc(radius, a1);
        float z1 = zOnArc(radius, a1);
        double middle = (a0 + a1) / 2.0;
        sink.quad(x0, y0, z0, x1, y0, z0, x1, y1, z1, x0, y1, z1, rgb,
                0.0f, (float) -Math.sin(middle), (float) Math.cos(middle), false);
    }

    /** The numerals, the units down the face and the label on the tag plate, in cells. {@code caption} is the component's label. */
    public List<Legends.Item> legends(String caption) {
        List<Legends.Item> items = new ArrayList<>();
        double textRadius = radius(TEXT_LIFT);
        double step = majorStep();
        long first = (long) Math.ceil(data.min / step - 1.0e-9);
        long last = (long) Math.floor(data.max / step + 1.0e-9);
        List<String> numerals = new ArrayList<>();
        float widest = 1.0f;
        for (long k = first; k <= last; k++) {
            String text = PanelFormat.formatNumber(Math.round(k * step * 1.0e6) / 1.0e6, "");
            numerals.add(text);
            widest = Math.max(widest, textCaps(text));
        }
        // All in proportion to the case (GaugeScale): the picture's cap height times Text size, shrunk to the room left
        // of the ticks and to 0.6 of the spacing of the numbers, so they never leave the face or touch each other.
        float spacing = Math.abs(row(data.fraction(data.min + step)) - row(0.0)) * rowAspect;
        float right = numbersRight();
        float room = (right - FACE_LEFT - 0.08f * CM) * place.scale;
        float natural = NUMBER_CAP * place.scale;
        // Grown by Text size, the numbers also keep clear of the raised rail and of the caps at the ends of the face.
        float endRoom = Math.min(inchesAt(1.0), channelH - inchesAt(0.0));
        float grow = Math.min((right - FACE_LEFT - GROWN_MARGIN) / widest, 2.0f * (endRoom - GROWN_MARGIN)) * place.scale;
        float cap = GaugeScale.textCap(natural, data.textFactor(),
                Math.min(Math.min(room / widest, 0.6f * spacing), Math.max(natural, grow)));
        float numbersRight = casePlace.x(right);
        for (int i = 0; i < numerals.size(); i++) {
            double fraction = data.fraction(Math.round((first + i) * step * 1.0e6) / 1.0e6);
            double a = angle(fraction, textRadius);
            items.add(new Legends.Item(numerals.get(i), numbersRight, row(fraction), zOnArc(textRadius, a), cap, 0.0f,
                    BakedPanelModel.Align.RIGHT, INK, false, FACE, (float) Math.toDegrees(a)));
        }
        String units = data.units;
        if (!units.isEmpty()) {
            // As in the picture: one letter per line down the middle of the face right of the channel, about two letter
            // heights apart, centred on the scale's middle.
            float scaleLength = Math.abs(highY - lowY);
            float text = data.textFactor();
            float pitch = Math.min(UNIT_PITCH * Math.max(1.0f, text), scaleLength / (units.length() + 1));
            float roomRight = FACE_RIGHT - (CHANNEL_X + channelW) - 0.15f * CM;
            float letterCap = GaugeScale.textCap(UNIT_CAP, text, Math.min(pitch / UNIT_PITCH_PER_CAP, roomRight * 7.0f / 5.0f)) * place.scale;
            float x = casePlace.x((CHANNEL_X + channelW + FACE_RIGHT) / 2.0f);
            float middle = (lowY + highY) / 2.0f;
            for (int i = 0; i < units.length(); i++) {
                char c = units.charAt(i);
                if (c != ' ') {
                    float inches = middle + pitch * (i - (units.length() - 1) / 2.0f);
                    double a = angleAt(inches, textRadius);
                    items.add(new Legends.Item(String.valueOf(c), x, place.y(inches), zOnArc(textRadius, a), letterCap, 0.0f,
                            BakedPanelModel.Align.CENTER, INK, false, FACE, (float) Math.toDegrees(a)));
                }
            }
        }
        if (!caption.isEmpty()) {
            float tagW = TAG_X1 - TAG_X0;
            float tagH = TAG_Y1 - TAG_Y0;
            float fit = Math.min((tagW - 0.3f * CM) / textCaps(caption), 0.8f * tagH);
            items.add(new Legends.Item(caption, casePlace.x((TAG_X0 + TAG_X1) / 2.0f), casePlace.y((TAG_Y0 + TAG_Y1) / 2.0f),
                    casePlace.z(TAG_Z), casePlace.length(GaugeScale.textCap(0.55f * tagH, data.textFactor(), fit)), 0.0f,
                    BakedPanelModel.Align.CENTER, INK, false, TAG));
        }
        return items;
    }

    /**
     * Width of {@code text} in cap heights, every letter taken 6 pixels apart (a cap is {@link Legends#CAP_PIXELS} high).
     * A little wide for narrow letters, but it is what the gauge has always been laid out with, so the big gauges keep
     * their look ({@link GaugeScale#textCaps} has the font's real widths).
     */
    private static float textCaps(String text) {
        return (6.0f * text.length() - 1.0f) / Legends.CAP_PIXELS;
    }

    /**
     * How much longer than drawn the ticks are: the Tick size, but a major tick never reaches further left than
     * {@value #MAX_TICK_SHARE} of the way from the channel to the left rail.
     */
    private float tickLengthFactor() {
        return GaugeScale.tickLengthFactor(CHANNEL_X - TICK_LEFT, data.tickFactor(), MAX_TICK_SHARE * (CHANNEL_X - FACE_LEFT));
    }

    /** The numbers' right end (case model x): where the picture has it, or further left when longer ticks need the room. */
    private float numbersRight() {
        float majorLeft = CHANNEL_X - (CHANNEL_X - TICK_LEFT) * tickLengthFactor();
        return Math.min(NUMBERS_RIGHT, majorLeft - NUMBER_GAP);
    }

    /** The numbered step: 1, 2, 2.5, 3, 5 or 10 times a power of ten, giving at most {@link #MAX_MAJORS} numbered ticks. */
    public double majorStep() {
        return majorStep(data.min, data.max);
    }

    public static double majorStep(double min, double max) {
        double span = max > min ? max - min : 1.0;
        double raw = span / (MAX_MAJORS - 1.0);
        double magnitude = Math.pow(10.0, Math.floor(Math.log10(raw)));
        for (double multiple : new double[] {1.0, 2.0, 2.5, 3.0, 5.0, 10.0}) {
            double step = multiple * magnitude;
            if (step >= raw * (1.0 - 1.0e-9)) {
                return step;
            }
        }
        return 10.0 * magnitude;
    }

    /**
     * 1 for a gauge {@value #FULL_SIZE_CELLS} cells tall or more, less for a smaller one: the cell minimums of tick
     * thickness and spacing shrink with the gauge, so a real-size gauge is the big one scaled down, not fat ticks.
     */
    public float sizeFactor() {
        return GaugeScale.sizeFactor(casePlace.scale * CASE_FOOTPRINT_H, FULL_SIZE_CELLS);
    }

    /** Minor ticks per numbered step: 10, fewer when they would crowd each other (a tick spacing under 0.14 cell) or when the step is 2.5. */
    private int subdivisions(double step) {
        float scaleRows = Math.abs(row(1.0) - row(0.0));
        double steps = Math.max(1.0e-9, (data.max - data.min) / step);
        int base = Math.abs(step / Math.pow(10.0, Math.floor(Math.log10(step))) - 2.5) < 1.0e-9 ? 5 : 10;
        for (int sub : new int[] {base, 5, 2, 1}) {
            if (sub <= base && scaleRows * rowAspect / (steps * sub)
                    >= GaugeScale.least(MINOR_MIN_SPACING, sizeFactor()) * Math.max(1.0f, data.tickFactor())) {
                return sub;
            }
        }
        return 1;
    }
}
