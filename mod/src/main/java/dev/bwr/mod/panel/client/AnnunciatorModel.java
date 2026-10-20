package dev.bwr.mod.panel.client;

import dev.bwr.mod.panel.component.AnnunciatorData;
import dev.bwr.mod.panel.component.AnnunciatorLogic;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.model.BakedPanelModel;
import dev.bwr.mod.panel.model.GaugeScale;
import dev.bwr.mod.panel.model.Legends;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

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

    /** The bezel's width: 0.75 cells, less on a real size window (a tile is 1 to 2 cells across) so it never closes the tile. */
    private static float border(PanelComponent component) {
        return Math.min(BORDER, 0.1f * Math.min(component.w(), component.h()));
    }

    @Override
    public void buildStatic(QuadSink sink, PanelComponent component) {
        AnnunciatorData data = (AnnunciatorData) component.data();
        float w = component.w();
        float h = component.h();
        float border = border(component);
        sink.box(0, 0, w, h, 0, 1.0f, ModelDraw.HOUSING, false);
        sink.box(border, border, w - 2 * border, h - 2 * border, 0.6f, 1.5f, QuadSink.shade(data.color, 0.2f), false);
    }

    @Override
    public void renderDynamic(PanelRenderContext context, PanelComponent component) {
        AnnunciatorData data = (AnnunciatorData) component.data();
        float w = component.w();
        float h = component.h();
        float border = border(component);
        AnnunciatorLogic.Display display = data.logic.display();
        boolean lit = display == AnnunciatorLogic.Display.STEADY
                || display == AnnunciatorLogic.Display.FLASH && context.blinkOn(4.0f);
        if (lit) {
            context.box(border, border, w - 2 * border, h - 2 * border, 0.6f, 1.6f, data.color, true);
        }
        if (Double.isNaN(component.input(0))) {
            // The grey corner flag: 1.5 x 0.9 cells, proportionally smaller on a window under 8 cells across.
            float k = Math.min(1.0f, Math.min(w, h) / 8.0f);
            context.rect(w - 2.25f * k, border + 0.3f * k, 1.5f * k, 0.9f * k, 1.8f, ModelDraw.NO_SIGNAL, false);
        }
        if (context.lod() < PanelRenderContext.LOD_FULL || data.line1.isEmpty() && data.line2.isEmpty()) {
            return;
        }
        RenderCache cache = RenderCache.of(component);
        if (cache.fitRevision != component.revision()) {
            fit(component, data, context.rowAspect(), context.font()::width, cache);
        }
        int ink = lit ? 0x101010 : 0xC8C8C8;
        if (!data.line1.isEmpty()) {
            context.text(data.line1, cache.textX, cache.textY, cache.scale, TEXT_Z, ink, lit);
        }
        if (!data.line2.isEmpty()) {
            context.text(data.line2, cache.textX2, cache.textY2, cache.scale, TEXT_Z, ink, lit);
        }
    }

    /** The two lines as {@link #fit} places them, measured with {@link GaugeScale#textPixels}. */
    @Override
    public List<Legends.Item> textLayout(PanelComponent component, float rowAspect) {
        AnnunciatorData data = (AnnunciatorData) component.data();
        List<Legends.Item> items = new ArrayList<>();
        if (data.line1.isEmpty() && data.line2.isEmpty()) {
            return items;
        }
        RenderCache cache = new RenderCache();
        fit(component, data, rowAspect, GaugeScale::textPixels, cache);
        float cap = Legends.CAP_PIXELS * cache.scale;
        float half = Legends.CAP_PIXELS / 2.0f * cache.scale * rowAspect;
        if (!data.line1.isEmpty()) {
            items.add(new Legends.Item(data.line1, cache.textX, cache.textY + half, TEXT_Z, cap, 0.0f,
                    BakedPanelModel.Align.LEFT, 0x101010, false, data.color));
        }
        if (!data.line2.isEmpty()) {
            items.add(new Legends.Item(data.line2, cache.textX2, cache.textY2 + half, TEXT_Z, cap, 0.0f,
                    BakedPanelModel.Align.LEFT, 0x101010, false, data.color));
        }
        return items;
    }

    /**
     * One scale for both lines, each centred; a single line is centred in the tile. Once per revision. Every length is a
     * share of the tile (the margins scale with windows under 8 cells), so a real size window is the big one smaller.
     */
    private static void fit(PanelComponent component, AnnunciatorData data, float rowAspect, ToIntFunction<String> widths,
                            RenderCache cache) {
        float w = component.w();
        float h = component.h();
        float border = border(component);
        float margin = Math.min(1.0f, Math.min(w, h) / 8.0f);
        float innerW = w - 2 * border - 0.9f * margin;
        float innerH = h - 2 * border - 0.6f * margin;
        int lines = (data.line1.isEmpty() ? 0 : 1) + (data.line2.isEmpty() ? 0 : 1);
        float width1 = Math.max(1, widths.applyAsInt(data.line1));
        float width2 = Math.max(1, widths.applyAsInt(data.line2));
        float widest = Math.max(data.line1.isEmpty() ? 0 : width1, data.line2.isEmpty() ? 0 : width2);
        float lineCells = innerH / Math.max(1, lines);
        float scale = Math.min(innerW / widest, lineCells / (8.0f * rowAspect));
        float lineHeight = 8.0f * scale * rowAspect;
        float top = (h - lineHeight * lines) / 2.0f;
        cache.scale = scale;
        cache.textX = (w - width1 * scale) / 2.0f;
        cache.textY = top;
        cache.textX2 = (w - width2 * scale) / 2.0f;
        cache.textY2 = top + (data.line1.isEmpty() ? 0 : lineHeight);
        cache.fitRevision = component.revision();
    }
}
