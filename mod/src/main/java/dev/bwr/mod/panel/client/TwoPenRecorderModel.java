package dev.bwr.mod.panel.client;

import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.component.TwoPenRecorderData;
import dev.bwr.mod.panel.model.BakedPanelModel;
import dev.bwr.mod.panel.model.GaugeScale;
import dev.bwr.mod.panel.model.Legends;

import java.util.ArrayList;
import java.util.List;

/**
 * Two pen strip recorder, drawn in code: a dark case with a header (a colour chip and number for each pen, and "2-PEN"), a
 * window of cream chart paper with a grid, and two coloured pens. The paper scrolls upward: "now" is the bottom edge, where the
 * pens sit, and the top edge is {@code span} ago; each pen moves left and right across the paper with its own scale and leaves a
 * step trace (the pen holds its last value between changes, so a flat signal is a straight line up the chart). The
 * body is recorded once; the traces and the pen carriages are drawn every frame from the bounded history in
 * {@link TwoPenRecorderData}, which the traces only read, at full detail. All sizes are shares of the case.
 */
final class TwoPenRecorderModel implements ComponentModel {
    private static final int CASE = 0x23282C;
    private static final int HEADER = 0x1B1F23;
    private static final int PAPER = 0xF2EFE2;
    private static final int GRID = 0xCFC9B4;
    private static final int RAIL = 0x3A3F44;
    private static final int INK = 0xE8E8E8;
    private static final float WIN_X0 = 0.07f;
    private static final float WIN_X1 = 0.93f;
    private static final float WIN_Y0 = 0.205f;
    private static final float WIN_Y1 = 0.945f;
    /** The pens' side margin inside the paper, a share of the paper's width. */
    private static final float SIDE = 0.04f;
    /** Height of the pens' rail at the bottom of the paper, a share of the component's height. */
    private static final float RAIL_H = 0.03f;

    private static float lineWidth(PanelComponent c) {
        return Math.min(c.w(), c.h()) * 0.014f;
    }

    @Override
    public void buildStatic(QuadSink sink, PanelComponent component) {
        TwoPenRecorderData data = (TwoPenRecorderData) component.data();
        float w = component.w();
        float h = component.h();
        sink.box(0, 0, w, h, 0, 1.0f, CASE, false);
        sink.rect(w * 0.035f, h * 0.025f, w * 0.93f, h * 0.15f, 1.03f, HEADER, false);
        float chip = h * 0.06f;
        for (int pen = 0; pen < 2; pen++) {
            sink.rect(chipX(component, pen), h * 0.1f - chip / 2.0f, chip, chip, 1.1f, data.penColor[pen], false);
        }
        float x0 = w * WIN_X0;
        float y0 = h * WIN_Y0;
        float pw = w * (WIN_X1 - WIN_X0);
        float ph = h * (WIN_Y1 - WIN_Y0);
        float rim = Math.min(w, h) * 0.02f;
        sink.rect(x0 - rim, y0 - rim, pw + 2 * rim, ph + 2 * rim, 1.04f, HEADER, false);
        sink.rect(x0, y0, pw, ph, 1.08f, PAPER, false);
        float thin = Math.min(w, h) * 0.006f;
        for (int i = 1; i < 10; i++) {
            sink.rect(x0 + pw * i / 10.0f - thin / 2.0f, y0, thin, ph, 1.1f, GRID, false);
        }
        for (int i = 1; i < 8; i++) {
            sink.rect(x0, y0 + ph * i / 8.0f - thin / 2.0f, pw, thin, 1.1f, GRID, false);
        }
        sink.rect(x0, y0 + ph - h * RAIL_H, pw, h * RAIL_H, 1.12f, RAIL, false);
    }

    private static float chipX(PanelComponent component, int pen) {
        return component.w() * (0.07f + 0.27f * pen);
    }

    @Override
    public void renderDynamic(PanelRenderContext context, PanelComponent component) {
        TwoPenRecorderData data = (TwoPenRecorderData) component.data();
        float w = component.w();
        float h = component.h();
        float x0 = w * WIN_X0 + w * (WIN_X1 - WIN_X0) * SIDE;
        float xw = w * (WIN_X1 - WIN_X0) * (1.0f - 2 * SIDE);
        float top = h * WIN_Y0;
        float bottom = h * WIN_Y1 - h * RAIL_H;
        double time = context.gameTime();
        data.rebase((long) time);
        double span = data.spanTicks();
        float line = lineWidth(component);
        boolean traces = context.lod() >= PanelRenderContext.LOD_FULL;
        for (int pen = 0; pen < 2; pen++) {
            int n = data.points(pen);
            int colour = data.penColor[pen];
            float z = 1.2f + 0.04f * pen;
            if (n == 0) {
                continue;
            }
            if (traces) {
                boolean have = false;
                float prevX = 0;
                float prevY = bottom;
                for (int k = 0; k < n; k++) {
                    float value = data.value(pen, k);
                    float y = clampY(bottom - (float) ((time - data.time(pen, k)) / span) * (bottom - top), top, bottom);
                    if (Float.isNaN(value)) {
                        have = false;
                        continue;
                    }
                    float x = x0 + (float) data.fraction(pen, value) * xw;
                    if (have) {
                        // The pen held its last value up to this point's time, then moved across to the new one.
                        context.rect(prevX - line / 2.0f, prevY, line, Math.max(0.0f, y - prevY), z, colour, false);
                        context.rect(Math.min(prevX, x) - line / 2.0f, y - line / 2.0f, Math.abs(x - prevX) + line, line, z, colour, false);
                    }
                    have = true;
                    prevX = x;
                    prevY = y;
                }
                if (have) {
                    context.rect(prevX - line / 2.0f, prevY, line, Math.max(0.0f, bottom - prevY), z, colour, false);
                }
            }
            // The pen carriage at the bottom edge, at the last value.
            float last = data.value(pen, n - 1);
            if (!Float.isNaN(last)) {
                float px = x0 + (float) data.fraction(pen, last) * xw;
                float half = Math.min(w, h) * 0.035f;
                Shapes.tri(context, px, bottom - half * 0.4f, px - half, bottom + h * RAIL_H * 0.9f, px + half, bottom + h * RAIL_H * 0.9f,
                        z + 0.1f, colour, false);
            }
        }
        // (No pen draws before its first value: a pen with no signal rests off the paper.)
        if (traces) {
            GaugeScratch.of(component).drawText(context, component, this);
        }
    }

    private static float clampY(float y, float top, float bottom) {
        return y < top ? top : Math.min(y, bottom);
    }

    /** The two pen numbers and the "2-PEN" mark in the header. */
    @Override
    public List<Legends.Item> textLayout(PanelComponent component, float rowAspect) {
        float w = component.w();
        float h = component.h();
        float chip = h * 0.06f;
        float cap = h * 0.07f;
        List<Legends.Item> items = new ArrayList<>();
        for (int pen = 0; pen < 2; pen++) {
            items.add(new Legends.Item(String.valueOf(pen + 1), chipX(component, pen) + chip * 1.4f, h * 0.1f, 1.2f, cap, 0.0f,
                    BakedPanelModel.Align.LEFT, INK, false, HEADER));
        }
        String title = "2-PEN";
        float room = w * 0.32f;
        items.add(new Legends.Item(title, w * 0.93f, h * 0.1f, 1.2f, Math.min(cap, room / GaugeScale.textCaps(title)), 0.0f,
                BakedPanelModel.Align.RIGHT, INK, false, HEADER));
        return items;
    }
}
