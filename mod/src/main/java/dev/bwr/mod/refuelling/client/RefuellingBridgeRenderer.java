package dev.bwr.mod.refuelling.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.bwr.core.refuelling.BridgeMotion;
import dev.bwr.core.refuelling.BridgeMotion.Phase;
import dev.bwr.core.refuelling.BridgeMotion.Point;
import dev.bwr.mod.BwrMod;
import dev.bwr.mod.client.MachineMeshCache;
import dev.bwr.mod.refuelling.RefuellingBridgeBlockEntity;
import dev.bwr.mod.registry.BwrBlockEntities;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.ModelEvent;
import org.joml.Matrix3f;

/**
 * Draws the refuelling bridge (the refuelling platform) from the console's synced state, as Blender-authored parts
 * ({@code art/models/refuelling_bridge}, exported by {@code tools-export-refuelling-bridge.py}) drawn through
 * {@link MachineMeshCache}: two end trucks with their A-frames on the rails, the girders and walkway between them (one mesh for the
 * whole span: a plain section stretched to it and a detailed one tiled every metre), the blue trolley with its operator platform
 * and reels, the telescoping mast and the grapple, and the assembly it carries. Nothing is a block and nothing is an entity: the
 * pose is the leg the server started (from, to, ticks done, ticks in the leg) advanced by the client clock, so a moving bridge costs
 * the network one packet per phase, not one per tick.
 *
 * <p>Every part is a cached vertex buffer built once (the girder mesh again only when the span changes). A frame only changes the
 * pose a part is drawn at: the bridge along the rails, the trolley across, the mast sections' scale, the reels' and the jaws' angles.
 * The parts are authored for rails along X (the girders running to +Z from the first rail); for rails along Z the frame is turned a
 * quarter turn. Size: 4.2 m along the rails, 6.2 m above the rail block's floor, and as long across as the rails are apart (the girder
 * mesh is {@code cHigh - cLow} blocks long, from rail centre line to rail centre line).
 */
@EventBusSubscriber(modid = BwrMod.MOD_ID, value = Dist.CLIENT)
public final class RefuellingBridgeRenderer implements BlockEntityRenderer<RefuellingBridgeBlockEntity> {

    private static final ResourceLocation FUEL = ResourceLocation.fromNamespaceAndPath(BwrMod.MOD_ID, "block/refuelling_assembly");

    public static final String[] PARTS = {"end_truck", "girder_plain", "girder_tile", "deck_clutter", "plate", "trolley", "reel", "mast_outer",
            "mast_tube", "mast_collar", "grapple_head", "grapple_jaw", "assembly"};

    /** Cache key of a part that does not depend on the span. */
    private record Part(String name) {
    }

    /** Cache key of the girder mesh: the plain section stretched and the tile repeated over this many blocks. */
    private record Span(int blocks) {
    }

    /** Positions and sizes of the model, metres over the rail block's floor (art/models/refuelling_bridge/build_models.py). */
    private static final double REEL_Y = 5.71;
    private static final double REEL_X = 1.0;
    private static final double REEL_Z = 0.55;
    private static final double REEL_CABLE_RADIUS = 0.4;
    /** Where the outer mast tube ends and the inner sections come out: the retracted grapple tip plus the grapple head. */
    private static final double MAST_BOTTOM = BridgeMotion.MAST_HOME_ABOVE_RAIL_M + 0.32;
    private static final double[] INNER_RADIUS = {0.17, 0.14, 0.11};
    /** The grapple's jaws swing this far open (degrees) when the old picture's gap was 0.12 m on each side. */
    private static final double JAW_OPEN_M = 0.12;
    private static final double JAW_OPEN_DEGREES = 21.0;
    /** Shortest spans (blocks) with a maker's plate and with the deck clutter: they do not fit a shorter one. */
    private static final int PLATE_SPAN = 3;
    private static final int CLUTTER_SPAN = 6;

    public static ModelResourceLocation model(String part) {
        return ModelResourceLocation.standalone(ResourceLocation.fromNamespaceAndPath(BwrMod.MOD_ID, "block/refuelling_bridge/" + part + "/body"));
    }

    public RefuellingBridgeRenderer(BlockEntityRendererProvider.Context context) {
    }

    @SubscribeEvent
    public static void models(ModelEvent.RegisterAdditional event) {
        for (String part : PARTS) {
            event.register(model(part));
        }
    }

