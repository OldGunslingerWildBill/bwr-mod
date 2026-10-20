package dev.bwr.mod.panel.client;

import dev.bwr.mod.panel.component.GaugeData;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.display.PanelFormat;
import dev.bwr.mod.panel.model.BakedPanelModel;
import dev.bwr.mod.panel.model.GaugeScale;
import dev.bwr.mod.panel.model.Legends;

import java.util.ArrayList;
import java.util.List;

/**
 * Round dial: a dark square bezel, a cream face with a 270 degree scale
 * (coloured bands, 21 ticks) recorded once per revision, and a needle that
 * follows the input with the configured lag. No signal pegs the needle at the
 * low end and shows a red OFF flag; the min, max and units text draws only
 * close up.
 *
 * <p>Every size is a share of the face's radius ({@link GaugeScale}), so a real size 2 x 2 dial is the 12 x 12 one
 * scaled down; the wrench's Text size and Tick size multiply the text and the ticks.
 */
final class DialGaugeModel implements ComponentModel {
    private static final float START = (float) Math.toRadians(-135.0);
    private static final float SWEEP = (float) Math.toRadians(270.0);
    private static final int BEZEL = 0x23282C;
    private static final int RIM = 0x14181B;
    private static final int FACE = 0xE9E5D4;
    private static final int INK = 0x202020;
    private static final int NEEDLE = 0xD03010;
    private static final int FLAG = 0xC02020;
    /** Font pixels per face radius of the labels (a cap is 7 of them: about a quarter of the radius). */
    private static final float TEXT = 0.035f;
    /** Where the labels sit, in face radii from the centre: the end labels' outer ends and top row, the units' top row. */
    private static final float LABEL_X = 0.78f;
    private static final float LABEL_ROW = 0.66f;
    private static final float UNITS_ROW = 0.26f;
    /** The lowest the labels' bottom may reach (face radii; the bezel's edge is at 1.11) and the gap between the end labels. */
    private static final float LABEL_BOTTOM = 1.05f;
    private static final float LABEL_GAP = 0.1f;
    /** Tick ring, face radii: the outer end of every tick, and the inner ends of the minor and major ticks. */
    private static final float TICK_OUTER = 0.76f;
    private static final float MINOR_INNER = 0.66f;
    private static final float MAJOR_INNER = 0.56f;
    /** The furthest in a longer tick may reach, face radii (clear of the hub and the units). */
    private static final float TICK_INNERMOST = 0.42f;

    private static float face(PanelComponent component) {
        return Math.min(component.w(), component.h()) / 2.0f * 0.9f;
    }

    @Override
    public void buildStatic(QuadSink sink, PanelComponent component) {
        GaugeData data = (GaugeData) component.data();
        float w = component.w();
        float h = component.h();
        float cx = w / 2.0f;
        float cy = h / 2.0f;
        float radius = Math.min(w, h) / 2.0f;
        float face = radius * 0.9f;
        sink.box(0, 0, w, h, 0, 1.0f, BEZEL, false);
        Shapes.disc(sink, cx, cy, radius * 0.97f, 1.02f, RIM, 32, false);
        Shapes.disc(sink, cx, cy, face, 1.05f, FACE, 32, false);
        float step = (float) Math.toRadians(4.0);
        for (int i = 0; i < GaugeData.BANDS; i++) {
            if (data.bandHigh[i] > data.bandLow[i]) {
                float a0 = START + SWEEP * (float) data.fraction(data.bandLow[i]);
                float a1 = START + SWEEP * (float) data.fraction(data.bandHigh[i]);
                if (a1 > a0) {
                    Shapes.ring(sink, cx, cy, face * 0.80f, face * 0.96f, a0, a1, 1.1f, data.bandColor[i], step, false);
                }
            }
        }
        // Proportional throughout (the old 0.1 and 0.06 cell least widths only acted below the 12 x 12 size, where they
        // made a 2 x 2 dial's ticks two and a half times too fat). Tick size multiplies widths and lengths; a longer tick
        // grows inward, never past TICK_INNERMOST.
        float tick = data.tickFactor();
        float majorWidth = face * 0.04f * tick;
        float minorWidth = face * 0.022f * tick;
        float majorInner = TICK_OUTER - (TICK_OUTER - MAJOR_INNER)
                * GaugeScale.tickLengthFactor(TICK_OUTER - MAJOR_INNER, tick, TICK_OUTER - TICK_INNERMOST);
        float minorInner = TICK_OUTER - (TICK_OUTER - MINOR_INNER)
                * GaugeScale.tickLengthFactor(TICK_OUTER - MINOR_INNER, tick, TICK_OUTER - TICK_INNERMOST);
        for (int i = 0; i <= 20; i++) {
            float angle = START + SWEEP * i / 20.0f;
            boolean major = i % 5 == 0;
            Shapes.spoke(sink, cx, cy, face * (major ? majorInner : minorInner), face * TICK_OUTER, angle,
                    major ? majorWidth : minorWidth, 1.12f, INK, false);
        }
        Shapes.disc(sink, cx, cy, face * 0.1f, 1.4f, 0x303030, 12, false);
    }

