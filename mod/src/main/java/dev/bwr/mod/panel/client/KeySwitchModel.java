package dev.bwr.mod.panel.client;

import dev.bwr.mod.panel.component.PanelComponent;

/** Key switch: a steel escutcheon with a keyway that turns a quarter when on, and an indicator dot. */
final class KeySwitchModel implements ComponentModel {
    @Override
    public void buildStatic(QuadSink sink, PanelComponent component) {
        float w = component.w();
        float h = component.h();
        sink.box(0, 0, w, h, 0, 0.6f, ModelDraw.HOUSING, false);
        float cx = w / 2.0f;
        float cy = h * 0.55f;
        float r = Math.min(w * 0.4f, h * 0.3f);
        sink.box(cx - r, cy - r, 2 * r, 2 * r, 0.6f, 1.6f, ModelDraw.STEEL, false);
        sink.box(0.2f, 0.2f, 0.5f, 0.5f, 0.4f, 0.9f, QuadSink.shade(ModelDraw.GREEN, 0.2f), false);
    }

    @Override
    public void renderDynamic(PanelRenderContext context, PanelComponent component) {
        boolean on = component.output(0) > 0.5;
        float w = component.w();
        float h = component.h();
        float cx = w / 2.0f;
        float cy = h * 0.55f;
        float r = Math.min(w * 0.4f, h * 0.3f);
        ModelDraw.bar(context, cx, cy, on ? (float) (Math.PI / 2) : 0.0f, r * 0.8f, 0.3f, 1.9f, ModelDraw.RECESS, false);
        ModelDraw.bar(context, cx, cy, on ? (float) (-Math.PI / 2) : (float) Math.PI, r * 0.8f, 0.3f, 1.9f, ModelDraw.RECESS, false);
        if (on) {
            context.box(0.2f, 0.2f, 0.5f, 0.5f, 0.4f, 1.0f, ModelDraw.GREEN, true);
        }
    }
}
