package dev.bwr.mod.panel.plant;

import dev.bwr.mod.panel.model.GaugeScale;
import dev.bwr.mod.panel.model.Legends;

import java.util.ArrayList;
import java.util.List;

/**
 * What the plant computer's screen looks like, as shapes and text lines in the component's own cells. The client model draws
 * them (and caches the result until the content changes); the tests record them and check that nothing leaves the screen. No
 * Minecraft rendering code is in here, and the width of text comes from {@link GaugeScale#textPixels}, so the layout is the
 * same on both sides.
 *
 * <p>Text follows the panel text rules: one size for the whole screen, a share of the character cell, which is in turn a
 * share of the wrench's Text size ({@link PlantLayout}); a line that is too long for its room is made smaller to fit, never
 * below {@link GaugeScale#MIN_TEXT_SHARE} of that size, and lies flat on the face at one height ({@link #TEXT_Z}).
 */
public final class PlantScreen {
    private PlantScreen() {
    }

    /** Where shapes and text go. All lengths are panel cells; {@code scale} is cells per font pixel. */
    public interface Sink {
        void rect(float x, float y, float w, float h, float z, int rgb, boolean emissive);

        /** A straight line of {@code thickness} cells. */
        void line(float x0, float y0, float x1, float y1, float thickness, float z, int rgb, boolean emissive);

        /** A line of text whose top left (of the glyph box) is at ({@code x}, {@code y}). */
        void text(String text, float x, float y, float scale, float z, int rgb);
    }

    // elevations, 1/16 block: the bezel is below 0.8, the screen at 0.9
    public static final float SCREEN_Z = 0.9f;
    public static final float SHAPE_Z = 1.0f;
    public static final float DETAIL_Z = 1.1f;
    public static final float LINE_Z = 1.2f;
    public static final float TEXT_Z = 1.35f;

    public static final int BEZEL = 0x15181A;
    public static final int SCREEN = 0x050809;
    public static final int HEADER = 0x0F3340;
    public static final int TRACK = 0x14222A;
    public static final int GRID = 0x2A3A42;
    public static final int KEY = 0x1E2E36;
    public static final int DIM_TEXT = 0x9DA8AE;
    public static final int TITLE = 0x30D8E8;
    private static final int[] TILE_FILL = {0x1A2429, 0x2BC25A, 0xFFB020, 0xE23B2E};
    private static final int[] TILE_INK = {0x7C898F, 0x04140A, 0x1C1200, 0x1E0502};

    /** Cells per font pixel of a character cell this wide: six font pixels fill 92 percent of it. */
    public static float baseScale(float cellW) {
        return cellW * 0.92f / 6.0f;
    }

    private static float px(String text) {
        return GaugeScale.textPixels(text);
    }

    /** The scale a line of {@code pixels} font pixels takes to fit {@code room} cells, from {@code scale} down to its least. */
    static float fit(float pixels, float room, float scale) {
        if (pixels <= 0.0f) {
            return scale;
        }
        return Math.max(scale * GaugeScale.MIN_TEXT_SHARE, Math.min(scale, room / pixels));
    }

    /**
     * Draws {@code state} (its shown page) for a component of {@code w x h} cells. A program display with nothing defined
     * shows "NO PROGRAM".
     */
    public static void draw(PlantState state, PlantLayout layout, int w, int h, boolean programMode, String noProgram,
                            String noProgramHint, Sink sink) {
        sink.rect(0.0f, 0.0f, w, h, SCREEN_Z, SCREEN, false);
        float scale = baseScale(layout.cellW());
        if (programMode && state.isEmpty()) {
            noProgram(layout, w, h, scale, noProgram, noProgramHint, sink);
            return;
        }
        PlantState.Page page = state.page(state.selected());
        if (page == null) {
            return;
        }
        if (!page.title.isEmpty()) {
            header(page.title, state, layout, scale, programMode, sink);
        }
        for (PlantElement e : page.elements.values()) {
            switch (e.kind) {
                case TEXT -> text(e, layout, scale, sink);
                case FIELD -> field(e, layout, scale, sink);
                case BAR -> bar(e, layout, scale, sink);
                case TREND -> trend(e, layout, scale, sink);
                case TILE -> tile(e, layout, scale, sink);
                case KEY -> key(e, layout, scale, sink);
            }
        }
    }

    // ---- helpers

    private static float x(PlantLayout l, int col1) {
        return l.left() + (col1 - 1) * l.cellW();
    }

    private static float y(PlantLayout l, int row1) {
        return l.top() + (row1 - 1) * l.cellH();
    }

