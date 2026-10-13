package dev.bwr.mod.panel.client;

import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.component.StripChartData;

/**
 * Strip-chart recorder: a dark case with cream paper and a faint grid recorded
 * once, and one coloured step trace per pen drawn every frame from the bounded
 * history in {@link StripChartData}. The paper scrolls: "now" is the right edge
 * and the left edge is {@code span} ago, positioned with the frame time so the
 * motion is smooth. Traces are fine detail and draw only close up.
 */
final class StripChartModel implements ComponentModel {
    private static final int CASE = 0x23282C;
    private static final int PAPER = 0xF2EFE2;
    private static final int GRID = 0xCFC9B4;
    private static final float MARGIN = 1.0f;
    private static final float LINE = 0.3f;

    @Override
    public void buildStatic(QuadSink sink, PanelComponent component) {
        float w = component.w();
        float h = component.h();
        sink.box(0, 0, w, h, 0, 1.0f, CASE, false);
        float x0 = MARGIN;
        float y0 = MARGIN;
        float pw = w - 2 * MARGIN;
        float ph = h - 2 * MARGIN;
        sink.rect(x0, y0, pw, ph, 1.05f, PAPER, false);
        for (int i = 1; i < 5; i++) {
            sink.rect(x0 + pw * i / 5.0f - 0.05f, y0, 0.1f, ph, 1.08f, GRID, false);
            sink.rect(x0, y0 + ph * i / 5.0f - 0.05f, pw, 0.1f, 1.08f, GRID, false);
        }
    }

    @Override
    public void renderDynamic(PanelRenderContext context, PanelComponent component) {
        if (context.lod() < PanelRenderContext.LOD_FULL) {
            return;
        }
        StripChartData data = (StripChartData) component.data();
        double time = context.gameTime();
        data.rebase((long) time);
        float x0 = MARGIN;
        float y0 = MARGIN;
        float pw = component.w() - 2 * MARGIN;
        float ph = component.h() - 2 * MARGIN;
        double span = data.spanTicks();
        double start = time - span;
        for (int pen = 0; pen < data.pens; pen++) {
            int n = data.points(pen);
            if (n == 0) {
                continue;
            }
            int color = data.penColor[pen];
            float z = 1.2f + 0.03f * pen;
            boolean havePrev = false;
            float prevX = x0;
            float prevY = 0;
            for (int k = 0; k < n; k++) {
                float value = data.value(pen, k);
                float x = x0 + clamp((float) ((data.time(pen, k) - start) / span)) * pw;
                if (Float.isNaN(value)) {
                    havePrev = false;
                    prevX = x;
                    continue;
                }
                float y = y0 + (1.0f - (float) data.fraction(value)) * ph;
                if (havePrev) {
                    context.rect(prevX, prevY - LINE / 2.0f, Math.max(0.0f, x - prevX), LINE, z, color, false);
                    context.rect(x - LINE / 2.0f, Math.min(prevY, y) - LINE / 2.0f, LINE,
                            Math.abs(y - prevY) + LINE, z, color, false);
                }
                havePrev = true;
                prevX = x;
                prevY = y;
            }
            if (havePrev) {
                context.rect(prevX, prevY - LINE / 2.0f, Math.max(0.0f, x0 + pw - prevX), LINE, z, color, false);
            }
        }
    }

    private static float clamp(float f) {
        return f < 0.0f ? 0.0f : Math.min(f, 1.0f);
    }
}
