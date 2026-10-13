package dev.bwr.mod.panel.client;

import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.component.PushbuttonData;

/** Pushbutton: a square bezel with a coloured cap that sits lower, and glows, while held. */
final class PushbuttonModel implements ComponentModel {
    @Override
    public void buildStatic(QuadSink sink, PanelComponent component) {
        float w = component.w();
        float h = component.h();
        sink.box(0, 0, w, h, 0, 1.2f, ModelDraw.BEZEL, false);
        float ring = Math.min(w, h) * 0.12f;
        sink.box(ring, ring, w - 2 * ring, h - 2 * ring, 0, 1.5f, ModelDraw.RECESS, false);
    }

    @Override
    public void renderDynamic(PanelRenderContext context, PanelComponent component) {
        PushbuttonData data = (PushbuttonData) component.data();
        boolean held = component.output(0) > 0.5;
        float w = component.w();
        float h = component.h();
        float inset = Math.min(w, h) * 0.2f;
        int color = held ? data.color : QuadSink.shade(data.color, 0.8f);
        context.box(inset, inset, w - 2 * inset, h - 2 * inset, 0.8f, held ? 2.0f : 3.6f, color, held);
    }
}