    /**
     * Prints one line of text vertically centred in a band {@code h} cells high at {@code top}, anchored at {@code anchor}
     * ({@code align} -1: its left edge, 0: its middle, 1: its right edge). It is made smaller to fit {@code room} cells, down to
     * {@link GaugeScale#MIN_TEXT_SHARE} of {@code scale}, and if it is still too long the end is cut: nothing leaves its room.
     */
    private static void put(Sink sink, String text, float anchor, int align, float room, float top, float h, float scale, int rgb) {
        if (text.isEmpty() || room <= 0.0f) {
            return;
        }
        String t = text;
        float s = fit(px(t), room, scale);
        while (t.length() > 1 && (px(t) - 1.0f) * s > room) {
            t = t.substring(0, t.length() - 1);
        }
        float width = (px(t) - 1.0f) * s;
        float x = align < 0 ? anchor : align > 0 ? anchor - width : anchor - width / 2.0f;
        sink.text(t, x, top + (h - Legends.CAP_PIXELS * s) / 2.0f, s, TEXT_Z, rgb);
    }

    private static void line(Sink sink, String text, float x, float top, float h, float scale, int rgb, float room) {
        put(sink, text, x, -1, room, top, h, scale, rgb);
    }

    private static void right(Sink sink, String text, float rightEdge, float top, float h, float scale, int rgb, float room) {
        put(sink, text, rightEdge, 1, room, top, h, scale, rgb);
    }

    private static void centred(Sink sink, String text, float centre, float top, float h, float scale, int rgb, float room) {
        put(sink, text, centre, 0, room, top, h, scale, rgb);
    }

    private static void header(String title, PlantState state, PlantLayout l, float scale, boolean programMode, Sink sink) {
        float top = y(l, 1);
        float width = l.cols() * l.cellW();
        sink.rect(l.left(), top, width, l.cellH(), SHAPE_Z, HEADER, false);
        String pages = programMode && state.definedPages() > 1 ? "PAGE " + (state.selected() + 1) : "";
        float pagesWidth = pages.isEmpty() ? 0.0f : (px(pages) - 1.0f) * scale;
        float room = width - pagesWidth - l.cellW() * 1.5f;
        line(sink, title, l.left() + l.cellW() * 0.5f, top, l.cellH(), scale, TITLE, room);
        if (!pages.isEmpty()) {
            right(sink, pages, l.left() + width - l.cellW() * 0.5f, top, l.cellH(), scale, DIM_TEXT, pagesWidth + 0.5f);
        }
    }

