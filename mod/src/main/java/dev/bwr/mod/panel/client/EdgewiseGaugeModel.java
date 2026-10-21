package dev.bwr.mod.panel.client;

import dev.bwr.mod.panel.component.GaugeData;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.model.BakedPanelModel;
import dev.bwr.mod.panel.model.GaugeScale;
import dev.bwr.mod.panel.model.Legends;

import java.util.ArrayList;
import java.util.List;

/**
 * Edgewise meter, vertical (low at the bottom) or horizontal (low at the left):
 * a cream strip with tick marks and colour bands recorded once, a pointer that
 * follows input 0 with the configured lag and, while input 1 has a signal, a
 * demand marker on the other side of the scale. No signal pegs the pointer low
 * and shows a red flag at the high end. The units text draws at the top of the
 * vertical meter, close up only.
 *
 * <p>Drawn for a strip {@value #DESIGN_ACROSS} cells wide and {@value #DESIGN_ALONG} long; a smaller one (the real size
 * is 1 x 3) is that drawing scaled down ({@link GaugeScale}): its margins, tick thicknesses, pointer and flag shrink by
 * {@link #sizeFactor}, which is 1 at the design size and above, so the big meters are as they always were.
 */
final class EdgewiseGaugeModel implements ComponentModel {
    private static final int BEZEL = 0x23282C;
    private static final int FACE = 0xE9E5D4;
    private static final int INK = 0x202020;
    private static final int POINTER = 0xD03010;
    private static final int DEMAND = 0x1B6FE0;
    private static final int FLAG = 0xC02020;
    /** The size the drawing's cell lengths are for. */
    static final float DESIGN_ACROSS = 6.0f;
    static final float DESIGN_ALONG = 16.0f;
    /** Cells at the design size: the bezel round the face, the major and minor tick thickness, the pointer. */
    private static final float INSET = 0.3f;
    private static final float MAJOR_THICK = 0.3f;
    private static final float MINOR_THICK = 0.15f;
    private static final float POINTER_THICK = 0.4f;
    /** Ticks across the strip, shares of its width: they end at TICK_END and start at MAJOR_START or MINOR_START. */
    private static final float TICK_END = 0.6f;
    private static final float MAJOR_START = 0.2f;
    private static final float MINOR_START = 0.32f;
    /** The furthest a longer tick may start (share of the width), clear of the pointer's end. */
    private static final float TICK_FIRST = 0.08f;
    /** Units text of the vertical meter: font pixels per design cell, its top (cells at the design size) and side margin. */
    private static final float UNITS_TEXT = 0.2f;
    private static final float UNITS_TOP = 0.5f;
    private static final float UNITS_MARGIN = 0.4f;
    private final float pad;

    private final boolean vertical;

    EdgewiseGaugeModel(boolean vertical) {
        this.vertical = vertical;
        this.pad = vertical ? 2.4f : 1.2f;
    }

    private float along(PanelComponent component) {
        return vertical ? component.h() : component.w();
    }

    private float across(PanelComponent component) {
        return vertical ? component.w() : component.h();
    }

    /** 1 at the design size and bigger, less for a smaller strip: what every length in cells above is multiplied by. */
    private float sizeFactor(PanelComponent component) {
        return Math.min(GaugeScale.sizeFactor(across(component), DESIGN_ACROSS),
                GaugeScale.sizeFactor(along(component), DESIGN_ALONG));
    }

    /** The margin at each end of the scale, cells. */
    private float pad(PanelComponent component) {
        return pad * sizeFactor(component);
    }

    /** Position along the meter of a scale fraction (0 low .. 1 high), from the top or left edge. */
    private float position(PanelComponent component, float fraction) {
        float pad = pad(component);
        float usable = along(component) - 2.0f * pad;
        return vertical ? pad + (1.0f - fraction) * usable : pad + fraction * usable;
    }

    /** A line across the meter at position {@code p}, from {@code a0} to {@code a1} across, of thickness {@code t}. */
    private void line(QuadSink sink, float p, float a0, float a1, float t, float z, int rgb) {
        if (vertical) {
            sink.rect(a0, p - t / 2.0f, a1 - a0, t, z, rgb, false);
        } else {
            sink.rect(p - t / 2.0f, a0, t, a1 - a0, z, rgb, false);
        }
    }

    /** A strip along the meter between positions {@code p0} and {@code p1}, across {@code a0..a1}. */
    private void strip(QuadSink sink, float p0, float p1, float a0, float a1, float z, int rgb) {
        float lo = Math.min(p0, p1);
        float hi = Math.max(p0, p1);
        if (vertical) {
            sink.rect(a0, lo, a1 - a0, hi - lo, z, rgb, false);
        } else {
            sink.rect(lo, a0, hi - lo, a1 - a0, z, rgb, false);
        }
    }

