package dev.bwr.mod.panel.client;

import dev.bwr.mod.panel.component.FullCoreDisplayData;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.display.SevenSegment;
import dev.bwr.mod.panel.fcd.FcdLayout;
import dev.bwr.mod.panel.fcd.FcdSnapshot;
import dev.bwr.mod.panel.model.FcdModels;
import dev.bwr.mod.panel.model.PanelModels;
import dev.bwr.mod.reactor.CoreRodMap;

import java.util.Locale;

/**
 * The full core display. Columbia style: every rod a 2 x 2 block of lamp tiles (coordinate over DRIFT, FULL IN over
 * FULL OUT, ACCUM over SCRAM, and a blank), mirrored so the blanks of four rods meet around each LPRM string and
 * its yellow dot, on a black backplate that follows the core outline. Browns Ferry style: a module per rod with a
 * two digit position readout and a lamp cluster, a four lamp strip per LPRM string, and numbered grid lines, with no
 * backplate. Baked models ({@code fcd_tile_columbia}, {@code fcd_tile_blank}, {@code fcd_bf_rod_module},
 * {@code fcd_bf_lprm_strip}) are used when loaded, the procedural bodies below otherwise; see
 * {@link CoreDisplayModel} for what is drawn when.
 */
final class FullCoreDisplayModel extends CoreDisplayModel {
    // lamp colours
    private static final int WHITE = 0xFFFFF0;
    private static final int AMBER = 0xFFB000;
    private static final int RED = 0xFF2A1E;
    private static final int BLUE = 0x4A86FF;
    private static final int GREEN = 0x30E060;
    private static final int DOT = 0xD8C21E;
    private static final int GRID_LINE = 0x101010;

    private static final String[] COLUMBIA_UPPER_TEXT = {null, "FULL IN", "ACCUM", ""};
    private static final String[] COLUMBIA_LOWER_TEXT = {"DRIFT", "FULL OUT", "SCRAM", ""};

    /** Everything worked out once per component revision, model generation and row aspect. */
    private static final class State {
        final int revision;
        final int generation;
        final float aspect;
        final FcdLayout layout;
        final boolean fits;
        final float ox;
        final float oy;
        final int[] needed;
        final Kit tile;
        final Kit blank;
        final Kit module;
        final Kit strip;
        final boolean edge;
        final boolean coordinates;
        final String caption;
        final OffsetSink offset = new OffsetSink();
        /** Elevation of the printed legends: over the baked bodies when there are some. */
        float textZ = TEXT_Z;
        /** Elevation of the glow over a procedural window (a baked part glows where it is). */
        float dotZ = 0.9f;
        /** Digit window of the module in cells relative to the module's top-left: x, y, w, h. */
        float[] digits;

        State(PanelComponent component, float aspect) {
            FullCoreDisplayData data = (FullCoreDisplayData) component.data();
            this.revision = component.revision();
            this.generation = PanelModels.generation();
            this.aspect = aspect;
            this.layout = FcdLayout.of(data.displayMap(), data.layoutStyle(), data.tileCells);
            this.edge = data.edgeNumbersOn();
            this.coordinates = data.coordLegends;
            this.caption = data.caption;
            this.needed = new int[] {layout.neededWidth(), layout.neededHeight()};
            this.fits = component.w() >= needed[0] && component.h() >= needed[1];
            this.ox = dev.bwr.mod.panel.component.CoreDisplayData.centre(component.w(), needed[0]);
            this.oy = dev.bwr.mod.panel.component.CoreDisplayData.centre(component.h(), needed[1]);
            float t = layout.tile();
            if (layout.style() == FcdLayout.Style.COLUMBIA) {
                tile = Kit.of(FcdModels.TILE_COLUMBIA, t, t, aspect);
                blank = Kit.of(FcdModels.TILE_BLANK, t, t, aspect);
                module = null;
                strip = null;
                if (tile != null) {
                    textZ = tile.topZ() + 0.05f;
                    dotZ = 0.15f;
                }
            } else {
                tile = null;
                blank = null;
                module = Kit.of(FcdModels.BF_ROD_MODULE, layout.moduleWidth(), layout.moduleHeight(), aspect);
                strip = Kit.of(FcdModels.BF_LPRM_STRIP, layout.stripWidth(), layout.stripHeight(), aspect);
                if (module != null) {
                    digits = module.bounds("digits");
                    textZ = module.topZ() + 0.05f;
                }
                if (digits == null) {
                    digits = new float[] {layout.moduleWidth() * 0.22f, layout.moduleHeight() * 0.06f,
                            layout.moduleWidth() * 0.56f, layout.moduleHeight() * 0.42f};
                }
            }
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

    // ---- static

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
        if (s.layout.style() == FcdLayout.Style.COLUMBIA) {
            buildColumbia(sink, s);
        } else {
            buildBrownsFerry(sink, s);
        }
    }

