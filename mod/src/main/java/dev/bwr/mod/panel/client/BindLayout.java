package dev.bwr.mod.panel.client;

/**
 * Where the binding screen puts things, as pure arithmetic: a vertical stack of title, device line, port and
 * mode selectors, the current wiring, then (when the mode lists choices) a filter and a selection list, an
 * optional scale field and the buttons. Replace this class to restyle the screen; {@link BindModel} is
 * untouched.
 */
public final class BindLayout {
    public static final int ROW = 22;

    public final int left;
    public final int width;
    public final int top;
    public final int titleY;
    public final int statusY;
    public final int portY;
    public final int modeY;
    public final int currentY;
    public final int filterY;
    public final int listY;
    public final int listHeight;
    public final int scaleY;
    public final int buttonY;
    public final int bottom;

    /**
     * @param currentRows rows used by the "now wired to" lines (each sink gets a remove button)
     * @param listed      whether a filter and selection list are shown
     * @param scaled      whether the scale field is shown
     */
    public BindLayout(int screenWidth, int screenHeight, int currentRows, boolean listed, boolean scaled) {
        this.width = Math.min(340, screenWidth - 20);
        this.left = (screenWidth - width) / 2;
        this.top = 6;
        this.titleY = top + 4;
        this.statusY = titleY + 14;
        this.portY = statusY + 16;
        this.modeY = portY + ROW;
        this.currentY = modeY + ROW + 4;
        int y = currentY + Math.max(1, currentRows) * ROW + 2;
        this.filterY = y;
        int listTop = listed ? y + ROW : y;
        int reserved = (scaled ? ROW : 0) + ROW + 8;
        this.listY = listTop;
        this.listHeight = listed ? Math.max(36, screenHeight - listTop - reserved - 6) : 0;
        this.scaleY = listTop + listHeight + (listed ? 4 : 0);
        this.buttonY = scaleY + (scaled ? ROW : 0) + 4;
        this.bottom = buttonY + ROW;
    }
}
