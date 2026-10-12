package dev.bwr.mod.panel.client;

import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.component.ThumbwheelData;

/** Thumbwheel: one dark window per digit with a raise mark above and a lower mark below, the digit lit inside. */
final class ThumbwheelModel implements ComponentModel {
    private static final String[] DIGITS = {"0", "1", "2", "3", "4", "5", "6", "7", "8", "9"};

    @Override
    public void buildStatic(QuadSink sink, PanelComponent component) {
        float w = component.w();
        float h = component.h();
        sink.box(0, 0, w, h, 0, 0.6f, ModelDraw.HOUSING, false);
        for (int i = 0; i < component.w(); i++) {
            sink.box(i + 0.12f, 0.45f, 0.76f, h - 0.9f, 0.6f, 1.0f, ModelDraw.RECESS, false);
            sink.rect(i + 0.3f, 0.12f, 0.4f, 0.22f, 0.8f, ModelDraw.ENGRAVED, false);
            sink.rect(i + 0.3f, h - 0.34f, 0.4f, 0.22f, 0.8f, ModelDraw.ENGRAVED, false);
        }
    }

    @Override
    public void renderDynamic(PanelRenderContext context, PanelComponent component) {
        if (context.lod() < PanelRenderContext.LOD_FULL) {
            return;
        }
        ThumbwheelData data = (ThumbwheelData) component.data();
        int digits = component.w();
        long value = Math.max(0L, Math.round(component.output(0)));
        for (int i = 0; i < digits; i++) {
            String digit = DIGITS[ThumbwheelData.digit(value, digits, i)];
            ModelDraw.centreText(context, digit, i + 0.15f, 0.5f, 0.7f, component.h() - 1.0f, 1.1f, data.color, true);
        }
    }
}
