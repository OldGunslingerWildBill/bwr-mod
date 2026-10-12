package dev.bwr.mod.panel.client;

import dev.bwr.mod.panel.component.BarGraphData;
import dev.bwr.mod.panel.component.BarGraphLogic;
import dev.bwr.mod.panel.component.PanelComponent;

/**
 * Segmented bar graph: a dark housing with one segment per cell along the long
 * side, filled from the bottom (vertical) or the left (horizontal). Lit
 * segments are full-bright in their band colour (normal / amber warn / red
 * alarm); unlit ones show the band colour dimmed so the bands are readable
 * when idle. No signal draws every segment grey and unlit.
 */
final class BarGraphModel implements ComponentModel {
    private static final float GAP = 0.12f;

    private static boolean vertical(PanelComponent component) {
        return component.h() >= component.w();
    }

    private static int bandColor(BarGraphData data, int band) {
        return band == BarGraphLogic.ALARM ? ModelDraw.RED : band == BarGraphLogic.WARN ? ModelDraw.AMBER : data.color;
    }

    @Override
    public void buildStatic(QuadSink sink, PanelComponent component) {
        BarGraphData data = (BarGraphData) component.data();
        float w = component.w();
        float h = component.h();
        sink.box(0, 0, w, h, 0, 0.6f, ModelDraw.HOUSING, false);
        boolean vertical = vertical(component);
        int segments = vertical ? component.h() : component.w();
        for (int i = 0; i < segments; i++) {
            int band = BarGraphLogic.band(i, data.min, data.max, segments, data.warn, data.alarm);
            int dim = QuadSink.shade(bandColor(data, band), 0.22f);
            if (vertical) {
                sink.box(0.12f, h - (i + 1) + GAP, w - 0.24f, 1 - 2 * GAP, 0.6f, 1.0f, dim, false);
            } else {
                sink.box(i + GAP, 0.12f, 1 - 2 * GAP, h - 0.24f, 0.6f, 1.0f, dim, false);
            }
        }
    }

    @Override
    public void renderDynamic(PanelRenderContext context, PanelComponent component) {
        BarGraphData data = (BarGraphData) component.data();
        float w = component.w();
        float h = component.h();
        boolean vertical = vertical(component);
        int segments = vertical ? component.h() : component.w();
        double value = component.input(0);
        boolean noSignal = Double.isNaN(value);
        int lit = BarGraphLogic.lit(value, data.min, data.max, segments);
        for (int i = 0; i < segments; i++) {
            boolean on = i < lit;
            if (!on && !noSignal) {
                continue;
            }
            int color = noSignal ? ModelDraw.NO_SIGNAL
                    : bandColor(data, BarGraphLogic.band(i, data.min, data.max, segments, data.warn, data.alarm));
            if (vertical) {
                context.box(0.12f, h - (i + 1) + GAP, w - 0.24f, 1 - 2 * GAP, 0.6f, 1.2f, color, !noSignal);
            } else {
                context.box(i + GAP, 0.12f, 1 - 2 * GAP, h - 0.24f, 0.6f, 1.2f, color, !noSignal);
            }
        }
    }
}
