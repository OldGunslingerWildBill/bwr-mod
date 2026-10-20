package dev.bwr.mod.panel.client;

import dev.bwr.mod.panel.component.ControllerStationData;
import dev.bwr.mod.panel.component.ControllerStationType;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.component.StationLayout;
import dev.bwr.mod.panel.display.PanelFormat;
import dev.bwr.mod.panel.model.BakedPanelModel;
import dev.bwr.mod.panel.model.GaugeScale;
import dev.bwr.mod.panel.model.Legends;

import java.util.ArrayList;
import java.util.List;

/**
 * Manual/auto controller station, drawn in code (there is no Blender model): a dark case with a cream scale window that
 * carries the process-variable pointer (red, right of the scale line) and the setpoint pointer (green, left) on one scale,
 * the demand bar beside it (green in AUTO, amber in MAN), the AUTO and MAN lamp pushbuttons, and the RAISE and LOWER caps.
 * Every part sits where {@link StationLayout} says, as a fraction of the component, and every printed length is a share of
 * the component's height, so the real size 3 x 6 station is the 12 x 24 one scaled down. The body is recorded once per
 * revision; the pointers, the bar, the lit lamps and the pressed caps are drawn each frame from the live values.
 */
final class ControllerStationModel implements ComponentModel {
    private static final int HOUSING = 0x23272C;
    private static final int PLATE = 0x2E3439;
    private static final int RECESS = 0x14171A;
    private static final int CARD = 0xE9E5D4;
    private static final int INK = 0x202020;
    private static final int PV = 0xD03010;
    private static final int SP = 0x1E8A3A;
    private static final int AUTO_LAMP = 0x30D060;
    private static final int MAN_LAMP = 0xFFB000;
    private static final int CAP = 0xC9CDD1;
    private static final int CAP_INK = 0x15171A;
    private static final int FLAG = 0xC02020;
    /** Cap heights, shares of the component height. */
    private static final float HEADER_CAP = 0.034f;
    private static final float NUMERAL_CAP = 0.030f;
    private static final float LAMP_CAP = 0.036f;
    private static final float BUTTON_CAP = 0.040f;

    @Override
    public void buildStatic(QuadSink sink, PanelComponent component) {
        float w = component.w();
        float h = component.h();
        sink.box(0, 0, w, h, 0, 1.0f, HOUSING, false);
        sink.rect(w * 0.025f, h * 0.012f, w * 0.95f, h * 0.976f, 1.02f, PLATE, false);
        // The scale window: a dark rim, the cream card, the scale line and its graduations.
        float x0 = StationLayout.WIN_X0 * w;
        float y0 = StationLayout.WIN_Y0 * h;
        float ww = (StationLayout.WIN_X1 - StationLayout.WIN_X0) * w;
        float wh = (StationLayout.WIN_Y1 - StationLayout.WIN_Y0) * h;
        float rim = Math.min(w, h) * 0.02f;
        sink.rect(x0 - rim, y0 - rim, ww + 2 * rim, wh + 2 * rim, 1.04f, RECESS, false);
        sink.rect(x0, y0, ww, wh, 1.08f, CARD, false);
        float axisX = StationLayout.AXIS_X * w;
        float top = StationLayout.scaleY(1.0) * h;
        float bottom = StationLayout.scaleY(0.0) * h;
        float line = h * 0.0045f;
        sink.rect(axisX - line / 2.0f, top, line, bottom - top, 1.12f, INK, false);
        for (int i = 0; i <= 20; i++) {
            boolean major = i % 5 == 0;
            float y = StationLayout.scaleY(i / 20.0) * h;
            float half = (major ? 0.040f : 0.022f) * w;
            float thick = h * (major ? 0.0055f : 0.0035f);
            sink.rect(axisX - half, y - thick / 2.0f, 2 * half, thick, 1.12f, INK, false);
        }
        // The demand bar: a dark channel with its own graduations to the right.
        float bx0 = StationLayout.BAR_X0 * w;
        float bw = (StationLayout.BAR_X1 - StationLayout.BAR_X0) * w;
        sink.rect(bx0 - rim, top - rim, bw + 2 * rim, bottom - top + 2 * rim, 1.1f, RECESS, false);
        sink.rect(bx0, top, bw, bottom - top, 1.14f, 0x2A2E32, false);
        for (int i = 0; i <= 4; i++) {
            float y = StationLayout.scaleY(i / 4.0) * h;
            float len = (i % 2 == 0 ? 0.05f : 0.03f) * w;
            sink.rect(bx0 + bw + rim, y - h * 0.002f, len, h * 0.004f, 1.12f, INK, false);
        }
        // The lamp pushbuttons: a rim, then a dim lens.
        lens(sink, w, h, StationLayout.LEFT_X0, StationLayout.LEFT_X1, AUTO_LAMP);
        lens(sink, w, h, StationLayout.RIGHT_X0, StationLayout.RIGHT_X1, MAN_LAMP);
        // The caps, with their arrows.
        cap(sink, w, h, StationLayout.LEFT_X0, StationLayout.LEFT_X1, true);
        cap(sink, w, h, StationLayout.RIGHT_X0, StationLayout.RIGHT_X1, false);
    }

