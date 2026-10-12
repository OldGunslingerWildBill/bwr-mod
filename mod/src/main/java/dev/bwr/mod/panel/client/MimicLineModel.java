package dev.bwr.mod.panel.client;

import dev.bwr.mod.panel.component.InputSources;
import dev.bwr.mod.panel.component.MimicLineData;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.display.MimicShape;

/**
 * Mimic line piece. The static mesh is the bus in gray; when the line is lit
 * (nothing bound to its input, or the bound signal is non-zero) the same shape
 * is drawn again in its colour just above it. Dimming is therefore the absence
 * of the overlay: a dimmed line costs nothing per frame.
 */
final class MimicLineModel implements ComponentModel {
    private static final int DIMMED = 0x6E7B72;

    @Override
    public void buildStatic(QuadSink sink, PanelComponent component) {
        emit(sink, component, (MimicLineData) component.data(), DIMMED, 0.3f);
    }

    @Override
    public void renderDynamic(PanelRenderContext context, PanelComponent component) {
        MimicLineData data = (MimicLineData) component.data();
        boolean bound = component.source(0) != InputSources.NONE;
        double live = component.input(0);
        if (bound && (Double.isNaN(live) || live == 0.0)) {
            return;
        }
        emit(context, component, data, data.color, 0.36f);
    }

    private static void emit(QuadSink sink, PanelComponent component, MimicLineData data, int rgb, float z) {
        float w = component.w();
        float h = component.h();
        int arms = MimicShape.arms(data.shape, data.rotation, component.w(), component.h());
        float t = MimicShape.thickness(data.thickness, component.w(), component.h());
        float cx = w / 2.0f;
        float cy = h / 2.0f;
        float lo = t / 2.0f;
        if ((arms & MimicShape.RIGHT) != 0) {
            sink.rect(cx - lo, cy - lo, w - (cx - lo), t, z, rgb, false);
        }
        if ((arms & MimicShape.LEFT) != 0) {
            sink.rect(0, cy - lo, cx + lo, t, z, rgb, false);
        }
        if ((arms & MimicShape.DOWN) != 0) {
            sink.rect(cx - lo, cy - lo, t, h - (cy - lo), z, rgb, false);
        }
        if ((arms & MimicShape.UP) != 0) {
            sink.rect(cx - lo, 0, t, cy + lo, z, rgb, false);
        }
    }
}
