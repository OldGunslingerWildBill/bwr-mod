package dev.bwr.mod.panel.client;

import com.mojang.blaze3d.platform.NativeImage;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.component.PaperlessRecorderData;
import dev.bwr.mod.panel.component.RecorderKey;
import dev.bwr.mod.panel.component.RecorderStyle;
import dev.bwr.mod.panel.component.RecorderUi;
import dev.bwr.mod.panel.display.RecorderCanvas;
import dev.bwr.mod.panel.display.RecorderPainter;
import dev.bwr.mod.panel.model.BakedPanelModel;
import dev.bwr.mod.panel.model.Legends;
import dev.bwr.mod.panel.model.PanelModels;
import dev.bwr.mod.panel.model.PartDraw;
import dev.bwr.mod.panel.model.RecorderLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * The paperless recorder: a bezel (the baked {@code yokogawa_dx1000} / {@code yokogawa_dx2000} model, or a procedural
 * stand-in with the same rectangles when the model is not loaded), the screen as one full-bright textured quad, the
 * DX2000's hinged key-panel cover, the power LED, the legends and a flash on the key that was just pressed.
 *
 * <p>The screen texture is painted by {@link RecorderPainter} and only when its {@link RecorderPainter#stamp} changes
 * (a new dot, the one second value refresh, an alarm blink, a key press, a setting): never per frame. Beyond the
 * panel's full detail distance the last picture is shown and not repainted. The texture is the 320 x 240 or 640 x 480
 * of the recorder's style, drawn exactly on the {@code screen} part's bounds.
 */
final class RecorderModel implements ComponentModel {
    static final int BEZEL = 0x4A5157;
    static final int FRAME = 0x2E3438;
    static final int SCREEN_BACK = 0x101418;
    static final int KEY_CAP = 0x23282C;
    static final int KEY_RING = 0x6E767C;
    private static final float COVER_OPEN_DEGREES = 95.0f;
    private static final float COVER_SPEED_DEGREES_PER_TICK = 16.0f;
    private static final int FEEDBACK_TICKS = 6;
    private static final int LED_RUNNING = 0x20E040;
    private static final int LED_POWER = 0xE02020;
    private static final float TEXT_LIFT = 0.05f;

    /** One printed legend of the bezel. */
    private record Legend(String id, String text, float x, float y, float z, float capCells, float rotation,
                          BakedPanelModel.Align align, int ink) {
    }

    /** What the model keeps per component between frames; lives in {@link RenderCache#scratch}. Never saved. */
    static final class State {
        RecorderLayout layout;
        int layoutGeneration = -1;
        List<Legend> legends = List.of();
        BakedPanelModel.Part[] dynamicParts = new BakedPanelModel.Part[0];
        final RecorderUi ui = new RecorderUi();
        RecorderCanvas canvas;
        int[] shown;
        float coverAngle;
        float lastTime = Float.NaN;
        int paints;
        Object lastEntry;
        final float[] scratch = new float[PartDraw.SCRATCH];
    }

    static State state(PanelComponent component) {
        RenderCache cache = RenderCache.of(component);
        if (!(cache.scratch instanceof State)) {
            cache.scratch = new State();
        }
        return (State) cache.scratch;
    }

    /** Number of screen repaints made for this component (checks). */
    static int paints(PanelComponent component) {
        return state(component).paints;
    }

    // ---- static body

    @Override
    public void buildStatic(QuadSink sink, PanelComponent component) {
        buildStatic(sink, component, 1.0f);
    }

    @Override
    public void buildStatic(QuadSink sink, PanelComponent component, float rowAspect) {
        PaperlessRecorderData data = (PaperlessRecorderData) component.data();
        State st = state(component);
        RecorderStyle style = data.style;
        BakedPanelModel model = PanelModels.get(style.id());
        st.layout = RecorderLayout.of(style, model, component.w(), component.h(), rowAspect);
        st.layoutGeneration = PanelModels.generation();
        RecorderLayout layout = st.layout;
        List<BakedPanelModel.Part> moving = new ArrayList<>();
        if (layout.baked) {
            for (int i = 0; i < layout.model.partCount(); i++) {
                BakedPanelModel.Part part = layout.model.part(i);
                if (part.moving() || part.role == BakedPanelModel.Role.LAMP) {
                    moving.add(part);
                } else if (part.role != BakedPanelModel.Role.GLASS) {
                    PartDraw.emit(sink, part, layout.place, 0.0f, 0.0f, -1, 1.0f, false, st.scratch);
                }
            }
        } else {
            proceduralBezel(sink, layout, style);
        }
        st.dynamicParts = moving.toArray(new BakedPanelModel.Part[0]);
        st.legends = legendsOf(layout, style);
    }

    private static void box(QuadSink sink, RecorderLayout l, RecorderLayout.Box b, float z0, float z1, int rgb) {
        sink.box(l.cellX(b.x0()), l.cellY(b.y0()), l.cellX(b.x1()) - l.cellX(b.x0()), l.cellY(b.y1()) - l.cellY(b.y0()),
                l.place.z(z0), l.place.z(z1), rgb, false);
    }

    /** The stand-in bezel: a slab, the screen well, and a box for every key, at the model's rectangles. */
    private static void proceduralBezel(QuadSink sink, RecorderLayout l, RecorderStyle style) {
        float size = style == RecorderStyle.DX2000 ? RecorderLayout.DX2000_INCHES : RecorderLayout.DX1000_INCHES;
        box(sink, l, new RecorderLayout.Box(0, 0, size, size), 0.0f, l.faceZ, BEZEL);
        float pad = style == RecorderStyle.DX2000 ? 0.16f : 0.09f;
        RecorderLayout.Box s = l.screen;
        box(sink, l, new RecorderLayout.Box(s.x0() - pad, s.y0() - pad, s.x1() + pad, s.y1() + pad), l.faceZ, l.faceZ + 0.04f, FRAME);
        box(sink, l, s, l.screenZ, l.screenZ + 0.01f, SCREEN_BACK);
        for (RecorderKey key : RecorderKey.values()) {
            RecorderLayout.Box b = l.key(key);
            if (b == null || key == RecorderKey.COVER_TAB) {
                continue;
            }
            float z = key == RecorderKey.DISP || key == RecorderKey.UP || key == RecorderKey.DOWN || key == RecorderKey.LEFT
                    || key == RecorderKey.RIGHT ? l.faceZ : l.faceZ - 0.05f;
            box(sink, l, b, z, z + 0.08f, KEY_CAP);
            float e = 0.03f;
            box(sink, l, new RecorderLayout.Box(b.x0() - e, b.y0() - e, b.x1() + e, b.y1() + e), z - 0.02f, z, KEY_RING);
        }
        if (l.coverTab != null) {
            box(sink, l, l.coverTab, l.faceZ - 0.1f, l.faceZ + 0.08f, KEY_RING);
        }
    }

    // ---- legends

    private static String legendText(String id, RecorderStyle style) {
        return switch (id) {
            case "maker" -> "YOKOGAWA";
            case "model" -> style == RecorderStyle.DX2000 ? "DX2000" : "DX1000";
            case "series" -> "DAQSTATION";
            case "start", "stop", "user", "func", "esc", "menu", "fav" -> id.toUpperCase(java.util.Locale.ROOT);
            case "disp" -> "DISP";
            case "enter" -> "ENTER";
            default -> {
                if (id.startsWith("pad") && id.length() > 3) {
                    int n = Integer.parseInt(id.substring(3).replaceAll("[^0-9]", "0"));
                    yield n >= 1 && n <= 12 ? String.valueOf("123456789-0.".charAt(n - 1)) : null;
                }
                yield null;
            }
        };
    }

    private static boolean hiddenByCover(String id) {
        return switch (id) {
            case "start", "stop", "user", "func", "esc", "menu", "fav" -> true;
            default -> id.startsWith("pad");
        };
    }

    private static List<Legend> legendsOf(RecorderLayout l, RecorderStyle style) {
        List<Legend> out = new ArrayList<>();
        if (l.baked) {
            for (int i = 0; i < l.model.labelCount(); i++) {
                BakedPanelModel.Label label = l.model.label(i);
                String text = legendText(label.id, style);
                if (text != null) {
                    out.add(new Legend(label.id, text, l.place.x(label.x), l.place.y(label.y), l.place.z(label.z),
                            l.place.length(label.height), label.rotation, label.align, label.ink));
                }
            }
            return out;
        }
        float cap = style == RecorderStyle.DX2000 ? 0.106f : 0.059f;
        String[][] keyed = {{"start", "START"}, {"stop", "STOP"}, {"user", "USER"}, {"func", "FUNC"}, {"esc", "ESC"}, {"menu", "MENU"}, {"fav", "FAV"}};
        for (String[] pair : keyed) {
            RecorderKey key = RecorderKey.valueOf(pair[0].toUpperCase(java.util.Locale.ROOT));
            RecorderLayout.Box b = l.key(key);
            if (b != null) {
                out.add(new Legend(pair[0], pair[1], l.cellX(b.centreX()), l.cellY(b.centreY()), l.place.z(l.faceZ + 0.1f),
                        l.place.length(cap), 0.0f, BakedPanelModel.Align.CENTER, 0xE0E0E0));
            }
        }
        RecorderLayout.Box d = l.key(RecorderKey.DISP);
        if (d != null) {
            out.add(new Legend("disp", "DISP", l.cellX(d.centreX()), l.cellY(d.centreY()), l.place.z(l.faceZ + 0.2f),
                    l.place.length(cap), 0.0f, BakedPanelModel.Align.CENTER, 0xE0E0E0));
        }
        out.add(new Legend("model", style == RecorderStyle.DX2000 ? "DX2000" : "DX1000", l.cellX(l.screen.x1()), l.cellY(0.236f),
                l.place.z(l.faceZ + 0.02f), l.place.length(cap * 2), 0.0f, BakedPanelModel.Align.RIGHT, 0xE0E0E0));
        out.add(new Legend("maker", "YOKOGAWA", l.cellX(l.screen.x0()), l.cellY(0.236f), l.place.z(l.faceZ + 0.02f),
                l.place.length(cap * 2), 0.0f, BakedPanelModel.Align.LEFT, 0xE0E0E0));
        return out;
    }

    // ---- per frame

    @Override
    public void renderDynamic(PanelRenderContext context, PanelComponent component) {
        renderLive(context, component);
        renderLegends(context, component);
    }

    @Override
    public void renderLive(PanelRenderContext context, PanelComponent component) {
        if (!RecorderClientStats.timing) {
            drawLive(context, component);
            return;
        }
        long start = System.nanoTime();
        drawLive(context, component);
        RecorderClientStats.renderNanos += System.nanoTime() - start;
        RecorderClientStats.renderCalls++;
    }

    private void drawLive(PanelRenderContext context, PanelComponent component) {
        PaperlessRecorderData data = (PaperlessRecorderData) component.data();
        State st = state(component);
        RecorderLayout layout = st.layout;
        if (layout == null) {
            return;
        }
        RecorderStyle style = data.style;
        int width = style.textureWidth();
        int height = style.textureHeight();
        DisplayTextures.Entry entry = DisplayTextures.acquire(data, width, height);
        long tick = (long) context.gameTime();
        int stamp = RecorderPainter.stamp(data, component, st.ui, tick);
        if (entry.version != stamp && (context.lod() >= PanelRenderContext.LOD_FULL || entry.version == -1)) {
            paint(st, entry, data, component, tick, width, height);
            entry.version = stamp;
        }
        RecorderLayout.Box s = layout.screen;
        float lift = 0.05f;
        context.texturedRect(entry.type, layout.cellX(s.x0()), layout.cellY(s.y0()), layout.cellX(s.x1()) - layout.cellX(s.x0()),
                layout.cellY(s.y1()) - layout.cellY(s.y0()), layout.place.z(layout.screenZ) + lift, true);
        if (context.lod() < PanelRenderContext.LOD_FULL) {
            return;
        }
        float time = context.gameTime();
        float dt = Float.isNaN(st.lastTime) ? 1000.0f : Math.max(0.0f, Math.min(20.0f, time - st.lastTime));
        st.lastTime = time;
        float target = style.hasCover() && data.coverOpen ? COVER_OPEN_DEGREES : 0.0f;
        float step = COVER_SPEED_DEGREES_PER_TICK * dt;
        st.coverAngle = st.coverAngle < target ? Math.min(target, st.coverAngle + step) : Math.max(target, st.coverAngle - step);
        if (layout.baked) {
            for (BakedPanelModel.Part part : st.dynamicParts) {
                if (part.role == BakedPanelModel.Role.LAMP) {
                    PartDraw.emit(context, part, layout.place, 0.0f, 0.0f, data.running ? LED_RUNNING : LED_POWER, 1.0f, true, st.scratch);
                } else {
                    PartDraw.emit(context, part, layout.place, part.hasPivot ? st.coverAngle : 0.0f, 0.0f, -1, 1.0f, false, st.scratch);
                }
            }
        } else {
            proceduralMoving(context, layout, data, st);
        }
        feedback(context, layout, st, tick);
        if (RecorderClientStats.showHitBoxes) {
            hitBoxes(context, layout);
        }
    }

    private static void hitBoxes(PanelRenderContext context, RecorderLayout l) {
        int index = 0;
        for (RecorderKey key : RecorderKey.values()) {
            RecorderLayout.Box b = l.key(key);
            if (b == null) {
                continue;
            }
            int ink = new int[] {0xFF2020, 0x20FF20, 0x2080FF, 0xFFFF20, 0xFF20FF, 0x20FFFF}[index++ % 6];
            float x = l.cellX(b.x0());
            float y = l.cellY(b.y0());
            float w = l.cellX(b.x1()) - x;
            float h = l.cellY(b.y1()) - y;
            float t = 0.12f;
            float z = l.place.z(l.faceZ + 0.3f);
            context.rect(x, y, w, t, z, ink, true);
            context.rect(x, y + h - t, w, t, z, ink, true);
            context.rect(x, y, t, h, z, ink, true);
            context.rect(x + w - t, y, t, h, z, ink, true);
        }
    }

    private static void proceduralMoving(PanelRenderContext context, RecorderLayout l, PaperlessRecorderData data, State st) {
        RecorderLayout.Box led = l.led;
        if (led != null) {
            context.rect(l.cellX(led.x0()), l.cellY(led.y0()), l.cellX(led.x1()) - l.cellX(led.x0()),
                    l.cellY(led.y1()) - l.cellY(led.y0()), l.place.z(l.faceZ + 0.1f), data.running ? LED_RUNNING : LED_POWER, true);
        }
        RecorderLayout.Box cover = l.cover;
        if (cover != null) {
            double open = Math.toRadians(st.coverAngle);
            float h = (float) ((cover.y1() - cover.y0()) * Math.cos(open));
            if (h > 0.02f) {
                float z = l.place.z(l.faceZ + 0.05f + (float) Math.sin(open) * 0.5f);
                context.rect(l.cellX(cover.x0()), l.cellY(cover.y1() - h), l.cellX(cover.x1()) - l.cellX(cover.x0()),
                        l.cellY(cover.y1()) - l.cellY(cover.y1() - h), z, BEZEL, false);
            }
        }
    }

    /** A bright outline for a few ticks around the key that was just pressed. */
    private static void feedback(PanelRenderContext context, RecorderLayout l, State st, long tick) {
        RecorderKey key = st.ui.lastKey;
        if (key == null || st.ui.lastKeyTick == Long.MIN_VALUE || tick - st.ui.lastKeyTick > FEEDBACK_TICKS || tick < st.ui.lastKeyTick) {
            return;
        }
        RecorderLayout.Box b = l.key(key);
        if (b == null) {
            return;
        }
        float x = l.cellX(b.x0());
        float y = l.cellY(b.y0());
        float w = l.cellX(b.x1()) - x;
        float h = l.cellY(b.y1()) - y;
        float t = 0.25f;
        float z = l.place.z(l.faceZ + 0.25f);
        int ink = 0x60C0FF;
        context.rect(x - t, y - t, w + 2 * t, t, z, ink, true);
        context.rect(x - t, y + h, w + 2 * t, t, z, ink, true);
        context.rect(x - t, y, t, h, z, ink, true);
        context.rect(x + w, y, t, h, z, ink, true);
    }

    @Override
    public void renderLegends(PanelRenderContext context, PanelComponent component) {
        if (context.lod() < PanelRenderContext.LOD_FULL) {
            return;
        }
        PaperlessRecorderData data = (PaperlessRecorderData) component.data();
        State st = state(component);
        boolean closed = data.style.hasCover() && st.coverAngle < 20.0f;
        for (Legend legend : st.legends) {
            if (closed && hiddenByCover(legend.id())) {
                continue;
            }
            float width = context.font().width(legend.text());
            float dx = switch (legend.align()) {
                case LEFT -> 0.0f;
                case CENTER -> -width / 2.0f;
                case RIGHT -> -width;
            };
            context.textRotated(legend.text(), legend.x(), legend.y(), legend.capCells() / Legends.CAP_PIXELS, legend.rotation(),
                    legend.z() + TEXT_LIFT, legend.ink(), false, dx, -Legends.CAP_PIXELS / 2.0f);
        }
    }

    // ---- painting

    private static void paint(State st, DisplayTextures.Entry entry, PaperlessRecorderData data, PanelComponent component,
                              long tick, int width, int height) {
        if (st.canvas == null || st.canvas.width != width || st.canvas.height != height) {
            st.canvas = new RecorderCanvas(width, height);
            st.shown = new int[width * height];
        }
        long start = RecorderClientStats.timing ? System.nanoTime() : 0L;
        RecorderPainter.paint(st.canvas, data, component, st.ui, tick, System.currentTimeMillis() / 1000L);
        int[] pixels = st.canvas.pixels();
        NativeImage image = entry.image();
        boolean first = st.lastEntry != entry; // a new texture (the first, or one made again after an idle release) is blank
        st.lastEntry = entry;
        for (int y = 0; y < height; y++) {
            int row = y * width;
            for (int x = 0; x < width; x++) {
                int p = pixels[row + x];
                if (p != st.shown[row + x] || first) {
                    image.setPixelRGBA(x, y, p);
                    st.shown[row + x] = p;
                }
            }
        }
        entry.texture.upload();
        st.paints++;
        if (RecorderClientStats.timing) {
            RecorderClientStats.paintNanos += System.nanoTime() - start;
            RecorderClientStats.paintCount++;
        }
    }
}
