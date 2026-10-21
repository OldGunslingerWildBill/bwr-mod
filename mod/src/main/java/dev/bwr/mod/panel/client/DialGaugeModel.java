package dev.bwr.mod.panel.client;

import dev.bwr.mod.panel.component.GaugeData;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.model.BakedPanelModel;
import dev.bwr.mod.panel.model.GaugeScale;
import dev.bwr.mod.panel.model.Legends;
import dev.bwr.mod.panel.model.ScaleNumbers;

import java.util.ArrayList;
import java.util.List;

/**
 * Round dial: a dark square bezel, a cream face with a 270 degree scale
 * (coloured bands, 21 ticks) recorded once per revision, and a needle that
 * follows the input with the configured lag. No signal pegs the needle at the
 * low end and shows a red OFF flag; the min, max and units text draws only
 * close up, inside the face (the red flag sits above the hub, clear of it).
 *
 * <p>Every size is a share of the face's radius ({@link GaugeScale}), so a real size 2 x 2 dial is the 12 x 12 one
 * scaled down; the wrench's Text size and Tick size multiply the text and the ticks.
 */
final class DialGaugeModel implements ComponentModel {
    private static final float START = (float) Math.toRadians(-135.0);
    private static final float SWEEP = (float) Math.toRadians(270.0);
    private static final int BEZEL = 0x23282C;
    private static final int RIM = 0x14181B;
    private static final int FACE = 0xE9E5D4;
    private static final int INK = 0x202020;
    private static final int NEEDLE = 0xD03010;
    private static final int FLAG = 0xC02020;
    /** Font pixels per face radius of the labels (a cap is 7 of them: about a quarter of the radius). */
    private static final float TEXT = 0.035f;
    /**
     * Where the labels go, in face radii from the centre (y down). The units sit under the hub from {@link #UNITS_TOP}.
     * The minimum and the maximum sit side by side in the gap of the scale (the bottom 90 degrees), under the units: the
     * only part of the face the bands and ticks leave free. Their top corners stay {@link #WEDGE_MARGIN} inside the
     * gap's edges (where the needle lies at the ends of the scale), and every corner of every piece of text stays
     * inside {@link #FIT_RADIUS}, a little inside the face's rim, so nothing reaches the black bezel (the units, inside
     * {@link #UNITS_RADIUS}, the inner ring of the ticks).
     */
    private static final float UNITS_TOP = 0.20f;
    private static final float LABELS_TOP = 0.30f;
    private static final float ROW_GAP = 0.04f;
    private static final float WEDGE_MARGIN = 0.04f;
    private static final float FIT_RADIUS = 0.95f;
    /** The units stay inside the ticks' inner ring, so they never touch the scale's ends. */
    private static final float UNITS_RADIUS = 0.56f;
    /** The gap between the end labels, in cap heights (a bit under a digit's width), so "0" and "100" do not read as "0100". */
    private static final float LABEL_GAP_CAPS = 0.7f;
    /** Tick ring, face radii: the outer end of every tick, and the inner ends of the minor and major ticks. */
    private static final float TICK_OUTER = 0.76f;
    private static final float MINOR_INNER = 0.66f;
    private static final float MAJOR_INNER = 0.56f;
    /** The furthest in a longer tick may reach, face radii (clear of the hub and the units). */
    private static final float TICK_INNERMOST = 0.42f;

    private static float face(PanelComponent component) {
        return Math.min(component.w(), component.h()) / 2.0f * 0.9f;
    }

