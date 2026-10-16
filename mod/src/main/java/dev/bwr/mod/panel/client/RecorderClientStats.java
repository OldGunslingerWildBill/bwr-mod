package dev.bwr.mod.panel.client;

import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.component.RecorderUi;
import dev.bwr.mod.panel.model.RecorderLayout;

/**
 * What the client checks may ask of a recorder's client side: how often its screen was repainted, whether the baked
 * bezel model is the one drawing it, the picture last painted, the front-key state, and (with
 * {@code -Dbwr.recorderTiming=true}) the CPU time spent in its render path. Read only apart from the timing counters.
 */
public final class RecorderClientStats {
    private RecorderClientStats() {
    }

    /** Whether the render path measures itself (a development switch; off in play). */
    public static final boolean timing = Boolean.getBoolean("bwr.recorderTiming");
    /** A development switch for the client checks: outline every key's hit box on the face (shows they sit on the keys drawn). */
    public static boolean showHitBoxes;
    static long renderNanos;
    static long renderCalls;
    static long paintNanos;
    static long paintCount;

    /** Screen repaints made for the component so far. */
    public static int paints(PanelComponent component) {
        return RecorderModel.paints(component);
    }

    /** Whether the component is drawn from the baked bezel model (false: the procedural stand-in, or not drawn yet). */
    public static boolean usesBakedModel(PanelComponent component) {
        RecorderModel.State st = RecorderModel.state(component);
        return st.layout != null && st.layout.baked;
    }

    /** The key layout the component is drawn with, or null before it was drawn. */
    public static RecorderLayout layout(PanelComponent component) {
        return RecorderModel.state(component).layout;
    }

    /** A copy of the screen as last painted (0xAABBGGRR), or null before the first paint. */
    public static int[] picture(PanelComponent component) {
        int[] shown = RecorderModel.state(component).shown;
        return shown == null ? null : shown.clone();
    }

    /** The front-key state of the component on this client. */
    public static RecorderUi ui(PanelComponent component) {
        return RecorderModel.state(component).ui;
    }

    public static void resetTiming() {
        renderNanos = 0;
        renderCalls = 0;
        paintNanos = 0;
        paintCount = 0;
    }

    /** Nanoseconds spent in recorder render calls since {@link #resetTiming}, and how many calls. */
    public static long[] renderTotals() {
        return new long[] {renderNanos, renderCalls};
    }

    /** Nanoseconds spent painting screens since {@link #resetTiming}, and how many paints. */
    public static long[] paintTotals() {
        return new long[] {paintNanos, paintCount};
    }
}
