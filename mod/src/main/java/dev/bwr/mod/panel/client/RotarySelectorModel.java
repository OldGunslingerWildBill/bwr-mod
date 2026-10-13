package dev.bwr.mod.panel.client;

import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.component.RotarySelectorData;

/** Rotary selector: detent ticks on a dark dial, a pointer knob, and the selected position's name underneath. */
final class RotarySelectorModel implements ComponentModel {
    /** Height of the name band under the dial. */
    private static float band(PanelComponent component) {
        return Math.min(3.2f, component.h() * 0.3f);
    }

    @Override
    public void buildStatic(QuadSink sink, PanelComponent component) {
        RotarySelectorData data = (RotarySelectorData) component.data();
        float w = component.w();
        float h = component.h();
        float dial = h - band(component);
        float cx = w / 2.0f;
        float cy = dial / 2.0f;
        sink.box(0, 0, w, h, 0, 1.0f, ModelDraw.HOUSING, false);
        float radius = Math.min(w, dial) / 2.0f;
        float tickWidth = Math.max(0.3f, radius * 0.1f);
        for (int i = 0; i < data.positions; i++) {
            ModelDraw.tick(sink, cx, cy, ModelDraw.dialAngle(i, data.positions), radius * 0.8f, radius * 0.98f,
                    tickWidth, 1.3f, ModelDraw.ENGRAVED);
        }
        float knob = radius * 0.55f;
        sink.box(cx - knob, cy - knob, 2 * knob, 2 * knob, 1.0f, 3.0f, data.color, false);
    }

    @Override
    public void renderDynamic(PanelRenderContext context, PanelComponent component) {
        RotarySelectorData data = (RotarySelectorData) component.data();
        int position = Math.max(0, Math.min(data.positions - 1, (int) component.output(0)));
        float w = component.w();
        float h = component.h();
        float band = band(component);
        float dial = h - band;
        float cx = w / 2.0f;
        float cy = dial / 2.0f;
        float radius = Math.min(w, dial) / 2.0f;
        ModelDraw.bar(context, cx, cy, ModelDraw.dialAngle(position, data.positions), radius * 0.62f,
                Math.max(0.5f, radius * 0.16f), 3.3f, ModelDraw.RECESS, false);
        if (context.lod() == PanelRenderContext.LOD_FULL) {
            ModelDraw.centreText(context, data.nameAt(position), 0.4f, dial + 0.1f, w - 0.8f, band - 0.3f, 1.3f,
                    ModelDraw.ENGRAVED, false);
        }
    }
}