    private void buildColumbia(QuadSink sink, State s) {
        FcdLayout layout = s.layout;
        float t = layout.tile();
        for (FcdLayout.Rect plate : layout.backplate()) {
            sink.rect(s.ox + plate.x(), s.oy + plate.y(), plate.w(), plate.h(), 0.02f, BLACK, false);
        }
        CoreRodMap map = layout.map();
        for (int rod = 0; rod < map.rodCount(); rod++) {
            for (int which = FcdLayout.TILE_A; which <= FcdLayout.TILE_D; which++) {
                float x = s.ox + layout.tileX(rod, which);
                float y = s.oy + layout.tileY(rod, which);
                if (which == FcdLayout.TILE_D) {
                    if (s.blank != null) {
                        s.blank.emitStatic(sink, s.offset, x, y);
                    } else {
                        float m = t * 0.03f;
                        sink.box(x + m, y + m, t - 2 * m, t - 2 * m, 0.0f, 1.0f, FRAME, false);
                        sink.rect(x + 3 * m, y + 3 * m, t - 6 * m, t - 6 * m, 1.05f, 0x101214, false);
                    }
                } else if (s.tile != null) {
                    s.tile.emitStatic(sink, s.offset, x, y);
                    s.tile.emitPart(sink, s.offset, "upper", x, y, PLATE, 1.0f, false, 0.0f);
                    s.tile.emitPart(sink, s.offset, "lower", x, y, PLATE, 1.0f, false, 0.0f);
                } else {
                    float m = t * 0.03f;
                    sink.box(x + m, y + m, t - 2 * m, t - 2 * m, 0.0f, 1.0f, FRAME, false);
                    sink.rect(x + upperX(t), y + upperY(t), windowW(t), windowH(t), 1.1f, PLATE, false);
                    sink.rect(x + upperX(t), y + lowerY(t), windowW(t), windowH(t), 1.1f, PLATE, false);
                }
            }
        }
        for (FcdLayout.Spot dot : layout.dots()) {
            Shapes.disc(sink, s.ox + dot.x(), s.oy + dot.y(), t * 0.16f, s.dotZ, DOT, 8, false);
        }
    }

    // procedural tile windows
    private static float upperX(float t) {
        return t * 0.08f;
    }

    private static float upperY(float t) {
        return t * 0.08f;
    }

    private static float windowW(float t) {
        return t * 0.84f;
    }

    private static float windowH(float t) {
        return t * 0.40f;
    }

    private static float lowerY(float t) {
        return t * 0.52f;
    }

    // BF module window rectangles, as fractions of the module: x, y, w, h
    private static final float[] BF_ACCUM = {0.06f, 0.54f, 0.43f, 0.20f};
    private static final float[] BF_SELECT = {0.51f, 0.54f, 0.43f, 0.20f};
    private static final float[] BF_SCRAM = {0.06f, 0.76f, 0.43f, 0.20f};
    private static final float[] BF_DRIFT = {0.51f, 0.76f, 0.43f, 0.20f};
    private static final float[] BF_FULL_IN = {0.04f, 0.10f, 0.15f, 0.34f};
    private static final float[] BF_FULL_OUT = {0.81f, 0.10f, 0.15f, 0.34f};

