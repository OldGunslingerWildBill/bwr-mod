package dev.bwr.mod.floodlight.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.bwr.mod.BwrMod;
import dev.bwr.mod.client.MachineMeshCache;
import dev.bwr.mod.floodlight.BeamLampBlockEntity;
import dev.bwr.mod.floodlight.XenonFloodlightBlock;
import dev.bwr.mod.registry.BwrBlockEntities;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.ModelEvent;

/**
 * Draws the head of a xenon floodlight (reflector, trunnion, cooling fins, lens) turned to the lamp's aim. The head is one
 * OBJ, authored for a north-facing lamp with its pivot (the trunnion) at the origin, shown as two standalone models: the
 * body, lit by the world, and the lens, drawn full bright while the lamp is lit (its labPBR {@code _s} map makes it emissive
 * under a shader pack). The pose applies the turns the block uses for the beam: facing, then the yaw offset, then the pitch.
 * The base and ballast box are the block's own model; the high-bay and the HPS panel have no moving head.
 */
@EventBusSubscriber(modid = BwrMod.MOD_ID, value = Dist.CLIENT)
public final class XenonFloodlightRenderer implements BlockEntityRenderer<BeamLampBlockEntity> {
    private static final Direction[] SIDES = {Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, null};
    public static final ModelResourceLocation HEAD_BODY = head("head_body");
    public static final ModelResourceLocation LENS_ON = head("head_lens_on");
    public static final ModelResourceLocation LENS_OFF = head("head_lens_off");

    private static ModelResourceLocation head(String name) {
        return ModelResourceLocation.standalone(ResourceLocation.fromNamespaceAndPath(BwrMod.MOD_ID, "block/xenon_floodlight/" + name));
    }

    public XenonFloodlightRenderer(BlockEntityRendererProvider.Context context) {
    }

    @SubscribeEvent
    public static void renderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(BwrBlockEntities.BEAM_LAMP.get(), XenonFloodlightRenderer::new);
    }

    @SubscribeEvent
    public static void models(ModelEvent.RegisterAdditional event) {
        event.register(HEAD_BODY);
        event.register(LENS_ON);
        event.register(LENS_OFF);
    }

    @Override
    public void render(BeamLampBlockEntity lamp, float partial, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        if (!lamp.floodlight()) {
            return;
        }
        boolean lit = lamp.lit();
        pose.pushPose();
        pose.translate(0.5, XenonFloodlightBlock.PIVOT_Y, 0.5);
        pose.mulPose(Axis.YP.rotationDegrees(-BeamLampBlockEntity.yawOf(lamp.facing())));
        pose.mulPose(Axis.YP.rotationDegrees(-lamp.yawDegrees()));
        pose.mulPose(Axis.XP.rotationDegrees(lamp.pitchDegrees()));
        MachineMeshCache.model(HEAD_BODY, pose, buffers, light, overlay);
        if (lit && buffers instanceof MultiBufferSource.BufferSource) {
            // Vanilla: the machine mesh program shades by the normal (a lens tilted down came out at about 40 %), so the lit
            // lens goes into the terrain cutout layer, whose program has no diffuse shading (the light map at full bright only).
            var vertices = buffers.getBuffer(RenderType.cutout());
            var model = Minecraft.getInstance().getModelManager().getModel(LENS_ON);
            RandomSource random = RandomSource.create(42);
            for (Direction side : SIDES) {
                random.setSeed(42);
                for (BakedQuad quad : model.getQuads(null, side, random, ModelData.EMPTY, RenderType.solid())) {
                    vertices.putBulkData(pose.last(), quad, 1f, 1f, 1f, 1f, LightTexture.FULL_BRIGHT, overlay);
                }
            }
        } else if (lit) {
            // A shader pack's wrapper: the pack's program shades it, and the lens's labPBR _s map makes it emissive.
            MachineMeshCache.model(LENS_ON, pose, buffers, LightTexture.FULL_BRIGHT, overlay);
        } else {
            MachineMeshCache.model(LENS_OFF, pose, buffers, light, overlay);
        }
        pose.popPose();
    }

    @Override
    public AABB getRenderBoundingBox(BeamLampBlockEntity lamp) {
        return new AABB(lamp.getBlockPos()).inflate(1.0);
    }
}
