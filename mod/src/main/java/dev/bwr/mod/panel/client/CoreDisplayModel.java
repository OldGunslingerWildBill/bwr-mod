package dev.bwr.mod.panel.client;

import dev.bwr.mod.panel.component.CoreDisplayData;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.model.BakedPanelModel;
import dev.bwr.mod.panel.model.PanelModels;
import dev.bwr.mod.panel.model.PartDraw;
import dev.bwr.mod.panel.model.Placement;
import net.minecraft.client.resources.language.I18n;

/**
 * What the two core displays share on the client: the per-revision state hung on the component's
 * {@link RenderCache}, the baked model kits with procedural fallbacks, legend fitting, and the split of work the
 * panel's GPU cache wants.
 *
 * <h3>What is drawn when</h3>
 * <ul>
 *   <li>{@link #buildStatic}: everything that never changes while the layout stays: backplate, tile bodies, unlit
 *       windows, LPRM dots, grid lines. Recorded once per component revision (the layout change touches the
 *       revision) and kept in the block's vertex buffer.</li>
 *   <li>{@link #poseKey} / {@link #emitPosed}: what the snapshot lights: lamp emission and the digits. The block's
 *       buffer is rebuilt only when {@link dev.bwr.mod.panel.fcd.FcdSnapshot#visualKey()} changes, so a display with
 *       nothing moving costs no model code at all; the LPRM percentages, which are not drawn, never rebuild it.</li>
 *   <li>{@link #emitLegends}: every printed legend (rod coordinates, FULL IN, DRIFT ...). Static text is not
 *       re-laid out each frame: the block's text cache ({@link PanelMeshCache}) captures it once into vertex
 *       buffers per revision and draws it with one call. A lamp that is off therefore costs no text draw at all.</li>
 * </ul>
 * Beyond the panel's full-detail distance the renderer skips legends and glass as for every component, so far away a
 * display is its coloured quads only.
 */
abstract class CoreDisplayModel implements ComponentModel {
    /**
     * Whether legends go into the block's text cache. {@code -Dbwr.fcdLegendCache=false} lays them out every frame
     * instead, which is what the panel cache did before it could hold text: the number the cache is measured against.
     */
    static final boolean LEGEND_CACHE = !"false".equals(System.getProperty("bwr.fcdLegendCache"));
    /** {@code -Dbwr.fcdKits=false} ignores baked models and draws the procedural bodies (a check switch). */
    private static final boolean KITS = !"false".equals(System.getProperty("bwr.fcdKits"));

    /** Dim window colour of an unlit legend plate: frosted white plastic with a lamp behind it. */
    static final int PLATE = 0xF2F3EC;
    static final int BLACK = 0x0A0B0C;
    static final int FRAME = 0x2A2D31;
    static final int METAL = 0xB8BCC0;
    static final int INK = 0x15171A;
    /** Brightness of an unlit baked lens: the legend plates read pale, as on the photographed boards. */
    static final float LENS_OFF = 0.80f;

    /** Z of the glow over a window, and of the printed legend over it, in 1/16 block. */
    static final float GLOW_Z = 1.25f;
    static final float TEXT_Z = 1.35f;

    /** A baked model laid out for one instance (a tile, a button, a module): its parts by name and its placement. */
    static final class Kit {
        final BakedPanelModel model;
        final Placement place;
        final BakedPanelModel.Part[] statics;
        final float[] scratch = new float[PartDraw.SCRATCH];

        private Kit(BakedPanelModel model, Placement place) {
            this.model = model;
            this.place = place;
            java.util.List<BakedPanelModel.Part> list = new java.util.ArrayList<>();
            for (int i = 0; i < model.partCount(); i++) {
                BakedPanelModel.Part part = model.part(i);
                if (part.role == BakedPanelModel.Role.PAINT || part.role == BakedPanelModel.Role.NEEDLE) {
                    if (!part.moving()) {
                        list.add(part);
                    }
                }
            }
            statics = list.toArray(new BakedPanelModel.Part[0]);
        }

        /** The kit of model {@code id} fitted into {@code w x h} cells, or null when no such model is loaded. */
        static Kit of(String id, float w, float h, float rowAspect) {
            BakedPanelModel model = KITS ? PanelModels.get(id) : null;
            return model == null ? null : new Kit(model, Placement.fit(model, w, h, rowAspect));
        }

        /** Every static (paint) part, offset to {@code (dx, dy)}. */
        void emitStatic(QuadSink sink, OffsetSink offset, float dx, float dy) {
            offset.at(sink, dx, dy);
            for (BakedPanelModel.Part part : statics) {
                PartDraw.emit(offset, part, place, 0.0f, 0.0f, -1, 1.0f, false, scratch);
            }
        }

