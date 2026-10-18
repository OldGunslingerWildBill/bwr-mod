package dev.bwr.mod.panel.client;

import dev.bwr.mod.panel.component.GaugeData;
import dev.bwr.mod.panel.model.BakedPanelModel;
import dev.bwr.mod.panel.model.Placement;
import dev.bwr.mod.panel.model.Poses;

/**
 * The colour bands of a baked switchboard meter (the GE 180 edgewise meters and the AB-40 dial). The classic
 * procedural meters have always drawn the bands of their {@link GaugeData}; the baked models carry the ticks and
 * the numerals but no zones, so the bands are laid under the ticks here, once per revision, on the cream scale:
 * a strip along the tick zone of a strip meter, an arc over the tick ring of the dial.
 *
 * <p>The geometry comes from the model: the numerals' anchors ({@code scale0} .. {@code scaleN}) give the ends of
 * the scale, and the pointer part gives the other side of the tick zone (a strip meter's pointer runs along its
 * ticks; the dial's pivot is the centre of the ring).
 */
public final class BandOverlay {
    /** Gap between the numerals and the first tick of a strip meter, and the tick ring's offset from the numerals' circle (inches). */
    private static final float NUMERAL_GAP = 0.06f;
    private static final float RING_INNER = 0.26f;
    private static final float RING_OUTER = 0.56f;
    /** Just above the cream face and under the printed ticks, inches. */
    private static final float BELOW_LABEL = 0.014f;
    private static final float ARC_STEP = (float) Math.toRadians(4.0);

    private final GaugeData data;
    private final Placement place;
    private final boolean dial;
    // strip meter: scale end points and the tick zone's cross extent, model inches
    private final boolean alongY;
    private final float lowAlong;
    private final float highAlong;
    private final float crossStart;
    private final float crossEnd;
    // dial: centre and ring radii
    private final float centreX;
    private final float centreY;
    private final float ringInner;
    private final float ringOuter;
    private final float z;

    private BandOverlay(GaugeData data, Placement place, boolean dial, boolean alongY, float lowAlong, float highAlong,
                        float crossStart, float crossEnd, float centreX, float centreY, float ringInner, float ringOuter, float z) {
        this.data = data;
        this.place = place;
        this.dial = dial;
        this.alongY = alongY;
        this.lowAlong = lowAlong;
        this.highAlong = highAlong;
        this.crossStart = crossStart;
        this.crossEnd = crossEnd;
        this.centreX = centreX;
        this.centreY = centreY;
        this.ringInner = ringInner;
        this.ringOuter = ringOuter;
        this.z = z;
    }

    /** The overlay for {@code data} on {@code model}, or null when no band is in use or the model has no numbered scale. */
    public static BandOverlay of(BakedPanelModel model, Placement place, GaugeData data, boolean dial) {
        boolean any = false;
        for (int i = 0; i < data.bandCount(); i++) {
            any |= data.bandHigh[i] > data.bandLow[i];
        }
        BakedPanelModel.Part needle = model.part("needle");
        BakedPanelModel.Label first = model.label("scale0");
        int count = 0;
        while (model.label("scale" + (count + 1)) != null) {
            count++;
        }
        if (!any || needle == null || first == null || count < 1) {
            return null;
        }
        BakedPanelModel.Label last = model.label("scale" + count);
        if (dial) {
            if (!needle.hasPivot) {
                return null;
            }
            float radius = (float) Math.hypot(first.x - needle.pivotX, first.y - needle.pivotY);
            return new BandOverlay(data, place, true, false, 0, 0, 0, 0, needle.pivotX, needle.pivotY,
                    radius + RING_INNER, radius + RING_OUTER, first.z - BELOW_LABEL);
        }
        boolean alongY = Math.abs(last.y - first.y) >= Math.abs(last.x - first.x);
        float needleMin = Float.MAX_VALUE;
        for (int f = 0; f < needle.faceCount; f++) {
            for (int c = 0; c < 4; c++) {
                needleMin = Math.min(needleMin, needle.corner(f, c, alongY ? 0 : 1));
            }
        }
        float crossStart = alongY ? first.x + NUMERAL_GAP : first.y + first.height / 2.0f + NUMERAL_GAP;
        return new BandOverlay(data, place, false, alongY, alongY ? first.y : first.x, alongY ? last.y : last.x,
                crossStart, needleMin, 0, 0, 0, 0, first.z - BELOW_LABEL);
    }

    public void emit(QuadSink sink) {
        for (int i = 0; i < data.bandCount(); i++) {
            if (data.bandHigh[i] <= data.bandLow[i]) {
                continue;
            }
            float f0 = (float) data.fraction(data.bandLow[i]);
            float f1 = (float) data.fraction(data.bandHigh[i]);
            if (f1 <= f0) {
                continue;
            }
            if (dial) {
                arc(sink, f0, f1, data.bandColor[i]);
            } else {
                strip(sink, f0, f1, data.bandColor[i]);
            }
        }
    }

    private void strip(QuadSink sink, float f0, float f1, int rgb) {
        float a0 = lowAlong + (highAlong - lowAlong) * f0;
        float a1 = lowAlong + (highAlong - lowAlong) * f1;
        float lo = Math.min(a0, a1);
        float hi = Math.max(a0, a1);
        float zc = place.z(z);
        if (alongY) {
            sink.rect(place.x(crossStart), place.y(lo), place.x(crossEnd) - place.x(crossStart), place.y(hi) - place.y(lo), zc, rgb, false);
        } else {
            sink.rect(place.x(lo), place.y(crossStart), place.x(hi) - place.x(lo), place.y(crossEnd) - place.y(crossStart), zc, rgb, false);
        }
    }

    /** An arc of the tick ring from scale fraction {@code f0} to {@code f1}; true lengths, so it stays round on a sloped board. */
    private void arc(QuadSink sink, float f0, float f1, int rgb) {
        float a0 = (float) Math.toRadians(Poses.needleAngle(f0, Poses.DIAL_SWEEP_DEG));
        float a1 = (float) Math.toRadians(Poses.needleAngle(f1, Poses.DIAL_SWEEP_DEG));
        int steps = Math.max(1, (int) Math.ceil((a1 - a0) / ARC_STEP));
        float da = (a1 - a0) / steps;
        float zc = place.z(z);
        float rowPerCol = place.rowScale / place.scale;
        for (int i = 0; i < steps; i++) {
            float a = a0 + i * da;
            float b = a + da;
            float sa = (float) Math.sin(a);
            float ca = (float) Math.cos(a);
            float sb = (float) Math.sin(b);
            float cb = (float) Math.cos(b);
            sink.quad(px(sa * ringInner), py(ca * ringInner, rowPerCol), zc, px(sa * ringOuter), py(ca * ringOuter, rowPerCol), zc,
                    px(sb * ringOuter), py(cb * ringOuter, rowPerCol), zc, px(sb * ringInner), py(cb * ringInner, rowPerCol), zc,
                    rgb, QuadSink.NORMAL_FACE, false);
        }
    }

    private float px(float dx) {
        return place.x(centreX + dx);
    }

    /** Row of a point {@code dy} inches above the centre (rows run down, and a row is {@code rowPerCol} columns long). */
    private float py(float up, float rowPerCol) {
        return place.y(centreY) - up * place.scale * rowPerCol;
    }
}