    private static void noProgram(PlantLayout l, int w, int h, float scale, String title, String hint, Sink sink) {
        float width = l.cols() * l.cellW();
        centred(sink, title, w / 2.0f, h / 2.0f - l.cellH() * 1.6f, l.cellH() * 2.0f, scale * 2.4f, PlantScreen.TITLE, width * 0.8f);
        // The hint wraps at word breaks into lines that fit the grid.
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : hint.split(" ")) {
            if (current.length() > 0 && current.length() + 1 + word.length() > Math.max(8, l.cols() - 2)) {
                lines.add(current.toString());
                current.setLength(0);
            }
            if (current.length() > 0) {
                current.append(' ');
            }
            current.append(word);
        }
        if (current.length() > 0) {
            lines.add(current.toString());
        }
        float top = h / 2.0f + l.cellH() * 0.4f;
        for (int i = 0; i < lines.size() && top + l.cellH() < h - l.top(); i++) {
            centred(sink, lines.get(i), w / 2.0f, top, l.cellH(), scale, DIM_TEXT, width);
            top += l.cellH();
        }
    }

    private static void text(PlantElement e, PlantLayout l, float scale, Sink sink) {
        float room = (l.cols() - e.col + 1) * l.cellW();
        line(sink, e.text, x(l, e.col), y(l, e.row), l.cellH(), scale, PlantColors.rgb(e.colour), room);
    }

    private static void field(PlantElement e, PlantLayout l, float scale, Sink sink) {
        float left = x(l, e.col);
        float width = e.w * l.cellW();
        float top = y(l, e.row);
        String value = e.valueText() + (e.unit.isEmpty() || e.valueText().equals(PlantFormat.NO_DATA) ? "" : " " + e.unit);
        float pixels = px(e.text) + px(value) + (e.text.isEmpty() ? 0.0f : 3.0f);
        float s = fit(pixels, width, scale);
        float valueWidth = Math.min(width, (px(value) - 1.0f) * s);
        right(sink, value, left + width, top, l.cellH(), s, PlantColors.rgb(e.valueColour()), width);
        line(sink, e.text, left, top, l.cellH(), s, PlantScreen.DIM_TEXT, width - valueWidth - l.cellW() * 0.5f);
    }

    private static void bar(PlantElement e, PlantLayout l, float scale, Sink sink) {
        float left = x(l, e.col);
        float width = e.w * l.cellW();
        float top = y(l, e.row);
        boolean showValue = e.state == 0;
        String value = e.valueText() + (e.unit.isEmpty() || e.valueText().equals(PlantFormat.NO_DATA) ? "" : " " + e.unit);
        float captionPx = e.text.isEmpty() ? 0.0f : px(e.text) + 3.0f;
        float valuePx = showValue ? px(value) + 3.0f : 0.0f;
        float s = scale;
        // The bar keeps at least a third of the width; the text shrinks to leave it.
        float textRoom = width * 0.66f;
        if ((captionPx + valuePx) * s > textRoom) {
            s = fit(captionPx + valuePx, textRoom, scale);
        }
        float captionW = e.text.isEmpty() ? 0.0f : Math.min(textRoom, (px(e.text) - 1.0f) * s);
        float valueW = showValue ? Math.min(textRoom, (px(value) - 1.0f) * s) : 0.0f;
        float gap = l.cellW() * 0.6f;
        float barLeft = left + (captionW > 0.0f ? captionW + gap : 0.0f);
        float barRight = left + width - (valueW > 0.0f ? valueW + gap : 0.0f);
        line(sink, e.text, left, top, l.cellH(), s, PlantScreen.DIM_TEXT, captionW + 0.5f);
        if (showValue) {
            right(sink, value, left + width, top, l.cellH(), s, PlantColors.rgb(e.valueColour()), valueW + 0.5f);
        }
        float barH = l.cellH() * 0.5f;
        float barTop = top + (l.cellH() - barH) / 2.0f;
        float barW = Math.min(left + width - barLeft, Math.max(l.cellW(), barRight - barLeft));
        sink.rect(barLeft, barTop, barW, barH, SHAPE_Z, TRACK, false);
        float fraction = e.fraction();
        if (fraction > 0.0f) {
            sink.rect(barLeft, barTop, barW * fraction, barH, DETAIL_Z, PlantColors.rgb(e.valueColour()), true);
        }
        mark(sink, e, e.warnLow, barLeft, barTop, barW, barH, PlantColors.AMBER);
        mark(sink, e, e.warnHigh, barLeft, barTop, barW, barH, PlantColors.AMBER);
        mark(sink, e, e.alarmLow, barLeft, barTop, barW, barH, PlantColors.RED);
        mark(sink, e, e.alarmHigh, barLeft, barTop, barW, barH, PlantColors.RED);
    }

    private static void mark(Sink sink, PlantElement e, float limit, float left, float top, float width, float height, int colour) {
        if (Float.isNaN(limit) || limit <= e.min || limit >= e.max) {
            return;
        }
        float at = left + width * (limit - e.min) / (e.max - e.min);
        sink.rect(at - 0.1f, top - height * 0.2f, 0.2f, height * 1.4f, LINE_Z, PlantColors.rgb(colour), true);
    }

    private static void trend(PlantElement e, PlantLayout l, float scale, Sink sink) {
        float left = x(l, e.col);
        float top = y(l, e.row);
        float width = e.w * l.cellW();
        float height = e.h * l.cellH();
        float pad = l.cellW() * 0.25f;
        sink.rect(left, top, width, height, SHAPE_Z, GRID, false);
        sink.rect(left + 0.25f, top + 0.25f, width - 0.5f, height - 0.5f, DETAIL_Z - 0.05f, TRACK, false);
        String latest = Float.isNaN(e.value) ? "" : PlantFormat.number(e.value, e.decimals) + (e.unit.isEmpty() ? "" : " " + e.unit);
        float s = fit(px(e.text) + px(latest) + 3.0f, width - 2.0f * pad, scale);
        float latestW = Math.min(width - 2.0f * pad, (px(latest) - 1.0f) * s);
        right(sink, latest, left + width - pad, top, l.cellH(), s, PlantColors.rgb(e.colour), width - 2.0f * pad);
        line(sink, e.text, left + pad, top, l.cellH(), s, PlantScreen.DIM_TEXT, width - 2.0f * pad - latestW - l.cellW() * 0.5f);
        // The axes: the maximum and minimum at the left, a line half way.
        String max = PlantFormat.number(e.max, e.decimals);
        String min = PlantFormat.number(e.min, e.decimals);
        float axisScale = scale * 0.8f;
        float axisWidth = Math.max(px(max), px(min)) * axisScale + pad;
        float plotLeft = left + pad + axisWidth;
        float plotRight = left + width - pad;
        float plotTop = top + l.cellH() + pad * 0.5f;
        float plotBottom = top + height - pad;
        if (plotRight - plotLeft < 2.0f * l.cellW() || plotBottom - plotTop < l.cellH()) {
            return;
        }
        float axisH = Legends.CAP_PIXELS * axisScale;
        sink.text(max, left + pad, plotTop - 0.1f, axisScale, TEXT_Z, DIM_TEXT);
        sink.text(min, left + pad, plotBottom - axisH, axisScale, TEXT_Z, DIM_TEXT);
        sink.rect(plotLeft, plotTop, plotRight - plotLeft, 0.12f, DETAIL_Z, GRID, false);
        sink.rect(plotLeft, (plotTop + plotBottom) / 2.0f, plotRight - plotLeft, 0.12f, DETAIL_Z, GRID, false);
        sink.rect(plotLeft, plotBottom - 0.12f, plotRight - plotLeft, 0.12f, DETAIL_Z, GRID, false);
        if (e.count < 1) {
            return;
        }
        int ink = PlantColors.rgb(e.colour);
        float px0 = 0;
        float py0 = 0;
        for (int i = 0; i < e.count; i++) {
            float fx = e.len > 1 ? (float) (e.len - e.count + i) / (e.len - 1) : 1.0f;
            float xx = plotLeft + (plotRight - plotLeft) * fx;
            float yy = plotBottom - (plotBottom - plotTop) * (e.points[i] / 65535.0f);
            if (i > 0) {
                sink.line(px0, py0, xx, yy, Math.max(0.2f, l.cellH() * 0.1f), LINE_Z, ink, true);
            }
            px0 = xx;
            py0 = yy;
        }
    }

    private static void tile(PlantElement e, PlantLayout l, float scale, Sink sink) {
        float inset = Math.min(l.cellW(), l.cellH()) * 0.15f;
        float left = x(l, e.col) + inset;
        float top = y(l, e.row) + inset;
        float width = e.w * l.cellW() - 2.0f * inset;
        float height = e.h * l.cellH() - 2.0f * inset;
        int state = Math.max(0, Math.min(3, e.state));
        sink.rect(left, top, width, height, SHAPE_Z, PlantColors.rgb(PlantColors.ofState(state)), state != PlantColors.STATE_OFF);
        sink.rect(left + 0.3f, top + 0.3f, width - 0.6f, height - 0.6f, DETAIL_Z - 0.05f, TILE_FILL[state], state != PlantColors.STATE_OFF);
        label(e.text, left, top, width, height, l, scale, TILE_INK[state], sink);
    }

    /** A label centred in a box, on two lines at a space when it is wider than the box. */
    private static void label(String text, float left, float top, float width, float height, PlantLayout l, float scale,
                              int ink, Sink sink) {
        if (text.isEmpty()) {
            return;
        }
        int split = text.indexOf(' ');
        float room = width - l.cellW() * 0.6f;
        if (split > 0 && px(text) * scale > room && height >= l.cellH() * 2.0f * 0.9f) {
            String first = text.substring(0, split);
            String second = text.substring(split + 1);
            float s = fit(Math.max(px(first), px(second)), room, scale);
            centred(sink, first, left + width / 2.0f, top + height / 2.0f - l.cellH(), l.cellH(), s, ink, room);
            centred(sink, second, left + width / 2.0f, top + height / 2.0f, l.cellH(), s, ink, room);
        } else {
            centred(sink, text, left + width / 2.0f, top + (height - l.cellH()) / 2.0f, l.cellH(), scale, ink, room);
        }
    }

    private static void key(PlantElement e, PlantLayout l, float scale, Sink sink) {
        int start = l.keyStart(e.state);
        int cols = l.keyWidth(e.state);
        if (cols < 1) {
            return;
        }
        float inset = Math.min(l.cellW(), l.cellH()) * 0.12f;
        float left = x(l, 1 + start) + inset;
        float top = y(l, l.rows()) + inset;
        float width = cols * l.cellW() - 2.0f * inset;
        float height = l.cellH() - 2.0f * inset;
        sink.rect(left, top, width, height, SHAPE_Z, GRID, false);
        sink.rect(left + 0.2f, top + 0.2f, width - 0.4f, height - 0.4f, DETAIL_Z - 0.05f, KEY, false);
        centred(sink, e.text, left + width / 2.0f, top, height, scale, PlantColors.rgb(e.colour), width - l.cellW() * 0.4f);
    }
}
