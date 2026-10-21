package dev.bwr.mod.panel.client;

import dev.bwr.mod.panel.component.LedRodDisplayData;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.fcd.FcdSnapshot;
import dev.bwr.mod.reactor.CoreRodMap;

/**
 * The LED rod pattern display: a dark board with, for every rod in core layout, a tile holding two small LEDs, amber
 * over red. The board and the unlit LEDs (a dim amber and a dim red, so the grid reads when nothing is lit) are recorded once
 * per layout; the lit LEDs and the white frame of the selected rod are what the snapshot changes, so they are the "posed"
 * part: the block's vertex buffer is rebuilt only when {@link FcdSnapshot#visualKey()} or the mode changes, and a display with
 * nothing moving costs no model code (see {@link CoreDisplayModel}). Every length is a share of the tile, so a 1 cell tile
 * is the 3 cell one scaled down. Text is only the optional caption in the top margin (and "needs W x H" when the component is
 * too small for the core), printed through the block's text cache.
 */
final class LedRodDisplayModel extends CoreDisplayModel {
    private static final int FRAME_COLOUR = 0x15171A;
    private static final int BOARD = 0x0B0C0D;
    private static final int AMBER = 0xFFB000;
    private static final int RED = 0xFF3020;
    private static final int SELECTED = 0xF4F4EC;
    private static final int CAPTION_INK = 0xE8E8E8;
    private static final float DIM = 0.22f;
    /** LED radius, a share of the tile; the LEDs sit at 0.5 across and 0.30 and 0.70 down the tile. */
    private static final float RADIUS = 0.17f;
    private static final float AMBER_AT = 0.30f;
    private static final float RED_AT = 0.70f;
    private static final int SEGMENTS = 8;

    private static LedRodDisplayData led(PanelComponent component) {
        return (LedRodDisplayData) component.data();
    }

    private static float originX(PanelComponent component, int[] needed) {
        return dev.bwr.mod.panel.component.CoreDisplayData.centre(component.w(), needed[0]);
    }

    private static float originY(PanelComponent component, int[] needed) {
        return dev.bwr.mod.panel.component.CoreDisplayData.centre(component.h(), needed[1]);
    }

    private static boolean fits(PanelComponent component, int[] needed) {
        return component.w() >= needed[0] && component.h() >= needed[1];
    }

    @Override
    public void buildStatic(QuadSink sink, PanelComponent component) {
        LedRodDisplayData data = led(component);
        int[] needed = data.neededCells();
        if (!fits(component, needed)) {
            frame(sink, component);
            return;
        }
        CoreRodMap map = data.displayMap();
        float t = data.tileCells;
        float ox = originX(component, needed);
        float oy = originY(component, needed);
        sink.box(ox, oy, needed[0], needed[1], 0.0f, 0.8f, FRAME_COLOUR, false);
        float margin = t * 0.5f;
        sink.rect(ox + margin * 0.2f, oy + margin * 0.2f, needed[0] - margin * 0.4f, needed[1] - margin * 0.4f, 0.9f, BOARD, false);
        for (int rod = 0; rod < map.rodCount(); rod++) {
            float x = ox + (1 + map.col(rod)) * t;
            float y = oy + (1 + map.row(rod)) * t;
            led(sink, x, y, t, AMBER_AT, QuadSink.shade(AMBER, DIM), false);
            led(sink, x, y, t, RED_AT, QuadSink.shade(RED, DIM), false);
        }
    }

    private static void led(QuadSink sink, float x, float y, float t, float down, int colour, boolean lit) {
        Shapes.disc(sink, x + t * 0.5f, y + t * down, t * RADIUS, lit ? 1.1f : 1.0f, colour, SEGMENTS, lit);
    }

    @Override
    public long poseKey(PanelRenderContext context, PanelComponent component) {
        LedRodDisplayData data = led(component);
        if (!fits(component, data.neededCells())) {
            return 0L;
        }
        return 1L + data.snapshot().visualKey() * 31L + data.mode.hashCode();
    }

    @Override
    public void emitPosed(QuadSink sink, PanelComponent component) {
        LedRodDisplayData data = led(component);
        int[] needed = data.neededCells();
        if (!fits(component, needed)) {
            return;
        }
        FcdSnapshot snap = data.snapshot();
        CoreRodMap map = data.displayMap();
        boolean live = snap.formed() && snap.rodCount() == map.rodCount();
        float t = data.tileCells;
        float ox = originX(component, needed);
        float oy = originY(component, needed);
        for (int rod = 0; rod < map.rodCount(); rod++) {
            float x = ox + (1 + map.col(rod)) * t;
            float y = oy + (1 + map.row(rod)) * t;
            if (live && data.amber(snap, rod)) {
                led(sink, x, y, t, AMBER_AT, AMBER, true);
            }
            if (live && data.red(snap, rod)) {
                led(sink, x, y, t, RED_AT, RED, true);
            }
            if (live && snap.selectedRod(rod)) {
                float b = t * 0.06f;
                float m = t * 0.04f;
                sink.rect(x + m, y + m, t - 2 * m, b, 1.15f, SELECTED, true);
                sink.rect(x + m, y + t - m - b, t - 2 * m, b, 1.15f, SELECTED, true);
                sink.rect(x + m, y + m, b, t - 2 * m, 1.15f, SELECTED, true);
                sink.rect(x + t - m - b, y + m, b, t - 2 * m, 1.15f, SELECTED, true);
            }
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

    @Override
    public void renderLegends(PanelRenderContext context, PanelComponent component) {
        emitLegends(context, component);
    }

    @Override
    public void emitLegends(PanelRenderContext context, PanelComponent component) {
        LedRodDisplayData data = led(component);
        int[] needed = data.neededCells();
        if (!fits(component, needed)) {
            needsLegend(context, component, needed);
            return;
        }
        float t = data.tileCells;
        caption(context, data.caption, originX(component, needed) + needed[0] / 2.0f, originY(component, needed) + t * 0.5f, needed[0] * 0.9f,
                t * 0.8f, 1.0f, CAPTION_INK);
    }
}
