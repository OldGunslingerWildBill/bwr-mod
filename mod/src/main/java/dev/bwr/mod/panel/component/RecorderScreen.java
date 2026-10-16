package dev.bwr.mod.panel.component;

import java.util.Locale;

/**
 * The screens a paperless recorder shows (the DX's display selection menu). The first five are the DX1000's;
 * {@link #FOUR_PANEL} (four reduced screens at once, with {@link PaperlessRecorderData#quad} choosing the layout) and
 * {@link #CIRCULAR} are the DX2000's.
 */
public enum RecorderScreen {
    TREND, DIGITAL, BAR, OVERVIEW, ALARM_SUMMARY, FOUR_PANEL, CIRCULAR;

    /** The name as saved. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Whether the make has this screen. */
    public boolean availableOn(RecorderStyle style) {
        return style == RecorderStyle.DX2000 || ordinal() <= ALARM_SUMMARY.ordinal();
    }

    /** Whether the screen shows one group's channels (so the left and right arrows change something). */
    public boolean perGroup() {
        return this == TREND || this == DIGITAL || this == BAR || this == FOUR_PANEL || this == CIRCULAR;
    }

    public static RecorderScreen byId(String id) {
        for (RecorderScreen screen : values()) {
            if (screen.id().equals(id)) {
                return screen;
            }
        }
        return TREND;
    }
}
