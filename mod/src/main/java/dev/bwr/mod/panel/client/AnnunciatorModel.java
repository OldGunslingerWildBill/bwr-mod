package dev.bwr.mod.panel.client;

import dev.bwr.mod.panel.component.AnnunciatorData;
import dev.bwr.mod.panel.component.AnnunciatorLogic;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.model.AnnunciatorBox;
import dev.bwr.mod.panel.model.AnnunciatorLayout;
import dev.bwr.mod.panel.model.BakedPanelModel;
import dev.bwr.mod.panel.model.GaugeScale;
import dev.bwr.mod.panel.model.Legends;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

/**
 * Annunciator window, drawn as part of a window box. A window is a backlit tile in a dark recessed frame; windows that
 * touch along a whole side share one frame (the edge words {@link AnnunciatorBoxes} works out from the neighbours on
 * the panel group, {@link AnnunciatorBox}): between two windows there is only a thin dark gap, and the bezel runs round
 * the outside of the group, each window drawing just its own part of it. Off, the tile is a dim version of its colour
 * with dim lettering; in alarm it flashes fast; once acknowledged it is steady; a held TEST button on a control module
 * of its group lights it too. Lit, the tile is full-bright with lettering in a dark tone of the same colour (light
 * lettering on a red one). The legend is up to four lines, each centred ({@link AnnunciatorLayout}); no signal puts a
 * grey flag in the corner. The text fit is computed once per revision and neighbour change.
 */
final class AnnunciatorModel implements ComponentModel {
    // Elevations: see AnnunciatorBox. The legend sits a hair above the lit face, not a fixed lift above the base.
    private static final float BASE_Z = AnnunciatorBox.BASE_Z;
    private static final float TILE_Z = AnnunciatorBox.TILE_Z;
    private static final float LIT_Z = AnnunciatorBox.LIT_Z;
    private static final float NOTCH_Z = AnnunciatorBox.NOTCH_Z;
    private static final float LIP_Z = AnnunciatorBox.LIP_Z;
    private static final float FLAG_Z = AnnunciatorBox.FLAG_Z;
    private static final float TEXT_Z = AnnunciatorBox.TEXT_Z;
    /** The dark recessed box of the reference annunciators, the bezel lip round it, and the window colour schemes' defaults. */
    static final int BOX_DARK = 0x17191A;
    static final int BOX_LIP = 0x3A413E;

    /** The legend as laid out for one revision and set of neighbours. */
    private static final class Legend {
        long key = Long.MIN_VALUE;
        String[] lines = new String[0];
        AnnunciatorLayout.Fit fit;
    }

    private static long legendKey(PanelComponent component, int edges) {
        return (long) component.revision() << 16 | edges;
    }

    @Override
    public long staticKey(PanelComponent component) {
        return RenderCache.of(component).boxEdges;
    }

    @Override
    public void buildStatic(QuadSink sink, PanelComponent component) {
        AnnunciatorData data = (AnnunciatorData) component.data();
        int edges = RenderCache.of(component).boxEdges;
        float w = component.w();
        float h = component.h();
        float[] in = AnnunciatorBox.insets(edges, w, h);
        float frame = AnnunciatorBox.frame(w, h);
        float lip = Math.max(0.03f, 0.3f * frame);
        sink.box(0, 0, w, h, 0, BASE_Z, BOX_DARK, false);
        // The bezel lip runs along each side that is not joined to a neighbour.
        if ((edges & AnnunciatorBox.LEFT) == 0) {
            sink.box(0, 0, lip, h, BASE_Z, LIP_Z, BOX_LIP, false);
        }
        if ((edges & AnnunciatorBox.RIGHT) == 0) {
            sink.box(w - lip, 0, lip, h, BASE_Z, LIP_Z, BOX_LIP, false);
        }
        if ((edges & AnnunciatorBox.TOP) == 0) {
            sink.box(0, 0, w, lip, BASE_Z, LIP_Z, BOX_LIP, false);
        }
        if ((edges & AnnunciatorBox.BOTTOM) == 0) {
            sink.box(0, h - lip, w, lip, BASE_Z, LIP_Z, BOX_LIP, false);
        }
        float tileW = w - in[0] - in[2];
        float tileH = h - in[1] - in[3];
        sink.box(in[0], in[1], tileW, tileH, BASE_Z, TILE_Z, AnnunciatorLayout.offBody(data.color), false);
        // An inside corner of the box: the frame of the neighbours turns the corner here, so it takes a piece of the tile.
        notch(sink, edges, AnnunciatorBox.NOTCH_TOP_LEFT, 0, 0, frame, lip, 1, 1);
        notch(sink, edges, AnnunciatorBox.NOTCH_TOP_RIGHT, w - frame, 0, frame, lip, -1, 1);
        notch(sink, edges, AnnunciatorBox.NOTCH_BOTTOM_RIGHT, w - frame, h - frame, frame, lip, -1, -1);
        notch(sink, edges, AnnunciatorBox.NOTCH_BOTTOM_LEFT, 0, h - frame, frame, lip, 1, -1);
    }

