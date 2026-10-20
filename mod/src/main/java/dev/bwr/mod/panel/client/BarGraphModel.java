package dev.bwr.mod.panel.client;

import dev.bwr.mod.panel.component.BarGraphData;
import dev.bwr.mod.panel.component.BarGraphLogic;
import dev.bwr.mod.panel.component.BarGraphType;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.model.GaugeScale;

/**
 * Segmented bar graph: a dark housing with one segment per
 * {@link BarGraphType#SEGMENT_CELLS} cells along the long side, filled from the
 * bottom (vertical) or the left (horizontal). Lit segments are full-bright in
 * their band colour (normal / amber warn / red alarm); unlit ones show the band
 * colour dimmed so the bands are readable when idle. No signal draws every
 * segment grey and unlit.
 */
final class BarGraphModel implements ComponentModel {
    private static final float SEG = BarGraphType.SEGMENT_CELLS;
    private static final float GAP = 0.25f;
    private static final float PAD = 0.4f;
    /** The thickness the big bars are drawn at, cells. */
    private static final float DESIGN_THICK = 3.0f;
    /** A real size bar (under 3 cells thick) has two segments per cell: its LEDs are far finer than the big bars. */
    private static final float THIN_SEG = 0.5f;

    private static float seg(PanelComponent component) {
        return Math.min(component.w(), component.h()) < 3 ? THIN_SEG : SEG;
    }

    private static float gap(float seg) {
        return GAP * seg / SEG;
    }

    /**
     * The housing round the segments: {@value #PAD} cells on a bar {@value #DESIGN_THICK} cells thick or more, the same
     * share of a thinner one ({@link GaugeScale}). (It was 0.2 of the thickness under 2 cells: a real size bar's housing
     * was half as heavy again as the big bars'.)
     */
    private static float pad(PanelComponent component) {
        return GaugeScale.least(PAD, GaugeScale.sizeFactor(Math.min(component.w(), component.h()), DESIGN_THICK));
    }

    private static boolean vertical(PanelComponent component) {
        return component.h() >= component.w();
    }

    private static int segments(PanelComponent component) {
        return Math.max(1, (int) ((vertical(component) ? component.h() : component.w()) / seg(component)));
    }

    private static int bandColor(BarGraphData data, int band) {
        return band == BarGraphLogic.ALARM ? ModelDraw.RED : band == BarGraphLogic.WARN ? ModelDraw.AMBER : data.color;
    }

    @Override
    public void buildStatic(QuadSink sink, PanelComponent component) {
        BarGraphData data = (BarGraphData) component.data();
        float w = component.w();
        float h = component.h();
        sink.box(0, 0, w, h, 0, 1.0f, ModelDraw.HOUSING, false);
        boolean vertical = vertical(component);
        int segments = segments(component);
        float seg = seg(component);
        float gap = gap(seg);
        float pad = pad(component);
        for (int i = 0; i < segments; i++) {
            int band = BarGraphLogic.band(i, data.min, data.max, segments, data.warn, data.alarm);
            int dim = QuadSink.shade(bandColor(data, band), 0.22f);
            if (vertical) {
                sink.box(pad, h - (i + 1) * seg + gap, w - 2 * pad, seg - 2 * gap, 1.0f, 1.5f, dim, false);
            } else {
                sink.box(i * seg + gap, pad, seg - 2 * gap, h - 2 * pad, 1.0f, 1.5f, dim, false);
            }
        }
    }

    @Override
    public void renderDynamic(PanelRenderContext context, PanelComponent component) {
        BarGraphData data = (BarGraphData) component.data();
        float w = component.w();
        float h = component.h();
        boolean vertical = vertical(component);
        int segments = segments(component);
        double value = component.input(0);
        boolean noSignal = Double.isNaN(value);
        float seg = seg(component);
        float gap = gap(seg);
        float pad = pad(component);
        int lit = BarGraphLogic.lit(value, data.min, data.max, segments);
        for (int i = 0; i < segments; i++) {
            boolean on = i < lit;
            if (!on && !noSignal) {
                continue;
            }
            int color = noSignal ? ModelDraw.NO_SIGNAL
                    : bandColor(data, BarGraphLogic.band(i, data.min, data.max, segments, data.warn, data.alarm));
            if (vertical) {
                context.box(pad, h - (i + 1) * seg + gap, w - 2 * pad, seg - 2 * gap, 1.0f, 1.8f, color, !noSignal);
            } else {
                context.box(i * seg + gap, pad, seg - 2 * gap, h - 2 * pad, 1.0f, 1.8f, color, !noSignal);
            }
        }
    }
}
