package dev.bwr.mod.panel.client;

import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.component.SyncMath;
import dev.bwr.mod.panel.component.SynchroscopeData;
import dev.bwr.mod.panel.component.SynchroscopeType;
import dev.bwr.mod.panel.model.BakedPanelModel;
import dev.bwr.mod.panel.model.GaugeScale;
import dev.bwr.mod.panel.model.Legends;

import java.util.ArrayList;
import java.util.List;

/**
 * Synchroscope, drawn in code: a round dial (a dark bezel, a cream face with a full circle of graduations, a zero mark at 12
 * o'clock and SLOW / FAST printed either side) with a pointer that turns by the phase angle or by the slip, and under it a strip with
 * the two synchronising lamps. The dial is the component's width across; the strip is what is left of its height. Every size is a
 * share of the face radius, so a 2 x 3 instrument is the 20 x 26 one scaled down. The pointer's angle is kept on the client
 * ({@link GaugeScratch#needle}, in degrees) and moved each frame by the frame time: dead reckoning with the slip, pulled to
 * the phase ({@link SyncMath}).
 */
final class SynchroscopeModel implements ComponentModel {
    private static final int HOUSING = 0x23282C;
    private static final int RIM = 0x14181B;
    private static final int FACE = 0xE9E5D4;
    private static final int INK = 0x202020;
    private static final int POINTER = 0xD03010;
    private static final int FLAG = 0xC02020;
    private static final int STRIP = 0x1B1F23;
    /** Lag with which the pointer is pulled to a measured phase, seconds. */
    private static final double LAG = 0.12;
    /** Cap height of the printing, a share of the face radius. */
    private static final float CAP = 0.15f;
    /** Sides of a round lamp lens. */
    private static final int LAMP_SEGMENTS = 20;

    private static float radius(PanelComponent component) {
        return component.w() / 2.0f * 0.9f;
    }

    private static float lamp(PanelComponent component) {
        float strip = component.h() - component.w();
        return Math.min(strip * 0.62f, component.w() * 0.26f);
    }

    private static float lampCentreY(PanelComponent component) {
        return component.w() + (component.h() - component.w()) / 2.0f;
    }

    private static float lampCentreX(PanelComponent component, int index) {
        return component.w() * (index == 0 ? 0.30f : 0.70f);
    }

    @Override
    public void buildStatic(QuadSink sink, PanelComponent component) {
        SynchroscopeData data = (SynchroscopeData) component.data();
        float w = component.w();
        float h = component.h();
        float cx = w / 2.0f;
        float cy = w / 2.0f;
        float radius = w / 2.0f;
        float face = radius(component);
        sink.box(0, 0, w, h, 0, 1.0f, HOUSING, false);
        Shapes.disc(sink, cx, cy, radius * 0.97f, 1.02f, RIM, 36, false);
        Shapes.disc(sink, cx, cy, face, 1.05f, FACE, 36, false);
        for (int i = 1; i < 36; i++) { // 0 is the zero mark's place
            boolean major = i % 3 == 0;
            float angle = (float) Math.toRadians(i * 10.0);
            Shapes.spoke(sink, cx, cy, face * (major ? 0.64f : 0.74f), face * 0.86f, angle, face * (major ? 0.04f : 0.022f), 1.12f, INK, false);
        }
        // The zero mark: a solid triangle at 12 o'clock pointing at the in-phase position.
        Shapes.tri(sink, cx, cy - face * 0.57f, cx - face * 0.06f, cy - face * 0.69f, cx + face * 0.06f, cy - face * 0.69f, 1.14f, 0x1E8A3A, false);
        // The lamp strip.
        float stripY = w;
        sink.rect(w * 0.04f, stripY, w * 0.92f, h - w - w * 0.02f, 1.03f, STRIP, false);
        // Round lamp lenses in a bezel, not square pushbutton caps: these two are indicators and nothing here can be pressed.
        float lens = lamp(component);
        for (int i = 0; i < 2; i++) {
            Shapes.disc(sink, lampCentreX(component, i), lampCentreY(component), lens * 0.5f * 1.2f, 1.4f, RIM, LAMP_SEGMENTS, false);
            Shapes.disc(sink, lampCentreX(component, i), lampCentreY(component), lens * 0.5f, 1.8f,
                    QuadSink.shade(i == 0 ? data.lamp1Color : data.lamp2Color, 0.22f), LAMP_SEGMENTS, false);
        }
    }

