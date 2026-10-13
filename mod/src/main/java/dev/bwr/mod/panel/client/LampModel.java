package dev.bwr.mod.panel.client;

import dev.bwr.mod.panel.component.LampData;
import dev.bwr.mod.panel.component.PanelComponent;

/**
 * Indicator lamp: a dark bezel with a dim lens (static), and a full-bright lens
 * on top while the input is active (dynamic). Flashing is a function of game
 * time, so a flashing lamp is not synced and costs nothing when idle.
 */
final class LampModel implements ComponentModel {
    private static final int BEZEL = 0x2A2E33;

    @Override
    public void buildStatic(QuadSink sink, PanelComponent component) {
        LampData data = (LampData) component.data();
        float w = component.w();
        float h = component.h();
        sink.box(0, 0, w, h, 0, 1.5f, BEZEL, false);
        float inset = Math.min(w, h) * 0.16f;
        sink.box(inset, inset, w - 2 * inset, h - 2 * inset, 0.8f, 2.0f, QuadSink.shade(data.color, 0.22f), false);
    }

    @Override
    public void renderDynamic(PanelRenderContext context, PanelComponent component) {
        if (!LampData.active(component.input(0))) {
            return;
        }
        LampData data = (LampData) component.data();
        if (LampData.SLOW.equals(data.flash) && !context.blinkOn(1.0f)
                || LampData.FAST.equals(data.flash) && !context.blinkOn(4.0f)) {
            return;
        }
        float w = component.w();
        float h = component.h();
        float inset = Math.min(w, h) * 0.16f;
        context.box(inset, inset, w - 2 * inset, h - 2 * inset, 0.8f, 2.1f, data.color, true);
    }
}