    @Override
    public void buildStatic(QuadSink sink, PanelComponent component) {
        GaugeData data = (GaugeData) component.data();
        float w = component.w();
        float h = component.h();
        float cx = w / 2.0f;
        float cy = h / 2.0f;
        float radius = Math.min(w, h) / 2.0f;
        float face = radius * 0.9f;
        sink.box(0, 0, w, h, 0, 1.0f, BEZEL, false);
        Shapes.disc(sink, cx, cy, radius * 0.97f, 1.02f, RIM, 32, false);
        Shapes.disc(sink, cx, cy, face, GaugeScale.CLASSIC_FACE_Z, FACE, 32, false);
        float step = (float) Math.toRadians(4.0);
        for (int i = 0; i < GaugeData.BANDS; i++) {
            if (data.bandHigh[i] > data.bandLow[i]) {
                float a0 = START + SWEEP * (float) data.fraction(data.bandLow[i]);
                float a1 = START + SWEEP * (float) data.fraction(data.bandHigh[i]);
                if (a1 > a0) {
                    Shapes.ring(sink, cx, cy, face * 0.80f, face * 0.96f, a0, a1, 1.1f, data.bandColor[i], step, false);
                }
            }
        }
        // Proportional throughout (the old 0.1 and 0.06 cell least widths only acted below the 12 x 12 size, where they
        // made a 2 x 2 dial's ticks two and a half times too fat). Tick size multiplies widths and lengths; a longer tick
        // grows inward, never past TICK_INNERMOST.
        float tick = data.tickFactor();
        float majorWidth = face * 0.04f * tick;
        float minorWidth = face * 0.022f * tick;
        float majorInner = TICK_OUTER - (TICK_OUTER - MAJOR_INNER)
                * GaugeScale.tickLengthFactor(TICK_OUTER - MAJOR_INNER, tick, TICK_OUTER - TICK_INNERMOST);
        float minorInner = TICK_OUTER - (TICK_OUTER - MINOR_INNER)
                * GaugeScale.tickLengthFactor(TICK_OUTER - MINOR_INNER, tick, TICK_OUTER - TICK_INNERMOST);
        for (int i = 0; i <= 20; i++) {
            float angle = START + SWEEP * i / 20.0f;
            boolean major = i % 5 == 0;
            Shapes.spoke(sink, cx, cy, face * (major ? majorInner : minorInner), face * TICK_OUTER, angle,
                    major ? majorWidth : minorWidth, 1.12f, INK, false);
        }
        Shapes.disc(sink, cx, cy, face * 0.1f, 1.4f, 0x303030, 12, false);
    }

    @Override
    public void renderDynamic(PanelRenderContext context, PanelComponent component) {
        GaugeData data = (GaugeData) component.data();
        GaugeScratch scratch = GaugeScratch.of(component);
        double value = component.input(0);
        float position = scratch.follow((float) data.fraction(value), data.damping, context.gameTime());
        float w = component.w();
        float h = component.h();
        float cx = w / 2.0f;
        float cy = h / 2.0f;
        float face = face(component);
        float angle = START + SWEEP * position;
        Shapes.spoke(context, cx, cy, -face * 0.16f, face * 0.74f, angle, face * 0.055f, 1.3f, NEEDLE, false);
        boolean offScale = GaugeData.offScale(value);
        if (offScale) {
            context.rect(cx - face * 0.26f, cy - face * 0.46f, face * 0.52f, face * 0.2f, 1.2f, FLAG, false);
        }
        if (context.lod() < PanelRenderContext.LOD_FULL) {
            return;
        }
        scratch.drawText(context, component, this);
        if (offScale) {
            float scale = face * TEXT * 0.8f;
            context.text("OFF", cx - context.font().width("OFF") * scale / 2.0f, cy - face * 0.425f, scale, 1.25f,
                    0xFFFFFF, false);
        }
    }

