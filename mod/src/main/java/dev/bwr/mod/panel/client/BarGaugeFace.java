package dev.bwr.mod.panel.client;

import dev.bwr.mod.panel.component.GaugeData;
import dev.bwr.mod.panel.display.PanelFormat;
import dev.bwr.mod.panel.model.BakedPanelModel;
import dev.bwr.mod.panel.model.Legends;
import dev.bwr.mod.panel.model.Placement;

import java.util.ArrayList;
import java.util.List;

/**
 * The printed face and case of a bar gauge around its model (a narrow black channel with a grey triangular pointer
 * inside it), laid out like the owner's simulator picture (references/gauges/owner-bar-gauge-target-look.png). It is
 * worked out once per component revision. Top to bottom:
 * <ul>
 *   <li>a small white tag plate carrying the component's label, on the panel above the case;</li>
 *   <li>the grey case: a top cap, the white face between two thin side rails, and a bottom cap with a round dark
 *       screw head in its middle;</li>
 *   <li>on the face, from the left: the numbers (at "nice" steps, 1, 2, 2.5, 3 or 5 times a power of ten, at most
 *       {@link #MAX_MAJORS} of them), the ticks to their right (a minor tick every tenth of a numbered step, half as
 *       long, against the channel), the colour bands as a narrow strip behind the ticks, then the channel a little
 *       right of the middle, and right of the channel the units written down the face one letter per line.</li>
 * </ul>
 * The channel runs the height of the face. The pointer is the model's {@code needle} part, which slides from its
 * modelled place (the scale minimum) up the model's travel (the scale maximum); the ticks are laid out between those
 * two points, so the pointer always reads against them. Everything is placed through one {@link Placement}, so the
 * face keeps its real proportions on the sloped boards.
 */
public final class BarGaugeFace {
    static final int CASE = 0xBFC3C7;
    static final int FACE = 0xF6F5EE;
    /** The tag plate above the case. */
    static final int TAG = 0xE6E6E1;
    /** The screw head on the bottom cap: dark, but not {@link #INK}, which only the ticks are drawn in. */
    static final int SCREW = 0x2B2C2E;
    public static final int INK = 0x141414;
    /** Largest number of numbered ticks on the scale. */
    public static final int MAX_MAJORS = 8;
    /** Rows above the tag plate, and between it and the case. */
    private static final float TAG_TOP = 0.1f;
    private static final float TAG_GAP = 0.2f;
    /** Tag plate, top cap and bottom cap heights as shares of the component's real height (rows times the row aspect). */
    private static final float TAG_SHARE = 0.06f;
    private static final float CAP_TOP_SHARE = 0.075f;
    private static final float CAP_BOTTOM_SHARE = 0.085f;
    /** Where the channel's centre sits across the gauge, and the widest it may get, as shares of the width. */
    private static final float CHANNEL_CENTRE = 0.6f;
    private static final float CHANNEL_MAX = 0.16f;
    /** Columns between a number's right end and its tick. */
    private static final float NUMERAL_GAP = 0.25f;
    /** Height of the channel when a model does not say (inches above the panel). */
    private static final float DEFAULT_CHANNEL_TOP = 0.15f;

    public final Placement place;
    private final GaugeData data;
    private final float w;
    private final float h;
    private final float rowAspect;
    /** Pointer centre at the scale minimum and maximum, model inches down from the top. */
    private final float lowY;
    private final float highY;
    /** Rows: the tag plate's height, the case's top edge, the face's top and bottom edges. */
    private final float tagRows;
    private final float caseTop;
    private final float faceTop;
    private final float faceBottom;
    /** Columns: the side rails' width, the ticks' right end (the channel's left edge) and left end, the channel's right edge. */
    private final float rail;
    private final float tickRight;
    private final float tickLeft;
    private final float channelRight;
    /** Elevations (1/16 block): the face just under the channel's top, the rails, the caps and the tag plate above it. */
    private final float zFace;
    private final float zRail;
    private final float zCap;
    private final float zTag;

