package dev.bwr.mod.panel.client;

import dev.bwr.mod.panel.component.AnnunciatorControlData;
import dev.bwr.mod.panel.component.AnnunciatorControlType;
import dev.bwr.mod.panel.component.AnnunciatorData;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.model.BakedPanelModel;
import dev.bwr.mod.panel.model.PanelModels;
import dev.bwr.mod.panel.model.PartDraw;
import dev.bwr.mod.panel.model.Placement;

/**
 * Annunciator control module: a dark housing with a row of four pushbuttons, ACK, RESET, SILENCE and TEST, each with
 * its legend under it. A button sits low and glows while it is held (the output is 1), like a pushbutton. At the real
 * size (2 x 1 cells) the legends are far too small to read, which is what the distance fade is for.
 *
 * <p>A module whose Style is the lamp box is drawn from the control station model instead ({@code ann_control}, built by
 * {@code art/models/panel/annunciator}): its plate and bezels are recorded, its four caps ({@link #CAP_PARTS}, in the
 * order of the outputs) slide in and glow while held, and the same four legends are printed under them. The model is
 * four buttons wide as the module is, so the quarter of the face a click lands on is still the button under it.
 */
final class AnnunciatorControlModel implements ComponentModel {
    static final String[] LEGENDS = {"ACK", "RESET", "SILENCE", "TEST"};
    /** Cap colours: white, blue, amber, green. */
    static final int[] CAPS = {0xE6E2D0, 0x4FA0E0, 0xE8A820, 0x30B050};
    /** The buttons' share of the module's height; the legends take the rest. */
    /** The housing's top face: the legends are printed on it, a hair above. */
    private static final float HOUSING_TOP = 1.0f;
    private static final float BUTTON_SHARE = 0.6f;

    /** The caps of the control station model, one per output. */
    static final String[] CAP_PARTS = {"cap_ack", "cap_reset", "cap_silence", "cap_test"};
    /** A label anchor of the station model: its elevation is the plate the legends are printed on. */
    static final String PLATE_ANCHOR = "ack";

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
        if (!(component.data() instanceof AnnunciatorControlData data) || !AnnunciatorData.STYLE_LAMPBOX.equals(data.style)) {
            return null;
        }
        BakedPanelModel model = PanelModels.get(AnnunciatorControlData.STATION_MODEL);
        if (model == null || model.label(PLATE_ANCHOR) == null) {
            return null;
        }
        for (String cap : CAP_PARTS) {
            if (model.part(cap) == null) {
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
        float w = component.w();
        float h = component.h();
        Widths widths = state(component);
        BakedPanelModel model = widths.model;
        // The rectangle the four buttons and their legends are laid out in, and the surface the legends are printed on:
        // the whole module, or what the station model covers of it.
        float left = 0.0f;
        float top = 0.0f;
        float plate = HOUSING_TOP;
        if (model != null) {
            Placement place = widths.place;
            for (int i = 0; i < AnnunciatorControlType.BUTTONS; i++) {
                boolean held = component.output(i) > 0.5;
                PartDraw.emit(context, model.part(CAP_PARTS[i]), place, 0.0f, held ? 1.0f : 0.0f, -1, held ? 1.0f : 0.8f, held,
                        widths.quad);
            }
            left = place.offsetX;
            top = place.offsetY;
            w = model.footprintW * place.scale;
            h = model.footprintH * place.rowScale;
            plate = place.z(model.label(PLATE_ANCHOR).z);
        } else {
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
        }
        if (context.lod() < PanelRenderContext.LOD_FULL) {
            return;
        }
        if (!widths.measured) {
            for (int i = 0; i < LEGENDS.length; i++) {
                widths.pixels[i] = Math.max(1, context.font().width(LEGENDS[i]));
            }
            widths.measured = true;
        }
        float pitch = w / AnnunciatorControlType.BUTTONS;
        float labelTop = h * BUTTON_SHARE + h * 0.03f;
        float labelHeight = h - labelTop - h * 0.14f;
        float rowAspect = context.rowAspect();
        for (int i = 0; i < LEGENDS.length; i++) {
            float scale = Math.min(pitch * 0.92f / widths.pixels[i], labelHeight / (8.0f * rowAspect));
            float x = left + pitch * i + (pitch - widths.pixels[i] * scale) / 2.0f;
            float y = top + labelTop + (labelHeight - 8.0f * scale * rowAspect) / 2.0f;
            context.text(LEGENDS[i], x, y, scale, plate + 0.05f, ModelDraw.ENGRAVED, false);
        }
    }
}
