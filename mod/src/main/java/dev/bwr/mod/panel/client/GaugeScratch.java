package dev.bwr.mod.panel.client;

import dev.bwr.mod.panel.component.GaugeData;
import dev.bwr.mod.panel.component.PanelComponent;

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
    String minLabel = "";
    String maxLabel = "";
    String units = "";
    float minWidth;
    float maxWidth;
    float unitsWidth;

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

    /** Fills the label text and widths for the current config. Runs once per revision. */
    void layoutText(PanelRenderContext context, PanelComponent component, GaugeData data) {
        if (textRevision == component.revision()) {
            return;
        }
        textRevision = component.revision();
        minLabel = dev.bwr.mod.panel.display.PanelFormat.formatNumber(data.min, "");
        maxLabel = dev.bwr.mod.panel.display.PanelFormat.formatNumber(data.max, "");
        units = data.units;
        minWidth = context.font().width(minLabel);
        maxWidth = context.font().width(maxLabel);
        unitsWidth = context.font().width(units);
    }
}