    public BarGaugeFace(BakedPanelModel model, GaugeData data, float w, float h, float rowAspect) {
        this.data = data;
        this.w = w;
        this.h = h;
        this.rowAspect = rowAspect;
        BakedPanelModel.Part needle = model.part("needle");
        float minY = Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        for (int f = 0; f < needle.faceCount; f++) {
            for (int c = 0; c < 4; c++) {
                float y = needle.corner(f, c, 1);
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
            }
        }
        this.lowY = (minY + maxY) / 2.0f;
        this.highY = lowY + needle.axisY * needle.travel;
        this.tagRows = Math.max(0.9f, Math.min(2.0f, TAG_SHARE * h * rowAspect)) / rowAspect;
        this.caseTop = TAG_TOP + tagRows + TAG_GAP;
        this.faceTop = caseTop + CAP_TOP_SHARE * h;
        this.faceBottom = h - CAP_BOTTOM_SHARE * h;
        float faceRows = faceBottom - faceTop;
        float scale = Math.min(faceRows * rowAspect / model.footprintH, CHANNEL_MAX * w / model.footprintW);
        float channelRows = model.footprintH * scale / rowAspect;
        this.place = Placement.of(scale, rowAspect, CHANNEL_CENTRE * w - model.footprintW * scale / 2.0f,
                faceTop + (faceRows - channelRows) / 2.0f);
        this.rail = Math.max(0.25f, 0.035f * w);
        this.tickRight = place.x(0.0f);
        this.tickLeft = tickRight - Math.max(0.8f, Math.min(2.2f, 0.14f * w));
        this.channelRight = place.x(model.footprintW);
        this.zFace = Math.max(0.1f, place.z(channelTop(model)) - 0.07f);
        this.zCap = zFace + Math.max(0.3f, 0.06f * w);
        this.zRail = (zFace + zCap) / 2.0f;
        this.zTag = zFace + 0.12f;
    }

    /** Placement for a bar gauge model of {@code w x h} cells (see the constructor): the model sits right of the scale. */
    public static Placement placement(BakedPanelModel model, GaugeData data, float w, float h, float rowAspect) {
        return new BarGaugeFace(model, data, w, h, rowAspect).place;
    }

    /** The top of the channel: the highest corner of every part but the pointer, model inches. */
    private static float channelTop(BakedPanelModel model) {
        float top = -Float.MAX_VALUE;
        for (int i = 0; i < model.partCount(); i++) {
            BakedPanelModel.Part part = model.part(i);
            if (part.role == BakedPanelModel.Role.NEEDLE) {
                continue;
            }
            for (int f = 0; f < part.faceCount; f++) {
                for (int c = 0; c < 4; c++) {
                    top = Math.max(top, part.corner(f, c, 2));
                }
            }
        }
        return top > 0.0f ? top : DEFAULT_CHANNEL_TOP;
    }

    /** Row of the scale position {@code fraction} (0 minimum .. 1 maximum). */
    public float row(double fraction) {
        return place.y(lowY + (highY - lowY) * (float) Math.max(0.0, Math.min(1.0, fraction)));
    }

    /** Records the tag plate, the case, the face, the bands and the ticks. */
    public void emitStatic(QuadSink sink) {
        float faceRows = faceBottom - faceTop;
        sink.box(0.12f * w, TAG_TOP, 0.76f * w, tagRows, 0.0f, zTag, TAG, false);
        sink.box(0.0f, caseTop, w, faceTop - caseTop, 0.0f, zCap, CASE, false);
        sink.box(0.0f, faceBottom, w, h - faceBottom, 0.0f, zCap, CASE, false);
        sink.box(0.0f, faceTop, rail, faceRows, 0.0f, zRail, CASE, false);
        sink.box(w - rail, faceTop, rail, faceRows, 0.0f, zRail, CASE, false);
        sink.rect(rail, faceTop, w - 2.0f * rail, faceRows, zFace, FACE, false);
        screw(sink, w / 2.0f, (faceBottom + h) / 2.0f, 0.2f * (h - faceBottom) * rowAspect, zCap + 0.02f);
        float zBand = zFace + 0.02f;
        float zTick = zFace + 0.04f;
        for (int i = 0; i < data.bandCount(); i++) {
            if (data.bandHigh[i] > data.bandLow[i]) {
                float top = row(data.fraction(data.bandHigh[i]));
                float bottom = row(data.fraction(data.bandLow[i]));
                if (bottom > top) {
                    sink.rect(tickLeft, top, tickRight - tickLeft, bottom - top, zBand, data.bandColor[i], false);
                }
            }
        }
        double step = majorStep();
        int subdivisions = subdivisions(step);
        double minor = step / subdivisions;
        long first = (long) Math.ceil(data.min / minor - 1.0e-9);
        long last = (long) Math.floor(data.max / minor + 1.0e-9);
        for (long k = first; k <= last; k++) {
            boolean major = k % subdivisions == 0;
            float y = row(data.fraction(k * minor));
            float length = major ? tickRight - tickLeft : (tickRight - tickLeft) * 0.5f;
            float thickness = (major ? Math.max(0.16f, 0.02f * w) : Math.max(0.08f, 0.011f * w)) / rowAspect;
            sink.rect(tickRight - length, y - thickness / 2.0f, length, thickness, zTick, INK, false);
        }
    }

