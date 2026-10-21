package dev.bwr.mod.panel.client;

import dev.bwr.mod.panel.component.AnnunciatorControlType;
import dev.bwr.mod.panel.component.PanelComponent;

/**
 * Annunciator control module: a dark housing with a row of four pushbuttons, ACK, RESET, SILENCE and TEST, each with
 * its legend under it. A button sits low and glows while it is held (the output is 1), like a pushbutton. At the real
 * size (2 x 1 cells) the legends are far too small to read, which is what the distance fade is for.
 */
final class AnnunciatorControlModel implements ComponentModel {
    static final String[] LEGENDS = {"ACK", "RESET", "SILENCE", "TEST"};
    /** Cap colours: white, blue, amber, green. */
    static final int[] CAPS = {0xE6E2D0, 0x4FA0E0, 0xE8A820, 0x30B050};
    /** The buttons' share of the module's height; the legends take the rest. */
    /** The housing's top face: the legends are printed on it, a hair above. */
    private static final float HOUSING_TOP = 1.0f;
    private static final float BUTTON_SHARE = 0.6f;

    private static final class Widths {
        final int[] pixels = new int[LEGENDS.length];
        boolean measured;
    }

    /** Side of a button's square bezel: the pitch, less a gap, or the button row's height. */
    static float bezel(float w, float h) {
        float pitch = w / AnnunciatorControlType.BUTTONS;
        return Math.min(pitch * 0.92f, h * BUTTON_SHARE * 0.96f);
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
        sink.box(0, 0, w, h, 0, HOUSING_TOP, ModelDraw.HOUSING, false);
        float bezel = bezel(w, h);
        float ring = bezel * 0.1f;
        for (int i = 0; i < AnnunciatorControlType.BUTTONS; i++) {
            float x = bezelX(w, i, bezel);
            float y = bezelY(h, bezel);
            sink.box(x, y, bezel, bezel, 1.0f, 1.3f, ModelDraw.BEZEL, false);
            sink.box(x + ring, y + ring, bezel - 2 * ring, bezel - 2 * ring, 1.0f, 1.5f, ModelDraw.RECESS, false);
        }
    }

    @Override
    public void renderDynamic(PanelRenderContext context, PanelComponent component) {
        float w = component.w();
        float h = component.h();
        float bezel = bezel(w, h);
        float inset = bezel * 0.2f;
        for (int i = 0; i < AnnunciatorControlType.BUTTONS; i++) {
            boolean held = component.output(i) > 0.5;
            int color = held ? CAPS[i] : QuadSink.shade(CAPS[i], 0.8f);
            float x = bezelX(w, i, bezel) + inset;
            float y = bezelY(h, bezel) + inset;
            // A button stands out in proportion to its size, so the real size module's caps are not posts.
            float stand = Math.min(1.0f, bezel / 3.0f);
            context.box(x, y, bezel - 2 * inset, bezel - 2 * inset, HOUSING_TOP, HOUSING_TOP + (held ? 1.0f : 2.2f) * stand, color, held);
        }
        if (context.lod() < PanelRenderContext.LOD_FULL) {
            return;
        }
        RenderCache cache = RenderCache.of(component);
        Widths widths = cache.scratch instanceof Widths existing ? existing : new Widths();
        cache.scratch = widths;
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
            float x = pitch * i + (pitch - widths.pixels[i] * scale) / 2.0f;
            float y = labelTop + (labelHeight - 8.0f * scale * rowAspect) / 2.0f;
            context.text(LEGENDS[i], x, y, scale, HOUSING_TOP + 0.05f, ModelDraw.ENGRAVED, false);
        }
    }
}