        /**
         * One named part (a lamp, a cap, a glass), offset to {@code (dx, dy)}, recoloured when {@code rgb} is not
         * negative, slid {@code slide} of its travel.
         */
        void emitPart(QuadSink sink, OffsetSink offset, String name, float dx, float dy, int rgb, float brightness,
                      boolean emissive, float slide) {
            BakedPanelModel.Part part = model.part(name);
            if (part != null) {
                offset.at(sink, dx, dy);
                PartDraw.emit(offset, part, place, 0.0f, slide, rgb, brightness, emissive, scratch);
            }
        }

        /**
         * The bounding box of a part in cells, relative to the instance's top-left: x, y, width, height; null when
         * the model has no such part.
         */
        float[] bounds(String name) {
            BakedPanelModel.Part part = model.part(name);
            if (part == null || part.faceCount == 0) {
                return null;
            }
            float minX = Float.MAX_VALUE;
            float minY = Float.MAX_VALUE;
            float maxX = -Float.MAX_VALUE;
            float maxY = -Float.MAX_VALUE;
            for (int f = 0; f < part.faceCount; f++) {
                for (int c = 0; c < 4; c++) {
                    minX = Math.min(minX, part.corner(f, c, 0));
                    maxX = Math.max(maxX, part.corner(f, c, 0));
                    minY = Math.min(minY, part.corner(f, c, 1));
                    maxY = Math.max(maxY, part.corner(f, c, 1));
                }
            }
            return new float[] {place.x(minX), place.y(minY), place.x(maxX) - place.x(minX), place.y(maxY) - place.y(minY)};
        }

        /** The highest elevation of any part (1/16 block): where printed legends go, just over it. */
        float topZ() {
            float top = 0.0f;
            for (int i = 0; i < model.partCount(); i++) {
                BakedPanelModel.Part part = model.part(i);
                if (part.role == BakedPanelModel.Role.GLASS) {
                    continue;
                }
                for (int f = 0; f < part.faceCount; f++) {
                    for (int c = 0; c < 4; c++) {
                        top = Math.max(top, part.corner(f, c, 2));
                    }
                }
            }
            return place.z(top);
        }

        boolean has(String name) {
            return model.part(name) != null;
        }

        BakedPanelModel.Label label(String id) {
            return model.label(id);
        }

        /** Cell x of a label anchor of an instance at {@code dx}. */
        float labelX(BakedPanelModel.Label label, float dx) {
            return dx + place.x(label.x);
        }

        float labelY(BakedPanelModel.Label label, float dy) {
            return dy + place.y(label.y);
        }
    }

    // ---- legends

    /**
     * Prints {@code text} centred in the box at {@code (cx, cy)} of {@code w x h} cells, on up to two lines (a
     * space makes a line break), as large as fits. {@code z} is the elevation of the print.
     */
    static void fitted(PanelRenderContext context, String text, float cx, float cy, float w, float h, float z, int ink) {
        int split = text.indexOf(' ');
        if (split > 0) {
            String first = text.substring(0, split);
            String second = text.substring(split + 1);
            float lineH = h / 2.0f;
            line(context, first, cx, cy - lineH / 2.0f, w, lineH, z, ink);
            line(context, second, cx, cy + lineH / 2.0f, w, lineH, z, ink);
        } else {
            line(context, text, cx, cy, w, h, z, ink);
        }
    }

    private static void line(PanelRenderContext context, String text, float cx, float cy, float w, float h, float z, int ink) {
        float pixels = context.font().width(text);
        if (pixels <= 0.0f) {
            return;
        }
        // A capital is 7 font pixels tall: fit the height first (a little under the box), then the width.
        float scale = Math.min(h * 0.62f / 7.0f, w * 0.9f / pixels);
        context.textRotated(text, cx, cy, scale, 0.0f, z, ink, false, -pixels / 2.0f, -3.5f);
    }

    // ---- shared frame

    /** A dark frame with the message that the core needs a larger component. */
    static void frame(QuadSink sink, PanelComponent component) {
        sink.box(0, 0, component.w(), component.h(), 0.0f, 0.8f, 0x15171A, false);
        float m = Math.min(component.w(), component.h()) * 0.04f;
        sink.rect(m, m, component.w() - 2 * m, component.h() - 2 * m, 0.9f, 0x0B0C0D, false);
    }

    /** The "needs W x H" legend of a component whose drawing does not fit. */
    static void needsLegend(PanelRenderContext context, PanelComponent component, int[] needed) {
        String text = I18n.get("text.bwr.core_display.needs_size", needed[0], needed[1]);
        float w = component.w();
        float h = component.h();
        float pixels = context.font().width(text);
        float scale = Math.min(h * 0.1f / 7.0f * 3.0f, w * 0.8f / Math.max(1.0f, pixels));
        context.textRotated(text, w / 2.0f, h / 2.0f, scale, 0.0f, 1.2f, 0xE0C060, false, -pixels / 2.0f, -3.5f);
    }

    /** Data of a component that is a core display. */
    static CoreDisplayData data(PanelComponent component) {
        return (CoreDisplayData) component.data();
    }
}
