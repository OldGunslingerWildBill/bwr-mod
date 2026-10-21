package dev.bwr.mod.panel.client;

import dev.bwr.mod.panel.component.NameplateData;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.model.GaugeScale;

/**
 * Black engraved nameplate: a flat plate with a thin raised rim and up to three lines of text, stacked and centred.
 * A valve nameplate (the Columbia SRV plates) adds an optional coloured stripe down the left edge, and a band along
 * the bottom with a coloured dot, a corner field and a coloured tag with its own text at the left, and a corner field
 * at the right. The text fit (scales and positions of every piece) is worked out once per revision into the
 * component's {@link RenderCache} so drawing a frame is just the draw calls; the tag's plate and the dot, which depend
 * on the same fit, are recorded with the static body.
 */
final class NameplateModel implements ComponentModel {
    private static final float RIM = 0.25f;
    private static final float PLATE_TOP = GaugeScale.NAMEPLATE_TOP;
    private static final float MARGIN = 0.6f;
    private static final int TAG_INK = 0xFFFFFF;

    /** Everything the plate draws, in cells; computed once per revision. */
    private static final class Plan {
        final int revision;
        final float aspect;
        float stripeWidth;
        /** Text lines present (in order), their text, position and scale; the three arrays have {@code lineCount} entries. */
        int lineCount;
        final String[] lines = new String[NameplateData.LINES];
        final float[] lineX = new float[NameplateData.LINES];
        final float[] lineY = new float[NameplateData.LINES];
        float lineScale;
        // the bottom band: dot, left corner, tag (plate and text), right corner
        boolean hasDot;
        float dotX, dotY, dotRadius;
        String cornerLeft = "";
        float cornerLeftX, bandY, bandScale;
        String tag = "";
        float tagX, tagW, tagH, tagTextX;
        String cornerRight = "";
        float cornerRightX;

        Plan(PanelComponent component, float aspect) {
            this.revision = component.revision();
            this.aspect = aspect;
        }

        boolean current(PanelComponent component, float aspect) {
            return revision == component.revision() && this.aspect == aspect;
        }
    }

    private static Plan plan(PanelComponent component, float aspect, net.minecraft.client.gui.Font font) {
        RenderCache cache = RenderCache.of(component);
        if (cache.scratch instanceof Plan existing && existing.current(component, aspect)) {
            return existing;
        }
        Plan plan = new Plan(component, aspect);
        NameplateData data = (NameplateData) component.data();
        float w = component.w();
        float h = component.h();
        plan.stripeWidth = data.stripe ? Math.max(1.0f, Math.min(2.0f, w * 0.06f)) : 0.0f;
        boolean bottom = !data.cornerLeft.isEmpty() || !data.cornerRight.isEmpty() || !data.tag.isEmpty() || data.dot;
        float band = bottom ? Math.min(h * 0.34f, 3.2f) : 0.0f;
        float x0 = RIM + plan.stripeWidth + MARGIN;
        float x1 = w - RIM - MARGIN;
        float top = MARGIN * 0.6f;
        float bottomOfText = h - MARGIN * 0.6f - band;
        for (int i = 0; i < NameplateData.LINES; i++) {
            if (!data.line(i).isEmpty()) {
                plan.lines[plan.lineCount++] = data.line(i);
            }
        }
        if (plan.lineCount > 0) {
            float slot = (bottomOfText - top) / plan.lineCount;
            float scale = Float.MAX_VALUE;
            for (int i = 0; i < plan.lineCount; i++) {
                float width = Math.max(1, font.width(plan.lines[i]));
                scale = Math.min(scale, Math.min((x1 - x0) / width, slot / aspect * 0.9f / 8.0f));
            }
            plan.lineScale = scale;
            for (int i = 0; i < plan.lineCount; i++) {
                float width = Math.max(1, font.width(plan.lines[i]));
                plan.lineX[i] = x0 + ((x1 - x0) - width * scale) / 2.0f;
                plan.lineY[i] = top + slot * i + (slot - 8.0f * scale * aspect) / 2.0f;
            }
        }
        if (bottom) {
            float bandTop = h - MARGIN * 0.6f - band;
            plan.bandY = bandTop;
            plan.bandScale = band / aspect * 0.8f / 8.0f;
            float x = x0;
            if (data.dot) {
                plan.hasDot = true;
                plan.dotRadius = band * 0.3f;
                plan.dotX = x + plan.dotRadius;
                plan.dotY = bandTop + band / 2.0f;
                x += plan.dotRadius * 2.0f + MARGIN;
            }
            plan.cornerLeft = data.cornerLeft;
            plan.cornerLeftX = x;
            if (!data.cornerLeft.isEmpty()) {
                x += font.width(data.cornerLeft) * plan.bandScale + MARGIN;
            }
            if (!data.tag.isEmpty()) {
                plan.tag = data.tag;
                float textWidth = font.width(data.tag) * plan.bandScale;
                plan.tagX = x;
                plan.tagW = textWidth + 0.8f;
                plan.tagH = band * 0.92f;
                plan.tagTextX = x + 0.4f;
            }
            plan.cornerRight = data.cornerRight;
            plan.cornerRightX = x1 - font.width(data.cornerRight) * plan.bandScale;
        }
        cache.scratch = plan;
        return plan;
    }

