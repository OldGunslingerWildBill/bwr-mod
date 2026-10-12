package dev.bwr.mod.panel.client;

import dev.bwr.mod.panel.component.ControlSwitchData;
import dev.bwr.mod.panel.component.LampData;
import dev.bwr.mod.panel.component.PanelComponent;

/**
 * Pistol-grip control switch: a red lamp (open / running) and a green lamp
 * (closed / stopped) across the top, each captioned, a handle that swings to
 * CLOSE (left) / NORMAL (up) / OPEN (right), and the three position names along
 * the bottom. Lamps are driven by the inputs and glow full-bright; no signal
 * leaves them dark.
 */
final class ControlSwitchModel implements ComponentModel {
    @Override
    public void buildStatic(QuadSink sink, PanelComponent component) {
        float w = component.w();
        float h = component.h();
        sink.box(0, 0, w, h, 0, 0.6f, ModelDraw.HOUSING, false);
        float lamp = Math.min(1.2f, w * 0.4f);
        sink.box(w * 0.25f - lamp / 2, 0.15f, lamp, lamp, 0.4f, 1.2f, QuadSink.shade(ModelDraw.RED, 0.22f), false);
        sink.box(w * 0.75f - lamp / 2, 0.15f, lamp, lamp, 0.4f, 1.2f, QuadSink.shade(ModelDraw.GREEN, 0.22f), false);
        // Escutcheon the handle turns on.
        float cx = w / 2.0f;
        float cy = h * 0.58f;
        float r = Math.min(w, h) * 0.26f;
        sink.box(cx - r, cy - r, 2 * r, 2 * r, 0.6f, 1.4f, ModelDraw.STEEL, false);
    }

    @Override
    public void renderDynamic(PanelRenderContext context, PanelComponent component) {
        ControlSwitchData data = (ControlSwitchData) component.data();
        float w = component.w();
        float h = component.h();
        float lamp = Math.min(1.2f, w * 0.4f);
        if (LampData.active(component.input(0))) {
            context.box(w * 0.25f - lamp / 2 + 0.1f, 0.25f, lamp - 0.2f, lamp - 0.2f, 0.4f, 1.4f, ModelDraw.RED, true);
        }
        if (LampData.active(component.input(1))) {
            context.box(w * 0.75f - lamp / 2 + 0.1f, 0.25f, lamp - 0.2f, lamp - 0.2f, 0.4f, 1.4f, ModelDraw.GREEN, true);
        }
        int position = Math.max(-1, Math.min(1, (int) Math.round(component.output(0))));
        float cx = w / 2.0f;
        float cy = h * 0.58f;
        float r = Math.min(w, h) * 0.26f;
        float angle = (float) Math.toRadians(position * 45.0);
        ModelDraw.bar(context, cx, cy, angle, r * 1.5f, 0.55f, 2.4f, ModelDraw.RECESS, false);
        context.box(cx - 0.35f, cy - 0.35f, 0.7f, 0.7f, 1.4f, 2.8f, ModelDraw.RECESS, false);
        if (context.lod() == PanelRenderContext.LOD_FULL) {
            ModelDraw.centreText(context, data.redText, 0.05f, 1.5f, w / 2 - 0.1f, 0.5f, 0.6f, ModelDraw.ENGRAVED, false);
            ModelDraw.centreText(context, data.greenText, w / 2 + 0.05f, 1.5f, w / 2 - 0.1f, 0.5f, 0.6f, ModelDraw.ENGRAVED, false);
            float third = w / 3.0f;
            ModelDraw.centreText(context, data.closeText, 0.0f, h - 0.55f, third, 0.45f, 0.6f, position < 0 ? ModelDraw.AMBER : ModelDraw.ENGRAVED, false);
            ModelDraw.centreText(context, data.normalText, third, h - 0.55f, third, 0.45f, 0.6f, position == 0 ? ModelDraw.AMBER : ModelDraw.ENGRAVED, false);
            ModelDraw.centreText(context, data.openText, 2 * third, h - 0.55f, third, 0.45f, 0.6f, position > 0 ? ModelDraw.AMBER : ModelDraw.ENGRAVED, false);
        }
    }
}