    private void buildBrownsFerry(QuadSink sink, State s) {
        FcdLayout layout = s.layout;
        CoreRodMap map = layout.map();
        float mw = layout.moduleWidth();
        float mh = layout.moduleHeight();
        for (int rod = 0; rod < map.rodCount(); rod++) {
            float x = s.ox + layout.moduleX(rod);
            float y = s.oy + layout.moduleY(rod);
            if (s.module != null) {
                s.module.emitStatic(sink, s.offset, x, y);
                s.module.emitPart(sink, s.offset, "digits", x, y, 0x050607, 1.0f, false, 0.0f);
                for (String lamp : MODULE_LAMPS) {
                    s.module.emitPart(sink, s.offset, lamp, x, y, PLATE, 1.0f, false, 0.0f);
                }
            } else {
                sink.box(x, y, mw, mh, 0.0f, 1.0f, 0x8E928E, false);
                sink.rect(x + s.digits[0], y + s.digits[1], s.digits[2], s.digits[3], 1.1f, 0x050607, false);
                for (float[] w : new float[][] {BF_ACCUM, BF_SELECT, BF_SCRAM, BF_DRIFT, BF_FULL_IN, BF_FULL_OUT}) {
                    sink.rect(x + w[0] * mw, y + w[1] * mh, w[2] * mw, w[3] * mh, 1.1f, PLATE, false);
                }
            }
        }
        for (int string = 0; string < map.lprmStrings().size(); string++) {
            float x = s.ox + layout.stripX(string);
            float y = s.oy + layout.stripY(string);
            float sw = layout.stripWidth();
            float sh = layout.stripHeight();
            if (s.strip != null) {
                s.strip.emitStatic(sink, s.offset, x, y);
                for (int slot = 0; slot < 4; slot++) {
                    s.strip.emitPart(sink, s.offset, "lvl_" + (char) ('a' + slot), x, y, 0x30343A, 1.0f, false, 0.0f);
                }
            } else {
                sink.box(x, y, sw, sh, 0.0f, 1.0f, 0x8E928E, false);
                for (int slot = 0; slot < 4; slot++) {
                    float[] w = stripWindow(sw, sh, slot);
                    sink.rect(x + w[0], y + w[1], w[2], w[3], 1.1f, 0x30343A, false);
                }
            }
        }
        // grid lines from the numbers to the modules
        float line = Math.max(0.2f, layout.tile() * 0.1f);
        for (int row = 0; s.edge && row < map.rows(); row++) {
            int first = -1;
            for (int c = 0; c < map.cols() && first < 0; c++) {
                first = map.rodAt(c, row) >= 0 ? c : -1;
            }
            if (first >= 0) {
                int rod = map.rodAt(first, row);
                float x0 = s.ox + layout.leftMargin() * 0.82f;
                float x1 = s.ox + layout.moduleX(rod);
                sink.rect(x0, s.oy + layout.rowCentreY(row) - line / 2, x1 - x0, line, 0.5f, GRID_LINE, false);
            }
        }
        for (int c = 0; s.edge && c < map.cols(); c++) {
            int lowest = -1;
            for (int row = map.rows() - 1; row >= 0 && lowest < 0; row--) {
                lowest = map.rodAt(c, row) >= 0 ? row : -1;
            }
            if (lowest >= 0) {
                float y0 = s.oy + layout.moduleY(map.rodAt(c, lowest)) + mh;
                float y1 = s.oy + layout.bottomMarginY() + layout.bottomMargin() * 0.1f;
                sink.rect(s.ox + layout.columnCentreX(c) - line / 2, y0, line, y1 - y0, 0.5f, GRID_LINE, false);
            }
        }
    }

    private static final String[] MODULE_LAMPS = {"accum", "scram", "drift", "full_in", "full_out", "select"};

    /** A procedural strip's lamp window {@code slot} (0 = top): x, y, w, h in cells relative to the strip. */
    private static float[] stripWindow(float sw, float sh, int slot) {
        float h = sh * 0.17f;
        return new float[] {sw * 0.2f, sh * 0.06f + slot * sh * 0.185f, sw * 0.6f, h};
    }

    // ---- lit

    @Override
    public long poseKey(PanelRenderContext context, PanelComponent component) {
        State s = existing(component);
        return s == null || !s.fits ? 0L : ((FullCoreDisplayData) component.data()).snapshot().visualKey();
    }

    @Override
    public void emitPosed(QuadSink sink, PanelComponent component) {
        State s = existing(component);
        if (s == null || !s.fits) {
            return;
        }
        FcdSnapshot snap = ((FullCoreDisplayData) component.data()).snapshot();
        if (!snap.formed() || snap.rodCount() != s.layout.map().rodCount()) {
            return;
        }
        if (s.layout.style() == FcdLayout.Style.COLUMBIA) {
            litColumbia(sink, s, snap);
        } else {
            litBrownsFerry(sink, s, snap);
        }
    }