    @Override
    public void buildStatic(QuadSink sink, PanelComponent component) {
        GaugeData data = (GaugeData) component.data();
        float w = component.w();
        float h = component.h();
        float t = across(component);
        float k = sizeFactor(component);
        float inset = INSET * k;
        sink.box(0, 0, w, h, 0, 1.0f, BEZEL, false);
        sink.rect(inset, inset, w - 2.0f * inset, h - 2.0f * inset, GaugeScale.CLASSIC_FACE_Z, FACE, false);
        for (int i = 0; i < GaugeData.BANDS; i++) {
            if (data.bandHigh[i] > data.bandLow[i]) {
                strip(sink, position(component, (float) data.fraction(data.bandLow[i])),
                        position(component, (float) data.fraction(data.bandHigh[i])),
                        t * 0.64f, t * 0.8f, 1.1f, data.bandColor[i]);
            }
        }
        // Tick size: thicker, and longer toward the pointer's side, never past TICK_FIRST.
        float tick = data.tickFactor();
        float majorStart = TICK_END - (TICK_END - MAJOR_START)
                * GaugeScale.tickLengthFactor(TICK_END - MAJOR_START, tick, TICK_END - TICK_FIRST);
        float minorStart = TICK_END - (TICK_END - MINOR_START)
                * GaugeScale.tickLengthFactor(TICK_END - MINOR_START, tick, TICK_END - TICK_FIRST);
        for (int i = 0; i <= 20; i++) {
            boolean major = i % 5 == 0;
            line(sink, position(component, i / 20.0f), t * (major ? majorStart : minorStart), t * TICK_END,
                    (major ? MAJOR_THICK : MINOR_THICK) * k * tick, 1.12f, INK);
        }
    }

    @Override
    public void renderDynamic(PanelRenderContext context, PanelComponent component) {
        GaugeData data = (GaugeData) component.data();
        GaugeScratch scratch = GaugeScratch.of(component);
        double value = component.input(0);
        float t = across(component);
        float k = sizeFactor(component);
        float needle = scratch.follow((float) data.fraction(value), data.damping, context.gameTime());
        line(context, position(component, needle), t * 0.06f, t * 0.62f, POINTER_THICK * k, 1.3f, POINTER);
        double demand = component.input(1);
        if (!Double.isNaN(demand)) {
            float marker = scratch.followMarker((float) data.fraction(demand), data.damping);
            line(context, position(component, marker), t * 0.82f, t * 0.98f, POINTER_THICK * k, 1.25f, DEMAND);
        } else {
            scratch.markerPrimed = false;
        }
        if (GaugeData.offScale(value)) {
            float p = position(component, 1.0f);
            if (vertical) {
                context.rect(t * 0.3f, p - 0.9f * k, t * 0.4f, 0.6f * k, 1.2f, FLAG, false);
            } else {
                context.rect(p - 0.3f * k, t * 0.3f, 0.6f * k, t * 0.4f, 1.2f, FLAG, false);
            }
        }
        if (vertical && context.lod() >= PanelRenderContext.LOD_FULL && !data.units.isEmpty()) {
            scratch.drawText(context, component, this);
        }
    }

    /**
     * The vertical meter's units, centred in the margin above the scale: {@value #UNITS_TEXT} font pixels per design
     * cell times Text size, shrunk to the strip's width and to the margin's height.
     */
    @Override
    public List<Legends.Item> textLayout(PanelComponent component, float rowAspect) {
        GaugeData data = (GaugeData) component.data();
        List<Legends.Item> items = new ArrayList<>();
        if (!vertical || data.units.isEmpty()) {
            return items;
        }
        float t = across(component);
        float k = sizeFactor(component);
        float top = UNITS_TOP * k;
        float fit = Math.min((t - 2.0f * UNITS_MARGIN * k) / Math.max(1, GaugeScale.textPixels(data.units)),
                (pad(component) - top - 0.1f * k) / 8.0f);
        float scale = GaugeScale.textCap(UNITS_TEXT * k, data.textFactor(), fit);
        items.add(new Legends.Item(data.units, t / 2.0f, top + Legends.CAP_PIXELS / 2.0f * scale, GaugeScale.CLASSIC_TEXT_Z,
                Legends.CAP_PIXELS * scale, 0.0f, BakedPanelModel.Align.CENTER, INK, false, FACE));
        return items;
    }
}
