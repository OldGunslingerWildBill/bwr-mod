package dev.bwr.mod.panel.client;

import com.mojang.blaze3d.platform.NativeImage;
import dev.bwr.mod.panel.component.LcdData;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.display.LcdFont;

/**
 * Character LCD: a dark frame (static) around one dynamic texture that holds
 * the whole dot-matrix picture, six pixels per character across and eight per
 * row down. The texture is repainted only when the shown text or the colours
 * change, then drawn as a single full-bright quad, so a 4 x 40 display costs
 * the same per frame as a 1 x 8 one.
 */
final class LcdModel implements ComponentModel {
    private static final int FRAME = 0x1A1D1F;
    private static final float INSET = 0.2f;

    @Override
    public void buildStatic(QuadSink sink, PanelComponent component) {
        sink.box(0, 0, component.w(), component.h(), 0, 0.8f, FRAME, false);
    }

    @Override
    public void renderDynamic(PanelRenderContext context, PanelComponent component) {
        LcdData data = (LcdData) component.data();
        int cols = data.cols();
        int rows = data.rows();
        String[] shown = data.shownRows(component);
        DisplayTextures.Entry entry = DisplayTextures.acquire(data, cols * LcdFont.ADVANCE, rows * LcdFont.LINE);
        int stamp = data.version() * 31 + data.backlight * 7 + data.ink;
        if (entry.version != stamp) {
            paint(entry.image(), shown, cols, rows, data.backlight, data.ink);
            entry.texture.upload();
            entry.version = stamp;
        }
        context.texturedRect(entry.type, INSET, INSET, component.w() - 2 * INSET, component.h() - 2 * INSET, 0.85f, true);
    }

    private static void paint(NativeImage image, String[] shown, int cols, int rows, int backlight, int ink) {
        int back = abgr(backlight);
        int unlit = abgr(QuadSink.shade(backlight, 0.9f));
        int lit = abgr(ink);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                image.setPixelRGBA(x, y, back);
            }
        }
        for (int row = 0; row < rows; row++) {
            String text = shown[row];
            for (int col = 0; col < cols; col++) {
                char c = col < text.length() ? text.charAt(col) : ' ';
                for (int px = 0; px < LcdFont.WIDTH; px++) {
                    for (int py = 0; py < LcdFont.HEIGHT; py++) {
                        image.setPixelRGBA(col * LcdFont.ADVANCE + px, row * LcdFont.LINE + py,
                                LcdFont.pixel(c, px, py) ? lit : unlit);
                    }
                }
            }
        }
    }

    /** 0xRRGGBB to the 0xAABBGGRR order NativeImage stores. */
    static int abgr(int rgb) {
        return 0xFF000000 | (rgb & 0xFF) << 16 | (rgb & 0xFF00) | (rgb >> 16 & 0xFF);
    }
}
