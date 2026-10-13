package dev.bwr.mod.panel.client;

import dev.bwr.mod.panel.component.AnnunciatorData;
import dev.bwr.mod.panel.component.AnnunciatorLogic;
import dev.bwr.mod.panel.component.PanelComponent;

/**
 * Annunciator window: a bezel round a backlit tile with two engraved lines. Off
 * the tile is a dim version of its colour; in alarm it flashes fast; once
 * acknowledged it is steady. A lit tile is full-bright with dark lettering. No
 * signal puts a grey flag in the corner. The text fit is computed once per
 * revision.
 */
final class AnnunciatorModel implements ComponentModel {
    private static final float BORDER = 0.75f;
    private static final float TEXT_Z = 1.9f;

    @Override
    public void buildStatic(QuadSink sink, PanelComponent component) {
        AnnunciatorData data = (AnnunciatorData) component.data();
        float w = component.w();
        float h = component.h();
        sink.box(0, 0, w, h, 0, 1.0f, ModelDraw.HOUSING, false);
        sink.box(BORDER, BORDER, w - 2 * BORDER, h - 2 * BORDER, 0.6f, 1.5f, QuadSink.shade(data.color, 0.2f), false);
    }

    @Override
    public void renderDynamic(PanelRenderContext context, PanelComponent component) {
        AnnunciatorData data = (AnnunciatorData) component.data();
        float w = component.w();
        float h = component.h();
        AnnunciatorLogic.Display display = data.logic.display();
        boolean lit = display == AnnunciatorLogic.Display.STEADY
                || display == AnnunciatorLogic.Display.FLASH && context.blinkOn(4.0f);
        if (lit) {
            context.box(BORDER, BORDER, w - 2 * BORDER, h - 2 * BORDER, 0.6f, 1.6f, data.color, true);
        }
        if (Double.isNaN(component.input(0))) {
            context.rect(w - 2.25f, BORDER + 0.3f, 1.5f, 0.9f, 1.8f, ModelDraw.NO_SIGNAL, false);
        }
        if (context.lod() < PanelRenderContext.LOD_FULL || data.line1.isEmpty() && data.line2.isEmpty()) {
            return;
        }
        RenderCache cache = RenderCache.of(component);
        if (cache.fitRevision != component.revision()) {
            fit(context, component, data, cache);
        }
        int ink = lit ? 0x101010 : 0xC8C8C8;
        if (!data.line1.isEmpty()) {
            context.text(data.line1, cache.textX, cache.textY, cache.scale, TEXT_Z, ink, lit);
        }
        if (!data.line2.isEmpty()) {
            context.text(data.line2, cache.textX2, cache.textY2, cache.scale, TEXT_Z, ink, lit);
        }
    }

    /** One scale for both lines, each centred; a single line is centred in the tile. Once per revision. */
    private static void fit(PanelRenderContext context, PanelComponent component, AnnunciatorData data, RenderCache cache) {
        float w = component.w();
        float h = component.h();
        float innerW = w - 2 * BORDER - 0.9f;
        float innerH = h - 2 * BORDER - 0.6f;
        int lines = (data.line1.isEmpty() ? 0 : 1) + (data.line2.isEmpty() ? 0 : 1);
        float width1 = Math.max(1, context.font().width(data.line1));
        float width2 = Math.max(1, context.font().width(data.line2));
        float widest = Math.max(data.line1.isEmpty() ? 0 : width1, data.line2.isEmpty() ? 0 : width2);
        float lineCells = innerH / Math.max(1, lines);
        float scale = Math.min(innerW / widest, lineCells / (8.0f * context.rowAspect()));
        float lineHeight = 8.0f * scale * context.rowAspect();
        float top = (h - lineHeight * lines) / 2.0f;
        cache.scale = scale;
        cache.textX = (w - width1 * scale) / 2.0f;
        cache.textY = top;
        cache.textX2 = (w - width2 * scale) / 2.0f;
        cache.textY2 = top + (data.line1.isEmpty() ? 0 : lineHeight);
        cache.fitRevision = component.revision();
    }
}
