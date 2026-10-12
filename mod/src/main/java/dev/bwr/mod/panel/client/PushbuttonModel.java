package dev.bwr.mod.panel.client;

import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.component.PushbuttonData;

/** Pushbutton: a square bezel with a coloured cap that sits lower, and glows, while held. */
final class PushbuttonModel implements ComponentModel {
    @Override
    public void buildStatic(QuadSink sink, PanelComponent component) {
        float w = component.w();
        float h = component.h();
        sink.box(0, 0, w, h, 0, 0.8f, ModelDraw.BEZEL, false);
        float ring = Math.min(w, h) * 0.12f;
        sink.box(ring, ring, w - 2 * ring, h - 2 * ring, 0, 1.0f, ModelDraw.RECESS, false);
    }

    @Override
    public void renderDynamic(PanelRenderContext context, PanelComponent component) {
        PushbuttonData data = (PushbuttonData) component.data();
        boolean held = component.output(0) > 0.5;
        float w = component.w();
        float h = component.h();
        float inset = Math.min(w, h) * 0.22f;
        int color = held ? data.color : QuadSink.shade(data.color, 0.8f);
        context.box(inset, inset, w - 2 * inset, h - 2 * inset, 0.5f, held ? 1.3f : 2.4f, color, held);
    }
}
