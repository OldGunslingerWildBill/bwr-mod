package dev.bwr.mod.panel.client;

import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.component.ToggleSwitchData;

/** Toggle switch: a bezel with a slot, and a lever that points up when on and down when off. */
final class ToggleSwitchModel implements ComponentModel {
    @Override
    public void buildStatic(QuadSink sink, PanelComponent component) {
        float w = component.w();
        float h = component.h();
        float pivot = w * 0.18f;
        sink.box(0, 0, w, h, 0, 1.2f, ModelDraw.BEZEL, false);
        sink.box(w * 0.28f, h * 0.1f, w * 0.44f, h * 0.8f, 0, 1.5f, ModelDraw.RECESS, false);
        sink.box(w * 0.5f - pivot, h * 0.5f - pivot, 2 * pivot, 2 * pivot, 0, 2.6f, ModelDraw.STEEL, false);
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
        float length = h * 0.3f;
        float tip = w * 0.17f;
        // The lever lies on the face in this placeholder; the tip is a coloured block.
        ModelDraw.bar(context, cx, cy, angle, length, w * 0.14f, 3.3f, ModelDraw.STEEL, false);
        float tipY = on ? cy - length : cy + length;
        context.box(cx - tip, tipY - tip, 2 * tip, 2 * tip, 1.5f, 4.2f, data.color, false);
    }
}
