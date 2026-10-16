package dev.bwr.mod.panel.display;

import java.util.Arrays;

/**
 * A small software canvas the paperless recorder paints its screen into: an {@code int[]} of pixels (ABGR, the order
 * a {@code NativeImage} stores, so the client can copy it as it is), with no Minecraft types so the screens can be
 * rendered and checked without a client.
 *
 * <p>Drawing is in <em>logical</em> units: the DX1000 screen is 320 x 240 and the DX2000's 640 x 480 is the same
 * layout at {@code scale} 2, so a screen is laid out once. {@link #origin} moves the origin and scale for a
 * sub-screen (a quadrant of the DX2000's four-panel display is a 320 x 240 screen at scale 1 inside the 640 x 480
 * canvas). Text is the 5 x 7 {@link LcdFont}: one font pixel is {@code size} logical pixels, a character cell is
 * {@code 6 * size} wide and {@code 8 * size} tall.
 */
public final class RecorderCanvas {
    public final int width;
    public final int height;
    private final int[] pixels;
    private int originX;
    private int originY;
    private int scale = 1;
    private int clipX0;
    private int clipY0;
    private int clipX1;
    private int clipY1;

    public RecorderCanvas(int width, int height) {
        this.width = width;
        this.height = height;
        this.pixels = new int[width * height];
        noClip();
    }

    /** The pixels, row by row, as 0xAABBGGRR. */
    public int[] pixels() {
        return pixels;
    }

    /** 0xRRGGBB to the 0xFFBBGGRR a texture stores. */
    public static int abgr(int rgb) {
        return 0xFF000000 | (rgb & 0xFF) << 16 | (rgb & 0xFF00) | (rgb >> 16 & 0xFF);
    }

    /** 0xAABBGGRR back to 0xRRGGBB (for writing an image file). */
    public static int rgb(int abgr) {
        return (abgr & 0xFF) << 16 | (abgr & 0xFF00) | (abgr >> 16 & 0xFF);
    }

    /** Sets where logical (0, 0) is, in canvas pixels, and how many canvas pixels a logical pixel is. Resets the clip. */
    public void origin(int x, int y, int scale) {
        this.originX = x;
        this.originY = y;
        this.scale = Math.max(1, scale);
        noClip();
    }

    public int scale() {
        return scale;
    }

    /** Restricts drawing to a logical rectangle (intersected with the canvas). */
    public void clip(int x, int y, int w, int h) {
        clipX0 = Math.max(0, originX + x * scale);
        clipY0 = Math.max(0, originY + y * scale);
        clipX1 = Math.min(width, originX + (x + w) * scale);
        clipY1 = Math.min(height, originY + (y + h) * scale);
    }

    public void noClip() {
        clipX0 = 0;
        clipY0 = 0;
        clipX1 = width;
        clipY1 = height;
    }

    public void clear(int rgb) {
        Arrays.fill(pixels, abgr(rgb));
    }

    private void fillPixels(int px0, int py0, int px1, int py1, int color) {
        int x0 = Math.max(px0, clipX0);
        int y0 = Math.max(py0, clipY0);
        int x1 = Math.min(px1, clipX1);
        int y1 = Math.min(py1, clipY1);
        if (x0 >= x1 || y0 >= y1) {
            return;
        }
        for (int y = y0; y < y1; y++) {
            Arrays.fill(pixels, y * width + x0, y * width + x1, color);
        }
    }

    /** A filled logical rectangle. */
    public void fill(int x, int y, int w, int h, int rgb) {
        fillPixels(originX + x * scale, originY + y * scale, originX + (x + w) * scale, originY + (y + h) * scale, abgr(rgb));
    }

    public void hline(int x0, int x1, int y, int rgb) {
        fill(Math.min(x0, x1), y, Math.abs(x1 - x0) + 1, 1, rgb);
    }

    public void vline(int x, int y0, int y1, int rgb) {
        fill(x, Math.min(y0, y1), 1, Math.abs(y1 - y0) + 1, rgb);
    }

    /** A one pixel rectangle outline. */
    public void outline(int x, int y, int w, int h, int rgb) {
        fill(x, y, w, 1, rgb);
        fill(x, y + h - 1, w, 1, rgb);
        fill(x, y, 1, h, rgb);
        fill(x + w - 1, y, 1, h, rgb);
    }

    /** A dashed horizontal line: {@code dash} on, {@code gap} off. */
    public void dashedH(int x0, int x1, int y, int thickness, int dash, int gap, int rgb) {
        for (int x = x0; x <= x1; x += dash + gap) {
            fill(x, y, Math.min(dash, x1 - x + 1), thickness, rgb);
        }
    }

