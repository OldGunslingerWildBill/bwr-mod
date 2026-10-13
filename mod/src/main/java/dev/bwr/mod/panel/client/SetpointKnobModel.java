package dev.bwr.mod.panel.client;

import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.component.SetpointKnobData;

/** Setpoint knob: a 0 to 100 scale of eleven ticks on a dark dial and a pointer knob. */
final class SetpointKnobModel implements ComponentModel {
    @Override
    public void buildStatic(QuadSink sink, PanelComponent component) {
        SetpointKnobData data = (SetpointKnobData) component.data();
        float w = component.w();
        float h = component.h();
        float cx = w / 2.0f;
        float cy = h / 2.0f;
        float radius = Math.min(w, h) / 2.0f;
        sink.box(0, 0, w, h, 0, 1.0f, ModelDraw.HOUSING, false);
        for (int i = 0; i <= 10; i++) {
            boolean major = i % 5 == 0;
            ModelDraw.tick(sink, cx, cy, ModelDraw.dialAngle(i, 11), radius * (major ? 0.74f : 0.82f), radius * 0.96f,
                    radius * (major ? 0.1f : 0.06f), 1.3f, ModelDraw.ENGRAVED);
        }
        float knob = radius * 0.52f;
        sink.box(cx - knob, cy - knob, 2 * knob, 2 * knob, 1.0f, 3.2f, data.color, false);
    }

    @Override
    public void renderDynamic(PanelRenderContext context, PanelComponent component) {
        float w = component.w();
        float h = component.h();
        float radius = Math.min(w, h) / 2.0f;
        double value = Math.max(0.0, Math.min(100.0, component.output(0)));
        float angle = (float) Math.toRadians(-135.0 + 270.0 * value / 100.0);
        ModelDraw.bar(context, w / 2.0f, h / 2.0f, angle, radius * 0.6f, radius * 0.14f, 3.5f, ModelDraw.AMBER, false);
    }
}
