package dev.bwr.mod.panel.client;

import dev.bwr.mod.panel.component.PanelComponent;

/**
 * Draws one component type, client side. This is the seam where a baked
 * Blender model replaces the procedural placeholder: register a different
 * {@code ComponentModel} for the type id in {@link ComponentModels} and nothing
 * else changes (logic lives in the common {@code ComponentType}).
 *
 * <p>Drawing is split in two so the renderer can cache what does not move:
 * <ul>
 *   <li>{@link #buildStatic} records the body once per {@link PanelComponent#revision()}
 *       (config edits bump it). It must depend only on the component's
 *       config/footprint, never on live values or time.</li>
 *   <li>{@link #renderDynamic} runs every frame for moving parts: needles, lit
 *       lamp lenses, segments, pens, text. It must not allocate: no
 *       {@code new}, no string building, no lambdas; read primitives and cached
 *       fields only.</li>
 * </ul>
 * Geometry is in component space, see {@link QuadSink}. Models are stateless
 * singletons shared by every component of the type.
 */
public interface ComponentModel {
    /** Records the static body into {@code sink}. */
    void buildStatic(QuadSink sink, PanelComponent component);

    /**
     * {@link #buildStatic(QuadSink, PanelComponent)} told the real length of one row relative to one column
     * ({@link PanelRenderContext#rowAspect()}: 1.0 on a vertical board, 1.41 on a benchboard). A model that draws
     * real-proportioned geometry (a baked Blender model) uses it to keep its shape on a sloped face; a model that
     * fills its cells as drawn ignores it, which is what this default does.
     */
    default void buildStatic(QuadSink sink, PanelComponent component, float rowAspect) {
        buildStatic(sink, component);
    }

    /**
     * Draws the moving parts. {@link PanelRenderContext#lod()} says how much
     * detail is worth drawing; skip text and fine detail unless it is
     * {@link PanelRenderContext#LOD_FULL}.
     */
    void renderDynamic(PanelRenderContext context, PanelComponent component);
}