    private static void lens(QuadSink sink, float w, float h, float u0, float u1, int colour) {
        float rim = Math.min(w, h) * 0.02f;
        float x = u0 * w;
        float y = StationLayout.LENS_Y0 * h;
        float lw = (u1 - u0) * w;
        float lh = (StationLayout.LENS_Y1 - StationLayout.LENS_Y0) * h;
        sink.box(x - rim, y - rim, lw + 2 * rim, lh + 2 * rim, 0.9f, 1.4f, RECESS, false);
        sink.box(x, y, lw, lh, 1.0f, 1.8f, QuadSink.shade(colour, 0.22f), false);
    }

    private static void cap(QuadSink sink, float w, float h, float u0, float u1, boolean up) {
        float rim = Math.min(w, h) * 0.02f;
        float x = u0 * w;
        float y = StationLayout.CAP_Y0 * h;
        float cw = (u1 - u0) * w;
        float ch = (StationLayout.CAP_Y1 - StationLayout.CAP_Y0) * h;
        sink.box(x - rim, y - rim, cw + 2 * rim, ch + 2 * rim, 0.9f, 1.4f, RECESS, false);
        sink.box(x, y, cw, ch, 1.0f, 2.6f, CAP, false);
        // The arrow on the upper half of the cap.
        float cx = x + cw / 2.0f;
        float ay = y + ch * 0.30f;
        float half = Math.min(cw * 0.16f, ch * 0.2f);
        float tip = up ? ay - half : ay + half;
        float base = up ? ay + half : ay - half;
        Shapes.tri(sink, cx, tip, cx - half, base, cx + half, base, 2.65f, CAP_INK, false);
    }

