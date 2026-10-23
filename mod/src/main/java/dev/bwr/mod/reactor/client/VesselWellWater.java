package dev.bwr.mod.reactor.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.bwr.mod.reactor.VesselAppearance;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;

/**
 * The water standing in the reactor well and in the annulus down to the cavity seal: one translucent surface at the level the server
 * sends, over the cells of the well at that height, in the same tint the spent fuel pool draws its water. No water blocks, no
 * entities, no per-frame mesh building beyond a handful of quads (one per run of cells).
 */
final class VesselWellWater {
    private static final ResourceLocation WATER = ResourceLocation.withDefaultNamespace("block/water_still");

    private VesselWellWater() {
    }

    /** Local coordinates are those of the vessel renderer: x and z from the middle of the footprint, y up from the bottom of the vessel. */
    static void render(VesselAppearance.Envelope e, double surface, PoseStack pose, MultiBufferSource buffers, int light) {
        var seal = e.cavity();
        if (seal == null || seal.well() == null || !(surface > 0)) {
            return;
        }
        var well = seal.well();
        int bottom = e.min().getY();
        int row = bottom + (int) Math.floor(surface);
        row = Math.max(well.firstRow, Math.min(well.topRow(), row));
        // The surface sits a little under the top of its layer, as the pool's does under its rim.
        double y = Math.min(surface, row + 1 - bottom - 0.06);
        double cx = e.min().getX() + e.width() / 2.0;
        double cz = e.min().getZ() + e.depth() / 2.0;
        double lip = dev.bwr.mod.reactor.VesselWaterGeometry.rim(e);
        boolean inAnnulus = surface < lip - 1e-6;
        TextureAtlasSprite sprite = Minecraft.getInstance().getTextureAtlas(TextureAtlas.LOCATION_BLOCKS).apply(WATER);
        VertexConsumer out = buffers.getBuffer(Sheets.translucentCullBlockSheet());
        PoseStack.Pose p = pose.last();
        for (int z = well.minZ; z < well.minZ + well.sizeZ; z++) {
            int runStart = Integer.MIN_VALUE;
            for (int x = well.minX; x <= well.minX + well.sizeX; x++) {
                boolean water = x < well.minX + well.sizeX && well.cell(row, x, z) && !(inAnnulus && insideVessel(e, x - cx, z - cz));
                if (water && runStart == Integer.MIN_VALUE) {
                    runStart = x;
                } else if (!water && runStart != Integer.MIN_VALUE) {
                    quad(out, p, sprite, runStart - cx, y, z - cz, x - cx, z + 1 - cz, light, 1f);
                    quad(out, p, sprite, runStart - cx, y, z + 1 - cz, x - cx, z - cz, light, -1f);
                    runStart = Integer.MIN_VALUE;
                }
            }
        }
    }

    /** Whether the whole cell lies inside the vessel body, where there is no annulus to show. */
    private static boolean insideVessel(VesselAppearance.Envelope e, double lx, double lz) {
        double rx = 0.44 * e.width();
        double rz = 0.44 * e.depth();
        for (int k = 0; k < 4; k++) {
            double px = lx + (k & 1);
            double pz = lz + (k >> 1);
            if (px * px / (rx * rx) + pz * pz / (rz * rz) > 1.0) {
                return false;
            }
        }
        return true;
    }

    private static void quad(VertexConsumer out, PoseStack.Pose p, TextureAtlasSprite sprite, double x0, double y, double z0, double x1, double z1,
                             int light, float normalY) {
        float u0 = sprite.getU0();
        float u1 = sprite.getU1();
        float v0 = sprite.getV0();
        float v1 = sprite.getV1();
        vertex(out, p, x0, y, z0, u0, v0, light, normalY);
        vertex(out, p, x0, y, z1, u0, v1, light, normalY);
        vertex(out, p, x1, y, z1, u1, v1, light, normalY);
        vertex(out, p, x1, y, z0, u1, v0, light, normalY);
    }

    private static void vertex(VertexConsumer out, PoseStack.Pose p, double x, double y, double z, float u, float v, int light, float normalY) {
        out.addVertex(p, (float) x, (float) y, (float) z).setColor(0.18f, 0.42f, 0.95f, 0.40f).setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(p, 0, normalY, 0);
    }
}