    @SubscribeEvent
    public static void renderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(BwrBlockEntities.REFUELLING_BRIDGE.get(), RefuellingBridgeRenderer::new);
    }

    @Override
    public void render(RefuellingBridgeBlockEntity be, float partial, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        RefuellingBridgeBlockEntity.Geom g = be.geom();
        Point tip = be.tip(partial);
        if (g == null || tip == null || be.getLevel() == null) {
            return;
        }
        var level = be.getLevel();
        BlockPos root = be.getBlockPos();
        pose.pushPose();
        pose.translate(-root.getX(), -root.getY(), -root.getZ());

        double a = g.alongX() ? tip.x() : tip.z();
        double c = g.alongX() ? tip.z() : tip.x();
        double ry = g.railY();
        double c0 = g.cLow() + 0.5;
        int span = Math.max(1, g.cHigh() - g.cLow());
        double across = c - c0;
        double tipLocal = tip.y() - ry;
        int work = work(LevelRenderer.getLightColor(level, BlockPos.containing(tip.x(), tip.y() + 0.5, tip.z())));
        int trolleyLit = LevelRenderer.getLightColor(level, BlockPos.containing(tip.x(), ry + 4.6, tip.z()));
        double mid = c0 + span / 2.0;
        int girderLit = LevelRenderer.getLightColor(level, BlockPos.containing(g.alongX() ? a : mid, ry + 4.0, g.alongX() ? mid : a));

        // girders, walkway, plate and deck clutter: one mesh for the whole span
        pose.pushPose();
        frame(pose, g, a, c0, ry);
        MachineMeshCache.draw(new Span(span), b -> buildGirders(b, span), pose, buffers, girderLit, overlay);
        pose.popPose();

        // the two end trucks: the second is the same part turned half a turn at the other rail
        for (int end = 0; end < 2; end++) {
            double rail = c0 + (end == 0 ? 0 : span);
            int lit = LevelRenderer.getLightColor(level, BlockPos.containing(g.alongX() ? a : rail, ry + 1.2, g.alongX() ? rail : a));
            pose.pushPose();
            frame(pose, g, a, c0, ry);
            if (end == 1) {
                pose.translate(0, 0, span);
                pose.mulPose(Axis.YP.rotationDegrees(180));
            }
            part(pose, buffers, "end_truck", lit, overlay);
            pose.popPose();
        }

        // the trolley, its reels (they turn with the hoist: the cable paid out is the height the grapple has moved) and the outer mast
        pose.pushPose();
        frame(pose, g, a, c0, ry);
        pose.translate(0, 0, across);
        part(pose, buffers, "trolley", trolleyLit, overlay);
        float reelAngle = (float) (tipLocal / REEL_CABLE_RADIUS);
        for (int side = -1; side <= 1; side += 2) {
            pose.pushPose();
            pose.translate(REEL_X, REEL_Y, side * REEL_Z);
            pose.mulPose(Axis.XP.rotation(reelAngle));
            part(pose, buffers, "reel", trolleyLit, overlay);
            pose.popPose();
        }
        part(pose, buffers, "mast_outer", trolleyLit, overlay);

        // the inner sections slide out of the outer tube, a third of the reach each (a unit tube scaled: the pose carries the size,
        // the normals keep the unscaled matrix so the lighting does not change with the length)
        double reach = Math.max(0.0, BridgeMotion.MAST_HOME_ABOVE_RAIL_M - tipLocal);
        if (reach > 0.005) {
            for (int k = 0; k < INNER_RADIUS.length; k++) {
                double top = MAST_BOTTOM - reach * k / 3.0 + 0.02;
                double bottom = MAST_BOTTOM - reach * (k + 1) / 3.0;
                scaled(pose, bottom, INNER_RADIUS[k], top - bottom, INNER_RADIUS[k]);
                part(pose, buffers, "mast_tube", work, overlay);
                pose.popPose();
                scaled(pose, bottom, INNER_RADIUS[k] + 0.025, 1.0, INNER_RADIUS[k] + 0.025);
                part(pose, buffers, "mast_collar", work, overlay);
                pose.popPose();
            }
        }

        // the grapple: a head, two jaws that swing shut on the handle (open while empty and lowering, shut once latched), the assembly
        pose.translate(0, tipLocal, 0);
        part(pose, buffers, "grapple_head", work, overlay);
        float jawDegrees = (float) (jawOpening(be) / JAW_OPEN_M * JAW_OPEN_DEGREES);
        for (int side = 0; side < 2; side++) {
            pose.pushPose();
            pose.mulPose(Axis.ZP.rotationDegrees(side == 0 ? jawDegrees : -jawDegrees));
            if (side == 1) {
                pose.mulPose(Axis.YP.rotationDegrees(180));
            }
            part(pose, buffers, "grapple_jaw", work, overlay);
            pose.popPose();
        }
        if (!be.carried().isEmpty()) {
            part(pose, buffers, "assembly", work, overlay);
        }
        pose.popPose();

        // hot fuel under water glows blue (the reactor's own Cherenkov strength, from the decay heat), until it is lifted out
        if (!be.carried().isEmpty() && be.glow() > 0 && tip.y() < be.surfaceSeen()) {
            TextureAtlasSprite fuel = Minecraft.getInstance().getTextureAtlas(TextureAtlas.LOCATION_BLOCKS).apply(FUEL);
            glow(buffers.getBuffer(RenderType.eyes(TextureAtlas.LOCATION_BLOCKS)), pose.last(), fuel, tip.x(), tip.y() - BridgeMotion.ASSEMBLY_LENGTH_M, tip.z(),
                    tip.y() - 0.2, be.glow() / 15.0f);
        }
        pose.popPose();
    }

    /** One cached part at the current pose. */
    private static void part(PoseStack pose, MultiBufferSource buffers, String name, int light, int overlay) {
        MachineMeshCache.draw(new Part(name), b -> b.part(model(name), 0, 0, 0, 1, 1, 1), pose, buffers, light, overlay);
    }

    /** The bridge's frame: origin on the first rail's centre line at the bridge's position, X along the rails, Z across to the second rail. */
    private static void frame(PoseStack pose, RefuellingBridgeBlockEntity.Geom g, double a, double c0, double ry) {
        pose.translate(g.alongX() ? a : c0, ry, g.alongX() ? c0 : a);
        if (!g.alongX()) {
            // local +Z (across) points along world +X, local +X (along the rails) along world -Z
            pose.mulPose(Axis.YP.rotationDegrees(90));
        }
    }

    /** Pushes a pose at height y on the mast axis, scaled by (sx, sy, sz), whose normal matrix stays the unscaled one (the cached shader does not renormalise). */
    private static void scaled(PoseStack pose, double y, double sx, double sy, double sz) {
        pose.pushPose();
        pose.translate(0, y, 0);
        Matrix3f normal = new Matrix3f(pose.last().normal());
        pose.scale((float) sx, (float) sy, (float) sz);
        pose.last().normal().set(normal);
    }

    private static void buildGirders(MachineMeshCache.Builder b, int span) {
        b.part(model("girder_plain"), 0, 0, 0, 1, 1, span);
        for (int k = 0; k < span; k++) {
            b.part(model("girder_tile"), 0, 0, k, 1, 1, 1);
        }
        if (span >= PLATE_SPAN) {
            b.part(model("plate"), 0, 0, Math.max(1.0, span * 0.3), 1, 1, 1);
        }
        if (span >= CLUTTER_SPAN) {
            b.part(model("deck_clutter"), 0, 0, 0, 1, 1, 1);
        }
    }

    /** A soft blue shell round the carried assembly: additive, so each vertex is premultiplied by its strength; fading towards the ends. */
    private static void glow(VertexConsumer out, PoseStack.Pose p, TextureAtlasSprite sprite, double x, double y0, double z, double y1, float strength) {
        double h = 0.3;
        double[][] c = {{-h, -h}, {-h, h}, {h, h}, {h, -h}};
        float u = sprite.getU(0.5f);
        float v = sprite.getV(0.5f);
        for (int i = 0; i < 4; i++) {
            double[] a = c[i];
            double[] b = c[(i + 1) % 4];
            float s = 0.5f * strength;
            glowVertex(out, p, x + a[0], y0, z + a[1], u, v, 0.1f * s);
            glowVertex(out, p, x + b[0], y0, z + b[1], u, v, 0.1f * s);
            glowVertex(out, p, x + b[0], y1, z + b[1], u, v, s);
            glowVertex(out, p, x + a[0], y1, z + a[1], u, v, s);
        }
    }

    private static void glowVertex(VertexConsumer out, PoseStack.Pose p, double x, double y, double z, float u, float v, float a) {
        out.addVertex(p, (float) x, (float) y, (float) z).setColor(0.04f * a, 0.6f * a, a, a).setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT).setNormal(p, 0, 1, 0);
    }

    /** Half the gap between the jaws: wide while empty, closing during LATCH and holding shut with an assembly. */
    private static double jawOpening(RefuellingBridgeBlockEntity be) {
        Phase phase = be.shownPhase();
        double open = JAW_OPEN_M;
        if (phase == Phase.LATCH) {
            return open * (1.0 - be.progress());
        }
        if (phase == Phase.RELEASE) {
            return open * be.progress();
        }
        return be.carried().isEmpty() ? open : 0.0;
    }

    /** The mast carries its own work lights: never darker than a lit room, so it can be seen inside a closed vessel. */
    private static int work(int packed) {
        return LightTexture.pack(Math.max(LightTexture.block(packed), 11), Math.max(LightTexture.sky(packed), 6));
    }

    @Override
    public AABB getRenderBoundingBox(RefuellingBridgeBlockEntity be) {
        RefuellingBridgeBlockEntity.Geom g = be.geom();
        if (g == null) {
            return new AABB(be.getBlockPos());
        }
        double x0 = g.alongX() ? g.aLo() - 3 : g.cLow() - 3;
        double x1 = g.alongX() ? g.aHi() + 4 : g.cHigh() + 4;
        double z0 = g.alongX() ? g.cLow() - 3 : g.aLo() - 3;
        double z1 = g.alongX() ? g.cHigh() + 4 : g.aHi() + 4;
        return new AABB(x0, g.railY() - 64, z0, x1, g.railY() + 8, z1);
    }

    @Override
    public int getViewDistance() {
        return 160;
    }

    @Override
    public boolean shouldRenderOffScreen(RefuellingBridgeBlockEntity be) {
        return true;
    }

    @Override
    public boolean shouldRender(RefuellingBridgeBlockEntity be, Vec3 eye) {
        return be.geom() != null && getRenderBoundingBox(be).distanceToSqr(eye) < (double) getViewDistance() * getViewDistance();
    }
}
