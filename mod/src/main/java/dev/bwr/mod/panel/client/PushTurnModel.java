package dev.bwr.mod.panel.client;

import dev.bwr.mod.panel.component.PanelComponent;

/**
 * The plain stand-in look of the push-to-turn switch, used only when its baked model file is not loaded: a dark plate
 * with a round red knob that sits lower while pushed in, and a black bar that points at AUTO (left) or ARM (right).
 */
final class PushTurnModel implements ComponentModel {
    private static final int KNOB = 0xC02020;
    private static final int ARROW = 0x0A0A0A;

    @Override
    public void buildStatic(QuadSink sink, PanelComponent component) {
        sink.box(0, 0, component.w(), component.h(), 0, 1.2f, ModelDraw.BEZEL, false);
    }

    @Override
    public void renderDynamic(PanelRenderContext context, PanelComponent component) {
        float w = component.w();
        float h = component.h();
        float cx = w / 2.0f;
        float cy = h / 2.0f;
        float r = Math.min(w, h) * 0.34f;
        boolean pushed = component.output(1) > 0.5;
        Shapes.disc(context, cx, cy, r, pushed ? 2.0f : 3.6f, KNOB, 16, false);
        float side = component.output(0) > 0.5 ? 1.0f : -1.0f;
        context.rect(cx + side * r * 0.45f - r * 0.1f, cy - r * 0.6f, r * 0.2f, r * 1.0f, pushed ? 2.1f : 3.7f, ARROW, false);
    }
}