    private void litColumbia(QuadSink sink, State s, FcdSnapshot snap) {
        FcdLayout layout = s.layout;
        float t = layout.tile();
        for (int rod = 0; rod < snap.rodCount(); rod++) {
            float ax = s.ox + layout.tileX(rod, FcdLayout.TILE_A);
            float ay = s.oy + layout.tileY(rod, FcdLayout.TILE_A);
            float bx = s.ox + layout.tileX(rod, FcdLayout.TILE_B);
            float by = s.oy + layout.tileY(rod, FcdLayout.TILE_B);
            float cx = s.ox + layout.tileX(rod, FcdLayout.TILE_C);
            float cy = s.oy + layout.tileY(rod, FcdLayout.TILE_C);
            if (snap.selectedRod(rod)) {
                lampPart(sink, s, s.tile, "upper", ax, ay, windowW(t), windowH(t), WHITE, upperX(t), upperY(t));
            }
            if (snap.drift(rod)) {
                lampPart(sink, s, s.tile, "lower", ax, ay, windowW(t), windowH(t), AMBER, upperX(t), lowerY(t));
            }
            if (snap.fullIn(rod)) {
                lampPart(sink, s, s.tile, "upper", bx, by, windowW(t), windowH(t), WHITE, upperX(t), upperY(t));
            }
            if (snap.fullOut(rod)) {
                lampPart(sink, s, s.tile, "lower", bx, by, windowW(t), windowH(t), RED, upperX(t), lowerY(t));
            }
            if (snap.accumulatorTrouble(rod)) {
                lampPart(sink, s, s.tile, "upper", cx, cy, windowW(t), windowH(t), AMBER, upperX(t), upperY(t));
            }
            if (snap.scramLamp(rod)) {
                lampPart(sink, s, s.tile, "lower", cx, cy, windowW(t), windowH(t), BLUE, upperX(t), lowerY(t));
            }
        }
    }

    /** {@link #lampPart} for a procedural window at {@code (x + ox, y + oy)} or a baked part at {@code (x, y)}. */
    private void lampPart(QuadSink sink, State s, Kit kit, String part, float x, float y, float w, float h, int rgb,
                          float ox, float oy) {
        if (kit != null && kit.has(part)) {
            kit.emitPart(sink, s.offset, part, x, y, rgb, 1.0f, true, 0.0f);
        } else {
            sink.rect(x + ox, y + oy, w, h, GLOW_Z, rgb, true);
        }
    }

    private void litBrownsFerry(QuadSink sink, State s, FcdSnapshot snap) {
        FcdLayout layout = s.layout;
        float mw = layout.moduleWidth();
        float mh = layout.moduleHeight();
        for (int rod = 0; rod < snap.rodCount(); rod++) {
            float x = s.ox + layout.moduleX(rod);
            float y = s.oy + layout.moduleY(rod);
            int notch = snap.notch(rod);
            int digitRgb = notch == 0 ? GREEN : notch == FcdSnapshot.NOTCHES ? RED : AMBER;
            drawDigits(sink, x + s.digits[0], y + s.digits[1], s.digits[2], s.digits[3], digitRgb, notch * 2);
            if (snap.accumulatorTrouble(rod)) {
                moduleLamp(sink, s, "accum", BF_ACCUM, x, y, mw, mh, AMBER);
            }
            if (snap.scramLamp(rod)) {
                moduleLamp(sink, s, "scram", BF_SCRAM, x, y, mw, mh, RED);
            }
            if (snap.drift(rod)) {
                moduleLamp(sink, s, "drift", BF_DRIFT, x, y, mw, mh, AMBER);
            }
            if (snap.fullIn(rod)) {
                moduleLamp(sink, s, "full_in", BF_FULL_IN, x, y, mw, mh, WHITE);
            }
            if (snap.fullOut(rod)) {
                moduleLamp(sink, s, "full_out", BF_FULL_OUT, x, y, mw, mh, RED);
            }
            if (snap.selectedRod(rod)) {
                moduleLamp(sink, s, "select", BF_SELECT, x, y, mw, mh, WHITE);
            }
        }
        int strings = Math.min(snap.lprmStrings(), layout.map().lprmStrings().size());
        for (int string = 0; string < strings; string++) {
            float x = s.ox + layout.stripX(string);
            float y = s.oy + layout.stripY(string);
            for (int slot = 0; slot < 4; slot++) {
                int lamp = snap.lprmLamp(string, FcdLayout.lprmLevelAtSlot(slot));
                if (lamp == FcdSnapshot.LAMP_OFF) {
                    continue;
                }
                int rgb = switch (lamp) {
                    case FcdSnapshot.LAMP_UPSCALE -> AMBER;
                    case FcdSnapshot.LAMP_BYPASSED -> BLUE;
                    case FcdSnapshot.LAMP_FAILED -> RED;
                    default -> WHITE;
                };
                String part = "lvl_" + (char) ('a' + slot);
                if (s.strip != null && s.strip.has(part)) {
                    s.strip.emitPart(sink, s.offset, part, x, y, rgb, 1.0f, true, 0.0f);
                } else {
                    float[] w = stripWindow(layout.stripWidth(), layout.stripHeight(), slot);
                    sink.rect(x + w[0], y + w[1], w[2], w[3], GLOW_Z, rgb, true);
                }
            }
        }
    }

