package dev.bwr.mod.panel.client;

import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.mimic.MimicGeometry;
import dev.bwr.mod.panel.mimic.MimicPath;
import dev.bwr.mod.panel.mimic.MimicStyle;

import java.util.List;

/**
 * Draws the mimic paths of one panel block (spec 3). The paths are flat quads on the face, below the components:
 * <ul>
 *   <li>the <b>baked</b> mesh holds everything that does not move: the lines, ends, junction dots and, for a path with
 *       no signal bound, its arrows. It is rebuilt only when the paths change or the board's row aspect does, and it
 *       goes into the block's GPU buffer with the components' static geometry ({@link PanelMeshCache}), so a wall with
 *       a hundred paths costs one draw call;</li>
 *   <li>the <b>dynamic</b> pass draws what a bound path does with its signal: the base is dim grey, and while the
 *       signal is above its threshold the same line is drawn again in its colour, with the arrows moving along it (a
 *       function of game time, nothing is sent). A bound path with no signal shows still grey arrows.</li>
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
    /** Cells a moving arrow travels per tick (8 cells a second). */
    private static final float ARROW_SPEED = 0.4f;

    private static final MimicMesher BAKER = new MimicMesher();
    private static final MimicMesher PAINTER = new MimicMesher();

    /** What a bound path needs each frame; built with the baked mesh. */
    private static final class Live {
        StaticMesh lit = StaticMesh.EMPTY;
        StaticMesh dimArrows = StaticMesh.EMPTY;
        StaticMesh litArrows = StaticMesh.EMPTY;
        PathGeometry geometry;
        int rank;
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
            BAKER.paint(base, lineZ);
            BAKER.body(geometry, style);
            if (!path.bound() && style.hasFlowArrows()) {
                BAKER.paint(base, lineZ + ARROW_STEP);
                BAKER.flow(geometry, style, style.flowEvery / 2.0f);
            }
            BAKER.paint(base, DOT_Z);
            junctions(BAKER, geometry, path, paths);
            if (path.bound()) {
                live[index] = liveOf(geometry, path, paths, aspect, rank, lineZ);
                any = true;
            }
        }
        cache.base = baked.build();
        cache.live = live;
        cache.anyLive = any;
        cache.revision = be.mimicRevision();
        cache.aspect = aspect;
    }

    private static Live liveOf(PathGeometry geometry, MimicPath path, List<MimicPath> paths, float aspect, int rank,
                               float lineZ) {
        MimicStyle style = path.style();
        Live live = new Live();
        live.geometry = geometry;
        live.rank = rank;
        MeshBuilder lit = new MeshBuilder();
        MimicMesher mesher = new MimicMesher();
        mesher.begin(lit, aspect, true);
        mesher.paint(style.color, LIT_Z + 0.01f * rank);
        mesher.body(geometry, style);
        mesher.paint(style.color, LIT_DOT_Z);
        junctions(mesher, geometry, path, paths);
        live.lit = lit.build();
        if (style.hasFlowArrows()) {
            MeshBuilder dim = new MeshBuilder();
            mesher.begin(dim, aspect, true);
            mesher.paint(DIM, lineZ + ARROW_STEP);
            mesher.flow(geometry, style, style.flowEvery / 2.0f);
            live.dimArrows = dim.build();
            MeshBuilder still = new MeshBuilder();
            mesher.begin(still, aspect, true);
            mesher.paint(style.color, LIT_ARROW_Z + 0.01f * rank);
            mesher.flow(geometry, style, style.flowEvery / 2.0f);
            live.litArrows = still.build();
        }
        return live;
    }

    /** A dot on every vertex of the path that lies on another path (spec 2: T junctions; plain crossings get none). */
    private static void junctions(MimicMesher mesher, PathGeometry geometry, MimicPath path, List<MimicPath> paths) {
        if (paths.size() < 2) {
            return;
        }
        float radius = MimicMesher.dotRadius(path.style().width);
        for (int v = 0; v < path.pointCount(); v++) {
            if (MimicGeometry.junction(path, v, paths)) {
                mesher.circle(geometry.x[v], geometry.y[v], radius);
            }
        }
    }

    // ---- per frame

    /** Draws what a bound path changes with its signal; a block with none costs one field read. */
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
        boolean painterReady = false;
        for (int index = 0; index < cache.live.length && index < paths.size(); index++) {
            Live live = cache.live[index];
            if (live == null) {
                continue;
            }
            MimicPath path = paths.get(index);
            MimicStyle style = path.style();
            if (!path.lit()) {
                live.dimArrows.emit(context);
                continue;
            }
            live.lit.emit(context);
            if (!style.hasFlowArrows()) {
                continue;
            }
            if (!style.animate) {
                live.litArrows.emit(context);
                continue;
            }
            if (!painterReady) {
                PAINTER.begin(context, context.rowAspect(), true);
                painterReady = true;
            }
            float phase = (context.gameTime() * ARROW_SPEED) % style.flowEvery;
            PAINTER.paint(style.color, LIT_ARROW_Z + 0.01f * live.rank);
            PAINTER.flow(live.geometry, style, phase);
        }
    }
}
