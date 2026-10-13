package dev.bwr.mod.panel.client;

import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.component.ThumbwheelData;
import dev.bwr.mod.panel.component.ThumbwheelType;

/**
 * Thumbwheel: one dark window per 3 x 6 digit cell with a raise mark above and a lower mark below, the digit lit
 * inside.
 */
final class ThumbwheelModel implements ComponentModel {
    private static final String[] DIGITS = {"0", "1", "2", "3", "4", "5", "6", "7", "8", "9"};
    private static final float DW = ThumbwheelType.DIGIT_W;
    /** Height of the raise and lower marks, and the gap round them. */
    private static final float MARK = 0.7f;

    @Override
    public void buildStatic(QuadSink sink, PanelComponent component) {
        float w = component.w();
        float h = component.h();
        sink.box(0, 0, w, h, 0, 1.0f, ModelDraw.HOUSING, false);
        int digits = ThumbwheelType.digits(component);
        for (int i = 0; i < digits; i++) {
            float x = i * DW;
            sink.box(x + 0.3f, 1.4f, DW - 0.6f, h - 2.8f, 1.0f, 1.5f, ModelDraw.RECESS, false);
            sink.rect(x + 0.9f, 0.35f, DW - 1.8f, MARK, 1.3f, ModelDraw.ENGRAVED, false);
            sink.rect(x + 0.9f, h - 0.35f - MARK, DW - 1.8f, MARK, 1.3f, ModelDraw.ENGRAVED, false);
        }
    }

    @Override
    public void renderDynamic(PanelRenderContext context, PanelComponent component) {
        if (context.lod() < PanelRenderContext.LOD_FULL) {
            return;
        }
        ThumbwheelData data = (ThumbwheelData) component.data();
        int digits = ThumbwheelType.digits(component);
        long value = Math.max(0L, Math.round(component.output(0)));
        for (int i = 0; i < digits; i++) {
            String digit = DIGITS[ThumbwheelData.digit(value, digits, i)];
            ModelDraw.centreText(context, digit, i * DW + 0.6f, 1.5f, DW - 1.2f, component.h() - 3.0f, 1.8f, data.color, true);
        }
    }
}
