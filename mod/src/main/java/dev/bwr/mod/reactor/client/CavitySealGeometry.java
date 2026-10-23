package dev.bwr.mod.reactor.client;

import dev.bwr.mod.client.MachineMeshCache;
import dev.bwr.mod.reactor.VesselAppearance;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;

import java.util.Arrays;

/**
 * The cavity seal, drawn as part of the vessel: a flat steel plate from the round flange out to the square footprint and the walls
 * (corner gaps and up to one block of gap included), with a dark bellows collar standing round the flange. Procedural, built once into
 * the vessel's cached mesh (the key includes the seal), so it costs nothing per frame. Not a block.
 */
final class CavitySealGeometry {
    private static final ResourceLocation TEXTURE = ResourceLocation.withDefaultNamespace("block/white_concrete");
    private static final int STEPS = 96;
    private static final float[] STEEL = {.72f, .77f, .79f};
    private static final float[] DARK = {.16f, .18f, .20f};
    /** Inner edge of the plate (the barrel) and the collar's radii, as fractions of the footprint. */
    private static final double INNER = .46;
    private static final double COLLAR_IN = .49;
    private static final double COLLAR_OUT = .53;
    private static final double COLLAR_HEIGHT = .11;
    private static final double THICKNESS = .15;

    private CavitySealGeometry() {
    }

    static void build(MachineMeshCache.Builder b, VesselAppearance.Envelope e) {
        var seal = e.cavity();
        if (seal == null) {
            return;
        }
        var sprite = Minecraft.getInstance().getTextureAtlas(TextureAtlas.LOCATION_BLOCKS).apply(TEXTURE);
        double w = e.width();
        double d = e.depth();
        int mask = seal.gapMask();
        double x0 = -w / 2 - ((mask & 4) != 0 ? 1 : 0);
        double x1 = w / 2 + ((mask & 8) != 0 ? 1 : 0);
        double z0 = -d / 2 - ((mask & 1) != 0 ? 1 : 0);
        double z1 = d / 2 + ((mask & 2) != 0 ? 1 : 0);
        double top = seal.plateTop();
        double thick = seal.plateAtFlange() ? .12 : THICKNESS;
        // Angles round the ellipse, with the four corners of the outer rectangle among them so no quad bends round one.
        double[] angles = new double[STEPS + 4];
        for (int i = 0; i < STEPS; i++) {
            angles[i] = Math.PI * 2 * i / STEPS;
        }
        double[][] corners = {{x0, z0}, {x1, z0}, {x1, z1}, {x0, z1}};
        for (int i = 0; i < 4; i++) {
            double a = Math.atan2(corners[i][1] / (INNER * d), corners[i][0] / (INNER * w));
            angles[STEPS + i] = a < 0 ? a + Math.PI * 2 : a;
        }
        Arrays.sort(angles);
        int n = angles.length;
        double[][] inner = new double[n + 1][];
        double[][] outer = new double[n + 1][];
        double[][] collarIn = new double[n + 1][];
        double[][] collarOut = new double[n + 1][];
        for (int i = 0; i <= n; i++) {
            double a = angles[i % n] + (i == n ? Math.PI * 2 : 0);
            double ex = INNER * w * Math.cos(a);
            double ez = INNER * d * Math.sin(a);
            inner[i] = new double[] {ex, ez};
            double tx = ex > 1e-9 ? x1 / ex : ex < -1e-9 ? x0 / ex : Double.MAX_VALUE;
            double tz = ez > 1e-9 ? z1 / ez : ez < -1e-9 ? z0 / ez : Double.MAX_VALUE;
            double t = Math.min(tx, tz);
            outer[i] = new double[] {ex * t, ez * t};
            collarIn[i] = new double[] {COLLAR_IN * w * Math.cos(a), COLLAR_IN * d * Math.sin(a)};
            collarOut[i] = new double[] {COLLAR_OUT * w * Math.cos(a), COLLAR_OUT * d * Math.sin(a)};
        }
        for (int i = 0; i < n; i++) {
            // Plate: top face, underside, and the rim of the rectangle (against the wall or standing free over the gap).
            quad(b, sprite, STEEL, 0, 1, 0, inner[i], inner[i + 1], outer[i + 1], outer[i], top);
            quad(b, sprite, DARK, 0, -1, 0, inner[i], outer[i], outer[i + 1], inner[i + 1], top - thick);
            wall(b, sprite, STEEL, outer[i], outer[i + 1], top - thick, top, true);
            // Collar: top ring and the two walls.
            double ct = top + COLLAR_HEIGHT;
            quad(b, sprite, DARK, 0, 1, 0, collarIn[i], collarIn[i + 1], collarOut[i + 1], collarOut[i], ct);
            wall(b, sprite, DARK, collarOut[i], collarOut[i + 1], top, ct, true);
            wall(b, sprite, DARK, collarIn[i], collarIn[i + 1], top, ct, false);
        }
    }

