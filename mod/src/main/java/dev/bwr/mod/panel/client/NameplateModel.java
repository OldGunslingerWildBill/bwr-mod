package dev.bwr.mod.panel.client;

import dev.bwr.mod.panel.component.NameplateData;
import dev.bwr.mod.panel.component.PanelComponent;

/**
 * Black engraved nameplate: a flat plate with a thin raised rim and the text
 * centred on it. The text fit (scale and offsets) is worked out once per
 * revision into a {@link RenderCache} so drawing a frame is just the draw call.
 */
final class NameplateModel implements ComponentModel {
    private static final float RIM = 0.08f;
    private static final float PLATE_TOP = 0.5f;

    @Override
    public void buildStatic(QuadSink sink, PanelComponent component) {
        NameplateData data = (NameplateData) component.data();
        float w = component.w();
        float h = component.h();
        sink.box(0, 0, w, h, 0, PLATE_TOP, data.plate, false);
        int rim = QuadSink.shade(data.plate, 1.6f);
        sink.rect(0, 0, w, RIM, PLATE_TOP + 0.02f, rim, false);
        sink.rect(0, h - RIM, w, RIM, PLATE_TOP + 0.02f, rim, false);
        sink.rect(0, RIM, RIM, h - 2 * RIM, PLATE_TOP + 0.02f, rim, false);
        sink.rect(w - RIM, RIM, RIM, h - 2 * RIM, PLATE_TOP + 0.02f, rim, false);
    }

    @Override
    public void renderDynamic(PanelRenderContext context, PanelComponent component) {
        if (context.lod() < PanelRenderContext.LOD_FULL) {
            return;
        }
        NameplateData data = (NameplateData) component.data();
        if (data.text.isEmpty()) {
            return;
        }
        RenderCache cache = RenderCache.of(component);
        if (cache.fitRevision != component.revision()) {
            fit(context, component, data, cache);
        }
        context.text(data.text, cache.textX, cache.textY, cache.scale, PLATE_TOP + 0.06f, data.ink, false);
    }

    /** Scale the text to the plate (one line, a little margin) and centre it. Runs once per revision. */
    private static void fit(PanelRenderContext context, PanelComponent component, NameplateData data, RenderCache cache) {
        float lineHeight = 8.0f;
        float textWidth = Math.max(1, context.font().width(data.text));
        float availableW = component.w() - 0.4f;
        float availableH = (component.h() - 0.3f) / context.rowAspect();
        float scale = Math.min(availableW / textWidth, availableH / lineHeight);
        cache.scale = scale;
        cache.textX = (component.w() - textWidth * scale) / 2.0f;
        cache.textY = (component.h() - lineHeight * scale * context.rowAspect()) / 2.0f;
        cache.fitRevision = component.revision();
    }
}
