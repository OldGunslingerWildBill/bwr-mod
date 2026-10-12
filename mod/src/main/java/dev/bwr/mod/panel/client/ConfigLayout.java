package dev.bwr.mod.panel.client;

/**
 * Where the wrench screen puts things, as pure arithmetic: a title, N rows of
 * "label ... widget", then Done/Cancel. Row 0 is always the label field.
 * Replace this class to restyle the screen; {@link ConfigModel} is untouched.
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

    public ConfigLayout(int screenWidth, int screenHeight, int rowCount) {
        this.width = Math.min(300, screenWidth - 20);
        this.left = (screenWidth - width) / 2;
        int available = screenHeight - 20 - 24 - 36;
        this.rowHeight = Math.max(18, Math.min(24, available / Math.max(1, rowCount)));
        int total = 24 + rowCount * rowHeight + 30;
        this.top = Math.max(4, (screenHeight - total) / 2);
        this.labelWidth = width * 2 / 5;
        this.widgetX = left + labelWidth;
        this.widgetWidth = width - labelWidth;
        this.buttonY = top + 24 + rowCount * rowHeight + 6;
        this.panelBottom = buttonY + 24;
    }

    public int rowY(int row) {
        return top + 24 + row * rowHeight;
    }

    public int titleY() {
        return top + 6;
    }
}
