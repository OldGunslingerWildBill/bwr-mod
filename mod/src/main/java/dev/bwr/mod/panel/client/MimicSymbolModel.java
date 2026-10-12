package dev.bwr.mod.panel.client;

import dev.bwr.mod.panel.component.MimicSymbolData;
import dev.bwr.mod.panel.component.PanelComponent;

/**
 * Mimic symbol: a dark plate (static) and the symbol drawn on it in the colour
 * of its state (dynamic): bowtie valve with a stem, circle pump with an
 * impeller, tank, tapered turbine, breaker with a blade that sits upright when
 * the input is non-zero. No signal draws the symbol gray.
 */
final class MimicSymbolModel implements ComponentModel {
    private static final int PLATE = 0x15191C;

    @Override
    public void buildStatic(QuadSink sink, PanelComponent component) {
        sink.box(0, 0, component.w(), component.h(), 0, 0.4f, PLATE, false);
    }

    @Override
    public void renderDynamic(PanelRenderContext context, PanelComponent component) {
        MimicSymbolData data = (MimicSymbolData) component.data();
        double input = component.input(0);
        int color = data.colorFor(input);
        float w = component.w();
        float h = component.h();
        float cx = w / 2.0f;
        float cy = h / 2.0f;
        float r = Math.min(w, h) * 0.5f - Math.min(w, h) * 0.16f;
        float z = 0.5f;
        float line = Math.max(0.1f, r * 0.14f);
        switch (data.symbol) {
            case MimicSymbolData.PUMP -> {
                Shapes.disc(context, cx, cy, r, z, color, 20, false);
                Shapes.tri(context, cx - r * 0.35f, cy - r * 0.45f, cx - r * 0.35f, cy + r * 0.45f, cx + r * 0.5f, cy,
                        z + 0.05f, PLATE, false);
                context.rect(cx, cy - r - line, r + line, line, z, color, false);
            }
            case MimicSymbolData.TANK -> {
                context.rect(cx - r * 0.7f, cy - r, r * 1.4f, r * 2.0f, z, color, false);
                context.rect(cx - r * 0.7f, cy - r * 0.35f, r * 1.4f, line * 0.7f, z + 0.05f, PLATE, false);
                context.rect(cx - r * 0.7f, cy + r * 0.3f, r * 1.4f, line * 0.7f, z + 0.05f, PLATE, false);
            }
            case MimicSymbolData.TURBINE -> {
                context.quad(cx - r, cy - r * 0.35f, z, cx + r, cy - r * 0.9f, z, cx + r, cy + r * 0.9f, z,
                        cx - r, cy + r * 0.35f, z, color, QuadSink.NORMAL_FACE, false);
                context.rect(cx - r - line * 1.5f, cy - line / 2.0f, line * 1.5f, line, z, color, false);
                context.rect(cx + r, cy - line / 2.0f, line * 1.5f, line, z, color, false);
            }
            case MimicSymbolData.BREAKER -> {
                context.rect(cx - r * 0.45f, cy - r * 0.55f, r * 0.9f, r * 1.1f, z, color, false);
                context.rect(cx - line / 2.0f, cy - r, line, r * 0.5f, z, color, false);
                context.rect(cx - line / 2.0f, cy + r * 0.55f, line, r * 0.45f, z, color, false);
                if (input != 0.0 && !Double.isNaN(input)) {
                    Shapes.bar(context, cx, cy + r * 0.4f, cx, cy - r * 0.4f, line, z + 0.05f, PLATE, false);
                } else {
                    Shapes.bar(context, cx, cy + r * 0.4f, cx + r * 0.3f, cy - r * 0.4f, line, z + 0.05f, PLATE, false);
                }
            }
            default -> {
                // valve: two triangles meeting at the centre, with a stem and a handwheel
                Shapes.tri(context, cx - r, cy - r * 0.7f, cx - r, cy + r * 0.7f, cx, cy, z, color, false);
                Shapes.tri(context, cx + r, cy - r * 0.7f, cx + r, cy + r * 0.7f, cx, cy, z, color, false);
                context.rect(cx - line / 2.0f, cy - r * 0.95f, line, r * 0.95f, z, color, false);
                context.rect(cx - r * 0.35f, cy - r * 0.95f - line, r * 0.7f, line, z, color, false);
            }
        }
    }
}
