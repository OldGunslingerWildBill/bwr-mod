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
        // A real size one (shorter than DIGIT_H) is the same drawing scaled down: k is 1 for the big digit cells.
        float k = scale(component);
        float pw = DW * k;
        for (int i = 0; i < digits; i++) {
            float x = i * pw;
            sink.box(x + 0.3f * k, 1.4f * k, pw - 0.6f * k, h - 2.8f * k, 1.0f, 1.5f, ModelDraw.RECESS, false);
            sink.rect(x + 0.9f * k, 0.35f * k, pw - 1.8f * k, MARK * k, 1.3f, ModelDraw.ENGRAVED, false);
            sink.rect(x + 0.9f * k, h - 0.35f * k - MARK * k, pw - 1.8f * k, MARK * k, 1.3f, ModelDraw.ENGRAVED, false);
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
        float k = scale(component);
        float pw = DW * k;
        for (int i = 0; i < digits; i++) {
            String digit = DIGITS[ThumbwheelData.digit(value, digits, i)];
            ModelDraw.centreText(context, digit, i * pw + 0.6f * k, 1.5f * k, pw - 1.2f * k, component.h() - 3.0f * k, 1.8f,
                    data.color, true);
        }
    }

    /** How much the 3 x 6 digit cell is scaled to fit the component's height: 1 normally, 1/3 on a real size one. */
    private static float scale(PanelComponent component) {
        return ThumbwheelType.digitWidth(component.h()) / DW;
    }
}