    @Override
    public void renderDynamic(PanelRenderContext context, PanelComponent component) {
        ControllerStationData data = (ControllerStationData) component.data();
        float w = component.w();
        float h = component.h();
        GaugeScratch scratch = GaugeScratch.of(component);
        double pv = component.input(ControllerStationType.IN_PV);
        boolean pvSignal = !Double.isNaN(pv);
        float pvPosition = scratch.follow((float) data.fraction(pv), 0.2, context.gameTime());
        float half = StationLayout.POINTER_HALF * h;
        // Setpoint pointer: green, left of the scale line, pointing right.
        float spY = StationLayout.scaleY(data.fraction(ControllerStationType.shownSetpoint(component))) * h;
        Shapes.tri(context, StationLayout.SP_TIP * w, spY, StationLayout.SP_BASE * w, spY - half, StationLayout.SP_BASE * w, spY + half,
                1.3f, SP, false);
        // Process variable pointer: red, right of the line, pointing left; none without a signal (a red flag instead).
        if (pvSignal) {
            float pvY = StationLayout.scaleY(pvPosition) * h;
            Shapes.tri(context, StationLayout.PV_TIP * w, pvY, StationLayout.PV_BASE * w, pvY - half, StationLayout.PV_BASE * w, pvY + half,
                    1.32f, PV, false);
        } else {
            float flagW = w * 0.20f;
            context.rect(StationLayout.AXIS_X * w - flagW / 2.0f, StationLayout.scaleY(0.0) * h + h * 0.012f, flagW, h * 0.016f, 1.3f, FLAG, false);
        }
        // The demand bar: lit from the bottom to the demand, green in AUTO, amber in MAN.
        float top = StationLayout.scaleY(1.0) * h;
        float bottom = StationLayout.scaleY(0.0) * h;
        double demand = ControllerStationType.shownDemand(component);
        float level = (float) (demand / 100.0) * (bottom - top);
        if (level > 0.0f) {
            context.rect(StationLayout.BAR_X0 * w, bottom - level, (StationLayout.BAR_X1 - StationLayout.BAR_X0) * w, level, 1.2f,
                    data.auto ? AUTO_LAMP : MAN_LAMP, true);
        }
        // The lamps.
        lit(context, w, h, StationLayout.LEFT_X0, StationLayout.LEFT_X1, AUTO_LAMP, data.auto);
        lit(context, w, h, StationLayout.RIGHT_X0, StationLayout.RIGHT_X1, MAN_LAMP, !data.auto);
        // A held cap shows pressed.
        pressed(context, w, h, StationLayout.LEFT_X0, StationLayout.LEFT_X1, component.output(ControllerStationType.OUT_RAISE) > 0.5);
        pressed(context, w, h, StationLayout.RIGHT_X0, StationLayout.RIGHT_X1, component.output(ControllerStationType.OUT_LOWER) > 0.5);
        if (context.lod() >= PanelRenderContext.LOD_FULL) {
            scratch.drawText(context, component, this);
        }
    }

    private static void lit(PanelRenderContext context, float w, float h, float u0, float u1, int colour, boolean on) {
        if (!on) {
            return;
        }
        float edge = Math.min(w, h) * 0.015f;
        context.box(u0 * w + edge, StationLayout.LENS_Y0 * h + edge, (u1 - u0) * w - 2 * edge,
                (StationLayout.LENS_Y1 - StationLayout.LENS_Y0) * h - 2 * edge, 1.0f, 1.9f, colour, true);
    }

    private static void pressed(PanelRenderContext context, float w, float h, float u0, float u1, boolean down) {
        if (!down) {
            return;
        }
        float edge = Math.min(w, h) * 0.015f;
        context.rect(u0 * w + edge, StationLayout.CAP_Y0 * h + edge, (u1 - u0) * w - 2 * edge,
                (StationLayout.CAP_Y1 - StationLayout.CAP_Y0) * h - 2 * edge, 2.7f, MAN_LAMP, true);
    }

