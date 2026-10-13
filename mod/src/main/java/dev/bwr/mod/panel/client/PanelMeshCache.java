package dev.bwr.mod.panel.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.logging.LogUtils;
import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.component.ComponentType;
import dev.bwr.mod.panel.component.PanelComponent;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import org.joml.Matrix4f;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * The solid geometry of one panel block, kept in a vertex buffer on the GPU so a frame draws it with one call
 * instead of sending every vertex through the CPU again: the recorded static meshes of the components it owns,
 * and the posed geometry of the baked models (handles, caps, keys, knobs, lenses, flags: parts that only change
 * when a component is operated, see {@link ComponentModel#emitPosed}). Meters' pointers, text and glass stay in
 * the per frame path ({@link ComponentModel#renderLive}).
 *
 * <p>The buffer is rebuilt when its {@code signature} changes: the packed light of the block, the loaded model
 * set, the row aspect, and every component's id, revision and pose key. A rebuild runs the very same drawing code
 * as the per frame path, a {@link PanelRenderContext} with an identity pose into a private buffer, so the two
 * cannot differ; it costs about what one frame of that block used to, and happens only when something changed.
 *
 * <p>Drawing sets up the same render type the per frame path uses ({@link PanelRenderContext#SOLID}) and draws
 * the buffer with the model-view matrix of the camera and the block. Everything pending in the buffer source
 * stays pending and is drawn after, so glass still lands over the bodies behind it.
 *
 * <p>One cache per block entity, made on first draw and closed when the block has not been drawn for
 * {@link #IDLE_NANOS}, is removed, or the level is left. Render thread only.
 */
final class PanelMeshCache implements AutoCloseable {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** A cache whose block was not drawn for this long (nanoseconds) is closed and its video memory freed. */
    static final long IDLE_NANOS = 10_000_000_000L;
    private static final int OVERLAY = net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY;

    private static final Map<PanelBlockEntity, PanelMeshCache> CACHES = new HashMap<>();
    /** Where the vertices of a rebuild are written before they are uploaded; reused, grows to the largest block. */
    private static ByteBufferBuilder scratch = new ByteBufferBuilder(1 << 18);
    private static final PanelRenderContext CAPTURE = new PanelRenderContext();
    private static final PoseStack IDENTITY = new PoseStack();
    private static BufferBuilder building;
    private static final MultiBufferSource CAPTURE_SOURCE = type -> building;
    private static final Matrix4f MODEL_VIEW = new Matrix4f();
    /** Rebuilds since the game started and the time they took, for checks. */
    private static int totalRebuilds;
    private static long totalRebuildNanos;

    private VertexBuffer buffer;
    private int quads;
    private long signature;
    private boolean built;
    /** Set when a rebuild threw: this block draws on the per frame path from then on, and says so once. */
    private boolean failed;
    private long lastUsed;
    /** Rebuilds so far, for checks. */
    private int rebuilds;

    private PanelMeshCache() {
    }

    /** The cache of {@code be}, made on first use, and marked as used now. */
    static PanelMeshCache of(PanelBlockEntity be) {
        PanelMeshCache cache = CACHES.computeIfAbsent(be, ignored -> new PanelMeshCache());
        cache.lastUsed = System.nanoTime();
        return cache;
    }

    /** Closes the caches of blocks that were removed or have not been drawn lately. Once a frame. */
    static void sweep() {
        if (CACHES.isEmpty()) {
            return;
        }
        long now = System.nanoTime();
        Iterator<Map.Entry<PanelBlockEntity, PanelMeshCache>> it = CACHES.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<PanelBlockEntity, PanelMeshCache> entry = it.next();
            if (entry.getKey().isRemoved() || now - entry.getValue().lastUsed > IDLE_NANOS) {
                entry.getValue().close();
                it.remove();
            }
        }
    }

    /** Number of blocks holding a buffer, for checks. */
    static int cachedBlocks() {
        return CACHES.size();
    }

    /** Quads held in buffers right now, for checks. */
    static int cachedQuads() {
        int total = 0;
        for (PanelMeshCache cache : CACHES.values()) {
            total += cache.quads;
        }
        return total;
    }

    /** Rebuilds since the game started, for checks. */
    static int totalRebuilds() {
        return totalRebuilds;
    }

    /** Time spent in rebuilds since the game started, nanoseconds, for checks. */
    static long totalRebuildNanos() {
        return totalRebuildNanos;
    }

    /** How often the buffer of {@code be} was rebuilt (0 when it has none), for checks. */
    static int rebuildCount(PanelBlockEntity be) {
        PanelMeshCache cache = CACHES.get(be);
        return cache == null ? 0 : cache.rebuilds;
    }

    // ---- signature

    /** The start of a signature: what applies to the whole block. */
    static long seed(int light, int modelGeneration, float rowAspect, int componentCount) {
        long h = mix(0x51ED270B3C6A9F41L, light);
        h = mix(h, modelGeneration);
        h = mix(h, Float.floatToIntBits(rowAspect));
        return mix(h, componentCount);
    }

    /** Folds one more value into a signature. */
    static long mix(long h, long value) {
        h ^= value + 0x9E3779B97F4A7C15L + (h << 6) + (h >>> 2);
        return h * 0xFF51AFD7ED558CCDL;
    }

    /** Whether the buffer holds the geometry the signature describes. */
    boolean isCurrent(long signature) {
        return built && this.signature == signature;
    }

    /** False once a rebuild threw: the block is then drawn without the cache. */
    boolean usable() {
        return !failed;
    }

    // ---- building

    /**
     * Rebuilds the buffer from the components' recorded meshes and posed geometry. Every component's mesh must
     * already be current (the renderer makes them so while it works out the signature). A model that throws
     * does not take the frame down: the block is marked unusable and drawn without the cache.
     */
    void rebuild(PanelBlockEntity be, List<PanelComponent> components, int light, Font font, long signature) {
        long started = System.nanoTime();
        MeshData mesh = null;
        boolean clean = false;
        try {
            RenderType type = PanelRenderContext.SOLID;
            BufferBuilder builder = new BufferBuilder(scratch, type.mode(), type.format());
            building = builder;
            CAPTURE.begin(be, IDENTITY, CAPTURE_SOURCE, light, OVERLAY, 0.0f, PanelRenderContext.LOD_FULL, font);
            for (int i = 0; i < components.size(); i++) {
                PanelComponent component = components.get(i);
                ComponentType componentType = component.type();
                if (componentType == null) {
                    continue;
                }
                CAPTURE.beginComponent(component);
                RenderCache.of(component).mesh.emit(CAPTURE);
                ComponentModels.get(componentType.id()).emitPosed(CAPTURE, component);
            }
            mesh = builder.build();
            clean = true;
        } catch (RuntimeException e) {
            failed = true;
            LOGGER.warn("Panel block {} could not build its GPU cache; drawing it without", be.getBlockPos(), e);
        } finally {
            building = null;
            if (!clean) {
                // The scratch buffer may hold half a block: start a clean one.
                scratch.close();
                scratch = new ByteBufferBuilder(1 << 18);
            }
        }
        if (clean) {
            if (mesh == null) {
                quads = 0;
            } else {
                quads = mesh.drawState().vertexCount() / 4;
                if (buffer == null) {
                    buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
                }
                buffer.bind();
                buffer.upload(mesh);
                VertexBuffer.unbind();
            }
            this.signature = signature;
            built = true;
            rebuilds++;
            totalRebuilds++;
            totalRebuildNanos += System.nanoTime() - started;
        }
    }

    // ---- drawing

    /** Draws the buffer for the block whose camera-relative pose is {@code pose}. */
    void draw(PoseStack.Pose pose) {
        if (buffer == null || quads == 0) {
            return;
        }
        RenderType type = PanelRenderContext.SOLID;
        type.setupRenderState();
        MODEL_VIEW.set(RenderSystem.getModelViewMatrix()).mul(pose.pose());
        buffer.bind();
        buffer.drawWithShader(MODEL_VIEW, RenderSystem.getProjectionMatrix(), RenderSystem.getShader());
        VertexBuffer.unbind();
        type.clearRenderState();
    }

    @Override
    public void close() {
        if (buffer != null) {
            buffer.close();
            buffer = null;
        }
        built = false;
        quads = 0;
    }
}