    /** One inside corner: a dark square over the tile corner and the lip piece that joins the neighbours' lips. */
    private static void notch(QuadSink sink, int edges, int bit, float x, float y, float frame, float lip, int dx, int dy) {
        if ((edges & bit) == 0) {
            return;
        }
        sink.box(x, y, frame, frame, BASE_Z, NOTCH_Z, BOX_DARK, false);
        sink.box(dx > 0 ? x : x + frame - lip, dy > 0 ? y : y + frame - lip, lip, lip, BASE_Z, LIP_Z, BOX_LIP, false);
    }

    @Override
    public void renderDynamic(PanelRenderContext context, PanelComponent component) {
        AnnunciatorData data = (AnnunciatorData) component.data();
        RenderCache cache = RenderCache.of(component);
        float w = component.w();
        float h = component.h();
        float[] in = AnnunciatorBox.insets(cache.boxEdges, w, h);
        AnnunciatorLogic.Display display = data.logic.display();
        boolean lit = display == AnnunciatorLogic.Display.STEADY
                || display == AnnunciatorLogic.Display.FLASH && context.blinkOn(4.0f)
                || AnnunciatorBoxes.testing(cache, data.group);
        if (lit) {
            context.box(in[0], in[1], w - in[0] - in[2], h - in[1] - in[3], TILE_Z, LIT_Z, data.color, true);
        }
        if (Double.isNaN(component.input(0))) {
            // The grey corner flag: 1.5 x 0.9 cells, proportionally smaller on a window under 8 cells across.
            float k = Math.min(1.0f, Math.min(w, h) / 8.0f);
            context.box(w - in[2] - 1.5f * k - 0.75f * k, in[1] + 0.3f * k, 1.5f * k, 0.9f * k, TILE_Z, FLAG_Z, ModelDraw.NO_SIGNAL, false);
        }
        if (context.lod() < PanelRenderContext.LOD_FULL) {
            return;
        }
        Legend legend = legend(context, component, data, cache);
        if (legend.lines.length == 0) {
            return;
        }
        int ink = AnnunciatorLayout.ink(data.color, lit);
        AnnunciatorLayout.Fit fit = legend.fit;
        for (int i = 0; i < fit.lines(); i++) {
            context.text(legend.lines[i], fit.x()[i], fit.y()[i], fit.scale(), TEXT_Z, ink, lit);
        }
    }

    private static Legend legend(PanelRenderContext context, PanelComponent component, AnnunciatorData data, RenderCache cache) {
        Legend legend = cache.scratch instanceof Legend existing ? existing : new Legend();
        cache.scratch = legend;
        long key = legendKey(component, cache.boxEdges);
        if (legend.key != key) {
            legend.lines = data.legend();
            legend.fit = fit(component, legend.lines, cache.boxEdges, context.rowAspect(), context.font()::width);
            legend.key = key;
        }
        return legend;
    }

    /** The legend lines as {@link #fit} places them, measured with {@link GaugeScale#textPixels}. */
    @Override
    public List<Legends.Item> textLayout(PanelComponent component, float rowAspect) {
        AnnunciatorData data = (AnnunciatorData) component.data();
        List<Legends.Item> items = new ArrayList<>();
        String[] lines = data.legend();
        if (lines.length == 0) {
            return items;
        }
        AnnunciatorLayout.Fit fit = fit(component, lines, RenderCache.of(component).boxEdges, rowAspect, GaugeScale::textPixels);
        float cap = Legends.CAP_PIXELS * fit.scale();
        float half = Legends.CAP_PIXELS / 2.0f * fit.scale() * rowAspect;
        for (int i = 0; i < fit.lines(); i++) {
            items.add(new Legends.Item(lines[i], fit.x()[i], fit.y()[i] + half, TEXT_Z, cap, 0.0f,
                    BakedPanelModel.Align.LEFT, AnnunciatorLayout.ink(data.color, true), false, data.color));
        }
        return items;
    }

    private static AnnunciatorLayout.Fit fit(PanelComponent component, String[] lines, int edges, float rowAspect,
                                             ToIntFunction<String> widths) {
        int[] pixels = new int[lines.length];
        for (int i = 0; i < lines.length; i++) {
            pixels[i] = widths.applyAsInt(lines[i]);
        }
        return AnnunciatorLayout.fit(component.w(), component.h(),
                AnnunciatorBox.insets(edges, component.w(), component.h()), pixels, rowAspect);
    }
}
