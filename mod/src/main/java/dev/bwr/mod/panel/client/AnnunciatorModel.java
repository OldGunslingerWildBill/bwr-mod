package dev.bwr.mod.panel.client;

import com.mojang.logging.LogUtils;
import dev.bwr.mod.panel.component.AnnunciatorData;
import dev.bwr.mod.panel.component.AnnunciatorLogic;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.model.AnnunciatorBox;
import dev.bwr.mod.panel.model.AnnunciatorLayout;
import dev.bwr.mod.panel.model.BakedPanelModel;
import dev.bwr.mod.panel.model.GaugeScale;
import dev.bwr.mod.panel.model.Legends;
import dev.bwr.mod.panel.model.PanelModels;
import org.slf4j.Logger;

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
 *
 * <h3>The Columbia style</h3>
 * A window whose Style is Columbia is an ivory tray tile on a black plate ({@link AnnunciatorTileBox}, from the tile
 * model): a window at least twice as wide as tall is one of the two alarms of a split tile, and the box carries row and
 * column numerals in its plate margins, a nameplate under it and a latch beside it. What changes with state is drawn by
 * the same rules as above, over the tile's face: lit, the face is the window's colour, full-bright, with the same
 * lettering; dark, it is ivory with black lettering. A classic window, a missing tile model and a window whose panel
 * group has not been scanned yet all fall back safely (the procedural window, or a tile that is a box by itself).
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
    /** The Columbia style: the white numerals in the plate margins, and how far text on the plate stands off it. */
    static final int NUMERAL_RGB = ModelDraw.ENGRAVED;
    private static final float PLATE_TEXT_LIFT = 0.05f;
    /** The first line of a box's nameplate ("ANN PANEL" on Columbia's boxes); the second is the box's name. */
    static final String BOX_TITLE_KEY = "text.bwr.annunciator.box_title";
    private static final Logger LOGGER = LogUtils.getLogger();
    /**
     * Set if drawing the Columbia style ever throws: the style is then off until the game restarts and every window
     * draws classic. The style is decoration, and a client that cannot draw a panel is worse than a plain window.
     */
    private static volatile boolean tileBroken;

    private static void tileFailed(RuntimeException e) {
        if (!tileBroken) {
            tileBroken = true;
            LOGGER.error("The Columbia annunciator style failed to draw; it is off until the game restarts and windows draw classic", e);
        }
    }

    /** The legend as laid out for one revision and set of neighbours. */
    private static final class Legend {
        long key = Long.MIN_VALUE;
        String[] lines = new String[0];
        AnnunciatorLayout.Fit fit;

        // The Columbia style's own state, kept apart from the classic fit above so the two never rebuild each other.
        long tileKey = Long.MIN_VALUE;
        /** The tile the window draws as; null when it draws as the procedural window after all (no usable model). */
        AnnunciatorTileBox.Shape shape;
        String[] tileLines = new String[0];
        AnnunciatorLayout.Fit tileFit;
        /** The row numeral left of the window and the column numeral above it; null where that side is not the box's edge. */
        String rowText;
        float rowX;
        float rowY;
        String columnText;
        float columnX;
        float columnY;
        float numeralScale;
        /** The nameplate's two lines; null on every window but the one that draws the nameplate (and for an empty line). */
        String nameTop;
        float nameTopX;
        float nameTopY;
        String nameBottom;
        float nameBottomX;
        float nameBottomY;
        float nameScale;
        float nameZ;
    }

    private static long legendKey(PanelComponent component, int edges) {
        return (long) component.revision() << 16 | edges;
    }

    @Override
    public long staticKey(PanelComponent component) {
        RenderCache cache = RenderCache.of(component);
        if (component.data() instanceof AnnunciatorData data && data.overhangs()) {
            // The Columbia mesh also follows the window's place in its box: which half of a split tile it is, the tile
            // width the margins are sized by, and whether it draws the nameplate or the latch.
            AnnunciatorBox.Tile tile = cache.boxTile;
            return cache.boxEdges | (tile == null ? 0L : tile.key() + 1L) << 8;
        }
        return cache.boxEdges;
    }

    /**
     * The Columbia tile {@code component} draws as, or null for the procedural window: its style is classic, or the
     * tile model is missing or unusable. A window not yet placed in a box by {@link AnnunciatorBoxes} is a box by itself.
     */
    private static AnnunciatorTileBox.Shape tileShape(PanelComponent component, AnnunciatorData data, RenderCache cache) {
        if (tileBroken || !data.overhangs()) {
            return null;
        }
        AnnunciatorBox.Tile tile = cache.boxTile != null ? cache.boxTile : AnnunciatorBox.Tile.lone(component.w(), component.h());
        return AnnunciatorTileBox.shape(PanelModels.get(AnnunciatorData.WINDOW_MODEL), component.w(), component.h(), tile);
    }

    @Override
    public void buildStatic(QuadSink sink, PanelComponent component) {
        AnnunciatorData data = (AnnunciatorData) component.data();
        RenderCache cache = RenderCache.of(component);
        int edges = cache.boxEdges;
        float w = component.w();
        float h = component.h();
        try {
            AnnunciatorTileBox.Shape shape = tileShape(component, data, cache);
            if (shape != null) {
                AnnunciatorTileBox.emit(sink, shape, edges, w, h, AnnunciatorLayout.columbiaFace(data.color));
                return;
            }
        } catch (RuntimeException e) {
            tileFailed(e);
        }
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
        if (data.overhangs() && !tileBroken) {
            try {
                Legend tile = tileLegend(context, component, data, cache);
                if (tile.shape != null) {
                    renderTile(context, component, data, cache, tile);
                    return;
                }
            } catch (RuntimeException e) {
                tileFailed(e);
            }
        }
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

    // ---- the Columbia style: what changes with state, and the text round the box

    /** The moving parts of a Columbia tile: the lit face, the no-signal flag, the legend, and the box's numerals and nameplate. */
    private static void renderTile(PanelRenderContext context, PanelComponent component, AnnunciatorData data, RenderCache cache,
                                   Legend legend) {
        AnnunciatorTileBox.Shape shape = legend.shape;
        float w = component.w();
        float h = component.h();
        float[] in = shape.insets;
        AnnunciatorLogic.Display display = data.logic.display();
        boolean lit = display == AnnunciatorLogic.Display.STEADY
                || display == AnnunciatorLogic.Display.FLASH && context.blinkOn(4.0f)
                || AnnunciatorBoxes.testing(cache, data.group);
        if (lit) {
            context.box(in[0], in[1], w - in[0] - in[2], h - in[1] - in[3], TILE_Z, LIT_Z, data.color, true);
        }
        if (Double.isNaN(component.input(0))) {
            float k = Math.min(1.0f, Math.min(w, h) / 8.0f);
            context.box(w - in[2] - 1.5f * k - 0.75f * k, in[1] + 0.3f * k, 1.5f * k, 0.9f * k, TILE_Z, FLAG_Z, ModelDraw.NO_SIGNAL, false);
        }
        if (context.lod() < PanelRenderContext.LOD_FULL) {
            return;
        }
        AnnunciatorLayout.Fit fit = legend.tileFit;
        if (fit != null) {
            // Dark, the tile is ivory with black lettering whatever its colour; lit, it reads as the procedural window does.
            int ink = lit ? AnnunciatorLayout.ink(data.color, true) : AnnunciatorLayout.COLUMBIA_INK;
            for (int i = 0; i < fit.lines(); i++) {
                context.text(legend.tileLines[i], fit.x()[i], fit.y()[i], fit.scale(), TEXT_Z, ink, lit);
            }
        }
        float onPlate = shape.plateZ + PLATE_TEXT_LIFT;
        if (legend.rowText != null) {
            context.text(legend.rowText, legend.rowX, legend.rowY, legend.numeralScale, onPlate, NUMERAL_RGB, false);
        }
        if (legend.columnText != null) {
            context.text(legend.columnText, legend.columnX, legend.columnY, legend.numeralScale, onPlate, NUMERAL_RGB, false);
        }
        if (legend.nameTop != null) {
            context.text(legend.nameTop, legend.nameTopX, legend.nameTopY, legend.nameScale, legend.nameZ, AnnunciatorLayout.COLUMBIA_INK, false);
        }
        if (legend.nameBottom != null) {
            context.text(legend.nameBottom, legend.nameBottomX, legend.nameBottomY, legend.nameScale, legend.nameZ, AnnunciatorLayout.COLUMBIA_INK, false);
        }
    }

    /**
     * The Columbia state of a window, worked out again only when something it depends on changed: its settings, its
     * neighbours, its place in its box, the box's name, the loaded models or the panel's slope.
     */
    private static Legend tileLegend(PanelRenderContext context, PanelComponent component, AnnunciatorData data, RenderCache cache) {
        Legend legend = cache.scratch instanceof Legend existing ? existing : new Legend();
        cache.scratch = legend;
        AnnunciatorBox.Tile placed = cache.boxTile;
        String boxName = cache.boxName == null ? "" : cache.boxName;
        long key = component.revision();
        key = key * 31 + cache.boxEdges;
        key = key * 31 + (placed == null ? -1L : placed.key());
        key = key * 31 + (placed == null ? 0L : placed.row() * 4099L + placed.column());
        key = key * 31 + boxName.hashCode();
        key = key * 31 + PanelModels.generation();
        key = key * 31 + Float.floatToIntBits(context.rowAspect());
        if (legend.tileKey == key) {
            return legend;
        }
        legend.tileKey = key;
        legend.rowText = null;
        legend.columnText = null;
        legend.nameTop = null;
        legend.nameBottom = null;
        legend.tileFit = null;
        AnnunciatorTileBox.Shape shape = tileShape(component, data, cache);
        legend.shape = shape;
        if (shape == null) {
            return legend;
        }
        float w = component.w();
        float h = component.h();
        legend.tileLines = data.legend();
        if (legend.tileLines.length > 0) {
            int[] pixels = new int[legend.tileLines.length];
            for (int i = 0; i < pixels.length; i++) {
                pixels[i] = context.font().width(legend.tileLines[i]);
            }
            legend.tileFit = AnnunciatorLayout.fit(w, h, shape.insets, pixels, context.rowAspect());
        }
        AnnunciatorBox.Tile tile = shape.tile;
        float unit = tile.unit();
        // The numerals: the row number left of a window on the box's left edge, "-column" above one on its top edge.
        float cap = AnnunciatorBox.NUMERAL_CAP * unit;
        legend.numeralScale = cap / Legends.CAP_PIXELS;
        if ((cache.boxEdges & AnnunciatorBox.LEFT) == 0) {
            legend.rowText = Integer.toString(tile.row() + 1);
            legend.rowX = -shape.marginLeft / 2.0f - context.font().width(legend.rowText) * legend.numeralScale / 2.0f;
            legend.rowY = h / 2.0f - cap / 2.0f;
        }
        if ((cache.boxEdges & AnnunciatorBox.TOP) == 0) {
            legend.columnText = "-" + (tile.column() + 1);
            legend.columnX = w / 2.0f - context.font().width(legend.columnText) * legend.numeralScale / 2.0f;
            legend.columnY = -shape.marginTop / 2.0f - cap / 2.0f;
        }
        if (tile.nameplate()) {
            // Two centred lines, the title and the box's name; the title alone, in the middle, for a box without a name.
            String title = boxTitle();
            int titlePixels = Math.max(1, context.font().width(title));
            int namePixels = boxName.isEmpty() ? 0 : Math.max(1, context.font().width(boxName));
            float plateW = AnnunciatorBox.NAMEPLATE_W * unit;
            float plateH = AnnunciatorBox.NAMEPLATE_H * unit;
            float top = h + AnnunciatorBox.NAMEPLATE_GAP * unit;
            float centre = tile.nameX2() / 2.0f;
            float scale = Math.min(AnnunciatorBox.NAMEPLATE_CAP * unit / Legends.CAP_PIXELS, 0.9f * plateW / Math.max(titlePixels, namePixels));
            float nameCap = Legends.CAP_PIXELS * scale;
            legend.nameScale = scale;
            legend.nameZ = shape.plateZ + AnnunciatorTileBox.nameplateThickness(unit) + PLATE_TEXT_LIFT;
            if (!title.isEmpty()) {
                legend.nameTop = title;
                legend.nameTopX = centre - titlePixels * scale / 2.0f;
                legend.nameTopY = top + plateH * (boxName.isEmpty() ? 0.5f : 0.29f) - nameCap / 2.0f;
            }
            if (!boxName.isEmpty()) {
                legend.nameBottom = boxName;
                legend.nameBottomX = centre - namePixels * scale / 2.0f;
                legend.nameBottomY = top + plateH * (title.isEmpty() ? 0.5f : 0.71f) - nameCap / 2.0f;
            }
        }
        return legend;
    }

    /** The first line of a nameplate, in the player's language. Only ever called while drawing (a client class). */
    private static String boxTitle() {
        return net.minecraft.client.resources.language.I18n.get(BOX_TITLE_KEY);
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
        RenderCache cache = RenderCache.of(component);
        AnnunciatorTileBox.Shape shape = null;
        try {
            shape = tileShape(component, data, cache);
        } catch (RuntimeException e) {
            tileFailed(e);
        }
        AnnunciatorLayout.Fit fit;
        if (shape != null) {
            // A Columbia tile: the legend is laid out in the tile's face (the margin numerals and the nameplate are not
            // part of the window's legend and are not listed).
            int[] pixels = new int[lines.length];
            for (int i = 0; i < lines.length; i++) {
                pixels[i] = GaugeScale.textPixels(lines[i]);
            }
            fit = AnnunciatorLayout.fit(component.w(), component.h(), shape.insets, pixels, rowAspect);
        } else {
            fit = fit(component, lines, cache.boxEdges, rowAspect, GaugeScale::textPixels);
        }
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
