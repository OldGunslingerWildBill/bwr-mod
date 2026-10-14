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

    // The next three are how a model takes part in the panel's GPU cache (PanelMeshCache), which keeps the solid
    // geometry of a block in a vertex buffer: the recorded static meshes, plus any posed geometry a model says
    // only changes when its poseKey does. A model that has none of that keeps these defaults and draws all its
    // moving parts through renderDynamic, every frame, as before.

    /**
     * Reads the component's live state once a frame and returns a key for the posed geometry
     * {@link #emitPosed} would record now; the block's buffer is rebuilt when the key changes. 0 when the model
     * has no posed geometry.
     */
    default long poseKey(PanelRenderContext context, PanelComponent component) {
        return 0L;
    }

    /** Records the posed geometry: parts that change only when the component is operated, in the pose of the last {@link #poseKey}. */
    default void emitPosed(QuadSink sink, PanelComponent component) {
    }

    /**
     * What is drawn every frame when the static meshes and the posed geometry live in the block's buffer: by
     * default everything {@link #renderDynamic} draws. The renderer calls this for every component of a block,
     * then {@link #renderLegends} for every component, then {@link #renderGlass}: each switches the buffer
     * source to another render type, so grouping them keeps a block to a handful of draw calls.
     */
    default void renderLive(PanelRenderContext context, PanelComponent component) {
        renderDynamic(context, component);
    }

    /** Text drawn after the {@link #renderLive} of every component of the block; nothing by default (see there). */
    default void renderLegends(PanelRenderContext context, PanelComponent component) {
    }

    /**
     * Whether this model's printed legends never change while the component's revision and the block's light stay
     * the same, so the block can capture them once into a text vertex buffer ({@link PanelMeshCache}) and draw them
     * with one call instead of laying every string out again each frame. A model that says yes writes its legends
     * in {@link #emitLegends}; the renderer then does not call {@link #renderLegends} for it, unless the capture
     * failed, when {@code renderLegends} is the per frame fallback and should draw the same text.
     */
    default boolean legendsCached() {
        return false;
    }

    /** Draws the legends for capture into the text cache (full detail only: the cache is not drawn beyond that). */
    default void emitLegends(PanelRenderContext context, PanelComponent component) {
    }

    /** Translucent geometry drawn last of all, over everything it covers; nothing by default (see {@link #renderLive}). */
    default void renderGlass(PanelRenderContext context, PanelComponent component) {
    }
}
