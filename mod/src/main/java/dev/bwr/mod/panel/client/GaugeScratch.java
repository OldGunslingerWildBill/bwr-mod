package dev.bwr.mod.panel.client;

import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.model.Legends;

/**
 * Per-component client state of a gauge, kept in {@link RenderCache#scratch}:
 * the damped needle (and demand marker) positions and the text layout of the
 * labels, worked out once per revision.
 */
final class GaugeScratch {
    float needle;
    float marker;
    float lastTime = Float.NaN;
    boolean primed;
    boolean markerPrimed;
    float dt;

    int textRevision = -1;
    /** The classic look's printed text for {@link #textRevision}, and each string's width in font pixels. */
    Legends.Item[] text = new Legends.Item[0];
    float[] textWidth = new float[0];

    static GaugeScratch of(PanelComponent component) {
        RenderCache cache = RenderCache.of(component);
        if (cache.scratch instanceof GaugeScratch existing) {
            return existing;
        }
        GaugeScratch created = new GaugeScratch();
        cache.scratch = created;
        return created;
    }

    /**
     * Moves the needle toward {@code target} (0..1) with a first order lag of {@code damping} seconds, using the
     * frame time. Returns the new position. The first call, and a damping of zero, snap to the target.
     */
    float follow(float target, double damping, float time) {
        dt = Float.isNaN(lastTime) ? 0.0f : Math.max(0.0f, Math.min(20.0f, time - lastTime)) / 20.0f;
        lastTime = time;
        if (!primed || damping <= 0.001) {
            primed = true;
            needle = target;
        } else {
            needle += (target - needle) * (1.0f - (float) Math.exp(-dt / damping));
        }
        return needle;
    }

    /** Same lag for the demand marker, using the frame time of the last {@link #follow}. */
    float followMarker(float target, double damping) {
        if (!markerPrimed || damping <= 0.001) {
            markerPrimed = true;
            marker = target;
        } else {
            marker += (target - marker) * (1.0f - (float) Math.exp(-dt / damping));
        }
        return marker;
    }

    /**
     * Draws the classic look's printed text, laid out by {@code model}'s own {@link ComponentModel#textLayout} once per
     * revision (in the classic look's cells, which the context maps onto the panel). Full detail only.
     */
    void drawText(PanelRenderContext context, PanelComponent component, ComponentModel model) {
        if (textRevision != component.revision()) {
            textRevision = component.revision();
            text = model.textLayout(component, 1.0f).toArray(new Legends.Item[0]);
            textWidth = new float[text.length];
            for (int i = 0; i < text.length; i++) {
                textWidth[i] = context.font().width(text[i].text());
            }
        }
        for (int i = 0; i < text.length; i++) {
            Legends.Item item = text[i];
            float dx = switch (item.align()) {
                case LEFT -> 0.0f;
                case CENTER -> -textWidth[i] / 2.0f;
                case RIGHT -> -textWidth[i];
            };
            context.textRotated(item.text(), item.x(), item.y(), item.capCells() / Legends.CAP_PIXELS, item.rotation(),
                    item.z(), item.ink(), false, dx, -Legends.CAP_PIXELS / 2.0f);
        }
    }
}
