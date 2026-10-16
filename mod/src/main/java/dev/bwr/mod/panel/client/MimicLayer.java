package dev.bwr.mod.panel.client;

import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.mimic.MimicGeometry;
import dev.bwr.mod.panel.mimic.MimicPath;
import dev.bwr.mod.panel.mimic.MimicStyle;

import java.util.List;

/**
 * Draws the mimic paths of one panel block (spec 3). The paths are flat quads on the face, below the components:
 * <ul>
 *   <li>the <b>baked</b> mesh holds every path: lines, ends, arrows and junction dots. It is rebuilt only when the
 *       paths change or the board's row aspect does, and it goes into the block's GPU buffer with the components'
 *       static geometry ({@link PanelMeshCache}), so a wall with a hundred paths costs one draw call;</li>
 *   <li>the <b>dynamic</b> pass only switches a bound path between dim grey and its colours: while the signal is above
 *       the threshold, a pre-built lit copy of the path is drawn over the grey one. Nothing moves (owner's change
 *       2026-10-16: static lines only).</li>
 * </ul>
 * Paths are indication only: the signal changes colours, nothing else.
 */
final class MimicLayer {
    private MimicLayer() {
    }

    /** The grey of a path whose signal is below its threshold (the same as a dimmed mimic line). */
    private static final int DIM = 0x6E7B72;
    /** Layers (1/16 block above the face), all below the mimic line piece's 0.3 and so below every component. */
    private static final float LINE_Z = 0.04f;
    private static final float RANK_STEP = 0.03f;
    private static final float ARROW_STEP = 0.01f;
    private static final float DOT_Z = 0.16f;
    private static final float LIT_Z = 0.18f;
    private static final float LIT_ARROW_Z = 0.20f;
    private static final float LIT_DOT_Z = 0.22f;

    private static final MimicMesher BAKER = new MimicMesher();

    /** The lit copy of a bound path, drawn over its grey base while the signal is up; built with the baked mesh. */
    private static final class Live {
        StaticMesh lit = StaticMesh.EMPTY;
    }

    private static final class Cache {
        int revision = -1;
        float aspect;
        StaticMesh base = StaticMesh.EMPTY;
        /** One entry per path of the block, in order; null for a path with no signal bound. */
        Live[] live = new Live[0];
        boolean anyLive;
    }

    private static Cache cache(PanelBlockEntity be, float aspect) {
        Cache cache = be.mimicClientCache instanceof Cache existing ? existing : null;
        if (cache == null) {
            cache = new Cache();
            be.mimicClientCache = cache;
        }
        if (cache.revision != be.mimicRevision() || cache.aspect != aspect) {
            build(be, cache, aspect);
        }
        return cache;
    }

    /** What changes the baked mesh, for the block buffer's signature. */
    static long signature(PanelBlockEntity be) {
        return be.mimicCount() == 0 ? 0L : (long) be.mimicRevision() * 31L + be.mimicCount() + 1L;
    }

    /** Records the baked mesh of the block (the context has been begun for the layer, see {@code beginLayer}). */
    static void emitBase(PanelRenderContext context, PanelBlockEntity be) {
        if (be.mimicCount() > 0) {
            cache(be, context.rowAspect()).base.emit(context);
        }
    }

    // ---- building

    private static void build(PanelBlockEntity be, Cache cache, float aspect) {
        List<MimicPath> paths = be.mimicPaths();
        MeshBuilder baked = new MeshBuilder();
        Live[] live = new Live[paths.size()];
        boolean any = false;
        BAKER.begin(baked, aspect, true);
        for (int index = 0; index < paths.size(); index++) {
            MimicPath path = paths.get(index);
            MimicStyle style = path.style();
            PathGeometry geometry = new PathGeometry(path, aspect);
            int rank = index % 4;
            float lineZ = LINE_Z + RANK_STEP * rank;
            int base = path.bound() ? DIM : style.color;
            int arrows = path.bound() ? DIM : style.arrowRgb();
            BAKER.paint(base, lineZ);
            BAKER.body(geometry, style, arrows, lineZ + ARROW_STEP);
            if (style.hasFlowArrows()) {
                BAKER.paint(arrows, lineZ + ARROW_STEP);
                BAKER.flow(geometry, style, style.flowEvery / 2.0f);
            }
            BAKER.paint(base, DOT_Z);
            junctions(BAKER, path, paths, aspect);
            if (path.bound()) {
                live[index] = liveOf(geometry, path, paths, aspect, rank);
                any = true;
            }
        }
        cache.base = baked.build();
        cache.live = live;
        cache.anyLive = any;
        cache.revision = be.mimicRevision();
        cache.aspect = aspect;
    }

    private static Live liveOf(PathGeometry geometry, MimicPath path, List<MimicPath> paths, float aspect, int rank) {
        MimicStyle style = path.style();
        Live live = new Live();
        MeshBuilder lit = new MeshBuilder();
        MimicMesher mesher = new MimicMesher();
        mesher.begin(lit, aspect, true);
        float litZ = LIT_Z + 0.01f * rank;
        mesher.paint(style.color, litZ);
        mesher.body(geometry, style, style.arrowRgb(), LIT_ARROW_Z + 0.01f * rank);
        if (style.hasFlowArrows()) {
            mesher.paint(style.arrowRgb(), LIT_ARROW_Z + 0.01f * rank);
            mesher.flow(geometry, style, style.flowEvery / 2.0f);
        }
        mesher.paint(style.color, LIT_DOT_Z);
        junctions(mesher, path, paths, aspect);
        live.lit = lit.build();
        return live;
    }

    /**
     * A dot on every clicked point of the path that lies on another path (spec 2: T junctions; plain crossings get
     * none). The dot sits on the clicked point itself, which every route and corner shape passes through or meets.
     */
    private static void junctions(MimicMesher mesher, MimicPath path, List<MimicPath> paths, float aspect) {
        if (paths.size() < 2) {
            return;
        }
        float radius = MimicMesher.dotRadius(path.style().width);
        for (int v = 0; v < path.pointCount(); v++) {
            if (MimicGeometry.junction(path, v, paths)) {
                mesher.circle(path.x(v), path.y(v) * aspect, radius);
            }
        }
    }

    // ---- per frame

    /** Draws the lit copy of each bound path whose signal is up; a block with none costs one field read. */
    static void renderDynamic(PanelRenderContext context, PanelBlockEntity be) {
        if (be.mimicCount() == 0) {
            return;
        }
        Cache cache = cache(be, context.rowAspect());
        if (!cache.anyLive) {
            return;
        }
        List<MimicPath> paths = be.mimicPaths();
        context.beginLayer();
        for (int index = 0; index < cache.live.length && index < paths.size(); index++) {
            Live live = cache.live[index];
            if (live != null && paths.get(index).lit()) {
                live.lit.emit(context);
            }
        }
    }
}
