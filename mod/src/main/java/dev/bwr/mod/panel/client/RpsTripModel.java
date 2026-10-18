package dev.bwr.mod.panel.client;

import dev.bwr.mod.panel.component.PanelComponent;

/**
 * The plain stand-in look of the RPS trip assembly, used only when its baked model file is not loaded: a dark bezel
 * with two red square caps that sit lower while held.
 */
final class RpsTripModel implements ComponentModel {
    private static final int CAP = 0xC02020;

    @Override
    public void buildStatic(QuadSink sink, PanelComponent component) {
        sink.box(0, 0, component.w(), component.h(), 0, 1.2f, ModelDraw.BEZEL, false);
    }

    @Override
    public void renderDynamic(PanelRenderContext context, PanelComponent component) {
        float w = component.w();
        float h = component.h();
        for (int i = 0; i < 2; i++) {
            boolean held = component.output(i) > 0.5;
            float x = w * (0.1f + 0.45f * i);
            context.box(x, h * 0.2f, w * 0.35f, h * 0.6f, 0.8f, held ? 2.0f : 3.6f, held ? CAP : QuadSink.shade(CAP, 0.8f), held);
        }
    }
}
