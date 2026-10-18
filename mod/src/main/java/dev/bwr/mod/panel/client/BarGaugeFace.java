package dev.bwr.mod.panel.client;

import dev.bwr.mod.panel.component.GaugeData;
import dev.bwr.mod.panel.display.PanelFormat;
import dev.bwr.mod.panel.model.BakedPanelModel;
import dev.bwr.mod.panel.model.Legends;
import dev.bwr.mod.panel.model.Placement;

import java.util.ArrayList;
import java.util.List;

/**
 * The printed face of a bar gauge: everything the owner's CAD model (a black channel with a satin bezel and a sliding
 * pointer) does not have. It is worked out once per component revision and drawn around the model, as in the owner's
 * reference pictures:
 * <ul>
 *   <li>a cream scale strip on the left with a minor tick every tenth of a numbered step, the numbered major ticks,
 *       and the colour bands as filled strips behind the ticks;</li>
 *   <li>the numbers at "nice" steps (1, 2, 2.5, 3 or 5 times a power of ten, at most {@link #MAX_MAJORS} of them),
 *       placed beside the major ticks;</li>
 *   <li>the units written down the right hand side, one letter per line;</li>
 *   <li>the component's label as the caption under the gauge.</li>
 * </ul>
 * The pointer is the model's {@code needle} part, which slides from the foot of the channel (the scale minimum) up
 * the model's travel (the scale maximum); the ticks are laid out between those two points, so the pointer always
 * reads against them. Everything is placed through one {@link Placement}, so the face keeps its real proportions on
 * the sloped boards.
 */
public final class BarGaugeFace {
    static final int CASE = 0xA9ADB1;
    static final int FACE = 0xEDEBE0;
    public static final int INK = 0x141414;
    /** Largest number of numbered ticks on the scale. */
    public static final int MAX_MAJORS = 8;
    /** Rows above the scale, and rows kept under it for the caption. */
    private static final float TOP_PAD = 0.9f;
    private static final float BOTTOM_ZONE = 2.2f;
    /** Elevations (cells above the panel): case top, face, bands, ticks, text. The model's channel rises above all of them. */
    private static final float Z_CASE = 0.45f;
    private static final float Z_FACE = 0.5f;
    private static final float Z_BAND = 0.52f;
    private static final float Z_TICK = 0.56f;
    private static final float Z_TEXT = 0.6f;

    public final Placement place;
    private final GaugeData data;
    private final float w;
    private final float h;
    private final float rowAspect;
    /** Pointer centre at the scale minimum and maximum, model inches down from the top. */
    private final float lowY;
    private final float highY;
    /** Column where the scale zone ends (the bezel's outer edge) and where the tick zone begins, cells. */
    private final float tickRight;
    private final float tickLeft;
    private final float rightZone;

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
        this.rightZone = Math.max(1.4f, 0.2f * w);
        float availableH = (h - TOP_PAD - BOTTOM_ZONE) * rowAspect;
        float scale = Math.min(availableH / model.footprintH, (w - rightZone - 2.2f) / model.footprintW);
        this.place = Placement.of(scale, rowAspect, w - rightZone - model.footprintW * scale, TOP_PAD);
        this.tickRight = place.x(0.04f) - 0.04f;
        float leftZone = place.offsetX;
        this.tickLeft = tickRight - Math.max(0.9f, Math.min(1.6f, leftZone * 0.38f));
    }

    /** Placement for a bar gauge model of {@code w x h} cells (see the constructor): the model sits to the right of the scale. */
    public static Placement placement(BakedPanelModel model, GaugeData data, float w, float h, float rowAspect) {
        return new BarGaugeFace(model, data, w, h, rowAspect).place;
    }

    /** Row of the scale position {@code fraction} (0 minimum .. 1 maximum). */
    public float row(double fraction) {
        return place.y(lowY + (highY - lowY) * (float) Math.max(0.0, Math.min(1.0, fraction)));
    }

    /** Records the case, the scale face, the bands and the ticks. */
    public void emitStatic(QuadSink sink) {
        sink.box(0, 0, w, h, 0, Z_CASE, CASE, false);
        sink.rect(0.4f, 0.4f, w - 0.8f, h - 0.8f, Z_FACE, FACE, false);
        for (int i = 0; i < data.bandCount(); i++) {
            if (data.bandHigh[i] > data.bandLow[i]) {
                float top = row(data.fraction(data.bandHigh[i]));
                float bottom = row(data.fraction(data.bandLow[i]));
                if (bottom > top) {
                    sink.rect(tickLeft, top, tickRight - tickLeft, bottom - top, Z_BAND, data.bandColor[i], false);
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
            float thickness = (major ? 0.16f : 0.08f) / rowAspect;
            sink.rect(tickRight - length, y - thickness / 2.0f, length, thickness, Z_TICK, INK, false);
        }
    }

    /** The numerals, the vertical units and the caption, in cells. {@code caption} is the component's label. */
    public List<Legends.Item> legends(String caption) {
        List<Legends.Item> items = new ArrayList<>();
        float cap = Math.max(0.32f, 0.2f * place.scale);
        double step = majorStep();
        long first = (long) Math.ceil(data.min / step - 1.0e-9);
        long last = (long) Math.floor(data.max / step + 1.0e-9);
        for (long k = first; k <= last; k++) {
            double value = Math.round(k * step * 1.0e6) / 1.0e6;
            items.add(new Legends.Item(PanelFormat.formatNumber(value, ""), tickLeft - 0.3f, row(data.fraction(value)),
                    Z_TEXT, cap, 0.0f, BakedPanelModel.Align.RIGHT, INK, false, FACE));
        }
        String units = data.units;
        if (!units.isEmpty()) {
            float middle = (row(0.0) + row(1.0)) / 2.0f;
            float scaleLength = Math.abs(row(0.0) - row(1.0));
            float pitch = Math.min(cap * 1.9f, scaleLength / (units.length() + 1));
            float unitCap = Math.min(cap * 1.1f, pitch / 1.9f);
            float x = w - rightZone / 2.0f;
            float y = middle - pitch * (units.length() - 1) / 2.0f;
            for (int i = 0; i < units.length(); i++) {
                char c = units.charAt(i);
                if (c != ' ') {
                    items.add(new Legends.Item(String.valueOf(c), x, y + i * pitch, Z_TEXT, unitCap, 0.0f,
                            BakedPanelModel.Align.CENTER, INK, false, FACE));
                }
            }
        }
        if (!caption.isEmpty()) {
            // A caption of 6 pixel wide letters, kept inside the case.
            float fit = (w - 1.2f) * Legends.CAP_PIXELS / (6.0f * caption.length());
            items.add(new Legends.Item(caption, w / 2.0f, h - BOTTOM_ZONE / 2.0f - 0.2f, Z_TEXT, Math.min(0.6f, fit), 0.0f,
                    BakedPanelModel.Align.CENTER, INK, false, FACE));
        }
        return items;
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
