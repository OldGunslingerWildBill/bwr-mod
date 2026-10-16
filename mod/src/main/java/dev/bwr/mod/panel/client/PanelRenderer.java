package dev.bwr.mod.panel.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.bwr.mod.BwrMod;
import dev.bwr.mod.client.VisualEffectsConfig;
import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.component.ComponentType;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.model.PanelModels;
import dev.bwr.mod.registry.BwrBlockEntities;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.List;

/**
 * The one block-entity renderer for all panel blocks: draws every component the
 * block owns, including parts that extend into neighbouring blocks.
 *
 * <h3>Cost model</h3>
 * <ul>
 *   <li>Blocks that own nothing return immediately.</li>
 *   <li>Static geometry is recorded once per component revision
 *       ({@link ComponentModel#buildStatic}) into a {@link StaticMesh}. With the GPU cache on (the default) a
 *       block's recorded meshes and posed geometry sit in one vertex buffer ({@link PanelMeshCache}) that is
 *       rebuilt only when something in it changes and drawn with one call, so a frame runs model code only for
 *       what moves continuously, the legends and the glass ({@link ComponentModel#renderLive}). With it off,
 *       or for a block whose buffer failed, the meshes are replayed and {@link ComponentModel#renderDynamic}
 *       runs per frame.</li>
 *   <li>The frame loop does not allocate: one shared {@link PanelRenderContext},
 *       index loops, cached render bounds.</li>
 *   <li>Level of detail by distance to the block (spec section 10): beyond
 *       {@link #FULL_DETAIL_DISTANCE} blocks no text or fine detail
 *       ({@link PanelRenderContext#LOD_REDUCED}), beyond
 *       {@link #COMPONENT_DISTANCE} no components at all.</li>
 * </ul>
 */
@EventBusSubscriber(modid = BwrMod.MOD_ID, value = Dist.CLIENT)
public final class PanelRenderer implements BlockEntityRenderer<PanelBlockEntity> {
    public static final double FULL_DETAIL_DISTANCE = 24.0;
    public static final double COMPONENT_DISTANCE = 64.0;
    private static final double FULL_SQ = FULL_DETAIL_DISTANCE * FULL_DETAIL_DISTANCE;
    private static final double COMPONENT_SQ = COMPONENT_DISTANCE * COMPONENT_DISTANCE;

    private static final boolean IMMEDIATE = Boolean.getBoolean("bwr.panelImmediate");

    private final PanelRenderContext context = new PanelRenderContext();

    public PanelRenderer(BlockEntityRendererProvider.Context ignored) {
    }

    @SubscribeEvent
    public static void register(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(BwrBlockEntities.PANEL.get(), PanelRenderer::new);
    }

    /** LOD for a block at squared distance {@code distSq} from the camera. */
    public static int lodFor(double distSq) {
        return distSq > COMPONENT_SQ ? PanelRenderContext.LOD_NONE
                : distSq > FULL_SQ ? PanelRenderContext.LOD_REDUCED : PanelRenderContext.LOD_FULL;
    }

    @Override
    public void render(PanelBlockEntity be, float partial, PoseStack pose, MultiBufferSource buffers,
                       int light, int overlay) {
        List<PanelComponent> components = be.owned();
        int count = components.size();
        if (count == 0 && be.mimicCount() == 0) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        Vec3 camera = minecraft.gameRenderer.getMainCamera().getPosition();
        double dx = camera.x - (be.getBlockPos().getX() + 0.5);
        double dy = camera.y - (be.getBlockPos().getY() + 0.5);
        double dz = camera.z - (be.getBlockPos().getZ() + 0.5);
        int lod = lodFor(dx * dx + dy * dy + dz * dz);
        if (lod == PanelRenderContext.LOD_NONE) {
            return;
        }
        float time = be.getLevel() == null ? 0.0f : be.getLevel().getGameTime() + partial;
        context.begin(be, pose, buffers, light, overlay, time, lod, minecraft.font);
        PanelMeshCache cache = gpuCache() ? PanelMeshCache.of(be) : null;
        if (cache == null || !cache.usable()) {
            renderImmediate(be, components, count);
        } else {
            renderCached(be, cache, components, count, pose, light, minecraft.font);
        }
    }

