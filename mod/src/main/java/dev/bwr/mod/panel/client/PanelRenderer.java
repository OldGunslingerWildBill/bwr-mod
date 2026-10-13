package dev.bwr.mod.panel.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.bwr.mod.BwrMod;
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

import java.util.List;

/**
 * The one block-entity renderer for all panel blocks: draws every component the
 * block owns, including parts that extend into neighbouring blocks.
 *
 * <h3>Cost model</h3>
 * <ul>
 *   <li>Blocks that own nothing return immediately.</li>
 *   <li>Static geometry is recorded once per component revision
 *       ({@link ComponentModel#buildStatic}) into a {@link StaticMesh} and replayed;
 *       only {@link ComponentModel#renderDynamic} runs model code per frame.</li>
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
        if (count == 0) {
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
        for (int i = 0; i < count; i++) {
            PanelComponent component = components.get(i);
            ComponentType type = component.type();
            if (type == null) {
                continue;
            }
            ComponentModel model = ComponentModels.get(type.id());
            RenderCache cache = RenderCache.of(component);
            int generation = PanelModels.generation();
            float aspect = context.rowAspect();
            if (cache.meshRevision != component.revision() || cache.modelGeneration != generation
                    || cache.meshRowAspect != aspect) {
                // Rebuilt only when the component changed (config edit, new component, network state), the
                // set of loaded baked models did (a resource reload), or the panel under it has other cells.
                MeshBuilder fresh = new MeshBuilder();
                model.buildStatic(fresh, component, aspect);
                cache.mesh = fresh.build();
                cache.meshRevision = component.revision();
                cache.modelGeneration = generation;
                cache.meshRowAspect = aspect;
            }
            context.beginComponent(component);
            cache.mesh.emit(context);
            model.renderDynamic(context, component);
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
        return be.ownedCount() > 0 && dx * dx + dy * dy + dz * dz <= COMPONENT_SQ;
    }
}