    @Override
    public void renderDynamic(PanelRenderContext context, PanelComponent component) {
        SynchroscopeData data = (SynchroscopeData) component.data();
        GaugeScratch scratch = GaugeScratch.of(component);
        double phase = component.input(SynchroscopeType.IN_PHASE);
        double slip = component.input(SynchroscopeType.IN_SLIP);
        boolean havePhase = !Double.isNaN(phase);
        boolean haveSlip = !Double.isNaN(slip);
        float now = context.gameTime();
        double dt = Float.isNaN(scratch.lastTime) ? 0.0 : Math.max(0.0, Math.min(1.0, (now - scratch.lastTime) / 20.0));
        scratch.lastTime = now;
        double angle = scratch.needle;
        if (haveSlip) {
            angle = SyncMath.advance(angle, slip, dt);
        }
        if (havePhase) {
            angle = scratch.primed ? SyncMath.follow(angle, phase, dt, LAG) : SyncMath.wrap(phase);
        }
        scratch.primed = havePhase || scratch.primed;
        scratch.needle = (float) angle;
        double shown = havePhase || haveSlip ? angle : 0.0;
        float w = component.w();
        float cx = w / 2.0f;
        float cy = w / 2.0f;
        float face = radius(component);
        float radians = (float) Math.toRadians(data.reverse ? -shown : shown);
        ModelDraw.bar(context, cx, cy, radians, face * 0.80f, face * 0.055f, 1.3f, POINTER, false);
        ModelDraw.bar(context, cx, cy, radians + (float) Math.PI, face * 0.20f, face * 0.055f, 1.3f, POINTER, false);
        Shapes.disc(context, cx, cy, face * 0.09f, 1.4f, 0x303030, 12, false);
        if (!havePhase && !haveSlip) {
            context.rect(cx - face * 0.26f, cy + face * 0.52f, face * 0.52f, face * 0.18f, 1.2f, FLAG, false);
        }
        // The lamps: dark when in phase (left), bright when in phase (right); with no signal both are off.
        if (havePhase || haveSlip) {
            lamp(context, component, 0, data.lamp1Color, SyncMath.darkLamp(shown));
            lamp(context, component, 1, data.lamp2Color, SyncMath.brightLamp(shown));
        }
        if (context.lod() >= PanelRenderContext.LOD_FULL) {
            scratch.drawText(context, component, this);
        }
    }

    private static void lamp(PanelRenderContext context, PanelComponent component, int index, int colour, double brightness) {
        if (brightness < 0.04) {
            return;
        }
        float lens = lamp(component);
        Shapes.disc(context, lampCentreX(component, index), lampCentreY(component), lens * 0.5f * 0.92f, 1.9f,
                QuadSink.shade(colour, (float) (0.22 + 0.78 * brightness)), LAMP_SEGMENTS, true);
    }

    /** SYNC under the zero mark, and SLOW and FAST on the two sides of the lower half. */
    @Override
    public List<Legends.Item> textLayout(PanelComponent component, float rowAspect) {
        float face = radius(component);
        float cx = component.w() / 2.0f;
        float cy = component.w() / 2.0f;
        float cap = face * CAP;
        float room = face * 0.42f;
        List<Legends.Item> items = new ArrayList<>();
        String slow = "SLOW";
        String fast = "FAST";
        float side = Math.min(cap, Math.min(room / GaugeScale.textCaps(slow), room / GaugeScale.textCaps(fast)));
        items.add(new Legends.Item("SYNC", cx, cy - face * 0.34f, 1.2f, Math.min(cap, face * 0.6f / GaugeScale.textCaps("SYNC")), 0.0f,
                BakedPanelModel.Align.CENTER, INK, false, FACE));
        items.add(new Legends.Item(slow, cx - face * 0.10f, cy + face * 0.34f, 1.2f, side, 0.0f, BakedPanelModel.Align.RIGHT, INK, false, FACE));
        items.add(new Legends.Item(fast, cx + face * 0.10f, cy + face * 0.34f, 1.2f, side, 0.0f, BakedPanelModel.Align.LEFT, INK, false, FACE));
        return items;
    }
}