    private void moduleLamp(QuadSink sink, State s, String part, float[] window, float x, float y, float mw, float mh, int rgb) {
        if (s.module != null && s.module.has(part)) {
            s.module.emitPart(sink, s.offset, part, x, y, rgb, 1.0f, true, 0.0f);
        } else {
            sink.rect(x + window[0] * mw, y + window[1] * mh, window[2] * mw, window[3] * mh, GLOW_Z, rgb, true);
        }
    }

    /** A two digit readout of {@code value} (00 to 48) in a window of {@code w x h} cells. */
    private static void drawDigits(QuadSink sink, float x, float y, float w, float h, int rgb, int value) {
        String text = String.format(Locale.ROOT, "%02d", value);
        float cw = w / 2.0f;
        float thickness = Math.max(0.28f, cw * 0.17f);
        float e = thickness * 0.6f;
        for (int digit = 0; digit < 2; digit++) {
            int mask = SevenSegment.mask(text.charAt(digit));
            float xl = x + digit * cw + cw * 0.2f;
            float xr = x + (digit + 1) * cw - cw * 0.2f;
            float yt = y + h * 0.13f;
            float ym = y + h * 0.5f;
            float yb = y + h * 0.87f;
            float z = 1.2f;
            if ((mask & SevenSegment.A) != 0) {
                Shapes.bar(sink, xl + e, yt, xr - e, yt, thickness, z, rgb, true);
            }
            if ((mask & SevenSegment.B) != 0) {
                Shapes.bar(sink, xr, yt + e, xr, ym - e * 0.5f, thickness, z, rgb, true);
            }
            if ((mask & SevenSegment.C) != 0) {
                Shapes.bar(sink, xr, ym + e * 0.5f, xr, yb - e, thickness, z, rgb, true);
            }
            if ((mask & SevenSegment.D) != 0) {
                Shapes.bar(sink, xl + e, yb, xr - e, yb, thickness, z, rgb, true);
            }
            if ((mask & SevenSegment.E) != 0) {
                Shapes.bar(sink, xl, ym + e * 0.5f, xl, yb - e, thickness, z, rgb, true);
            }
            if ((mask & SevenSegment.F) != 0) {
                Shapes.bar(sink, xl, yt + e, xl, ym - e * 0.5f, thickness, z, rgb, true);
            }
            if ((mask & SevenSegment.G) != 0) {
                Shapes.bar(sink, xl + e, ym, xr - e, ym, thickness, z, rgb, true);
            }
        }
    }

    // ---- per frame, without the block's GPU cache

    @Override
    public void renderDynamic(PanelRenderContext context, PanelComponent component) {
        emitPosed(context, component);
        if (context.lod() >= PanelRenderContext.LOD_FULL) {
            emitLegends(context, component);
        }
    }

    /** The posed geometry lives in the block's buffer (or {@link #renderDynamic}); nothing else moves. */
    @Override
    public void renderLive(PanelRenderContext context, PanelComponent component) {
    }

    /** The per frame legends: used when the block's text cache is off or failed. */
    @Override
    public void renderLegends(PanelRenderContext context, PanelComponent component) {
        emitLegends(context, component);
    }

    @Override
    public boolean legendsCached() {
        return CoreDisplayModel.LEGEND_CACHE;
    }

    // ---- legends

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
        if (s.layout.style() == FcdLayout.Style.COLUMBIA) {
            columbiaLegends(context, s);
        } else {
            brownsFerryLegends(context, s);
        }
        // The caption sits in the margin above the picture (a tile high in every style): light on the black backplate
        // of Columbia, dark on the board elsewhere.
        float t = s.layout.tile();
        caption(context, s.caption, s.ox + s.needed[0] / 2.0f, s.oy + t * 0.5f, s.needed[0] * 0.9f, t * 0.8f, s.textZ,
                s.layout.style() == FcdLayout.Style.COLUMBIA ? 0xE8E8E0 : GRID_LINE);
    }

