package dev.bwr.mod.panel.client;

import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.component.PlantComputerData;
import dev.bwr.mod.panel.plant.PlantLayout;
import dev.bwr.mod.panel.plant.PlantScreen;
import net.minecraft.client.resources.language.I18n;

/**
 * The plant computer: a lit screen in a dark bezel. {@link PlantScreen} lays the shown page out (shapes and text in cells);
 * this model only records the shapes into the block's cached mesh and prints the text through the block's text cache.
 *
 * <h3>What is drawn when</h3>
 * Both are cached per component revision, and the revision moves only when a patch (or a save) changes what is shown, which
 * the server sends at most five times a second per display and only when something differs. So a display whose program has
 * not changed costs the renderer nothing but replaying two vertex buffers; one that updates a number a second rebuilds its
 * block's buffers once a second. Nothing is laid out per frame. Beyond the full-detail distance the renderer skips text, and
 * a far display is its coloured shapes only.
 */
final class PlantComputerModel implements ComponentModel {
    private static PlantComputerData data(PlantComputerData d) {
        return d;
    }

    private static PlantComputerData of(PanelComponent component) {
        return (PlantComputerData) component.data();
    }

    /** Records the shapes of the screen into the static mesh. */
    @Override
    public void buildStatic(QuadSink sink, PanelComponent component) {
        PlantComputerData data = of(component);
        sink.box(0.0f, 0.0f, component.w(), component.h(), 0.0f, 0.8f, PlantScreen.BEZEL, false);
        PlantLayout layout = PlantLayout.of(component.w(), component.h(), data.textSize);
        PlantScreen.draw(data.shown(), layout, component.w(), component.h(), data.programMode(), "", "", new PlantScreen.Sink() {
            @Override
            public void rect(float x, float y, float w, float h, float z, int rgb, boolean emissive) {
                sink.rect(x, y, w, h, z, rgb, emissive);
            }

            @Override
            public void line(float x0, float y0, float x1, float y1, float thickness, float z, int rgb, boolean emissive) {
                Lines.draw(sink, x0, y0, x1, y1, thickness, z, rgb, emissive);
            }

            @Override
            public void text(String text, float x, float y, float scale, float z, int rgb) {
            }
        });
    }

    @Override
    public void renderDynamic(PanelRenderContext context, PanelComponent component) {
    }

    @Override
    public boolean legendsCached() {
        return true;
    }

    @Override
    public void renderLegends(PanelRenderContext context, PanelComponent component) {
        emitLegends(context, component);
    }

    /** The text of the screen: one string per label, number and title, at the sizes {@link PlantScreen} chose. */
    @Override
    public void emitLegends(PanelRenderContext context, PanelComponent component) {
        PlantComputerData data = of(component);
        PlantLayout layout = PlantLayout.of(component.w(), component.h(), data.textSize);
        PlantScreen.draw(data.shown(), layout, component.w(), component.h(), data.programMode(),
                I18n.get("text.bwr.plant_computer.no_program"), I18n.get("text.bwr.plant_computer.no_program_hint"),
                new PlantScreen.Sink() {
                    @Override
                    public void rect(float x, float y, float w, float h, float z, int rgb, boolean emissive) {
                    }

                    @Override
                    public void line(float x0, float y0, float x1, float y1, float thickness, float z, int rgb, boolean emissive) {
                    }

                    @Override
                    public void text(String text, float x, float y, float scale, float z, int rgb) {
                        context.text(text, x, y, scale, z, rgb, true);
                    }
                });
    }

    /** A thick line as one quad. */
    static final class Lines {
        private Lines() {
        }

        static void draw(QuadSink sink, float x0, float y0, float x1, float y1, float thickness, float z, int rgb, boolean emissive) {
            float dx = x1 - x0;
            float dy = y1 - y0;
            float length = (float) Math.sqrt(dx * dx + dy * dy);
            if (length < 1.0e-4f) {
                return;
            }
            float nx = -dy / length * thickness / 2.0f;
            float ny = dx / length * thickness / 2.0f;
            // A quad along the line; its ends are squared off, which the points overlap enough to hide.
            sink.quad(x0 - nx, y0 - ny, z, x1 - nx, y1 - ny, z, x1 + nx, y1 + ny, z, x0 + nx, y0 + ny, z, rgb,
                    QuadSink.NORMAL_FACE, emissive);
        }
    }
}
