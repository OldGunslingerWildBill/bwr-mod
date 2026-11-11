package dev.bwr.mod.panel.client;

import dev.bwr.mod.panel.component.AnnunciatorControlData;
import dev.bwr.mod.panel.component.AnnunciatorControlType;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.model.BakedPanelModel;
import dev.bwr.mod.panel.model.Legends;
import dev.bwr.mod.panel.model.PanelModels;
import dev.bwr.mod.panel.model.PartDraw;
import dev.bwr.mod.panel.model.Placement;

/**
 * Annunciator control module: a dark housing with a row of four pushbuttons, ACK, RESET, SILENCE and TEST, each with
 * its legend under it. A button sits low and glows while it is held (the output is 1), like a pushbutton. At the real
 * size (2 x 1 cells) the legends are far too small to read, which is what the distance fade is for.
 *
 * <p>A module whose Style is the pushbutton station is drawn from the station model instead ({@code ann_control}, built
 * by {@code art/models/panel/annunciator} after a photograph of a local panel): four round black pushbuttons in bright
 * bezel rings set straight in the panel face, a black legend plate under each. Its rings and plates are recorded; its
 * four caps ({@link #CAP_PARTS}, in the order of the outputs) are drawn each frame, black at rest and slid in and
 * glowing in the button's colour while held; the same four legends are printed on the plates, at the model's label
 * anchors ({@link #LABELS}). The model is four buttons wide as the module is, so the quarter of the face a click lands
 * on is still the button under it.
 */
final class AnnunciatorControlModel implements ComponentModel {
    static final String[] LEGENDS = {"ACK", "RESET", "SILENCE", "TEST"};
    /** Cap colours: white, blue, amber, green. */
    static final int[] CAPS = {0xE6E2D0, 0x4FA0E0, 0xE8A820, 0x30B050};
    /** The buttons' share of the module's height; the legends take the rest. */
    /** The housing's top face: the legends are printed on it, a hair above. */
    private static final float HOUSING_TOP = 1.0f;
    private static final float BUTTON_SHARE = 0.6f;

    /** The caps of the station model and the label anchors of their legend plates, one per output. */
    static final String[] CAP_PARTS = {"cap_ack", "cap_reset", "cap_silence", "cap_test"};
    static final String[] LABELS = {"ack", "reset", "silence", "test"};
    /** The most of a button's quarter of the width a legend may take: the legend plate, less its edge. */
    private static final float PLATE_TEXT_SHARE = 0.7f;

    private static final class Widths {
        final int[] pixels = new int[LEGENDS.length];
        boolean measured;
        /** The station model drawing this module and how it lies on the module's cells; null for the procedural look. */
        BakedPanelModel model;
        Placement place;
        final float[] quad = new float[PartDraw.SCRATCH];
    }

    private static Widths state(PanelComponent component) {
        RenderCache cache = RenderCache.of(component);
        Widths widths = cache.scratch instanceof Widths existing ? existing : new Widths();
        cache.scratch = widths;
        return widths;
    }

    /** The station model this module is drawn from, or null: its style is classic, or the file is missing or not a station. */
    private static BakedPanelModel station(PanelComponent component) {
        if (!(component.data() instanceof AnnunciatorControlData data) || !AnnunciatorControlData.STYLE_STATION.equals(data.style)) {
            return null;
        }
        BakedPanelModel model = PanelModels.get(AnnunciatorControlData.STATION_MODEL);
        if (model == null || !(model.footprintW > 0.0f) || !(model.footprintH > 0.0f)) {
            return null;
        }
        for (int i = 0; i < CAP_PARTS.length; i++) {
            if (model.part(CAP_PARTS[i]) == null || model.label(LABELS[i]) == null) {
                return null;
            }
        }
        return model;
    }

    /** Side of a button's square bezel: the pitch, less a gap, or the button row's height. */
    static float bezel(float w, float h) {
        float pitch = w / AnnunciatorControlType.BUTTONS;
        return Math.min(pitch * 0.92f, h * BUTTON_SHARE * 0.96f);
    }

    /** A button stands out in proportion to its size, so the real size module's caps are not posts. */
    private static float stand(float bezel) {
        return Math.min(1.0f, bezel / 3.0f);
    }

    private static float bezelX(float w, int i, float bezel) {
        float pitch = w / AnnunciatorControlType.BUTTONS;
        return pitch * i + (pitch - bezel) / 2.0f;
    }

    private static float bezelY(float h, float bezel) {
        return (h * BUTTON_SHARE - bezel) / 2.0f + h * 0.03f;
    }