    /** Every vertex of every component goes through the CPU every frame. */
    private void renderImmediate(PanelBlockEntity be, List<PanelComponent> components, int count) {
        float aspect = context.rowAspect();
        if (be.mimicCount() > 0) {
            context.beginLayer();
            MimicLayer.emitBase(context, be);
            MimicLayer.renderDynamic(context, be);
        }
        for (int i = 0; i < count; i++) {
            PanelComponent component = components.get(i);
            ComponentType type = component.type();
            if (type == null) {
                continue;
            }
            ComponentModel model = ComponentModels.get(type.id());
            RenderCache cache = currentMesh(component, model, aspect);
            context.beginComponent(component);
            cache.mesh.emit(context);
            model.renderDynamic(context, component);
        }
    }

    /**
     * The solid geometry of the block comes from its vertex buffer, rebuilt only when the signature of what is in
     * it changes; each frame then draws only what moves continuously, the legends and the glass.
     */
    private void renderCached(PanelBlockEntity be, PanelMeshCache cache, List<PanelComponent> components, int count,
                              PoseStack pose, int light, net.minecraft.client.gui.Font font) {
        float aspect = context.rowAspect();
        long signature = PanelMeshCache.seed(light, PanelModels.generation(), aspect, count);
        signature = PanelMeshCache.mix(signature, MimicLayer.signature(be));
        long legendSignature = signature;
        boolean cachedLegends = false;
        for (int i = 0; i < count; i++) {
            PanelComponent component = components.get(i);
            ComponentType type = component.type();
            if (type == null) {
                continue;
            }
            ComponentModel model = ComponentModels.get(type.id());
            currentMesh(component, model, aspect);
            signature = PanelMeshCache.mix(signature, component.id());
            signature = PanelMeshCache.mix(signature, component.revision());
            signature = PanelMeshCache.mix(signature, model.poseKey(context, component));
            if (model.legendsCached()) {
                // Printed legends depend on the revision only (never on a pose), so a lamp coming on rebuilds no text.
                cachedLegends = true;
                legendSignature = PanelMeshCache.mix(PanelMeshCache.mix(legendSignature, component.id()), component.revision());
            }
        }
        if (!cache.isCurrent(signature)) {
            cache.rebuild(be, components, light, font, signature);
            if (!cache.usable()) {
                // Poses are already read for this frame; the models draw them from the state they hold.
                context.beginLayer();
                MimicLayer.emitBase(context, be);
                for (int i = 0; i < count; i++) {
                    PanelComponent component = components.get(i);
                    ComponentType type = component.type();
                    if (type != null) {
                        context.beginComponent(component);
                        RenderCache.of(component).mesh.emit(context);
                        ComponentModels.get(type.id()).emitPosed(context, component);
                    }
                }
            }
        }
        if (cache.usable()) {
            cache.draw(pose.last());
        }
        MimicLayer.renderDynamic(context, be);
        for (int i = 0; i < count; i++) {
            PanelComponent component = components.get(i);
            ComponentType type = component.type();
            if (type != null) {
                context.beginComponent(component);
                ComponentModels.get(type.id()).renderLive(context, component);
            }
        }
        if (context.lod() < PanelRenderContext.LOD_FULL) {
            return;
        }
        // Text, then glass, each for the whole block: one render type switch each instead of one per component.
        // Models whose legends are static have them in the block's text buffers: captured once, drawn with one call.
        boolean legendBuffers = false;
        if (cachedLegends) {
            if (cache.legendsUsable() && !cache.legendsCurrent(legendSignature)) {
                cache.rebuildLegends(be, components, light, font, legendSignature);
            }
            legendBuffers = cache.legendsUsable();
            if (legendBuffers) {
                cache.drawLegends(pose.last());
            }
        }
        for (int i = 0; i < count; i++) {
            PanelComponent component = components.get(i);
            ComponentType type = component.type();
            if (type != null) {
                ComponentModel model = ComponentModels.get(type.id());
                if (!(legendBuffers && model.legendsCached())) {
                    context.beginComponent(component);
                    model.renderLegends(context, component);
                }
            }
        }
        for (int i = 0; i < count; i++) {
            PanelComponent component = components.get(i);
            ComponentType type = component.type();
            if (type != null) {
                context.beginComponent(component);
                ComponentModels.get(type.id()).renderGlass(context, component);
            }
        }
    }

