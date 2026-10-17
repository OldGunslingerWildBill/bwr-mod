package dev.bwr.mod.panel.client;

/**
 * Where the wrench screen puts things, as pure arithmetic: a title, N rows of
 * "label ... widget", then Done/Cancel. Row 0 is always the label field.
 * Replace this class to restyle the screen; {@link ConfigModel} is untouched.
 *
 * <p>When the rows do not fit the screen (a gauge has scale, bands, look and inputs: well over a dozen rows), they
 * sit in a scrolling viewport between the title and the buttons, which stay put: {@link #scrollable} is true and
 * {@link #maxScroll} is how far the rows can move. {@link #rowY} is the unscrolled position; the screen subtracts its
 * scroll offset.
 */
public final class ConfigLayout {
    public final int left;
    public final int top;
    public final int width;
    public final int rowHeight;
    public final int labelWidth;
    public final int widgetX;
    public final int widgetWidth;
    public final int buttonY;
    public final int panelBottom;
    /** The rows' viewport, in screen coordinates. */
    public final int viewTop;
    public final int viewBottom;
    public final boolean scrollable;
    public final int maxScroll;

    public ConfigLayout(int screenWidth, int screenHeight, int rowCount) {
        this.width = Math.min(380, screenWidth - 20);
        this.left = (screenWidth - width) / 2;
        int available = screenHeight - 20 - 24 - 36;
        this.rowHeight = Math.max(18, Math.min(24, available / Math.max(1, rowCount)));
        int content = rowCount * rowHeight;
        int view = Math.max(rowHeight, Math.min(content, available));
        int total = 24 + view + 30;
        this.top = Math.max(4, (screenHeight - total) / 2);
        this.labelWidth = width * 2 / 5;
        this.widgetX = left + labelWidth;
        this.widgetWidth = width - labelWidth;
        this.viewTop = top + 24;
        this.viewBottom = viewTop + view;
        this.scrollable = content > view;
        this.maxScroll = Math.max(0, content - view);
        this.buttonY = viewBottom + 6;
        this.panelBottom = buttonY + 24;
    }

    public int rowY(int row) {
        return top + 24 + row * rowHeight;
    }

    public int titleY() {
        return top + 6;
    }
}
