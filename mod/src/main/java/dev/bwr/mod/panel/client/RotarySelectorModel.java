package dev.bwr.mod.panel.client;

import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.component.RotarySelectorData;

/** Rotary selector: detent ticks on a dark dial, a pointer knob, and the selected position's name underneath. */
final class RotarySelectorModel implements ComponentModel {
    @Override
    public void buildStatic(QuadSink sink, PanelComponent component) {
        RotarySelectorData data = (RotarySelectorData) component.data();
        float w = component.w();
        float h = component.h();
        float dial = h - 1.0f;
        float cx = w / 2.0f;
        float cy = dial / 2.0f + 0.1f;
        sink.box(0, 0, w, h, 0, 0.6f, ModelDraw.HOUSING, false);
        float radius = Math.min(w, dial) / 2.0f;
        for (int i = 0; i < data.positions; i++) {
            ModelDraw.tick(sink, cx, cy, ModelDraw.dialAngle(i, data.positions), radius * 0.82f, radius, 0.18f, 0.9f, ModelDraw.ENGRAVED);
        }
        float knob = radius * 0.56f;
        sink.box(cx - knob, cy - knob, 2 * knob, 2 * knob, 0.6f, 2.0f, data.color, false);
    }

    @Override
    public void renderDynamic(PanelRenderContext context, PanelComponent component) {
        RotarySelectorData data = (RotarySelectorData) component.data();
        int position = Math.max(0, Math.min(data.positions - 1, (int) component.output(0)));
        float w = component.w();
        float h = component.h();
        float dial = h - 1.0f;
        float cx = w / 2.0f;
        float cy = dial / 2.0f + 0.1f;
        float radius = Math.min(w, dial) / 2.0f;
        ModelDraw.bar(context, cx, cy, ModelDraw.dialAngle(position, data.positions), radius * 0.62f, 0.28f, 2.2f, ModelDraw.RECESS, false);
        if (context.lod() == PanelRenderContext.LOD_FULL) {
            ModelDraw.centreText(context, data.nameAt(position), 0.2f, h - 0.95f, w - 0.4f, 0.8f, 0.9f, ModelDraw.ENGRAVED, false);
        }
    }
}