    @Override
    public void buildStatic(QuadSink sink, PanelComponent component) {
        float w = component.w();
        float h = component.h();
        Widths state = state(component);
        state.model = station(component);
        state.place = state.model == null ? null : Placement.fit(state.model, w, h);
        if (state.model != null) {
            for (int i = 0; i < state.model.partCount(); i++) {
                BakedPanelModel.Part part = state.model.part(i);
                if (!part.moving() && part.role != BakedPanelModel.Role.GLASS) {
                    PartDraw.emit(sink, part, state.place, 0.0f, 0.0f, -1, 1.0f, false, state.quad);
                }
            }
            return;
        }
        sink.box(0, 0, w, h, 0, HOUSING_TOP, ModelDraw.HOUSING, false);
        float bezel = bezel(w, h);
        float ring = bezel * 0.1f;
        float stand = stand(bezel);
        for (int i = 0; i < AnnunciatorControlType.BUTTONS; i++) {
            float x = bezelX(w, i, bezel);
            float y = bezelY(h, bezel);
            sink.box(x, y, bezel, bezel, HOUSING_TOP, HOUSING_TOP + 0.3f * stand, ModelDraw.BEZEL, false);
            sink.box(x + ring, y + ring, bezel - 2 * ring, bezel - 2 * ring, HOUSING_TOP, HOUSING_TOP + 0.5f * stand, ModelDraw.RECESS, false);
        }
    }

    @Override
    public void renderDynamic(PanelRenderContext context, PanelComponent component) {
        Widths widths = state(component);
        if (widths.model != null && widths.place != null) {
            renderStation(context, component, widths);
            return;
        }
        float w = component.w();
        float h = component.h();
        float bezel = bezel(w, h);
        float inset = bezel * 0.2f;
        for (int i = 0; i < AnnunciatorControlType.BUTTONS; i++) {
            boolean held = component.output(i) > 0.5;
            int color = held ? CAPS[i] : QuadSink.shade(CAPS[i], 0.8f);
            float x = bezelX(w, i, bezel) + inset;
            float y = bezelY(h, bezel) + inset;
            float stand = stand(bezel);
            context.box(x, y, bezel - 2 * inset, bezel - 2 * inset, HOUSING_TOP, HOUSING_TOP + (held ? 0.9f : 2.2f) * stand, color, held);
        }
        if (context.lod() < PanelRenderContext.LOD_FULL) {
            return;
        }
        measure(context, widths);
        float pitch = w / AnnunciatorControlType.BUTTONS;
        float labelTop = h * BUTTON_SHARE + h * 0.03f;
        float labelHeight = h - labelTop - h * 0.14f;
        float rowAspect = context.rowAspect();
        for (int i = 0; i < LEGENDS.length; i++) {
            float scale = Math.min(pitch * 0.92f / widths.pixels[i], labelHeight / (8.0f * rowAspect));
            float x = pitch * i + (pitch - widths.pixels[i] * scale) / 2.0f;
            float y = labelTop + (labelHeight - 8.0f * scale * rowAspect) / 2.0f;
            context.text(LEGENDS[i], x, y, scale, HOUSING_TOP + 0.05f, ModelDraw.ENGRAVED, false);
        }
    }

    /** The legends' widths in font pixels, measured once. */
    private static void measure(PanelRenderContext context, Widths widths) {
        if (!widths.measured) {
            for (int i = 0; i < LEGENDS.length; i++) {
                widths.pixels[i] = Math.max(1, context.font().width(LEGENDS[i]));
            }
            widths.measured = true;
        }
    }

    /** The station model's moving parts and legends: each cap in or out, and the legend on the plate under it. */
    private static void renderStation(PanelRenderContext context, PanelComponent component, Widths widths) {
        BakedPanelModel model = widths.model;
        Placement place = widths.place;
        for (int i = 0; i < CAP_PARTS.length; i++) {
            BakedPanelModel.Part cap = model.part(CAP_PARTS[i]);
            if (cap == null) {
                continue;
            }
            // Black at rest; pushed in and glowing in the button's own colour while its output is 1.
            boolean held = component.output(i) > 0.5;
            PartDraw.emit(context, cap, place, 0.0f, held ? 1.0f : 0.0f, held ? CAPS[i] : -1, 1.0f, held, widths.quad);
        }
        if (context.lod() < PanelRenderContext.LOD_FULL) {
            return;
        }
        measure(context, widths);
        float room = PLATE_TEXT_SHARE * place.length(model.footprintW) / AnnunciatorControlType.BUTTONS;
        for (int i = 0; i < LABELS.length; i++) {
            BakedPanelModel.Label label = model.label(LABELS[i]);
            if (label == null) {
                continue;
            }
            float scale = Math.min(place.length(label.height) / Legends.CAP_PIXELS, room / widths.pixels[i]);
            context.text(LEGENDS[i], place.x(label.x) - widths.pixels[i] * scale / 2.0f,
                    place.y(label.y) - Legends.CAP_PIXELS * scale / 2.0f, scale, place.z(label.z) + 0.05f, ModelDraw.ENGRAVED, false);
        }
    }
}
