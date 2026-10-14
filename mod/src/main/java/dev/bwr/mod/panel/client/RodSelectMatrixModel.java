package dev.bwr.mod.panel.client;

import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.component.RodSelectMatrixData;
import dev.bwr.mod.panel.fcd.FcdLayout;
import dev.bwr.mod.panel.fcd.FcdSnapshot;
import dev.bwr.mod.panel.model.FcdModels;
import dev.bwr.mod.panel.model.PanelModels;
import dev.bwr.mod.reactor.CoreRodMap;

/**
 * The rod select matrix: one square illuminated pushbutton per rod in core layout, each with its coordinate engraved,
 * the button of the reactor's selected rod lit white (and pushed in, with the baked model). {@code rod_select_button}
 * is used when loaded, the procedural button otherwise. See {@link CoreDisplayModel} for what is cached.
 */
final class RodSelectMatrixModel extends CoreDisplayModel {
    private static final int CAP = 0xD6D8D0;
    private static final int LIT = 0xFFFFF4;

    private static final class State {
        final int revision;
        final int generation;
        final float aspect;
        final CoreRodMap map;
        final int tile;
        final int[] needed;
        final boolean fits;
        final float ox;
        final float oy;
        final Kit button;
        final OffsetSink offset = new OffsetSink();
        final float textZ;

        State(PanelComponent component, float aspect) {
            RodSelectMatrixData data = (RodSelectMatrixData) component.data();
            this.revision = component.revision();
            this.generation = PanelModels.generation();
            this.aspect = aspect;
            this.map = data.displayMap();
            this.tile = FcdLayout.clampTile(data.tileCells);
            this.needed = new int[] {FcdLayout.matrixWidth(map, tile), FcdLayout.matrixHeight(map, tile)};
            this.fits = component.w() >= needed[0] && component.h() >= needed[1];
            this.ox = dev.bwr.mod.panel.component.CoreDisplayData.centre(component.w(), needed[0]);
            this.oy = dev.bwr.mod.panel.component.CoreDisplayData.centre(component.h(), needed[1]);
            this.button = Kit.of(FcdModels.ROD_SELECT_BUTTON, tile, tile, aspect);
            this.textZ = button != null ? button.topZ() + 0.05f : 2.0f;
        }

        boolean current(PanelComponent component, float aspect) {
            return revision == component.revision() && generation == PanelModels.generation() && this.aspect == aspect;
        }
    }

    private static State state(PanelComponent component, float aspect) {
        RenderCache cache = RenderCache.of(component);
        if (cache.scratch instanceof State s && s.current(component, aspect)) {
            return s;
        }
        State created = new State(component, aspect);
        cache.scratch = created;
        return created;
    }

    private static State existing(PanelComponent component) {
        return RenderCache.of(component).scratch instanceof State s ? s : null;
    }

    @Override
    public void buildStatic(QuadSink sink, PanelComponent component) {
        buildStatic(sink, component, 1.0f);
    }

    @Override
    public void buildStatic(QuadSink sink, PanelComponent component, float rowAspect) {
        State s = state(component, rowAspect);
        if (!s.fits) {
            frame(sink, component);
            return;
        }
        float t = s.tile;
        for (int rod = 0; rod < s.map.rodCount(); rod++) {
            float x = s.ox + FcdLayout.buttonX(s.map, s.tile, rod);
            float y = s.oy + FcdLayout.buttonY(s.map, s.tile, rod);
            if (s.button != null) {
                s.button.emitStatic(sink, s.offset, x, y); // the caps are posed: the selected one is pressed in and glows
            } else {
                float m = t * 0.04f;
                sink.box(x + m, y + m, t - 2 * m, t - 2 * m, 0.0f, 1.2f, 0x3C4045, false);
                sink.box(x + 4 * m, y + 4 * m, t - 8 * m, t - 8 * m, 1.2f, 1.8f, CAP, false);
            }
        }
    }

    @Override
    public long poseKey(PanelRenderContext context, PanelComponent component) {
        State s = existing(component);
        if (s == null || !s.fits) {
            return 0L;
        }
        // Only the selected rod and whether a core is behind the buttons show: rods moving never rebuild the buffer.
        FcdSnapshot snap = ((RodSelectMatrixData) component.data()).snapshot();
        return 1L + snap.selected() * 2L + (snap.formed() ? 1L : 0L);
    }

    @Override
    public void emitPosed(QuadSink sink, PanelComponent component) {
        State s = existing(component);
        if (s == null || !s.fits) {
            return;
        }
        FcdSnapshot snap = ((RodSelectMatrixData) component.data()).snapshot();
        boolean live = snap.formed() && snap.rodCount() == s.map.rodCount();
        int selected = live && snap.selected() >= 1 && snap.selected() <= s.map.rodCount() ? snap.selected() - 1 : -1;
        if (s.button != null && s.button.has("cap")) {
            // Every cap is posed: pale, or white and pushed in for the selected rod.
            for (int rod = 0; rod < s.map.rodCount(); rod++) {
                float x = s.ox + FcdLayout.buttonX(s.map, s.tile, rod);
                float y = s.oy + FcdLayout.buttonY(s.map, s.tile, rod);
                if (rod == selected) {
                    s.button.emitPart(sink, s.offset, "cap", x, y, LIT, 1.0f, true, 1.0f);
                } else {
                    s.button.emitPart(sink, s.offset, "cap", x, y, CAP, 1.0f, false, 0.0f);
                }
            }
        } else if (selected >= 0) {
            float x = s.ox + FcdLayout.buttonX(s.map, s.tile, selected);
            float y = s.oy + FcdLayout.buttonY(s.map, s.tile, selected);
            float m = s.tile * 0.16f;
            sink.rect(x + m, y + m, s.tile - 2 * m, s.tile - 2 * m, 1.9f, LIT, true);
        }
    }

    @Override
    public void renderDynamic(PanelRenderContext context, PanelComponent component) {
        emitPosed(context, component);
        if (context.lod() >= PanelRenderContext.LOD_FULL) {
            emitLegends(context, component);
        }
    }

    @Override
    public void renderLive(PanelRenderContext context, PanelComponent component) {
    }

    @Override
    public boolean legendsCached() {
        return CoreDisplayModel.LEGEND_CACHE;
    }

    /** The per frame legends: used when the block's text cache is off or failed. */
    @Override
    public void renderLegends(PanelRenderContext context, PanelComponent component) {
        emitLegends(context, component);
    }

    @Override
    public void emitLegends(PanelRenderContext context, PanelComponent component) {
        State s = existing(component);
        if (s == null) {
            return;
        }
        if (!s.fits) {
            needsLegend(context, component, s.needed);
            return;
        }
        float t = s.tile;
        for (int rod = 0; rod < s.map.rodCount(); rod++) {
            float x = s.ox + FcdLayout.buttonX(s.map, s.tile, rod);
            float y = s.oy + FcdLayout.buttonY(s.map, s.tile, rod);
            fitted(context, s.map.label(rod), x + t / 2.0f, y + t / 2.0f, t * 0.74f, t * 0.4f, s.textZ, INK);
        }
    }
}
