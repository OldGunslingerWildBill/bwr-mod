package dev.bwr.mod.panel.client;

import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.component.ToggleSwitchData;

/** Toggle switch: a bezel with a slot, and a lever that points up when on and down when off. */
final class ToggleSwitchModel implements ComponentModel {
    @Override
    public void buildStatic(QuadSink sink, PanelComponent component) {
        float w = component.w();
        float h = component.h();
        sink.box(0, 0, w, h, 0, 0.8f, ModelDraw.BEZEL, false);
        sink.box(w * 0.3f, h * 0.12f, w * 0.4f, h * 0.76f, 0, 1.0f, ModelDraw.RECESS, false);
        sink.box(w * 0.5f - 0.4f, h * 0.5f - 0.4f, 0.8f, 0.8f, 0, 1.8f, ModelDraw.STEEL, false);
    }

    @Override
    public void renderDynamic(PanelRenderContext context, PanelComponent component) {
        ToggleSwitchData data = (ToggleSwitchData) component.data();
        boolean on = component.output(0) > 0.5;
        float w = component.w();
        float h = component.h();
        float cx = w * 0.5f;
        float cy = h * 0.5f;
        float angle = on ? 0.0f : (float) Math.PI;
        float length = h * 0.34f;
        // The lever lies on the face in this placeholder; the tip is a coloured block.
        ModelDraw.bar(context, cx, cy, angle, length, 0.6f, 2.2f, ModelDraw.STEEL, false);
        float tipY = on ? cy - length : cy + length;
        context.box(cx - 0.45f, tipY - 0.45f, 0.9f, 0.9f, 1.0f, 2.8f, data.color, false);
    }
}
