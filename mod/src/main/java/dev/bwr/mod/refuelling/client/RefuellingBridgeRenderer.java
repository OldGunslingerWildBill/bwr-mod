package dev.bwr.mod.refuelling.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.bwr.core.refuelling.BridgeMotion;
import dev.bwr.core.refuelling.BridgeMotion.Phase;
import dev.bwr.core.refuelling.BridgeMotion.Point;
import dev.bwr.mod.BwrMod;
import dev.bwr.mod.refuelling.RefuellingBridgeBlockEntity;
import dev.bwr.mod.registry.BwrBlockEntities;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/**
 * Draws the refuelling bridge from the console's synced state: two end trucks on the rails, a girder between them, the
 * trolley on the girder, a four-section telescoping mast and the grapple, and the assembly it carries. Nothing is a block
 * and nothing is an entity: the pose is the leg the server started (from, to, ticks done, ticks in the leg) advanced by the
 * client clock, so a moving bridge costs the network one packet per phase, not one per tick, and an idle bridge costs one
 * box set replayed.
 */
@EventBusSubscriber(modid = BwrMod.MOD_ID, value = Dist.CLIENT)
public final class RefuellingBridgeRenderer implements BlockEntityRenderer<RefuellingBridgeBlockEntity> {

    private static final ResourceLocation STEEL = ResourceLocation.fromNamespaceAndPath(BwrMod.MOD_ID, "block/refuelling_bridge_steel");
    private static final ResourceLocation MAST = ResourceLocation.fromNamespaceAndPath(BwrMod.MOD_ID, "block/refuelling_bridge_mast");
    private static final ResourceLocation FUEL = ResourceLocation.fromNamespaceAndPath(BwrMod.MOD_ID, "block/refuelling_assembly");

    public RefuellingBridgeRenderer(BlockEntityRendererProvider.Context context) {
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
        var atlas = Minecraft.getInstance().getTextureAtlas(TextureAtlas.LOCATION_BLOCKS);
        TextureAtlasSprite steel = atlas.apply(STEEL);
        TextureAtlasSprite mast = atlas.apply(MAST);
        TextureAtlasSprite fuel = atlas.apply(FUEL);
        BlockPos root = be.getBlockPos();
        pose.pushPose();
        pose.translate(-root.getX(), -root.getY(), -root.getZ());
        PoseStack.Pose p = pose.last();
        VertexConsumer out = buffers.getBuffer(Sheets.cutoutBlockSheet());

        double a = g.alongX() ? tip.x() : tip.z();
        double c = g.alongX() ? tip.z() : tip.x();
        double ry = g.railY();
        int lit = LevelRenderer.getLightColor(be.getLevel(), BlockPos.containing(tip.x(), ry + 1.0, tip.z()));
        int work = work(LevelRenderer.getLightColor(be.getLevel(), BlockPos.containing(tip.x(), tip.y() + 0.5, tip.z())));

        // end trucks on the two rails and the two girder beams between them
        double c0 = g.cLow() + 0.5;
        double c1 = g.cHigh() + 0.5;
        for (double rc : new double[]{c0, c1}) {
            box(out, p, steel, g, a - 0.62, ry + 0.31, rc - 0.42, a + 0.62, ry + 0.85, rc + 0.42, lit);
        }
        box(out, p, steel, g, a - 0.5, ry + 0.85, c0, a - 0.17, ry + 1.35, c1, lit);
        box(out, p, steel, g, a + 0.17, ry + 0.85, c0, a + 0.5, ry + 1.35, c1, lit);
        // the trolley rides on the beams
        double topOfMast = ry + 1.35;
        box(out, p, steel, g, a - 0.62, ry + 1.35, c - 0.62, a + 0.62, ry + 1.85, c + 0.62, lit);

        // the mast: four sections that telescope, each a little narrower than the one above
        double tipY = tip.y();
        double bottomOfMast = tipY + 0.32;
        double length = Math.max(0.02, topOfMast - bottomOfMast);
        double[] width = {0.15, 0.125, 0.10, 0.075};
        for (int i = 0; i < 4; i++) {
            double top = topOfMast - length * i / 4.0;
            double bottom = topOfMast - length * (i + 1) / 4.0 - (i < 3 ? 0.02 : 0.0);
            box(out, p, mast, g, tip.x() - width[i], bottom, tip.z() - width[i], tip.x() + width[i], top, tip.z() + width[i], work, true);
        }
        // the grapple: a head and two jaws that close on the handle (open while empty and lowering, shut once latched)
        double jaw = jawOpening(be);
        double x = tip.x();
        double z = tip.z();
        box(out, p, mast, g, x - 0.22, tipY, z - 0.22, x + 0.22, tipY + 0.32, z + 0.22, work, true);
        box(out, p, steel, g, x - 0.06 - jaw, tipY - 0.34, z - 0.2, x - 0.02 - jaw, tipY, z + 0.2, work, true);
        box(out, p, steel, g, x + 0.02 + jaw, tipY - 0.34, z - 0.2, x + 0.06 + jaw, tipY, z + 0.2, work, true);
        // the assembly on the grapple, hanging by its handle
        if (!be.carried().isEmpty()) {
            box(out, p, fuel, g, x - 0.12, tipY - BridgeMotion.ASSEMBLY_LENGTH_M, z - 0.12, x + 0.12, tipY - 0.34, z + 0.12, work, true);
            box(out, p, fuel, g, x - 0.04, tipY - 0.34, z - 0.04, x + 0.04, tipY, z + 0.04, work, true);
        }
        pose.popPose();
    }

