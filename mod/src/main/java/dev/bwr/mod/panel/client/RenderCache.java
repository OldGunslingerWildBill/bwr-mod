package dev.bwr.mod.panel.client;

import dev.bwr.mod.panel.component.PanelComponent;

/**
 * The client-only cache hung off {@link PanelComponent#clientCache}: the
 * recorded static mesh plus scratch fields a model may fill once per revision
 * (text fit, precomputed ticks). Dropped and rebuilt whenever the component's
 * revision changes.
 */
public final class RenderCache {
    /** Static geometry, valid for {@link #meshRevision}. */
    StaticMesh mesh = StaticMesh.EMPTY;
    int meshRevision = -1;
    /** The {@link dev.bwr.mod.panel.model.PanelModels#generation()} the mesh was built under; a reload rebuilds it. */
    int modelGeneration = -1;
    /** The row aspect (real row length per column width) of the panel the mesh was built for; another one rebuilds it. */
    float meshRowAspect;
    /** The baked model drawing this component, or null for the classic procedural look. Set with the mesh. */
    BakedComponentModel baked;
    dev.bwr.mod.panel.model.PlacedQuadSink classicSink;

    /** Scratch for models; reset implicitly because a new cache is made per revision of the mesh. */
    public int fitRevision = -1;
    public float scale;
    public float textX;
    public float textY;
    /** A second text line (annunciator windows). */
    public float textX2;
    public float textY2;

    /** An annunciator window's joined sides and inside corners ({@link dev.bwr.mod.panel.model.AnnunciatorBox}); 0 for a lone window. */
    public int boxEdges;
    /** The panel group's annunciator state the window was last scanned with (see {@link AnnunciatorBoxes}); null before the first scan. */
    AnnunciatorBoxes.State boxState;
    /** What the static mesh was built for beyond the revision: {@link ComponentModel#staticKey}. */
    long staticKey;

    /** A model's own per-component client state (damped needle position, cached labels). Never saved. */
    public Object scratch;

    /** Whether the component was last drawn from a baked model (false: the classic procedural look). For checks. */
    public boolean usesBaked() {
        return baked != null;
    }

    /** The cache of {@code component}, created on first use. */
    public static RenderCache of(PanelComponent component) {
        if (component.clientCache instanceof RenderCache cache) {
            return cache;
        }
        RenderCache created = new RenderCache();
        component.clientCache = created;
        return created;
    }
}
