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

    /** Scratch for models; reset implicitly because a new cache is made per revision of the mesh. */
    public int fitRevision = -1;
    public float scale;
    public float textX;
    public float textY;

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