    /**
     * The printed text: the SP, PV and OUT headings, the five numerals of the scale, the units, and the legends of the
     * four buttons. Every cap is a share of the component's height, shrunk to the room it has across.
     */
    @Override
    public List<Legends.Item> textLayout(PanelComponent component, float rowAspect) {
        ControllerStationData data = (ControllerStationData) component.data();
        float w = component.w();
        float h = component.h();
        List<Legends.Item> items = new ArrayList<>();
        float header = h * HEADER_CAP;
        float headerY = h * 0.085f;
        add(items, "SP", (StationLayout.SP_BASE + StationLayout.SP_TIP) / 2.0f * w, headerY, 1.2f, fit(header, "SP", 0.13f * w), SP, CARD, BakedPanelModel.Align.CENTER);
        add(items, "PV", (StationLayout.PV_BASE + StationLayout.PV_TIP) / 2.0f * w, headerY, 1.2f, fit(header, "PV", 0.13f * w), PV, CARD, BakedPanelModel.Align.CENTER);
        add(items, "OUT", (StationLayout.BAR_X0 + StationLayout.BAR_X1) / 2.0f * w, headerY, 1.2f, fit(header, "OUT", 0.17f * w), INK, CARD, BakedPanelModel.Align.CENTER);
        // Numerals: the same cap for all five, so the widest sets it.
        String[] numerals = new String[5];
        float cap = h * NUMERAL_CAP;
        float room = (StationLayout.BAR_X0 - 0.015f - StationLayout.NUMERAL_X) * w;
        for (int i = 0; i < 5; i++) {
            numerals[i] = PanelFormat.formatNumber(data.min + data.span() * i / 4.0, "");
            cap = fit(cap, numerals[i], room);
        }
        for (int i = 0; i < 5; i++) {
            add(items, numerals[i], StationLayout.NUMERAL_X * w, StationLayout.scaleY(i / 4.0) * h, 1.2f, cap, INK, CARD, BakedPanelModel.Align.LEFT);
        }
        if (!data.units.isEmpty()) {
            add(items, data.units, StationLayout.AXIS_X * w, (StationLayout.SCALE_BOTTOM + StationLayout.WIN_Y1) / 2.0f * h + h * 0.004f, 1.2f,
                    fit(h * NUMERAL_CAP, data.units, 0.5f * w), INK, CARD, BakedPanelModel.Align.CENTER);
        }
        float lampY = (StationLayout.LENS_Y1 + (StationLayout.CAP_Y0)) / 2.0f * h - h * 0.012f;
        float lampCap = h * LAMP_CAP;
        float lampRoom = (StationLayout.LEFT_X1 - StationLayout.LEFT_X0) * w;
        lampCap = fit(fit(lampCap, "AUTO", lampRoom), "MAN", lampRoom);
        add(items, "AUTO", (StationLayout.LEFT_X0 + StationLayout.LEFT_X1) / 2.0f * w, lampY, 1.1f, lampCap, 0xE8E8E8, HOUSING, BakedPanelModel.Align.CENTER);
        add(items, "MAN", (StationLayout.RIGHT_X0 + StationLayout.RIGHT_X1) / 2.0f * w, lampY, 1.1f, lampCap, 0xE8E8E8, HOUSING, BakedPanelModel.Align.CENTER);
        float buttonY = StationLayout.CAP_Y1 * h - (StationLayout.CAP_Y1 - StationLayout.CAP_Y0) * h * 0.26f;
        float buttonCap = fit(fit(h * BUTTON_CAP, "RAISE", lampRoom * 0.9f), "LOWER", lampRoom * 0.9f);
        add(items, "RAISE", (StationLayout.LEFT_X0 + StationLayout.LEFT_X1) / 2.0f * w, buttonY, 2.75f, buttonCap, CAP_INK, CAP, BakedPanelModel.Align.CENTER);
        add(items, "LOWER", (StationLayout.RIGHT_X0 + StationLayout.RIGHT_X1) / 2.0f * w, buttonY, 2.75f, buttonCap, CAP_INK, CAP, BakedPanelModel.Align.CENTER);
        return items;
    }

    private static void add(List<Legends.Item> items, String text, float x, float y, float z, float cap, int ink, int surface,
                            BakedPanelModel.Align align) {
        items.add(new Legends.Item(text, x, y, z, cap, 0.0f, align, ink, false, surface));
    }

    /** {@code cap}, or the largest cap at which {@code text} is no wider than {@code room} cells. */
    private static float fit(float cap, String text, float room) {
        float caps = Math.max(1.0f, GaugeScale.textCaps(text));
        return Math.min(cap, room / caps);
    }
}
