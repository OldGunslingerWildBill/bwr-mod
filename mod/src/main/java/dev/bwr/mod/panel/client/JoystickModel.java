package dev.bwr.mod.panel.client;

import dev.bwr.mod.panel.component.JoystickData;
import dev.bwr.mod.panel.component.JoystickType;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.model.BakedPanelModel;
import dev.bwr.mod.panel.model.GaugeScale;
import dev.bwr.mod.panel.model.Legends;

import java.util.ArrayList;
import java.util.List;

/**
 * Joystick bypass switch, drawn in code: a dark square plate with four position marks and the legends round a round well, a
 * rubber boot in it and a lever that stands up in the centre and leans toward UP, DOWN, LEFT or RIGHT with its knob. The lit
 * mark of the direction the lever is in glows amber. Every size is a share of the plate's smaller side. The plate and the dim
 * marks are recorded once; the boot, the lever and the lit mark are drawn each frame from the position output.
 */
final class JoystickModel implements ComponentModel {
    private static final int PLATE = 0x2B2D2F;
    private static final int WELL = 0x0E0E0E;
    private static final int BOOT = 0x17181A;
    private static final int LEVER = 0x1C1D1F;
    private static final int KNOB = 0x2A2B2E;
    private static final int KNOB_HIGHLIGHT = 0x6A6C70;
    private static final int MARK = 0xC4C9CE;
    private static final int LIT = 0xFFB000;
    private static final int INK = 0xE8E8E8;
    /** Distance of the position marks from the middle, a share of the smaller side. */
    private static final float MARK_AT = 0.335f;
    private static final float LEGEND_AT = 0.435f;
    /** Directions of the lever, per position: x and y (down the plate). */
    private static final float[][] DIRECTION = {{0, 0}, {0, -1}, {0, 1}, {-1, 0}, {1, 0}};

    private static float side(PanelComponent c) {
        return Math.min(c.w(), c.h());
    }

    @Override
    public void buildStatic(QuadSink sink, PanelComponent component) {
        float w = component.w();
        float h = component.h();
        float m = side(component);
        float cx = w / 2.0f;
        float cy = h / 2.0f;
        sink.box(0, 0, w, h, 0, 1.0f, PLATE, false);
        Shapes.disc(sink, cx, cy, m * 0.27f, 1.05f, WELL, 24, false);
        for (int i = 1; i < JoystickData.POSITIONS; i++) {
            Shapes.disc(sink, cx + DIRECTION[i][0] * m * MARK_AT, cy + DIRECTION[i][1] * m * MARK_AT, m * 0.045f, 1.1f,
                    QuadSink.shade(MARK, 0.6f), 8, false);
        }
    }

    @Override
    public void renderDynamic(PanelRenderContext context, PanelComponent component) {
        float w = component.w();
        float h = component.h();
        float m = side(component);
        float cx = w / 2.0f;
        float cy = h / 2.0f;
        int position = Math.max(0, Math.min(JoystickData.POSITIONS - 1, (int) Math.round(component.output(JoystickType.OUT_POSITION))));
        float dx = DIRECTION[position][0];
        float dy = DIRECTION[position][1];
        if (position != JoystickData.CENTRE) {
            Shapes.disc(context, cx + dx * m * MARK_AT, cy + dy * m * MARK_AT, m * 0.05f, 1.2f, LIT, 8, true);
        }
        Shapes.disc(context, cx, cy, m * 0.19f, 1.4f, BOOT, 20, false);
        float knobX = cx + dx * m * 0.23f;
        float knobY = cy + dy * m * 0.23f;
        float knobZ = position == JoystickData.CENTRE ? 3.7f : 3.3f;
        if (position != JoystickData.CENTRE) {
            // The shaft: a strip from the foot of the lever in the boot to the knob, rising as it goes.
            float nx = -dy * m * 0.045f;
            float ny = dx * m * 0.045f;
            context.quad(cx - nx, cy - ny, 1.5f, cx + nx, cy + ny, 1.5f, knobX + nx, knobY + ny, knobZ - 0.2f, knobX - nx, knobY - ny,
                    knobZ - 0.2f, LEVER, QuadSink.NORMAL_FACE, false);
        }
        Shapes.disc(context, knobX, knobY, m * 0.12f, knobZ, KNOB, 16, false);
        Shapes.disc(context, knobX - m * 0.03f, knobY - m * 0.03f, m * 0.04f, knobZ + 0.05f, KNOB_HIGHLIGHT, 8, false);
        if (context.lod() >= PanelRenderContext.LOD_FULL) {
            GaugeScratch.of(component).drawText(context, component, this);
        }
    }

    /** The four legends round the well: UP above, DOWN below, LEFT and RIGHT read along the sides. */
    @Override
    public List<Legends.Item> textLayout(PanelComponent component, float rowAspect) {
        JoystickData data = (JoystickData) component.data();
        float m = side(component);
        float cx = component.w() / 2.0f;
        float cy = component.h() / 2.0f;
        List<Legends.Item> items = new ArrayList<>();
        add(items, data.legendUp, cx, cy - m * LEGEND_AT, 0.0f, m);
        add(items, data.legendDown, cx, cy + m * LEGEND_AT, 0.0f, m);
        add(items, data.legendLeft, cx - m * LEGEND_AT, cy, -90.0f, m);
        add(items, data.legendRight, cx + m * LEGEND_AT, cy, 90.0f, m);
        return items;
    }

    private static void add(List<Legends.Item> items, String text, float x, float y, float rotation, float m) {
        if (text.isEmpty()) {
            return;
        }
        float cap = Math.min(m * 0.085f, m * 0.6f / GaugeScale.textCaps(text));
        items.add(new Legends.Item(text, x, y, 1.2f, cap, rotation, BakedPanelModel.Align.CENTER, INK, false, PLATE));
    }
}