    @Override
    public void renderDynamic(PanelRenderContext context, PanelComponent component) {
        GaugeData data = (GaugeData) component.data();
        GaugeScratch scratch = GaugeScratch.of(component);
        double value = component.input(0);
        float position = scratch.follow((float) data.fraction(value), data.damping, context.gameTime());
        float w = component.w();
        float h = component.h();
        float cx = w / 2.0f;
        float cy = h / 2.0f;
        float face = face(component);
        float angle = START + SWEEP * position;
        Shapes.spoke(context, cx, cy, -face * 0.16f, face * 0.74f, angle, face * 0.055f, 1.3f, NEEDLE, false);
        boolean offScale = GaugeData.offScale(value);
        if (offScale) {
            context.rect(cx - face * 0.26f, cy + face * 0.42f, face * 0.52f, face * 0.2f, 1.2f, FLAG, false);
        }
        if (context.lod() < PanelRenderContext.LOD_FULL) {
            return;
        }
        scratch.drawText(context, component, this);
        if (offScale) {
            float scale = face * TEXT * 0.8f;
            context.text("OFF", cx - context.font().width("OFF") * scale / 2.0f, cy + face * 0.455f, scale, 1.25f,
                    0xFFFFFF, false);
        }
    }

    /**
     * The minimum and maximum under the ends of the scale and the units above the hub: a cap of a quarter of the face
     * radius times Text size, shrunk so the end labels stay apart and inside the bezel.
     */
    @Override
    public List<Legends.Item> textLayout(PanelComponent component, float rowAspect) {
        GaugeData data = (GaugeData) component.data();
        float face = face(component);
        float cx = component.w() / 2.0f;
        float cy = component.h() / 2.0f;
        String min = PanelFormat.formatNumber(data.min, "");
        String max = PanelFormat.formatNumber(data.max, "");
        float ends = Math.max(1, GaugeScale.textPixels(min) + GaugeScale.textPixels(max));
        float fit = Math.min((2.0f * LABEL_X - LABEL_GAP) * face / ends, (LABEL_BOTTOM - LABEL_ROW) * face / 8.0f);
        if (!data.units.isEmpty()) {
            fit = Math.min(fit, 1.5f * face / Math.max(1, GaugeScale.textPixels(data.units)));
        }
        float scale = GaugeScale.textCap(face * TEXT, data.textFactor(), fit);
        float cap = Legends.CAP_PIXELS * scale;
        float half = Legends.CAP_PIXELS / 2.0f * scale;
        List<Legends.Item> items = new ArrayList<>();
        items.add(new Legends.Item(min, cx - face * LABEL_X, cy + face * LABEL_ROW + half, 1.2f, cap, 0.0f,
                BakedPanelModel.Align.LEFT, INK, false, FACE));
        items.add(new Legends.Item(max, cx + face * LABEL_X, cy + face * LABEL_ROW + half, 1.2f, cap, 0.0f,
                BakedPanelModel.Align.RIGHT, INK, false, FACE));
        if (!data.units.isEmpty()) {
            items.add(new Legends.Item(data.units, cx, cy + face * UNITS_ROW + half, 1.2f, cap, 0.0f,
                    BakedPanelModel.Align.CENTER, INK, false, FACE));
        }
        return items;
    }
}