    /**
     * The component's render cache with its static mesh current: rebuilt only when the component changed (config
     * edit, new component, network state), the set of loaded baked models did (a resource reload), or the panel
     * under it has other cells.
     */
    private static RenderCache currentMesh(PanelComponent component, ComponentModel model, float aspect) {
        RenderCache cache = RenderCache.of(component);
        int generation = PanelModels.generation();
        if (cache.meshRevision != component.revision() || cache.modelGeneration != generation
                || cache.meshRowAspect != aspect) {
            MeshBuilder fresh = new MeshBuilder();
            model.buildStatic(fresh, component, aspect);
            cache.mesh = fresh.build();
            cache.meshRevision = component.revision();
            cache.modelGeneration = generation;
            cache.meshRowAspect = aspect;
        }
        return cache;
    }

    /**
     * Whether blocks keep their solid geometry in vertex buffers: on unless the client option turns it off or
     * {@code -Dbwr.panelImmediate} is set (a development switch to compare against the per frame path). A shader
     * pack does not turn it off: the buffers are drawn through the same render type as the per frame path, which
     * Iris re-routes to the pack's own entity program, and without them a pack costs a fully populated baked wall
     * 36 ms a frame instead of 6 ms (measured under Complementary Reimagined with Sodium and Iris).
     */
    private static boolean gpuCache() {
        return !IMMEDIATE && VisualEffectsConfig.PANEL_GPU_CACHE.get();
    }

    /** Block buffers built so far, for checks. */
    public static int debugRebuilds() {
        return PanelMeshCache.totalRebuilds();
    }

    /** Milliseconds spent building block buffers so far, for checks. */
    public static double debugRebuildMillis() {
        return PanelMeshCache.totalRebuildNanos() / 1.0e6;
    }

    /** Block buffers currently alive, for checks. */
    public static int debugCachedBlocks() {
        return PanelMeshCache.cachedBlocks();
    }

    /** Text vertices held in block legend buffers right now, for checks. */
    public static int debugCachedLegendVertices() {
        return PanelMeshCache.cachedLegendVertices();
    }

    /** Legend captures since the game started, for checks. */
    public static int debugLegendRebuilds() {
        return PanelMeshCache.totalLegendRebuilds();
    }

    /** Quads held in block buffers right now (each is four 36 byte vertices), for checks. */
    public static int debugCachedQuads() {
        return PanelMeshCache.cachedQuads();
    }

    /** Closes the buffers of blocks that stopped being drawn; once per frame, before any block is drawn. */
    @SubscribeEvent
    public static void sweep(RenderLevelStageEvent event) {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_SKY) {
            PanelMeshCache.sweep();
        }
    }

    @Override
    public AABB getRenderBoundingBox(PanelBlockEntity be) {
        return be.renderBounds();
    }

    @Override
    public int getViewDistance() {
        return (int) COMPONENT_DISTANCE + 32;
    }

    @Override
    public boolean shouldRender(PanelBlockEntity be, Vec3 camera) {
        double dx = camera.x - (be.getBlockPos().getX() + 0.5);
        double dy = camera.y - (be.getBlockPos().getY() + 0.5);
        double dz = camera.z - (be.getBlockPos().getZ() + 0.5);
        return (be.ownedCount() > 0 || be.mimicCount() > 0) && dx * dx + dy * dy + dz * dz <= COMPONENT_SQ;
    }
}