    /** Half the gap between the jaws: wide while empty, closing during LATCH and holding shut with an assembly. */
    private static double jawOpening(RefuellingBridgeBlockEntity be) {
        Phase phase = be.shownPhase();
        double open = 0.12;
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

    private static void box(VertexConsumer out, PoseStack.Pose p, TextureAtlasSprite s, RefuellingBridgeBlockEntity.Geom g,
                            double x0, double y0, double z0, double x1, double y1, double z1, int light) {
        box(out, p, s, g, x0, y0, z0, x1, y1, z1, light, false);
    }

    /** One cuboid, six faces. In block-entity coordinates already translated so that the world origin is the pose's. */
    private static void box(VertexConsumer out, PoseStack.Pose p, TextureAtlasSprite s, RefuellingBridgeBlockEntity.Geom g,
                            double xa, double ya, double za, double xb, double yb, double zb, int light, boolean alreadyWorld) {
        double x0 = Math.min(xa, xb);
        double x1 = Math.max(xa, xb);
        double y0 = Math.min(ya, yb);
        double y1 = Math.max(ya, yb);
        double z0 = Math.min(za, zb);
        double z1 = Math.max(za, zb);
        if (!alreadyWorld && !g.alongX()) {
            // bridge boxes are written for rails along x (a = x, c = z); with rails along z the two horizontal axes swap
            double t = x0;
            x0 = z0;
            z0 = t;
            t = x1;
            x1 = z1;
            z1 = t;
        }
        // down
        quad(out, p, s, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, 0, -1, 0, 0.55f, light);
        // up
        quad(out, p, s, x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, y1, z0, 0, 1, 0, 1.0f, light);
        // north (z0)
        quad(out, p, s, x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0, 0, 0, -1, 0.8f, light);
        // south (z1)
        quad(out, p, s, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, 0, 0, 1, 0.8f, light);
        // west (x0)
        quad(out, p, s, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, -1, 0, 0, 0.65f, light);
        // east (x1)
        quad(out, p, s, x1, y0, z1, x1, y0, z0, x1, y1, z0, x1, y1, z1, 1, 0, 0, 0.65f, light);
    }

    private static void quad(VertexConsumer out, PoseStack.Pose p, TextureAtlasSprite s,
                             double ax, double ay, double az, double bx, double by, double bz,
                             double cx, double cy, double cz, double dx, double dy, double dz,
                             float nx, float ny, float nz, float shade, int light) {
        float u0 = s.getU0();
        float u1 = s.getU1();
        float v0 = s.getV0();
        float v1 = s.getV1();
        vertex(out, p, ax, ay, az, u0, v1, nx, ny, nz, shade, light);
        vertex(out, p, bx, by, bz, u1, v1, nx, ny, nz, shade, light);
        vertex(out, p, cx, cy, cz, u1, v0, nx, ny, nz, shade, light);
        vertex(out, p, dx, dy, dz, u0, v0, nx, ny, nz, shade, light);
    }

    private static void vertex(VertexConsumer out, PoseStack.Pose p, double x, double y, double z, float u, float v,
                               float nx, float ny, float nz, float shade, int light) {
        out.addVertex(p, (float) x, (float) y, (float) z).setColor(shade, shade, shade, 1.0f).setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(p, nx, ny, nz);
    }

    @Override
    public AABB getRenderBoundingBox(RefuellingBridgeBlockEntity be) {
        RefuellingBridgeBlockEntity.Geom g = be.geom();
        if (g == null) {
            return new AABB(be.getBlockPos());
        }
        double x0 = g.alongX() ? g.aLo() - 2 : g.cLow() - 2;
        double x1 = g.alongX() ? g.aHi() + 3 : g.cHigh() + 3;
        double z0 = g.alongX() ? g.cLow() - 2 : g.aLo() - 2;
        double z1 = g.alongX() ? g.cHigh() + 3 : g.aHi() + 3;
        return new AABB(x0, g.railY() - 64, z0, x1, g.railY() + 4, z1);
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
