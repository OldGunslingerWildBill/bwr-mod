package dev.bwr.mod.panel.client;

import dev.bwr.mod.panel.component.GaugeData;
import dev.bwr.mod.panel.component.PanelComponent;

/**
 * Round dial: a dark square bezel, a cream face with a 270 degree scale
 * (coloured bands, 21 ticks) recorded once per revision, and a needle that
 * follows the input with the configured lag. No signal pegs the needle at the
 * low end and shows a red OFF flag; the min, max and units text draws only
 * close up.
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
        Shapes.disc(sink, cx, cy, face, 1.05f, FACE, 32, false);
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
        float majorWidth = Math.max(0.1f, face * 0.04f);
        float minorWidth = Math.max(0.06f, face * 0.022f);
        for (int i = 0; i <= 20; i++) {
            float angle = START + SWEEP * i / 20.0f;
            boolean major = i % 5 == 0;
            Shapes.spoke(sink, cx, cy, face * (major ? 0.56f : 0.66f), face * 0.76f, angle,
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
        float face = Math.min(w, h) / 2.0f * 0.9f;
        float angle = START + SWEEP * position;
        Shapes.spoke(context, cx, cy, -face * 0.16f, face * 0.74f, angle, Math.max(0.1f, face * 0.055f), 1.3f, NEEDLE, false);
        boolean offScale = GaugeData.offScale(value);
        if (offScale) {
            context.rect(cx - face * 0.26f, cy + face * 0.42f, face * 0.52f, face * 0.2f, 1.2f, FLAG, false);
        }
        if (context.lod() < PanelRenderContext.LOD_FULL) {
            return;
        }
        scratch.layoutText(context, component, data);
        float scale = face * 0.035f;
        float rowY = cy + face * 0.66f;
        context.text(scratch.minLabel, cx - face * 0.78f, rowY, scale, 1.2f, INK, false);
        context.text(scratch.maxLabel, cx + face * 0.78f - scratch.maxWidth * scale, rowY, scale, 1.2f, INK, false);
        if (!scratch.units.isEmpty()) {
            context.text(scratch.units, cx - scratch.unitsWidth * scale / 2.0f, cy + face * 0.26f, scale, 1.2f, INK, false);
        }
        if (offScale) {
            context.text("OFF", cx - context.font().width("OFF") * scale / 2.0f, cy + face * 0.455f, scale * 0.8f, 1.25f,
                    0xFFFFFF, false);
        }
    }
}
