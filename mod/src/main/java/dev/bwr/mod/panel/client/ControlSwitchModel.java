package dev.bwr.mod.panel.client;

import dev.bwr.mod.panel.component.ControlSwitchData;
import dev.bwr.mod.panel.component.LampData;
import dev.bwr.mod.panel.component.PanelComponent;

/**
 * Pistol-grip control switch: a red lamp (open / running) and a green lamp
 * (closed / stopped) across the top, each captioned, a handle that swings to
 * CLOSE (left) / NORMAL (up) / OPEN (right), and the name of the current
 * position along the bottom. Lamps are driven by the inputs and glow
 * full-bright; no signal leaves them dark. Everything is laid out in fractions
 * of the footprint so the larger size scales the same way.
 */
final class ControlSwitchModel implements ComponentModel {
    /** Edge of a lamp lens. */
    private static float lamp(PanelComponent component) {
        return component.w() * 0.36f;
    }

    private static float lampY(PanelComponent component) {
        return component.h() * 0.04f;
    }

    private static float centreY(PanelComponent component) {
        return component.h() * 0.64f;
    }

    private static float radius(PanelComponent component) {
        return Math.min(component.w() * 0.27f, component.h() * 0.18f);
    }

    @Override
    public void buildStatic(QuadSink sink, PanelComponent component) {
        float w = component.w();
        float h = component.h();
        sink.box(0, 0, w, h, 0, 1.0f, ModelDraw.HOUSING, false);
        float lamp = lamp(component);
        float y = lampY(component);
        sink.box(w * 0.25f - lamp / 2, y, lamp, lamp, 0.8f, 1.8f, QuadSink.shade(ModelDraw.RED, 0.22f), false);
        sink.box(w * 0.75f - lamp / 2, y, lamp, lamp, 0.8f, 1.8f, QuadSink.shade(ModelDraw.GREEN, 0.22f), false);
        // Escutcheon the handle turns on.
        float cx = w / 2.0f;
        float cy = centreY(component);
        float r = radius(component);
        sink.box(cx - r, cy - r, 2 * r, 2 * r, 1.0f, 2.2f, ModelDraw.STEEL, false);
    }

    @Override
    public void renderDynamic(PanelRenderContext context, PanelComponent component) {
        ControlSwitchData data = (ControlSwitchData) component.data();
        float w = component.w();
        float h = component.h();
        float lamp = lamp(component);
        float y = lampY(component);
        float edge = lamp * 0.08f;
        if (LampData.active(component.input(0))) {
            context.box(w * 0.25f - lamp / 2 + edge, y + edge, lamp - 2 * edge, lamp - 2 * edge, 0.8f, 1.9f, ModelDraw.RED, true);
        }
        if (LampData.active(component.input(1))) {
            context.box(w * 0.75f - lamp / 2 + edge, y + edge, lamp - 2 * edge, lamp - 2 * edge, 0.8f, 1.9f, ModelDraw.GREEN, true);
        }
        int position = Math.max(-1, Math.min(1, (int) Math.round(component.output(0))));
        float cx = w / 2.0f;
        float cy = centreY(component);
        float r = radius(component);
        float angle = (float) Math.toRadians(position * 45.0);
        ModelDraw.bar(context, cx, cy, angle, r * 1.5f, r * 0.34f, 3.2f, ModelDraw.RECESS, false);
        float hub = r * 0.22f;
        context.box(cx - hub, cy - hub, 2 * hub, 2 * hub, 2.2f, 3.6f, ModelDraw.RECESS, false);
        if (context.lod() == PanelRenderContext.LOD_FULL) {
            float captionY = y + lamp + h * 0.01f;
            float captionH = h * 0.14f;
            ModelDraw.centreText(context, data.redText, 0.2f, captionY, w / 2 - 0.4f, captionH, 1.0f, ModelDraw.ENGRAVED, false);
            ModelDraw.centreText(context, data.greenText, w / 2 + 0.2f, captionY, w / 2 - 0.4f, captionH, 1.0f, ModelDraw.ENGRAVED, false);
            String current = position < 0 ? data.closeText : position == 0 ? data.normalText : data.openText;
            float bottom = cy + r + h * 0.02f;
            ModelDraw.centreText(context, current, 0.3f, bottom, w - 0.6f, h - bottom - 0.2f, 1.0f, ModelDraw.AMBER, false);
        }
    }
}