    private void columbiaLegends(PanelRenderContext context, State s) {
        FcdLayout layout = s.layout;
        CoreRodMap map = layout.map();
        float t = layout.tile();
        float hw = windowW(t);
        float hh = windowH(t);
        for (int rod = 0; rod < map.rodCount(); rod++) {
            for (int which = FcdLayout.TILE_A; which <= FcdLayout.TILE_C; which++) {
                float x = s.ox + layout.tileX(rod, which);
                float y = s.oy + layout.tileY(rod, which);
                String upper = which == FcdLayout.TILE_A ? (s.coordinates ? map.label(rod) : "") : COLUMBIA_UPPER_TEXT[which];
                String lower = COLUMBIA_LOWER_TEXT[which];
                float cx = x + t / 2.0f;
                fitted(context, upper, cx, y + upperY(t) + hh / 2.0f, hw, hh, s.textZ, INK);
                fitted(context, lower, cx, y + lowerY(t) + hh / 2.0f, hw, hh, s.textZ, INK);
            }
        }
        if (s.edge) {
            // GE row numbers in the margin of the backplate left of each row, column numbers under each column
            for (int row = 0; row < map.rows(); row++) {
                int first = -1;
                for (int c = 0; c < map.cols() && first < 0; c++) {
                    first = map.rodAt(c, row) >= 0 ? c : -1;
                }
                if (first >= 0) {
                    fitted(context, String.format(Locale.ROOT, "%02d", map.labelY(row)), s.ox + (1 + 2 * first) * t - t * 0.5f,
                            s.oy + (2 + 2 * row) * t, t * 0.9f, t * 0.7f, 0.3f, 0xE8E8E0);
                }
            }
            for (int c = 0; c < map.cols(); c++) {
                int lowest = -1;
                for (int row = map.rows() - 1; row >= 0 && lowest < 0; row--) {
                    lowest = map.rodAt(c, row) >= 0 ? row : -1;
                }
                if (lowest >= 0) {
                    fitted(context, String.format(Locale.ROOT, "%02d", map.labelX(c)), s.ox + (2 + 2 * c) * t,
                            s.oy + (1 + 2 * lowest) * t + 2.5f * t, t * 1.4f, t * 0.7f, 0.3f, 0xE8E8E0);
                }
            }
        }
    }

    private void brownsFerryLegends(PanelRenderContext context, State s) {
        FcdLayout layout = s.layout;
        CoreRodMap map = layout.map();
        float mw = layout.moduleWidth();
        float mh = layout.moduleHeight();
        for (int rod = 0; rod < map.rodCount(); rod++) {
            float x = s.ox + layout.moduleX(rod);
            float y = s.oy + layout.moduleY(rod);
            legend(context, "ACCUM", BF_ACCUM, x, y, mw, mh, s.textZ);
            legend(context, "SCRAM", BF_SCRAM, x, y, mw, mh, s.textZ);
            legend(context, "DRIFT", BF_DRIFT, x, y, mw, mh, s.textZ);
            if (s.coordinates) {
                legend(context, map.label(rod), BF_SELECT, x, y, mw, mh, s.textZ);
            }
        }
        for (int string = 0; string < map.lprmStrings().size(); string++) {
            float x = s.ox + layout.stripX(string);
            float y = s.oy + layout.stripY(string) + layout.stripHeight();
            fitted(context, map.lprmStrings().get(string).label(), x + layout.stripWidth() / 2.0f,
                    y + layout.tile() * 0.3f, layout.stripWidth() * 1.5f, layout.tile() * 0.5f, s.textZ, GRID_LINE);
        }
        for (int row = 0; s.edge && row < map.rows(); row++) {
            fitted(context, String.format(Locale.ROOT, "%02d", map.labelY(row)), s.ox + layout.leftMargin() * 0.4f,
                    s.oy + layout.rowCentreY(row), layout.leftMargin() * 0.7f, layout.moduleHeight() * 0.8f, 0.6f, GRID_LINE);
        }
        for (int c = 0; s.edge && c < map.cols(); c++) {
            fitted(context, String.format(Locale.ROOT, "%02d", map.labelX(c)), s.ox + layout.columnCentreX(c),
                    s.oy + layout.bottomMarginY() + layout.bottomMargin() * 0.55f, layout.moduleWidth() * 0.7f,
                    layout.bottomMargin() * 0.6f, 0.6f, GRID_LINE);
        }
    }

    private static void legend(PanelRenderContext context, String text, float[] window, float x, float y, float mw, float mh, float z) {
        fitted(context, text, x + (window[0] + window[2] / 2.0f) * mw, y + (window[1] + window[3] / 2.0f) * mh,
                window[2] * mw, window[3] * mh, z, INK);
    }
}