    @Override
    public void buildStatic(QuadSink sink, PanelComponent component) {
        buildStatic(sink, component, 1.0f);
    }

    @Override
    public void buildStatic(QuadSink sink, PanelComponent component, float rowAspect) {
        NameplateData data = (NameplateData) component.data();
        float w = component.w();
        float h = component.h();
        sink.box(0, 0, w, h, 0, PLATE_TOP, data.plate, false);
        int rim = QuadSink.shade(data.plate, 1.6f);
        sink.rect(0, 0, w, RIM, PLATE_TOP + 0.02f, rim, false);
        sink.rect(0, h - RIM, w, RIM, PLATE_TOP + 0.02f, rim, false);
        sink.rect(0, RIM, RIM, h - 2 * RIM, PLATE_TOP + 0.02f, rim, false);
        sink.rect(w - RIM, RIM, RIM, h - 2 * RIM, PLATE_TOP + 0.02f, rim, false);
        Plan plan = plan(component, rowAspect, net.minecraft.client.Minecraft.getInstance().font);
        if (data.stripe) {
            sink.rect(RIM, RIM, plan.stripeWidth, h - 2 * RIM, PLATE_TOP + 0.03f, data.stripeColor, false);
        }
        if (plan.hasDot) {
            Shapes.disc(sink, plan.dotX, plan.dotY, plan.dotRadius, PLATE_TOP + 0.04f, data.dotColor, 10, false);
        }
        if (!plan.tag.isEmpty()) {
            sink.rect(plan.tagX, plan.bandY + (h - MARGIN * 0.6f - plan.bandY - plan.tagH) / 2.0f, plan.tagW, plan.tagH,
                    GaugeScale.NAMEPLATE_TAG_Z, data.tagColor, false);
        }
    }

    @Override
    public void renderDynamic(PanelRenderContext context, PanelComponent component) {
        if (context.lod() < PanelRenderContext.LOD_FULL) {
            return;
        }
        NameplateData data = (NameplateData) component.data();
        Plan plan = plan(component, context.rowAspect(), context.font());
        float z = GaugeScale.NAMEPLATE_TEXT_Z;
        for (int i = 0; i < plan.lineCount; i++) {
            context.text(plan.lines[i], plan.lineX[i], plan.lineY[i], plan.lineScale, z, data.ink, false);
        }
        float textY = plan.bandY + ((component.h() - MARGIN * 0.6f - plan.bandY) - 8.0f * plan.bandScale * plan.aspect) / 2.0f;
        if (!plan.cornerLeft.isEmpty()) {
            context.text(plan.cornerLeft, plan.cornerLeftX, textY, plan.bandScale, z, data.ink, false);
        }
        if (!plan.tag.isEmpty()) {
            context.text(plan.tag, plan.tagTextX, textY, plan.bandScale, GaugeScale.NAMEPLATE_TAG_TEXT_Z, TAG_INK, false);
        }
        if (!plan.cornerRight.isEmpty()) {
            context.text(plan.cornerRight, plan.cornerRightX, textY, plan.bandScale, z, data.ink, false);
        }
    }
}
