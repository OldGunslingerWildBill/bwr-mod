package dev.bwr.mod.panel.client;

import com.mojang.blaze3d.platform.NativeImage;
import dev.bwr.mod.panel.component.GraphicDisplayData;
import dev.bwr.mod.panel.component.PanelComponent;

/**
 * Graphic display: a bezel (static) and one full-bright quad textured with the
 * framebuffer. The texture is repainted from the palette-index buffer only when
 * the data's version moved (a patch arrived), never per frame.
 */
final class GraphicDisplayModel implements ComponentModel {
    private static final float INSET = 0.25f;

    @Override
    public void buildStatic(QuadSink sink, PanelComponent component) {
        GraphicDisplayData data = (GraphicDisplayData) component.data();
        sink.box(0, 0, component.w(), component.h(), 0, 0.8f, data.bezel, false);
    }

    @Override
    public void renderDynamic(PanelRenderContext context, PanelComponent component) {
        GraphicDisplayData data = (GraphicDisplayData) component.data();
        int width = data.width();
        int height = data.height();
        if (width <= 0 || height <= 0) {
            return;
        }
        DisplayTextures.Entry entry = DisplayTextures.acquire(data, width, height);
        if (entry.version != data.version()) {
            paint(entry.image(), data, width, height);
            entry.texture.upload();
            entry.version = data.version();
        }
        context.texturedRect(entry.type, INSET, INSET, component.w() - 2 * INSET, component.h() - 2 * INSET, 0.85f, true);
    }

    private static void paint(NativeImage image, GraphicDisplayData data, int width, int height) {
        int[] palette = new int[GraphicDisplayData.PALETTE_SIZE];
        for (int i = 0; i < palette.length; i++) {
            palette[i] = LcdModel.abgr(data.rawPalette(i));
        }
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setPixelRGBA(x, y, palette[data.rawPixel(y * width + x)]);
            }
        }
    }
}