    /**
     * The minimum and maximum side by side in the gap of the scale and the units above them, under the hub: a cap of a
     * quarter of the face radius times Text size, shrunk to the largest size at which all of it lies inside the face
     * (see {@link #arrange}), so at any Text size and range none of it reaches the bezel.
     */
    @Override
    public List<Legends.Item> textLayout(PanelComponent component, float rowAspect) {
        GaugeData data = (GaugeData) component.data();
        float face = face(component);
        float cx = component.w() / 2.0f;
        float cy = component.h() / 2.0f;
        boolean units = !data.units.isEmpty();
        float unitsCaps = units ? GaugeScale.textCaps(data.units) : 0.0f;
        // The scale (cells per font pixel) of a cap of c face radii is c * face / CAP_PIXELS.
        float asked = Legends.CAP_PIXELS * TEXT * data.textFactor();
        // Full numbers when both fit at the size asked, else k and M (ScaleNumbers).
        List<String> ends = ScaleNumbers.labels(new double[] {data.min, data.max}, strings ->
                arrange(asked, GaugeScale.textCaps(strings.get(0)), GaugeScale.textCaps(strings.get(1)), unitsCaps, units) != null);
        String min = ends.get(0);
        String max = ends.get(1);
        float minCaps = GaugeScale.textCaps(min);
        float maxCaps = GaugeScale.textCaps(max);
        float low = 0.0f;
        float high = asked;
        if (arrange(high, minCaps, maxCaps, unitsCaps, units) == null) {
            for (int i = 0; i < 24; i++) {
                float mid = (low + high) / 2.0f;
                if (arrange(mid, minCaps, maxCaps, unitsCaps, units) != null) {
                    low = mid;
                } else {
                    high = mid;
                }
            }
        } else {
            low = high;
        }
        float scale = GaugeScale.textCap(face * TEXT, data.textFactor(), low * face / Legends.CAP_PIXELS);
        float cap = Legends.CAP_PIXELS * scale;
        // Laid out at the cap that is drawn (the floor of textCap may be a little above what fits).
        float[] layout = arrange(cap / face, minCaps, maxCaps, unitsCaps, units);
        if (layout == null) {
            layout = place(cap / face, minCaps, maxCaps, units);
        }
        float half = cap / 2.0f;
        List<Legends.Item> items = new ArrayList<>();
        items.add(new Legends.Item(min, cx - face * layout[0], cy + face * layout[1] + half, GaugeScale.CLASSIC_TEXT_Z, cap, 0.0f,
                BakedPanelModel.Align.LEFT, INK, false, FACE));
        items.add(new Legends.Item(max, cx + face * layout[0], cy + face * layout[1] + half, GaugeScale.CLASSIC_TEXT_Z, cap, 0.0f,
                BakedPanelModel.Align.RIGHT, INK, false, FACE));
        if (units) {
            items.add(new Legends.Item(data.units, cx, cy + face * UNITS_TOP + half, GaugeScale.CLASSIC_TEXT_Z, cap, 0.0f,
                    BakedPanelModel.Align.CENTER, INK, false, FACE));
        }
        return items;
    }

    /**
     * Where the text goes for a cap of {@code cap} face radii, the width of each piece in caps: {x of the end labels'
     * outer ends from the centre, top of the end labels' row}, both in face radii; or null if some corner of some piece
     * then lies outside {@link #FIT_RADIUS}.
     */
    private static float[] arrange(float cap, float minCaps, float maxCaps, float unitsCaps, boolean units) {
        float[] layout = place(cap, minCaps, maxCaps, units);
        if (!within(FIT_RADIUS, layout[0], layout[1] + cap) || (units && !within(UNITS_RADIUS, unitsCaps * cap / 2.0f, UNITS_TOP + cap))) {
            return null;
        }
        return layout;
    }

    /** The layout of {@link #arrange}, whether or not it fits. */
    private static float[] place(float cap, float minCaps, float maxCaps, boolean units) {
        float half = (minCaps + maxCaps + LABEL_GAP_CAPS) * cap / 2.0f;
        float top = Math.max(units ? UNITS_TOP + cap + ROW_GAP : LABELS_TOP, half + WEDGE_MARGIN);
        return new float[] {half, top};
    }

    private static boolean within(float radius, float x, float y) {
        return x * x + y * y <= radius * radius;
    }
}