    private static void quad(MachineMeshCache.Builder b, TextureAtlasSprite sprite, float[] c, float nx, float ny, float nz,
                             double[] p0, double[] p1, double[] p2, double[] p3, double y) {
        vertex(b, sprite, c, nx, ny, nz, p0[0], y, p0[1]);
        vertex(b, sprite, c, nx, ny, nz, p1[0], y, p1[1]);
        vertex(b, sprite, c, nx, ny, nz, p2[0], y, p2[1]);
        vertex(b, sprite, c, nx, ny, nz, p3[0], y, p3[1]);
    }

    /** A vertical strip between two points of the outline; {@code outward} faces away from the middle of the vessel. */
    private static void wall(MachineMeshCache.Builder b, TextureAtlasSprite sprite, float[] c, double[] a, double[] bPoint,
                             double y0, double y1, boolean outward) {
        double dx = bPoint[0] - a[0];
        double dz = bPoint[1] - a[1];
        double len = Math.hypot(dx, dz);
        if (len < 1e-6) {
            return;
        }
        float nx = (float) (dz / len);
        float nz = (float) (-dx / len);
        double mx = (a[0] + bPoint[0]) / 2;
        double mz = (a[1] + bPoint[1]) / 2;
        // Face away from the middle of the vessel, or towards it for an inward wall.
        boolean pointsOut = nx * mx + nz * mz > 0;
        boolean flip = pointsOut != outward;
        if (flip) {
            nx = -nx;
            nz = -nz;
        }
        // The points run clockwise seen from above, so the counter-clockwise order for the face on the outer side is a bottom, a top,
        // b top, b bottom; for the face looking inwards the other way round.
        if (!flip) {
            vertex(b, sprite, c, nx, 0, nz, a[0], y0, a[1]);
            vertex(b, sprite, c, nx, 0, nz, a[0], y1, a[1]);
            vertex(b, sprite, c, nx, 0, nz, bPoint[0], y1, bPoint[1]);
            vertex(b, sprite, c, nx, 0, nz, bPoint[0], y0, bPoint[1]);
        } else {
            vertex(b, sprite, c, nx, 0, nz, a[0], y0, a[1]);
            vertex(b, sprite, c, nx, 0, nz, bPoint[0], y0, bPoint[1]);
            vertex(b, sprite, c, nx, 0, nz, bPoint[0], y1, bPoint[1]);
            vertex(b, sprite, c, nx, 0, nz, a[0], y1, a[1]);
        }
    }

    private static void vertex(MachineMeshCache.Builder b, TextureAtlasSprite sprite, float[] c, float nx, float ny, float nz,
                               double x, double y, double z) {
        float u = (float) (x - Math.floor(x));
        float v = (float) (z - Math.floor(z));
        b.vertex(x, y, z, sprite.getU(u), sprite.getV(v), nx, ny, nz, c[0], c[1], c[2]);
    }
}
