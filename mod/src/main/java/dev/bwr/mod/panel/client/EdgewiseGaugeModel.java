package dev.bwr.mod.panel.client;

import dev.bwr.mod.panel.component.GaugeData;
import dev.bwr.mod.panel.component.PanelComponent;

/**
 * Edgewise meter, vertical (low at the bottom) or horizontal (low at the left):
 * a cream strip with tick marks and colour bands recorded once, a pointer that
 * follows input 0 with the configured lag and, while input 1 has a signal, a
 * demand marker on the other side of the scale. No signal pegs the pointer low
 * and shows a red flag at the high end. The units text draws at the top of the
 * vertical meter, close up only.
 */
final class EdgewiseGaugeModel implements ComponentModel {
    private static final int BEZEL = 0x23282C;
    private static final int FACE = 0xE9E5D4;
    private static final int INK = 0x202020;
    private static final int POINTER = 0xD03010;
    private static final int DEMAND = 0x1B6FE0;
    private static final int FLAG = 0xC02020;
    private static final float PAD = 0.55f;

    private final boolean vertical;

    EdgewiseGaugeModel(boolean vertical) {
        this.vertical = vertical;
    }

    private float along(PanelComponent component) {
        return vertical ? component.h() : component.w();
    }

    private float across(PanelComponent component) {
        return vertical ? component.w() : component.h();
    }

    /** Position along the meter of a scale fraction (0 low .. 1 high), from the top or left edge. */
    private float position(PanelComponent component, float fraction) {
        float usable = along(component) - 2.0f * PAD;
        return vertical ? PAD + (1.0f - fraction) * usable : PAD + fraction * usable;
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
        sink.box(0, 0, w, h, 0, 1.0f, BEZEL, false);
        sink.rect(0.12f, 0.12f, w - 0.24f, h - 0.24f, 1.05f, FACE, false);
        for (int i = 0; i < GaugeData.BANDS; i++) {
            if (data.bandHigh[i] > data.bandLow[i]) {
                strip(sink, position(component, (float) data.fraction(data.bandLow[i])),
                        position(component, (float) data.fraction(data.bandHigh[i])),
                        t * 0.64f, t * 0.8f, 1.1f, data.bandColor[i]);
            }
        }
        for (int i = 0; i <= 20; i++) {
            boolean major = i % 5 == 0;
            line(sink, position(component, i / 20.0f), t * (major ? 0.2f : 0.32f), t * 0.6f, major ? 0.1f : 0.05f, 1.12f, INK);
        }
    }

    @Override
    public void renderDynamic(PanelRenderContext context, PanelComponent component) {
        GaugeData data = (GaugeData) component.data();
        GaugeScratch scratch = GaugeScratch.of(component);
        double value = component.input(0);
        float t = across(component);
        float needle = scratch.follow((float) data.fraction(value), data.damping, context.gameTime());
        line(context, position(component, needle), t * 0.06f, t * 0.62f, 0.16f, 1.3f, POINTER);
        double demand = component.input(1);
        if (!Double.isNaN(demand)) {
            float marker = scratch.followMarker((float) data.fraction(demand), data.damping);
            line(context, position(component, marker), t * 0.82f, t * 0.98f, 0.16f, 1.25f, DEMAND);
        } else {
            scratch.markerPrimed = false;
        }
        if (GaugeData.offScale(value)) {
            float p = position(component, 1.0f);
            if (vertical) {
                context.rect(t * 0.3f, p - 0.38f, t * 0.4f, 0.26f, 1.2f, FLAG, false);
            } else {
                context.rect(p - 0.12f, t * 0.3f, 0.26f, t * 0.4f, 1.2f, FLAG, false);
            }
        }
        if (vertical && context.lod() >= PanelRenderContext.LOD_FULL && !data.units.isEmpty()) {
            scratch.layoutText(context, component, data);
            float scale = Math.min(0.06f, (t - 0.3f) / Math.max(1.0f, scratch.unitsWidth));
            context.text(scratch.units, (t - scratch.unitsWidth * scale) / 2.0f, 0.18f, scale, 1.2f, INK, false);
        }
    }
}