    /** A round screw head of radius {@code radius} columns at {@code (x, y)}: a 16-gon in eight quads. */
    private void screw(QuadSink sink, float x, float y, float radius, float z) {
        float[] px = new float[17];
        float[] py = new float[17];
        for (int i = 0; i <= 16; i++) {
            double a = Math.PI * 2.0 * i / 16.0;
            px[i] = x + radius * (float) Math.cos(a);
            py[i] = y + radius / rowAspect * (float) Math.sin(a);
        }
        for (int i = 0; i < 16; i += 2) {
            sink.quad(x, y, z, px[i], py[i], z, px[i + 1], py[i + 1], z, px[i + 2], py[i + 2], z, SCREW, QuadSink.NORMAL_FACE, false);
        }
    }

    /** The numerals, the vertical units and the label on the tag plate, in cells. {@code caption} is the component's label. */
    public List<Legends.Item> legends(String caption) {
        List<Legends.Item> items = new ArrayList<>();
        float zText = zFace + 0.06f;
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
        float spacing = Math.abs(row(data.fraction(data.min + step)) - row(0.0)) * rowAspect;
        float cap = Math.max(0.3f, Math.min(0.075f * w, Math.min((tickLeft - NUMERAL_GAP - rail - 0.15f) / widest, 0.6f * spacing)));
        for (int i = 0; i < numerals.size(); i++) {
            double value = Math.round((first + i) * step * 1.0e6) / 1.0e6;
            items.add(new Legends.Item(numerals.get(i), tickLeft - NUMERAL_GAP, row(data.fraction(value)), zText, cap, 0.0f,
                    BakedPanelModel.Align.RIGHT, INK, false, FACE));
        }
        String units = data.units;
        if (!units.isEmpty()) {
            float middle = (row(0.0) + row(1.0)) / 2.0f;
            float scaleLength = Math.abs(row(0.0) - row(1.0)) * rowAspect;
            // As in the picture: the letters a little smaller than the numbers, about 1.7 letter heights apart.
            float pitch = Math.min(cap * 1.35f, scaleLength / (units.length() + 1));
            float room = w - rail - channelRight - 0.3f;
            float unitCap = Math.min(0.8f * cap, Math.min(pitch / 1.45f, room * 7.0f / 5.0f));
            float x = (channelRight + w - rail) / 2.0f;
            float y = middle - pitch / rowAspect * (units.length() - 1) / 2.0f;
            for (int i = 0; i < units.length(); i++) {
                char c = units.charAt(i);
                if (c != ' ') {
                    items.add(new Legends.Item(String.valueOf(c), x, y + i * pitch / rowAspect, zText, unitCap, 0.0f,
                            BakedPanelModel.Align.CENTER, INK, false, FACE));
                }
            }
        }
        if (!caption.isEmpty()) {
            float fit = (0.76f * w - 0.4f) / textCaps(caption);
            items.add(new Legends.Item(caption, w / 2.0f, TAG_TOP + tagRows / 2.0f, zTag, Math.min(0.55f * tagRows * rowAspect, fit),
                    0.0f, BakedPanelModel.Align.CENTER, INK, false, TAG));
        }
        return items;
    }

    /** Width of {@code text} in cap heights: the font's letters are 6 pixels apart and a cap is {@link Legends#CAP_PIXELS} high. */
    private static float textCaps(String text) {
        return (6.0f * text.length() - 1.0f) / Legends.CAP_PIXELS;
    }

    // ---- the scale

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

    /** Minor ticks per numbered step: 10, fewer when they would crowd each other (a tick spacing under 0.14 cell) or when the step is 2.5. */
    private int subdivisions(double step) {
        float scaleRows = Math.abs(row(1.0) - row(0.0));
        double steps = Math.max(1.0e-9, (data.max - data.min) / step);
        int base = Math.abs(step / Math.pow(10.0, Math.floor(Math.log10(step))) - 2.5) < 1.0e-9 ? 5 : 10;
        for (int sub : new int[] {base, 5, 2, 1}) {
            if (sub <= base && scaleRows * rowAspect / (steps * sub) >= 0.14f) {
                return sub;
            }
        }
        return 1;
    }
}