    public void dashedV(int x, int y0, int y1, int thickness, int dash, int gap, int rgb) {
        for (int y = y0; y <= y1; y += dash + gap) {
            fill(x, y, thickness, Math.min(dash, y1 - y + 1), rgb);
        }
    }

    /** A line of {@code width} logical pixels (Bresenham, square pen). */
    public void line(int x0, int y0, int x1, int y1, int width, int rgb) {
        int color = abgr(rgb);
        int dx = Math.abs(x1 - x0);
        int dy = -Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1;
        int sy = y0 < y1 ? 1 : -1;
        int err = dx + dy;
        int x = x0;
        int y = y0;
        int off = (width - 1) / 2;
        while (true) {
            int px = originX + (x - off) * scale;
            int py = originY + (y - off) * scale;
            fillPixels(px, py, px + width * scale, py + width * scale, color);
            if (x == x1 && y == y1) {
                return;
            }
            int e2 = 2 * err;
            if (e2 >= dy) {
                err += dy;
                x += sx;
            }
            if (e2 <= dx) {
                err += dx;
                y += sy;
            }
        }
    }

    /** A filled disc of radius {@code r} logical pixels. */
    public void disc(int cx, int cy, int r, int rgb) {
        for (int y = -r; y <= r; y++) {
            int half = (int) Math.round(Math.sqrt(Math.max(0, r * r + r - y * y)));
            fill(cx - half, cy + y, 2 * half + 1, 1, rgb);
        }
    }

    /** A circle outline of radius {@code r}, one logical pixel thick, drawn as short steps (cheap, good enough for a grid). */
    public void circle(int cx, int cy, int r, int rgb) {
        int steps = Math.max(24, r * 5);
        int lastX = cx + r;
        int lastY = cy;
        for (int i = 1; i <= steps; i++) {
            double a = Math.PI * 2 * i / steps;
            int x = cx + (int) Math.round(Math.cos(a) * r);
            int y = cy + (int) Math.round(Math.sin(a) * r);
            line(lastX, lastY, x, y, 1, rgb);
            lastX = x;
            lastY = y;
        }
    }

    /** A triangle pointing down with its flat top at {@code y}, {@code w} wide and {@code h} tall. */
    public void triangleDown(int x, int y, int w, int h, int rgb) {
        for (int i = 0; i < h; i++) {
            int span = Math.max(1, w - (w * i) / Math.max(1, h));
            fill(x + (w - span) / 2, y + i, span, 1, rgb);
        }
    }

    public void triangleUp(int x, int y, int w, int h, int rgb) {
        for (int i = 0; i < h; i++) {
            int span = Math.max(1, (w * (i + 1)) / Math.max(1, h));
            fill(x + (w - span) / 2, y + i, span, 1, rgb);
        }
    }

    // ---- text

    public static int textWidth(String text, int size) {
        return text.isEmpty() ? 0 : (text.length() * LcdFont.ADVANCE - 1) * size;
    }

    public static int textHeight(int size) {
        return LcdFont.HEIGHT * size;
    }

    /** Draws {@code text} with its top-left at (x, y); returns the x after it. */
    public int text(int x, int y, String text, int rgb, int size) {
        int color = abgr(rgb);
        int cx = x;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != ' ') {
                for (int col = 0; col < LcdFont.WIDTH; col++) {
                    int bits = LcdFont.column(c, col);
                    if (bits == 0) {
                        continue;
                    }
                    for (int row = 0; row < LcdFont.HEIGHT; row++) {
                        if ((bits >> row & 1) != 0) {
                            int px = originX + (cx + col * size) * scale;
                            int py = originY + (y + row * size) * scale;
                            fillPixels(px, py, px + size * scale, py + size * scale, color);
                        }
                    }
                }
            }
            cx += LcdFont.ADVANCE * size;
        }
        return cx;
    }

    public void textRight(int rightX, int y, String text, int rgb, int size) {
        text(rightX - textWidth(text, size), y, text, rgb, size);
    }

    public void textCentered(int centerX, int y, String text, int rgb, int size) {
        text(centerX - textWidth(text, size) / 2, y, text, rgb, size);
    }

    /** The longest prefix of {@code text} that fits {@code maxWidth} logical pixels at {@code size}. */
    public static String fit(String text, int maxWidth, int size) {
        int chars = Math.max(0, (maxWidth / size + 1) / LcdFont.ADVANCE);
        return text.length() <= chars ? text : text.substring(0, chars);
    }
}
